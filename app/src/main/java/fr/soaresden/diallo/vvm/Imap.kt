package fr.soaresden.diallo.vvm

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Client IMAP minimal pour la messagerie visuelle (IMAPS, port 993).
 *
 * Le serveur de Free met ~30 s avant de répondre au TLS : on laisse donc 2 min à chaque lecture
 * (l'appli Free coupe à 30 s, d'où ses chargements sans fin). On ne garde qu'une session par synchro.
 */
internal class Imap(private val socket: SSLSocket) : AutoCloseable {
    private val inp = BufferedInputStream(socket.inputStream)
    private val out: OutputStream = socket.outputStream
    private var seq = 0

    /** Une réponse non étiquetée (« * … »), avec les littéraux {n} gardés à part en octets. */
    class Untagged(val text: String, val literals: List<ByteArray>)
    class Result(val ok: Boolean, val status: String, val untagged: List<Untagged>)
    class ImapException(msg: String) : IOException(msg)

    companion object {
        const val READ_TIMEOUT_MS = 120_000
        const val CONNECT_TIMEOUT_MS = 20_000
        /** Le certificat de Free couvre *.proxad.net (ex. vvm.free.fr → CN=proxad.net). */
        private val TRUSTED_NAMES = listOf("vvm.proxad.net", "vvm.free.fr")

        /** Connexion TLS sur un socket déjà lié au bon réseau (la 4G). */
        fun open(plain: Socket, host: String, port: Int): Imap {
            plain.soTimeout = READ_TIMEOUT_MS
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain, host, port, true) as SSLSocket
            ssl.soTimeout = READ_TIMEOUT_MS
            ssl.startHandshake()   // chaîne vérifiée par le magasin de certificats d'Android
            val hv = HttpsURLConnection.getDefaultHostnameVerifier()
            if (TRUSTED_NAMES.none { hv.verify(it, ssl.session) } && !hv.verify(host, ssl.session)) {
                ssl.close(); throw ImapException("certificat inattendu : " + ssl.session.peerPrincipal?.name)
            }
            val c = Imap(ssl)
            val greet = c.readLine()
            if (!greet.startsWith("* OK") && !greet.startsWith("* PREAUTH")) { c.close(); throw ImapException("accueil IMAP : " + greet.take(80)) }
            return c
        }

        fun connectPlain(factory: javax.net.SocketFactory, addr: InetSocketAddress): Socket =
            factory.createSocket().apply { connect(addr, CONNECT_TIMEOUT_MS) }

        /** Chaîne IMAP entre guillemets (mot de passe, etc.). */
        fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    private fun readLine(): String {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = inp.read()
            if (c < 0) throw ImapException("connexion fermée par le serveur")
            if (c == '\n'.code) break
            if (c != '\r'.code) b.write(c)
        }
        return b.toString(Charsets.ISO_8859_1.name())
    }

    private fun readExact(n: Int): ByteArray {
        val buf = ByteArray(n); var off = 0
        while (off < n) { val r = inp.read(buf, off, n - off); if (r < 0) throw ImapException("message tronqué"); off += r }
        return buf
    }

    /** Envoie une commande et lit jusqu'à la ligne étiquetée. */
    fun cmd(command: String): Result {
        val tag = "D" + (++seq)
        out.write("$tag $command\r\n".toByteArray(Charsets.ISO_8859_1)); out.flush()
        val untagged = ArrayList<Untagged>()
        while (true) {
            var line = readLine()
            if (line.startsWith("$tag ")) {
                val rest = line.substring(tag.length + 1)
                return Result(rest.startsWith("OK"), rest, untagged)
            }
            if (line.startsWith("+")) continue
            // ligne « * … » pouvant contenir des littéraux {n}
            val text = StringBuilder(); val lits = ArrayList<ByteArray>()
            while (true) {
                val m = Regex("\\{(\\d+)\\}$").find(line)
                if (m == null) { text.append(line); break }
                text.append(line, 0, m.range.first).append("{LIT").append(lits.size).append('}')
                lits.add(readExact(m.groupValues[1].toInt()))
                line = readLine()
            }
            untagged.add(Untagged(text.toString(), lits))
        }
    }

    fun ok(command: String, what: String): Result {
        val r = cmd(command)
        if (!r.ok) throw ImapException("$what : ${r.status.take(120)}")
        return r
    }

    override fun close() {
        try { socket.soTimeout = 5_000; cmd("LOGOUT") } catch (_: Throwable) { }
        try { socket.close() } catch (_: Throwable) { }
    }
}
