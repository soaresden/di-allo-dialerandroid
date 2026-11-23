package fr.soaresden.diallo.vvm

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.SubscriptionManager
import fr.soaresden.diallo.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Journal de la messagerie visuelle, pour qu'un utilisateur puisse l'envoyer quand ça ne marche pas chez lui.
 * Jamais d'identifiant, de mot de passe ni de numéro de téléphone : seulement les étapes, durées et erreurs.
 */
internal object VvmLog {
    private const val MAX_LINES = 400
    private val lock = Any()
    private fun file(ctx: Context) = File(File(ctx.noBackupFilesDir, "vvm").apply { mkdirs() }, "journal.txt")

    /** Champs des SMS de Free qu'on peut écrire sans risque (pas u, pw, smtp_*, tui, dn…). */
    private val SAFE_KEYS = setOf("st", "rc", "rs", "ev", "srv", "ipt", "spt", "lang", "pm", "gm", "c", "t", "dt", "l", "id", "vs_len", "g_len", "pw_len")

    fun add(ctx: Context, line: String) {
        val t = SimpleDateFormat("dd/MM HH:mm:ss", Locale.US).format(Date())
        synchronized(lock) {
            try {
                val f = file(ctx)
                val old = if (f.isFile) f.readLines() else emptyList()
                f.writeText((old + "$t $line").takeLast(MAX_LINES).joinToString("\n") + "\n")
            } catch (_: Throwable) { }
        }
    }

    /** « STATUS st=R rc=0 srv=… » sans les champs sensibles. */
    fun sms(kind: String, f: Map<String, String>): String =
        "SMS reçu de Free : $kind " + f.entries.joinToString(" ") { (k, v) -> if (k in SAFE_KEYS) "$k=$v" else "$k=(${v.length} car.)" }

    fun network(ctx: Context): String = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        when {
            caps == null -> "aucun"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "autre"
        }
    } catch (_: Throwable) { "?" }

    /** Texte à envoyer : appareil, SIM (opérateur seulement), puis le journal. */
    fun report(ctx: Context): String {
        val sims = try {
            ctx.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty()
                .joinToString(", ") { "${it.carrierName} (${it.mccString}-${it.mncString})" }
        } catch (_: Throwable) { "?" }
        val head = buildString {
            appendLine("Journal messagerie visuelle Di-Allô")
            appendLine("Version : ${BuildConfig.VERSION_NAME}")
            appendLine("Téléphone : ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("SIM : $sims")
            appendLine("Réseau : ${network(ctx)}")
            appendLine("----")
        }
        val body = synchronized(lock) { try { file(ctx).takeIf { it.isFile }?.readText() } catch (_: Throwable) { null } }
        return head + (body ?: "(journal vide)")
    }
}
