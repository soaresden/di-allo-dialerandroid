package fr.soaresden.diallo.rec

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Après un redémarrage, relance le service d'enregistrement (le serveur shell ne survit pas au reboot). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        if (!CallRecorder.enabled(ctx) || !AdbLauncher.paired(ctx)) return
        val res = goAsync()
        Thread {
            try {
                // le Wi-Fi met parfois un moment à revenir après le démarrage
                repeat(6) { if (CallRecorder.ensureServer(ctx.applicationContext)) return@Thread; Thread.sleep(10_000) }
            } finally { res.finish() }
        }.start()
    }
}
