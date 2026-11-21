package fr.soaresden.diallo

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

internal val RecRed = Color(0xFFE5484D)

private fun instrLabel(id: String) = Synth.instruments.firstOrNull { it.id == id }?.let { "${it.emoji} ${it.name}" } ?: "🎵"

private fun copyText(ctx: Context, t: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Mélodie", t))
    Toast.makeText(ctx, "Mélodie copiée", Toast.LENGTH_SHORT).show()
}

/**
 * Mode composeur : prend la place de la liste des contacts au-dessus du clavier.
 * Mes mélodies (lecture, modifier, copier, partager, supprimer) puis le recueil, repliable.
 */
@Composable
internal fun MelodyLibrary(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val ver by Melodies.version
    val list = remember(ver) { Melodies.load(ctx) }
    val playing by Melodies.playingId
    var fromText by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Melodies.Melody?>(null) }
    var bookClosed by rememberCollapsed("songbook", true)
    val cs = MaterialTheme.colorScheme

    Box(modifier) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(vertical = 6.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("🎼 Mes mélodies", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = cs.onBackground)
                    Text("Joue : ça s'écrit en bas. ␣ = pause, ⌫ = corriger.", fontSize = 11.5.sp, color = muted(.55f))
                }
                TextButton(onClick = { fromText = true }) { Icon(Icons.Filled.Keyboard, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("En chiffres", fontSize = 13.sp) }
            }
        }
        if (list.isEmpty()) item {
            Text("Aucune mélodie pour l'instant 🎹", color = muted(.5f), fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(16.dp))
        }
        items(list, key = { "m${it.id}" }) { m ->
            val on = playing == m.id
            var menu by remember { mutableStateOf(false) }
            Row(
                Modifier.fillMaxWidth().clickable { if (on) Melodies.stop() else Melodies.play(m) }.padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(38.dp).clip(CircleShape).background(if (on) cs.primary else cs.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(if (on) Icons.Filled.Stop else Icons.Filled.PlayArrow, if (on) "Arrêter" else "Écouter", tint = if (on) cs.onPrimary else cs.primary, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(m.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${m.notes.size} notes · ${fmtMelodyDur(m.durationMs)} · ${instrLabel(m.instr)}", fontSize = 11.5.sp, color = muted())
                }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) { Icon(Icons.Filled.MoreVert, "Options", tint = muted(.6f)) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("✏️ Modifier") }, onClick = { menu = false; Melodies.stop(); editing = m })
                        DropdownMenuItem(text = { Text("📋 Copier") }, onClick = { menu = false; copyText(ctx, "${m.name} : ${Melodies.toText(m.notes, lines = true)}") })
                        DropdownMenuItem(text = { Text("📤 Partager") }, onClick = {
                            menu = false
                            val t = "${m.name} : ${Melodies.toText(m.notes, lines = true)}\n\n🎹 à jouer sur Di-Allô"
                            try { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t), "Partager la mélodie")) } catch (_: Throwable) { }
                        })
                        DropdownMenuItem(text = { Text("🗑️ Supprimer", color = RecRed) }, onClick = { menu = false; Melodies.delete(ctx, m.id) })
                    }
                }
            }
        }
        // ---- Recueil, replié par défaut ----
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp).clickable { bookClosed = !bookClosed }.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("📖 Recueil · ${Melodies.SONGBOOK.size} airs du domaine public", fontWeight = FontWeight.Bold, fontSize = 13.5.sp, color = cs.primary)
                    if (!bookClosed) Text("▶ écouter avec ton instrument · ＋ ajouter à tes mélodies", fontSize = 11.sp, color = muted(.55f))
                }
                Chevron(bookClosed)
            }
        }
        if (!bookClosed) items(Melodies.SONGBOOK.size, key = { "p$it" }) { i ->
            val p = Melodies.SONGBOOK[i]
            val pid = -100L - i
            val on = playing == pid
            val notes = remember(i) { p.notes }
            Row(
                Modifier.fillMaxWidth().clickable { if (on) Melodies.stop() else Melodies.play(pid, p.name, Synth.currentId, notes) }.padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(if (on) cs.primary else cs.surfaceVariant), contentAlignment = Alignment.Center) {
                    Icon(if (on) Icons.Filled.Stop else Icons.Filled.PlayArrow, if (on) "Arrêter" else "Écouter", tint = if (on) cs.onPrimary else cs.onSurface, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, fontWeight = FontWeight.Medium, fontSize = 13.5.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${notes.size} notes · ${fmtMelodyDur((notes.lastOrNull()?.t ?: 0) + 400)}", fontSize = 11.sp, color = muted())
                }
                TextButton(onClick = { Melodies.add(ctx, p.name, Synth.currentId, notes); Toast.makeText(ctx, "« ${p.name} » ajoutée 🎼", Toast.LENGTH_SHORT).show() }) { Text("＋", fontSize = 18.sp) }
            }
        }
        item { Text(Melodies.LEGEND, fontSize = 10.5.sp, color = muted(.4f), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(10.dp)) }
    }
    LazyScrollbar(listState, Modifier.align(Alignment.CenterEnd).padding(vertical = 10.dp))
    }

    if (fromText) MelodyFromTextDialog { fromText = false }
    editing?.let { m -> MelodyEditDialog(m.notes, Melodies.toText(m.notes, lines = true), existing = m) { editing = null } }
}

/** Karaoké : la ligne en cours, notes jouées en couleur, note actuelle en gros, barre d'avancement. */
@Composable
internal fun KaraokeDisplay(h: Dp) {
    val notes by Melodies.playingNotes
    val idx by Melodies.playingIndex
    val name by Melodies.playingName
    val cs = MaterialTheme.colorScheme
    val (text, pos) = remember(notes) { Melodies.layout(notes) }
    val cur = if (idx in pos.indices) pos[idx] else -1
    // ligne en cours
    val lineStart = if (cur < 0) 0 else text.lastIndexOf('\n', cur - 1).let { if (it < 0) 0 else it + 1 }
    val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
    val line = text.substring(lineStart, lineEnd)
    val c = if (cur < 0) -1 else cur - lineStart
    // fenêtre autour de la note en cours si la ligne est longue
    val win = 18
    val from = if (line.length <= win || c < 0) 0 else (c - win / 2).coerceIn(0, line.length - win)
    val to = (from + win).coerceAtMost(line.length)
    val shown = buildAnnotatedString {
        if (from > 0) withStyle(SpanStyle(color = muted(.3f))) { append("…") }
        for (i in from until to) {
            val ch = line[i].toString()
            when {
                i == c -> withStyle(SpanStyle(color = cs.onPrimary, background = cs.primary, fontWeight = FontWeight.Bold)) { append(ch) }
                c >= 0 && i < c -> withStyle(SpanStyle(color = cs.primary, fontWeight = FontWeight.Medium)) { append(ch) }
                else -> withStyle(SpanStyle(color = muted(.45f))) { append(ch) }
            }
        }
        if (to < line.length) withStyle(SpanStyle(color = muted(.3f))) { append("…") }
    }
    val nextLine = if (lineEnd < text.length) text.substring(lineEnd + 1).substringBefore('\n') else ""
    val progress = if (notes.isEmpty()) 0f else ((idx + 1).toFloat() / notes.size).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth().height(h).padding(horizontal = 18.dp), verticalArrangement = Arrangement.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🎶 ${name ?: ""}", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = cs.primary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("${(idx + 1).coerceAtLeast(0)} / ${notes.size}", fontSize = 11.5.sp, color = muted(.55f))
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Filled.Stop, "Arrêter", tint = cs.primary, modifier = Modifier.size(22.dp).clip(CircleShape).clickable { Melodies.stop() })
        }
        Text(shown, fontSize = 30.sp, letterSpacing = 3.sp, fontFamily = FontFamily.Monospace, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        if (nextLine.isNotBlank()) Text(nextLine.take(30), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = muted(.35f), maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Box(Modifier.fillMaxWidth().padding(top = 3.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(cs.surfaceVariant)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(cs.primary))
        }
    }
}

/**
 * Garder une composition, ou modifier une mélodie existante ([existing]) :
 * nom, touches modifiables, rythme joué (d'origine) ou régulier.
 */
@Composable
internal fun MelodyEditDialog(played: List<Melodies.Note>, typed: String, existing: Melodies.Melody? = null, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(existing?.name ?: Melodies.nextName(ctx)) }
    var text by remember { mutableStateOf(typed) }
    val instr = remember { existing?.instr ?: Synth.currentId }
    val playing by Melodies.playingId
    val med = remember { played.zipWithNext { a, b -> b.t - a.t }.filter { it > 40 }.sorted().let { it.getOrNull(it.size / 2) ?: 300 }.coerceIn(120, 700) }
    var step by remember { mutableFloatStateOf(med.toFloat()) }
    val changed = text.trim() != typed.trim()
    var asPlayed by remember { mutableStateOf(true) }
    val useRhythm = asPlayed && !changed
    val notes = if (useRhythm) played else remember(text, step) { Melodies.fromText(text, step.toInt()) }
    val dur = (notes.lastOrNull()?.t ?: 0) + 400
    val previewId = if (existing != null) -3L else -1L
    AlertDialog(
        onDismissRequest = { },
        title = { Text(if (existing != null) "✏️ Modifier la mélodie" else "🎵 Garder la composition ?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text("Nom") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    text, { v -> text = v.replace("\r", "").filter { it.toString() in Melodies.VALID || it == ' ' || it == '\n' } },
                    label = { Text("Touches (modifiables)") },
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 17.sp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), maxLines = 7, modifier = Modifier.fillMaxWidth()
                )
                Text("${notes.size} notes · ${fmtMelodyDur(dur)} · ${instrLabel(instr)}", fontSize = 12.sp, color = muted(), modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(8.dp))
                Segmented(listOf(if (existing != null) "🎵 Rythme d'origine" else "🎵 Rythme joué", "📏 Régulier"), if (useRhythm) 0 else 1, { asPlayed = it == 0 })
                if (changed) Text("Touches modifiées : rythme régulier (espace ou retour à la ligne = pause).", fontSize = 11.5.sp, color = muted(.55f), modifier = Modifier.padding(top = 4.dp))
                if (!useRhythm) {
                    Text("Tempo : ${(60000 / step).toInt()} notes / min", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 6.dp))
                    Slider(step, { step = it }, valueRange = 120f..700f)
                }
                TextButton(enabled = notes.isNotEmpty(), onClick = { if (playing == previewId) Melodies.stop() else Melodies.play(previewId, name, instr, notes) }) {
                    Icon(if (playing == previewId) Icons.Filled.Stop else Icons.Filled.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
                    Text(if (playing == previewId) "Arrêter" else "Écouter")
                }
            }
        },
        confirmButton = {
            Button(enabled = notes.isNotEmpty(), onClick = {
                Melodies.stop()
                if (existing != null) Melodies.update(ctx, existing.copy(name = name.trim().ifBlank { existing.name }, notes = notes))
                else Melodies.add(ctx, name, instr, notes)
                Toast.makeText(ctx, if (existing != null) "Mélodie modifiée ✏️" else "Mélodie enregistrée 🎼", Toast.LENGTH_SHORT).show()
                onDone()
            }) { Text(if (existing != null) "Enregistrer" else "Garder") }
        },
        dismissButton = {
            TextButton(onClick = { Melodies.stop(); onDone() }) { if (existing != null) Text("Annuler") else Text("Jeter", color = RecRed) }
        }
    )
}

/** Créer une mélodie en tapant les chiffres : « 624 624 68756752132 », espace = pause. */
@Composable
internal fun MelodyFromTextDialog(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(Melodies.nextName(ctx)) }
    var digits by remember { mutableStateOf("") }
    var step by remember { mutableFloatStateOf(300f) }
    val instr = remember { Synth.currentId }
    val playing by Melodies.playingId
    val notes = remember(digits, step) { Melodies.fromText(digits, step.toInt()) }
    AlertDialog(
        onDismissRequest = { Melodies.stop(); onDone() },
        title = { Text("⌨️ Mélodie en chiffres") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Nom") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    digits, { v ->
                        // texte collé « Nom : chiffres » → le nom va dans son champ
                        if (v.contains(':')) { val (n, d) = Melodies.parsePasted(v); n?.let { name = it }; digits = d }
                        else digits = v.replace("\r", "").filter { it.toString() in Melodies.VALID || it == ' ' || it == '\n' }
                    },
                    label = { Text("Touches") }, placeholder = { Text("ex. 624 624 687") },
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 17.sp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), maxLines = 7,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Espace = pause · retour à la ligne = pause aussi", fontSize = 11.5.sp, color = muted(.55f))
                        Text(Melodies.LEGEND, fontSize = 10.5.sp, color = muted(.45f))
                    }
                    TextButton(onClick = {
                        val clip = (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                        val t = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                        if (t.isBlank()) Toast.makeText(ctx, "Presse-papiers vide", Toast.LENGTH_SHORT).show()
                        else { val (n, d) = Melodies.parsePasted(t); n?.let { name = it }; digits = d }
                    }) { Text("📋 Coller") }
                }
                Spacer(Modifier.height(10.dp))
                Text("Tempo : ${(60000 / step).toInt()} notes / min", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                Slider(step, { step = it }, valueRange = 120f..700f)
                TextButton(enabled = notes.isNotEmpty(), onClick = { if (playing == -2L) Melodies.stop() else Melodies.play(-2L, name, instr, notes) }) {
                    Icon(if (playing == -2L) Icons.Filled.Stop else Icons.Filled.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
                    Text(if (playing == -2L) "Arrêter" else "Écouter (${instrLabel(instr)})")
                }
            }
        },
        confirmButton = { Button(enabled = notes.isNotEmpty(), onClick = { Melodies.stop(); Melodies.add(ctx, name, instr, notes); onDone() }) { Text("Garder") } },
        dismissButton = { TextButton(onClick = { Melodies.stop(); onDone() }) { Text("Annuler") } }
    )
}
