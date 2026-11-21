package fr.soaresden.diallo.rec

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.mutableStateOf
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.android.AdbMdns
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Di-Allô se connecte au « Débogage sans fil » de son propre téléphone (comme le ferait un PC)
 * pour lancer [RecServer] avec les droits shell. Pas de Shizuku.
 *  - une seule fois : association avec le code affiché par Android (notification avec champ de saisie) ;
 *  - ensuite : connexion automatique (en Wi-Fi), et Di-Allô peut activer/couper le débogage sans fil
 *    lui-même grâce à WRITE_SECURE_SETTINGS, qu'il s'accorde à la première connexion.
 */
object AdbLauncher {
    @Volatile var lastError: String? = null
    val pairing = mutableStateOf<String?>(null)   // texte d'état de l'association, pour l'écran de réglages

    private fun files(ctx: Context) = File(ctx.filesDir, "adb").apply { mkdirs() }
    fun paired(ctx: Context) = CallRecorder.prefs(ctx).getBoolean("paired", false)
    fun canToggle(ctx: Context) = ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED
    fun wifiDebugOn(ctx: Context) = try { Settings.Global.getInt(ctx.contentResolver, "adb_wifi_enabled", 0) == 1 } catch (_: Throwable) { false }
    private fun setWifiDebug(ctx: Context, on: Boolean) = try { Settings.Global.putInt(ctx.contentResolver, "adb_wifi_enabled", if (on) 1 else 0) } catch (_: Throwable) { false }

    // ---------- Clés et certificat (générés une fois, gardés dans l'appli) ----------
    private class Manager(ctx: Context) : AbsAdbConnectionManager() {
        private val key: PrivateKey
        private val cert: Certificate
        init {
            setApi(Build.VERSION.SDK_INT)
            setTimeout(10, TimeUnit.SECONDS)
            val kf = File(files(ctx), "key.pk8"); val cf = File(files(ctx), "cert.der")
            if (kf.exists() && cf.exists()) {
                key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(kf.readBytes()))
                cert = CertificateFactory.getInstance("X.509").generateCertificate(cf.inputStream())
            } else {
                val g = KeyPairGenerator.getInstance("RSA"); g.initialize(2048, SecureRandom())
                val kp = g.generateKeyPair()
                val der = selfSigned(kp.public.encoded, kp.private)
                kf.writeBytes(kp.private.encoded); cf.writeBytes(der)
                key = kp.private
                cert = CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream())
            }
        }
        override fun getPrivateKey() = key
        override fun getCertificate() = cert
        override fun getDeviceName() = "Di-Allo"
    }

    @Volatile private var mgr: Manager? = null
    private fun manager(ctx: Context): Manager = mgr ?: synchronized(this) { mgr ?: Manager(ctx.applicationContext).also { mgr = it } }

    // ---------- Association (une fois) ----------
    /** Cherche le port d'association annoncé tant que l'écran « Associer avec un code » est ouvert. */
    private fun discover(ctx: Context, type: String, waitMs: Long): Int? {
        var port: Int? = null
        val latch = CountDownLatch(1)
        val m = AdbMdns(ctx, type) { _, p -> if (p > 0) { port = p; latch.countDown() } }
        m.start()
        try { latch.await(waitMs, TimeUnit.MILLISECONDS) } finally { m.stop() }
        return port
    }

    /** Associe Di-Allô au débogage sans fil. [port] facultatif (sinon détecté). Hors fil principal. */
    fun pair(ctx: Context, code: String, port: Int? = null): Boolean {
        pairing.value = "Association…"
        return try {
            val p = port ?: discover(ctx, AdbMdns.SERVICE_TYPE_TLS_PAIRING, 10_000)
                ?: throw IllegalStateException("Écran « Associer avec un code » introuvable : laisse-le ouvert pendant la saisie")
            if (!manager(ctx).pair("127.0.0.1", p, code.trim())) throw IllegalStateException("Code refusé")
            CallRecorder.prefs(ctx).edit().putBoolean("paired", true).apply()
            pairing.value = "✅ Associé. Lancement du service…"
            // juste après l'association, le débogage sans fil met un instant à accepter la connexion
            var ok = false
            for (i in 0 until 4) { Thread.sleep(if (i == 0) 1500L else 3000L); if (CallRecorder.ensureServer(ctx)) { ok = true; break } }
            pairing.value = if (ok) "✅ Prêt : les appels peuvent être enregistrés" else "Associé, mais lancement impossible : ${lastError ?: "?"}"
            true
        } catch (t: Throwable) {
            pairing.value = "⚠️ " + (t.message ?: t.javaClass.simpleName); false
        }
    }

    // ---------- Lancement d'une commande shell ----------
    /** Exécute [cmd] en shell via le débogage sans fil. Hors fil principal. */
    fun run(ctx: Context, cmd: String): Boolean {
        lastError = null
        if (!paired(ctx)) { lastError = "Enregistrement pas encore configuré (Réglages › Enregistrement)"; return false }
        val wasOn = wifiDebugOn(ctx)
        var turnedOn = false
        try {
            if (!wasOn) {
                if (!canToggle(ctx)) { lastError = "Active le « Débogage sans fil » (Options pour les développeurs)"; return false }
                turnedOn = setWifiDebug(ctx, true)
                Thread.sleep(1500)
            }
            val m = manager(ctx)
            if (!m.isConnected) {
                val last = CallRecorder.prefs(ctx).getInt("adb_port", 0)
                val ok = (last > 0 && try { m.connect("127.0.0.1", last) } catch (_: Throwable) { false }) ||
                    (discover(ctx, AdbMdns.SERVICE_TYPE_TLS_CONNECT, 8000)?.let { p ->
                        CallRecorder.prefs(ctx).edit().putInt("adb_port", p).apply()
                        try { m.connect("127.0.0.1", p) } catch (_: Throwable) { false }
                    } ?: false)
                if (!ok) { lastError = "Débogage sans fil injoignable (Wi-Fi connecté ?)"; return false }
            }
            // une fois pour toutes : de quoi activer/couper le débogage sans fil sans passer par les réglages
            if (!canToggle(ctx)) shell(m, "pm grant ${ctx.packageName} android.permission.WRITE_SECURE_SETTINGS")
            shell(m, cmd)
            return true
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            return false
        } finally {
            try { mgr?.disconnect() } catch (_: Throwable) { }
            if (turnedOn) setWifiDebug(ctx, false)
        }
    }

    private fun shell(m: AbsAdbConnectionManager, cmd: String): String {
        val st = m.openStream("shell:$cmd")
        val out = ByteArrayOutputStream()
        try { st.openInputStream().use { it.copyTo(out) } } catch (_: Throwable) { }
        try { st.close() } catch (_: Throwable) { }
        return out.toString()
    }

    // ---------- Notification pour saisir le code d'association ----------
    private const val CH = "rec_setup"; private const val NID = 4242
    const val ACTION_CODE = "fr.soaresden.diallo.PAIR_CODE"; const val KEY_CODE = "code"

    fun askCode(ctx: Context, text: String = "Dans « Débogage sans fil », touche « Associer l'appareil avec un code », puis tape le code ici.") {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null) nm.createNotificationChannel(NotificationChannel(CH, "Configuration de l'enregistrement", NotificationManager.IMPORTANCE_HIGH))
        val ri = RemoteInput.Builder(KEY_CODE).setLabel("Code d'association (6 chiffres)").build()
        val pi = PendingIntent.getBroadcast(ctx, 77, Intent(ctx, PairReceiver::class.java).setAction(ACTION_CODE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        val act = android.app.Notification.Action.Builder(null, "Saisir le code", pi).addRemoteInput(ri).build()
        val n = android.app.Notification.Builder(ctx, CH)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Di-Allô · Enregistrement des appels")
            .setContentText(text).setStyle(android.app.Notification.BigTextStyle().bigText(text))
            .addAction(act).setOngoing(true).build()
        nm.notify(NID, n)
    }

    fun note(ctx: Context, text: String, ongoing: Boolean = false) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.notify(NID, android.app.Notification.Builder(ctx, CH).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Di-Allô · Enregistrement des appels").setContentText(text).setStyle(android.app.Notification.BigTextStyle().bigText(text))
            .setOngoing(ongoing).setAutoCancel(true).build())
    }

    class PairReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, i: Intent) {
            val code = RemoteInput.getResultsFromIntent(i)?.getCharSequence(KEY_CODE)?.toString()?.filter { it.isDigit() } ?: return
            note(ctx, "Association en cours…", ongoing = true)
            val res = goAsync()
            Thread {
                try {
                    pair(ctx.applicationContext, code)
                    val msg = pairing.value ?: ""
                    if (msg.startsWith("⚠️")) askCode(ctx, "$msg\nRéessaie : « Associer l'appareil avec un code » puis tape le nouveau code.")
                    else note(ctx, msg)
                } finally { res.finish() }
            }.start()
        }
    }

    // ---------- Certificat X.509 auto-signé minimal (DER écrit à la main) ----------
    private fun der(tag: Int, body: ByteArray): ByteArray {
        val n = body.size
        val len = when { n < 0x80 -> byteArrayOf(n.toByte()); n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte()); n < 0x10000 -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte()); else -> byteArrayOf(0x83.toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte()) }
        return byteArrayOf(tag.toByte()) + len + body
    }
    private fun seq(vararg p: ByteArray) = der(0x30, p.fold(ByteArray(0)) { a, b -> a + b })
    private val SHA256_RSA = byteArrayOf(0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B)
    private val NULL = byteArrayOf(0x05, 0x00)
    private fun name(cn: String) = seq(der(0x31, seq(byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03), der(0x0C, cn.toByteArray()))))

    private fun selfSigned(spki: ByteArray, key: PrivateKey): ByteArray {
        val alg = seq(SHA256_RSA, NULL)
        val tbs = seq(
            der(0xA0, der(0x02, byteArrayOf(2))),
            der(0x02, BigInteger(63, SecureRandom()).add(BigInteger.ONE).toByteArray()),
            alg, name("Di-Allo"),
            seq(der(0x17, "200101000000Z".toByteArray()), der(0x17, "491231235959Z".toByteArray())),
            name("Di-Allo"), spki
        )
        val sig = Signature.getInstance("SHA256withRSA").apply { initSign(key); update(tbs) }.sign()
        return seq(tbs, alg, der(0x03, byteArrayOf(0) + sig))
    }
}
