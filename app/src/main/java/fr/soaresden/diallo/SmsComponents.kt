package fr.soaresden.diallo

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat

/*
 * Composants minimaux exigés par Android pour que Di-Allô puisse devenir
 * TEMPORAIREMENT l'app SMS par défaut (seule façon d'écrire des SMS restaurés).
 * Pendant ce temps, les SMS reçus sont enregistrés dans la boîte de réception
 * pour ne rien perdre. Il faut ensuite remettre Google Messages par défaut.
 */

/** SMS reçu pendant que Di-Allô est l'app SMS : on l'enregistre + notification. */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (msgs.isEmpty()) return
        val from = msgs[0].displayOriginatingAddress ?: ""
        val body = msgs.joinToString("") { it.displayMessageBody ?: "" }
        try {
            ctx.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, ContentValues().apply {
                put(Telephony.Sms.ADDRESS, from); put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, System.currentTimeMillis()); put(Telephony.Sms.DATE_SENT, msgs[0].timestampMillis)
                put(Telephony.Sms.READ, 0); put(Telephony.Sms.SEEN, 0)
            })
        } catch (_: Throwable) { }
        notify(ctx, from, body)
    }

    private fun notify(ctx: Context, from: String, body: String) {
        try {
            I18n.ensure(ctx)
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("sms", tr("SMS reçus"), NotificationManager.IMPORTANCE_HIGH))
            nm.notify(from.hashCode(), NotificationCompat.Builder(ctx, "sms")
                .setSmallIcon(android.R.drawable.sym_action_chat)
                .setContentTitle(tr("SMS de {0}", from))
                .setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true).build())
        } catch (_: Throwable) { }
    }
}

/** MMS : on ne les gère pas (rien à faire, Messages les récupérera une fois remis par défaut). */
class MmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) { }
}

/** "Répondre par SMS" depuis l'écran d'appel entrant. */
class RespondViaMessageService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val to = intent?.data?.schemeSpecificPart?.substringBefore('?')
            val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
            if (!to.isNullOrBlank() && !text.isNullOrBlank()) {
                @Suppress("DEPRECATION") val sm = getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
                sm.sendTextMessage(to, null, text, null, null)
            }
        } catch (_: Throwable) { }
        stopSelf(startId)
        return START_NOT_STICKY
    }
}

/** Écran affiché si on tente d'écrire un SMS pendant que Di-Allô est l'app SMS. */
class ComposeSmsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        I18n.ensure(this)
        setContent {
            HyperTheme(loadTheme(this)) {
                Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(tr("Di-Allô n'est pas une messagerie"), fontWeight = FontWeight.Bold, fontSize = 20.sp, color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(10.dp))
                    Text(tr("Il est devenu l'app SMS par défaut seulement pour restaurer ton backup. Remets Google Messages par défaut pour écrire tes SMS."), color = MaterialTheme.colorScheme.onBackground.copy(alpha = .7f), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = { openDefaultApps(this@ComposeSmsActivity); finish() }) { Text(tr("Changer l'app SMS par défaut")) }
                    TextButton(onClick = { finish() }) { Text(tr("Fermer")) }
                }
            }
        }
    }
}

internal fun openDefaultApps(ctx: Context) {
    try { ctx.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    catch (_: Throwable) { try { ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) { } }
}

internal fun isDefaultSms(ctx: Context): Boolean = Telephony.Sms.getDefaultSmsPackage(ctx) == ctx.packageName
