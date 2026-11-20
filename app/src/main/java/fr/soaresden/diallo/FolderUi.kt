package fr.soaresden.diallo

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Choix du dossier + proposition de restauration si le dossier contient déjà des données. */
@Composable
internal fun rememberFolderPicker(onRestored: () -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var found by remember { mutableStateOf<DataFolder.Found?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u: Uri? ->
        if (u == null) return@rememberLauncherForActivityResult
        DataFolder.choose(ctx, u)
        scope.launch {
            val f = withContext(Dispatchers.IO) { DataFolder.inspect(ctx) }
            if (f != null && (f.notes > 0 || f.settings > 0)) found = f
            else withContext(Dispatchers.IO) { DataFolder.syncNow(ctx); DataFolder.autoBackupIfDue(ctx) }
        }
    }
    found?.let { f ->
        AlertDialog(
            onDismissRequest = { found = null },
            icon = { Icon(Icons.Filled.Restore, null) },
            title = { Text("Données Di-Allô trouvées") },
            text = {
                Text(
                    "Ce dossier contient :\n• ${f.notes} note${if (f.notes > 1) "s" else ""}\n• ${f.settings} réglages" +
                        (if (f.date > 0) " (du ${fmtDate(f.date)})" else "") + "\n• ${f.backups} sauvegarde${if (f.backups > 1) "s" else ""} d'appels / SMS\n\n" +
                        "Restaurer les notes et les réglages ? (les notes actuelles sont gardées)"
                )
            },
            confirmButton = {
                Button(onClick = {
                    found = null
                    scope.launch {
                        withContext(Dispatchers.IO) { DataFolder.restore(ctx); DataFolder.syncNow(ctx) }
                        onRestored()
                    }
                }) { Text("Restaurer") }
            },
            dismissButton = {
                TextButton(onClick = { found = null; scope.launch(Dispatchers.IO) { DataFolder.syncNow(ctx) } }) { Text("Non, écraser avec l'actuel") }
            }
        )
    }
    return { launcher.launch(null) }
}

/** Demandée au premier lancement (et tant qu'aucun dossier n'est choisi). */
@Composable
internal fun FolderPrompt(onRestored: () -> Unit) {
    val ctx = LocalContext.current
    var show by remember { mutableStateOf(DataFolder.uri(ctx) == null) }
    val pick = rememberFolderPicker(onRestored)
    if (!show) return
    AlertDialog(
        onDismissRequest = { show = false },
        icon = { Icon(Icons.Filled.Folder, null) },
        title = { Text("📁 Dossier de travail") },
        text = {
            Text(
                "Choisis (ou crée) un dossier sur le téléphone, par exemple « Di-Allo ».\n\n" +
                    "Di-Allô y range automatiquement tes notes, tes réglages et une sauvegarde complète des appels et des SMS/MMS.\n\n" +
                    "Après une réinstallation, rechoisis ce dossier : tout revient."
            )
        },
        confirmButton = { Button(onClick = { show = false; pick() }) { Text("Choisir le dossier") } },
        dismissButton = { TextButton(onClick = { show = false }) { Text("Plus tard") } }
    )
}

/** Carte des réglages : dossier, sauvegarde auto, sauvegarder / restaurer. */
@Composable
internal fun FolderCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val fv by DataFolder.folderVersion
    val name = remember(fv) { DataFolder.name(ctx) }
    val busy by DataFolder.busy
    val status by DataFolder.status
    var auto by remember { mutableStateOf(DataFolder.autoBackup(ctx)) }
    val pick = rememberFolderPicker { (ctx as? Activity)?.recreate() }
    var smsOk by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) }
    val smsPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> smsOk = ok; if (ok) DataFolder.backupNowAsync(ctx) }

    SectionCard("📁 Dossier & sauvegardes", id = "folder") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name ?: "Aucun dossier choisi", fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, color = if (name == null) Amber else MaterialTheme.colorScheme.onSurface)
                Text("Notes, réglages et sauvegardes y sont rangés", fontSize = 12.sp, color = muted())
            }
            Button(onClick = pick, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) { Text(if (name == null) "Choisir" else "Changer", fontSize = 13.sp) }
        }
        if (name != null) {
            Spacer(Modifier.height(10.dp))
            SwitchLine("Sauvegarde automatique", "Une fois par jour : journal d'appels complet + SMS + MMS (photos comprises). Les 7 dernières sont gardées.", auto) { auto = it; DataFolder.setAutoBackup(ctx, it) }
            if (!smsOk) Text("⚠️ Autorise la lecture des SMS pour les sauvegarder aussi.", fontSize = 12.sp, color = Amber, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { if (smsOk) DataFolder.backupNowAsync(ctx) else smsPerm.launch(Manifest.permission.READ_SMS) }, enabled = busy == null, modifier = Modifier.weight(1f)) { Text("Sauvegarder", fontSize = 13.sp) }
                OutlinedButton(onClick = {
                    scope.launch { withContext(Dispatchers.IO) { DataFolder.restore(ctx) }; (ctx as? Activity)?.recreate() }
                }, enabled = busy == null, modifier = Modifier.weight(1f)) { Text("Restaurer", fontSize = 13.sp) }
            }
            busy?.let { Spacer(Modifier.height(8.dp)); Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text(it, fontSize = 13.sp, color = muted(.75f)) } }
            status?.let { Text(it, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) }
            val last = DataFolder.lastBackup(ctx)
            if (last > 0) Text("Dernière sauvegarde complète : ${fmtWhen(last)}", fontSize = 12.sp, color = muted(.6f), modifier = Modifier.padding(top = 4.dp))
            Text("« Restaurer » remet les notes et les réglages. Pour le journal d'appels ou les SMS, utilise « Choisir un backup » ci-dessous et prends un fichier du dossier Sauvegardes.", fontSize = 11.5.sp, color = muted(.55f), modifier = Modifier.padding(top = 8.dp))
        }
    }
}
