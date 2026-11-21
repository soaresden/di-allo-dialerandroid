package fr.soaresden.diallo

import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Couleur de chaque note (façon tubes sonores) : Do rouge → Si violet, même teinte à l'octave. */
internal fun noteColor(k: String): Color = when (k) {
    "1", "8" -> Color(0xFFE5484D)
    "2", "9" -> Color(0xFFF2862E)
    "3", "*" -> Color(0xFFE8C21E)
    "4", "0" -> Color(0xFF3DB46D)
    "5", "#" -> Color(0xFF20A4B5)
    "6" -> Color(0xFF3E7BE6)
    "7" -> Color(0xFF8E5BE0)
    else -> Color.Gray
}
internal fun noteName(k: String): String = when (k) {
    "1", "8" -> "Do"; "2", "9" -> "Ré"; "3", "*" -> "Mi"; "4", "0" -> "Fa"; "5", "#" -> "Sol"; "6" -> "La"; "7" -> "Si"; else -> ""
}

/**
 * Éditeur de mélodie plein écran, façon karaoké :
 *  - la partition en pastilles colorées, la note jouée s'allume et la vue la suit ;
 *  - toucher une note place le curseur (et la fait entendre) ; le clavier en bas insère au curseur ;
 *  - la barre de temps se fait glisser : la lecture repart de là.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MelodyEditDialog(played: List<Melodies.Note>, typed: String, existing: Melodies.Melody? = null, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var name by remember { mutableStateOf(existing?.name ?: Melodies.nextName(ctx)) }
    var text by remember { mutableStateOf(typed) }
    var cursor by remember { mutableIntStateOf(typed.length) }
    val instr = remember { existing?.instr ?: Synth.currentId }
    val playing by Melodies.playingId
    val idx by Melodies.playingIndex
    val med = remember { played.zipWithNext { a, b -> b.t - a.t }.filter { it > 40 }.sorted().let { it.getOrNull(it.size / 2) ?: 300 }.coerceIn(120, 700) }
    var step by remember { mutableFloatStateOf(med.toFloat()) }
    val changed = text.trim() != typed.trim()
    var asPlayed by remember { mutableStateOf(true) }
    val useRhythm = asPlayed && !changed && played.isNotEmpty()
    val notes = if (useRhythm) played else remember(text, step) { Melodies.fromText(text, step.toInt()) }
    val total = (notes.lastOrNull()?.t ?: 0) + 400
    val previewId = if (existing != null) -3L else -1L
    val isPlaying = playing == previewId
    /** Position dans le texte de chaque note (même ordre que [notes]). */
    val notePos = remember(text) { text.indices.filter { text[it].toString() in Melodies.VALID }.toIntArray() }
    val curNote = remember(notePos, cursor) { notePos.count { it < cursor } }   // nombre de notes avant le curseur
    var drag by remember { mutableStateOf<Float?>(null) }
    var showTempo by remember { mutableStateOf(false) }

    fun playFrom(i: Int) { if (notes.isNotEmpty()) Melodies.play(previewId, name, instr, notes, from = if (i in notes.indices) i else 0) }
    fun insert(s: String) { val c = cursor.coerceIn(0, text.length); text = text.substring(0, c) + s + text.substring(c); cursor = c + s.length }
    fun erase() { val c = cursor.coerceIn(0, text.length); if (c > 0) { text = text.removeRange(c - 1, c); cursor = c - 1 } }
    fun close() { Melodies.stop(); onDone() }

    // pendant la lecture, le curseur suit la note jouée
    LaunchedEffect(idx, isPlaying) { if (isPlaying && drag == null && idx in notePos.indices) cursor = notePos[idx] + 1 }

    val lines = remember(text) { var off = 0; text.split('\n').map { l -> (off to l).also { off += l.length + 1 } } }
    val cursorLine = remember(lines, cursor) { lines.indexOfLast { it.first <= cursor }.coerceAtLeast(0) }
    val listState = rememberLazyListState()
    LaunchedEffect(cursorLine) {
        val vis = listState.layoutInfo.visibleItemsInfo
        if (vis.none { it.index == cursorLine } || vis.lastOrNull()?.index == cursorLine) listState.animateScrollToItem((cursorLine - 1).coerceAtLeast(0))
    }
    val blink by rememberInfiniteTransition(label = "c").animateFloat(1f, .15f, infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "c")

    Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = cs.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
                // ---------- En-tête ----------
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { close() }) { Icon(Icons.Filled.Close, if (existing != null) "Annuler" else "Jeter") }
                    OutlinedTextField(name, { name = it }, Modifier.weight(1f), singleLine = true, textStyle = LocalTextStyle.current.copy(fontWeight = FontWeight.SemiBold), shape = RoundedCornerShape(14.dp))
                    Spacer(Modifier.width(8.dp))
                    Button(enabled = notes.isNotEmpty(), onClick = {
                        Melodies.stop()
                        if (existing != null) Melodies.update(ctx, existing.copy(name = name.trim().ifBlank { existing.name }, notes = notes))
                        else Melodies.add(ctx, name, instr, notes)
                        Toast.makeText(ctx, if (existing != null) "Mélodie modifiée ✏️" else "Mélodie enregistrée 🎼", Toast.LENGTH_SHORT).show()
                        onDone()
                    }) { Text(if (existing != null) "Enregistrer" else "Garder") }
                }
                Text(
                    "${notes.size} notes · ${fmtMelodyDur(total)} · ${instrLabel(instr)}" + if (useRhythm) " · rythme d'origine" else " · rythme régulier",
                    fontSize = 12.sp, color = muted(.6f), modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 6.dp)
                )

                // ---------- Partition ----------
                Surface(Modifier.fillMaxWidth().weight(1f), shape = RoundedCornerShape(20.dp), color = cs.surface) {
                    Box(Modifier.fillMaxSize()) {
                        if (text.isEmpty()) Text("Tape des notes avec le clavier en bas 🎹", color = muted(.5f), modifier = Modifier.align(Alignment.Center))
                        LazyColumn(Modifier.fillMaxSize().padding(end = 14.dp), state = listState, contentPadding = PaddingValues(10.dp)) {
                            items(lines.size) { li ->
                                val (off, l) = lines[li]
                                FlowRow(
                                    Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable { cursor = off + l.length },
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    if (cursor == off) Caret(blink)
                                    for (j in l.indices) {
                                        val gi = off + j
                                        val k = l[j].toString()
                                        val ni = if (k in Melodies.VALID) notePos.binarySearch(gi) else -1
                                        val now = isPlaying && ni >= 0 && ni == idx
                                        val past = isPlaying && ni in 0 until idx
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            NoteChip(k, now, past) { cursor = gi + 1; if (k in Melodies.VALID) Synth.play(k, instr) }
                                            if (cursor == gi + 1) Caret(blink) else Spacer(Modifier.width(4.dp))
                                        }
                                    }
                                    if (li < lines.lastIndex) Text("↵", color = muted(.3f), fontSize = 14.sp, modifier = Modifier.padding(start = 2.dp, top = 16.dp))
                                }
                            }
                        }
                        LazyScrollbar(listState, Modifier.align(Alignment.CenterEnd).padding(vertical = 10.dp))
                    }
                }

                // ---------- Barre de temps (on la fait glisser : la lecture repart de là) ----------
                val n = notes.size
                val curIdx = if (isPlaying && idx >= 0) idx else curNote.coerceAtMost((n - 1).coerceAtLeast(0))
                val shownIdx = drag?.toInt() ?: curIdx
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(fmtMelodyDur(notes.getOrNull(shownIdx)?.t ?: 0), fontSize = 11.5.sp, color = muted(.6f), modifier = Modifier.width(36.dp))
                    Slider(
                        value = (drag ?: curIdx.toFloat()).coerceIn(0f, (n - 1).coerceAtLeast(0).toFloat()),
                        onValueChange = { v ->
                            drag = v
                            val i = v.toInt()
                            notePos.getOrNull(i)?.let { cursor = it + 1 }
                        },
                        onValueChangeFinished = { val i = drag?.toInt() ?: 0; drag = null; if (isPlaying) playFrom(i) else notes.getOrNull(i)?.let { Synth.play(it.k, instr) } },
                        valueRange = 0f..(n - 1).coerceAtLeast(1).toFloat(), enabled = n > 1, modifier = Modifier.weight(1f)
                    )
                    Text(fmtMelodyDur(total), fontSize = 11.5.sp, color = muted(.6f), modifier = Modifier.width(36.dp), textAlign = TextAlign.End)
                }
                // ---------- Lecture ----------
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { cursor = 0; if (isPlaying) playFrom(0) }) { Icon(Icons.Filled.SkipPrevious, "Début") }
                    IconButton(onClick = { cursor = (cursor - 1).coerceAtLeast(0) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Curseur à gauche") }
                    Box(
                        Modifier.size(58.dp).clip(CircleShape).background(if (notes.isEmpty()) cs.surfaceVariant else cs.primary)
                            .clickable(enabled = notes.isNotEmpty()) { if (isPlaying) Melodies.stop() else playFrom(if (curNote >= n) 0 else curNote) },
                        contentAlignment = Alignment.Center
                    ) { Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (isPlaying) "Pause" else "Lire depuis le curseur", tint = cs.onPrimary, modifier = Modifier.size(32.dp)) }
                    IconButton(onClick = { cursor = (cursor + 1).coerceAtMost(text.length) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Curseur à droite") }
                    TextButton(onClick = { showTempo = !showTempo }) { Text(if (useRhythm) "🎵" else "📏", fontSize = 18.sp) }
                }
                if (showTempo) Column(Modifier.padding(horizontal = 6.dp)) {
                    Segmented(listOf(if (existing != null) "🎵 Rythme d'origine" else "🎵 Rythme joué", "📏 Régulier"), if (useRhythm) 0 else 1, { asPlayed = it == 0 })
                    if (changed) Text("Notes modifiées : rythme régulier (␣ ou ↵ = pause).", fontSize = 11.5.sp, color = muted(.55f), modifier = Modifier.padding(top = 2.dp))
                    if (!useRhythm) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${(60000 / step).toInt()} / min", fontSize = 12.sp, color = cs.onSurface, modifier = Modifier.width(70.dp))
                        Slider(step, { step = it }, valueRange = 120f..700f, modifier = Modifier.weight(1f))
                    }
                }

                // ---------- Clavier d'édition : insère au curseur ----------
                val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("*", "0", "#"))
                Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    rows.forEach { r ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            r.forEach { k -> EditKey(k, noteName(k), noteColor(k), Modifier.weight(1f)) { insert(k); Synth.play(k, instr) } }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        EditKey("␣", "pause", cs.outline, Modifier.weight(1f)) { insert(" ") }
                        EditKey("↵", "ligne", cs.outline, Modifier.weight(1f)) { insert("\n") }
                        EditKey("⌫", "effacer", Red, Modifier.weight(1f)) { erase() }
                    }
                }
            }
        }
    }
}

@Composable
private fun Caret(alpha: Float) {
    Box(Modifier.padding(horizontal = 2.dp).width(3.dp).height(46.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = alpha)))
}

@Composable
private fun NoteChip(k: String, now: Boolean, past: Boolean, onTap: () -> Unit) {
    val s by animateFloatAsState(if (now) 1.18f else 1f, label = "n")
    if (k == " ") {
        Box(Modifier.width(16.dp).height(52.dp).clip(RoundedCornerShape(6.dp)).clickable { onTap() }, contentAlignment = Alignment.Center) {
            Text("·", color = muted(.45f), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        return
    }
    val c = noteColor(k)
    Column(
        Modifier.scale(s).width(40.dp).height(52.dp).clip(RoundedCornerShape(12.dp))
            .background(c.copy(alpha = when { now -> 1f; past -> .5f; else -> .18f }))
            .border(if (now) 2.dp else 0.dp, if (now) MaterialTheme.colorScheme.onBackground else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable { onTap() },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Text(k, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = if (now || past) Color.White else MaterialTheme.colorScheme.onSurface)
        Text(noteName(k), fontSize = 9.sp, color = if (now || past) Color.White.copy(alpha = .9f) else c)
    }
}

@Composable
private fun EditKey(label: String, sub: String, color: Color, mod: Modifier, onClick: () -> Unit) {
    Column(
        mod.height(50.dp).clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = .16f)).clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Text(label, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Text(sub, fontSize = 9.5.sp, color = color, fontWeight = FontWeight.SemiBold)
    }
}
