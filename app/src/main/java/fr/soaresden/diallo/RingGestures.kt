package fr.soaresden.diallo

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.telecom.Call
import android.telecom.TelecomManager
import androidx.compose.runtime.mutableStateOf
import kotlin.math.abs

/**
 * Gestes pendant la sonnerie :
 *  - porter le téléphone à l'oreille → décroche ;
 *  - le retourner face contre la table → coupe sonnerie ET vibration
 *    (puis double vibration quand cet appel est terminé : on peut le reprendre).
 */
object RingGestures : SensorEventListener {

    data class Cfg(val raise: Boolean = true, val flip: Boolean = true, val flipEndVib: Boolean = true, val endVib: Boolean = false)

    fun cfg(ctx: Context): Cfg = prefs(ctx).let {
        Cfg(it.getBoolean("g_raise", true), it.getBoolean("g_flip", true), it.getBoolean("g_flipvib", true), it.getBoolean("g_endvib", false))
    }
    fun save(ctx: Context, c: Cfg) {
        prefs(ctx).edit().putBoolean("g_raise", c.raise).putBoolean("g_flip", c.flip).putBoolean("g_flipvib", c.flipEndVib).putBoolean("g_endvib", c.endVib).apply()
    }

    /** La sonnerie de l'appel entrant a été coupée (retourné ou bouton). */
    val silenced = mutableStateOf(false)
    /** Appels coupés par retournement : double vibration quand ils se terminent. */
    private val flipped = HashSet<Call>()

    private var sm: SensorManager? = null
    private var app: Context? = null
    private var c = Cfg()
    private var threshold = 5f
    private var near: Boolean? = null
    private var farSince = 0L
    private var armed = false
    private var startAt = 0L
    private var tilted = false
    private var faceDownSince = 0L
    private var startedFaceDown: Boolean? = null
    private val h = Handler(Looper.getMainLooper())

    private val answerRun = Runnable {
        if (near == true && tilted && armed) HyperInCallService.answer()
    }

    fun start(ctx: Context) {
        if (sm != null) return
        c = cfg(ctx)
        silenced.value = false
        if (!c.raise && !c.flip) return
        val m = ctx.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        app = ctx.applicationContext
        near = null; farSince = 0; armed = false; tilted = false; faceDownSince = 0; startedFaceDown = null
        startAt = SystemClock.elapsedRealtime()
        m.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let { threshold = it.maximumRange.coerceAtMost(5f).coerceAtLeast(0.5f); m.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { m.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        sm = m
    }

    fun stop() {
        sm?.unregisterListener(this); sm = null
        h.removeCallbacks(answerRun)
    }

    /** Coupe la sonnerie et la vibration de l'appel entrant (l'appel continue de « sonner » en silence). */
    fun silence(ctx: Context, byFlip: Boolean = false) {
        try { ctx.getSystemService(TelecomManager::class.java)?.silenceRinger() } catch (_: Throwable) { }
        silenced.value = true
        if (byFlip) HyperInCallService.ringingCall?.let { flipped.add(it) }
    }

    /** Appelé quand un appel disparaît : vibration de fin si demandé. */
    fun onCallEnded(ctx: Context, call: Call, wasActive: Boolean) {
        val cf = cfg(ctx)
        val wasFlipped = flipped.remove(call)
        if ((wasFlipped && cf.flipEndVib) || (wasActive && cf.endVib)) doubleVibrate(ctx)
    }

    fun doubleVibrate(ctx: Context) {
        try {
            val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
            else @Suppress("DEPRECATION") ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            v?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 110, 130, 110), -1))
        } catch (_: Throwable) { }
    }

    override fun onSensorChanged(e: SensorEvent) {
        val now = SystemClock.elapsedRealtime()
        when (e.sensor.type) {
            Sensor.TYPE_PROXIMITY -> {
                val n = e.values[0] < threshold
                if (n == near) return
                near = n
                h.removeCallbacks(answerRun)
                if (!n) farSince = now
                // Décrocher : le téléphone était sorti (loin de tout), il arrive contre l'oreille en étant tenu incliné
                else if (c.raise && armed && tilted && now - startAt > 700) h.postDelayed(answerRun, 300)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val x = e.values[0]; val y = e.values[1]; val z = e.values[2]
                tilted = abs(z) < 7.5f
                // Armement anti-poche : il faut avoir vu le capteur dégagé au moins 0,4 s
                if (near == false && farSince > 0 && now - farSince >= 400) armed = true
                val faceDown = z < -8.0f && abs(x) < 2.5f && abs(y) < 2.5f
                if (startedFaceDown == null) startedFaceDown = faceDown
                if (faceDown) {
                    if (faceDownSince == 0L) faceDownSince = now
                    if (c.flip && !silenced.value && startedFaceDown == false && now - faceDownSince > 350) app?.let { silence(it, byFlip = true) }
                } else {
                    faceDownSince = 0L
                    if (startedFaceDown == true) startedFaceDown = false     // il était déjà retourné : il faudra le retourner à nouveau
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
