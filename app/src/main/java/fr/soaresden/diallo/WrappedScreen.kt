package fr.soaresden.diallo

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.provider.CallLog
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import kotlin.math.roundToInt

/* ======================= Calculs ======================= */

private data class WPerson(val key: String, val number: String, val name: String, val photo: String?, val n: Int, val dur: Long, val missed: Int)

private class Wrap(
    val year: Int, val total: Int, val out: Int, val inc: Int, val missed: Int, val dur: Long, val answered: Int,
    val top: List<WPerson>, val longest: CallRepository.CallEntry?, val longestName: String,
    val months: IntArray, val hours: IntArray, val recordDay: Long, val recordN: Int, val streak: Int,
    val people: Int, val newPeople: Int, val ghost: WPerson?, val countries: Int, val prevTotal: Int
)

private fun yearStart(y: Int) = Calendar.getInstance().apply { clear(); set(y, 0, 1) }.timeInMillis

private fun computeWrap(all: List<CallRepository.CallEntry>, contacts: List<CallRepository.Contact>, year: Int): Wrap {
    val from = yearStart(year); val to = yearStart(year + 1)
    val calls = all.filter { it.date in from until to }
    val byKey = contacts.associateBy { keyOf(it.number) }
    fun k(c: CallRepository.CallEntry) = keyOf(c.number).ifEmpty { c.number }
    val groups = calls.filter { it.number.any(Char::isDigit) }.groupBy { k(it) }
    val people = groups.map { (key, l) ->
        val c0 = l.first(); val ct = byKey[key]
        WPerson(key, c0.number, ct?.display ?: c0.name ?: displayNumber(c0.number), ct?.photoUri ?: c0.photoUri, l.size, l.sumOf { it.duration.toLong() },
            l.count { it.type == CallLog.Calls.MISSED_TYPE || it.type == CallLog.Calls.REJECTED_TYPE })
    }
    val top = people.sortedWith(compareByDescending<WPerson> { it.n }.thenByDescending { it.dur }).take(5)
    val cal = Calendar.getInstance()
    val months = IntArray(12); val hours = IntArray(24)
    val days = HashMap<Long, Int>()
    for (c in calls) {
        cal.timeInMillis = c.date
        months[cal.get(Calendar.MONTH)]++; hours[cal.get(Calendar.HOUR_OF_DAY)]++
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        days.merge(cal.timeInMillis, 1, Int::plus)
    }
    val rec = days.maxByOrNull { it.value }
    // plus longue série de jours consécutifs avec au moins un appel
    var streak = 0; var cur = 0; var prev = 0L
    for (d in days.keys.sorted()) { cur = if (prev != 0L && d - prev in 82_800_000L..90_000_000L) cur + 1 else 1; streak = maxOf(streak, cur); prev = d }
    val before = all.filter { it.date < from }.map { k(it) }.toHashSet()
    val longest = calls.maxByOrNull { it.duration }?.takeIf { it.duration > 0 }
    val prevTotal = all.count { it.date in yearStart(year - 1) until from }
    return Wrap(
        year, calls.size, calls.count { it.type == CallLog.Calls.OUTGOING_TYPE }, calls.count { it.type == CallLog.Calls.INCOMING_TYPE },
        calls.count { it.type == CallLog.Calls.MISSED_TYPE || it.type == CallLog.Calls.REJECTED_TYPE }, calls.sumOf { it.duration.toLong() }, calls.count { it.duration > 0 },
        top, longest, longest?.let { l -> byKey[keyOf(l.number)]?.display ?: l.name ?: displayNumber(l.number) } ?: "",
        months, hours, rec?.key ?: 0L, rec?.value ?: 0, streak, people.size, people.count { it.key !in before },
        people.filter { it.missed > 0 }.maxByOrNull { it.missed }, calls.mapNotNull { countryOf(it.number)?.iso }.distinct().size, prevTotal
    )
}

private fun persona(h: Int) = when (h) {
    in 0..5 -> "oiseau de nuit 🦉"; in 6..8 -> "lève-tôt 🐓"; in 9..11 -> "du matin ☕"; in 12..13 -> "pause déj 🥪"
    in 14..17 -> "de l'aprèm 🌤️"; in 18..20 -> "du soir 🌆"; else -> "oiseau de nuit 🦉"
}
private fun hm(s: Long) = "${s / 3600} h ${((s % 3600) / 60).toString().padStart(2, '0')}"

/* ======================= Écran ======================= */

private val GRADS = listOf(
    listOf(Color(0xFF7C3AED), Color(0xFFDB2777)), listOf(Color(0xFF0EA5E9), Color(0xFF6366F1)), listOf(Color(0xFFF59E0B), Color(0xFFEF4444)),
    listOf(Color(0xFF10B981), Color(0xFF0D9488)), listOf(Color(0xFFEC4899), Color(0xFF8B5CF6)), listOf(Color(0xFF22C55E), Color(0xFF0891B2)),
    listOf(Color(0xFFF97316), Color(0xFFDB2777)), listOf(Color(0xFF1E3A8A), Color(0xFF7C3AED)), listOf(Color(0xFF14B8A6), Color(0xFF3B82F6)),
    listOf(Color(0xFF64748B), Color(0xFF0F172A)), listOf(Color(0xFFE11D48), Color(0xFFF59E0B)), listOf(Color(0xFF16B871), Color(0xFF0A7F6B))
)

@OptIn(ExperimentalAnimationApi::class)
@Composable
internal fun WrappedScreen(calls: List<CallRepository.CallEntry>, contacts: List<CallRepository.Contact>, onClose: () -> Unit) {
    val ctx = LocalContext.current
    BackHandler { onClose() }
    val years = remember(calls) { calls.map { Calendar.getInstance().apply { timeInMillis = it.date }.get(Calendar.YEAR) }.distinct().sortedDescending().take(6) }
    var year by remember { mutableIntStateOf(years.firstOrNull() ?: Calendar.getInstance().get(Calendar.YEAR)) }
    val w by produceState<Wrap?>(null, year, calls) { value = null; value = withContext(Dispatchers.Default) { computeWrap(calls, contacts, year) } }
    var i by remember { mutableIntStateOf(0) }
    val slides = 12
    val prog = remember { Animatable(0f) }
    LaunchedEffect(i, w) {
        prog.snapTo(0f)
        if (w == null || i == 0 || i == slides - 1) return@LaunchedEffect
        prog.animateTo(1f, tween(7500, easing = LinearEasing))
        if (i < slides - 1) i++
    }
    val g = GRADS[i % GRADS.size]
    Box(
        Modifier.fillMaxSize().background(Brush.linearGradient(g))
            .pointerInput(Unit) { detectTapGestures { o -> if (o.x < size.width / 3f) { if (i > 0) i-- } else if (i < slides - 1) i++ } }
    ) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
            // barres de progression façon stories
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                repeat(slides) { k ->
                    val f = when { k < i -> 1f; k == i -> prog.value; else -> 0f }
                    Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = .3f))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(f).background(Color.White))
                    }
                }
                Spacer(Modifier.width(6.dp))
                IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Close, "Fermer", tint = Color.White) }
            }
            val wr = w
            if (wr == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }
            else AnimatedContent(i, transitionSpec = { (fadeIn(tween(350)) + scaleIn(tween(350), initialScale = .92f)) togetherWith fadeOut(tween(180)) }, label = "slide", modifier = Modifier.fillMaxSize()) { s ->
                Column(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Slide(s, wr, years, year, { year = it; i = 0 }, onStart = { i = 1 }, ctx)
                }
            }
        }
    }
}

@Composable
private fun Big(t: String, size: TextUnit = 64.sp) = Text(t, color = Color.White, fontSize = size, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, lineHeight = size * 1.05f)
@Composable
private fun Line(t: String, size: TextUnit = 20.sp, alpha: Float = 1f, bold: Boolean = false) =
    Text(t, color = Color.White.copy(alpha = alpha), fontSize = size, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium, textAlign = TextAlign.Center, lineHeight = size * 1.25f)
@Composable
private fun Emoji(e: String) = Text(e, fontSize = 64.sp, modifier = Modifier.padding(bottom = 10.dp))

@Composable
private fun CountUp(target: Long, suffix: String = "", size: TextUnit = 84.sp) {
    val a = remember(target) { Animatable(0f) }
    LaunchedEffect(target) { a.animateTo(target.toFloat(), tween(1400, easing = FastOutSlowInEasing)) }
    Big("%,d".format(a.value.roundToInt()).replace(',', ' ') + suffix, size)
}

@Composable
private fun Slide(s: Int, w: Wrap, years: List<Int>, year: Int, onYear: (Int) -> Unit, onStart: () -> Unit, ctx: Context) {
    val gap = Modifier.height(14.dp)
    if (w.total == 0 && s != 0) { Emoji("🤷"); Line("Pas d'appels en ${w.year}."); return }
    when (s) {
        0 -> {
            Emoji("🎁"); Line("Di-Allô Wrapped", 22.sp, .85f, true); Spacer(gap)
            Big("Ton année ${w.year}\nau téléphone", 44.sp); Spacer(Modifier.height(28.dp))
            if (years.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                years.take(5).forEach { y ->
                    Text(
                        "$y", color = if (y == year) Color(0xFF7C3AED) else Color.White, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (y == year) Color.White else Color.White.copy(alpha = .18f)).clickable { onYear(y) }.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
            Spacer(Modifier.height(36.dp))
            Text("C'est parti ›", color = Color(0xFF7C3AED), fontWeight = FontWeight.Bold, fontSize = 18.sp,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White).clickable { onStart() }.padding(horizontal = 26.dp, vertical = 12.dp))
            Spacer(gap); Line("Touche à droite pour avancer, à gauche pour revenir", 13.sp, .7f)
        }
        1 -> {
            Emoji("📞"); Line("Cette année, tu as passé ou reçu"); Spacer(gap)
            CountUp(w.total.toLong()); Line("appels", 28.sp, bold = true); Spacer(Modifier.height(20.dp))
            Line("📤 ${w.out} passés   📥 ${w.inc} reçus   👻 ${w.missed} manqués", 15.sp, .9f)
            if (w.prevTotal > 0) {
                val d = (w.total - w.prevTotal) * 100 / w.prevTotal
                Spacer(gap); Line(if (d >= 0) "📈 +$d % par rapport à ${w.year - 1}" else "📉 $d % par rapport à ${w.year - 1}", 17.sp, .9f, true)
            }
        }
        2 -> {
            Emoji("⏱️"); Line("Temps passé au téléphone"); Spacer(gap)
            Big(hm(w.dur), 72.sp); Spacer(Modifier.height(20.dp))
            val films = w.dur / 7200
            Line(if (films >= 1) "Soit $films film${if (films > 1) "s" else ""} 🎬 en entier" else "Soit ${w.dur / 60} minutes de blabla", 19.sp, .95f, true)
            if (w.answered > 0) { Spacer(gap); Line("En moyenne ${fmtDur((w.dur / w.answered).toInt())} par appel", 16.sp, .8f) }
        }
        3 -> w.top.firstOrNull()?.let { p ->
            Line("Ta personne n°1 👑", 22.sp, bold = true); Spacer(Modifier.height(20.dp))
            Box(Modifier.size(150.dp).clip(CircleShape).background(Color.White.copy(alpha = .25f)).padding(6.dp)) { Avatar(p.name, p.photo, 138.dp) }
            Spacer(Modifier.height(18.dp)); Big(p.name, 36.sp); Spacer(gap)
            Line("${p.n} appels · ${fmtClock(p.dur)} ensemble", 18.sp, .9f)
        } ?: run { Emoji("🫥"); Line("Personne n'a vraiment sorti du lot.") }
        4 -> {
            Line("Ton top 5 🏆", 26.sp, bold = true); Spacer(Modifier.height(22.dp))
            val medals = listOf("🥇", "🥈", "🥉", "4", "5")
            w.top.forEachIndexed { k, p ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = .14f)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(medals[k], fontSize = 24.sp, color = Color.White, fontWeight = FontWeight.Black, modifier = Modifier.width(38.dp), textAlign = TextAlign.Center)
                    Avatar(p.name, p.photo, 40.dp); Spacer(Modifier.width(10.dp))
                    Text(p.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${p.n}", color = Color.White, fontWeight = FontWeight.Black, fontSize = 20.sp)
                }
            }
        }
        5 -> {
            val m = w.months.indices.maxByOrNull { w.months[it] } ?: 0
            Emoji("📅"); Line("Ton mois le plus bavard"); Spacer(gap)
            Big(monthName(m).replaceFirstChar { it.uppercase() }.trimEnd('.'), 60.sp); Line("${w.months[m]} appels", 20.sp, .9f, true)
            Spacer(Modifier.height(26.dp)); Bars(w.months.toList(), listOf("J", "F", "M", "A", "M", "J", "J", "A", "S", "O", "N", "D"), m)
        }
        6 -> {
            Emoji("🔥"); Line("Ton jour record"); Spacer(gap)
            Big(if (w.recordDay > 0) fmtDate(w.recordDay) else "—", 40.sp); Spacer(gap)
            Line("${w.recordN} appels dans la journée !", 20.sp, bold = true)
            if (w.streak > 1) { Spacer(Modifier.height(26.dp)); Line("📆 Plus longue série : ${w.streak} jours d'affilée au téléphone", 17.sp, .9f) }
        }
        7 -> {
            val h = w.hours.indices.maxByOrNull { w.hours[it] } ?: 12
            Emoji("🕰️"); Line("Ton heure préférée"); Spacer(gap)
            Big("${h}h", 90.sp); Line("Tu es plutôt ${persona(h)}", 22.sp, bold = true)
            Spacer(Modifier.height(26.dp)); Bars(w.hours.toList(), (0..23).map { if (it % 6 == 0) "$it" else "" }, h)
        }
        8 -> w.longest?.let { l ->
            Emoji("🏆"); Line("Ton plus long appel"); Spacer(gap)
            Big(fmtDur(l.duration), 72.sp); Spacer(gap)
            Line("avec ${w.longestName}", 24.sp, bold = true); Line("le ${fmtDate(l.date)}", 17.sp, .85f)
        } ?: run { Emoji("⚡"); Line("Que des appels express cette année !") }
        9 -> {
            Emoji("👻"); Line("Appels manqués"); Spacer(gap)
            CountUp(w.missed.toLong(), size = 80.sp); Spacer(gap)
            w.ghost?.let { g -> Line("C'est ${g.name} qui t'a le plus attendu", 20.sp, bold = true); Line("${g.missed} appel${if (g.missed > 1) "s" else ""} sans réponse 😬", 17.sp, .85f) }
                ?: Line("Tu réponds toujours. Respect. 🫡", 20.sp, bold = true)
        }
        10 -> {
            Emoji("🫂"); Line("Tu as parlé avec"); Spacer(gap)
            CountUp(w.people.toLong()); Line("personnes différentes", 24.sp, bold = true); Spacer(Modifier.height(20.dp))
            if (w.newPeople > 0) Line("🆕 dont ${w.newPeople} nouvelle${if (w.newPeople > 1) "s" else ""} cette année", 18.sp, .9f)
            if (w.countries > 1) { Spacer(gap); Line("🌍 ${w.countries} pays au compteur", 18.sp, .9f) }
        }
        else -> Outro(w, ctx)
    }
}

@Composable
private fun Bars(values: List<Int>, labels: List<String>, hi: Int) {
    val max = (values.maxOrNull() ?: 1).coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().height(130.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
        values.forEachIndexed { k, v ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                Box(Modifier.fillMaxWidth().height((v * 108f / max).dp.coerceAtLeast(3.dp)).clip(RoundedCornerShape(4.dp)).background(if (k == hi) Color.White else Color.White.copy(alpha = .35f)))
                Text(labels[k], fontSize = 10.sp, color = Color.White.copy(alpha = .8f), maxLines = 1)
            }
        }
    }
}

@Composable
private fun Outro(w: Wrap, ctx: Context) {
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf<String?>(null) }
    Emoji("🤙"); Big("Ton ${w.year}\nen résumé", 40.sp); Spacer(Modifier.height(22.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color.White.copy(alpha = .16f)).padding(18.dp)) {
        summaryLines(w).forEach { Text(it, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 4.dp)) }
    }
    Spacer(Modifier.height(24.dp))
    Row(Modifier.clip(RoundedCornerShape(50)).background(Color.White).clickable {
        scope.launch {
            val f = withContext(Dispatchers.IO) { renderShareImage(ctx, w) }
            saved = withContext(Dispatchers.IO) { if (DataFolder.saveBytes(ctx, "Wrapped", "Di-Allo-Wrapped-${w.year}.png", f.readBytes())) "Image rangée dans le dossier Di-Allô › Wrapped" else null }
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Partager mon Wrapped"))
        }
    }.padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Share, null, tint = Color(0xFF16B871)); Spacer(Modifier.width(8.dp))
        Text("Partager", color = Color(0xFF0A7F6B), fontWeight = FontWeight.Bold, fontSize = 17.sp)
    }
    saved?.let { Spacer(Modifier.height(10.dp)); Line(it, 13.sp, .8f) }
}

private fun summaryLines(w: Wrap): List<String> = buildList {
    add("📞 ${w.total} appels")
    add("⏱️ ${hm(w.dur)} au téléphone")
    w.top.firstOrNull()?.let { add("👑 N°1 : ${it.name}") }
    val m = w.months.indices.maxByOrNull { w.months[it] } ?: 0
    add("📅 Mois record : ${monthName(m).trimEnd('.')}")
    val h = w.hours.indices.maxByOrNull { w.hours[it] } ?: 12
    add("🕰️ Plutôt ${persona(h)}")
    w.longest?.let { add("🏆 Record : ${fmtDur(it.duration)}") }
    add("🫂 ${w.people} personnes")
}

/** Image 1080×1920 du résumé, pour partager en story. */
private fun renderShareImage(ctx: Context, w: Wrap): File {
    val W = 1080; val H = 1920
    val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val bg = Paint().apply { shader = LinearGradient(0f, 0f, W.toFloat(), H.toFloat(), intArrayOf(0xFF7C3AED.toInt(), 0xFFDB2777.toInt(), 0xFFF59E0B.toInt()), null, Shader.TileMode.CLAMP) }
    c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), bg)
    val hand = BitmapFactory.decodeResource(ctx.resources, R.drawable.logo_hand)
    hand?.let { c.drawBitmap(Bitmap.createScaledBitmap(it, 220, (220f * it.height / it.width).toInt(), true), (W - 220) / 2f, 150f, null) }
    val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 96f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.CENTER }
    val sub = Paint(title).apply { textSize = 46f; typeface = Typeface.DEFAULT; alpha = 220 }
    val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 54f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    c.drawText("Mon ${w.year}", W / 2f, 520f, title)
    c.drawText("au téléphone", W / 2f, 630f, title)
    val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2EFFFFFF }
    c.drawRoundRect(80f, 720f, W - 80f, 1640f, 60f, 60f, card)
    var y = 830f
    summaryLines(w).forEach { s ->
        var t = s; while (line.measureText(t) > W - 260 && t.length > 4) t = t.dropLast(2) + "…"
        c.drawText(t, 140f, y, line); y += 112f
    }
    c.drawText("Di-Allô Wrapped 🤙", W / 2f, 1780f, sub)
    val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
    val f = File(dir, "Di-Allo-Wrapped-${w.year}.png")
    f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return f
}
