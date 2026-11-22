package fr.soaresden.diallo

import android.provider.CallLog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.filled.PhoneMissed
import androidx.compose.material.icons.filled.PhoneCallback
import androidx.compose.material.icons.filled.PhoneForwarded
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Calendar
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/* ---------- Périodes & découpage temporel ---------- */
enum class Gran { HOUR, DAY, WEEK, MONTH, YEAR }
/** Filtre posé en touchant une barre (heure, jour, pays, type…) : un seul filtre par sorte [kind]. */
class SFilter(val kind: String, val id: String, val label: String, val test: (CallRepository.CallEntry) -> Boolean)
data class Range(val start: Long, val end: Long, val label: String, val gran: Gran)
data class Bucket(val start: Long, val end: Long, val label: String)

private const val DAY_MS = 86_400_000L
private fun cal(ts: Long) = Calendar.getInstance().apply { timeInMillis = ts }
private fun startOfDay(ts: Long) = cal(ts).apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

internal val PRESETS = listOf("7 j", "30 j", "3 mois", "1 an", "Tout")
internal fun presetRange(i: Int, oldest: Long): Range {
    val now = System.currentTimeMillis()
    val today = startOfDay(now)
    return when (i) {
        0 -> Range(today - 6 * DAY_MS, now, tr("7 derniers jours"), Gran.DAY)
        1 -> Range(today - 29 * DAY_MS, now, tr("30 derniers jours"), Gran.DAY)
        2 -> Range(today - 12 * 7 * DAY_MS - 6 * DAY_MS, now, tr("3 derniers mois"), Gran.WEEK)
        3 -> Range(cal(today).apply { set(Calendar.DAY_OF_MONTH, 1); add(Calendar.MONTH, -11) }.timeInMillis, now, tr("12 derniers mois"), Gran.MONTH)
        else -> Range(cal(oldest).apply { set(Calendar.DAY_OF_YEAR, 1) }.let { startOfDay(it.timeInMillis) }, now, tr("Depuis le début"), Gran.YEAR)
    }
}

internal fun buckets(r: Range): List<Bucket> {
    val out = ArrayList<Bucket>()
    val c = cal(startOfDay(r.start))
    when (r.gran) {
        Gran.MONTH -> c.set(Calendar.DAY_OF_MONTH, 1)
        Gran.YEAR -> c.set(Calendar.DAY_OF_YEAR, 1)
        else -> {}
    }
    var guard = 0
    while (c.timeInMillis <= r.end && guard++ < 400) {
        val s = c.timeInMillis
        val label = when (r.gran) {
            Gran.HOUR -> c.get(Calendar.HOUR_OF_DAY).let { if (it % 3 == 0) "${it}h" else "" }
            Gran.DAY -> if (r.end - r.start <= 8 * DAY_MS) wdInitial(c.get(Calendar.DAY_OF_WEEK)) else c.get(Calendar.DAY_OF_MONTH).toString()
            Gran.WEEK -> "${c.get(Calendar.DAY_OF_MONTH)}/${c.get(Calendar.MONTH) + 1}"
            Gran.MONTH -> monthName(c.get(Calendar.MONTH)).take(1).uppercase()
            Gran.YEAR -> "'" + (c.get(Calendar.YEAR) % 100).toString().padStart(2, '0')
        }
        when (r.gran) {
            Gran.HOUR -> c.add(Calendar.HOUR_OF_DAY, 1)
            Gran.DAY -> c.add(Calendar.DAY_OF_YEAR, 1); Gran.WEEK -> c.add(Calendar.DAY_OF_YEAR, 7)
            Gran.MONTH -> c.add(Calendar.MONTH, 1); Gran.YEAR -> c.add(Calendar.YEAR, 1)
        }
        out.add(Bucket(s, c.timeInMillis, label))
    }
    return out
}

/** Initiale du jour (Calendar.SUNDAY…SATURDAY) dans la langue affichée : D L M M J V S, S M T W T F S… */
private fun wdInitial(dow: Int): String =
    Calendar.getInstance().apply { set(Calendar.DAY_OF_WEEK, dow) }.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, I18n.locale)
        ?.take(1)?.uppercase(I18n.locale) ?: ""

/** Nom complet du jour (Calendar.SUNDAY…SATURDAY) dans la langue affichée. */
private fun wdName(dow: Int): String =
    Calendar.getInstance().apply { set(Calendar.DAY_OF_WEEK, dow) }.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, I18n.locale) ?: ""

/** Zoom sur une barre : année -> mois -> jours. */
internal fun drill(b: Bucket, g: Gran): Range? {
    val c = cal(b.start)
    return when (g) {
        Gran.YEAR -> Range(b.start, b.end - 1, tr("Année {0}", c.get(Calendar.YEAR).toString()), Gran.MONTH)
        Gran.MONTH -> Range(b.start, b.end - 1, "${monthName(c.get(Calendar.MONTH)).replaceFirstChar { it.uppercase() }} ${c.get(Calendar.YEAR)}", Gran.DAY)
        Gran.WEEK -> Range(b.start, b.end - 1, tr("Semaine du {0}", fmtDate(b.start)), Gran.DAY)
        Gran.DAY -> Range(b.start, b.end - 1, "${wdName(c.get(Calendar.DAY_OF_WEEK)).replaceFirstChar { it.uppercase() }} ${fmtDate(b.start)}", Gran.HOUR)
        Gran.HOUR -> null
    }
}

internal fun countIn(calls: List<CallRepository.CallEntry>, bs: List<Bucket>): List<Int> {
    val arr = IntArray(bs.size)
    if (bs.isEmpty()) return arr.toList()
    val starts = LongArray(bs.size) { bs[it].start }
    for (c in calls) {
        if (c.date < bs.first().start || c.date >= bs.last().end) continue
        var i = java.util.Arrays.binarySearch(starts, c.date); if (i < 0) i = -i - 2
        if (i in arr.indices) arr[i]++
    }
    return arr.toList()
}

private fun isMissed(t: Int) = t == CallLog.Calls.MISSED_TYPE || t == CallLog.Calls.REJECTED_TYPE
private fun isIncomingSide(t: Int) = t == CallLog.Calls.INCOMING_TYPE || isMissed(t)

/* ---------- Faits marrants ---------- */
data class Fact(val emoji: String, val title: String, val value: String, val sub: String, val key: String? = null)

private class Agg(val key: String, val number: String, var name: String?, var photo: String?) {
    var n = 0; var out = 0; var inc = 0; var miss = 0; var outNoAns = 0; var ans = 0; var dur = 0L
    var first = Long.MAX_VALUE; var last = 0L; var maxGap = 0L; var prev = 0L; var gapEnd = 0L
    val display get() = name ?: number
}

private fun aggregate(calls: List<CallRepository.CallEntry>): Map<String, Agg> {
    val m = HashMap<String, Agg>()
    for (c in calls.sortedBy { it.date }) {
        val k = keyOf(c.number).ifEmpty { c.number }
        val a = m.getOrPut(k) { Agg(k, c.number, c.name, c.photoUri) }
        if (a.name == null) a.name = c.name; if (a.photo == null) a.photo = c.photoUri
        a.n++
        when {
            c.type == CallLog.Calls.OUTGOING_TYPE -> { a.out++; if (c.duration == 0) a.outNoAns++ }
            c.type == CallLog.Calls.INCOMING_TYPE -> a.inc++
            isMissed(c.type) -> a.miss++
        }
        if (c.duration > 0) { a.ans++; a.dur += c.duration }
        if (a.prev > 0 && c.date - a.prev > a.maxGap) { a.maxGap = c.date - a.prev; a.gapEnd = c.date }
        a.prev = c.date
        if (c.date < a.first) a.first = c.date; if (c.date > a.last) a.last = c.date
    }
    return m
}

private fun pct(a: Int, b: Int) = if (b == 0) 0 else (a * 100 / b)

private fun facts(calls: List<CallRepository.CallEntry>, aggs: Map<String, Agg>, contactKeys: Set<String>): List<Fact> {
    if (calls.isEmpty()) return emptyList()
    val f = ArrayList<Fact>()
    val people = aggs.values.filter { it.number.any(Char::isDigit) }

    calls.minByOrNull { it.date }?.let { f += Fact("🦕", tr("Le plus vieil appel"), it.display, tr("{0} · il y a {1} · {2}", fmtDate(it.date), agoText(it.date), it.dirLabel.lowercase(I18n.locale)), keyOf(it.number)) }

    people.filter { it.inc + it.miss >= 3 }.maxWithOrNull(compareBy<Agg>({ it.miss.toDouble() / (it.inc + it.miss) }, { it.miss }))?.takeIf { it.miss > 0 }?.let {
        f += Fact("👻", tr("La personne la plus ghostée"), it.display, tr("Tu as ignoré {0} de ses {1} appels ({2} %)", it.miss, it.inc + it.miss, pct(it.miss, it.inc + it.miss)), it.key)
    }
    people.filter { it.out >= 3 }.maxWithOrNull(compareBy<Agg>({ it.outNoAns.toDouble() / it.out }, { it.outNoAns }))?.takeIf { it.outNoAns > 0 }?.let {
        f += Fact("🙈", tr("Celle/celui qui te ghoste"), it.display, trn(it.outNoAns, "{0} appel sans réponse sur {1} ({2} %)", "{0} appels sans réponse sur {1} ({2} %)", it.out, pct(it.outNoAns, it.out)), it.key)
    }
    people.filter { it.ans >= 3 }.maxByOrNull { it.dur / it.ans }?.let {
        f += Fact("🗣️", tr("Le/la plus bavard·e"), it.display, tr("{0} en moyenne sur {1} appels", fmtDur((it.dur / it.ans).toInt()), it.ans), it.key)
    }
    calls.maxByOrNull { it.duration }?.takeIf { it.duration > 0 }?.let {
        f += Fact("⏱️", tr("Le marathon"), fmtDur(it.duration), tr("avec {0} · {1}", it.display, fmtDate(it.date)), keyOf(it.number))
    }
    people.filter { it.out + it.inc >= 6 }.maxByOrNull { it.out.toDouble() / (it.out + it.inc) }?.takeIf { it.out > it.inc }?.let {
        f += Fact("📣", tr("C'est toujours toi qui appelles"), it.display, tr("{0} % des appels viennent de toi ({1} vs {2})", pct(it.out, it.out + it.inc), it.out, it.inc), it.key)
    }
    people.filter { it.out + it.inc >= 6 }.maxByOrNull { it.inc.toDouble() / (it.out + it.inc) }?.takeIf { it.inc > it.out * 2 }?.let {
        f += Fact("🎣", tr("Elle/il t'appelle, tu rappelles jamais"), it.display, tr("{0} appels reçus pour {1} passés", it.inc, it.out), it.key)
    }
    people.filter { it.maxGap > 180 * DAY_MS }.maxByOrNull { it.maxGap }?.let {
        f += Fact("💌", tr("Le retour de flamme"), it.display, tr("Revenu·e après {0} de silence ({1})", agoTextSpan(it.maxGap), fmtDate(it.gapEnd)), it.key)
    }
    people.filter { it.n >= 3 }.maxByOrNull { it.last - it.first }?.let {
        f += Fact("🤝", tr("Fidèle au poste"), it.display, tr("Depuis {0} · {1} appels", fmtDate(it.first), it.n), it.key)
    }
    val night = calls.filter { cal(it.date).get(Calendar.HOUR_OF_DAY) in 0..4 }
    if (night.isNotEmpty()) {
        val top = night.groupBy { keyOf(it.number) }.maxByOrNull { it.value.size }!!.value
        f += Fact("🦉", tr("Oiseau de nuit"), trn(night.size, "{0} appel entre minuit et 5 h", "{0} appels entre minuit et 5 h"), tr("Complice principal : {0} ({1})", top.first().display, top.size), keyOf(top.first().number))
    }
    calls.filter { cal(it.date).get(Calendar.HOUR_OF_DAY) in 4..11 }.minByOrNull { val c = cal(it.date); c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE) }?.let {
        val c = cal(it.date)
        f += Fact("🐓", tr("Le plus matinal"), tr("{0} h {1}", c.get(Calendar.HOUR_OF_DAY), "%02d".format(c.get(Calendar.MINUTE))), tr("avec {0} · {1}", it.display, fmtDate(it.date)), keyOf(it.number))
    }
    val byDay = calls.groupBy { startOfDay(it.date) }
    byDay.maxByOrNull { it.value.size }?.let { (d, l) ->
        f += Fact("📅", tr("Jour record"), trn(l.size, "{0} appel", "{0} appels"), tr("{0} · surtout avec {1}", fmtDate(d), l.groupBy { keyOf(it.number) }.maxByOrNull { it.value.size }!!.value.first().display))
    }
    // plus longue série de jours consécutifs avec au moins un appel
    val days = byDay.keys.sorted()
    var best = 1; var cur = 1; var bestEnd = days.firstOrNull() ?: 0L
    for (i in 1 until days.size) { if (days[i] - days[i - 1] in (DAY_MS - 3_600_000)..(DAY_MS + 3_600_000)) cur++ else cur = 1; if (cur > best) { best = cur; bestEnd = days[i] } }
    if (days.isNotEmpty()) f += Fact("🔥", tr("Plus longue série"), trn(best, "{0} jour d'affilée", "{0} jours d'affilée"), tr("au téléphone chaque jour, jusqu'au {0}", fmtDate(bestEnd)))
    val unknown = calls.count { keyOf(it.number) !in contactKeys }
    val hidden = calls.count { it.number.none(Char::isDigit) }
    f += Fact("🕵️", tr("Inconnus"), trn(unknown, "{0} appel hors contacts", "{0} appels hors contacts"), if (hidden > 0) tr("dont {0} en numéro masqué", hidden) else tr("aucun numéro masqué"))
    val foreign = calls.filter { val c = countryOf(it.number); c != null && c.iso != "FR" }
    if (foreign.isNotEmpty()) {
        val byC = foreign.groupBy { countryOf(it.number)!! }
        val top = byC.maxByOrNull { it.value.size }!!
        f += Fact("🌍", tr("Globe-trotter"), trn(byC.size, "{0} pays étranger", "{0} pays étrangers"), tr("Surtout {0} {1} ({2})", top.key.flag, top.key.name, trn(top.value.size, "{0} appel", "{0} appels")))
    }
    val total = calls.sumOf { it.duration.toLong() }
    if (total > 0) f += Fact("🛋️", tr("Temps passé au téléphone"), fmtClock(total), tr("soit environ {0} films de 1 h 30", max(1, (total / 5400).toInt())))
    calls.groupBy { cal(it.date).get(Calendar.DAY_OF_WEEK) }.maxByOrNull { it.value.size }?.let { (d, l) ->
        val h = calls.groupBy { cal(it.date).get(Calendar.HOUR_OF_DAY) }.maxByOrNull { it.value.size }!!.key
        f += Fact("⏰", tr("Tes habitudes"), tr("Le {0}, vers {1} h", wdName(d), h), trn(l.size, "{0} appel un {1}", "{0} appels un {1}", wdName(d)))
    }
    return f
}

private fun agoTextSpan(ms: Long): String { val d = (ms / DAY_MS).toInt(); return if (d >= 365) trn(d / 365, "{0} an", "{0} ans") else tr("{0} mois", d / 30) }

/* ---------- Écran (version compacte, organisée) ---------- */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun StatsScreen(calls: List<CallRepository.CallEntry>, contacts: List<CallRepository.Contact>, onWrapped: () -> Unit = {}, onDetail: (String) -> Unit) {
    if (calls.isEmpty()) { Empty(tr("Pas de données : accorde l'accès au journal d'appels.")); return }
    val oldest = remember(calls) { calls.minOf { it.date } }
    var preset by remember { mutableStateOf(1) }
    val stack = remember { mutableStateListOf<Range>() }
    val range = stack.lastOrNull() ?: presetRange(preset, oldest)
    var habit by remember { mutableStateOf(0) }
    var rank by remember { mutableStateOf(0) }
    var origin by remember { mutableStateOf(0) }
    var allFacts by remember { mutableStateOf(false) }
    val filters = remember { mutableStateListOf<SFilter>() }
    fun toggle(f: SFilter) {
        val same = filters.firstOrNull { it.kind == f.kind }
        if (same != null) filters.remove(same)
        if (same?.id != f.id) filters.add(f)
    }
    fun active(kind: String) = filters.firstOrNull { it.kind == kind }?.id
    var detailAll by remember { mutableStateOf(false) }

    val fl = filters.toList()
    val inRange = remember(calls, range, fl) { calls.filter { c -> c.date in range.start..range.end && fl.all { it.test(c) } } }
    val lang = I18n.lang.value
    val bs = remember(range, lang) { buckets(range) }
    val perBucket = remember(inRange, bs) { countIn(inRange, bs) }
    // Calculs lourds en arrière-plan : l'écran ne fige jamais
    val aggs by produceState(emptyMap<String, Agg>(), inRange) { value = withContext(Dispatchers.Default) { aggregate(inRange) } }
    val allAggs by produceState(emptyMap<String, Agg>(), calls) { value = withContext(Dispatchers.Default) { aggregate(calls) } }
    val contactKeys = remember(contacts) { contacts.map { keyOf(it.number) }.toHashSet() }
    val fun_ by produceState(emptyList<Fact>(), inRange, contactKeys, lang) { value = withContext(Dispatchers.Default) { facts(inRange, aggregate(inRange), contactKeys) } }
    val origins by produceState(Triple(emptyList<Org>(), emptyList<Org>(), emptyList<Org>()), inRange, lang) {
        value = withContext(Dispatchers.Default) {
            val countries = inRange.groupBy { countryOf(it.number) }.map { (c, l) -> Org(c?.flag ?: "❔", c?.name ?: tr("Masqué / court"), l.size, c?.iso ?: "") }.sortedByDescending { it.n }
            val typeEmoji = mapOf("Mobile" to "📱", "Fixe" to "☎️", "Box / VoIP" to "🌐", "Numéro spécial" to "💶", "Court / service" to "🔢", "Étranger" to "✈️", "Masqué" to "🕵️")
            val types = inRange.groupBy { lineType(it.number) }.map { (t, l) -> Org(typeEmoji[t] ?: "❔", tr(t), l.size, t) }.sortedByDescending { it.n }
            val regEmoji = mapOf("Île-de-France" to "🗼", "Nord-Ouest" to "⚓", "Nord-Est" to "🏰", "Sud-Est" to "☀️", "Sud-Ouest" to "🍇", "Outre-mer" to "🏝️")
            val regions = inRange.mapNotNull { frRegion(it.number) }.groupingBy { it }.eachCount().map { (r, n) -> Org(regEmoji[r] ?: "📍", tr(r), n, r) }.sortedByDescending { it.n }
            Triple(countries, types, regions)
        }
    }
    val primary = MaterialTheme.colorScheme.primary

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        if (filters.isNotEmpty()) stickyHeader {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
                FlowRow(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    filters.forEach { f ->
                        InputChip(selected = true, onClick = { filters.remove(f) }, label = { Text(f.label, fontSize = 12.sp) },
                            trailingIcon = { Icon(Icons.Filled.Close, tr("Retirer"), Modifier.size(16.dp)) })
                    }
                    if (filters.size > 1) TextButton(onClick = { filters.clear() }) { Text(tr("Tout effacer"), fontSize = 12.sp) }
                }
            }
        }
        item {
            // 🎁 Bilan façon « Wrapped »
            Box(
                Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(16.dp))
                    .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color(0xFF7C3AED), Color(0xFFDB2777), Color(0xFFF59E0B))))
                    .clickable { onWrapped() }.padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🎁", fontSize = 24.sp); Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(tr("Ton Di-Allô Wrapped"), fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White)
                        Text(tr("Ton année au téléphone, en stories"), fontSize = 12.sp, color = Color.White.copy(alpha = .85f))
                    }
                    Text("›", fontSize = 26.sp, color = Color.White)
                }
            }
        }
        item {
            Segmented(PRESETS.map { tr(it) }, if (stack.isEmpty()) preset else -1, { preset = it; stack.clear() }, Modifier.padding(top = 2.dp, bottom = 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                if (stack.isNotEmpty()) IconButton(onClick = { stack.removeAt(stack.lastIndex) }, modifier = Modifier.size(28.dp)) { Icon(Icons.Filled.ArrowBack, tr("Retour"), tint = primary) }
                Text(range.label, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onBackground)
            }
        }
        // ---- 📊 Résumé : volume + sens des appels (téléphones fléchés) ----
        item {
            val answered = inRange.filter { it.duration > 0 }
            val totalDur = answered.sumOf { it.duration.toLong() }
            val avg = if (answered.isNotEmpty()) (totalDur / answered.size).toInt() else 0
            val out = inRange.count { it.type == CallLog.Calls.OUTGOING_TYPE }
            val inc = inRange.count { it.type == CallLog.Calls.INCOMING_TYPE }
            val miss = inRange.count { isMissed(it.type) }
            val days = max(1L, (minOf(range.end, System.currentTimeMillis()) - max(range.start, oldest)) / DAY_MS + 1)
            val tot = (out + inc + miss).coerceAtLeast(1).toFloat()
            SectionCard(tr("📊 Résumé"), id = "st_sum") {
                Row(Modifier.fillMaxWidth()) {
                    BigStat("📞", "${inRange.size}", trn(inRange.size, "appel", "appels"), Modifier.weight(1f))
                    BigStat("⏱️", fmtClock(totalDur), tr("au total"), Modifier.weight(1f))
                    BigStat("⌛", fmtDur(avg), tr("en moyenne"), Modifier.weight(1f))
                    BigStat("📆", String.format(I18n.locale, "%.1f", inRange.size.toDouble() / days), tr("par jour"), Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val dir = active("dir")
                    DirStat(Icons.Filled.PhoneForwarded, tr("Sortants"), out, (out * 100 / tot).toInt(), OutColor, Modifier.weight(1f), dir == "out") { toggle(SFilter("dir", "out", "↗️ " + tr("Sortants")) { it.type == CallLog.Calls.OUTGOING_TYPE }) }
                    DirStat(Icons.Filled.PhoneCallback, tr("Entrants"), inc, (inc * 100 / tot).toInt(), InColor, Modifier.weight(1f), dir == "in") { toggle(SFilter("dir", "in", "↙️ " + tr("Entrants")) { it.type == CallLog.Calls.INCOMING_TYPE }) }
                    DirStat(Icons.Filled.PhoneMissed, tr("Manqués"), miss, (miss * 100 / tot).toInt(), Red, Modifier.weight(1f), dir == "miss") { toggle(SFilter("dir", "miss", "❌ " + tr("Manqués")) { isMissed(it.type) }) }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp).height(6.dp).clip(RoundedCornerShape(50))) {
                    if (out > 0) Box(Modifier.weight(out / tot).fillMaxHeight().background(OutColor))
                    if (inc > 0) Box(Modifier.weight(inc / tot).fillMaxHeight().background(InColor))
                    if (miss > 0) Box(Modifier.weight(miss / tot).fillMaxHeight().background(Red))
                }
                Text(tr("✅ Tu réponds à {0} % des appels reçus · 👥 {1}", pct(inc, inc + miss), trn(aggs.size, "{0} correspondant", "{0} correspondants")), fontSize = 11.5.sp, color = muted(.65f), modifier = Modifier.padding(top = 6.dp))
            }
        }
        // ---- 📈 Activité (zoomable) ----
        item {
            val g = range.gran
            val sel = active("slot")
            SectionCard(tr("📈 Activité"), id = "st_act", action = { Text(tr("touche une barre"), fontSize = 10.5.sp, color = muted(.5f)) }) {
                BarChart(perBucket, bs.map { it.label }, primary, 96.dp, highlight = bs.indexOfFirst { it.start.toString() == sel }, onBar = { i ->
                    val b = bs[i]
                    drill(b, g)?.let { stack.add(it) } ?: toggle(SFilter("slot", b.start.toString(), "🕐 " + tr("{0} h", cal(b.start).get(Calendar.HOUR_OF_DAY))) { it.date >= b.start && it.date < b.end })
                })
            }
        }
        // ---- 🔎 Détail : les appels qui correspondent à ce que tu as touché ----
        if (filters.isNotEmpty() || range.gran == Gran.HOUR) item {
            val sorted = remember(inRange) { inRange.sortedByDescending { it.date } }
            val shown = if (detailAll) sorted else sorted.take(30)
            SectionCard(tr("🔎 Détail"), id = "st_det", collapsible = false, action = { Text(trn(sorted.size, "{0} appel", "{0} appels"), fontSize = 11.sp, color = muted(.6f)) }) {
                if (sorted.isEmpty()) Text(tr("Aucun appel ne correspond."), fontSize = 12.sp, color = muted(.5f))
                shown.forEach { c ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onDetail(keyOf(c.number).ifEmpty { c.number }) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.display, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(c.dirLabel + (if (c.duration > 0) " · " + fmtDur(c.duration) else ""), fontSize = 11.5.sp, color = if (isMissed(c.type)) Red else muted(.6f))
                        }
                        Text(fmtWhen(c.date), fontSize = 12.sp, color = muted(.6f))
                    }
                }
                if (sorted.size > 30) TextButton(onClick = { detailAll = !detailAll }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(if (detailAll) tr("Moins") else trn(sorted.size - 30, "Voir {0} autre", "Voir les {0} autres"))
                }
            }
        }
        // ---- 🌍 Origine des numéros ----
        item {
            val (countries, types, regions) = origins
            val list: List<Org> = when (origin) { 0 -> countries; 1 -> types; else -> regions }
            SectionCard(tr("🌍 Origine"), id = "st_org", action = { Segmented(listOf(tr("Pays"), tr("Type"), tr("Région")), origin, { origin = it }, Modifier.width(190.dp)) }) {
                if (origin == 0) countries.count { it.emoji != "❔" }.let { nc -> Text(trn(nc, "{0} pays différent", "{0} pays différents"), fontSize = 11.5.sp, color = muted(.6f), modifier = Modifier.padding(bottom = 4.dp)) }
                if (list.isEmpty()) Text(if (origin == 2) tr("Aucun fixe français sur la période.") else tr("Rien sur la période."), fontSize = 12.sp, color = muted(.5f))
                val m = (list.maxOfOrNull { it.n } ?: 1).coerceAtLeast(1)
                val kind = when (origin) { 0 -> "pays"; 1 -> "type"; else -> "reg" }
                val on = active(kind)
                list.take(if (on != null) 12 else 6).forEach { o ->
                    EmojiBar(o.emoji, o.name, o.n.toFloat() / m, "${o.n}", primary, selected = on == o.id) {
                        val test: (CallRepository.CallEntry) -> Boolean = when (kind) {
                            "pays" -> { c -> (countryOf(c.number)?.iso ?: "") == o.id }
                            "type" -> { c -> lineType(c.number) == o.id }
                            else -> { c -> frRegion(c.number) == o.id }
                        }
                        toggle(SFilter(kind, o.id, o.emoji + " " + o.name, test))
                    }
                }
            }
        }
        // ---- ⏰ Habitudes : heures OU jours ----
        item {
            val hours = IntArray(24); val wds = IntArray(7)
            inRange.forEach { val c = cal(it.date); hours[c.get(Calendar.HOUR_OF_DAY)]++; wds[(c.get(Calendar.DAY_OF_WEEK) + 5) % 7]++ }
            SectionCard(tr("⏰ Habitudes"), id = "st_hab", action = { Segmented(listOf(tr("Heures"), tr("Jours")), habit, { habit = it }, Modifier.width(140.dp)) }) {
                val dows = listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY)
                if (habit == 0) BarChart(hours.toList(), (0..23).map { if (it % 3 == 0) "${it}h" else "" }, primary.copy(alpha = .8f), 70.dp,
                    highlight = active("hour")?.toInt() ?: (hours.indices.maxByOrNull { hours[it] } ?: -1),
                    onBar = { h -> toggle(SFilter("hour", "$h", "⏰ " + tr("{0} h – {1} h", h, h + 1)) { cal(it.date).get(Calendar.HOUR_OF_DAY) == h }) })
                else BarChart(wds.toList(), dows.map { wdInitial(it) }, primary.copy(alpha = .8f), 70.dp,
                    highlight = active("wd")?.toInt() ?: (wds.indices.maxByOrNull { wds[it] } ?: -1),
                    onBar = { d -> toggle(SFilter("wd", "$d", "📅 " + wdName(dows[d]).replaceFirstChar { it.uppercase() }) { (cal(it.date).get(Calendar.DAY_OF_WEEK) + 5) % 7 == d }) })
                Text(tr("Touche une barre pour filtrer toute la page"), fontSize = 10.5.sp, color = muted(.5f), modifier = Modifier.padding(top = 4.dp))
            }
        }
        // ---- 🏆 Classements ----
        item {
            val people = aggs.values.filter { it.number.any(Char::isDigit) }
            val list: List<Triple<Agg, Float, String>> = when (rank) {
                0 -> { val l = people.sortedByDescending { it.n }.take(5); val m = (l.maxOfOrNull { it.n } ?: 1).coerceAtLeast(1); l.map { Triple(it, it.n.toFloat() / m, "${it.n}") } }
                1 -> { val l = people.sortedByDescending { it.dur }.take(5); val m = (l.maxOfOrNull { it.dur } ?: 1L).coerceAtLeast(1L); l.map { Triple(it, it.dur.toFloat() / m, fmtClock(it.dur)) } }
                2 -> people.filter { it.inc + it.miss >= 2 && it.miss > 0 }.sortedByDescending { it.miss.toDouble() / (it.inc + it.miss) * 100 + it.miss }.take(5).map { Triple(it, it.miss.toFloat() / (it.inc + it.miss), "${pct(it.miss, it.inc + it.miss)} %") }
                else -> allAggs.values.filter { it.n >= 5 && daysAgo(it.last) >= 60 && it.number.any(Char::isDigit) }.sortedByDescending { it.n }.take(5).map { Triple(it, (daysAgo(it.last) / 730f).coerceIn(0f, 1f), agoText(it.last)) }
            }
            val color = when (rank) { 2 -> Red; 3 -> Amber; else -> primary }
            SectionCard(tr("🏆 Classements"), id = "st_rank") {
                Segmented(listOf(tr("Top"), tr("Durée"), tr("Ghostés"), tr("Oubliés")), rank, { rank = it }, Modifier.padding(bottom = 6.dp))
                if (list.isEmpty()) Text(tr("Rien à signaler ici."), fontSize = 12.sp, color = muted(.5f), modifier = Modifier.padding(6.dp))
                list.forEach { (a, frac, right) -> MiniBar(a.display, a.photo, frac, right, color) { onDetail(a.key) } }
            }
        }
        // ---- 🎉 Faits marrants : grille 2 colonnes ----
        if (fun_.isNotEmpty()) item {
            val shown = if (allFacts) fun_ else fun_.take(6)
            SectionCard(tr("🎉 Faits marrants"), id = "st_fun") {
                shown.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { f -> FactTile(f, Modifier.weight(1f)) { f.key?.let(onDetail) } }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                if (fun_.size > 6) TextButton(onClick = { allFacts = !allFacts }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(if (allFacts) tr("Moins") else trn(fun_.size - 6, "Voir {0} autre", "Voir les {0} autres"))
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        // ---- 📚 Ce qui est compté : tout le journal, année par année ----
        item {
            val perYear = remember(calls) { calls.groupingBy { java.util.Calendar.getInstance().apply { timeInMillis = it.date }.get(java.util.Calendar.YEAR) }.eachCount().toSortedMap() }
            val maxN = perYear.values.maxOrNull() ?: 1
            val first = perYear.keys.firstOrNull()
            SectionCard(tr("📚 Données comptées"), id = "st_cover", defaultCollapsed = true) {
                Text(trn(calls.size, "{0} appel du journal, du {1} à aujourd'hui. Tous sont pris en compte.", "{0} appels du journal, du {1} à aujourd'hui. Tous sont pris en compte.", java.text.DateFormat.getDateInstance(java.text.DateFormat.LONG, I18n.locale).format(java.util.Date(oldest))), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(10.dp))
                perYear.forEach { (y, n) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("$y", fontSize = 12.sp, color = muted(.7f), modifier = Modifier.width(40.dp))
                        Box(Modifier.weight(1f).height(10.dp)) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth((n.toFloat() / maxN).coerceAtLeast(.01f)).clip(RoundedCornerShape(5.dp)).background(primary.copy(alpha = .75f)))
                        }
                        Text("$n", fontSize = 12.sp, color = muted(.7f), textAlign = TextAlign.End, modifier = Modifier.width(52.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(tr("Les années absentes ne sont pas dans le journal du téléphone{0}. Si tu as une sauvegarde plus ancienne (XML « SMS Backup & Restore »), importe-la dans Réglages › Sauvegardes : les appels manquants y sont ajoutés, puis comptés ici.", first?.let { tr(" (il commence en {0})", it.toString()) } ?: ""), fontSize = 12.sp, color = muted(.6f))
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

data class Org(val emoji: String, val name: String, val n: Int, val id: String)

internal val OutColor = Color(0xFF2FC45B)
internal val InColor = Color(0xFF3A7AFE)

@Composable
private fun BigStat(emoji: String, value: String, label: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(emoji, fontSize = 16.sp)
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, fontSize = 10.sp, color = muted(.55f), maxLines = 1)
    }
}

@Composable
private fun DirStat(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, n: Int, pc: Int, color: Color, modifier: Modifier, selected: Boolean = false, onClick: () -> Unit = {}) {
    Row(modifier.clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = if (selected) .32f else .12f)).then(if (selected) Modifier.border(1.5.dp, color, RoundedCornerShape(12.dp)) else Modifier).clickable { onClick() }.padding(horizontal = 8.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) { Icon(icon, label, tint = Color.White, modifier = Modifier.size(17.dp)) }
        Spacer(Modifier.width(7.dp))
        Column {
            Text("$n", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, maxLines = 1)
            Text("$label · $pc %", fontSize = 9.5.sp, color = muted(.65f), maxLines = 1)
        }
    }
}

@Composable
private fun EmojiBar(emoji: String, name: String, frac: Float, right: String, color: Color, selected: Boolean = false, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (selected) color.copy(alpha = .16f) else Color.Transparent)
        .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier).padding(vertical = 4.dp, horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(emoji, fontSize = 18.sp, modifier = Modifier.width(30.dp))
        Text(name, Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.width(80.dp).height(7.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(frac.coerceIn(0.04f, 1f)).clip(RoundedCornerShape(50)).background(color))
        }
        Text(right, fontSize = 12.sp, color = muted(), textAlign = TextAlign.End, modifier = Modifier.width(44.dp))
    }
}

/** Grille 3 colonnes de petits chiffres (bien plus compact que des tuiles). */
@Composable
private fun Grid3(items: List<Pair<String, String>>, accent: Map<Int, Color> = emptyMap()) {
    items.chunked(3).forEachIndexed { r, row ->
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            row.forEachIndexed { c, (k, v) ->
                Column(Modifier.weight(1f)) {
                    Text(v, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = accent[r * 3 + c] ?: MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(k, fontSize = 10.5.sp, color = muted(.55f), maxLines = 1)
                }
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun TileRow(items: List<Pair<String, String>>, accents: List<Color?> = emptyList()) {
    Grid3(items, accents.withIndex().mapNotNull { (i, c) -> c?.let { i to it } }.toMap())
}

@Composable
private fun Legend(c: Color, t: String) { Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(8.dp).clip(CircleShape).background(c)); Spacer(Modifier.width(4.dp)); Text(t, fontSize = 10.5.sp, color = muted(.65f)) } }

@Composable
private fun FactTile(f: Fact, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable(enabled = f.key != null) { onClick() }.padding(horizontal = 10.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(f.emoji, fontSize = 15.sp); Spacer(Modifier.width(5.dp))
            Text(f.title, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(f.value, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(f.sub, fontSize = 10.5.sp, color = muted(.6f), maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 13.sp)
    }
}

/* ---------- Fiche statistique d'un contact ---------- */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun ContactStatsSheet(key: String, calls: List<CallRepository.CallEntry>, onCall: (String) -> Unit, onDismiss: () -> Unit, onDeleted: () -> Unit = {}) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val gone = remember { mutableStateListOf<Long>() }          // supprimés : masqués tout de suite
    val mine = remember(key, calls, gone.size) { calls.filter { keyOf(it.number).ifEmpty { it.number } == key && it.id !in gone } }
    val sel = remember { mutableStateListOf<Long>() }
    var selecting by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }
    fun toggle(id: Long) { if (id < 0) return; if (id in sel) sel.remove(id) else sel.add(id) }
    if (askDelete) AlertDialog(
        onDismissRequest = { askDelete = false },
        title = { Text(trn(sel.size, "Supprimer {0} appel ?", "Supprimer {0} appels ?")) },
        text = { Text(tr("Ils seront effacés du journal du téléphone.")) },
        confirmButton = {
            Button(onClick = {
                askDelete = false
                val ids = sel.toList(); sel.clear(); selecting = false; gone.addAll(ids)
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { ids.count { CallRepository.deleteCall(ctx, it) } }
                    if (ok == 0) { gone.removeAll(ids); android.widget.Toast.makeText(ctx, tr("Impossible : Di-Allô doit être l'appli Téléphone par défaut"), android.widget.Toast.LENGTH_LONG).show() }
                    else { android.widget.Toast.makeText(ctx, trn(ok, "{0} appel supprimé", "{0} appels supprimés"), android.widget.Toast.LENGTH_SHORT).show(); onDeleted() }
                }
            }, colors = ButtonDefaults.buttonColors(containerColor = Red, contentColor = Color.White)) { Text(tr("Supprimer")) }
        },
        dismissButton = { TextButton(onClick = { askDelete = false }) { Text(tr("Annuler")) } }
    )
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = MaterialTheme.colorScheme.background) {
        if (mine.isEmpty()) { Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { Text(tr("Aucun appel avec ce contact."), color = muted()) }; return@ModalBottomSheet }
        val a = remember(mine) { aggregate(mine)[key] ?: aggregate(mine).values.first() }
        val primary = MaterialTheme.colorScheme.primary
        val stack = remember { mutableStateListOf<Range>() }
        val range = stack.lastOrNull() ?: presetRange(4, a.first)
        val bs = remember(range) { buckets(range) }
        val counts = remember(mine, bs) { countIn(mine, bs) }
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 14.dp)) {
                    Avatar(a.display, a.photo, 58.dp); Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.display, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(phoneText(a.number), fontSize = 13.sp, color = muted())
                    }
                    Box(Modifier.size(50.dp).clip(CircleShape).background(CallGreen).clickable { onCall(a.number) }, contentAlignment = Alignment.Center) { Icon(Icons.Filled.Call, tr("Appeler"), tint = Color.White) }
                }
                TileRow(listOf(tr("Appels") to a.n.toString(), tr("Durée totale") to fmtClock(a.dur), tr("Moyenne") to fmtDur(if (a.ans > 0) (a.dur / a.ans).toInt() else 0)))
                TileRow(listOf(tr("Sortants") to a.out.toString(), tr("Entrants") to a.inc.toString(), tr("Manqués") to a.miss.toString()), listOf(null, null, Red))
                TileRow(listOf(tr("Premier appel") to fmtDate(a.first), tr("Dernier") to agoText(a.last)))
                TileRow(listOf(tr("Réponds à") to "${pct(a.inc, a.inc + a.miss)} %", tr("Te répond") to "${pct(a.out - a.outNoAns, a.out)} %", tr("Plus long") to fmtDur(mine.maxOf { it.duration })))
                NotesCard(listOf(a.number), a.display)
                ContactRecordings(key)
                SectionCard(range.label, collapsible = false, action = { if (stack.isNotEmpty()) TextButton(onClick = { stack.removeAt(stack.lastIndex) }) { Text(tr("Retour")) } }) {
                    BarChart(counts, bs.map { it.label }, primary, 110.dp, onBar = { i -> drill(bs[i], range.gran)?.let { stack.add(it) } })
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (selecting) {
                        Text(trn(sel.size, "{0} sélectionné", "{0} sélectionnés"), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
                        TextButton(onClick = { val shown = mine.take(if (showAll) mine.size else 25).map { it.id }.filter { it >= 0 }; if (sel.containsAll(shown)) sel.clear() else { sel.clear(); sel.addAll(shown) } }) { Text(tr("Tout")) }
                        TextButton(onClick = { sel.clear(); selecting = false }) { Text(tr("Annuler")) }
                        Button(onClick = { if (sel.isNotEmpty()) askDelete = true }, enabled = sel.isNotEmpty(), colors = ButtonDefaults.buttonColors(containerColor = Red, contentColor = Color.White), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)) {
                            Icon(Icons.Filled.Delete, null, Modifier.size(16.dp))
                        }
                    } else {
                        Text(tr("Derniers appels"), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
                        TextButton(onClick = { selecting = true }) { Text("☑️ " + tr("Sélectionner")) }
                    }
                }
            }
            items(if (showAll) mine.size else mine.size.coerceAtMost(25)) { i ->
                val c = mine[i]
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .combinedClickable(onClick = { if (selecting) toggle(c.id) }, onLongClick = { selecting = true; toggle(c.id) })
                    .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (selecting) Checkbox(checked = c.id in sel, onCheckedChange = { toggle(c.id) }, modifier = Modifier.size(32.dp))
                    Text(c.dirLabel, fontSize = 13.sp, color = if (isMissed(c.type)) Red else MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
                    Text(if (c.duration > 0) fmtDur(c.duration) else "—", fontSize = 13.sp, color = muted(), modifier = Modifier.width(70.dp))
                    RecBadge(c.id, a.display)
                    Text(fmtWhen(c.date), fontSize = 13.sp, color = muted())
                }
            }
            if (!showAll && mine.size > 25) item {
                TextButton(onClick = { showAll = true }, modifier = Modifier.fillMaxWidth()) { Text(trn(mine.size, "Voir l'appel", "Voir les {0} appels")) }
            }
            item { Spacer(Modifier.height(30.dp)) }
        }
    }
}
