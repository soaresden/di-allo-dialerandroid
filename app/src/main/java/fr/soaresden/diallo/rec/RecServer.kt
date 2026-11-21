package fr.soaresden.diallo.rec

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * Petit serveur lancé avec les droits « shell » (via ADB, par app_process), seul utilisateur
 * autorisé à capter le son d'un appel (CAPTURE_AUDIO_OUTPUT). Il ne fait que ça : ouvrir le micro
 * d'appel et envoyer le PCM brut à Di-Allô, qui encode et range le fichier lui-même.
 *
 * Lancement : CLASSPATH=<apk> app_process / fr.soaresden.diallo.rec.RecServer <port> <jeton>
 * Protocole (TCP 127.0.0.1, une ligne de texte puis éventuellement du PCM) :
 *   "<jeton> PING"        → "PONG <version>"
 *   "<jeton> REC <hz>"    → "OK <hz> <source>" puis PCM 16 bits mono en continu, jusqu'à fermeture
 *   "<jeton> QUIT"        → arrêt du serveur
 *   "<jeton> PROBE <hz>"  → teste les sources et répond une ligne de diagnostic (dev)
 */
object RecServer {
    const val VERSION = 1

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.firstOrNull() == "probe") { println(probe(args.getOrNull(1)?.toIntOrNull() ?: 16000)); return }
        val port = args.getOrNull(0)?.toIntOrNull() ?: run { println("usage: RecServer <port> <token> | probe"); return }
        val token = args.getOrNull(1) ?: return
        val server = try { ServerSocket(port, 4, InetAddress.getByName("127.0.0.1")) } catch (t: Throwable) { println("ERR bind $t"); return }
        println("READY $port v$VERSION")
        while (true) {
            val s = try { server.accept() } catch (_: Throwable) { continue }
            thread(isDaemon = true) { handle(s, token) }
        }
    }

    private fun handle(s: Socket, token: String) {
        try {
            s.soTimeout = 5000
            val line = BufferedReader(InputStreamReader(s.getInputStream())).readLine() ?: return
            val parts = line.trim().split(' ')
            if (parts.size < 2 || parts[0] != token) return
            val out = s.getOutputStream()
            fun say(t: String) { out.write((t + "\n").toByteArray()); out.flush() }
            when (parts[1]) {
                "PING" -> say("PONG $VERSION")
                "QUIT" -> { say("BYE"); s.close(); System.exit(0) }
                "PROBE" -> say(probe(parts.getOrNull(2)?.toIntOrNull() ?: 16000))
                "REC" -> {
                    val hz = parts.getOrNull(2)?.toIntOrNull() ?: 16000
                    val (rec, src) = open(hz) ?: run { say("ERR micro d'appel indisponible"); return }
                    s.soTimeout = 0
                    try {
                        rec.startRecording()
                        say("OK $hz $src")
                        val buf = ByteArray(hz / 10 * 2)   // 100 ms
                        while (true) {
                            val n = rec.read(buf, 0, buf.size)
                            if (n < 0) break
                            if (n > 0) out.write(buf, 0, n)
                        }
                    } catch (_: Throwable) {
                    } finally { try { rec.stop() } catch (_: Throwable) { }; rec.release() }
                }
            }
        } catch (_: Throwable) {
        } finally { try { s.close() } catch (_: Throwable) { } }
    }

    private val SOURCES = listOf(MediaRecorder.AudioSource.VOICE_CALL to "VOICE_CALL", MediaRecorder.AudioSource.VOICE_DOWNLINK to "DOWNLINK")

    /** Ouvre la meilleure source d'appel disponible. */
    private fun open(hz: Int): Pair<AudioRecord, String>? {
        for ((src, name) in SOURCES) {
            val r = make(src, hz) ?: continue
            if (r.state == AudioRecord.STATE_INITIALIZED) return r to name
            r.release()
        }
        return null
    }

    private fun make(src: Int, hz: Int): AudioRecord? = try {
        val min = AudioRecord.getMinBufferSize(hz, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(hz)
        AudioRecord(src, hz, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 4)
    } catch (_: Throwable) { null }

    private fun probe(hz: Int): String = (SOURCES + (MediaRecorder.AudioSource.MIC to "MIC")).joinToString(" | ") { (src, name) ->
        val r = make(src, hz) ?: return@joinToString "$name:throw"
        try {
            if (r.state != AudioRecord.STATE_INITIALIZED) return@joinToString "$name:uninit"
            r.startRecording()
            val buf = ShortArray(hz / 2); var got = 0
            val t0 = System.currentTimeMillis()
            while (got < buf.size && System.currentTimeMillis() - t0 < 1500) { val n = r.read(buf, got, buf.size - got); if (n <= 0) break; got += n }
            val rms = if (got > 0) sqrt(buf.take(got).sumOf { it.toDouble() * it } / got).toInt() else -1
            "$name:state=${r.recordingState} read=$got rms=$rms"
        } catch (t: Throwable) { "$name:${t.javaClass.simpleName}" } finally { try { r.stop() } catch (_: Throwable) { }; r.release() }
    }

    /** Mode test en ligne de commande : app_process … RecServer probe */
    @JvmStatic
    fun probeMain() = println(probe(16000))
}
