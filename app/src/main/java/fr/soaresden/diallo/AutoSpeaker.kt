package fr.soaresden.diallo

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telecom.CallAudioState
import androidx.compose.runtime.mutableStateOf
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Haut-parleur automatique (intégration du projet « Auto Haut-Parleur ») :
 * à l'oreille → écouteur ; éloigné de l'oreille (ou posé à plat) → haut-parleur.
 * Ici pas besoin de service d'accessibilité : Di-Allô pilote directement la sortie audio de l'appel.
 */
object AutoSpeaker : SensorEventListener {

    data class Cfg(val on: Boolean = false, val flat: Boolean = false, val delayMs: Long = 800, val back: Boolean = true)

    fun cfg(ctx: Context): Cfg = prefs(ctx).let {
        Cfg(it.getBoolean("aspk", false), it.getBoolean("aspk_flat", false), it.getLong("aspk_delay", 800L), it.getBoolean("aspk_back", true))
    }
    fun save(ctx: Context, c: Cfg) {
        prefs(ctx).edit().putBoolean("aspk", c.on).putBoolean("aspk_flat", c.flat).putLong("aspk_delay", c.delayMs).putBoolean("aspk_back", c.back).apply()
    }

    /** Actif pendant l'appel en cours (bouton « Auto » de l'écran d'appel). */
    val active = mutableStateOf(false)
    /** null = inconnu, true = à l'oreille / couvert. Sert aussi au test dans les réglages. */
    val near = mutableStateOf<Boolean?>(null)
    /** Pour le test des réglages : posé à plat et immobile. */
    val resting = mutableStateOf(false)

    private var sm: SensorManager? = null
    private var c = Cfg()
    private var threshold = 5f
    private var testOnly = false
    private var manual = false            // l'utilisateur a touché le bouton haut-parleur : on le laisse faire
    private var restSince = 0L
    private var lastMag = 0f
    private val h = Handler(Looper.getMainLooper())

    private val toSpeaker = Runnable {
        if (!testOnly && inCall() && routeOk() && near.value == false && (!c.flat || resting.value) && !manual) HyperInCallService.setSpeaker(true, manual = false)
    }
    private val toEar = Runnable {
        if (!testOnly && inCall() && routeOk() && near.value == true && !manual) HyperInCallService.setSpeaker(false, manual = false)
    }

    /** Pendant la sonnerie on écoute déjà les capteurs, mais on ne touche à l'audio qu'une fois en ligne. */
    private fun inCall() = HyperInCallService.callState.intValue == android.telecom.Call.STATE_ACTIVE

    /** Décroché (ou l'autre a répondu) : la bonne sortie tout de suite, sans attendre le délai. */
    private val applyNow = Runnable {
        if (!active.value || testOnly || manual || !routeOk()) return@Runnable
        when {
            near.value == true -> { if (c.back) HyperInCallService.setSpeaker(false, manual = false) }
            !c.flat || resting.value -> HyperInCallService.setSpeaker(true, manual = false)
        }
    }
    fun onConnected() {
        if (!active.value || testOnly) return
        h.removeCallbacks(toSpeaker); h.removeCallbacks(toEar); h.removeCallbacks(applyNow)
        h.post(applyNow)
        h.postDelayed(applyNow, 600)    // Android remet parfois l'écouteur juste après la connexion
    }

    /** Seulement écouteur <-> haut-parleur : on ne touche pas au Bluetooth ni au casque filaire. */
    private fun routeOk(): Boolean {
        val r = HyperInCallService.audio.value?.route ?: return true
        return r == CallAudioState.ROUTE_EARPIECE || r == CallAudioState.ROUTE_SPEAKER
    }

    /** Démarre pour l'appel en cours (si activé dans les réglages, ou forcé depuis l'écran d'appel). */
    fun start(ctx: Context, force: Boolean = false) {
        val cf = cfg(ctx)
        if (!cf.on && !force) return
        listen(ctx, cf, test = false)
        // Bouton touché pendant l'appel : le téléphone est dans la main, loin de l'oreille → haut-parleur tout de suite
        if (force && active.value && !cf.flat && routeOk()) HyperInCallService.setSpeaker(true, manual = false)
    }

    /** Mode test (écran des réglages) : lit les capteurs sans toucher à l'audio. */
    fun startTest(ctx: Context) { if (!active.value) listen(ctx, cfg(ctx), test = true) }
    fun stopTest() { if (testOnly) stop() }

    private fun listen(ctx: Context, cf: Cfg, test: Boolean) {
        stop()
        c = cf; testOnly = test; manual = false
        val m = ctx.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val prox = m.getDefaultSensor(Sensor.TYPE_PROXIMITY) ?: return
        threshold = prox.maximumRange.coerceAtMost(5f).coerceAtLeast(0.5f)
        sm = m
        m.registerListener(this, prox, SensorManager.SENSOR_DELAY_NORMAL)
        m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { m.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        if (!test) active.value = true
    }

    fun stop() {
        sm?.unregisterListener(this); sm = null
        h.removeCallbacks(toSpeaker); h.removeCallbacks(toEar); h.removeCallbacks(applyNow)
        active.value = false; near.value = null; resting.value = false; testOnly = false
    }

    /** Appelé quand l'utilisateur change la sortie à la main : on suspend jusqu'au prochain mouvement oreille. */
    fun manualOverride() { manual = true; h.removeCallbacks(toSpeaker); h.removeCallbacks(toEar); h.removeCallbacks(applyNow) }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_PROXIMITY -> {
                val n = e.values[0] < threshold
                if (near.value == n) return
                near.value = n; manual = false
                h.removeCallbacks(toSpeaker); h.removeCallbacks(toEar)
                if (n) { if (c.back) h.postDelayed(toEar, 150) }
                else if (!c.flat) h.postDelayed(toSpeaker, c.delayMs)
                else if (resting.value) h.postDelayed(toSpeaker, c.delayMs)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val x = e.values[0]; val y = e.values[1]; val z = e.values[2]
                val mag = sqrt(x * x + y * y + z * z)
                val flatNow = abs(z) > 9.0f && abs(x) < 1.6f && abs(y) < 1.6f
                val still = abs(mag - SensorManager.GRAVITY_EARTH) < 0.45f && abs(mag - lastMag) < 0.25f
                lastMag = mag
                val now = SystemClock.elapsedRealtime()
                if (flatNow && still) { if (restSince == 0L) restSince = now } else restSince = 0L
                val r = restSince != 0L && now - restSince > 300
                if (r != resting.value) {
                    resting.value = r
                    if (c.flat) {
                        h.removeCallbacks(toSpeaker)
                        if (r && near.value == false) h.postDelayed(toSpeaker, c.delayMs)
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
