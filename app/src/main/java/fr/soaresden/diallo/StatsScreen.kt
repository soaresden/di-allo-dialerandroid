package fr.soaresden.diallo

import android.provider.CallLog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
enum class Gran { DAY, WEEK, MONTH, YEAR }
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
        0 -> Range(today - 6 * DAY_MS, now, "7 derniers jours", Gran.DAY)
        1 -> Range(today - 29 * DAY_MS, now, "30 derniers jours", Gran.DAY)
        2 -> Range(today - 12 * 7 * DAY_MS - 6 * DAY_MS, now, "3 derniers mois", Gran.WEEK)
        3 -> Range(cal(today).apply { set(Calendar.DAY_OF_MONTH, 1); add(Calendar.MONTH, -11) }.timeInMillis, now, "12 derniers mois", Gran.MONTH)
        else -> Range(cal(oldest).apply { set(Calendar.DAY_OF_YEAR, 1) }.let { startOfDay(it.timeInMillis) }, now, "Depuis le début", Gran.YEAR)
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
    val wd = listOf("D", "L", "M", "M", "J", "V", "S")
    var guard = 0
    while (c.timeInMillis <= r.end && guard++ < 400) {
        val s = c.timeInMillis
        val label = when (r.gran) {
            Gran.DAY -> if (r.end - r.start <= 8 * DAY_MS) wd[c.get(Calendar.DAY_OF_WEEK) - 1] else c.get(Calendar.DAY_OF_MONTH).toString()
            Gran.WEEK -> "${c.get(Calendar.DAY_OF_MONTH)}/${c.get(Calendar.MONTH) + 1}"
            Gran.MONTH -> monthName(c.get(Calendar.MONTH)).take(1).uppercase()
            Gran.YEAR -> "'" + (c.get(Calendar.YEAR) % 100).toString().padStart(2, '0')
        }
        when (r.gran) {
            Gran.DAY -> c.add(Calendar.DAY_OF_YEAR, 1); Gran.WEEK -> c.add(Calendar.DAY_OF_YEAR, 7)
            Gran.MONTH -> c.add(Calendar.MONTH, 1); Gran.YEAR -> c.add(Calendar.YEAR, 1)
        }
        out.add(Bucket(s, c.timeInMillis, label))
    }
    return out
}

/** Zoom sur une barre : année -> mois -> jours. */
internal fun drill(b: Bucket, g: Gran): Range? {
    val c = cal(b.start)
    return when (g) {
        Gran.YEAR -> Range(b.start, b.end - 1, "Année ${c.get(Calendar.YEAR)}", Gran.MONTH)
        Gran.MONTH -> Range(b.start, b.end - 1, "${monthName(c.get(Calendar.MONTH)).replaceFirstChar { it.uppercase() }} ${c.get(Calendar.YEAR)}", Gran.DAY)
        Gran.WEEK -> Range(b.start, b.end - 1, "Semaine du ${fmtDate(b.start)}", Gran.DAY)
        Gran.DAY -> null
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

    calls.minByOrNull { it.date }?.let { f += Fact("🦕", "Le plus vieil appel", it.display, "${fmtDate(it.date)} · il y a ${agoText(it.date)} · ${it.dirLabel.lowercase()}", keyOf(it.number)) }

    people.filter { it.inc + it.miss >= 3 }.maxWithOrNull(compareBy<Agg>({ it.miss.toDouble() / (it.inc + it.miss) }, { it.miss }))?.takeIf { it.miss > 0 }?.let {
        f += Fact("👻", "La personne la plus ghostée", it.display, "Tu as ignoré ${it.miss} de ses ${it.inc + it.miss} appels (${pct(it.miss, it.inc + it.miss)} %)", it.key)
    }
    people.filter { it.out >= 3 }.maxWithOrNull(compareBy<Agg>({ it.outNoAns.toDouble() / it.out }, { it.outNoAns }))?.takeIf { it.outNoAns > 0 }?.let {
        f += Fact("🙈", "Celle/celui qui te ghoste", it.display, "${it.outNoAns} appels sans réponse sur ${it.out} (${pct(it.outNoAns, it.out)} %)", it.key)
    }
    people.filter { it.ans >= 3 }.maxByOrNull { it.dur / it.ans }?.let {
        f += Fact("🗣️", "Le/la plus bavard·e", it.display, "${fmtDur((it.dur / it.ans).toInt())} en moyenne sur ${it.ans} appels", it.key)
    }
    calls.maxByOrNull { it.duration }?.takeIf { it.duration > 0 }?.let {
        f += Fact("⏱️", "Le marathon", fmtDur(it.duration), "avec ${it.display} · ${fmtDate(it.date)}", keyOf(it.number))
    }
    people.filter { it.out + it.inc >= 6 }.maxByOrNull { it.out.toDouble() / (it.out + it.inc) }?.takeIf { it.out > it.inc }?.let {
        f += Fact("📣", "C'est toujours toi qui appelles", it.display, "${pct(it.out, it.out + it.inc)} % des appels viennent de toi (${it.out} vs ${it.inc})", it.key)
    }
    people.filter { it.out + it.inc >= 6 }.maxByOrNull { it.inc.toDouble() / (it.out + it.inc) }?.takeIf { it.inc > it.out * 2 }?.let {
        f += Fact("🎣", "Elle/il t'appelle, tu rappelles jamais", it.display, "${it.inc} appels reçus pour ${it.out} passés", it.key)
    }
    people.filter { it.maxGap > 180 * DAY_MS }.maxByOrNull { it.maxGap }?.let {
        f += Fact("💌", "Le retour de flamme", it.display, "Revenu·e après ${agoTextSpan(it.maxGap)} de silence (${fmtDate(it.gapEnd)})", it.key)
    }
    people.filter { it.n >= 3 }.maxByOrNull { it.last - it.first }?.let {
        f += Fact("🤝", "Fidèle au poste", it.display, "Depuis ${fmtDate(it.first)} · ${it.n} appels", it.key)
    }
    val night = calls.filter { cal(it.date).get(Calendar.HOUR_OF_DAY) in 0..4 }
    if (night.isNotEmpty()) {
        val top = night.groupBy { keyOf(it.number) }.maxByOrNull { it.value.size }!!.value
        f += Fact("🦉", "Oiseau de nuit", "${night.size} appels entre minuit et 5 h", "Complice principal : ${top.first().display} (${top.size})", keyOf(top.first().number))
    }
    calls.filter { cal(it.date).get(Calendar.HOUR_OF_DAY) in 4..11 }.minByOrNull { val c = cal(it.date); c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE) }?.let {
        val c = cal(it.date)
        f += Fact("🐓", "Le plus matinal", "%d h %02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)), "avec ${it.display} · ${fmtDate(it.date)}", keyOf(it.number))
    }
    val byDay = calls.groupBy { startOfDay(it.date) }
    byDay.maxByOrNull { it.value.size }?.let { (d, l) ->
        f += Fact("📅", "Jour record", "${l.size} appels", fmtDate(d) + " · surtout avec " + l.groupBy { keyOf(it.number) }.maxByOrNull { it.value.size }!!.value.first().display)
    }
    // plus longue série de jours consécutifs avec au moins un appel
    val days = byDay.keys.sorted()
    var best = 1; var cur = 1; var bestEnd = days.firstOrNull() ?: 0L
    for (i in 1 until days.size) { if (days[i] - days[i - 1] in (DAY_MS - 3_600_000)..(DAY_MS + 3_600_000)) cur++ else cur = 1; if (cur > best) { best = cur; bestEnd = days[i] } }
    if (days.isNotEmpty()) f += Fact("🔥", "Plus longue série", "$best jours d'affilée", "au téléphone chaque jour, jusqu'au ${fmtDate(bestEnd)}")
    val unknown = calls.count { keyOf(it.number) !in contactKeys }
    val hidden = calls.count { it.number.none(Char::isDigit) }
    f += Fact("🕵️", "Inconnus", "$unknown appels hors contacts", if (hidden > 0) "dont $hidden en numéro masqué" else "aucun numéro masqué")
    val foreign = calls.filter { val c = countryOf(it.number); c != null && c.iso != "FR" }
    if (foreign.isNotEmpty()) {
        val byC = foreign.groupBy { countryOf(it.number)!! }
        val top = byC.maxByOrNull { it.value.size }!!
        f += Fact("🌍", "Globe-trotter", "${byC.size} pays étrangers", "Surtout ${top.key.flag} ${top.key.name} (${top.value.size} appels)")
    }
    val total = calls.sumOf { it.duration.toLong() }
    if (total > 0) f += Fact("🛋️", "Temps passé au téléphone", fmtClock(total), "soit environ ${max(1, (total / 5400).toInt())} films de 1 h 30")
    val wdNames = listOf("dimanche", "lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi")
    calls.groupBy { cal(it.date).get(Calendar.DAY_OF_WEEK) }.maxByOrNull { it.value.size }?.let { (d, l) ->
        val h = calls.groupBy { cal(it.date).get(Calendar.HOUR_OF_DAY) }.maxByOrNull { it.value.size }!!.key
        f += Fact("⏰", "Tes habitudes", "Le ${wdNames[d - 1]}, vers ${h} h", "${l.size} appels un ${wdNames[d - 1]}")
    }
    return f
}

private fun agoTextSpan(ms: Long): String { val d = (ms / DAY_MS).toInt(); return if (d >= 365) "${d / 365} an${if (d / 365 > 1) "s" else ""}" else "${d / 30} mois" }

/* ---------- Écran (version compacte, organisée) ---------- */
@Composable
internal fun StatsScreen(calls: List<CallRepository.CallEntry>, contacts: List<CallRepository.Contact>, onWrapped: () -> Unit = {}, onDetail: (String) -> Unit) {
    if (calls.isEmpty()) { Empty("Pas de données : accorde l'accès au journal d'appels."); return }
    val oldest = remember(calls) { calls.minOf { it.date } }
    var preset by remember { mutableStateOf(1) }
    val stack = remember { mutableStateListOf<Range>() }
    val range = stack.lastOrNull() ?: presetRange(preset, oldest)
    var habit by remember { mutableStateOf(0) }
    var rank by remember { mutableStateOf(0) }
    var origin by remember { mutableStateOf(0) }
    var allFacts by remember { mutableStateOf(false) }

    val inRange = remember(calls, range) { calls.filter { it.date in range.start..range.end } }
    val bs = remember(range) { buckets(range) }
    val perBucket = remember(inRange, bs) { countIn(inRange, bs) }
    // Calculs lourds en arrière-plan : l'écran ne fige jamais
    val aggs by produceState(emptyMap<String, Agg>(), inRange) { value = withContext(Dispatchers.Default) { aggregate(inRange) } }
    val allAggs by produceState(emptyMap<String, Agg>(), calls) { value = withContext(Dispatchers.Default) { aggregate(calls) } }
    val contactKeys = remember(contacts) { contacts.map { keyOf(it.number) }.toHashSet() }
    val fun_ by produceState(emptyList<Fact>(), inRange, contactKeys) { value = withContext(Dispatchers.Default) { facts(inRange, aggregate(inRange), contactKeys) } }
    val origins by produceState(Triple(emptyList<Triple<String, String, Int>>(), emptyList<Triple<String, String, Int>>(), emptyList<Triple<String, String, Int>>()), inRange) {
        value = withContext(Dispatchers.Default) {
            val countries = inRange.groupBy { countryOf(it.number) }.map { (c, l) -> Triple(c?.flag ?: "❔", c?.name ?: "Masqué / court", l.size) }.sortedByDescending { it.third }
            val typeEmoji = mapOf("Mobile" to "📱", "Fixe" to "☎️", "Box / VoIP" to "🌐", "Numéro spécial" to "💶", "Court / service" to "🔢", "Étranger" to "✈️", "Masqué" to "🕵️")
            val types = inRange.groupBy { lineType(it.number) }.map { (t, l) -> Triple(typeEmoji[t] ?: "❔", t, l.size) }.sortedByDescending { it.third }
            val regEmoji = mapOf("Île-de-France" to "🗼", "Nord-Ouest" to "⚓", "Nord-Est" to "🏰", "Sud-Est" to "☀️", "Sud-Ouest" to "🍇", "Outre-mer" to "🏝️")
            val regions = inRange.mapNotNull { frRegion(it.number) }.groupingBy { it }.eachCount().map { (r, n) -> Triple(regEmoji[r] ?: "📍", r, n) }.sortedByDescending { it.third }
            Triple(countries, types, regions)
        }
    }
    val primary = MaterialTheme.colorScheme.primary

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
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
                        Text("Ton Di-Allô Wrapped", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White)
                        Text("Ton année au téléphone, en stories", fontSize = 12.sp, color = Color.White.copy(alpha = .85f))
                    }
                    Text("›", fontSize = 26.sp, color = Color.White)
                }
            }
        }
        item {
            Segmented(PRESETS, if (stack.isEmpty()) preset else -1, { preset = it; stack.clear() }, Modifier.padding(top = 2.dp, bottom = 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                if (stack.isNotEmpty()) IconButton(onClick = { stack.removeAt(stack.lastIndex) }, modifier = Modifier.size(28.dp)) { Icon(Icons.Filled.ArrowBack, "Retour", tint = primary) }
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
            SectionCard("📊 Résumé", id = "st_sum") {
                Row(Modifier.fillMaxWidth()) {
                    BigStat("📞", "${inRange.size}", "appels", Modifier.weight(1f))
                    BigStat("⏱️", fmtClock(totalDur), "au total", Modifier.weight(1f))
                    BigStat("⌛", fmtDur(avg), "en moyenne", Modifier.weight(1f))
                    BigStat("📆", "%.1f".format(inRange.size.toDouble() / days), "par jour", Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DirStat(Icons.Filled.PhoneForwarded, "Sortants", out, (out * 100 / tot).toInt(), OutColor, Modifier.weight(1f))
                    DirStat(Icons.Filled.PhoneCallback, "Entrants", inc, (inc * 100 / tot).toInt(), InColor, Modifier.weight(1f))
                    DirStat(Icons.Filled.PhoneMissed, "Manqués", miss, (miss * 100 / tot).toInt(), Red, Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp).height(6.dp).clip(RoundedCornerShape(50))) {
                    if (out > 0) Box(Modifier.weight(out / tot).fillMaxHeight().background(OutColor))
                    if (inc > 0) Box(Modifier.weight(inc / tot).fillMaxHeight().background(InColor))
                    if (miss > 0) Box(Modifier.weight(miss / tot).fillMaxHeight().background(Red))
                }
                Text("✅ Tu réponds à ${pct(inc, inc + miss)} % des appels reçus · 👥 ${aggs.size} correspondants", fontSize = 11.5.sp, color = muted(.65f), modifier = Modifier.padding(top = 6.dp))
            }
        }
        // ---- 📈 Activité (zoomable) ----
        item {
            val g = range.gran
            SectionCard("📈 Activité", id = "st_act", action = { if (g != Gran.DAY) Text("touche une barre", fontSize = 10.5.sp, color = muted(.5f)) }) {
                BarChart(perBucket, bs.map { it.label }, primary, 96.dp, onBar = { i -> drill(bs[i], g)?.let { stack.add(it) } })
            }
        }
        // ---- 🌍 Origine des numéros ----
        item {
            val (countries, types, regions) = origins
            val list = when (origin) { 0 -> countries; 1 -> types; else -> regions }
            SectionCard("🌍 Origine", id = "st_org", action = { Segmented(listOf("Pays", "Type", "Région"), origin, { origin = it }, Modifier.width(190.dp)) }) {
                if (origin == 0) Text("${countries.count { it.first != "❔" }} pays différents", fontSize = 11.5.sp, color = muted(.6f), modifier = Modifier.padding(bottom = 4.dp))
                if (list.isEmpty()) Text(if (origin == 2) "Aucun fixe français sur la période." else "Rien sur la période.", fontSize = 12.sp, color = muted(.5f))
                val m = (list.maxOfOrNull { it.third } ?: 1).coerceAtLeast(1)
                list.take(6).forEach { (e, name, n) -> EmojiBar(e, name, n.toFloat() / m, "$n", primary) }
            }
        }
        // ---- ⏰ Habitudes : heures OU jours ----
        item {
            val hours = IntArray(24); val wds = IntArray(7)
            inRange.forEach { val c = cal(it.date); hours[c.get(Calendar.HOUR_OF_DAY)]++; wds[(c.get(Calendar.DAY_OF_WEEK) + 5) % 7]++ }
            SectionCard("⏰ Habitudes", id = "st_hab", action = { Segmented(listOf("Heures", "Jours"), habit, { habit = it }, Modifier.width(140.dp)) }) {
                if (habit == 0) BarChart(hours.toList(), (0..23).map { if (it % 3 == 0) "${it}h" else "" }, primary.copy(alpha = .8f), 70.dp, highlight = hours.indices.maxByOrNull { hours[it] } ?: -1)
                else BarChart(wds.toList(), listOf("L", "M", "M", "J", "V", "S", "D"), primary.copy(alpha = .8f), 70.dp, highlight = wds.indices.maxByOrNull { wds[it] } ?: -1)
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
            SectionCard("🏆 Classements", id = "st_rank") {
                Segmented(listOf("Top", "Durée", "Ghostés", "Oubliés"), rank, { rank = it }, Modifier.padding(bottom = 6.dp))
                if (list.isEmpty()) Text("Rien à signaler ici.", fontSize = 12.sp, color = muted(.5f), modifier = Modifier.padding(6.dp))
                list.forEach { (a, frac, right) -> MiniBar(a.display, a.photo, frac, right, color) { onDetail(a.key) } }
            }
        }
        // ---- 🎉 Faits marrants : grille 2 colonnes ----
        if (fun_.isNotEmpty()) item {
            val shown = if (allFacts) fun_ else fun_.take(6)
            SectionCard("🎉 Faits marrants", id = "st_fun") {
                shown.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { f -> FactTile(f, Modifier.weight(1f)) { f.key?.let(onDetail) } }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                if (fun_.size > 6) TextButton(onClick = { allFacts = !allFacts }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(if (allFacts) "Moins" else "Voir les ${fun_.size - 6} autres")
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        // ---- 📚 Ce qui est compté : tout le journal, année par année ----
        item {
            val perYear = remember(calls) { calls.groupingBy { java.util.Calendar.getInstance().apply { timeInMillis = it.date }.get(java.util.Calendar.YEAR) }.eachCount().toSortedMap() }
            val maxN = perYear.values.maxOrNull() ?: 1
            val first = perYear.keys.firstOrNull()
            SectionCard("📚 Données comptées", id = "st_cover", defaultCollapsed = true) {
                Text("${calls.size} appels du journal, du ${java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale.FRANCE).format(java.util.Date(oldest))} à aujourd'hui. Tous sont pris en compte.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
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
                Text("Les années absentes ne sont pas dans le journal du téléphone${first?.let { " (il commence en $it)" } ?: ""}. Si tu as une sauvegarde plus ancienne (XML « SMS Backup & Restore »), importe-la dans Réglages › Sauvegardes : les appels manquants y sont ajoutés, puis comptés ici.", fontSize = 12.sp, color = muted(.6f))
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

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
private fun DirStat(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, n: Int, pc: Int, color: Color, modifier: Modifier) {
    Row(modifier.clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = .12f)).padding(horizontal = 8.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) { Icon(icon, label, tint = Color.White, modifier = Modifier.size(17.dp)) }
        Spacer(Modifier.width(7.dp))
        Column {
            Text("$n", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, maxLines = 1)
            Text("$label · $pc %", fontSize = 9.5.sp, color = muted(.65f), maxLines = 1)
        }
    }
}

@Composable
private fun EmojiBar(emoji: String, name: String, frac: Float, right: String, color: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContactStatsSheet(key: String, calls: List<CallRepository.CallEntry>, onCall: (String) -> Unit, onDismiss: () -> Unit) {
    val mine = remember(key, calls) { calls.filter { keyOf(it.number).ifEmpty { it.number } == key } }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = MaterialTheme.colorScheme.background) {
        if (mine.isEmpty()) { Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { Text("Aucun appel avec ce contact.", color = muted()) }; return@ModalBottomSheet }
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
                    Box(Modifier.size(50.dp).clip(CircleShape).background(CallGreen).clickable { onCall(a.number) }, contentAlignment = Alignment.Center) { Icon(Icons.Filled.Call, "Appeler", tint = Color.White) }
                }
                TileRow(listOf("Appels" to a.n.toString(), "Durée totale" to fmtClock(a.dur), "Moyenne" to fmtDur(if (a.ans > 0) (a.dur / a.ans).toInt() else 0)))
                TileRow(listOf("Sortants" to a.out.toString(), "Entrants" to a.inc.toString(), "Manqués" to a.miss.toString()), listOf(null, null, Red))
                TileRow(listOf("Premier appel" to fmtDate(a.first), "Dernier" to agoText(a.last)))
                TileRow(listOf("Réponds à" to "${pct(a.inc, a.inc + a.miss)} %", "Te répond" to "${pct(a.out - a.outNoAns, a.out)} %", "Plus long" to fmtDur(mine.maxOf { it.duration })))
                NotesCard(listOf(a.number), a.display)
                ContactRecordings(key)
                SectionCard(range.label, collapsible = false, action = { if (stack.isNotEmpty()) TextButton(onClick = { stack.removeAt(stack.lastIndex) }) { Text("Retour") } }) {
                    BarChart(counts, bs.map { it.label }, primary, 110.dp, onBar = { i -> drill(bs[i], range.gran)?.let { stack.add(it) } })
                }
                Text("Derniers appels", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(vertical = 6.dp))
            }
            items(mine.size.coerceAtMost(25)) { i ->
                val c = mine[i]
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(c.dirLabel, fontSize = 13.sp, color = if (isMissed(c.type)) Red else MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
                    Text(if (c.duration > 0) fmtDur(c.duration) else "—", fontSize = 13.sp, color = muted(), modifier = Modifier.width(70.dp))
                    RecBadge(c.id, a.display)
                    Text(fmtWhen(c.date), fontSize = 13.sp, color = muted())
                }
            }
            item { Spacer(Modifier.height(30.dp)) }
        }
    }
}
