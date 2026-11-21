package fr.soaresden.diallo

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.material3.IconButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private fun clock(ms: Int): String { val s = (ms / 1000).coerceAtLeast(0); return "${s / 60}:${(s % 60).toString().padStart(2, '0')}" }

/** Petite icône 🎙️ à côté d'un appel enregistré ; un appui ouvre le lecteur. */
@Composable
internal fun RecBadge(callId: Long, title: String) {
    val map by Recordings.byCall
    val r = map[callId] ?: return
    Box(
        Modifier.padding(end = 6.dp).size(26.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer)
            .clickable { Recordings.open.value = r to title },
        contentAlignment = Alignment.Center
    ) { Text("🎙️", fontSize = 12.sp) }
}

/** Une ligne d'enregistrement (date · taille) ; un appui ouvre le lecteur. */
@Composable
private fun RecRow(r: Recordings.Rec, showWho: Boolean) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { Recordings.open.value = r to r.who }.padding(vertical = 7.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.PlayArrow, "Écouter", tint = cs.primary, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(if (showWho) r.who else fmtWhen(r.time), fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text((if (showWho) fmtWhen(r.time) + " · " else "") + Recordings.fmtSize(r.size) + " · " + r.name.substringAfterLast('.').uppercase(), fontSize = 11.5.sp, color = muted())
        }
        IconButton(onClick = { Recordings.askDelete.value = r }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.DeleteOutline, "Supprimer", tint = muted(.5f), modifier = Modifier.size(19.dp))
        }
    }
}

/** Fiche contact : ses enregistrements. */
@Composable
internal fun ContactRecordings(key: String) {
    val all by Recordings.all
    val list = remember(all, key) { all.filter { it.key == key } }
    if (list.isEmpty()) return
    var showAll by remember { mutableStateOf(false) }
    SectionCard("🎙️ Enregistrements (${list.size})", id = "contact_rec") {
        (if (showAll) list else list.take(5)).forEach { RecRow(it, showWho = false) }
        if (list.size > 5) TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Moins" else "Voir les ${list.size}") }
    }
}

/** Onglet REC : les enregistrements regroupés par contact. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RecScreen(contacts: List<CallRepository.Contact>, onCall: (String) -> Unit, onSettings: () -> Unit) {
    val all by Recordings.all
    val status by Recordings.status
    val busy by Recordings.busy
    val byKey = remember(contacts) { contacts.associateBy { keyOf(it.number) } }
    var q by rememberSaveable { mutableStateOf("") }
    val open = remember { mutableStateMapOf<String, Boolean>() }
    val ctx = LocalContext.current
    var byDate by remember { mutableStateOf(prefs(ctx).getBoolean("rec_by_date", false)) }
    val shown = remember(all, q) {
        val t = q.trim()
        all.filter { t.isEmpty() || it.who.contains(t, true) || (it.number?.filter { c -> c.isDigit() }?.contains(t.filter { c -> c.isDigit() }.ifEmpty { "§" }) == true) }
            .sortedByDescending { it.time }
    }
    // par contact : le correspondant du dernier enregistrement en haut (se recale si on supprime)
    val groups = remember(shown) { shown.groupBy { it.key }.entries.sortedByDescending { e -> e.value.maxOf { it.time } } }
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        OutlinedTextField(
            q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text("Chercher un contact, un numéro…") }, singleLine = true,
            shape = RoundedCornerShape(16.dp), leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = { if (q.isNotEmpty()) IconButton(onClick = { q = "" }) { Icon(Icons.Filled.Close, "Effacer") } }
        )
        Segmented(listOf("Par contact", "Par date"), if (byDate) 1 else 0, { byDate = it == 1; prefs(ctx).edit().putBoolean("rec_by_date", byDate).apply() }, Modifier.fillMaxWidth().padding(top = 8.dp))
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${all.size} enregistrement${if (all.size > 1) "s" else ""} · ${groups.size} correspondant${if (groups.size > 1) "s" else ""}", fontSize = 12.sp, color = muted(.55f), modifier = Modifier.weight(1f))
            if (busy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            TextButton(onClick = onSettings) { Text("Réglages", fontSize = 12.sp) }
        }
        status?.let { Text(it, fontSize = 12.sp, color = cs.primary, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)) }
        if (all.isEmpty()) {
            Empty(if (busy) "Lecture des enregistrements…" else "Aucun enregistrement.\nPendant un appel, touche 🔴 Enregistrer (à activer dans Réglages › Pendant l'appel › Enregistrement).")
        } else if (byDate) LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { "d_${it.name}" }) { r -> RecRow(r, showWho = true) }
            item { Spacer(Modifier.height(20.dp)) }
        } else LazyColumn(Modifier.fillMaxSize()) {
            groups.forEach { (k, list) ->
                val r0 = list.first()
                val c = byKey[k]
                val name = c?.display ?: r0.who
                val isOpen = open[k] ?: (groups.size <= 3)
                item(key = "h_$k") {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(12.dp)).background(cs.surface).clickable { open[k] = !isOpen }.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Avatar(name, c?.photoUri, 38.dp); Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(name, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${list.size} enregistrement${if (list.size > 1) "s" else ""} · dernier ${fmtWhen(r0.time)}", fontSize = 11.5.sp, color = muted())
                        }
                        r0.number?.let { n ->
                            Box(Modifier.size(34.dp).clip(CircleShape).background(CallGreen).clickable { onCall(n) }, contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Call, "Appeler", tint = Color.White, modifier = Modifier.size(17.dp))
                            }
                            Spacer(Modifier.width(6.dp))
                        }
                        Chevron(!isOpen)
                    }
                }
                if (isOpen) items(list, key = { "r_${it.name}" }) { r -> Box(Modifier.padding(start = 14.dp)) { RecRow(r, showWho = false) } }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

/** Lecteur d'un enregistrement d'appel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecordingPlayer() {
    val ctx = LocalContext.current
    Recordings.askDelete.value?.let { r ->
        AlertDialog(
            onDismissRequest = { Recordings.askDelete.value = null },
            title = { Text("Supprimer l'enregistrement ?") },
            text = { Text("${r.who} · ${fmtWhen(r.time)}\nLe fichier sera effacé du dossier Rec.") },
            confirmButton = {
                Button(onClick = {
                    Recordings.askDelete.value = null
                    if (!Recordings.delete(ctx, r)) android.widget.Toast.makeText(ctx, "Suppression impossible", android.widget.Toast.LENGTH_SHORT).show()
                }, colors = ButtonDefaults.buttonColors(containerColor = Red, contentColor = Color.White)) { Text("Supprimer") }
            },
            dismissButton = { TextButton(onClick = { Recordings.askDelete.value = null }) { Text("Annuler") } }
        )
    }
    val open by Recordings.open
    val (rec, title) = open ?: return
    val cs = MaterialTheme.colorScheme
    var player by remember(rec.uri) { mutableStateOf<MediaPlayer?>(null) }
    var error by remember(rec.uri) { mutableStateOf<String?>(null) }
    var playing by remember(rec.uri) { mutableStateOf(false) }
    var pos by remember(rec.uri) { mutableIntStateOf(0) }
    var dur by remember(rec.uri) { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf<Float?>(null) }

    DisposableEffect(rec.uri) {
        val mp = MediaPlayer()
        try {
            mp.setDataSource(ctx, rec.uri)
            mp.setOnPreparedListener { dur = it.duration; it.start(); playing = true }
            mp.setOnCompletionListener { playing = false; pos = dur }
            mp.setOnErrorListener { _, _, _ -> error = "Lecture impossible de ce fichier."; true }
            mp.prepareAsync()
            player = mp
        } catch (_: Throwable) { error = "Fichier illisible ou plus accessible." }
        onDispose { try { mp.release() } catch (_: Throwable) { } }
    }
    LaunchedEffect(rec.uri, playing) { while (playing) { player?.let { pos = try { it.currentPosition } catch (_: Throwable) { pos } }; delay(200) } }

    ModalBottomSheet(onDismissRequest = { Recordings.open.value = null }, containerColor = cs.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("🎙️ $title", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(fmtWhen(rec.time) + (Recordings.fmtSize(rec.size).takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "") + (rec.number?.let { " · " + phoneText(it) } ?: ""), fontSize = 12.5.sp, color = muted())
            Text("Rec/" + rec.name, fontSize = 11.sp, color = muted(.45f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(14.dp))
            if (error != null) Text(error!!, color = Red, fontSize = 13.sp)
            Slider(
                value = dragging ?: (if (dur > 0) pos.toFloat() / dur else 0f),
                onValueChange = { dragging = it },
                onValueChangeFinished = { dragging?.let { f -> player?.seekTo((f * dur).toInt()); pos = (f * dur).toInt() }; dragging = null },
                enabled = dur > 0
            )
            Row(Modifier.fillMaxWidth()) {
                Text(clock(dragging?.let { (it * dur).toInt() } ?: pos), fontSize = 12.sp, color = muted())
                Spacer(Modifier.weight(1f))
                Text(clock(dur), fontSize = 12.sp, color = muted())
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { player?.let { val p = (it.currentPosition - 10_000).coerceAtLeast(0); it.seekTo(p); pos = p } }) { Icon(Icons.Filled.Replay10, "Reculer de 10 s") }
                Spacer(Modifier.width(18.dp))
                Box(
                    Modifier.size(64.dp).clip(CircleShape).background(cs.primary).clickable(enabled = player != null && dur > 0) {
                        player?.let { if (it.isPlaying) { it.pause(); playing = false } else { if (pos >= dur) it.seekTo(0); it.start(); playing = true } }
                    },
                    contentAlignment = Alignment.Center
                ) { Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Lecture", tint = cs.onPrimary, modifier = Modifier.size(34.dp)) }
                Spacer(Modifier.width(18.dp))
                IconButton(onClick = { player?.let { val p = (it.currentPosition + 10_000).coerceAtMost(dur); it.seekTo(p); pos = p } }) { Icon(Icons.Filled.Forward10, "Avancer de 10 s") }
            }
            OutlinedButton(onClick = {
                val s = Intent(Intent.ACTION_SEND).setType(ctx.contentResolver.getType(rec.uri) ?: "audio/*").putExtra(Intent.EXTRA_STREAM, rec.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try { ctx.startActivity(Intent.createChooser(s, "Partager l'enregistrement")) } catch (_: Throwable) { }
            }, modifier = Modifier.fillMaxWidth()) { Text("📤 Partager") }
            TextButton(onClick = { Recordings.askDelete.value = rec }, modifier = Modifier.fillMaxWidth()) { Text("🗑️ Supprimer", color = Red) }
        }
    }
}

/** Réglages › Enregistrement des appels : l'enregistreur et le dossier Rec. */
@Composable
internal fun RecordingSettings() {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val fv by Recordings.folderVersion
    val all by Recordings.all
    val linked by Recordings.byCall
    val busy by Recordings.busy
    var go by remember { mutableIntStateOf(0) }
    val workdir = remember(fv) { DataFolder.name(ctx) }
    LaunchedEffect(go) {
        if (go == 0) return@LaunchedEffect
        val data = withContext(Dispatchers.IO) { CallRepository.load(ctx) }
        withContext(Dispatchers.IO) { Recordings.refresh(ctx, data) }
    }

    Text(
        "Les enregistrements sont rangés dans ton dossier, sous Rec/ (aaaa-mm-jj-hhmmss-NOM.mp3), et s'affichent avec les appels (🎙️), sur la fiche du contact et dans l'onglet REC.",
        fontSize = 12.5.sp, color = muted(.75f), modifier = Modifier.padding(bottom = 12.dp)
    )
    if (workdir == null) Text("⚠️ Choisis d'abord un dossier de travail (Réglages › Sauvegardes) : Rec/ y sera créé.", fontSize = 12.5.sp, color = Amber, modifier = Modifier.padding(bottom = 10.dp))
    fr.soaresden.diallo.rec.RecorderCard()
    SectionCard("Dossier Rec", collapsible = false) {
        Text("📁 ${workdir ?: "?"}/Rec", fontWeight = FontWeight.SemiBold, color = cs.onSurface)
        Text("${all.size} enregistrement${if (all.size > 1) "s" else ""} · ${linked.size} relié${if (linked.size > 1) "s" else ""} à un appel", fontSize = 13.sp, color = cs.primary, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { go++ }, enabled = !busy) { Text(if (busy) "Lecture…" else "🔄 Relire le dossier") }
            if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        }
    }
    Text("⚖️ Enregistrer un appel pour soi est toléré ; le diffuser sans l'accord de l'autre personne est interdit. Préviens ton correspondant.", fontSize = 11.5.sp, color = muted(.5f), textAlign = TextAlign.Start, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
}
