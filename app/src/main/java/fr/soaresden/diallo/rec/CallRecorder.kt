package fr.soaresden.diallo.rec

import android.content.Context
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import fr.soaresden.diallo.Recordings
import fr.soaresden.diallo.tr
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

/**
 * Enregistreur d'appels de Di-Allô.
 * Le son de l'appel vient de [RecServer] (lancé avec les droits shell par [AdbLauncher]) ;
 * ici on l'encode en MP3 puis on le range dans Rec/ à la fin de l'appel.
 */
object CallRecorder {
    const val HZ = 16000
    enum class State { IDLE, STARTING, ON }
    /** Mode d'enregistrement : "manual" (bouton 🔴), "all" (tous les appels), "contacts" (contacts seulement). */
    const val MANUAL = "manual"; const val ALL = "all"; const val CONTACTS = "contacts"

    val state = mutableStateOf(State.IDLE)
    val since = mutableLongStateOf(0L)
    val message = mutableStateOf<String?>(null)
    val lastSaved = mutableStateOf<String?>(null)

    @Volatile private var session: Session? = null

    fun prefs(ctx: Context) = ctx.getSharedPreferences("recorder", Context.MODE_PRIVATE)
    fun enabled(ctx: Context) = prefs(ctx).getBoolean("enabled", false)
    fun setEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("enabled", on).apply()
    fun mode(ctx: Context): String = prefs(ctx).getString("mode", MANUAL) ?: MANUAL
    fun setMode(ctx: Context, m: String) = prefs(ctx).edit().putString("mode", m).apply()

    fun port(ctx: Context): Int = prefs(ctx).getInt("port", 0).takeIf { it > 0 }
        ?: (40000 + SecureRandom().nextInt(20000)).also { prefs(ctx).edit().putInt("port", it).apply() }
    fun token(ctx: Context): String = prefs(ctx).getString("token", null)
        ?: ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }.also { prefs(ctx).edit().putString("token", it).apply() }

    /** Commande qui lance le serveur (via ADB, en shell). */
    fun launchCommand(ctx: Context): String =
        "CLASSPATH=${ctx.applicationInfo.sourceDir} setsid app_process / ${RecServer::class.java.name} ${port(ctx)} ${token(ctx)} </dev/null >/dev/null 2>&1 &"

    /** Le serveur répond-il ? (à appeler hors fil principal) */
    fun serverVersion(ctx: Context): Int? = ask(ctx, "PING")?.takeIf { it.startsWith("PONG") }?.substringAfter(' ')?.trim()?.toIntOrNull()

    fun stopServer(ctx: Context) { ask(ctx, "QUIT") }

    private fun ask(ctx: Context, cmd: String): String? = try {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port(ctx)), 700); s.soTimeout = 3000
            s.getOutputStream().write("${token(ctx)} $cmd\n".toByteArray())
            BufferedReader(InputStreamReader(s.getInputStream())).readLine()
        }
    } catch (_: Throwable) { null }

    /** Serveur prêt (le relance par ADB au besoin, à la bonne version). Hors fil principal. */
    fun ensureServer(ctx: Context): Boolean {
        val v = serverVersion(ctx)
        if (v == RecServer.VERSION) return true
        if (v != null) { stopServer(ctx); Thread.sleep(300) }
        repeat(2) { attempt ->
            if (attempt > 0) Thread.sleep(2000)
            if (AdbLauncher.run(ctx, launchCommand(ctx))) {
                repeat(30) { Thread.sleep(200); if (serverVersion(ctx) == RecServer.VERSION) return true }
                AdbLauncher.lastError = tr("Le service ne répond pas")
            }
        }
        return false
    }

    val isOn: Boolean get() = session != null

    /** Démarre l'enregistrement de l'appel en cours. [who] = nom affiché, [number] = numéro. */
    fun start(ctx: Context, who: String, number: String?) {
        if (session != null) return
        val s = Session(ctx.applicationContext, who, number)
        session = s
        state.value = State.STARTING; message.value = null
        s.start()
    }

    fun stop() { session?.halt() }

    fun toggle(ctx: Context, who: String, number: String?) = if (session != null) stop() else start(ctx, who, number)

    private class Session(val ctx: Context, val who: String, val number: String?) : Thread("diallo-rec") {
        @Volatile private var sock: Socket? = null
        @Volatile private var halted = false
        val t0 = System.currentTimeMillis()

        fun halt() { halted = true; try { sock?.close() } catch (_: Throwable) { } }

        override fun run() {
            val tmp = File(ctx.cacheDir, "rec-$t0.mp3")
            var ok = false
            try {
                if (!ensureServer(ctx)) throw IOException(AdbLauncher.lastError ?: tr("Service d'enregistrement injoignable"))
                if (halted) return
                val s = Socket(); sock = s
                s.connect(InetSocketAddress("127.0.0.1", port(ctx)), 1500)
                s.getOutputStream().write("${token(ctx)} REC $HZ\n".toByteArray())
                val inp = s.getInputStream()
                val head = line(inp) ?: throw IOException(tr("Pas de réponse du service"))
                if (!head.startsWith("OK")) throw IOException(head.removePrefix("ERR").trim().ifEmpty { tr("Refusé") })
                since.longValue = System.currentTimeMillis(); CallRecorder.state.value = CallRecorder.State.ON
                ok = encode(inp, tmp)
            } catch (t: Throwable) {
                if (!halted) message.value = "⚠️ " + (t.message ?: t.javaClass.simpleName)
            } finally {
                try { sock?.close() } catch (_: Throwable) { }
                if (ok && tmp.length() > 2048) {
                    val name = Recordings.store(ctx, tmp, t0, who, number) ?: keepLocally(tmp)
                    lastSaved.value = name; message.value = name?.let { tr("✅ Enregistré : {0}", it) } ?: tr("⚠️ Impossible d'enregistrer le fichier")
                }
                tmp.delete()
                session = null; CallRecorder.state.value = CallRecorder.State.IDLE; since.longValue = 0L
            }
        }

        /** Sans dossier de travail : on garde le fichier dans le stockage de l'appli. */
        private fun keepLocally(f: File): String? = try {
            val dir = File(ctx.getExternalFilesDir(null), "Rec").apply { mkdirs() }
            val name = java.text.SimpleDateFormat("yyyy-MM-dd-HHmmss", java.util.Locale.US).format(java.util.Date(t0)) + "-" + who.replace(Regex("""[/\\:*?"<>|]"""), " ") + ".mp3"
            f.copyTo(File(dir, name), overwrite = true); name
        } catch (_: Throwable) { null }

        private fun line(inp: InputStream): String? {
            val sb = StringBuilder()
            while (true) { val c = inp.read(); if (c < 0) return null; if (c == '\n'.code) return sb.toString(); sb.append(c.toChar()); if (sb.length > 200) return null }
        }

        /** Lit exactement [want] octets (moins si fin de flux) ; -1 si plus rien. */
        private fun fill(inp: InputStream, b: ByteArray, want: Int): Int {
            var got = 0
            while (got < want) { val n = try { inp.read(b, got, want - got) } catch (_: IOException) { -1 }; if (n < 0) break; got += n }
            return if (got == 0) -1 else got and 1.inv()   // échantillons entiers (16 bits)
        }

        /** PCM → MP3 (32 kb/s mono, ~14 Mo par heure). */
        private fun encode(inp: InputStream, out: File): Boolean {
            val chunk = ByteArray(HZ / 10 * 2)   // 100 ms
            var samples = 0L
            Mp3Writer(out, HZ).use { w ->
                while (true) {
                    val n = fill(inp, chunk, chunk.size)
                    if (n <= 0) break
                    w.write(chunk, n); samples += n / 2
                }
            }
            return samples > HZ / 2
        }
    }
}
