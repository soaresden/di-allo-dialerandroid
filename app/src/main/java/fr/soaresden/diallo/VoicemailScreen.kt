package fr.soaresden.diallo

import android.Manifest
import android.media.MediaPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Voicemail
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.soaresden.diallo.vvm.Omtp
import fr.soaresden.diallo.vvm.Vvm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Onglet « Messagerie » : messagerie vocale visuelle de Free (liste, écoute, rappel, suppression). */
@Composable
internal fun VoicemailScreen(contacts: List<CallRepository.Contact>, onCall: (String) -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { Vvm.load(app) } }
    DisposableEffect(Unit) { Vvm.screenOpen = true; Vvm.cancelNotification(app); onDispose { Vvm.screenOpen = false } }

    val msgs by Vvm.messages
    val progress by Vvm.progress
    val error by Vvm.error
    val ready by Vvm.ready
    val waiting by Vvm.waitingStatus
    val last by Vvm.lastSync
    fun syncNow() { scope.launch(Dispatchers.IO) { Vvm.sync(app) } }
    // à l'ouverture : synchro si la dernière date de plus de 5 min
    LaunchedEffect(ready) { if (ready && System.currentTimeMillis() - Vvm.lastSync.value > 5 * 60_000L) syncNow() }

    val smsPerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (Omtp.hasSmsPerms(app)) Vvm.requestStatus(app) else Vvm.error.value = tr("Sans l'autorisation SMS, Di-Allô ne peut pas demander tes messages à Free.")
    }
    fun activate() { if (Omtp.hasSmsPerms(app)) Vvm.requestStatus(app) else smsPerms.launch(arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS)) }

    val byKey = remember(contacts) { contacts.associateBy { keyOf(it.number) } }
    var open by remember { mutableStateOf<Long?>(null) }
    var askDelete by remember { mutableStateOf<List<Vvm.Msg>?>(null) }
    var showTrash by remember { mutableStateOf(false) }
    val inbox = msgs.filter { it.trashed == 0L }
    val bin = msgs.filter { it.trashed > 0 }.sortedByDescending { it.trashed }
    LaunchedEffect(bin.isEmpty()) { if (bin.isEmpty()) showTrash = false }
    val shown = if (showTrash) bin else inbox

    // lecteur unique
    val player = remember { arrayOfNulls<MediaPlayer>(1) }
    var playing by remember { mutableStateOf<Long?>(null) }
    var pos by remember { mutableIntStateOf(0) }
    var len by remember { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    fun stop() { player[0]?.run { try { stop() } catch (_: Throwable) { }; release() }; player[0] = null; playing = null; pos = 0; paused = false }
    DisposableEffect(Unit) { onDispose { stop() } }
    LaunchedEffect(playing) { while (playing != null) { player[0]?.let { try { pos = it.currentPosition } catch (_: Throwable) { } }; delay(200) } }
    fun play(m: Vvm.Msg) {
        if (playing == m.uid) { player[0]?.let { if (it.isPlaying) { it.pause(); paused = true } else { it.start(); paused = false } }; return }
        stop()
        val f = Vvm.audioFile(app, m) ?: run { Vvm.error.value = tr("Audio pas encore téléchargé : relance la synchro."); return }
        try {
            player[0] = MediaPlayer().apply { setDataSource(f.path); prepare(); setOnCompletionListener { pos = 0; playing = null }; start() }
            len = player[0]!!.duration; playing = m.uid
            Vvm.markSeen(app, m)
        } catch (_: Throwable) { stop(); Vvm.error.value = tr("Lecture impossible de ce message.") }
    }

    askDelete?.let { list ->
        AlertDialog(
            onDismissRequest = { askDelete = null },
            title = { Text(if (list.size == 1) tr("Supprimer définitivement ce message ?") else tr("Vider la corbeille ({0} messages) ?", list.size)) },
            text = { Text(tr("Il sera aussi effacé de ta messagerie Free. Pense à l'enregistrer avant si tu veux le garder.")) },
            confirmButton = { Button(onClick = { if (list.any { it.uid == playing }) stop(); Vvm.deleteForever(app, list); askDelete = null }, colors = ButtonDefaults.buttonColors(containerColor = Red, contentColor = Color.White)) { Text(tr("Supprimer")) } },
            dismissButton = { TextButton(onClick = { askDelete = null }) { Text(tr("Annuler")) } }
        )
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (showTrash) tr("Corbeille") else tr("Messagerie vocale"), fontWeight = FontWeight.Bold, fontSize = 17.sp)
                if (showTrash) Text(tr("Les messages restent chez Free tant que tu ne les supprimes pas d'ici."), fontSize = 11.5.sp, color = muted())
                else if (ready && last > 0) Text(tr("Synchro : {0}", fmtWhen(last)), fontSize = 11.5.sp, color = muted())
            }
            if (showTrash) {
                TextButton(onClick = { askDelete = bin }, colors = ButtonDefaults.textButtonColors(contentColor = Red)) { Text(tr("Vider")) }
                IconButton(onClick = { showTrash = false; open = null }) { Icon(Icons.Filled.Close, tr("Fermer la corbeille")) }
            } else {
            if (bin.isNotEmpty()) IconButton(onClick = { showTrash = true; open = null }) {
                BadgedBox(badge = { Badge { Text("${bin.size}") } }) { Icon(Icons.Filled.Delete, tr("Corbeille")) }
            }
            IconButton(onClick = { onCall(Vvm.tuiNumber) }) { Icon(Icons.Filled.Voicemail, tr("Appeler la messagerie")) }
            if (ready) IconButton(onClick = { syncNow() }, enabled = progress == null) { Icon(Icons.Filled.Refresh, tr("Actualiser")) }
            }
        }
        progress?.let { p ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp))
                Text(p, fontSize = 13.sp, color = muted(.8f))
            }
        }
        error?.let { e ->
            Surface(color = Red.copy(alpha = .14f), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(e, Modifier.padding(12.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground)
            }
        }
        if (!ready) {
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(tr("Messagerie visuelle Free"), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(tr("Di-Allô demande à Free l'accès à ta messagerie par un SMS gratuit au 2051, puis télécharge tes messages vocaux par Internet (Wi-Fi ou réseau mobile)."), fontSize = 13.sp, color = muted(.8f))
                    Spacer(Modifier.height(12.dp))
                    if (waiting) Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp))
                        Text(tr("En attente de la réponse de Free…"), fontSize = 13.sp)
                        Spacer(Modifier.weight(1f)); TextButton(onClick = { activate() }) { Text(tr("Renvoyer")) }
                    } else Button(onClick = { activate() }) { Text(tr("Activer")) }
                }
            }
        } else if (shown.isEmpty()) {
            Empty(if (progress != null) tr("Recherche des messages…") else tr("Aucun message vocal."))
        } else LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { it.uid }) { m ->
                val c = byKey[keyOf(m.from)]
                val name = c?.display ?: if (m.from.isBlank()) tr("Numéro masqué") else displayNumber(m.from)
                val isOpen = open == m.uid
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (isOpen) MaterialTheme.colorScheme.surface else Color.Transparent)) {
                    Row(Modifier.fillMaxWidth().clickable { open = if (isOpen) null else m.uid }.padding(vertical = 9.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            Avatar(name, c?.photoUri)
                            if (!m.seen) Box(Modifier.align(Alignment.TopEnd).size(11.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(name, fontWeight = if (m.seen) FontWeight.Medium else FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(if (c != null || m.from.isBlank()) null else flagOf(m.from), if (c != null) displayNumber(m.from) else null, if (m.seconds > 0) fmtDur(m.seconds) else null).joinToString(" · ").ifEmpty { " " },
                                fontSize = 13.sp, color = muted(), maxLines = 1)
                        }
                        Text(fmtWhen(m.date), fontSize = 12.sp, color = muted())
                    }
                    if (isOpen) {
                        val isPlaying = playing == m.uid && !paused
                        Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            FilledIconButton(onClick = { play(m) }) { Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (isPlaying) tr("Pause") else tr("Écouter")) }
                            val total = if (playing == m.uid && len > 0) len else m.seconds * 1000
                            Slider(
                                value = if (playing == m.uid && total > 0) pos.toFloat() / total else 0f,
                                onValueChange = { v -> if (playing == m.uid) player[0]?.let { p -> p.seekTo((v * total).toInt()); pos = (v * total).toInt() } },
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                            )
                            Text(clockOf(if (playing == m.uid) pos else 0) + " / " + clockOf(total), fontSize = 11.5.sp, color = muted())
                        }
                        Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (showTrash) TextButton(onClick = { Vvm.restore(app, m); open = null }) { Icon(Icons.Filled.RestoreFromTrash, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr("Restaurer")) }
                            else if (m.from.any(Char::isDigit)) TextButton(onClick = { onCall(m.from) }) { Icon(Icons.Filled.Call, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr("Rappeler")) }
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = {
                                scope.launch {
                                    val saved = withContext(Dispatchers.IO) { Vvm.saveToFolder(app, m, name) }
                                    val msg = when {
                                        saved != null -> tr("Enregistré dans {0}", (DataFolder.name(app) ?: "DiAllo") + "/Messagerie/" + saved)
                                        DataFolder.uri(app) == null -> tr("Choisis d'abord un dossier de travail (Réglages) pour y ranger tes messages.")
                                        else -> tr("Impossible d'enregistrer ce message.")
                                    }
                                    android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
                                }
                            }) { Icon(Icons.Filled.Download, tr("Enregistrer dans le dossier")) }
                            IconButton(onClick = { if (!Vvm.share(ctx, m, name)) Vvm.error.value = tr("Audio pas encore téléchargé : relance la synchro.") }) { Icon(Icons.Filled.Share, tr("Partager")) }
                            if (showTrash) IconButton(onClick = { askDelete = listOf(m) }) { Icon(Icons.Filled.DeleteForever, tr("Supprimer définitivement"), tint = Red) }
                            else IconButton(onClick = {
                                if (playing == m.uid) stop(); Vvm.trash(app, m); open = null
                                android.widget.Toast.makeText(ctx, tr("Mis à la corbeille"), android.widget.Toast.LENGTH_SHORT).show()
                            }) { Icon(Icons.Filled.Delete, tr("Mettre à la corbeille")) }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

private fun clockOf(ms: Int): String { val s = (ms / 1000).coerceAtLeast(0); return "${s / 60}:${(s % 60).toString().padStart(2, '0')}" }
