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
import androidx.compose.material.icons.filled.FiberManualRecord
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions

internal val RecRed = Color(0xFFE5484D)

private fun instrLabel(id: String) = Synth.instruments.firstOrNull { it.id == id }?.let { "${it.emoji} ${it.name}" } ?: "🎵"

private fun copyText(ctx: Context, t: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Mélodie", t))
    Toast.makeText(ctx, "Chiffres copiés", Toast.LENGTH_SHORT).show()
}

/** Liste « Mes mélodies » : lecture, renommer, copier les chiffres, partager, supprimer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MelodySheet(onDismiss: () -> Unit, onRecord: () -> Unit) {
    val ctx = LocalContext.current
    val ver by Melodies.version
    val list = remember(ver) { Melodies.load(ctx) }
    val playing by Melodies.playingId
    var fromText by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Melodies.Melody?>(null) }
    val cs = MaterialTheme.colorScheme

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = cs.surface) {
        Column(Modifier.padding(horizontal = 18.dp)) {
            Text("🎼 Mes mélodies", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = cs.onBackground)
            Text("Joue un air sur le clavier et garde-le, ou tape-le en chiffres.", fontSize = 12.5.sp, color = muted())
            Text(Melodies.LEGEND, fontSize = 11.sp, color = muted(.5f), modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRecord, colors = ButtonDefaults.buttonColors(containerColor = RecRed, contentColor = Color.White), modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.FiberManualRecord, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Enregistrer")
                }
                OutlinedButton(onClick = { fromText = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Keyboard, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("En chiffres")
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
        if (list.isEmpty()) item { Box(Modifier.fillMaxWidth().padding(22.dp), contentAlignment = Alignment.Center) {
            Text("Aucune mélodie pour l'instant 🎹", color = muted(.5f), fontSize = 13.sp)
        } }
            items(list, key = { "m${it.id}" }) { m ->
                val on = playing == m.id
                var menu by remember { mutableStateOf(false) }
                Row(
                    Modifier.fillMaxWidth().clickable { if (on) Melodies.stop() else Melodies.play(m) }.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(42.dp).clip(CircleShape).background(if (on) cs.primary else cs.primaryContainer), contentAlignment = Alignment.Center) {
                        Icon(if (on) Icons.Filled.Stop else Icons.Filled.PlayArrow, if (on) "Arrêter" else "Écouter", tint = if (on) cs.onPrimary else cs.primary)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${m.notes.size} notes · ${fmtMelodyDur(m.durationMs)} · ${instrLabel(m.instr)}", fontSize = 12.sp, color = muted())
                        Text(Melodies.toText(m.notes), fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = muted(.5f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Options", tint = muted(.6f)) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Renommer") }, onClick = { menu = false; renaming = m })
                            DropdownMenuItem(text = { Text("Copier") }, onClick = { menu = false; copyText(ctx, "${m.name} : ${Melodies.toText(m.notes, lines = true)}") })
                            DropdownMenuItem(text = { Text("Partager") }, onClick = {
                                menu = false
                                val t = "${m.name} : ${Melodies.toText(m.notes, lines = true)}\n\n🎹 à jouer sur Di-Allô"
                                try { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t), "Partager la mélodie")) } catch (_: Throwable) { }
                            })
                            DropdownMenuItem(text = { Text("Supprimer", color = RecRed) }, onClick = { menu = false; Melodies.delete(ctx, m.id) })
                        }
                    }
                }
            }
            // ---- Recueil ----
            item {
                Text("📖 Recueil · airs du domaine public", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = cs.primary, modifier = Modifier.padding(start = 18.dp, top = 14.dp, bottom = 2.dp))
                Text("Touche ▶ pour écouter avec ton instrument, ＋ pour l'ajouter à tes mélodies.", fontSize = 11.5.sp, color = muted(.55f), modifier = Modifier.padding(start = 18.dp, bottom = 4.dp))
            }
            items(Melodies.SONGBOOK.size, key = { "p$it" }) { i ->
                val p = Melodies.SONGBOOK[i]
                val pid = -100L - i
                val on = playing == pid
                val notes = remember(i) { p.notes }
                Row(
                    Modifier.fillMaxWidth().clickable { if (on) Melodies.stop() else Melodies.play(pid, p.name, Synth.currentId, notes) }.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(if (on) cs.primary else cs.surfaceVariant), contentAlignment = Alignment.Center) {
                        Icon(if (on) Icons.Filled.Stop else Icons.Filled.PlayArrow, if (on) "Arrêter" else "Écouter", tint = if (on) cs.onPrimary else cs.onSurface, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${notes.size} notes · ${fmtMelodyDur((notes.lastOrNull()?.t ?: 0) + 400)}", fontSize = 11.5.sp, color = muted())
                    }
                    TextButton(onClick = { Melodies.add(ctx, p.name, Synth.currentId, notes); Toast.makeText(ctx, "« ${p.name} » ajoutée 🎼", Toast.LENGTH_SHORT).show() }) { Text("＋", fontSize = 18.sp) }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (fromText) MelodyFromTextDialog { fromText = false }
    renaming?.let { m ->
        var name by remember(m.id) { mutableStateOf(m.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Renommer") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { Button(onClick = { Melodies.rename(ctx, m.id, name); renaming = null }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Annuler") } }
        )
    }
}

/** Fin d'enregistrement : nommer, réécouter, garder ou jeter. */
@Composable
internal fun SaveRecordingDialog(notes: List<Melodies.Note>, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(Melodies.nextName(ctx)) }
    val instr = remember { Synth.currentId }
    val playing by Melodies.playingId
    val dur = (notes.lastOrNull()?.t ?: 0) + 400
    AlertDialog(
        onDismissRequest = { },
        title = { Text("🎙️ Garder la mélodie ?") },
        text = {
            Column {
                Text("${notes.size} notes · ${fmtMelodyDur(dur)} · ${instrLabel(instr)}", fontSize = 13.sp, color = muted())
                Text(Melodies.toText(notes), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = muted(.6f), maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
                OutlinedTextField(name, { name = it }, label = { Text("Nom") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { if (playing == -1L) Melodies.stop() else Melodies.play(-1L, name, instr, notes) }) {
                    Icon(if (playing == -1L) Icons.Filled.Stop else Icons.Filled.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
                    Text(if (playing == -1L) "Arrêter" else "Réécouter")
                }
            }
        },
        confirmButton = { Button(onClick = { Melodies.stop(); Melodies.add(ctx, name, instr, notes); Toast.makeText(ctx, "Mélodie enregistrée 🎼", Toast.LENGTH_SHORT).show(); onDone() }) { Text("Garder") } },
        dismissButton = { TextButton(onClick = { Melodies.stop(); onDone() }) { Text("Jeter", color = RecRed) } }
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
                        Text("Espace = pause · retour à la ligne = respiration", fontSize = 11.5.sp, color = muted(.55f))
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
