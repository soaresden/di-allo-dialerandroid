package fr.soaresden.diallo

import android.content.Context
import android.provider.CallLog
import android.provider.ContactsContract
import java.text.Normalizer

/** Lit le VRAI journal d'appels + contacts (noms, photos, libellés), résout et calcule les stats. */
object CallRepository {

    data class CallEntry(
        val number: String, val name: String?, val photoUri: String?,
        val date: Long, val duration: Int, val type: Int, val id: Long = -1
    ) {
        val display: String get() = name ?: number
        val dirLabel: String get() = when (type) {
            CallLog.Calls.OUTGOING_TYPE -> tr("Sortant")
            CallLog.Calls.INCOMING_TYPE -> tr("Entrant")
            CallLog.Calls.MISSED_TYPE -> tr("Manqué")
            CallLog.Calls.REJECTED_TYPE -> tr("Rejeté")
            CallLog.Calls.VOICEMAIL_TYPE -> tr("Messagerie")
            else -> tr("Appel")
        }
    }

    data class Contact(
        val number: String, val name: String?, val photoUri: String?,
        val contactId: Long = -1, val groups: List<String> = emptyList(), val starred: Boolean = false,
        val label: String? = null, val calls: Int = 0, val last: Long = 0, val score: Double = 0.0
    ) {
        val display: String get() = name ?: number
        val t9: String get() = t9Of(name ?: "")
        val digits: String get() = number.filter { it.isDigit() }
    }

    data class Stat(
        val number: String, var name: String?, var photoUri: String?,
        var count: Int = 0, var first: Long = Long.MAX_VALUE, var last: Long = 0,
        var totalDur: Long = 0, var answered: Int = 0, var out: Int = 0, var inc: Int = 0, var missed: Int = 0
    ) {
        val display: String get() = name ?: number
        val avgDur: Long get() = if (answered > 0) totalDur / answered else 0
    }

    data class Data(
        val calls: List<CallEntry>, val contacts: List<Contact>,
        val stats: Map<String, Stat>, val groups: List<String> = emptyList()
    )

    fun load(ctx: Context): Data {
        val contacts = contacts(ctx)
        val byKey = HashMap<String, Contact>()
        for (c in contacts) { val k = key(c.number); if (k.isNotEmpty() && !byKey.containsKey(k)) byKey[k] = c }
        val raw = rawCalls(ctx)
        val calls = raw.map { e ->
            if (e.name != null && e.photoUri != null) e
            else { val m = byKey[key(e.number)]; e.copy(name = e.name ?: m?.name, photoUri = e.photoUri ?: m?.photoUri) }
        }
        val groups = contacts.flatMap { it.groups }.distinct().sortedWith(compareBy({ !it.startsWith("⭐") }, { it.lowercase() }))
        val st = stats(calls)
        // nombre d'appels par numéro -> sert à trier les numéros d'une même personne
        // Score "intelligent" (fréquence x récence) : chaque appel compte, mais d'autant moins qu'il est ancien
        // (demi-vie ~2 mois). Un appel décroché pèse plus qu'un manqué, un long appel un peu plus.
        val now = System.currentTimeMillis()
        val score = HashMap<String, Double>()
        for (e in calls) {
            val k = key(e.number)
            if (k.isEmpty()) continue
            val ageDays = (now - e.date) / 86_400_000.0
            val w = Math.pow(0.5, ageDays / 60.0) * (if (e.duration > 0) 1.0 + kotlin.math.ln(1.0 + e.duration / 120.0) * 0.3 else 0.35)
            score[k] = (score[k] ?: 0.0) + w
        }
        val withCounts = contacts.map { c -> val k = key(c.number); c.copy(calls = st[k]?.count ?: 0, last = st[k]?.last ?: 0L, score = score[k] ?: 0.0) }
        return Data(calls, withCounts, st, groups)
    }

    /** Supprime UN appel du journal (Di-Allô doit être l'appli Téléphone par défaut). */
    fun deleteCall(ctx: Context, id: Long): Boolean = id >= 0 && try {
        ctx.contentResolver.delete(CallLog.Calls.CONTENT_URI, CallLog.Calls._ID + "=?", arrayOf(id.toString())) > 0
    } catch (_: Throwable) { false }

    private fun rawCalls(ctx: Context): List<CallEntry> {
        val list = ArrayList<CallEntry>()
        val cols = arrayOf(
            CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.CACHED_PHOTO_URI,
            CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE, CallLog.Calls._ID
        )
        try {
            ctx.contentResolver.query(CallLog.Calls.CONTENT_URI, cols, null, null, CallLog.Calls.DATE + " DESC")?.use { c ->
                val iN = c.getColumnIndex(CallLog.Calls.NUMBER); val iNm = c.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val iPh = c.getColumnIndex(CallLog.Calls.CACHED_PHOTO_URI); val iD = c.getColumnIndex(CallLog.Calls.DATE)
                val iDur = c.getColumnIndex(CallLog.Calls.DURATION); val iT = c.getColumnIndex(CallLog.Calls.TYPE)
                val iId = c.getColumnIndex(CallLog.Calls._ID)
                while (c.moveToNext()) {
                    val num = c.getString(iN) ?: tr("Inconnu")
                    val nm = if (iNm >= 0) c.getString(iNm)?.ifBlank { null } else null
                    val ph = if (iPh >= 0) c.getString(iPh)?.ifBlank { null } else null
                    list.add(CallEntry(num, nm, ph, c.getLong(iD), c.getInt(iDur), c.getInt(iT), if (iId >= 0) c.getLong(iId) else -1))
                }
            }
        } catch (_: Throwable) { }
        return list
    }

    fun contacts(ctx: Context): List<Contact> {
        val membership = groupTitlesByContact(ctx)
        val map = LinkedHashMap<String, Contact>()
        val cols = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.PHOTO_URI,
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.STARRED,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LABEL
        )
        try {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI, cols, null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )?.use { c ->
                val iN = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val iNm = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val iPh = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)
                val iId = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val iSt = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.STARRED)
                val iTy = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
                val iLb = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.LABEL)
                while (c.moveToNext()) {
                    val num = c.getString(iN) ?: continue
                    val k = key(num)
                    if (map.containsKey(k)) continue
                    val id = if (iId >= 0) c.getLong(iId) else -1
                    val label = try {
                        if (iTy >= 0) ContactsContract.CommonDataKinds.Phone.getTypeLabel(ctx.resources, c.getInt(iTy), if (iLb >= 0) c.getString(iLb) else null).toString() else null
                    } catch (_: Throwable) { null }
                    map[k] = Contact(num, c.getString(iNm)?.ifBlank { null }, if (iPh >= 0) c.getString(iPh) else null, id, membership[id] ?: emptyList(), iSt >= 0 && c.getInt(iSt) == 1, label)
                }
            }
        } catch (_: Throwable) { }
        return map.values.toList()
    }

    /** contactId -> libellés (groupes Google visibles). */
    private fun groupTitlesByContact(ctx: Context): Map<Long, List<String>> {
        val idToTitle = HashMap<Long, String>()
        try {
            ctx.contentResolver.query(
                ContactsContract.Groups.CONTENT_URI,
                arrayOf(ContactsContract.Groups._ID, ContactsContract.Groups.TITLE, ContactsContract.Groups.SYSTEM_ID, ContactsContract.Groups.DELETED, ContactsContract.Groups.FAVORITES),
                null, null, null
            )?.use { c ->
                // NB : les libellés Google ont group_visible=0 -> on ne filtre PAS dessus.
                val iId = c.getColumnIndex(ContactsContract.Groups._ID)
                val iT = c.getColumnIndex(ContactsContract.Groups.TITLE)
                val iS = c.getColumnIndex(ContactsContract.Groups.SYSTEM_ID)
                val iD = c.getColumnIndex(ContactsContract.Groups.DELETED)
                val iF = c.getColumnIndex(ContactsContract.Groups.FAVORITES)
                while (c.moveToNext()) {
                    if (iD >= 0 && c.getInt(iD) == 1) continue
                    if (iF >= 0 && c.getInt(iF) == 1) { idToTitle[c.getLong(iId)] = "⭐ Favoris"; continue }
                    if (iS >= 0 && !c.isNull(iS)) continue          // "My Contacts" & co
                    val title = c.getString(iT)?.trim()?.ifBlank { null } ?: continue
                    if (title.startsWith("System Group")) continue
                    idToTitle[c.getLong(iId)] = title
                }
            }
        } catch (_: Throwable) { }

        val result = HashMap<Long, MutableList<String>>()
        try {
            val mime = ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE
            ctx.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data.CONTACT_ID, ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID),
                ContactsContract.Data.MIMETYPE + "=?", arrayOf(mime), null
            )?.use { c ->
                val iC = c.getColumnIndex(ContactsContract.Data.CONTACT_ID)
                val iG = c.getColumnIndex(ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID)
                while (c.moveToNext()) {
                    val title = idToTitle[c.getLong(iG)] ?: continue
                    val cid = c.getLong(iC)
                    val l = result.getOrPut(cid) { mutableListOf() }
                    if (!l.contains(title)) l.add(title)
                }
            }
        } catch (_: Throwable) { }
        return result
    }

    private fun stats(calls: List<CallEntry>): Map<String, Stat> {
        val m = LinkedHashMap<String, Stat>()
        for (e in calls) {
            val k = key(e.number).ifEmpty { e.number }
            val s = m.getOrPut(k) { Stat(e.number, e.name, e.photoUri) }
            if (s.name == null && e.name != null) s.name = e.name
            if (s.photoUri == null && e.photoUri != null) s.photoUri = e.photoUri
            s.count++; s.totalDur += e.duration
            if (e.date < s.first) s.first = e.date
            if (e.date > s.last) s.last = e.date
            when (e.type) {
                CallLog.Calls.OUTGOING_TYPE -> s.out++
                CallLog.Calls.INCOMING_TYPE -> s.inc++
                CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> s.missed++
            }
            if (e.type != CallLog.Calls.MISSED_TYPE && e.type != CallLog.Calls.REJECTED_TYPE && e.duration > 0) s.answered++
        }
        return m
    }

    private fun key(n: String): String { val d = n.filter { it.isDigit() }; return if (d.length > 9) d.takeLast(9) else d }

    /** Convertit un nom en séquence T9 (accents retirés). */
    fun t9Of(name: String): String {
        val clean = Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").uppercase()
        val sb = StringBuilder()
        for (ch in clean) sb.append(when (ch) {
            in 'A'..'C' -> '2'; in 'D'..'F' -> '3'; in 'G'..'I' -> '4'; in 'J'..'L' -> '5'
            in 'M'..'O' -> '6'; in 'P'..'S' -> '7'; in 'T'..'V' -> '8'; in 'W'..'Z' -> '9'
            else -> ' '
        })
        return sb.toString()
    }
}
