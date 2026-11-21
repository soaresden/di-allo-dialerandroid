package fr.soaresden.diallo

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Enregistrements d'appels, rangés dans le dossier de travail : DiAllo/Rec/aaaa-mm-jj-hhmmss-NOM.mp3
 * (faits par l'enregistreur intégré, voir rec/CallRecorder.kt). Chaque fichier est relié à son appel.
 */
object Recordings {
    private const val INDEX = ".diallo-index.json"          // nom du fichier → numéro (le nom ne garde que le contact)
    private val AUDIO = setOf("ogg", "opus", "m4a", "mp3", "wav", "amr", "aac", "3gp", "flac", "oga")

    /** Un enregistrement de DiAllo/Rec. [who] = nom du contact (ou numéro), [number] si connu. */
    data class Rec(val uri: Uri, val name: String, val time: Long, val who: String, val number: String?, val size: Long) {
        /** Clé de regroupement par correspondant (comme les notes). */
        val key: String get() = number?.let { keyOf(it) }?.takeIf { it.isNotEmpty() } ?: "nom:${who.lowercase()}"
    }

    val folderVersion = mutableIntStateOf(0)
    val all = mutableStateOf<List<Rec>>(emptyList())
    val byCall = mutableStateOf<Map<Long, Rec>>(emptyMap())
    val open = mutableStateOf<Pair<Rec, String>?>(null)
    val status = mutableStateOf<String?>(null)
    val busy = mutableStateOf(false)

    // ---------- Accès aux dossiers ----------
    private class Doc(val id: String, val name: String, val mime: String?, val modified: Long, val size: Long)

    private fun children(ctx: Context, tree: Uri, parentId: String): List<Doc> {
        val out = ArrayList<Doc>()
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_SIZE
        )
        try {
            ctx.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId), cols, null, null, null)?.use { c ->
                while (c.moveToNext()) out.add(Doc(c.getString(0), c.getString(1) ?: "", c.getString(2), c.getLong(3), c.getLong(4)))
            }
        } catch (_: Throwable) { }
        return out
    }

    private fun readText(ctx: Context, u: Uri): String? =
        try { ctx.contentResolver.openInputStream(u)?.use { it.readBytes().toString(Charsets.UTF_8) } } catch (_: Throwable) { null }

    /** DiAllo/Rec (créé au besoin) : (arbre, id du dossier). */
    private fun recDir(ctx: Context): Pair<Uri, String>? {
        val tree = DataFolder.uri(ctx) ?: return null
        val d = DataFolder.subDir(ctx, "Rec") ?: return null
        return tree to DocumentsContract.getDocumentId(d.uri)
    }

    // ---------- Noms de fichiers ----------
    private val DATE_FULL = Regex("""(?<!\d)(\d{8})_(\d{6})(?:\.(\d{3}))?([+-]\d{4})?""")
    private val DATE_COMPACT = Regex("""(?<!\d)(\d{14})(?!\d)""")
    private val NUMBER = Regex("""\+?\d{6,}""")
    private val XIAOMI = Regex("""^(.*)\(([+\d ]+)\)_(\d{14})$""")
    private val OURS = Regex("""^(\d{4})-(\d{2})-(\d{2})-(\d{6})-(.+)$""")

    private fun compact(s: String): Long? = try { SimpleDateFormat("yyyyMMddHHmmss", Locale.US).parse(s)?.time } catch (_: Throwable) { null }

    /** (date, numéro, nom) tirés du nom d'un fichier d'enregistreur. */
    fun parseName(name: String): Triple<Long?, String?, String?> {
        val base = name.substringBeforeLast('.')
        XIAOMI.find(base)?.let { m ->
            val (who, num, d) = m.destructured
            return Triple(compact(d), num.replace(" ", ""), who.trim().takeIf { w -> w.any { it.isLetter() } })
        }
        var rest = base; var time: Long? = null
        DATE_FULL.find(base)?.let { m ->
            val (d, h, ms, off) = m.destructured
            time = try {
                if (off.isNotEmpty()) SimpleDateFormat("yyyyMMddHHmmssSSSZ", Locale.US).parse(d + h + ms.ifEmpty { "000" } + off)?.time
                else compact(d + h)
            } catch (_: Throwable) { null }
            rest = base.removeRange(m.range)
        }
        if (time == null) DATE_COMPACT.findAll(base).lastOrNull()?.let { m -> time = compact(m.value); rest = base.removeRange(m.range) }
        val num = NUMBER.find(rest)?.value
        val who = rest.replace(NUMBER, " ").replace(Regex("""\b(in|out)\b"""), " ").replace(Regex("""[_@()\-]+"""), " ").trim().takeIf { w -> w.any { it.isLetter() } }
        return Triple(time, num, who)
    }

    private fun safe(s: String): String = s.replace(Regex("""[/\\:*?"<>|\p{Cntrl}]"""), " ").replace(Regex("\\s+"), " ").trim().take(60).ifBlank { "Inconnu" }

    /** Range un enregistrement fait par Di-Allô dans Rec/ (aaaa-mm-jj-hhmmss-NOM.ext). Retourne le nom du fichier. */
    fun store(ctx: Context, file: java.io.File, time: Long, who: String, number: String?): String? {
        val (tree, recId) = recDir(ctx) ?: return null
        val kids = children(ctx, tree, recId)
        val base = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(java.util.Date(time)) + "-" + safe(who)
        val ext = file.name.substringAfterLast('.', "m4a")
        var dest = "$base.$ext"; var k = 2
        while (kids.any { it.name == dest }) dest = "$base-${k++}.$ext"
        val target = try { DocumentsContract.createDocument(ctx.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, recId), "application/octet-stream", dest) } catch (_: Throwable) { null } ?: return null
        val ok = try { file.inputStream().use { inp -> ctx.contentResolver.openOutputStream(target, "w")?.use { out -> inp.copyTo(out, 64 * 1024) } } != null } catch (_: Throwable) { false }
        if (!ok) { try { DocumentsContract.deleteDocument(ctx.contentResolver, target) } catch (_: Throwable) { }; return null }
        if (number != null) {
            val doc = kids.firstOrNull { it.name == INDEX }
            val index = loadIndex(ctx, tree, doc); index[dest] = number
            saveIndex(ctx, tree, recId, doc, index)
        }
        all.value = (listOf(Rec(target, dest, time, who, number, file.length())) + all.value).sortedByDescending { it.time }
        return dest
    }

    private fun loadIndex(ctx: Context, tree: Uri, doc: Doc?): HashMap<String, String> {
        val m = HashMap<String, String>()
        doc ?: return m
        try { val o = JSONObject(readText(ctx, DocumentsContract.buildDocumentUriUsingTree(tree, doc.id)) ?: "{}"); o.keys().forEach { k -> m[k] = o.getString(k) } } catch (_: Throwable) { }
        return m
    }
    private fun saveIndex(ctx: Context, tree: Uri, recId: String, doc: Doc?, m: Map<String, String>) {
        try {
            val u = doc?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it.id) }
                ?: DocumentsContract.createDocument(ctx.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, recId), "application/octet-stream", INDEX) ?: return
            ctx.contentResolver.openOutputStream(u, "wt")?.use { it.write(JSONObject(m as Map<*, *>).toString(1).toByteArray()) }
        } catch (_: Throwable) { }
    }

    // ---------- Lecture de DiAllo/Rec ----------
    fun scan(ctx: Context, numberOf: (String) -> String?): List<Rec> {
        val (tree, recId) = recDir(ctx) ?: return emptyList()
        val kids = children(ctx, tree, recId)
        val index = loadIndex(ctx, tree, kids.firstOrNull { it.name == INDEX })
        val fmt = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US)
        return kids.mapNotNull { d ->
            if (d.name.substringAfterLast('.', "").lowercase() !in AUDIO) return@mapNotNull null
            val base = d.name.substringBeforeLast('.')
            val m = OURS.find(base)
            val (time, who) = if (m != null) {
                val (y, mo, da, hms, w) = m.destructured
                ((try { fmt.parse("$y-$mo-$da-$hms")?.time } catch (_: Throwable) { null }) ?: d.modified) to w
            } else { val p = parseName(d.name); (p.first ?: d.modified) to (p.third ?: p.second ?: base) }
            val number = index[d.name] ?: who.takeIf { w -> w.count { it.isDigit() } >= 6 && w.none { it.isLetter() } } ?: numberOf(who)
            Rec(DocumentsContract.buildDocumentUriUsingTree(tree, d.id), d.name, time, who, number, d.size)
        }.sortedByDescending { it.time }
    }

    /** Relie chaque enregistrement à l'appel le plus proche avec le même correspondant. */
    fun match(calls: List<CallRepository.CallEntry>, recs: List<Rec>): Map<Long, Rec> {
        if (recs.isEmpty() || calls.isEmpty()) return emptyMap()
        val res = HashMap<Long, Rec>()
        val asc = calls.filter { it.id >= 0 }.sortedBy { it.date }
        val dates = LongArray(asc.size) { asc[it].date }
        for (r in recs) {
            val lo = java.util.Arrays.binarySearch(dates, r.time - 4 * 60_000L).let { if (it < 0) -it - 1 else it }
            val key = r.number?.let { keyOf(it) }?.takeIf { it.length >= 6 }
            var best: CallRepository.CallEntry? = null; var bestScore = Long.MAX_VALUE
            var i = lo
            while (i < asc.size && asc[i].date <= r.time + 90_000L) {
                val c = asc[i]
                val same = if (key != null) keyOf(c.number) == key else c.display.equals(r.who, true)
                if (same && res[c.id] == null) {
                    val diff = kotlin.math.abs(r.time - c.date) + (if (c.duration <= 0) 600_000L else 0L)
                    if (diff < bestScore) { best = c; bestScore = diff }
                }
                i++
            }
            best?.let { res[it.id] = r }
        }
        return res
    }

    /** Relecture de DiAllo/Rec (hors fil principal). */
    fun refresh(ctx: Context, data: CallRepository.Data) {
        if (busy.value) return
        busy.value = true
        try {
            val names = HashMap<String, String>(); val numbers = HashMap<String, String>()
            data.contacts.forEach { c -> val k = keyOf(c.number); if (k.isNotEmpty()) c.name?.let { n -> names.putIfAbsent(k, n); numbers.putIfAbsent(n.lowercase(), c.number) } }
            val recs = scan(ctx) { w -> numbers[w.lowercase()] }
            all.value = recs
            byCall.value = match(data.calls, recs)
        } finally { busy.value = false }
    }

    fun forKey(key: String): List<Rec> = all.value.filter { it.key == key }

    fun fmtSize(b: Long): String = when {
        b <= 0 -> ""
        b < 1024 * 1024 -> "${(b / 1024).coerceAtLeast(1)} Ko"
        else -> String.format(Locale.FRANCE, "%.1f Mo", b / (1024.0 * 1024.0))
    }
}
