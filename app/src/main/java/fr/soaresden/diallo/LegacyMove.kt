package fr.soaresden.diallo

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Version « pont » (BuildConfig.LEGACY) : dernière mise à jour des anciennes installations GitHub.
 * À chaque ouverture, elle explique que cette version ne sera plus mise à jour et guide vers le Play Store :
 * sauvegarder, ouvrir la page du Store, désinstaller cette version (le Store ne peut pas installer par-dessus), installer.
 * L'appli reste utilisable (« Plus tard ») : c'est le téléphone, on ne bloque jamais les appels.
 */
@Composable
internal fun LegacyMoveDialog() {
    if (!BuildConfig.LEGACY) return
    val ctx = LocalContext.current
    var show by rememberSaveable { mutableStateOf(true) }
    val fv by DataFolder.folderVersion
    val folder = remember(fv) { DataFolder.name(ctx) }
    val busy by DataFolder.busy
    val status by DataFolder.status
    val pick = rememberFolderPicker { }
    if (!show) return
    AlertDialog(
        onDismissRequest = { },
        title = { Text(tr("📦 Di-Allô passe au Play Store"), fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(tr("Cette version ne recevra plus de mises à jour. La suite de Di-Allô est sur le Play Store, mises à jour automatiques comprises."), fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                Step("1", tr("Sauvegarde tes notes et tes réglages dans ton dossier de travail."))
                Row(Modifier.padding(start = 26.dp, bottom = 6.dp)) {
                    if (folder == null) OutlinedButton(onClick = pick) { Text(tr("Choisir un dossier"), fontSize = 13.sp) }
                    else OutlinedButton(onClick = { DataFolder.syncNow(ctx); DataFolder.backupNowAsync(ctx) }, enabled = busy == null) { Text(tr("Sauvegarder"), fontSize = 13.sp) }
                }
                (busy ?: status)?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 26.dp, bottom = 6.dp)) }
                Step("2", tr("Ouvre la page Di-Allô du Play Store."))
                Row(Modifier.padding(start = 26.dp, bottom = 6.dp)) {
                    OutlinedButton(onClick = { openPlayStore(ctx) }) { Text(tr("Play Store"), fontSize = 13.sp) }
                }
                Step("3", tr("Désinstalle cette version : le Play Store ne peut pas l'installer par-dessus."))
                Row(Modifier.padding(start = 26.dp, bottom = 6.dp)) {
                    OutlinedButton(onClick = { try { ctx.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + ctx.packageName))) } catch (_: Throwable) { } }) { Text(tr("Désinstaller"), fontSize = 13.sp) }
                }
                Step("4", tr("Reviens dans le Play Store et installe Di-Allô. Choisis le même dossier de travail et touche « Restaurer » : tout revient."))
                Spacer(Modifier.height(4.dp))
                Text(tr("Pendant la phase de test, rejoins d'abord le groupe des testeurs pour voir Di-Allô dans le Play Store."), fontSize = 12.sp, color = muted(.65f))
                TextButton(onClick = { open(ctx, "https://groups.google.com/g/diallodialer") }, contentPadding = PaddingValues(0.dp)) { Text(tr("Rejoindre les testeurs"), fontSize = 13.sp) }
            }
        },
        confirmButton = { TextButton(onClick = { show = false }) { Text(tr("Plus tard")) } },
    )
}

@Composable
private fun Step(n: String, text: String) {
    Row(Modifier.padding(bottom = 4.dp)) {
        Text(n, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(26.dp))
        Text(text, fontSize = 13.5.sp)
    }
}

private fun openPlayStore(ctx: Context) {
    val id = "fr.soaresden.diallo"
    try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$id")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    catch (_: Throwable) { open(ctx, "https://play.google.com/store/apps/details?id=$id") }
}

private fun open(ctx: Context, url: String) {
    try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) { }
}
