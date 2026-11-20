package fr.soaresden.diallo

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Garde la trace du dernier plantage pour l'afficher au lancement suivant (pas besoin d'ADB). */
object CrashLog {
    @Volatile private var installed = false
    private fun file(ctx: Context) = File(ctx.filesDir, "crash.txt")

    fun install(ctx: Context) {
        if (installed) return
        installed = true
        val app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                val ver = try { app.packageManager.getPackageInfo(app.packageName, 0).versionName } catch (_: Throwable) { "?" }
                file(app).writeText(
                    "Di-Allô $ver · Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.FRANCE).format(Date()) + " · thread ${t.name}\n\n" + sw
                )
            } catch (_: Throwable) { }
            prev?.uncaughtException(t, e)
        }
    }

    fun pending(ctx: Context): String? = file(ctx).takeIf { it.exists() }?.let { try { it.readText() } catch (_: Throwable) { null } }

    /** Range le rapport dans le dossier de travail (Logs/) puis l'efface du téléphone. */
    fun archive(ctx: Context, text: String) {
        try { DataFolder.saveBytes(ctx, "Logs", "plantage-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt", text.toByteArray()) } catch (_: Throwable) { }
        file(ctx).delete()
    }
}

/** Au lancement : « Di-Allô a planté la dernière fois » avec le rapport à copier / partager. */
@Composable
internal fun CrashReport() {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf(CrashLog.pending(ctx)) }
    val t = text ?: return
    fun done() { val x = t; text = null; Thread { CrashLog.archive(ctx, x) }.start() }
    AlertDialog(
        onDismissRequest = { done() },
        title = { Text("😬 Di-Allô a planté") },
        text = {
            androidx.compose.foundation.layout.Column {
                Text("Envoie ce rapport pour que le bug soit corrigé :", fontSize = 13.sp)
                Text(
                    t.take(6000), fontSize = 10.5.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "Plantage Di-Allô").putExtra(Intent.EXTRA_TEXT, t)
                try { ctx.startActivity(Intent.createChooser(send, "Envoyer le rapport")) } catch (_: Throwable) { }
                done()
            }) { Text("Partager") }
        },
        dismissButton = {
            TextButton(onClick = {
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Plantage Di-Allô", t))
                Toast.makeText(ctx, "Rapport copié", Toast.LENGTH_SHORT).show()
                done()
            }) { Text("Copier") }
        }
    )
}
