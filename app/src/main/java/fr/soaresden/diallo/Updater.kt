package fr.soaresden.diallo

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Mises à jour depuis les Releases GitHub du projet : vérifie la dernière version publiée,
 * prévient s'il y en a une plus récente, télécharge l'APK et lance l'installation.
 * Seule requête faite : l'API publique GitHub du dépôt (aucune donnée personnelle envoyée).
 */
object Updater {
    private const val API = "https://api.github.com/repos/soaresden/di-allo-dialerandroid/releases/latest"
    data class Release(val version: String, val apk: String, val notes: String, val page: String)

    val available = mutableStateOf<Release?>(null)
    val progress = mutableStateOf<Float?>(null)        // téléchargement en cours (0..1)
    val showDialog = mutableStateOf(false)

    private fun p(c: Context) = c.getSharedPreferences("updater", Context.MODE_PRIVATE)
    fun auto(c: Context) = p(c).getBoolean("auto", true)
    fun setAuto(c: Context, on: Boolean) = p(c).edit().putBoolean("auto", on).apply()
    private fun skipped(c: Context) = p(c).getString("skip", null)
    fun skip(c: Context, v: String) { p(c).edit().putString("skip", v).apply(); available.value = null }

    /** Versions « aaaa-mm-jj-NN » : l'ordre alphabétique est l'ordre chronologique. */
    private fun newer(remote: String, local: String) = remote.isNotBlank() && local.isNotBlank() && remote > local

    /** Interroge GitHub (au plus toutes les 6 h, sauf [force]). Retourne la release plus récente, ou null. Hors fil principal. */
    fun check(ctx: Context, force: Boolean = false): Release? {
        val now = System.currentTimeMillis()
        if (!force && (!auto(ctx) || now - p(ctx).getLong("last", 0) < 6 * 3_600_000L)) return available.value
        p(ctx).edit().putLong("last", now).apply()
        return try {
            val c = (URL(API).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000; readTimeout = 8000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Di-Allo-app")
            }
            val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val v = o.optString("tag_name").removePrefix("v")
            val assets = o.optJSONArray("assets")
            var apk = ""
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) { apk = a.optString("browser_download_url"); break }
            }
            val local = appVersion(ctx)
            val r = Release(v, apk, o.optString("body"), o.optString("html_url"))
            if (apk.isNotEmpty() && newer(v, local) && (force || skipped(ctx) != v)) { available.value = r; r } else { if (force) available.value = null; null }
        } catch (_: Throwable) { null }
    }

    /** Télécharge l'APK (notification système) puis ouvre l'installeur. */
    suspend fun install(ctx: Context, r: Release) {
        val app = ctx.applicationContext
        if (!app.packageManager.canRequestPackageInstalls()) {
            // Android demande une fois d'autoriser Di-Allô à installer des applis
            try { ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + app.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) { }
            return
        }
        val dm = app.getSystemService(DownloadManager::class.java) ?: return
        val name = "Di-Allo-${r.version}.apk"
        app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.listFiles()?.forEach { if (it.name.endsWith(".apk")) it.delete() }
        val id = dm.enqueue(
            DownloadManager.Request(Uri.parse(r.apk))
                .setTitle("Di-Allô ${r.version}")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, name)
        )
        progress.value = 0f
        try {
            while (true) {
                delay(400)
                val st = withContext(Dispatchers.IO) {
                    dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                        if (!c.moveToFirst()) null else Triple(
                            c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                            c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                            c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        )
                    }
                } ?: break
                if (st.third > 0) progress.value = st.second.toFloat() / st.third
                if (st.first == DownloadManager.STATUS_FAILED) break
                if (st.first == DownloadManager.STATUS_SUCCESSFUL) {
                    val uri = dm.getUriForDownloadedFile(id) ?: break
                    ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                    break
                }
            }
        } finally { progress.value = null }
    }
}

/** Fenêtre « mise à jour disponible » : nouveautés + Télécharger. */
@Composable
internal fun UpdateDialog() {
    val ctx = LocalContext.current
    val r = Updater.available.value ?: return
    if (!Updater.showDialog.value) return
    val scope = rememberCoroutineScope()
    val prog = Updater.progress.value
    AlertDialog(
        onDismissRequest = { Updater.showDialog.value = false },
        title = { Text("⬆️ " + tr("Di-Allô {0}", r.version)) },
        text = {
            Column {
                Text(tr("Ta version : {0}", appVersion(ctx)), fontSize = 12.sp, color = muted(.6f))
                Spacer(Modifier.height(8.dp))
                Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                    r.notes.lines().filter { it.isNotBlank() }.forEach { Text(it.replace("**", ""), fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp)) }
                }
                if (prog != null) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(progress = { prog }, modifier = Modifier.fillMaxWidth())
                    Text(tr("Téléchargement… {0} %", (prog * 100).toInt()), fontSize = 12.sp, color = muted(.6f), modifier = Modifier.padding(top = 4.dp))
                }
            }
        },
        confirmButton = {
            Button(onClick = { scope.launch { Updater.install(ctx, r) } }, enabled = prog == null) { Text(tr("Télécharger et installer"), fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            TextButton(onClick = { Updater.skip(ctx, r.version); Updater.showDialog.value = false }) { Text(tr("Ignorer cette version")) }
        }
    )
}
