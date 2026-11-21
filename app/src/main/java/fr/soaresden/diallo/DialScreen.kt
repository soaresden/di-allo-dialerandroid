package fr.soaresden.diallo

import android.content.Intent
import android.provider.ContactsContract
import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.lerp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Voicemail
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp

private data class K(val d: String, val l: String)
private val KEYS = listOf(
    K("1", "vm"), K("2", "ABC"), K("3", "DEF"),
    K("4", "GHI"), K("5", "JKL"), K("6", "MNO"),
    K("7", "PQRS"), K("8", "TUV"), K("9", "WXYZ"),
    K("*", ""), K("0", "+"), K("#", "")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DialScreen(
    onConfirm: (String) -> Unit, onVoicemail: () -> Unit,
    contacts: List<CallRepository.Contact>, frequent: List<CallRepository.Stat>
) {
    val ctx = LocalContext.current
    val view = LocalView.current
    LaunchedEffect(Unit) { Synth.warmUp() }   // flux audio ouvert + sons calculés avant le 1er appui
    var value by rememberSaveable { mutableStateOf("") }
    var speedAssign by remember { mutableStateOf<String?>(null) }
    speedAssign?.let { d ->
        ContactPickerDialog(tr("Numérotation rapide · touche {0}", d), onDismiss = { speedAssign = null }) { p ->
            SpeedDial.set(ctx, d, p); speedAssign = null; value = ""
            android.widget.Toast.makeText(ctx, tr("Appui long sur {0} : appelle {1}", d, p.name), android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    val typed = value.filter { it.isDigit() }
    // Candidats T9 / numéro, triés par nombre d'appels décroissant (une ligne par numéro)
    // Recherche à la fois en T9 (nom) ET dans le numéro (format national 06…, pour que "33" ne matche pas tout le +33)
    val intl = value.startsWith("+")
    val suggestions = remember(typed, intl, contacts) {
        if (typed.isEmpty()) emptyList()
        else contacts.filter { it.t9.contains(typed) || nationalDigits(it.number).contains(typed) || (intl && it.digits.contains(typed)) }
            .sortedWith(compareByDescending<CallRepository.Contact> { it.calls }
                .thenBy { c -> if (c.t9.split(' ').any { w -> w.startsWith(typed) } || nationalDigits(c.number).startsWith(typed)) 0 else 1 }
                .thenBy { it.display.lowercase() })
            .take(60)
    }
    val exact = remember(typed, contacts) {
        if (typed.length < 9) null else contacts.firstOrNull { it.digits.length >= 9 && it.digits.takeLast(9) == typed.takeLast(9) }
    }
    val favorites = remember(contacts) { contacts.filter { it.starred || it.groups.contains("⭐ Favoris") }.sortedWith(compareByDescending<CallRepository.Contact> { it.calls }.thenBy { it.display.lowercase() }) }
    val byKey = remember(contacts) { contacts.associateBy { keyOf(it.number) } }
    val freqRows = remember(frequent, byKey) {
        frequent.map { s -> byKey[keyOf(s.number)] ?: CallRepository.Contact(s.number, s.name, s.photoUri, calls = s.count) }
    }
    var showFav by remember { mutableStateOf(false) }
    var toSave by remember { mutableStateOf<Pair<List<Melodies.Note>, String>?>(null) }
    val recording by Melodies.recording
    val playingName by Melodies.playingName
    fun press(k: String) {
        if (recording) Melodies.note(k) else if (value.length < 24) value += k
        Synth.play(k)
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }
    // Mode composeur : ⌨️ joue sans taper de numéro, la composition s'écrit dans l'affichage
    fun saveDraft() { val r = Melodies.takeDraft(); if (r.first.isNotEmpty()) toSave = r }
    fun toggleComposer() { if (recording) { saveDraft(); Melodies.exitComposer() } else { value = ""; Melodies.startRec() } }
    androidx.activity.compose.BackHandler(enabled = recording) { toggleComposer() }
    toSave?.let { (n, t) -> MelodyEditDialog(n, t) { toSave = null } }

    if (showFav) {
        ModalBottomSheet(onDismissRequest = { showFav = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surface) {
            Text(tr("⭐ Favoris"), fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            if (favorites.isEmpty()) Hint(tr("Aucun favori. Mets une étoile à un contact dans l'app Contacts."))
            else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
                items(favorites.size) { i -> CompactRow(favorites[i], { showFav = false; onConfirm(it) }) }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // On réserve d'abord la place des candidats (zone haute) et de l'affichage,
        // puis le cadran prend le reste : 5 rangées + 4 espaces (16 % d'une touche).
        val displayH = 84.dp
        val topMin = 190.dp
        val byH = (maxHeight - displayH - topMin - 12.dp) / 5.64f
        val byW = (maxWidth - 72.dp) / 3
        val key = min(min(byH, byW), 80.dp).coerceAtLeast(46.dp)
        val gapV = (key * 0.16f)
        val gapH = (key * 0.42f)

        Column(Modifier.fillMaxSize()) {
            // ---------- Volet liste : favoris / fréquents, ou candidats pendant la frappe ----------
            Surface(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp).padding(top = 8.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = .92f), shape = RoundedCornerShape(20.dp)
            ) {
                if (recording) MelodyLibrary(Modifier.fillMaxSize())
                else Box(Modifier.fillMaxSize()) {
                val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                LaunchedEffect(value.isEmpty()) { listState.scrollToItem(0) }
                LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(vertical = 6.dp)) {
                    when {
                        value.isEmpty() -> {
                            if (freqRows.isNotEmpty()) {
                                item { SmallTitle(tr("Fréquents")) }
                                items(freqRows.size) { i -> CompactRow(freqRows[i], onConfirm) }
                            } else item { Hint(tr("Tes contacts fréquents apparaîtront ici.")) }
                        }
                        exact != null -> {
                            item { SmallTitle(tr("Contact trouvé")) }
                            item { CompactRow(exact, onConfirm) }
                        }
                        suggestions.isEmpty() -> item { Hint(tr("Aucun contact ne correspond")) }
                        else -> {
                            item { SmallTitle(if (suggestions.size >= 60) tr("60+ résultats") else trn(suggestions.size, "{0} résultat", "{0} résultats")) }
                            items(suggestions.size) { i -> CompactRow(suggestions[i], onConfirm, typed) }
                        }
                    }
                }
                LazyScrollbar(listState, Modifier.align(Alignment.CenterEnd).padding(vertical = 10.dp))
                }
            }

            // ---------- Affichage du numéro ----------
            if (playingName != null && (recording || value.isEmpty())) KaraokeDisplay(displayH)
            else if (recording) RecDisplay(displayH)
            else Box(Modifier.fillMaxWidth().height(displayH)) {
            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (value.isEmpty()) "" else prettyNumber(value),
                    fontSize = (when { value.length <= 10 -> 38f; value.length <= 14 -> 31f; else -> 25f } * LocalHTheme.current.numScale.coerceIn(.7f, 1.15f)).sp,
                    fontWeight = if (LocalHTheme.current.num != null) FontWeight.Normal else FontWeight.Light, letterSpacing = 1.5.sp, textAlign = TextAlign.Center,
                    fontFamily = LocalHTheme.current.num, style = androidx.compose.ui.text.TextStyle(shadow = LocalHTheme.current.glowShadow()),
                    color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
                when {
                    value.isEmpty() -> Text(tr("Saisis un numéro ou un nom (T9)"), fontSize = 13.sp, color = muted(.45f))
                    exact != null -> Text(exact.display, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    typed.length >= 6 -> Row(Modifier.clip(RoundedCornerShape(50)).clickable {
                        try { ctx.startActivity(Intent(ContactsContract.Intents.Insert.ACTION).setType(ContactsContract.RawContacts.CONTENT_TYPE).putExtra(ContactsContract.Intents.Insert.PHONE, value)) } catch (_: Throwable) {}
                    }.padding(horizontal = 10.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.PersonAdd, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                        Text(tr("Créer un contact"), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                    }
                    else -> Spacer(Modifier.height(18.dp))
                }
            }
            }

            // ---------- Cadran ----------
            Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(gapV)) {
                for (r in 0 until 4) {
                    Row(horizontalArrangement = Arrangement.spacedBy(gapH)) {
                        for (col in 0 until 3) {
                            val k = KEYS[r * 3 + col]
                            DialKey(k, key, pulse = Melodies.pulse[k.d] ?: 0, onDown = { press(k.d) }, onLong = {
                                if (!recording) when (k.d) {
                                    "1" -> { if (value.endsWith("1")) value = value.dropLast(1); onVoicemail() }
                                    in SpeedDial.DIGITS -> if (value == k.d) {
                                        // numérotation rapide : appui long sur 2…9, clavier vide
                                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                        val sd = SpeedDial.get(ctx, k.d)
                                        if (sd != null) { value = ""; placeCall(ctx, sd.number) } else speedAssign = k.d
                                    }
                                    "0" -> { if (value.endsWith("0")) value = value.dropLast(1); if (value.length < 24) value += "+"; view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
                                }
                            })
                        }
                    }
                }
                // Rangée d'appel : [favoris] [appeler] [effacer]
                if (recording) Row(horizontalArrangement = Arrangement.spacedBy(gapH), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(key).themedKey(LocalHTheme.current, LocalHTheme.current.keyShapeOf(), 0f)
                        .clickable { Melodies.space(); view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }, contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("␣", fontSize = (key.value * 0.36f).sp, color = MaterialTheme.colorScheme.onSurface)
                            Text(tr("PAUSE"), fontSize = (key.value * 0.12f).sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, color = muted(.5f))
                        }
                    }
                    val hasDraft = Melodies.recTokens.isNotEmpty()
                    Box(Modifier.size(key).shadow(if (hasDraft) 8.dp else 0.dp, LocalHTheme.current.keyShapeOf(), ambientColor = MaterialTheme.colorScheme.primary, spotColor = MaterialTheme.colorScheme.primary)
                        .clip(LocalHTheme.current.keyShapeOf()).background(if (hasDraft) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(enabled = hasDraft) { saveDraft() }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Save, tr("Garder la composition"), tint = if (hasDraft) MaterialTheme.colorScheme.onPrimary else muted(.3f), modifier = Modifier.size(key * 0.4f))
                    }
                    if (hasDraft) BackspaceKey(key, visible = true, onClick = {
                        Melodies.backspace(); view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    }, onLong = { Melodies.clearRec(); view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) })
                    else MusicKey(key, active = true) { toggleComposer() }
                }
                else Row(horizontalArrangement = Arrangement.spacedBy(gapH), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(key).clip(CircleShape).clickable { showFav = true; view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Star, tr("Favoris"), tint = Color(0xFFF5B301), modifier = Modifier.size(key * 0.38f))
                    }
                    Box(Modifier.size(key).shadow(8.dp, LocalHTheme.current.keyShapeOf(), ambientColor = CallGreen, spotColor = CallGreen).clip(LocalHTheme.current.keyShapeOf()).background(CallGreen)
                        .clickable { if (value.isNotEmpty()) onConfirm(value) }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Call, tr("Appeler"), tint = Color.White, modifier = Modifier.size(key * 0.4f))
                    }
                    if (value.isNotEmpty()) BackspaceKey(key, visible = true, onClick = {
                        value = value.dropLast(1); view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    }, onLong = { value = ""; view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) })
                    else MusicKey(key, active = false) { toggleComposer() }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DialKey(k: K, size: Dp, pulse: Int = 0, onDown: () -> Unit, onLong: () -> Unit) {
    val down by rememberUpdatedState(onDown); val long by rememberUpdatedState(onLong)
    val scope = rememberCoroutineScope()
    // Animations jouées jusqu'au bout, même pour une frappe très brève
    val press = remember { Animatable(1f) }    // échelle de la touche
    val glow = remember { Animatable(0f) }     // halo coloré
    val wave = remember { Animatable(1f) }     // onde qui part du doigt
    var touch by remember { mutableStateOf(Offset.Unspecified) }
    val cs = MaterialTheme.colorScheme
    val th = LocalHTheme.current
    val accent = cs.primary
    val shape = th.keyShapeOf()
    // Une mélodie joue cette touche : même animation qu'un appui
    LaunchedEffect(pulse) {
        if (pulse == 0) return@LaunchedEffect
        touch = Offset.Unspecified
        launch { wave.snapTo(0f); wave.animateTo(1f, tween(480, easing = LinearOutSlowInEasing)) }
        launch { glow.snapTo(1f); delay(110); glow.animateTo(0f, tween(300)) }
        press.animateTo(.88f, tween(70, easing = FastOutSlowInEasing))
        press.animateTo(1f, spring(dampingRatio = .38f, stiffness = 700f))
    }
    Box(
        Modifier.size(size)
            .graphicsLayer { scaleX = press.value; scaleY = press.value }
            .themedKey(th, shape, glow.value)
            .drawBehind {
                if (glow.value > 0f) drawRect(accent.copy(alpha = .30f * glow.value))
                if (wave.value < 1f) {
                    val c = if (touch.isSpecified) touch else center
                    drawCircle(accent.copy(alpha = .38f * (1f - wave.value)), radius = this.size.width * (.18f + 1.1f * wave.value), center = c)
                }
            }
            // son + vibration + chiffre dès le contact du doigt (pas au relâchement)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { o ->
                        touch = o
                        down()
                        val sink = scope.launch { press.animateTo(.86f, tween(70, easing = FastOutSlowInEasing)) }
                        val lit = scope.launch { glow.snapTo(1f) }
                        scope.launch { wave.snapTo(0f); wave.animateTo(1f, tween(480, easing = LinearOutSlowInEasing)) }
                        tryAwaitRelease()
                        scope.launch { sink.join(); press.animateTo(1f, spring(dampingRatio = .38f, stiffness = 700f)) }
                        scope.launch { lit.join(); delay(40); glow.animateTo(0f, tween(300)) }
                    },
                    onLongPress = { long() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        val rest = if (th.keyStyle == KeyStyle.NEON) lerp(cs.onSurface, accent, .45f) else cs.onSurface
        val digit = lerp(rest, accent, glow.value)
        val plain = k.d == "*" || k.d == "#"
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                k.d, fontSize = (size.value * (if (k.d == "*") 0.5f else 0.4f) * (if (plain) 1f else th.numScale)).sp,
                fontWeight = if (th.num != null && !plain) FontWeight.Normal else if (glow.value > .5f) FontWeight.Normal else FontWeight.Light,
                fontFamily = if (plain) null else th.num,
                style = androidx.compose.ui.text.TextStyle(shadow = th.glowShadow()),
                color = digit, modifier = Modifier.offset(y = if (k.d == "*") 4.dp else 0.dp)
            )
            when (k.l) {
                "vm" -> Icon(Icons.Filled.Voicemail, tr("Messagerie"), tint = muted(.5f), modifier = Modifier.size((size.value * 0.17f).dp))
                "" -> {}
                else -> Text(k.l, fontSize = (size.value * 0.125f).sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp, color = muted(.5f))
            }
        }
    }
}

/** Grosse note de musique : entre / sort du mode composeur. */
@Composable
private fun MusicKey(size: Dp, active: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(size).clip(CircleShape).background(if (active) cs.primaryContainer else Color.Transparent).clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text("🎵", fontSize = (size.value * 0.42f).sp)
        if (active) Text("✕", fontSize = (size.value * 0.16f).sp, fontWeight = FontWeight.Bold, color = cs.primary, modifier = Modifier.align(Alignment.TopEnd).padding(top = size * 0.12f, end = size * 0.14f))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BackspaceKey(size: Dp, visible: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).then(if (visible) Modifier.combinedClickable(onClick = onClick, onLongClick = onLong) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        if (visible) Icon(Icons.Filled.Backspace, tr("Effacer"), tint = muted(.6f), modifier = Modifier.size(size * 0.34f))
    }
}

@Composable
private fun SmallTitle(t: String) {
    val th = LocalHTheme.current
    Text(t, fontSize = (11.5f * th.headScale).sp, fontWeight = FontWeight.Bold, fontFamily = th.head, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 14.dp, top = 6.dp, bottom = 2.dp))
}

@Composable
private fun Hint(t: String) {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { Text(t, color = muted(.45f), fontSize = 13.sp) }
}

/** Vignette compacte sur UNE ligne : avatar · nom · type + numéro · nb d'appels. */
@Composable
private fun CompactRow(c: CallRepository.Contact, onConfirm: (String) -> Unit, query: String = "") {
    val hl = SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.ExtraBold, background = MaterialTheme.colorScheme.primary.copy(alpha = .16f))
    val shownNumber = displayNumber(c.number)
    Row(
        Modifier.fillMaxWidth().height(44.dp).clickable { onConfirm(c.number) }.padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(c.display, c.photoUri, 28.dp); Spacer(Modifier.width(10.dp))
        Text(highlightName(c.display, c.t9, query, hl), Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.width(8.dp))
        Text(
            buildAnnotatedString { append(flagOf(c.number) + " "); c.label?.let { append("$it · ") }; append(highlightDigits(shownNumber, query, hl)) },
            fontSize = 12.sp, color = muted(.55f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 170.dp)
        )
        Text(if (c.calls > 0) "${c.calls}" else "", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.End, modifier = Modifier.width(30.dp))
    }
}

/** Chiffres au format national : +33 6… / 0033 6… -> 06… */
internal fun nationalDigits(n: String): String {
    val d = n.filter { it.isDigit() }
    val t = n.trim()
    return when {
        t.startsWith("+33") -> "0" + d.drop(2)
        d.startsWith("0033") -> "0" + d.drop(4)
        else -> d
    }
}

/** Affichage au format international : +33 6 12 34 56 78. */
internal fun displayNumber(n: String): String = intlNumber(n)

/** Surligne dans le nom la partie qui correspond à la saisie T9. */
private fun highlightName(name: String, t9: String, q: String, st: SpanStyle): AnnotatedString {
    if (q.isEmpty() || t9.length != name.length) return AnnotatedString(name)
    // priorité à un début de mot
    var idx = -1; var from = 0
    while (true) { val i = t9.indexOf(q, from); if (i < 0) break; if (i == 0 || t9[i - 1] == ' ') { idx = i; break }; if (idx < 0) idx = i; from = i + 1 }
    if (idx < 0) return AnnotatedString(name)
    return buildAnnotatedString { append(name); addStyle(st, idx, idx + q.length) }
}

/** Surligne dans un numéro affiché (avec espaces) la suite de chiffres saisie. */
private fun highlightDigits(shown: String, q: String, st: SpanStyle): AnnotatedString {
    if (q.isEmpty()) return AnnotatedString(shown)
    val pos = ArrayList<Int>(); val sb = StringBuilder()
    shown.forEachIndexed { i, ch -> if (ch.isDigit()) { pos.add(i); sb.append(ch) } }
    val i = sb.indexOf(q)
    if (i < 0) return AnnotatedString(shown)
    return buildAnnotatedString { append(shown); addStyle(st, pos[i], pos[i + q.length - 1] + 1) }
}

/** Bandeau d'enregistrement : point rouge qui pulse, chrono, nombre de notes, bouton stop. */
@Composable
private fun RecDisplay(h: Dp) {
    val n = Melodies.recNotes.size
    val text = Melodies.recText
    val blink by androidx.compose.animation.core.rememberInfiniteTransition(label = "rec").animateFloat(
        1f, .25f, androidx.compose.animation.core.infiniteRepeatable(tween(650), androidx.compose.animation.core.RepeatMode.Reverse), label = "blink"
    )
    Column(Modifier.fillMaxWidth().height(h).padding(horizontal = 20.dp), verticalArrangement = Arrangement.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).graphicsLayer { alpha = blink }.clip(CircleShape).background(RecRed))
            Spacer(Modifier.width(8.dp))
            Text(
                if (n == 0) tr("MODE COMPOSEUR · joue !") else trn(n, "COMPOSEUR · {0} note · {1}", "COMPOSEUR · {0} notes · {1}", fmtMelodyDur(Melodies.recDurationMs)),
                fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = RecRed
            )
            Spacer(Modifier.weight(1f))
            Text(tr("␣ pause · ⌫ corriger"), fontSize = 11.sp, color = muted(.45f))
        }
        // les derniers chiffres tapés, le curseur au bout
        Text(
            if (text.isEmpty()) "…" else text.takeLast(26) + "▏",
            fontSize = if (text.length <= 14) 32.sp else 24.sp, fontWeight = FontWeight.Light, letterSpacing = 2.sp,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            color = if (text.isEmpty()) muted(.35f) else MaterialTheme.colorScheme.onBackground,
            maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
        )
    }
}

/** Habille une touche du cadran selon le thème : plein, verre, néon, contour, BD, tampon. */
internal fun Modifier.themedKey(th: HTheme, shape: androidx.compose.ui.graphics.Shape, lit: Float): Modifier {
    val ink = th.border ?: th.text
    val glowColor = th.primary
    return when (th.keyStyle) {
        KeyStyle.FILLED -> this.clip(shape).background(th.surface.copy(alpha = .9f))
        KeyStyle.GLASS -> this.clip(shape)
            .background(th.surface.copy(alpha = if (th.dark) .38f else .55f))
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.White.copy(alpha = if (th.dark) .16f else .5f), Color.White.copy(alpha = .02f))))
            .border(1.dp, if (th.dark) Color.White.copy(alpha = .22f) else th.text.copy(alpha = .10f), shape)
        KeyStyle.NEON -> this
            .drawBehind {
                val o = shape.createOutline(size, layoutDirection, this)
                val k = .55f + .45f * lit
                drawOutline(o, glowColor.copy(alpha = .07f * k), style = androidx.compose.ui.graphics.drawscope.Stroke(14.dp.toPx()))
                drawOutline(o, glowColor.copy(alpha = .14f * k), style = androidx.compose.ui.graphics.drawscope.Stroke(7.dp.toPx()))
            }
            .clip(shape).background(th.surface.copy(alpha = .35f))
            .border(1.8.dp, glowColor.copy(alpha = .9f), shape)
        KeyStyle.OUTLINE -> this.clip(shape).background(th.surface.copy(alpha = if (th.dark) .22f else .4f)).border(1.2.dp, th.border ?: th.text.copy(alpha = .28f), shape)
        KeyStyle.HARD -> this
            .drawBehind {
                val o = shape.createOutline(size, layoutDirection, this)
                val off = 4.dp.toPx() * (1f - lit * .7f)
                translate(off, off) { drawOutline(o, ink) }
            }
            .clip(shape).background(th.surface)
            .border(2.dp, ink, shape)
        KeyStyle.STAMP -> this.clip(shape).background(th.surface.copy(alpha = .78f))
            .drawBehind {
                val o = shape.createOutline(size, layoutDirection, this)
                val inset = 3.dp.toPx()
                inset(inset) {
                    drawOutline(shape.createOutline(this.size, layoutDirection, this), ink.copy(alpha = .75f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
                }
                drawOutline(o, ink.copy(alpha = .25f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            }
    }
}
