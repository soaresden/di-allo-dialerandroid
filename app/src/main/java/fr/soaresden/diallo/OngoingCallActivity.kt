package fr.soaresden.diallo

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.provider.CallLog
import android.telecom.Call
import android.telecom.CallAudioState
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

class OngoingCallActivity : ComponentActivity() {
    private var wake: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); CrashLog.install(this); I18n.ensure(this)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        handle(intent)
        setContent { HyperTheme(loadTheme(this)) { CallScreen(onDone = { close() }, onProximity = { proximity(it) }) } }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }
    private fun handle(i: Intent?) { if (i?.getBooleanExtra("answer", false) == true) HyperInCallService.answer() }

    /** Écran éteint contre la joue : pas de clics involontaires pendant l'appel. */
    private fun proximity(on: Boolean) {
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (on) {
            if (wake == null && pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK))
                wake = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "diallo:proximity").apply { setReferenceCounted(false); acquire(4 * 60 * 60 * 1000L) }
        } else {
            wake?.let { if (it.isHeld) it.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY) }
            wake = null
        }
    }

    override fun onDestroy() { proximity(false); super.onDestroy() }

    private fun close() {
        if (isFinishing) return
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, android.R.anim.fade_out)
    }
}

private data class Hist(val type: Int, val date: Long, val dur: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CallScreen(onDone: () -> Unit, onProximity: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val call by HyperInCallService.current
    val state by HyperInCallService.callState
    val audio by HyperInCallService.audio
    val contact by HyperInCallService.contact
    val autoOn by AutoSpeaker.active
    val silenced by RingGestures.silenced
    @Suppress("UNUSED_VARIABLE") val ver by HyperInCallService.version     // recompose quand un appel change
    val others = HyperInCallService.others
    var ending by remember { mutableStateOf(false) }

    // ---- Notes (brouillon rattaché au numéro affiché)
    var draft by rememberSaveable { mutableStateOf("") }
    var number by remember { mutableStateOf(HyperInCallService.current.value?.let { HyperInCallService.numberOf(it) } ?: "") }
    var notes by remember { mutableStateOf<List<NotesRepository.Note>>(emptyList()) }
    val live = call?.let { HyperInCallService.numberOf(it) }
    LaunchedEffect(live) {
        if (!live.isNullOrBlank() && live != number) {
            // on change de correspondant (double appel) : la note en cours reste à l'ancien
            if (draft.isNotBlank() && number.isNotBlank()) { NotesRepository.add(ctx, number, null, draft); draft = "" }
            number = live
        }
    }
    LaunchedEffect(number) { if (number.isNotBlank()) notes = withContext(Dispatchers.IO) { NotesRepository.forNumber(ctx, number) } }
    val saveDraft = {
        if (draft.isNotBlank() && number.isNotBlank()) {
            NotesRepository.add(ctx, number, contact?.first, draft)
            draft = ""
            notes = NotesRepository.forNumber(ctx, number)
        }
    }
    DisposableEffect(Unit) { onDispose { if (draft.isNotBlank() && number.isNotBlank()) NotesRepository.add(ctx, number, contact?.first, draft) } }

    var connectAt by remember { mutableLongStateOf(0L) }
    val ct = call?.details?.connectTimeMillis ?: 0L
    LaunchedEffect(call, ct) { connectAt = ct }

    val over = call == null || state == Call.STATE_DISCONNECTED || state == Call.STATE_DISCONNECTING || ending
    val ringing = !over && state == Call.STATE_RINGING
    val conference = call?.let { HyperInCallService.isConference(it) } == true

    // Fin d'appel : fermeture immédiate… sauf si une note est en cours d'écriture
    LaunchedEffect(over) { if (over && draft.isBlank()) { delay(if (call == null) 0 else 250); onDone() } }

    // ---- Historique avec ce numéro
    val hist by produceState<List<Hist>?>(null, number) { value = if (number.isBlank()) emptyList() else withContext(Dispatchers.IO) { history(ctx, number) } }

    // ---- Capteur de proximité : seulement à l'écouteur
    val route = audio?.route ?: CallAudioState.ROUTE_EARPIECE
    val wantProx = !over && !ringing && route == CallAudioState.ROUTE_EARPIECE
    LaunchedEffect(wantProx) { onProximity(wantProx) }

    // ---- Chrono
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state) { while (state == Call.STATE_ACTIVE || state == Call.STATE_HOLDING) { now = System.currentTimeMillis(); delay(500) } }
    val elapsed = if (connectAt > 0) ((now - connectAt) / 1000).coerceAtLeast(0) else 0
    val clock = "%02d:%02d".format(elapsed / 60, elapsed % 60)
    val stateText = when {
        over -> if (connectAt > 0) tr("Appel terminé · {0}", clock) else tr("Appel terminé")
        ringing -> if (others.isNotEmpty()) tr("Double appel…") else tr("Appel entrant…")
        state == Call.STATE_DIALING || state == Call.STATE_CONNECTING || state == Call.STATE_NEW || state == Call.STATE_SELECT_PHONE_ACCOUNT -> tr("Appel en cours…")
        state == Call.STATE_ACTIVE -> clock
        state == Call.STATE_HOLDING -> tr("⏸ En attente · {0}", clock)
        else -> ""
    }

    val shown = when {
        conference -> tr("Conférence")
        else -> contact?.first ?: if (number.isBlank()) tr("Numéro masqué") else displayNumber(number)
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var keypad by rememberSaveable { mutableStateOf(false) }
    var dtmf by rememberSaveable { mutableStateOf("") }
    var smsSheet by remember { mutableStateOf(false) }

    if (smsSheet) QuickSmsSheet(onDismiss = { smsSheet = false }) { msg -> smsSheet = false; ending = others.isEmpty(); HyperInCallService.rejectWithSms(msg) }

    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // ---- Autres appels (double appel) : en attente, basculer
        if (!over) others.forEach { o -> OtherCallRow(o, canSwap = o.state == Call.STATE_HOLDING && !ringing) }

        // ---- En-tête
        Spacer(Modifier.height(if (ringing && others.isEmpty()) 40.dp else 8.dp))
        if (conference) Box(Modifier.size(72.dp).clip(CircleShape).background(cs.primaryContainer), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Groups, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(38.dp)) }
        else Avatar(shown, contact?.second, if (ringing && others.isEmpty()) 110.dp else 72.dp)
        Spacer(Modifier.height(10.dp))
        Text(shown, color = cs.onBackground, fontSize = if (ringing) 28.sp else 23.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (number.isNotBlank() && !conference) {
            val type = listOfNotNull(lineTypeLabel(number), frRegionLabel(number)).joinToString(" · ")
            val line = if (contact != null) listOf(phoneText(number), type).filter { it.isNotEmpty() }.joinToString(" · ") else (flagOf(number) + " " + type).trim()
            Text(line, color = muted(), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(4.dp))
        Text(stateText, color = if (state == Call.STATE_ACTIVE && !over) CallGreen else muted(), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        if (!conference) hist?.let { h ->
            val info = if (h.isEmpty()) tr("Premier appel avec ce numéro 👋")
            else {
                val missed = h.count { it.type == CallLog.Calls.MISSED_TYPE || it.type == CallLog.Calls.REJECTED_TYPE }
                trn(h.size, "{0} appel", "{0} appels") + " · " + fmtDur(h.sumOf { it.dur }) + " · " + tr("dernier {0}", fmtWhen(h.first().date)) + if (missed > 0) " · " + trn(missed, "{0} manqué", "{0} manqués") else ""
            }
            Text(info, color = muted(.6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(12.dp))

        // ---- Android demande la SIM (appel lancé hors de Di-Allô) : choix ici
        if (!over && state == Call.STATE_SELECT_PHONE_ACCOUNT) call?.let { c ->
            val sims = remember(c) { SimChoice.forCall(ctx, c) }
            if (sims.isNotEmpty()) Surface(color = cs.surface, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(tr("Appeler avec quelle SIM ?"), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = cs.onSurface)
                    Spacer(Modifier.height(6.dp))
                    SimButtons(number, sims, remember(c) { SimChoice.likely(ctx, c, number, sims) }) { s -> try { c.phoneAccountSelected(s.handle, false) } catch (_: Throwable) { } }
                }
            }
        }

        if (ringing) {
            // Dernière note : utile pour savoir de quoi on parlait avant de décrocher
            notes.firstOrNull()?.let { n ->
                Surface(color = cs.surface, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(tr("📝 Dernière note · {0}", fmtWhen(n.date)), fontSize = 12.sp, color = muted(.6f))
                        Text(n.text, fontSize = 14.sp, color = cs.onSurface, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                SmallAction(if (silenced) tr("🔕 Silencieux") else tr("🔕 Silence"), enabled = !silenced) { RingGestures.silence(ctx) }
                if (number.isNotBlank()) SmallAction(tr("💬 Message")) { smsSheet = true }
            }
            Spacer(Modifier.height(22.dp))
            if (others.isEmpty()) SlideCall(
                left = SlideEnd(tr("Refuser"), Icons.Filled.CallEnd, Red) { ending = true; HyperInCallService.hangup() },
                right = SlideEnd(tr("Répondre"), Icons.Filled.Call, CallGreen) { HyperInCallService.answer() },
                knobIcon = Icons.Filled.Call
            ) else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top) {
                RoundBtn(Red, Icons.Filled.CallEnd, tr("Refuser"), 62.dp) { HyperInCallService.hangup() }
                RoundBtn(CallGreen, Icons.Filled.PhonePaused, tr("Mettre en attente\net répondre"), 62.dp) { HyperInCallService.answer() }
                RoundBtn(Amber, Icons.Filled.PhoneForwarded, tr("Raccrocher\net répondre"), 62.dp) { HyperInCallService.answerEndingOthers() }
            }
            Spacer(Modifier.height(30.dp))
        } else {
            // ---- Participants de la conférence
            if (conference && !over) call?.children?.takeIf { it.isNotEmpty() }?.let { kids ->
                Surface(color = cs.surface, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        kids.forEach { k -> ParticipantRow(k) }
                    }
                }
            }

            // ---- Commandes d'appel
            if (!over) {
                val speaker = route == CallAudioState.ROUTE_SPEAKER
                val bt = route == CallAudioState.ROUTE_BLUETOOTH
                val btAvail = ((audio?.supportedRouteMask ?: 0) and CallAudioState.ROUTE_BLUETOOTH) != 0
                val muted = audio?.isMuted == true
                val holding = state == Call.STATE_HOLDING
                val canHold = call?.details?.can(Call.Details.CAPABILITY_HOLD) == true || holding
                Row(Modifier.fillMaxWidth()) {
                    @Suppress("DEPRECATION")
                    Ctrl(Icons.Filled.VolumeUp, tr("Haut-parleur"), speaker, Modifier.weight(1f)) { HyperInCallService.setSpeaker(!speaker) }
                    Ctrl(Icons.Filled.Sensors, tr("Auto HP"), autoOn, Modifier.weight(1f)) { if (autoOn) AutoSpeaker.stop() else AutoSpeaker.start(ctx, force = true) }
                    Ctrl(if (muted) Icons.Filled.MicOff else Icons.Filled.Mic, if (muted) tr("Coupé") else tr("Muet"), muted, Modifier.weight(1f)) { HyperInCallService.setMuted(!muted) }
                    Ctrl(Icons.Filled.Dialpad, tr("Clavier"), keypad, Modifier.weight(1f)) { keypad = !keypad }
                }
                Row(Modifier.fillMaxWidth()) {
                    Ctrl(if (holding) Icons.Filled.PlayArrow else Icons.Filled.Pause, if (holding) tr("Reprendre") else tr("Attente"), holding, Modifier.weight(1f), enabled = canHold) { HyperInCallService.setHold(!holding) }
                    Ctrl(Icons.Filled.Bluetooth, "Bluetooth", bt, Modifier.weight(1f), enabled = btAvail) { HyperInCallService.setBluetooth(!bt) }
                    Ctrl(Icons.Filled.PersonAdd, tr("Ajouter"), false, Modifier.weight(1f)) {
                        ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("tab", 0))
                    }
                    Ctrl(Icons.Filled.CallMerge, tr("Fusionner"), false, Modifier.weight(1f), enabled = HyperInCallService.canMerge) { HyperInCallService.merge() }
                }
                RecStrip(shown, number, state == Call.STATE_ACTIVE || state == Call.STATE_HOLDING)
                Spacer(Modifier.height(8.dp))
            }

            // ---- Zone : clavier DTMF ou notes / historique
            Surface(color = cs.surface, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().weight(1f)) {
                if (keypad && !over) DtmfPad(dtmf, onKey = { ch -> dtmf = (dtmf + ch).takeLast(24); HyperInCallService.dtmf(ch) }, onClose = { keypad = false })
                else Column(Modifier.padding(10.dp)) {
                    Segmented(listOf(tr("📝 Notes") + if (notes.isNotEmpty()) " (${notes.size})" else "", tr("🕘 Historique") + (hist?.let { " (${it.size})" } ?: "")), tab, { tab = it })
                    Spacer(Modifier.height(8.dp))
                    if (tab == 0) {
                        if (number.isBlank()) Text(tr("Numéro masqué : impossible de ranger une note."), fontSize = 13.sp, color = muted()) else {
                            OutlinedTextField(
                                draft, { draft = it }, Modifier.fillMaxWidth(),
                                placeholder = { Text(tr("Note pendant l'appel…")) }, minLines = 2, maxLines = 5,
                                trailingIcon = { if (draft.isNotBlank()) IconButton(onClick = { saveDraft() }) { Icon(Icons.Filled.Save, tr("Enregistrer"), tint = cs.primary) } }
                            )
                            Spacer(Modifier.height(6.dp))
                            NoteList(notes, Modifier.fillMaxWidth().weight(1f)) { id -> NotesRepository.delete(ctx, id); notes = NotesRepository.forNumber(ctx, number) }
                        }
                    } else {
                        val h = hist
                        when {
                            h == null -> Text(tr("Chargement…"), fontSize = 13.sp, color = muted())
                            h.isEmpty() -> Text(tr("Aucun appel précédent avec ce numéro."), fontSize = 13.sp, color = muted())
                            else -> LazyColumn(Modifier.fillMaxWidth().weight(1f)) { items(h.take(60)) { HistRow(it) } }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // ---- Bas : raccrocher / fermer
            if (over) fr.soaresden.diallo.rec.CallRecorder.message.value?.let { Text(it, fontSize = 12.5.sp, color = muted(.75f), maxLines = 2, modifier = Modifier.padding(bottom = 8.dp)) }
            if (over) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { draft = ""; onDone() }, modifier = Modifier.weight(1f)) { Text(tr("Fermer")) }
                    Button(onClick = { saveDraft(); onDone() }, modifier = Modifier.weight(1.6f), enabled = draft.isNotBlank()) {
                        Icon(Icons.Filled.Save, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr("Enregistrer et fermer"))
                    }
                }
                Spacer(Modifier.height(8.dp))
            } else {
                SlideCall(left = null, right = SlideEnd(tr("Glisser pour raccrocher"), Icons.Filled.CallEnd, Red) { ending = draft.isBlank() && others.isEmpty(); HyperInCallService.hangup() }, knobIcon = Icons.Filled.CallEnd)
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

/** Bandeau d'un autre appel (en attente) avec « Basculer ». */
@Composable
private fun OtherCallRow(c: Call, canSwap: Boolean) {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.surfaceVariant, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Row(Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(HyperInCallService.nameOf(c), HyperInCallService.photoOf(c), 34.dp); Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(HyperInCallService.nameOf(c), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(when (c.state) { Call.STATE_HOLDING -> tr("⏸ En attente"); Call.STATE_ACTIVE -> tr("En ligne"); Call.STATE_DIALING, Call.STATE_CONNECTING -> tr("Appel…"); else -> "" }, fontSize = 12.sp, color = muted())
            }
            if (canSwap) TextButton(onClick = { HyperInCallService.swap() }) { Icon(Icons.Filled.SwapVert, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(tr("Basculer")) }
        }
    }
}

/** Un participant de conférence : séparer (parler en privé) ou raccrocher. */
@Composable
private fun ParticipantRow(c: Call) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(HyperInCallService.nameOf(c), HyperInCallService.photoOf(c), 30.dp); Spacer(Modifier.width(10.dp))
        Text(HyperInCallService.nameOf(c), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (c.details?.can(Call.Details.CAPABILITY_SEPARATE_FROM_CONFERENCE) == true)
            IconButton(onClick = { c.splitFromConference() }, modifier = Modifier.size(36.dp)) { Icon(Icons.Filled.CallSplit, tr("Parler en privé"), tint = MaterialTheme.colorScheme.primary) }
        if (c.details?.can(Call.Details.CAPABILITY_DISCONNECT_FROM_CONFERENCE) == true)
            IconButton(onClick = { c.disconnect() }, modifier = Modifier.size(36.dp)) { Icon(Icons.Filled.CallEnd, tr("Raccrocher ce participant"), tint = Red) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickSmsSheet(onDismiss: () -> Unit, onSend: (String) -> Unit) {
    val ctx = LocalContext.current
    val list = remember { QuickReplies.load(ctx) }
    var custom by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(tr("Refuser avec un message"), fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(bottom = 10.dp))
            list.forEach { raw ->
                val m = tr(raw)   // réponses par défaut (en français) traduites ; les perso restent telles quelles
                Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(14.dp)).clickable { onSend(m) }) {
                    Text("💬  $m", fontSize = 14.5.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp))
                }
            }
            OutlinedTextField(
                custom, { custom = it }, Modifier.fillMaxWidth().padding(top = 4.dp), placeholder = { Text(tr("Message perso…")) }, shape = RoundedCornerShape(14.dp), maxLines = 3,
                trailingIcon = { if (custom.isNotBlank()) IconButton(onClick = { onSend(custom.trim()) }) { Icon(Icons.Filled.Send, tr("Envoyer"), tint = MaterialTheme.colorScheme.primary) } }
            )
        }
    }
}

@Composable
private fun SmallAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(50), modifier = Modifier.clip(RoundedCornerShape(50)).clickable(enabled = enabled) { onClick() }) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (enabled) MaterialTheme.colorScheme.onSurface else muted(.5f), modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
    }
}

/** Bandeau d'enregistrement : 🔴 Enregistrer / ⏹ 01:23. */
@Composable
private fun RecStrip(who: String, number: String, live: Boolean) {
    val ctx = LocalContext.current
    val rec = fr.soaresden.diallo.rec.CallRecorder
    val enabled = remember { rec.enabled(ctx) }
    if (enabled) RecStripBody(who, number, live)
}

@Composable
private fun RecStripBody(who: String, number: String, live: Boolean) {
    val ctx = LocalContext.current
    val rec = fr.soaresden.diallo.rec.CallRecorder
    val st by rec.state
    val since by rec.since
    val msg by rec.message
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(st) { while (st == fr.soaresden.diallo.rec.CallRecorder.State.ON) { now = System.currentTimeMillis(); delay(500) } }
    val cs = MaterialTheme.colorScheme
    val on = st != fr.soaresden.diallo.rec.CallRecorder.State.IDLE
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Surface(
            onClick = { rec.toggle(ctx, who, number.ifBlank { null }) }, enabled = live || on,
            shape = RoundedCornerShape(50), color = if (on) Red else cs.surfaceVariant
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(if (on) RoundedCornerShape(2.dp) else CircleShape).background(if (on) Color.White else Red))
                Spacer(Modifier.width(8.dp))
                val s = ((now - since) / 1000).coerceAtLeast(0)
                Text(
                    when (st) { fr.soaresden.diallo.rec.CallRecorder.State.STARTING -> tr("Démarrage…"); fr.soaresden.diallo.rec.CallRecorder.State.ON -> tr("REC {0} · Arrêter", "%d:%02d".format(s / 60, s % 60)); else -> tr("Enregistrer") },
                    color = if (on) Color.White else cs.onSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
    if (st == fr.soaresden.diallo.rec.CallRecorder.State.IDLE) msg?.let { Text(it, fontSize = 11.5.sp, color = muted(.7f), maxLines = 2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) }
}

@Composable
private fun Ctrl(icon: ImageVector, label: String, on: Boolean, mod: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(mod.clip(RoundedCornerShape(18.dp)).clickable(enabled = enabled) { onClick() }.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(if (on) cs.primary else cs.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = if (on) cs.onPrimary else cs.onSurface.copy(alpha = if (enabled) 1f else .3f), modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(3.dp))
        Text(label, fontSize = 11.sp, color = muted(if (enabled) .8f else .3f), maxLines = 1)
    }
}

@Composable
private fun DtmfPad(typed: String, onKey: (Char) -> Unit, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(typed.ifEmpty { tr("Tonalités") }, fontSize = 24.sp, fontWeight = FontWeight.Medium, color = if (typed.isEmpty()) muted(.4f) else cs.onSurface, maxLines = 1, modifier = Modifier.weight(1f).padding(start = 6.dp))
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, tr("Fermer le clavier"), tint = muted()) }
        }
        Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.SpaceEvenly) {
            listOf("123", "456", "789", "*0#").forEach { row ->
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { ch ->
                        Box(
                            Modifier.weight(1f).fillMaxHeight().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(cs.surfaceVariant).clickable { onKey(ch) },
                            contentAlignment = Alignment.Center
                        ) { Text(ch.toString(), fontSize = 26.sp, fontWeight = FontWeight.Medium, color = cs.onSurface) }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistRow(h: Hist) {
    val (icon, color, label) = when (h.type) {
        CallLog.Calls.OUTGOING_TYPE -> Triple(Icons.Filled.PhoneForwarded, OutColor, tr("Sortant"))
        CallLog.Calls.INCOMING_TYPE -> Triple(Icons.Filled.PhoneCallback, InColor, tr("Entrant"))
        CallLog.Calls.MISSED_TYPE -> Triple(Icons.Filled.PhoneMissed, Red, tr("Manqué"))
        CallLog.Calls.REJECTED_TYPE -> Triple(Icons.Filled.PhoneMissed, Red, tr("Refusé"))
        CallLog.Calls.VOICEMAIL_TYPE -> Triple(Icons.Filled.Voicemail, Amber, tr("Messagerie"))
        else -> Triple(Icons.Filled.Call, Color.Gray, tr("Appel"))
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, label, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(fmtWhen(h.date), fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(label, fontSize = 11.5.sp, color = muted(.55f))
        }
        if (h.dur > 0) Text(fmtDur(h.dur), fontSize = 13.sp, color = muted())
    }
}

private class SlideEnd(val label: String, val icon: ImageVector, val color: Color, val action: () -> Unit)

/**
 * Curseur à glisser : à deux sens (← refuser / répondre →) quand [left] est donné,
 * sinon à un sens (glisser vers la droite, ex. pour raccrocher). Évite les appuis involontaires.
 */
@Composable
private fun SlideCall(left: SlideEnd?, right: SlideEnd, knobIcon: ImageVector) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val off = remember { Animatable(0f) }
    var fired by remember { mutableStateOf(false) }
    val knob = 60.dp; val pad = 6.dp
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(knob + pad * 2).clip(RoundedCornerShape(50)).background(cs.surfaceVariant)
            .semantics {
                customActions = listOfNotNull(
                    CustomAccessibilityAction(right.label) { right.action(); true },
                    left?.let { l -> CustomAccessibilityAction(l.label) { l.action(); true } }
                )
            }
    ) {
        val density = LocalDensity.current
        val range = with(density) { (if (left != null) (maxWidth - knob) / 2 - pad else maxWidth - knob - pad * 2).toPx() }.coerceAtLeast(1f)
        val lo = if (left != null) -range else 0f
        val f = (off.value / range).coerceIn(-1f, 1f)
        val txt = cs.onSurfaceVariant
        // Libellés de la piste (s'effacent quand on glisse)
        Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            if (left != null) {
                Icon(left.icon, null, tint = left.color.copy(alpha = 1f - f.coerceAtLeast(0f)), modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(6.dp))
                Text(left.label, color = txt.copy(alpha = 1f - abs(f)), fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Spacer(Modifier.weight(1f))
                Text(right.label, color = txt.copy(alpha = 1f - abs(f)), fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            } else {
                Spacer(Modifier.width(knob))
                Text(right.label + "  ›", color = txt.copy(alpha = 1f - f), fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.width(6.dp))
            Icon(right.icon, null, tint = right.color.copy(alpha = 1f - (-f).coerceAtLeast(0f)), modifier = Modifier.size(22.dp))
        }
        // Poignée
        val base = if (left == null) right.color else cs.primary
        val kc = when { left != null && f > 0 -> lerp(base, right.color, f); left != null && f < 0 -> lerp(base, left.color, -f); else -> base }
        val ic = if (left == null) Color.White else lerp(cs.onPrimary, Color.White, abs(f))
        Box(
            Modifier.align(if (left != null) Alignment.Center else Alignment.CenterStart)
                .padding(start = if (left != null) 0.dp else pad)
                .offset { IntOffset(off.value.roundToInt(), 0) }
                .size(knob).clip(CircleShape).background(kc)
                .pointerInput(range, left != null) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                val end = when { off.value >= range * .8f -> right; left != null && off.value <= -range * .8f -> left; else -> null }
                                if (end != null && !fired) {
                                    fired = true
                                    off.animateTo(if (end === right) range else -range)
                                    end.action()
                                    delay(1500); fired = false   // si l'action n'a pas fermé l'écran, on revient au départ
                                }
                                off.animateTo(0f, spring(dampingRatio = .6f))
                            }
                        },
                        onDragCancel = { scope.launch { off.animateTo(0f) } }
                    ) { ch, d -> ch.consume(); scope.launch { off.snapTo((off.value + d).coerceIn(lo, range)) } }
                },
            contentAlignment = Alignment.Center
        ) {
            val icon = when { left != null && f < -.15f -> left.icon; f > .15f -> right.icon; else -> knobIcon }
            Icon(icon, null, tint = ic, modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
private fun RoundBtn(color: Color, icon: ImageVector, label: String?, size: Dp = 70.dp, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 120.dp)) {
        Box(Modifier.size(size).clip(CircleShape).background(color).clickable { onClick() }, contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = Color.White, modifier = Modifier.size(30.dp))
        }
        if (label != null) { Spacer(Modifier.height(8.dp)); Text(label, color = muted(), fontSize = 12.sp, textAlign = TextAlign.Center) }
    }
}

/** Tous les appels avec ce numéro, du plus récent au plus ancien. */
private fun history(ctx: Context, number: String): List<Hist> {
    val k = keyOf(number); if (k.isEmpty()) return emptyList()
    val out = ArrayList<Hist>()
    try {
        ctx.contentResolver.query(
            CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
            null, null, CallLog.Calls.DATE + " DESC"
        )?.use { c ->
            while (c.moveToNext()) {
                val n = c.getString(0) ?: continue
                if (keyOf(n) == k) out.add(Hist(c.getInt(1), c.getLong(2), c.getInt(3)))
            }
        }
    } catch (_: Throwable) { }
    return out
}
