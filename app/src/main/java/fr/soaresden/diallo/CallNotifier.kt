package fr.soaresden.diallo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.telecom.Call

/**
 * Notification d'appel : quand on réduit l'écran d'appel, l'appel reste accessible
 * (pastille verte dans la barre d'état, raccrocher / haut-parleur depuis le volet).
 */
object CallNotifier {
    private const val CH_IN = "diallo_calls_in"
    private const val CH_ON = "diallo_calls_on"
    private const val ID = 4242

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    private fun channels(ctx: Context) {
        val nm = nm(ctx)
        // (re)crée les canaux ; recréer un canal existant ne fait que mettre à jour son nom (changement de langue)
        val inName = tr("Appels entrants"); val onName = tr("Appel en cours")
        if (nm.getNotificationChannel(CH_IN)?.name?.toString() != inName) nm.createNotificationChannel(
            NotificationChannel(CH_IN, inName, NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null); enableVibration(false) }
        )
        if (nm.getNotificationChannel(CH_ON)?.name?.toString() != onName) nm.createNotificationChannel(
            NotificationChannel(CH_ON, onName, NotificationManager.IMPORTANCE_DEFAULT).apply { setSound(null, null); enableVibration(false) }
        )
    }

    fun update(ctx: Context) {
        val call = HyperInCallService.current.value ?: return cancel(ctx)
        val state = HyperInCallService.callState.intValue
        if (state == Call.STATE_DISCONNECTED || state == Call.STATE_DISCONNECTING) return cancel(ctx)
        I18n.ensure(ctx)
        channels(ctx)
        val number = call.details?.handle?.schemeSpecificPart ?: ""
        val title = HyperInCallService.nameOf(call)
        val ringing = state == Call.STATE_RINGING
        val stateText = when (state) {
            Call.STATE_RINGING -> tr("Appel entrant")
            Call.STATE_ACTIVE -> if (HyperInCallService.speakerOn) tr("En cours · haut-parleur") else tr("Appel en cours")
            Call.STATE_HOLDING -> tr("En attente")
            else -> tr("Appel…")
        }

        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(ctx, 1, Intent(ctx, OngoingCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
        val answer = PendingIntent.getActivity(ctx, 2, Intent(ctx, OngoingCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("answer", true), flags)
        fun act(a: String, code: Int) = PendingIntent.getBroadcast(ctx, code, Intent(ctx, CallActionReceiver::class.java).setAction(a), flags)
        val hang = act(CallActionReceiver.HANGUP, 3)
        val spk = act(CallActionReceiver.SPEAKER, 4)

        val b = Notification.Builder(ctx, if (ringing) CH_IN else CH_ON)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle(title)
            .setContentText(stateText)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .setOngoing(true)
            .setOnlyAlertOnce(!ringing)
            .setCategory(Notification.CATEGORY_CALL)
            .setColor(0xFF22C55E.toInt())
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        val connect = call.details?.connectTimeMillis ?: 0L
        if (state == Call.STATE_ACTIVE && connect > 0) b.setWhen(connect).setShowWhen(true).setUsesChronometer(true)

        val spkLabel = if (HyperInCallService.speakerOn) tr("Écouteur") else tr("Haut-parleur")
        val n = try {
            if (Build.VERSION.SDK_INT >= 31) {
                val person = Person.Builder().setName(title).setImportant(true).build()
                b.setStyle(if (ringing) Notification.CallStyle.forIncomingCall(person, hang, answer) else Notification.CallStyle.forOngoingCall(person, hang))
                if (!ringing) b.addAction(Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_stat_call), spkLabel, spk).build())
                b.build()
            } else null
        } catch (_: Throwable) { null } ?: fallback(ctx, title, stateText, open, ringing, answer, hang, spk, spkLabel)

        try { nm(ctx).notify(ID, n) }
        catch (_: Throwable) { try { nm(ctx).notify(ID, fallback(ctx, title, stateText, open, ringing, answer, hang, spk, spkLabel)) } catch (_: Throwable) { } }
    }

    private fun fallback(ctx: Context, title: String, text: String, open: PendingIntent, ringing: Boolean, answer: PendingIntent, hang: PendingIntent, spk: PendingIntent, spkLabel: String): Notification {
        val ic = Icon.createWithResource(ctx, R.drawable.ic_stat_call)
        val b = Notification.Builder(ctx, if (ringing) CH_IN else CH_ON)
            .setSmallIcon(R.drawable.ic_stat_call).setContentTitle(title).setContentText(text)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(!ringing)
            .setCategory(Notification.CATEGORY_CALL).setColor(0xFF22C55E.toInt())
        if (ringing) {
            b.setFullScreenIntent(open, true)
            b.addAction(Notification.Action.Builder(ic, tr("Refuser"), hang).build())
            b.addAction(Notification.Action.Builder(ic, tr("Répondre"), answer).build())
        } else {
            b.addAction(Notification.Action.Builder(ic, spkLabel, spk).build())
            b.addAction(Notification.Action.Builder(ic, tr("Raccrocher"), hang).build())
        }
        return b.build()
    }

    fun cancel(ctx: Context) { try { nm(ctx).cancel(ID) } catch (_: Throwable) { } }
}

/** Boutons de la notification d'appel. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        when (i.action) {
            HANGUP -> HyperInCallService.hangup()
            SPEAKER -> HyperInCallService.setSpeaker(!HyperInCallService.speakerOn)
        }
    }
    companion object {
        const val HANGUP = "fr.soaresden.diallo.HANGUP"
        const val SPEAKER = "fr.soaresden.diallo.SPEAKER"
    }
}
