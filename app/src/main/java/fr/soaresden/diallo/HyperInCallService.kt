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
    private val recAsked = HashSet<Call>()   // appels déjà passés par l'enregistrement automatique
    private val simAsked = HashSet<Call>()   // appels en attente de SIM déjà traités (SIM retenue / voiture)

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            // le correspondant a raccroché / l'appel a coupé : un « tut », joué pendant que l'appel a encore le son
            if (state == Call.STATE_DISCONNECTED && call.parent == null && call in everActive && liveCalls().none { it != call })
                EndTone.onEnded(call.details?.disconnectCause)
            refresh()
        }
        override fun onDetailsChanged(call: Call, details: Call.Details) = refresh()
        override fun onChildrenChanged(call: Call, children: MutableList<Call>) = refresh()
        override fun onParentChanged(call: Call, parent: Call?) = refresh()
        override fun onConferenceableCallsChanged(call: Call, conferenceableCalls: MutableList<Call>) = refresh()
    }

    /** Recalcule l'appel « principal » affiché et l'état de tout le monde. */
    private fun refresh() {
        val live = liveCalls()
        val justAnswered = live.filter { it.state == Call.STATE_ACTIVE }.filter { everActive.add(it) }.isNotEmpty()
        // Android demande la SIM (appel lancé depuis Android Auto, un widget, une autre appli…)
        live.filter { it.state == Call.STATE_SELECT_PHONE_ACCOUNT && simAsked.add(it) }.forEach { SimChoice.onSelectNeeded(this, it) }
        val top = live.filter { it.parent == null }
        val p = top.firstOrNull { it.state == Call.STATE_RINGING }
            ?: top.firstOrNull { it.state == Call.STATE_ACTIVE || it.state == Call.STATE_DIALING || it.state == Call.STATE_CONNECTING || it.state == Call.STATE_NEW || it.state == Call.STATE_SELECT_PHONE_ACCOUNT }
            ?: top.firstOrNull { it.state == Call.STATE_HOLDING }
            ?: top.lastOrNull()
        if (p != null) { current.value = p; callState.intValue = p.state; contact.value = names[numberOf(p)] }
        else callState.intValue = Call.STATE_DISCONNECTED
        version.intValue++

        if (top.any { it.state == Call.STATE_RINGING }) RingGestures.start(this) else RingGestures.stop()
        // haut-parleur auto : capteurs lancés dès la sonnerie / la numérotation, pour être prêt au décroché
        if (live.isNotEmpty() && !AutoSpeaker.active.value) AutoSpeaker.start(this)
        if (justAnswered) AutoSpeaker.onConnected()
        if (live.isEmpty()) AutoSpeaker.stop()
        autoRecord(live)
        CallNotifier.update(this)
    }

    /** Enregistrement : démarre tout seul selon le mode choisi, s'arrête quand il n'y a plus d'appel. */
    private fun autoRecord(live: List<Call>) {
        if (live.isEmpty()) { recAsked.clear(); if (fr.soaresden.diallo.rec.CallRecorder.isOn) fr.soaresden.diallo.rec.CallRecorder.stop(); return }
        val rec = fr.soaresden.diallo.rec.CallRecorder
        if (!rec.enabled(this) || rec.isOn) return
        val a = live.firstOrNull { it.state == Call.STATE_ACTIVE && it.parent == null && it !in recAsked } ?: return
        recAsked.add(a)
        val num = numberOf(a)
        val known = names[num]
        val go = when (rec.mode(this)) { rec.ALL -> true; rec.CONTACTS -> known != null; else -> false }
        if (go) rec.start(this, known?.first ?: num.ifBlank { "Numéro masqué" }, num.ifBlank { null })
    }

    override fun onCreate() {
        super.onCreate(); CrashLog.install(this); I18n.ensure(this); instance = this; DataFolder.init(this)
        if (Companion.calls.isEmpty()) CallNotifier.cancel(this)   // reste d'un appel d'avant un plantage
    }
    override fun onDestroy() { AutoSpeaker.stop(); RingGestures.stop(); CallNotifier.cancel(this); if (instance === this) instance = null; super.onDestroy() }

    override fun onCallAdded(call: Call) {
        if (Companion.calls.isEmpty()) { AutoSpeaker.stop(); fr.soaresden.diallo.rec.CallRecorder.message.value = null }
        Companion.calls.add(call)
        call.registerCallback(callback)
        val number = numberOf(call)
        // nom du contact tout de suite : la première notification (celle qui sonne) l'affiche déjà
        if (number.isNotBlank() && !names.containsKey(number)) lookupContact(this, number)?.let { names[number] = it }
        refresh()
        if (call.parent == null && call.details?.callDirection == Call.Details.DIRECTION_INCOMING) wakeScreen()
        if (call.parent == null) startActivity(
            Intent(this, OngoingCallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }

    /**
     * Appel entrant : allume l'écran. setTurnScreenOn de l'écran d'appel ne suffit pas partout
     * (HyperOS / MIUI l'ignorent souvent), le verrou de réveil, lui, marche partout.
     */
    private fun wakeScreen() {
        val pm = getSystemService(android.os.PowerManager::class.java) ?: return
        if (pm.isInteractive) return
        try {
            @Suppress("DEPRECATION")
            pm.newWakeLock(android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or android.os.PowerManager.ON_AFTER_RELEASE,
                "diallo:ring").apply { setReferenceCounted(false); acquire(10_000L) }
        } catch (_: Throwable) { }
    }

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(callback)
        Companion.calls.remove(call)
        simAsked.remove(call)
        val wasActive = everActive.remove(call)
        RingGestures.onCallEnded(this, call, wasActive)
        // appel reçu sans réponse : l'appelant laisse peut-être un message, on ira voir la messagerie Free
        if (!wasActive && call.details?.callDirection == Call.Details.DIRECTION_INCOMING) fr.soaresden.diallo.vvm.Vvm.afterMissedCall(this)
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
            if (isConference(c)) return tr("Conférence")
            val n = numberOf(c)
            return names[n]?.first ?: if (n.isBlank()) tr("Numéro masqué") else displayNumber(n)
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
