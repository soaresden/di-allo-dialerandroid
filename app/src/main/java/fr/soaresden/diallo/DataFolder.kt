package fr.soaresden.diallo

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.util.Base64
import android.util.Xml
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import org.xmlpull.v1.XmlSerializer
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Dossier de travail choisi sur le téléphone :
 *  - notes.json et reglages.json, tenus à jour automatiquement ;
 *    (la version F a ses propres fichiers, notes-f.json, reglages-f.json, « Sauvegardes F », pour que les deux versions
 *    installées côte à côte dans le même dossier ne s'écrasent pas ; chacune relit ceux de l'autre si elle n'a pas encore les siens) ;
 *  - Sauvegardes/ : journal d'appels + SMS/MMS complets au format « SMS Backup & Restore ».
 * Après une réinstallation, on rechoisit le même dossier et tout revient.
 */
object DataFolder {
    private const val KEY = "workdir"
    private const val LAST = "last_backup"
    private val IGNORED = setOf(KEY, LAST)
    private const val KEEP_DAYS = 30                // sauvegardes gardées un mois (la plus récente l'est toujours)
    private val F = BuildConfig.FULL && !BuildConfig.LEGACY
    private val NOTES = if (F) "notes-f.json" else "notes.json"
    private val NOTES_OTHER = if (F) "notes.json" else "notes-f.json"
    private val SETTINGS = if (F) "reglages-f.json" else "reglages.json"
    private val SETTINGS_OTHER = if (F) "reglages.json" else "reglages-f.json"
    private val BACKUPS = if (F) "Sauvegardes F" else "Sauvegardes"
    private val BACKUPS_OTHER = if (F) "Sauvegardes" else "Sauvegardes F"
    /** Autres fichiers de réglages sauvegardés avec « hyperdial », et les clés propres au téléphone qu'on n'y met pas. */
    private val EXTRA = mapOf(
        "favnums" to emptySet(), "speeddial" to emptySet(), "sim_choice" to emptySet(),
        "recorder" to setOf("port", "token", "paired", "adb_port"),
    )
    /**
     * Vrai entre le choix d'un dossier et la réponse à « Restaurer ? » : rien n'écrit reglages.json pendant ce temps,
     * sinon la sauvegarde automatique (lancée dès le retour du sélecteur) écrasait les réglages à restaurer par ceux par défaut.
     */
    @Volatile private var hold = false

    private val exec = Executors.newSingleThreadExecutor()
    private val h = Handler(Looper.getMainLooper())
    private val TOKEN = Any()
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    @Volatile private var restoring = false

    /** Dernière action (affichée dans les réglages). */
    val status = mutableStateOf<String?>(null)
    /** Sauvegarde en cours (texte de progression) ou null. */
    val busy = mutableStateOf<String?>(null)
    /** Change quand le dossier change (pour rafraîchir l'UI). */
    val folderVersion = mutableStateOf(0)

    fun uri(ctx: Context): Uri? = prefs(ctx).getString(KEY, null)?.let { Uri.parse(it) }
        ?.takeIf { u -> ctx.contentResolver.persistedUriPermissions.any { it.uri == u && it.isWritePermission } }
    fun name(ctx: Context): String? = uri(ctx)?.let { try { DocumentFile.fromTreeUri(ctx, it)?.name } catch (_: Throwable) { null } }
    private fun tree(ctx: Context): DocumentFile? = uri(ctx)?.let { DocumentFile.fromTreeUri(ctx, it) }?.takeIf { it.canWrite() }

    fun autoBackup(ctx: Context) = prefs(ctx).getBoolean("auto_backup", true)
    fun setAutoBackup(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("auto_backup", on).apply() }
    fun lastBackup(ctx: Context) = prefs(ctx).getLong(LAST, 0L)

    fun choose(ctx: Context, u: Uri) {
        try { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Throwable) { }
        hold = true
        prefs(ctx).edit().putString(KEY, u.toString()).apply()
        folderVersion.value++
    }

    /** À appeler au démarrage : toute modification des réglages est recopiée dans le dossier. */
    fun init(ctx: Context) {
        if (listener != null) return
        val app = ctx.applicationContext
        listener = SharedPreferences.OnSharedPreferenceChangeListener { _, k -> if (k != null && k !in IGNORED && !k.startsWith("col_") && !restoring) scheduleSync(app) }
        prefs(app).registerOnSharedPreferenceChangeListener(listener)
        EXTRA.keys.forEach { app.getSharedPreferences(it, Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(listener) }
    }

    fun scheduleSync(ctx: Context) {
        val app = ctx.applicationContext
        h.removeCallbacksAndMessages(TOKEN)
        h.postAtTime({ exec.execute { syncNow(app) } }, TOKEN, SystemClock.uptimeMillis() + 1500)
    }

    private fun now() = SimpleDateFormat("dd/MM HH:mm", I18n.locale).format(Date())

    /* ---------------- Notes + réglages ---------------- */

    /** [force] : la réponse à « Restaurer ? » est donnée (ou il n'y avait rien à restaurer), on peut écrire. */
    fun syncNow(ctx: Context, force: Boolean = false): Boolean {
        if (force) hold = false
        if (hold) return false
        val dir = tree(ctx) ?: return false
        return try {
            writeText(ctx, dir, NOTES, NotesRepository.toJson(NotesRepository.list(ctx)))
            writeText(ctx, dir, SETTINGS, settingsJson(ctx).toString(1))
            status.value = tr("✅ Notes et réglages enregistrés · {0}", now())
            true
        } catch (_: Throwable) { status.value = tr("❌ Impossible d'écrire dans le dossier"); false }
    }

    private fun writeText(ctx: Context, dir: DocumentFile, name: String, text: String) {
        val f = dir.findFile(name) ?: dir.createFile("application/octet-stream", name) ?: return
        ctx.contentResolver.openOutputStream(f.uri, "wt")?.use { it.write(text.toByteArray()) }
    }
    private fun readText(ctx: Context, dir: DocumentFile, name: String): String? =
        dir.findFile(name)?.let { f -> try { ctx.contentResolver.openInputStream(f.uri)?.use { it.readBytes().toString(Charsets.UTF_8) } } catch (_: Throwable) { null } }

    /** Le fichier de cette version, sinon celui de l'autre (passage de l'une à l'autre). */
    private fun readOwnOrOther(ctx: Context, dir: DocumentFile, own: String, other: String) = readText(ctx, dir, own) ?: readText(ctx, dir, other)

    private fun settingsJson(ctx: Context): JSONObject {
        val o = JSONObject().put("_app", "Di-Allô").put("_date", System.currentTimeMillis()).put("_version", appVersion(ctx))
        val files = JSONObject()
        EXTRA.forEach { (name, skip) -> files.put(name, dump(ctx.getSharedPreferences(name, Context.MODE_PRIVATE), skip)) }
        return o.put("prefs", dump(prefs(ctx), IGNORED)).put("files", files)
    }

    private fun dump(sp: SharedPreferences, skip: Set<String>): JSONObject {
        val vals = JSONObject()
        sp.all.forEach { (k, v) ->
            if (k in skip) return@forEach
            when (v) {
                is Boolean -> vals.put(k, JSONObject().put("t", "b").put("v", v))
                is Int -> vals.put(k, JSONObject().put("t", "i").put("v", v))
                is Long -> vals.put(k, JSONObject().put("t", "l").put("v", v))
                is Float -> vals.put(k, JSONObject().put("t", "f").put("v", v.toDouble()))
                is String -> vals.put(k, JSONObject().put("t", "s").put("v", v))
                is Set<*> -> vals.put(k, JSONObject().put("t", "ss").put("v", org.json.JSONArray(v.map { it.toString() })))
            }
        }
        return vals
    }

    data class Found(val notes: Int, val settings: Int, val date: Long, val backups: Int)

    /** Ce que contient le dossier (pour proposer une restauration). */
    fun inspect(ctx: Context): Found? {
        val dir = tree(ctx) ?: return null
        val notes = readOwnOrOther(ctx, dir, NOTES, NOTES_OTHER)?.let { NotesRepository.fromJson(it).size } ?: 0
        val set = readOwnOrOther(ctx, dir, SETTINGS, SETTINGS_OTHER)?.let { try { JSONObject(it) } catch (_: Throwable) { null } }
        val nSet = set?.optJSONObject("prefs")?.length() ?: 0
        val backups = listOf(BACKUPS, BACKUPS_OTHER).sumOf { b -> dir.findFile(b)?.listFiles()?.count { it.name?.endsWith(".xml") == true } ?: 0 }
        if (notes == 0 && nSet == 0 && backups == 0) return null
        return Found(notes, nSet, set?.optLong("_date") ?: 0L, backups)
    }

    /** Restaure notes + réglages depuis le dossier. Retourne (notes ajoutées, réglages appliqués). */
    fun restore(ctx: Context): Pair<Int, Int> {
        val dir = tree(ctx) ?: return 0 to 0
        val added = readOwnOrOther(ctx, dir, NOTES, NOTES_OTHER)?.let { NotesRepository.merge(ctx, NotesRepository.fromJson(it)) } ?: 0
        var n = 0
        readOwnOrOther(ctx, dir, SETTINGS, SETTINGS_OTHER)?.let { txt ->
            val root = try { JSONObject(txt) } catch (_: Throwable) { null } ?: return@let
            restoring = true
            try {
                root.optJSONObject("prefs")?.let { n += load(prefs(ctx), it, IGNORED) }
                val files = root.optJSONObject("files")
                EXTRA.forEach { (name, skip) -> files?.optJSONObject(name)?.let { n += load(ctx.getSharedPreferences(name, Context.MODE_PRIVATE), it, skip) } }
            } finally { restoring = false }
            ThemeFonts.reload()
        }
        hold = false
        status.value = trn(added, "♻️ Restauré : {0} note, {1} réglages · {2}", "♻️ Restauré : {0} notes, {1} réglages · {2}", n, now())
        return added to n
    }

    /** Remet les valeurs sauvegardées dans [sp] ; retourne le nombre de réglages appliqués. */
    private fun load(sp: SharedPreferences, p: JSONObject, skip: Set<String>): Int {
        var n = 0
        val e = sp.edit()
        p.keys().forEach { k ->
            if (k in skip) return@forEach
            val o = p.optJSONObject(k) ?: return@forEach
            when (o.optString("t")) {
                "b" -> e.putBoolean(k, o.optBoolean("v"))
                "i" -> e.putInt(k, o.optInt("v"))
                "l" -> e.putLong(k, o.optLong("v"))
                "f" -> e.putFloat(k, o.optDouble("v").toFloat())
                "s" -> e.putString(k, o.optString("v"))
                "ss" -> { val a = o.optJSONArray("v"); e.putStringSet(k, (0 until (a?.length() ?: 0)).map { a!!.getString(it) }.toSet()) }
                else -> return@forEach
            }
            n++
        }
        e.commit()
        return n
    }

    data class Backup(val name: String, val uri: Uri, val date: Long, val calls: Boolean, val size: Long = 0L)

    /** Sauvegardes présentes dans Sauvegardes/, de la plus récente à la plus ancienne. */
    fun backups(ctx: Context): List<Backup> {
        val root = tree(ctx) ?: return emptyList()
        val fmt = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
        // celles de cette version, puis celles de l'autre version installée à côté (même dossier)
        return listOfNotNull(root.findFile(BACKUPS), root.findFile(BACKUPS_OTHER)).flatMap { it.listFiles().toList() }.mapNotNull { f ->
            val n = f.name ?: return@mapNotNull null
            if (!n.endsWith(".xml")) return@mapNotNull null
            val calls = n.startsWith("calls-"); if (!calls && !n.startsWith("sms-")) return@mapNotNull null
            val d = try { fmt.parse(n.substringAfter('-').substringBefore('.'))?.time } catch (_: Throwable) { null } ?: f.lastModified()
            Backup(n, f.uri, d, calls, try { f.length() } catch (_: Throwable) { 0L })
        }.sortedByDescending { it.date }
    }

    /** Range un fichier dans un sous-dossier du dossier de travail (ex. Wrapped). */
    /** Sous-dossier du dossier de travail (créé au besoin), ex. Rec. */
    fun subDir(ctx: Context, sub: String): DocumentFile? = tree(ctx)?.let { d -> try { d.findFile(sub) ?: d.createDirectory(sub) } catch (_: Throwable) { null } }

    fun saveBytes(ctx: Context, sub: String, name: String, bytes: ByteArray): Boolean {
        val dir = tree(ctx) ?: return false
        return try {
            val d = dir.findFile(sub) ?: dir.createDirectory(sub) ?: return false
            val f = d.findFile(name) ?: d.createFile("application/octet-stream", name) ?: return false
            ctx.contentResolver.openOutputStream(f.uri, "wt")?.use { it.write(bytes) }; true
        } catch (_: Throwable) { false }
    }

    /* ---------------- Sauvegarde appels + SMS/MMS ---------------- */

    fun autoBackupIfDue(ctx: Context) {
        val app = ctx.applicationContext
        if (!autoBackup(app) || uri(app) == null || busy.value != null) return
        if (System.currentTimeMillis() - lastBackup(app) < 20L * 3600_000L) return
        exec.execute { backupNow(app) }
    }

    fun backupNowAsync(ctx: Context) { val app = ctx.applicationContext; if (busy.value == null) exec.execute { backupNow(app) } }

    private fun backupNow(ctx: Context) {
        val dir = tree(ctx) ?: return
        val out = dir.findFile(BACKUPS) ?: dir.createDirectory(BACKUPS) ?: return
        val stamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date())
        val parts = ArrayList<String>()
        try {
            busy.value = tr("Sauvegarde du journal d'appels…")
            out.createFile("application/octet-stream", "calls-$stamp.xml")?.let { f ->
                ctx.contentResolver.openOutputStream(f.uri, "wt")?.use { val n = exportCalls(ctx, it); parts.add(trn(n, "{0} appel", "{0} appels")) }
            }
            if (!BuildConfig.FULL) Unit
            else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) {
                busy.value = tr("Sauvegarde des SMS / MMS…")
                out.createFile("application/octet-stream", "sms-$stamp.xml")?.let { f ->
                    ctx.contentResolver.openOutputStream(f.uri, "wt")?.use { val (s, m) = exportSms(ctx, it); parts.add(tr("{0} SMS, {1} MMS", s, m)) }
                }
            } else parts.add(tr("SMS non autorisés"))
            rotate(out, "calls-"); rotate(out, "sms-")
            syncNow(ctx)
            prefs(ctx).edit().putLong(LAST, System.currentTimeMillis()).apply()
            status.value = tr("✅ Sauvegarde · {0} · {1}", parts.joinToString(" · "), now())
        } catch (t: Throwable) {
            status.value = tr("❌ Sauvegarde échouée : {0}", t.message ?: t.javaClass.simpleName)
        } finally { busy.value = null }
    }

    private fun rotate(dir: DocumentFile, prefix: String) {
        val fmt = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
        val limit = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L
        dir.listFiles().filter { it.name?.startsWith(prefix) == true && it.name?.endsWith(".xml") == true }
            .sortedByDescending { it.name }.drop(1)
            .filter { f -> (try { fmt.parse(f.name!!.removePrefix(prefix).substringBefore('.'))?.time } catch (_: Throwable) { null } ?: f.lastModified()) < limit }
            .forEach { try { it.delete() } catch (_: Throwable) { } }
    }

    private fun readable(ms: Long) = SimpleDateFormat("d MMM yyyy HH:mm:ss", Locale.FRANCE).format(Date(ms))

    /** Nom des contacts par numéro (clé 9 chiffres). */
    private fun names(ctx: Context): Map<String, String> {
        val m = HashMap<String, String>()
        try {
            ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME), null, null, null)?.use { c ->
                while (c.moveToNext()) { val k = keyOf(c.getString(0) ?: continue); if (k.isNotEmpty()) m.putIfAbsent(k, c.getString(1) ?: continue) }
            }
        } catch (_: Throwable) { }
        return m
    }

    private fun XmlSerializer.a(n: String, v: Any?) { attribute(null, n, v?.toString() ?: "null") }
    private fun serializer(out: OutputStream): XmlSerializer = Xml.newSerializer().apply {
        setOutput(out, "UTF-8"); startDocument("UTF-8", true)
    }

    /** Journal d'appels complet, format SMS Backup & Restore. */
    fun exportCalls(ctx: Context, out: OutputStream): Int {
        val nm = names(ctx)
        val cols = arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DURATION, CallLog.Calls.DATE, CallLog.Calls.TYPE, CallLog.Calls.NUMBER_PRESENTATION,
            CallLog.Calls.PHONE_ACCOUNT_ID, CallLog.Calls.POST_DIAL_DIGITS, CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME, CallLog.Calls.CACHED_NAME)
        var n = 0
        ctx.contentResolver.query(CallLog.Calls.CONTENT_URI, cols, null, null, CallLog.Calls.DATE + " DESC")?.use { c ->
            val x = serializer(out)
            x.startTag(null, "calls"); x.a("count", c.count); x.a("backup_set", UUID.randomUUID()); x.a("backup_date", System.currentTimeMillis()); x.a("type", "full")
            while (c.moveToNext()) {
                val num = c.getString(0) ?: ""
                val date = c.getLong(2)
                x.startTag(null, "call")
                x.a("number", num); x.a("duration", c.getLong(1)); x.a("date", date); x.a("type", c.getInt(3)); x.a("presentation", c.getInt(4))
                x.a("subscription_id", c.getString(5)); x.a("post_dial_digits", c.getString(6) ?: ""); x.a("subscription_component_name", c.getString(7))
                x.a("readable_date", readable(date)); x.a("contact_name", c.getString(8)?.takeIf { it.isNotBlank() } ?: nm[keyOf(num)] ?: "(Unknown)")
                x.endTag(null, "call"); n++
            }
            x.endTag(null, "calls"); x.endDocument(); x.flush()
        }
        return n
    }

    /** SMS + MMS complets (avec photos en base64), format SMS Backup & Restore. Retourne (SMS, MMS). */
    fun exportSms(ctx: Context, out: OutputStream): Pair<Int, Int> {
        val nm = names(ctx)
        val cr = ctx.contentResolver
        val smsC = cr.query(Telephony.Sms.CONTENT_URI, null, null, null, Telephony.Sms.DATE + " DESC")
        val mmsC = cr.query(Telephony.Mms.CONTENT_URI, null, null, null, Telephony.Mms.DATE + " DESC")
        var ns = 0; var nm2 = 0
        val x = serializer(out)
        x.startTag(null, "smses")
        x.a("count", (smsC?.count ?: 0) + (mmsC?.count ?: 0)); x.a("backup_set", UUID.randomUUID()); x.a("backup_date", System.currentTimeMillis()); x.a("type", "full")
        smsC?.use { c ->
            fun s(col: String) = c.getColumnIndex(col).let { if (it >= 0) c.getString(it) else null }
            while (c.moveToNext()) {
                val addr = s(Telephony.Sms.ADDRESS) ?: ""
                val date = s(Telephony.Sms.DATE)?.toLongOrNull() ?: 0L
                x.startTag(null, "sms")
                x.a("protocol", s(Telephony.Sms.PROTOCOL) ?: "0"); x.a("address", addr); x.a("date", date); x.a("type", s(Telephony.Sms.TYPE))
                x.a("subject", s(Telephony.Sms.SUBJECT)); x.a("body", s(Telephony.Sms.BODY) ?: ""); x.a("toa", null); x.a("sc_toa", null)
                x.a("service_center", s(Telephony.Sms.SERVICE_CENTER)); x.a("read", s(Telephony.Sms.READ) ?: "1"); x.a("status", s(Telephony.Sms.STATUS) ?: "-1")
                x.a("locked", s(Telephony.Sms.LOCKED) ?: "0"); x.a("date_sent", s(Telephony.Sms.DATE_SENT) ?: "0"); x.a("sub_id", s("sub_id") ?: "-1")
                x.a("readable_date", readable(date)); x.a("contact_name", nm[keyOf(addr)] ?: "(Unknown)")
                x.endTag(null, "sms"); ns++
                if (ns % 500 == 0) busy.value = tr("Sauvegarde des SMS… {0}", ns)
            }
        }
        mmsC?.use { c ->
            val skip = setOf("_id", "thread_id")
            while (c.moveToNext()) {
                val id = c.getLong(c.getColumnIndexOrThrow(Telephony.Mms._ID))
                val dateS = c.getLong(c.getColumnIndexOrThrow(Telephony.Mms.DATE))
                // adresses
                val addrs = ArrayList<Triple<String, String, String>>()
                cr.query(Uri.parse("content://mms/$id/addr"), arrayOf("address", "type", "charset"), null, null, null)?.use { a ->
                    while (a.moveToNext()) addrs.add(Triple(a.getString(0) ?: "", a.getString(1) ?: "137", a.getString(2) ?: "106"))
                }
                val others = addrs.map { it.first }.filter { it.isNotBlank() && it != "insert-address-token" }.distinct()
                x.startTag(null, "mms")
                for (i in 0 until c.columnCount) {
                    val col = c.getColumnName(i)
                    if (col in skip || col == "date") continue
                    x.a(col, if (c.getType(i) == android.database.Cursor.FIELD_TYPE_BLOB) null else c.getString(i))
                }
                x.a("date", dateS * 1000)
                x.a("address", others.joinToString("~"))
                x.a("readable_date", readable(dateS * 1000))
                x.a("contact_name", others.joinToString(", ") { nm[keyOf(it)] ?: it })
                x.startTag(null, "parts")
                cr.query(Uri.parse("content://mms/$id/part"), null, null, null, null)?.use { p ->
                    fun s(col: String) = p.getColumnIndex(col).let { if (it >= 0) p.getString(it) else null }
                    while (p.moveToNext()) {
                        val pid = s("_id") ?: continue
                        val ct = s("ct") ?: ""
                        x.startTag(null, "part")
                        listOf("seq", "ct", "name", "chset", "cd", "fn", "cid", "cl", "ctt_s", "ctt_t", "text").forEach { k -> x.a(k, s(k)) }
                        if (s("_data") != null && ct != "text/plain" && ct != "application/smil") {
                            val bytes = try { cr.openInputStream(Uri.parse("content://mms/part/$pid"))?.use { it.readBytes() } } catch (_: Throwable) { null }
                            if (bytes != null) x.a("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                        }
                        x.endTag(null, "part")
                    }
                }
                x.endTag(null, "parts")
                x.startTag(null, "addrs")
                addrs.forEach { (ad, t, cs) -> x.startTag(null, "addr"); x.a("address", ad); x.a("type", t); x.a("charset", cs); x.endTag(null, "addr") }
                x.endTag(null, "addrs")
                x.endTag(null, "mms"); nm2++
                if (nm2 % 50 == 0) busy.value = tr("Sauvegarde des MMS… {0}", nm2)
            }
        }
        x.endTag(null, "smses"); x.endDocument(); x.flush()
        return ns to nm2
    }
}
