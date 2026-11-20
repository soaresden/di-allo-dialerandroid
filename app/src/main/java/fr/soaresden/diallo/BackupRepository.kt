package fr.soaresden.diallo

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CallLog
import android.provider.Telephony
import android.util.Xml
import org.xmlpull.v1.XmlPullParser

/**
 * Backups "SMS Backup & Restore" : appels (calls-*.xml) et SMS (sms-*.xml).
 * Compare au contenu du téléphone et complète ce qui manque.
 */
object BackupRepository {

    enum class Kind { CALLS, SMS, UNKNOWN }

    data class BackupCall(val number: String, val date: Long, val duration: Int, val type: Int, val name: String?)
    data class Analysis(val total: Int, val present: Int, val missing: List<BackupCall>) { val missingCount get() = missing.size }

    data class BackupSms(
        val address: String, val body: String, val date: Long, val dateSent: Long, val type: Int,
        val read: Int, val status: Int, val subject: String?, val serviceCenter: String?, val locked: Int
    )
    /** MMS au format SMS Backup & Restore : attributs bruts + pièces (data en base64) + adresses. */
    data class BackupMms(val attrs: Map<String, String?>, val parts: List<Map<String, String?>>, val addrs: List<Map<String, String?>>) {
        val dateMs: Long get() = attrs["date"]?.toLongOrNull()?.let { if (it < 100_000_000_000L) it * 1000 else it } ?: 0L
        val box: Int get() = attrs["msg_box"]?.toIntOrNull() ?: 1
    }
    data class SmsParse(val sms: List<BackupSms>, val mms: List<BackupMms>, val mmsSkipped: Int = 0)
    data class SmsAnalysis(val total: Int, val present: Int, val missing: List<BackupSms>, val missingMms: List<BackupMms>, val mmsCount: Int) {
        val missingCount get() = missing.size + missingMms.size
    }

    private fun attr(p: XmlPullParser, n: String): String? = p.getAttributeValue(null, n)?.takeIf { it != "null" }

    /** Regarde la balise racine : <calls> ou <smses>. */
    fun detect(ctx: Context, uri: Uri): Kind {
        try {
            ctx.contentResolver.openInputStream(uri)?.use { ins ->
                val p = Xml.newPullParser(); p.setInput(ins, null)
                var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG) return when (p.name) { "calls" -> Kind.CALLS; "smses" -> Kind.SMS; else -> Kind.UNKNOWN }
                    ev = p.next()
                }
            }
        } catch (_: Throwable) { }
        return Kind.UNKNOWN
    }

    /* ---------------- Appels ---------------- */
    fun parse(ctx: Context, uri: Uri): List<BackupCall> {
        val out = ArrayList<BackupCall>()
        try {
            ctx.contentResolver.openInputStream(uri)?.use { ins ->
                val p = Xml.newPullParser()
                p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                p.setInput(ins, null)
                var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG && p.name == "call") {
                        val number = attr(p, "number")?.trim() ?: ""
                        val date = attr(p, "date")?.toLongOrNull() ?: 0L
                        val dur = attr(p, "duration")?.toIntOrNull() ?: 0
                        val type = attr(p, "type")?.toIntOrNull() ?: 0
                        val nm = attr(p, "contact_name")
                        if (date > 0 && number.isNotEmpty())
                            out.add(BackupCall(number, date, dur, type, if (nm.isNullOrBlank() || nm == "(Unknown)") null else nm))
                    }
                    ev = p.next()
                }
            }
        } catch (_: Throwable) { }
        return out
    }

    fun analyze(ctx: Context, backup: List<BackupCall>): Analysis {
        val existing = HashSet<Long>()
        try {
            ctx.contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.DATE), null, null, null)?.use { c ->
                val i = c.getColumnIndex(CallLog.Calls.DATE)
                while (c.moveToNext()) existing.add(c.getLong(i))
            }
        } catch (_: Throwable) { }
        val seen = HashSet<Long>()
        val missing = ArrayList<BackupCall>()
        var present = 0
        for (b in backup) {
            if (existing.contains(b.date)) { present++; continue }
            if (seen.add(b.date)) missing.add(b)
        }
        return Analysis(backup.size, present, missing)
    }

    fun importMissing(ctx: Context, missing: List<BackupCall>): Int {
        if (missing.isEmpty()) return 0
        val rows = missing.map { b ->
            ContentValues().apply {
                put(CallLog.Calls.NUMBER, b.number)
                put(CallLog.Calls.DATE, b.date)
                put(CallLog.Calls.DURATION, b.duration)
                put(CallLog.Calls.TYPE, if (b.type in 1..7) b.type else CallLog.Calls.OUTGOING_TYPE)
                put(CallLog.Calls.NEW, 0)
                if (b.name != null) put(CallLog.Calls.CACHED_NAME, b.name)
            }
        }.toTypedArray()
        return try { ctx.contentResolver.bulkInsert(CallLog.Calls.CONTENT_URI, rows) } catch (_: Throwable) { 0 }
    }

    /* ---------------- SMS ---------------- */
    fun parseSms(ctx: Context, uri: Uri): SmsParse {
        val out = ArrayList<BackupSms>()
        val mms = ArrayList<BackupMms>()
        var cur: MutableMap<String, String?>? = null
        var parts = ArrayList<Map<String, String?>>()
        var addrs = ArrayList<Map<String, String?>>()
        fun all(p: XmlPullParser): MutableMap<String, String?> { val m = LinkedHashMap<String, String?>(); for (i in 0 until p.attributeCount) m[p.getAttributeName(i)] = p.getAttributeValue(i).takeIf { it != "null" }; return m }
        try {
            ctx.contentResolver.openInputStream(uri)?.use { ins ->
                val p = Xml.newPullParser()
                p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                p.setInput(ins, null)
                var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG) when (p.name) {
                        "sms" -> {
                            val addr = attr(p, "address")?.trim() ?: ""
                            val date = attr(p, "date")?.toLongOrNull() ?: 0L
                            if (date > 0 && addr.isNotEmpty()) out.add(
                                BackupSms(
                                    addr, attr(p, "body") ?: "", date, attr(p, "date_sent")?.toLongOrNull() ?: 0L,
                                    attr(p, "type")?.toIntOrNull() ?: Telephony.Sms.MESSAGE_TYPE_INBOX,
                                    attr(p, "read")?.toIntOrNull() ?: 1, attr(p, "status")?.toIntOrNull() ?: -1,
                                    attr(p, "subject"), attr(p, "service_center"), attr(p, "locked")?.toIntOrNull() ?: 0
                                )
                            )
                        }
                        "mms" -> { cur = all(p); parts = ArrayList(); addrs = ArrayList() }
                        "part" -> if (cur != null) parts.add(all(p))
                        "addr" -> if (cur != null) addrs.add(all(p))
                    } else if (ev == XmlPullParser.END_TAG && p.name == "mms") {
                        cur?.let { mms.add(BackupMms(it, parts, addrs)) }; cur = null
                    }
                    ev = p.next()
                }
            }
        } catch (_: Throwable) { }
        return SmsParse(out, mms)
    }

    private fun smsKey(date: Long, addr: String) = "$date|${keyOf(addr)}"

    fun analyzeSms(ctx: Context, parsed: SmsParse): SmsAnalysis {
        val existing = HashSet<String>()
        try {
            ctx.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.DATE, Telephony.Sms.ADDRESS), null, null, null)?.use { c ->
                val iD = c.getColumnIndex(Telephony.Sms.DATE); val iA = c.getColumnIndex(Telephony.Sms.ADDRESS)
                while (c.moveToNext()) existing.add(smsKey(c.getLong(iD), c.getString(iA) ?: ""))
            }
        } catch (_: Throwable) { }
        val seen = HashSet<String>()
        val missing = ArrayList<BackupSms>()
        var present = 0
        for (s in parsed.sms) {
            val k = smsKey(s.date, s.address)
            if (existing.contains(k)) { present++; continue }
            if (seen.add(k)) missing.add(s)
        }
        // MMS : clé = date (s) | boîte
        val existingMms = HashSet<String>()
        try {
            ctx.contentResolver.query(Telephony.Mms.CONTENT_URI, arrayOf(Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX), null, null, null)?.use { c ->
                while (c.moveToNext()) existingMms.add("${c.getLong(0)}|${c.getInt(1)}")
            }
        } catch (_: Throwable) { }
        val missingMms = ArrayList<BackupMms>()
        for (m in parsed.mms) {
            val k = "${m.dateMs / 1000}|${m.box}"
            if (existingMms.contains(k)) { present++; continue }
            if (seen.add("mms$k")) missingMms.add(m)
        }
        return SmsAnalysis(parsed.sms.size + parsed.mms.size, present, missing, missingMms, parsed.mms.size)
    }

    /** Écrit les SMS manquants. N'aboutit QUE si Di-Allô est l'app SMS par défaut. */
    fun importSms(ctx: Context, missing: List<BackupSms>, progress: (Int) -> Unit): Int {
        var n = 0
        for ((i, s) in missing.withIndex()) {
            val v = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, s.address)
                put(Telephony.Sms.BODY, s.body)
                put(Telephony.Sms.DATE, s.date)
                if (s.dateSent > 0) put(Telephony.Sms.DATE_SENT, s.dateSent)
                put(Telephony.Sms.TYPE, s.type)
                put(Telephony.Sms.READ, s.read)
                put(Telephony.Sms.SEEN, 1)
                put(Telephony.Sms.STATUS, s.status)
                put(Telephony.Sms.LOCKED, s.locked)
                if (!s.subject.isNullOrBlank()) put(Telephony.Sms.SUBJECT, s.subject)
                if (!s.serviceCenter.isNullOrBlank()) put(Telephony.Sms.SERVICE_CENTER, s.serviceCenter)
            }
            try { if (ctx.contentResolver.insert(Telephony.Sms.CONTENT_URI, v) != null) n++ } catch (_: Throwable) { }
            if (i % 50 == 0) progress(i)
        }
        progress(missing.size)
        return n
    }

    /** Écrit les MMS manquants (texte + photos…). Nécessite aussi d'être l'app SMS par défaut. */
    fun importMms(ctx: Context, missing: List<BackupMms>, progress: (Int) -> Unit): Int {
        var n = 0
        // Colonnes connues du fournisseur MMS (les autres attributs du fichier sont ignorés)
        val keep = setOf("rr", "sub", "ct_t", "read_status", "sub_cs", "resp_st", "retr_st", "d_tm", "exp", "locked", "m_id", "st", "retr_txt_cs", "retr_txt",
            "creator", "date_sent", "m_size", "rpt_a", "ct_cls", "pri", "tr_id", "resp_txt", "ct_l", "m_cls", "d_rpt", "v", "m_type")
        for ((i, m) in missing.withIndex()) {
            try {
                val recips = m.addrs.mapNotNull { it["address"] }.filter { it.isNotBlank() && it != "insert-address-token" }.toSet()
                val v = ContentValues()
                m.attrs.forEach { (k, value) -> if (k in keep && value != null) v.put(k, value) }
                v.put(Telephony.Mms.DATE, m.dateMs / 1000)
                v.put(Telephony.Mms.MESSAGE_BOX, m.box)
                v.put(Telephony.Mms.READ, m.attrs["read"]?.toIntOrNull() ?: 1)
                v.put(Telephony.Mms.SEEN, 1)
                if (recips.isNotEmpty()) v.put(Telephony.Mms.THREAD_ID, Telephony.Threads.getOrCreateThreadId(ctx, recips))
                val uri = ctx.contentResolver.insert(Telephony.Mms.CONTENT_URI, v) ?: continue
                val id = uri.lastPathSegment ?: continue
                for (pt in m.parts) {
                    val pv = ContentValues()
                    listOf("seq", "ct", "name", "chset", "cd", "fn", "cid", "cl", "ctt_s", "ctt_t", "text").forEach { k -> pt[k]?.let { pv.put(k, it) } }
                    pv.put("mid", id)
                    val pUri = ctx.contentResolver.insert(Uri.parse("content://mms/$id/part"), pv) ?: continue
                    pt["data"]?.let { b64 -> ctx.contentResolver.openOutputStream(pUri)?.use { it.write(android.util.Base64.decode(b64, android.util.Base64.DEFAULT)) } }
                }
                for (a in m.addrs) {
                    val av = ContentValues()
                    av.put("address", a["address"] ?: continue); av.put("type", a["type"] ?: "137"); av.put("charset", a["charset"] ?: "106"); av.put("msg_id", id)
                    ctx.contentResolver.insert(Uri.parse("content://mms/$id/addr"), av)
                }
                n++
            } catch (_: Throwable) { }
            if (i % 10 == 0) progress(i)
        }
        progress(missing.size)
        return n
    }
}
