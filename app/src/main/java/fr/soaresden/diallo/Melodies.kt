package fr.soaresden.diallo

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Mélodies jouées sur le clavier : enregistrement (avec le rythme réel), saisie en chiffres
 * (« 624 624 68756752132 », espace = pause), lecture avec les touches qui s'animent.
 * Rangées dans les réglages → sauvegardées avec le dossier de travail.
 */
object Melodies {
    data class Note(val k: String, val t: Int)                       // touche, ms depuis le début
    data class Melody(val id: Long, val name: String, val instr: String, val notes: List<Note>) {
        val durationMs: Int get() = (notes.lastOrNull()?.t ?: 0) + 400
    }

    private const val KEY = "melodies"
    val VALID = setOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "#")

    /** Change à chaque modification de la liste. */
    val version = mutableIntStateOf(0)

    // ---------- Stockage ----------
    fun load(ctx: Context): List<Melody> = try {
        val a = JSONArray(prefs(ctx).getString(KEY, "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i); val n = o.getJSONArray("n")
            Melody(o.getLong("id"), o.optString("name", "Mélodie"), o.optString("instr", "piano"),
                (0 until n.length()).map { j -> val x = n.getJSONArray(j); Note(x.getString(0), x.getInt(1)) })
        }
    } catch (_: Throwable) { emptyList() }

    private fun save(ctx: Context, list: List<Melody>) {
        val a = JSONArray()
        list.forEach { m ->
            val n = JSONArray(); m.notes.forEach { n.put(JSONArray().put(it.k).put(it.t)) }
            a.put(JSONObject().put("id", m.id).put("name", m.name).put("instr", m.instr).put("n", n))
        }
        prefs(ctx).edit().putString(KEY, a.toString()).apply()
        version.intValue++
    }

    fun add(ctx: Context, name: String, instr: String, notes: List<Note>): Melody {
        val list = load(ctx)
        val id = maxOf(System.currentTimeMillis(), (list.maxOfOrNull { it.id } ?: 0L) + 1)
        val m = Melody(id, name.trim().ifBlank { "Mélodie ${list.size + 1}" }, instr, notes)
        save(ctx, listOf(m) + list)
        return m
    }
    fun rename(ctx: Context, id: Long, name: String) = save(ctx, load(ctx).map { if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }) else it })
    fun delete(ctx: Context, id: Long) { if (playingId.value == id) stop(); save(ctx, load(ctx).filter { it.id != id }) }
    fun nextName(ctx: Context) = "Mélodie ${load(ctx).size + 1}"

    // ---------- Chiffres <-> notes ----------
    /** « 624 624 687 » → notes espacées de [stepMs] ; chaque espace ajoute une pause d'un pas. */
    fun fromText(text: String, stepMs: Int): List<Note> {
        val out = ArrayList<Note>(); var t = 0
        text.forEach { c ->
            when {
                c.toString() in VALID -> { out.add(Note(c.toString(), t)); t += stepMs }
                c == ' ' || c == '-' || c == '.' || c == ',' -> if (out.isNotEmpty()) t += stepMs
            }
        }
        return out
    }

    /** Notes → chiffres, avec un espace là où le silence est nettement plus long que d'habitude. */
    fun toText(notes: List<Note>): String {
        if (notes.isEmpty()) return ""
        val gaps = notes.zipWithNext { a, b -> b.t - a.t }
        val med = gaps.sorted().getOrNull(gaps.size / 2)?.coerceAtLeast(60) ?: 0
        val sb = StringBuilder(notes[0].k)
        gaps.forEachIndexed { i, g -> if (med > 0 && g > med * 1.7) sb.append(' '); sb.append(notes[i + 1].k) }
        return sb.toString()
    }

    // ---------- Enregistrement ----------
    val recording = mutableStateOf(false)
    val recNotes = mutableStateListOf<Note>()
    var recStart = 0L; private set

    fun startRec() { stop(); recNotes.clear(); recStart = 0L; recording.value = true }
    /** Appelée à chaque appui sur une touche du clavier. */
    fun note(k: String) {
        if (!recording.value || k !in VALID) return
        val now = System.currentTimeMillis()
        if (recNotes.isEmpty()) recStart = now                      // le silence avant la 1re note ne compte pas
        recNotes.add(Note(k, (now - recStart).toInt()))
    }
    fun stopRec(): List<Note> { recording.value = false; return recNotes.toList().also { recNotes.clear() } }

    // ---------- Lecture ----------
    val playingId = mutableStateOf<Long?>(null)
    val playingName = mutableStateOf<String?>(null)
    /** Compteur par touche : il augmente à chaque note jouée, le clavier anime la touche. */
    val pulse = mutableStateMapOf<String, Int>()
    private var job: Job? = null
    private val scope = MainScope()

    fun play(m: Melody) = play(m.id, m.name, m.instr, m.notes)

    fun play(id: Long, name: String, instr: String, notes: List<Note>) {
        stop()
        if (notes.isEmpty()) return
        Synth.prepare(instr); Synth.warmUp()
        playingId.value = id; playingName.value = name
        job = scope.launch {
            delay(120)                                               // laisse le temps de préparer les sons
            val start = System.currentTimeMillis()
            for (n in notes) {
                val wait = start + n.t - System.currentTimeMillis()
                if (wait > 0) delay(wait)
                Synth.play(n.k, instr)
                pulse[n.k] = (pulse[n.k] ?: 0) + 1
            }
            delay(450)
            playingId.value = null; playingName.value = null
        }
    }

    fun stop() { job?.cancel(); job = null; playingId.value = null; playingName.value = null }
}

internal fun fmtMelodyDur(ms: Int): String { val s = (ms + 500) / 1000; return "${s / 60}:${(s % 60).toString().padStart(2, '0')}" }
