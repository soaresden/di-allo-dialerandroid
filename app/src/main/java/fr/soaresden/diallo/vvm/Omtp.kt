package fr.soaresden.diallo.vvm

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager

/**
 * Messagerie visuelle de Free Mobile (OMTP), comme l'appli « Messagerie Vocale Visuelle » de Free :
 *  1. SMS binaire « STATUS:pv=13;ct=free.VVM.11;pt=5499;//VVM » au 2051, port 5499 ;
 *  2. Free répond par SMS binaire sur le port 5499 : « //VVM:STATUS:st=R;rc=0;srv=…;ipt=993;u=…;pw=… » ;
 *  3. à chaque nouveau message, Free envoie « //VVM:SYNC:ev=NM;… » ;
 *  4. les messages se lisent en IMAPS (voir [Imap]), uniquement depuis le réseau mobile de Free.
 */
internal object Omtp {
    const val DEST = "2051"
    const val PORT: Short = 5499
    private const val CLIENT = "free.VVM.11"
    private const val PROTOCOL = "13"   // OMTP 1.3
    private val FREE_MCC_MNC = setOf("20815", "20816")

    fun statusRequest() = "STATUS:pv=$PROTOCOL;ct=$CLIENT;pt=$PORT;//VVM"
    /** Demande d'activation OMTP standard (ce qu'envoie aussi l'appli de Free) : pour une ligne pas encore inscrite. */
    fun activateRequest() = "Activate:pv=$PROTOCOL;ct=$CLIENT;pt=$PORT;//VVM"

    /** Abonnement (SIM) Free actif, s'il y en a un. */
    fun freeSubId(ctx: Context): Int? = try {
        ctx.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList
            ?.firstOrNull { (it.mccString + it.mncString) in FREE_MCC_MNC }?.subscriptionId
    } catch (_: SecurityException) { null }

    fun isFree(ctx: Context) = freeSubId(ctx) != null

    fun hasSmsPerms(ctx: Context) = listOf(Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS)
        .all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Demande à Free l'état de la messagerie (et les identifiants IMAP). La réponse arrive dans [VvmSmsReceiver]. */
    fun sendStatus(ctx: Context): Boolean = send(ctx, statusRequest())
    fun sendActivate(ctx: Context): Boolean = send(ctx, activateRequest())

    private fun send(ctx: Context, text: String): Boolean {
        val what = text.substringBefore(':')
        val sub = freeSubId(ctx) ?: run { VvmLog.add(ctx, "$what pas envoyé : aucune SIM Free trouvée"); return false }
        if (!hasSmsPerms(ctx)) { VvmLog.add(ctx, "$what pas envoyé : autorisation SMS refusée"); return false }
        return try {
            val sms = ctx.getSystemService(SmsManager::class.java).createForSubscriptionId(sub)
            val sent = PendingIntent.getBroadcast(ctx, 0, Intent("fr.soaresden.diallo.VVM_SENT").setPackage(ctx.packageName), PendingIntent.FLAG_IMMUTABLE)
            sms.sendDataMessage(DEST, null, PORT, text.toByteArray(Charsets.UTF_8), sent, null)
            VvmLog.add(ctx, "SMS $what envoyé au $DEST (port $PORT, client $CLIENT)")
            true
        } catch (e: Throwable) { VvmLog.add(ctx, "SMS $what : échec d'envoi ${e.javaClass.simpleName} ${e.message}"); false }
    }

    /** « //VVM:STATUS:st=R;rc=0;… » → ("STATUS", {st=R, rc=0, …}). */
    fun parse(text: String): Pair<String, Map<String, String>>? {
        val t = text.trim()
        if (!t.startsWith("//VVM:")) return null
        val kind = t.removePrefix("//VVM:").substringBefore(':')
        val body = t.removePrefix("//VVM:$kind:")
        val map = body.split(';').mapNotNull { kv ->
            val i = kv.indexOf('='); if (i <= 0) null else kv.substring(0, i).trim() to kv.substring(i + 1).trim()
        }.toMap()
        return kind to map
    }
}

/** SMS binaires de Free sur le port 5499 (déclaré dans le manifeste). */
class VvmSmsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.DATA_SMS_RECEIVED_ACTION) return
        val parts = try { Telephony.Sms.Intents.getMessagesFromIntent(intent) } catch (_: Throwable) { null } ?: return
        val bytes = parts.filterNotNull().fold(ByteArray(0)) { acc, m -> acc + (m.userData ?: ByteArray(0)) }
        val app = ctx.applicationContext
        val (kind, map) = Omtp.parse(String(bytes, Charsets.UTF_8)) ?: run { VvmLog.add(app, "SMS port 5499 reçu mais illisible (${bytes.size} octets)"); return }
        VvmLog.add(app, VvmLog.sms(kind, map))
        when (kind) {
            "STATUS" -> Vvm.onStatus(app, map)
            "SYNC" -> Vvm.onSync(app, map)
        }
    }
}
