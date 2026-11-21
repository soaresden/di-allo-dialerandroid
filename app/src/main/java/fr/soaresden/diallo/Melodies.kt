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
            Melody(o.getLong("id"), o.optString("name", tr("Mélodie")), o.optString("instr", "piano"),
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
        val m = Melody(id, name.trim().ifBlank { tr("Mélodie {0}", list.size + 1) }, instr, notes)
        save(ctx, listOf(m) + list)
        return m
    }
    fun update(ctx: Context, m: Melody) = save(ctx, load(ctx).map { if (it.id == m.id) m else it })
    fun rename(ctx: Context, id: Long, name: String) = save(ctx, load(ctx).map { if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }) else it })
    fun delete(ctx: Context, id: Long) { if (playingId.value == id) stop(); save(ctx, load(ctx).filter { it.id != id }) }
    fun nextName(ctx: Context) = tr("Mélodie {0}", load(ctx).size + 1)

    // ---------- Chiffres <-> notes ----------
    /** « 624 624 687 » → notes espacées de [stepMs] ; un espace = une pause, un retour à la ligne aussi (une seule, même juste après un espace). */
    fun fromText(text: String, stepMs: Int): List<Note> {
        val out = ArrayList<Note>(); var t = 0; var prev = ' '
        text.forEach { c ->
            when {
                c.toString() in VALID -> { out.add(Note(c.toString(), t)); t += stepMs }
                c == '\n' -> if (out.isNotEmpty() && prev != ' ' && prev != '\n') t += stepMs
                c == ' ' || c == '-' || c == '.' || c == ',' || c == '/' -> if (out.isNotEmpty()) t += stepMs
            }
            prev = c
        }
        return out
    }

    /** Comme [toText] (avec retours à la ligne) + la position de chaque note dans le texte (karaoké). */
    fun layout(notes: List<Note>): Pair<String, IntArray> {
        if (notes.isEmpty()) return "" to IntArray(0)
        val gaps = notes.zipWithNext { a, b -> b.t - a.t }
        val med = gaps.sorted().getOrNull(gaps.size / 2)?.coerceAtLeast(60) ?: 0
        val sb = StringBuilder(); val pos = IntArray(notes.size)
        pos[0] = 0; sb.append(notes[0].k)
        gaps.forEachIndexed { i, g ->
            if (med > 0 && g > med * 2.6) sb.append('\n') else if (med > 0 && g > med * 1.7) sb.append(' ')
            pos[i + 1] = sb.length; sb.append(notes[i + 1].k)
        }
        return sb.toString() to pos
    }

    /** Ne garde que ce qui sert : touches, espaces, retours à la ligne. */
    fun cleanDigits(v: String): String =
        v.replace("\r", "").filter { it.toString() in VALID || it == ' ' || it == '\n' }
            .lines().joinToString("\n") { it.trim().replace(Regex(" {2,}"), " ") }.trim('\n')

    /** Texte collé « Nom : 22223 444445 … » → (nom ou null, chiffres). */
    fun parsePasted(v: String): Pair<String?, String> {
        val t = v.replace("\r", "")
        val i = t.indexOf(':')
        if (i > 0) {
            val name = t.substring(0, i).trim().trimStart { !it.isLetterOrDigit() }.trimEnd(' ', '-', '—')
            if (name.any { it.isLetter() }) return name to cleanDigits(t.substring(i + 1))
        }
        return null to cleanDigits(t)
    }

    /** Notes → chiffres : espace là où le silence est nettement plus long, retour à la ligne pour les grandes respirations ([lines]). */
    fun toText(notes: List<Note>, lines: Boolean = false): String {
        if (notes.isEmpty()) return ""
        val gaps = notes.zipWithNext { a, b -> b.t - a.t }
        val med = gaps.sorted().getOrNull(gaps.size / 2)?.coerceAtLeast(60) ?: 0
        val sb = StringBuilder(notes[0].k)
        gaps.forEachIndexed { i, g ->
            if (med > 0 && lines && g > med * 2.6) sb.append('\n') else if (med > 0 && g > med * 1.7) sb.append(' ')
            sb.append(notes[i + 1].k)
        }
        return sb.toString()
    }

    // ---------- Recueil : airs du domaine public ----------
    /** Touches → notes : 1 Do · 2 Ré · 3 Mi · 4 Fa · 5 Sol · 6 La · 7 Si · 8 Do · 9 Ré · * Mi · 0 Fa · # Sol (noms dans la langue affichée). */
    val LEGEND: String get() = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#").joinToString(" · ") { "$it ${noteName(it)}" }

    /** Chaque chiffre dure un pas, chaque « - » prolonge d'un pas ; « | » sépare les mesures (ignoré). */
    data class Preset(val name: String, val stepMs: Int, val text: String) {
        val notes: List<Note> get() = fromText(text.replace("|", ""), stepMs)
    }

    /** Titre affiché d'un air du recueil ([Preset.name] français = clé de traduction). */
    fun title(p: Preset): String = tr(p.name)

    val SONGBOOK = listOf(
        Preset("Au clair de la lune", 380, "4445|6-5-|4655|4---|4445|6-5-|4655|4---|5555|2-2-|5432|1---|4445|6-5-|4655|4---"),
        Preset("Frère Jacques", 240, "5-6-7-5-|5-6-7-5-|7-8-9---|7-8-9---|9*987-5-|9*987-5-|5-2-5---|5-2-5---"),
        Preset("Ah ! vous dirai-je, maman", 380, "1155|665-|4433|221-|5544|332-|5544|332-|1155|665-|4433|221-"),
        Preset("Joyeux anniversaire", 230, "55|6-5-8-|7---55|6-5-9-|8---55|#-*-8-|7-6---00|*-8-9-|8-----"),
        Preset("Ode à la joie (Beethoven)", 250, "7-7-8-9-|9-8-7-6-|5-5-6-7-|7--66---|7-7-8-9-|9-8-7-6-|5-5-6-7-|6--55---|6-6-7-5-|6-787-5-|6-787-6-|5-6-2---|7-7-8-9-|9-8-7-6-|5-5-6-7-|6--55---"),
        Preset("Vive le vent (Jingle Bells)", 200, "3-3-3---|3-3-3---|3-5-1--2|3-------|4-4-4--4|4-3-3-33|3-2-2-3-|2---5---|3-3-3---|3-3-3---|3-5-1--2|3-------|4-4-4--4|4-3-3-33|5-5-4-2-|1-------"),
        Preset("Douce nuit", 240, "5--65-3-----|5--65-3-----|9-97-----|8-85-----|6-68765653-----|6-68765653-----|9-90978-----*-----|8-535-421-----"),
        Preset("Berceuse (Brahms)", 260, "335---|335---|358-7--66-5-|234-2-|234---|24765-7-8---|118---|645---|314565---|118---|645---|314321---"),
        Preset("Mary avait un petit agneau", 300, "3212|333-|222-|355-|3212|3333|2232|1---"),
        Preset("Korobeïniki (chanson russe)", 170, "6-345-43|2-246-54|3--45-6-|4-2-2---|6-345-43|2-246-54|3--45-6-|4-2-2---"),
    )

    // ---------- Enregistrement ----------
    val recording = mutableStateOf(false)
    val recNotes = mutableStateListOf<Note>()
    /** Ce qui s'affiche pendant l'enregistrement : une touche ou " " (pause), dans l'ordre. */
    val recTokens = mutableStateListOf<String>()
    private var tLast = 0                 // instant (ms) de la dernière note dans la mélodie
    private var wallLast = 0L             // heure réelle de la dernière action
    private var edited = false            // on vient d'effacer : le temps passé à corriger ne compte pas

    val recText: String get() = recTokens.joinToString("")

    /** Écart habituel entre deux notes (sert de durée à une pause « espace »). */
    private fun typicalGap(): Int {
        val g = recNotes.zipWithNext { a, b -> b.t - a.t }.filter { it > 40 }.sorted()
        return (g.getOrNull(g.size / 2) ?: 320).coerceIn(120, 900)
    }

    fun startRec() { stop(); recNotes.clear(); recTokens.clear(); tLast = 0; edited = false; recording.value = true }

    /** Appelée à chaque appui sur une touche du clavier. */
    fun note(k: String) {
        if (!recording.value || k !in VALID) return
        val now = System.currentTimeMillis()
        val t = if (recNotes.isEmpty()) 0 else {
            val gap = (now - wallLast).toInt()
            tLast + if (edited) minOf(gap, typicalGap()) else minOf(gap, 2500)   // long silence de réflexion → raccourci
        }
        recNotes.add(Note(k, t)); recTokens.add(k)
        tLast = t; wallLast = now; edited = false
    }

    /** Touche espace : une pause d'une note. */
    fun space() {
        if (!recording.value || recTokens.isEmpty() || recTokens.last() == " ") return
        recTokens.add(" ")
        val now = System.currentTimeMillis()
        tLast += typicalGap(); wallLast = now; edited = true
    }

    /** ⌫ : enlève la dernière note (ou la dernière pause). */
    fun backspace() {
        if (recTokens.isEmpty()) return
        val last = recTokens.removeAt(recTokens.lastIndex)
        if (last == " ") tLast = (tLast - typicalGap()).coerceAtLeast(recNotes.lastOrNull()?.t ?: 0)
        else { recNotes.removeAt(recNotes.lastIndex); tLast = recNotes.lastOrNull()?.t ?: 0 }
        wallLast = System.currentTimeMillis(); edited = true
    }
    fun clearRec() { recNotes.clear(); recTokens.clear(); tLast = 0; edited = false }

    /** Prend le brouillon (notes, texte) et le vide, en restant en mode composeur. */
    fun takeDraft(): Pair<List<Note>, String> {
        val r = recNotes.toList() to recText.trim()
        recNotes.clear(); recTokens.clear(); tLast = 0; edited = false
        return r
    }
    fun exitComposer() { recording.value = false; recNotes.clear(); recTokens.clear() }

    /** Fin : (notes avec le rythme joué, texte affiché). */
    fun stopRec(): Pair<List<Note>, String> {
        recording.value = false
        val r = recNotes.toList() to recText.trim()
        recNotes.clear(); recTokens.clear()
        return r
    }
    val recDurationMs: Int get() = if (recNotes.isEmpty()) 0 else tLast + 400

    // ---------- Lecture ----------
    val playingId = mutableStateOf<Long?>(null)
    val playingName = mutableStateOf<String?>(null)
    /** Pour le karaoké : notes en cours de lecture et index de la note jouée (-1 = pas encore). */
    val playingNotes = mutableStateOf<List<Note>>(emptyList())
    val playingIndex = mutableIntStateOf(-1)
    /** Compteur par touche : il augmente à chaque note jouée, le clavier anime la touche. */
    val pulse = mutableStateMapOf<String, Int>()
    private var job: Job? = null
    private val scope = MainScope()

    fun play(m: Melody) = play(m.id, m.name, m.instr, m.notes)

    /** Joue [notes] à partir de la note [from] (pour reprendre au milieu d'un air). */
    fun play(id: Long, name: String, instr: String, notes: List<Note>, from: Int = 0) {
        stop()
        if (notes.isEmpty()) return
        val first = from.coerceIn(0, notes.lastIndex)
        Synth.prepare(instr); Synth.warmUp()
        playingId.value = id; playingName.value = name; playingNotes.value = notes; playingIndex.intValue = first - 1
        job = scope.launch {
            delay(120)                                               // laisse le temps de préparer les sons
            val start = System.currentTimeMillis() - notes[first].t
            for (i in first until notes.size) {
                val n = notes[i]
                val wait = start + n.t - System.currentTimeMillis()
                if (wait > 0) delay(wait)
                playingIndex.intValue = i
                Synth.play(n.k, instr)
                pulse[n.k] = (pulse[n.k] ?: 0) + 1
            }
            delay(450)
            playingId.value = null; playingName.value = null
        }
    }

    fun stop() { job?.cancel(); job = null; playingId.value = null; playingName.value = null; playingIndex.intValue = -1 }
}

internal fun fmtMelodyDur(ms: Int): String { val s = (ms + 500) / 1000; return "${s / 60}:${(s % 60).toString().padStart(2, '0')}" }
