package fr.soaresden.diallo

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf

/**
 * Service requis pour que Di-Allô soit le téléphone par défaut.
 * Gère plusieurs appels : double appel (attente / basculer), conférence.
 */
@Suppress("DEPRECATION")
class HyperInCallService : InCallService() {

    private val everActive = HashSet<Call>()

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) = refresh()
        override fun onDetailsChanged(call: Call, details: Call.Details) = refresh()
        override fun onChildrenChanged(call: Call, children: MutableList<Call>) = refresh()
        override fun onParentChanged(call: Call, parent: Call?) = refresh()
        override fun onConferenceableCallsChanged(call: Call, conferenceableCalls: MutableList<Call>) = refresh()
    }

    /** Recalcule l'appel « principal » affiché et l'état de tout le monde. */
    private fun refresh() {
        val live = liveCalls()
        live.filter { it.state == Call.STATE_ACTIVE }.forEach { everActive.add(it) }
        val top = live.filter { it.parent == null }
        val p = top.firstOrNull { it.state == Call.STATE_RINGING }
            ?: top.firstOrNull { it.state == Call.STATE_ACTIVE || it.state == Call.STATE_DIALING || it.state == Call.STATE_CONNECTING || it.state == Call.STATE_NEW || it.state == Call.STATE_SELECT_PHONE_ACCOUNT }
            ?: top.firstOrNull { it.state == Call.STATE_HOLDING }
            ?: top.lastOrNull()
        if (p != null) { current.value = p; callState.intValue = p.state; contact.value = names[numberOf(p)] }
        else callState.intValue = Call.STATE_DISCONNECTED
        version.intValue++

        if (top.any { it.state == Call.STATE_RINGING }) RingGestures.start(this) else RingGestures.stop()
        if (live.any { it.state == Call.STATE_ACTIVE }) { if (!AutoSpeaker.active.value) AutoSpeaker.start(this) }
        if (live.isEmpty()) AutoSpeaker.stop()
        CallNotifier.update(this)
    }

    override fun onCreate() { super.onCreate(); CrashLog.install(this); instance = this; DataFolder.init(this) }
    override fun onDestroy() { AutoSpeaker.stop(); RingGestures.stop(); CallNotifier.cancel(this); if (instance === this) instance = null; super.onDestroy() }

    override fun onCallAdded(call: Call) {
        if (Companion.calls.isEmpty()) AutoSpeaker.stop()
        Companion.calls.add(call)
        call.registerCallback(callback)
        val number = numberOf(call)
        refresh()
        if (number.isNotBlank() && !names.containsKey(number)) Thread {
            val c = lookupContact(this, number)
            main.post { if (c != null) names[number] = c; refresh() }
        }.start()
        if (call.parent == null) startActivity(
            Intent(this, OngoingCallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(callback)
        Companion.calls.remove(call)
        RingGestures.onCallEnded(this, call, everActive.remove(call))
        if (Companion.calls.isEmpty()) {
            current.value = null; callState.intValue = Call.STATE_DISCONNECTED; version.intValue++
            AutoSpeaker.stop(); RingGestures.stop(); CallNotifier.cancel(this)
        } else refresh()
    }

    /** Haut-parleur / écouteur / Bluetooth / micro coupé : l'écran d'appel suit ces changements. */
    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        audio.value = audioState
        CallNotifier.update(this)
    }

    companion object {
        /** États observables par Compose : l'écran d'appel réagit instantanément. */
        val calls = mutableStateListOf<Call>()
        val current = mutableStateOf<Call?>(null)
        val callState = mutableIntStateOf(Call.STATE_NEW)
        val audio = mutableStateOf<CallAudioState?>(null)
        /** Nom + photo du correspondant principal (null = inconnu). */
        val contact = mutableStateOf<Pair<String, String?>?>(null)
        /** Noms connus par numéro (pour le 2e appel et les participants de conférence). */
        val names = mutableStateMapOf<String, Pair<String, String?>>()
        /** Incrémenté à chaque changement d'un appel (les objets Call ne sont pas observables). */
        val version = mutableIntStateOf(0)
        @Volatile private var instance: HyperInCallService? = null
        private val main = Handler(Looper.getMainLooper())

        fun numberOf(c: Call): String = c.details?.handle?.schemeSpecificPart ?: ""
        fun isConference(c: Call) = c.details?.hasProperty(Call.Details.PROPERTY_CONFERENCE) == true || c.children.isNotEmpty()
        fun nameOf(c: Call): String {
            if (isConference(c)) return "Conférence"
            val n = numberOf(c)
            return names[n]?.first ?: if (n.isBlank()) "Numéro masqué" else displayNumber(n)
        }
        fun photoOf(c: Call): String? = names[numberOf(c)]?.second

        private fun liveCalls() = calls.filter { it.state != Call.STATE_DISCONNECTED && it.state != Call.STATE_DISCONNECTING }
        private fun topCalls() = liveCalls().filter { it.parent == null }
        /** Les autres appels (en attente…) que le principal. */
        val others: List<Call> get() = topCalls().filter { it != current.value }
        val ringingCall: Call? get() = topCalls().firstOrNull { it.state == Call.STATE_RINGING }
        val inCall get() = current.value != null && callState.intValue != Call.STATE_DISCONNECTED

        fun answer() { ringingCall?.answer(VideoProfile.STATE_AUDIO_ONLY) }
        /** Double appel : raccroche l'appel en cours puis décroche le nouveau. */
        fun answerEndingOthers() {
            val r = ringingCall ?: return
            topCalls().filter { it != r }.forEach { it.disconnect() }
            main.postDelayed({ if (r.state == Call.STATE_RINGING) r.answer(VideoProfile.STATE_AUDIO_ONLY) }, 350)
        }
        fun hangup() { current.value?.let { if (it.state == Call.STATE_RINGING) it.reject(false, null) else it.disconnect() } }
        /** Refuse l'appel entrant en envoyant un SMS (envoyé par l'appli SMS par défaut). */
        fun rejectWithSms(msg: String) { ringingCall?.reject(true, msg) }

        /** Bascule entre l'appel en cours et celui en attente. */
        fun swap() { topCalls().firstOrNull { it.state == Call.STATE_HOLDING }?.unhold() }
        val canMerge: Boolean get() {
            val a = topCalls().firstOrNull { it.state == Call.STATE_ACTIVE } ?: return false
            val h = topCalls().firstOrNull { it.state == Call.STATE_HOLDING }
            return (h != null && a.conferenceableCalls.contains(h)) || a.details?.can(Call.Details.CAPABILITY_MERGE_CONFERENCE) == true
        }
        fun merge() {
            val a = topCalls().firstOrNull { it.state == Call.STATE_ACTIVE } ?: return
            val h = topCalls().firstOrNull { it.state == Call.STATE_HOLDING }
            if (h != null && a.conferenceableCalls.contains(h)) a.conference(h) else a.mergeConference()
        }

        val speakerOn get() = audio.value?.route == CallAudioState.ROUTE_SPEAKER
        val bluetoothOn get() = audio.value?.route == CallAudioState.ROUTE_BLUETOOTH
        val bluetoothAvailable get() = (audio.value?.supportedRouteMask ?: 0) and CallAudioState.ROUTE_BLUETOOTH != 0
        val muted get() = audio.value?.isMuted == true

        fun setSpeaker(on: Boolean, manual: Boolean = true) {
            if (manual) AutoSpeaker.manualOverride()
            instance?.setAudioRoute(if (on) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_WIRED_OR_EARPIECE)
        }
        fun setBluetooth(on: Boolean) { AutoSpeaker.manualOverride(); instance?.setAudioRoute(if (on) CallAudioState.ROUTE_BLUETOOTH else CallAudioState.ROUTE_WIRED_OR_EARPIECE) }
        fun setMuted(on: Boolean) { instance?.setMuted(on) }
        fun setHold(on: Boolean) { current.value?.let { if (on) it.hold() else it.unhold() } }

        /** Tonalité DTMF envoyée au correspondant (serveurs vocaux, codes…). */
        fun dtmf(c: Char) {
            val call = current.value ?: return
            call.playDtmfTone(c)
            main.postDelayed({ try { call.stopDtmfTone() } catch (_: Throwable) {} }, 180)
        }

        /** Nom + photo du contact pour ce numéro. */
        fun lookupContact(ctx: Context, number: String): Pair<String, String?>? = try {
            if (number.isBlank()) null else {
                val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
                ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.PHOTO_URI), null, null, null)?.use { c ->
                    if (c.moveToFirst()) (c.getString(0) ?: number) to c.getString(1) else null
                }
            }
        } catch (_: Throwable) { null }
    }
}
