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
 * Enregistrements d'appels, rangés dans le dossier de travail : DiAllo/Rec/aaaa-mm-jj-hhmmss-NOM.ext
 *
 * Di-Allô n'enregistre pas lui-même (Android le réserve aux applis système) : il importe les fichiers
 * d'autres enregistreurs (« sources ») :
 *  - CallVault (copie : CallVault garde les siens) ;
 *  - les anciens enregistrements Xiaomi « Nom(numéro)_aaaammjjhhmmss.mp3 » (déplacés : ça libère la place).
 * Puis il relie chaque fichier à son appel (date + correspondant).
 */
object Recordings {
    const val CALLVAULT = "com.baba.callvault"
    const val CALLVAULT_URL = "https://github.com/madkongo/CallVault/releases/latest"
    private const val SOURCES = "rec_sources"
    private const val INDEX = ".diallo-index.json"          // nom du fichier → numéro (le nom ne garde que le contact)
    private val AUDIO = setOf("ogg", "opus", "m4a", "mp3", "wav", "amr", "aac", "3gp", "flac", "oga")

    /** Un enregistrement de DiAllo/Rec. [who] = nom du contact (ou numéro), [number] si connu. */
    data class Rec(val uri: Uri, val name: String, val time: Long, val who: String, val number: String?, val size: Long) {
        /** Clé de regroupement par correspondant (comme les notes). */
        val key: String get() = number?.let { keyOf(it) }?.takeIf { it.isNotEmpty() } ?: "nom:${who.lowercase()}"
    }
    data class Source(val uri: Uri, val move: Boolean)

    val folderVersion = mutableIntStateOf(0)
    val all = mutableStateOf<List<Rec>>(emptyList())
    val byCall = mutableStateOf<Map<Long, Rec>>(emptyMap())
    val open = mutableStateOf<Pair<Rec, String>?>(null)
    val status = mutableStateOf<String?>(null)
    val busy = mutableStateOf(false)

    // ---------- Sources ----------
    fun sources(ctx: Context): List<Source> = try {
        val a = JSONArray(prefs(ctx).getString(SOURCES, "[]"))
        (0 until a.length()).map { val o = a.getJSONObject(it); Source(Uri.parse(o.getString("u")), o.optBoolean("move")) }
            .filter { s -> ctx.contentResolver.persistedUriPermissions.any { it.uri == s.uri && it.isReadPermission } }
    } catch (_: Throwable) { emptyList() }

    private fun saveSources(ctx: Context, list: List<Source>) {
        val a = JSONArray(); list.forEach { a.put(JSONObject().put("u", it.uri.toString()).put("move", it.move)) }
        prefs(ctx).edit().putString(SOURCES, a.toString()).apply()
        folderVersion.intValue++
    }

    fun addSource(ctx: Context, u: Uri, move: Boolean) {
        try { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        catch (_: Throwable) { try { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Throwable) { } }
        saveSources(ctx, sources(ctx).filter { it.uri != u } + Source(u, move))
    }
    fun removeSource(ctx: Context, u: Uri) = saveSources(ctx, sources(ctx).filter { it.uri != u })

    fun sourceName(ctx: Context, u: Uri): String =
        try { androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, u)?.name } catch (_: Throwable) { null } ?: u.lastPathSegment ?: "?"

    /** Ouvre le sélecteur directement sur un dossier du téléphone (ex. MIUI/sound_recorder/call_rec). */
    fun hint(path: String): Uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$path")

    fun callVaultInstalled(ctx: Context): Boolean =
        try { ctx.packageManager.getPackageInfo(CALLVAULT, 0); true } catch (_: Throwable) { false }

    fun openCallVault(ctx: Context) {
        val i = ctx.packageManager.getLaunchIntentForPackage(CALLVAULT) ?: Intent(Intent.ACTION_VIEW, Uri.parse(CALLVAULT_URL))
        try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) { }
    }

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

    // ---------- Import ----------
    /**
     * Copie (ou déplace) dans DiAllo/Rec ce qui n'y est pas encore. Retourne le nombre de fichiers ajoutés.
     * [nameOf] : numéro → nom du contact.
     */
    fun importNew(ctx: Context, nameOf: (String) -> String?): Int {
        val (tree, recId) = recDir(ctx) ?: return 0
        val srcs = sources(ctx); if (srcs.isEmpty()) return 0
        val existing = children(ctx, tree, recId).associateBy { it.name }
        val index = loadIndex(ctx, tree, existing[INDEX])
        var added = 0; var dirty = false
        val fmt = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US)
        for (src in srcs) {
            val rootId = try { DocumentsContract.getTreeDocumentId(src.uri) } catch (_: Throwable) { continue }
            fun walk(id: String, depth: Int) {
                val kids = children(ctx, src.uri, id)
                val sidecars = kids.filter { it.name.endsWith(".json", true) }.associateBy { it.name.substringBeforeLast('.') }
                for (d in kids) {
                    if (d.mime == DocumentsContract.Document.MIME_TYPE_DIR) { if (depth < 2) walk(d.id, depth + 1); continue }
                    val ext = d.name.substringAfterLast('.', "").lowercase()
                    if (ext !in AUDIO) continue
                    // date + numéro : fichier de détails (CallVault, format BCR) sinon le nom
                    var time: Long? = null; var num: String? = null
                    sidecars[d.name.substringBeforeLast('.')]?.let { s ->
                        try {
                            val o = JSONObject(readText(ctx, DocumentsContract.buildDocumentUriUsingTree(src.uri, s.id)) ?: "{}")
                            time = o.optLong("timestamp_unix_ms", 0L).takeIf { it > 0 }
                            num = o.optJSONArray("calls")?.optJSONObject(0)?.optString("phone_number")?.takeIf { it.isNotBlank() && it != "null" }
                        } catch (_: Throwable) { }
                    }
                    val p = parseName(d.name)
                    val t = time ?: p.first ?: d.modified
                    val n = num ?: p.second
                    val who = n?.let { nameOf(it) } ?: p.third ?: n?.let { displayNumber(it) } ?: "Inconnu"
                    val dest = fmt.format(java.util.Date(t)) + "-" + safe(who) + "." + ext
                    val srcUri = DocumentsContract.buildDocumentUriUsingTree(src.uri, d.id)
                    val have = existing[dest]
                    if (have == null) {
                        val target = try { DocumentsContract.createDocument(ctx.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, recId), "application/octet-stream", dest) } catch (_: Throwable) { null } ?: continue
                        val ok = try {
                            ctx.contentResolver.openInputStream(srcUri)?.use { inp -> ctx.contentResolver.openOutputStream(target, "w")?.use { out -> inp.copyTo(out, 64 * 1024) } } != null
                        } catch (_: Throwable) { false }
                        if (!ok) { try { DocumentsContract.deleteDocument(ctx.contentResolver, target) } catch (_: Throwable) { }; continue }
                        added++
                        if (n != null) { index[dest] = n; dirty = true }
                        status.value = "Import… $added fichier${if (added > 1) "s" else ""}"
                    }
                    // anciens enregistrements : une fois la copie faite (taille identique), on libère la place
                    if (src.move && (have == null || have.size == d.size)) try { DocumentsContract.deleteDocument(ctx.contentResolver, srcUri) } catch (_: Throwable) { }
                }
            }
            walk(rootId, 0)
        }
        if (dirty) saveIndex(ctx, tree, recId, existing[INDEX], index)
        return added
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

    /** Import des nouveautés puis relecture de DiAllo/Rec (hors fil principal). */
    fun refresh(ctx: Context, data: CallRepository.Data) {
        if (busy.value) return
        busy.value = true
        try {
            val names = HashMap<String, String>(); val numbers = HashMap<String, String>()
            data.contacts.forEach { c -> val k = keyOf(c.number); if (k.isNotEmpty()) c.name?.let { n -> names.putIfAbsent(k, n); numbers.putIfAbsent(n.lowercase(), c.number) } }
            val added = importNew(ctx) { n -> names[keyOf(n)] }
            val recs = scan(ctx) { w -> numbers[w.lowercase()] }
            all.value = recs
            byCall.value = match(data.calls, recs)
            if (added > 0) status.value = "✅ $added enregistrement${if (added > 1) "s" else ""} importé${if (added > 1) "s" else ""} dans Rec"
            else if (status.value?.startsWith("Import") == true) status.value = null
        } finally { busy.value = false }
    }

    fun forKey(key: String): List<Rec> = all.value.filter { it.key == key }

    fun fmtSize(b: Long): String = when {
        b <= 0 -> ""
        b < 1024 * 1024 -> "${(b / 1024).coerceAtLeast(1)} Ko"
        else -> String.format(Locale.FRANCE, "%.1f Mo", b / (1024.0 * 1024.0))
    }
}
