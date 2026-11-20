package fr.soaresden.diallo

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Notes d'appel, rangées par correspondant (9 derniers chiffres du numéro). Stockées sur le téléphone. */
object NotesRepository {
    data class Note(val id: Long, val key: String, val number: String, val name: String?, val date: Long, val text: String)

    /** Incrémenté à chaque modification : les écrans Compose se mettent à jour tout seuls. */
    val version = mutableIntStateOf(0)

    private fun file(ctx: Context) = File(ctx.filesDir, "notes.json")
    @Volatile private var cache: MutableList<Note>? = null

    fun toJson(list: List<Note>): String {
        val arr = JSONArray()
        list.forEach { n -> arr.put(JSONObject().put("id", n.id).put("key", n.key).put("number", n.number).put("name", n.name ?: "").put("date", n.date).put("text", n.text)) }
        return arr.toString(1)
    }
    fun fromJson(s: String): List<Note> {
        val out = ArrayList<Note>()
        try {
            val arr = JSONArray(s)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Note(o.getLong("id"), o.getString("key"), o.optString("number"), o.optString("name").ifBlank { null }, o.getLong("date"), o.getString("text")))
            }
        } catch (_: Throwable) { }
        return out
    }

    @Synchronized
    private fun all(ctx: Context): MutableList<Note> {
        cache?.let { return it }
        val f = file(ctx)
        val list = if (f.exists()) fromJson(try { f.readText() } catch (_: Throwable) { "[]" }).toMutableList() else ArrayList()
        cache = list
        return list
    }

    @Synchronized
    private fun persist(ctx: Context) {
        try { file(ctx).writeText(toJson(all(ctx))) } catch (_: Throwable) { }
        version.intValue++
        DataFolder.scheduleSync(ctx)
    }

    fun list(ctx: Context): List<Note> = all(ctx).sortedByDescending { it.date }
    fun forNumber(ctx: Context, number: String): List<Note> { val k = keyOf(number); return all(ctx).filter { it.key == k }.sortedByDescending { it.date } }
    fun forKey(ctx: Context, key: String): List<Note> = all(ctx).filter { it.key == key }.sortedByDescending { it.date }

    @Synchronized
    fun add(ctx: Context, number: String, name: String?, text: String) {
        if (text.isBlank()) return
        val now = System.currentTimeMillis()
        all(ctx).add(Note(now, keyOf(number), number, name, now, text.trim()))
        persist(ctx)
    }

    @Synchronized
    fun delete(ctx: Context, id: Long) { all(ctx).removeAll { it.id == id }; persist(ctx) }

    /** Restauration : ajoute les notes absentes (même id = même note). Retourne le nombre ajouté. */
    @Synchronized
    fun merge(ctx: Context, incoming: List<Note>): Int {
        val l = all(ctx); val ids = l.map { it.id }.toHashSet()
        val add = incoming.filter { it.id !in ids }
        if (add.isNotEmpty()) { l.addAll(add); persist(ctx) }
        return add.size
    }
}
