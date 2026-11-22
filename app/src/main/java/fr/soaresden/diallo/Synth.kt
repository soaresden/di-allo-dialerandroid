package fr.soaresden.diallo

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin

/**
 * Moteur audio : reproduit les timbres du prototype web (piano, violon, batterie…)
 * en synthèse PCM jouée via AudioTrack. Aucune ressource audio externe.
 */
object Synth {

    private const val SR = 44100

    enum class Wave { SINE, TRI, SAW, SQR }
    enum class Kind { MELODIC, GUITAR, DRUM, DTMF, BOWED }

    data class Partial(val ratio: Double, val gain: Double, val wave: Wave)
    data class Instr(
        val id: String,
        val name: String,
        val kind: Kind = Kind.MELODIC,
        val partials: List<Partial> = emptyList(),
        val attack: Double = 0.005,
        val decay: Double = 1.0,
        val vibRate: Double = 0.0,
        val vibDepth: Double = 0.0,
        val emoji: String = "🎵",
        val octave: Double = 1.0,
        val body: Double = 1.0
    )

    // Gamme ascendante (Do majeur) attribuée aux touches, comme dans le prototype web
    private val freqOf = mapOf(
        "1" to 261.63, "2" to 293.66, "3" to 329.63, "4" to 349.23, "5" to 392.00, "6" to 440.00,
        "7" to 493.88, "8" to 523.25, "9" to 587.33, "*" to 659.25, "0" to 698.46, "#" to 783.99
    )
    private val dtmfOf = mapOf(
        "1" to (697 to 1209), "2" to (697 to 1336), "3" to (697 to 1477),
        "4" to (770 to 1209), "5" to (770 to 1336), "6" to (770 to 1477),
        "7" to (852 to 1209), "8" to (852 to 1336), "9" to (852 to 1477),
        "*" to (941 to 1209), "0" to (941 to 1336), "#" to (941 to 1477)
    )

    private fun p(r: Double, g: Double, w: Wave) = Partial(r, g, w)

    val instruments: List<Instr> = listOf(
        Instr("piano", "Piano", partials = listOf(p(1.0,1.0,Wave.TRI), p(2.0,.38,Wave.SINE), p(3.0,.14,Wave.SINE), p(4.0,.07,Wave.SINE)), attack=.006, decay=1.25, emoji="🎹"),
        Instr("guitare", "Guitare", kind = Kind.GUITAR, decay = 1.3, emoji="🎸"),
        Instr("violon", "Violon", kind = Kind.BOWED, decay = 1.1, vibRate = 5.8, emoji="🎻"),
        Instr("violoncelle", "Violoncelle", kind = Kind.BOWED, decay = 1.3, vibRate = 5.0, octave = .5, body = .55, emoji="🪕"),
        Instr("harpe", "Harpe", partials = listOf(p(1.0,.8,Wave.TRI), p(2.0,.25,Wave.SAW), p(3.0,.08,Wave.SINE)), attack=.004, decay=1.0, emoji="🎼"),
        Instr("marimba", "Marimba", partials = listOf(p(1.0,1.0,Wave.SINE), p(4.0,.3,Wave.SINE)), attack=.004, decay=.5, emoji="🪘"),
        Instr("xylo", "Xylophone", partials = listOf(p(1.0,1.0,Wave.SINE), p(3.0,.4,Wave.SINE), p(6.3,.12,Wave.SINE)), attack=.003, decay=.32, emoji="🌈"),
        Instr("kalimba", "Kalimba", partials = listOf(p(1.0,1.0,Wave.SINE), p(5.2,.18,Wave.SINE)), attack=.003, decay=.7, emoji="🌴"),
        Instr("cloche", "Cloche", partials = listOf(p(1.0,1.0,Wave.SINE), p(2.76,.5,Wave.SINE), p(5.4,.25,Wave.SINE), p(8.9,.12,Wave.SINE)), attack=.004, decay=1.9, emoji="🔔"),
        Instr("handpan", "Handpan", partials = listOf(p(1.0,1.0,Wave.SINE), p(2.0,.5,Wave.SINE), p(2.4,.33,Wave.SINE), p(4.0,.25,Wave.SINE)), attack=.004, decay=1.3, emoji="🛸"),
        Instr("orgue", "Orgue", partials = listOf(p(1.0,.5,Wave.SINE), p(2.0,.3,Wave.SINE), p(3.0,.14,Wave.SINE), p(4.0,.1,Wave.SINE)), attack=.02, decay=.7, emoji="⛪"),
        Instr("flute", "Flûte", partials = listOf(p(1.0,1.0,Wave.SINE), p(2.0,.08,Wave.SINE)), attack=.06, decay=.6, vibRate=5.0, vibDepth=.01, emoji="🪈"),
        Instr("choeur", "Chœur", partials = listOf(p(1.0,.5,Wave.SAW), p(2.0,.2,Wave.SAW), p(3.0,.12,Wave.SAW)), attack=.12, decay=.9, vibRate=5.0, vibDepth=.009, emoji="🎤"),
        Instr("cornemuse", "Cornemuse", partials = listOf(p(1.0,.5,Wave.SAW), p(1.5,.3,Wave.SAW), p(2.0,.18,Wave.SQR), p(.5,.25,Wave.SAW)), attack=.04, decay=.85, vibRate=4.5, vibDepth=.009, emoji="🐑"),
        Instr("synthe", "Synthé", partials = listOf(p(1.0,.3,Wave.SAW), p(1.003,.3,Wave.SAW), p(.997,.3,Wave.SAW), p(2.0,.12,Wave.SAW)), attack=.01, decay=.62, emoji="🎛️"),
        Instr("8bit", "8-bit", partials = listOf(p(1.0,1.0,Wave.SQR)), attack=.003, decay=.28, emoji="👾"),
        // --- claviers ---
        Instr("rhodes", "Piano électrique", partials = listOf(p(1.0,1.0,Wave.SINE), p(2.0,.25,Wave.SINE), p(7.0,.06,Wave.SINE)), attack=.004, decay=1.4, vibRate=5.5, vibDepth=.003, emoji="🌙"),
        Instr("clavecin", "Clavecin", partials = listOf(p(1.0,.5,Wave.SAW), p(2.0,.25,Wave.SQR), p(3.0,.1,Wave.SAW)), attack=.002, decay=.9, emoji="🏰"),
        Instr("accordeon", "Accordéon", partials = listOf(p(1.0,.4,Wave.SAW), p(1.004,.35,Wave.SAW), p(2.0,.15,Wave.SQR)), attack=.03, decay=.8, vibRate=6.0, vibDepth=.004, emoji="🪗"),
        // --- percussions mélodiques ---
        Instr("vibra", "Vibraphone", partials = listOf(p(1.0,1.0,Wave.SINE), p(4.0,.25,Wave.SINE)), attack=.004, decay=1.6, vibRate=6.0, vibDepth=.006, emoji="✨"),
        Instr("glock", "Glockenspiel", partials = listOf(p(1.0,1.0,Wave.SINE), p(2.76,.4,Wave.SINE)), attack=.002, decay=.9, octave=2.0, emoji="⭐"),
        Instr("boite", "Boîte à musique", partials = listOf(p(1.0,1.0,Wave.SINE), p(2.0,.3,Wave.SINE), p(5.4,.15,Wave.SINE)), attack=.002, decay=.9, octave=2.0, emoji="🎠"),
        Instr("steel", "Steel drum", partials = listOf(p(1.0,1.0,Wave.SINE), p(2.0,.6,Wave.SINE), p(3.0,.2,Wave.SINE), p(4.1,.15,Wave.SINE)), attack=.003, decay=.9, emoji="🏝️"),
        // --- cordes ---
        Instr("ukulele", "Ukulélé", kind = Kind.GUITAR, decay = .8, octave = 2.0, emoji="🌺"),
        Instr("basse", "Basse", partials = listOf(p(1.0,1.0,Wave.TRI), p(2.0,.3,Wave.SINE)), attack=.005, decay=.8, octave=.25, emoji="🔊"),
        Instr("contrebasse", "Contrebasse", kind = Kind.BOWED, decay = 1.2, vibRate = 4.5, octave = .25, body = .5, emoji="🎵"),
        Instr("sitar", "Sitar", partials = listOf(p(1.0,.6,Wave.SAW), p(2.01,.3,Wave.SAW), p(3.02,.2,Wave.SAW)), attack=.003, decay=1.4, vibRate=3.0, vibDepth=.004, emoji="🪷"),
        Instr("koto", "Koto", partials = listOf(p(1.0,1.0,Wave.TRI), p(2.0,.3,Wave.SAW)), attack=.002, decay=.9, emoji="🎎"),
        // --- vents ---
        Instr("trompette", "Trompette", partials = listOf(p(1.0,.6,Wave.SAW), p(2.0,.35,Wave.SAW), p(3.0,.2,Wave.SAW)), attack=.03, decay=.6, vibRate=5.5, vibDepth=.006, emoji="🎺"),
        Instr("sax", "Saxophone", partials = listOf(p(1.0,.6,Wave.SAW), p(2.0,.3,Wave.SQR), p(3.0,.15,Wave.SAW)), attack=.04, decay=.8, vibRate=5.0, vibDepth=.01, emoji="🎷"),
        Instr("clarinette", "Clarinette", partials = listOf(p(1.0,.6,Wave.SQR), p(3.0,.2,Wave.SINE)), attack=.04, decay=.7, vibRate=4.5, vibDepth=.006, emoji="🎶"),
        Instr("sifflet", "Sifflement", partials = listOf(p(1.0,1.0,Wave.SINE)), attack=.04, decay=.5, vibRate=5.5, vibDepth=.012, octave=2.0, emoji="😗"),
        // --- électro / fun ---
        Instr("theremine", "Thérémine", partials = listOf(p(1.0,1.0,Wave.SINE), p(2.0,.1,Wave.SINE)), attack=.08, decay=.9, vibRate=6.5, vibDepth=.02, emoji="👽"),
        Instr("pad", "Nappe", partials = listOf(p(1.0,.3,Wave.TRI), p(1.006,.3,Wave.TRI), p(.994,.3,Wave.TRI), p(2.0,.1,Wave.SINE)), attack=.15, decay=1.2, emoji="🌫️"),
        Instr("chip", "Console", partials = listOf(p(1.0,.7,Wave.SQR), p(2.0,.2,Wave.TRI)), attack=.002, decay=.18, octave=2.0, emoji="🕹️"),
        Instr("batterie", "Batterie", kind = Kind.DRUM, emoji="🥁"),
        Instr("tonalite", "Tonalité", kind = Kind.DTMF, emoji="☎️")
    )

    @Volatile var currentId: String = "piano"
    private val current: Instr get() = instruments.firstOrNull { it.id == currentId } ?: instruments[0]

    // un fil par cœur : les 12 sons d'un instrument se calculent en parallèle
    private val exec = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceIn(2, 8)) { r -> Thread(r, "diallo-render").apply { isDaemon = true } }

    // Cache des sons déjà calculés (instrument courant seulement) : préparés à l'avance
    private val cache = java.util.concurrent.ConcurrentHashMap<String, ShortArray>()
    private val KEYS = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#")

    fun setInstrument(id: String) {
        currentId = id
        cache.keys.removeAll { !it.startsWith("$id|") }
        prepare()
    }

    private fun render(instr: Instr, key: String): ShortArray = cache.getOrPut(instr.id + "|" + key) {
        when (instr.kind) {
            Kind.DRUM -> renderDrum(key)
            Kind.DTMF -> renderDtmf(key)
            Kind.GUITAR -> renderGuitar((freqOf[key] ?: 440.0) * instr.octave)
            Kind.BOWED -> renderBowed((freqOf[key] ?: 440.0) * instr.octave, instr)
            Kind.MELODIC -> renderMelodic((freqOf[key] ?: 440.0) * instr.octave, instr)
        }
    }

    private fun instr(id: String?): Instr = id?.let { i -> instruments.firstOrNull { it.id == i } } ?: current

    /** Calcule à l'avance les 12 touches de l'instrument [id] (courant par défaut). */
    fun prepare(id: String? = null) {
        val instr = instr(id)
        KEYS.forEach { k -> if (!cache.containsKey(instr.id + "|" + k)) exec.execute { try { render(instr, k) } catch (_: Throwable) { } } }
    }

    /** Ouvre le flux audio et prépare les sons : à appeler quand le clavier s'affiche. */
    fun warmUp() { prepare(); ensureMixer() }

    /** Joue le son de la touche [key] ("0".."9","*","#") avec l'instrument courant — sans attendre. */
    fun play(key: String, instrId: String? = null) {
        val instr = instr(instrId)
        val ready = cache[instr.id + "|" + key]
        if (ready != null) start(ready)
        else exec.execute { try { start(render(instr, key)) } catch (_: Throwable) { } }
    }

    // ---- Mixeur : un seul AudioTrack en flux, gardé ouvert quelques secondes ----
    private class Voice(val s: ShortArray) { var pos = 0 }
    private val voices = java.util.concurrent.ConcurrentLinkedQueue<Voice>()
    private val lock = Object()
    @Volatile private var mixer: Thread? = null
    @Volatile private var lastUse = 0L
    private const val CHUNK = 192          // ~4 ms par paquet
    private const val IDLE_MS = 60_000L    // ferme le flux après 1 min sans touche (le rouvrir coûte une latence)

    private fun start(s: ShortArray) {
        voices.add(Voice(s))
        while (voices.size > 6) voices.poll()   // pas plus de 6 notes à la fois
        ensureMixer()
    }

    private fun ensureMixer() {
        lastUse = System.currentTimeMillis()
        synchronized(lock) {
            if (mixer?.isAlive == true) return
            mixer = Thread({ runMixer() }, "diallo-synth").apply { priority = Thread.MAX_PRIORITY; start() }
        }
    }

    private fun runMixer() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        val min = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(CHUNK * 4)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SR).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(min)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (_: Throwable) { synchronized(lock) { mixer = null }; return }
        val mix = IntArray(CHUNK); val out = ShortArray(CHUNK)
        try {
            track.play()
            while (true) {
                if (voices.isEmpty() && System.currentTimeMillis() - lastUse > IDLE_MS) {
                    val stop = synchronized(lock) { if (voices.isEmpty()) { mixer = null; true } else false }
                    if (stop) break
                }
                java.util.Arrays.fill(mix, 0)
                val it = voices.iterator()
                while (it.hasNext()) {
                    val v = it.next()
                    val n = minOf(CHUNK, v.s.size - v.pos)
                    for (i in 0 until n) mix[i] += v.s[v.pos + i].toInt()
                    v.pos += n
                    if (v.pos >= v.s.size) it.remove()
                }
                for (i in 0 until CHUNK) out[i] = mix[i].coerceIn(-32767, 32767).toShort()
                track.write(out, 0, CHUNK)   // bloquant : cadence le mixeur sur la sortie audio
            }
        } catch (_: Throwable) {
            synchronized(lock) { mixer = null }
        } finally {
            try { track.stop() } catch (_: Throwable) {}
            try { track.release() } catch (_: Throwable) {}
        }
    }

    // ---- Rendu ----
    private fun wave(w: Wave, ph: Double): Double {
        val frac = (ph / (2 * Math.PI)).let { it - floor(it) }
        return when (w) {
            Wave.SINE -> sin(ph)
            Wave.TRI -> 4.0 * abs(frac - 0.5) - 1.0
            Wave.SAW -> 2.0 * frac - 1.0
            Wave.SQR -> if (frac < 0.5) 1.0 else -1.0
        }
    }

    private fun toShorts(buf: DoubleArray, master: Double): ShortArray {
        val out = ShortArray(buf.size)
        for (i in buf.indices) {
            var v = buf[i] * master
            if (v > 1) v = 1.0; if (v < -1) v = -1.0
            out[i] = (v * 32767).toInt().toShort()
        }
        return out
    }

    private fun renderMelodic(freq: Double, instr: Instr): ShortArray {
        val dur = instr.decay
        val n = (SR * dur).toInt()
        val buf = DoubleArray(n)
        val gainSum = instr.partials.sumOf { it.gain }
        val phases = DoubleArray(instr.partials.size)
        val a = instr.attack
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            val env = if (t < a) t / a else exp(-4.0 * (t - a) / (dur - a))
            val vib = if (instr.vibDepth > 0) 1.0 + instr.vibDepth * sin(2 * Math.PI * instr.vibRate * t) else 1.0
            var s = 0.0
            for (j in instr.partials.indices) {
                val part = instr.partials[j]
                phases[j] += 2 * Math.PI * freq * part.ratio * vib / SR
                s += part.gain * wave(part.wave, phases[j])
            }
            buf[i] = (s / gainSum) * env
        }
        return toShorts(buf, 0.5)
    }

    /**
     * Cordes frottées (violon / violoncelle) : spectre riche en harmoniques
     * filtré par les résonances de la caisse, attaque d'archet progressive avec
     * bruit de crin, vibrato qui n'arrive qu'après l'attaque, puis relâché.
     */
    private fun bodyGain(f: Double): Double {
        fun bump(f0: Double, g: Double, w: Double): Double { val x = ln(f / f0) / w; return g * exp(-x * x) }
        return 0.12 + bump(275.0, .55, .22) + bump(460.0, .85, .2) + bump(1000.0, .45, .28) +
            bump(2800.0, 1.0, .32) + bump(4300.0, .3, .25)
    }

    private fun renderBowed(freq: Double, instr: Instr): ShortArray {
        val dur = instr.decay
        val n = (SR * dur).toInt()
        val buf = DoubleArray(n)
        val nh = (9000.0 / freq).toInt().coerceIn(6, 32)
        val rnd = java.util.Random(freq.toLong())
        val gains = DoubleArray(nh) { k -> val h = k + 1; (1.0 / h) * bodyGain(freq * h / instr.body) }
        val gsum = gains.sum()
        for (k in gains.indices) gains[k] /= gsum
        val offs = DoubleArray(nh) { rnd.nextDouble() * 2 * Math.PI }
        var ph = 0.0
        var lp = 0.0; var hp = 0.0
        val attack = 0.11; val release = 0.24
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            // vibrato retardé (le violoniste stabilise la note avant de vibrer)
            val vAmt = 0.0055 * ((t - 0.16) / 0.28).coerceIn(0.0, 1.0)
            val vib = 1.0 + vAmt * sin(2 * Math.PI * instr.vibRate * t) + 0.0007 * sin(2 * Math.PI * 0.9 * t)
            ph += 2 * Math.PI * freq * vib / SR
            var s = 0.0
            for (k in 0 until nh) s += gains[k] * sin((k + 1) * ph + offs[k])
            // bruit d'archet : bruit filtré, fort à l'attaque, discret ensuite
            val w = rnd.nextGaussian()
            lp += 0.35 * (w - lp); val band = lp - hp; hp += 0.02 * (lp - hp)
            val noise = band * (0.04 + 0.22 * exp(-t / 0.05))
            val a = (t / attack).coerceAtMost(1.0)
            val env = a * a * (3 - 2 * a) * ((dur - t) / release).coerceIn(0.0, 1.0) * (0.92 + 0.08 * sin(Math.PI * t / dur))
            buf[i] = (s * 1.6 + noise) * env
        }
        return toShorts(buf, 0.6)
    }

    private fun renderGuitar(freq: Double): ShortArray {
        val period = (SR / freq).toInt().coerceAtLeast(2)
        val ring = DoubleArray(period) { Math.random() * 2 - 1 }
        val n = (SR * 1.3).toInt()
        val buf = DoubleArray(n)
        var idx = 0
        for (i in 0 until n) {
            val cur = ring[idx]
            val nxt = ring[(idx + 1) % period]
            ring[idx] = 0.5 * (cur + nxt) * 0.996
            buf[i] = cur
            idx = (idx + 1) % period
        }
        return toShorts(buf, 0.6)
    }

    private fun renderDtmf(key: String): ShortArray {
        val pair = dtmfOf[key] ?: (697 to 1209)
        val dur = 0.2
        val n = (SR * dur).toInt()
        val buf = DoubleArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            val env = if (t > dur - 0.03) ((dur - t) / 0.03).coerceIn(0.0, 1.0) else 1.0
            buf[i] = env * (sin(2 * Math.PI * pair.first * t) + sin(2 * Math.PI * pair.second * t)) / 2.0
        }
        return toShorts(buf, 0.45)
    }

    private fun renderDrum(key: String): ShortArray {
        fun tone(f0: Double, f1: Double, dur: Double, peak: Double): DoubleArray {
            val n = (SR * dur).toInt(); val b = DoubleArray(n); var ph = 0.0
            for (i in 0 until n) {
                val t = i.toDouble() / SR
                val f = f0 + (f1 - f0) * (t / dur)
                ph += 2 * Math.PI * f / SR
                b[i] = sin(ph) * peak * exp(-5.0 * t / dur)
            }
            return b
        }
        fun noise(dur: Double, peak: Double, high: Boolean): DoubleArray {
            val n = (SR * dur).toInt(); val b = DoubleArray(n); var last = 0.0
            for (i in 0 until n) {
                val t = i.toDouble() / SR
                var r = Math.random() * 2 - 1
                if (high) { val v = r - last; last = r; r = v } // pseudo passe-haut
                b[i] = r * peak * exp(-7.0 * t / dur)
            }
            return b
        }
        val parts: List<DoubleArray> = when (key) {
            "1" -> listOf(tone(150.0, 50.0, .32, .9))
            "0" -> listOf(tone(120.0, 42.0, .36, .9))
            "2" -> listOf(tone(190.0, 120.0, .18, .4), noise(.2, .5, false))
            "9" -> listOf(tone(320.0, 180.0, .12, .4), noise(.09, .5, true))
            "3" -> listOf(noise(.05, .5, true))
            "8" -> listOf(noise(.32, .4, true))
            "*" -> listOf(noise(.9, .45, true))
            "4" -> listOf(tone(120.0, 80.0, .26, .8))
            "5" -> listOf(tone(180.0, 120.0, .24, .8))
            "6" -> listOf(tone(260.0, 180.0, .22, .8))
            "7" -> listOf(noise(.14, .5, false))
            "#" -> listOf(tone(540.0, 540.0, .4, .5), tone(800.0, 800.0, .4, .3))
            else -> listOf(tone(180.0, 120.0, .2, .6))
        }
        val n = parts.maxOf { it.size }
        val mix = DoubleArray(n)
        for (part in parts) for (i in part.indices) mix[i] += part[i]
        return toShorts(mix, 0.6)
    }
}
