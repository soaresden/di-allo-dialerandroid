package fr.soaresden.diallo

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import java.util.Calendar

internal val CallGreen = Color(0xFF2FC45B)
internal val Amber = Color(0xFFE8903A)
internal val Red = Color(0xFFFF4D4F)

@Composable internal fun muted(a: Float = .6f) = MaterialTheme.colorScheme.onSurface.copy(alpha = a)

@Composable
internal fun Avatar(label: String, photo: String?, size: Dp = 42.dp) {
    if (!photo.isNullOrBlank()) AsyncImage(model = photo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(CircleShape))
    else {
        val palette = listOf(0xFF3A7AFE, 0xFF2FC45B, 0xFFE8903A, 0xFF9B59F6, 0xFFEF5DA8, 0xFF16B8A6).map { Color(it) }
        val col = palette[(label.hashCode().and(0x7fffffff)) % palette.size]
        Box(Modifier.size(size).clip(CircleShape).background(col), contentAlignment = Alignment.Center) {
            Text(label.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = (size.value / 2.4).sp)
        }
    }
}

@Composable
internal fun Empty(msg: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(msg, color = muted(.5f), textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp)) }
}

@Composable
internal fun Tile(k: String, v: String, mod: Modifier, accent: Color? = null) {
    Surface(mod, color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 11.dp)) {
            Text(k, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = muted(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(v, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = accent ?: MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** État replié/déplié mémorisé (survit à la fermeture de l'app). */
@Composable
internal fun rememberCollapsed(id: String, default: Boolean = false): MutableState<Boolean> {
    val ctx = LocalContext.current
    val st = remember(id) { mutableStateOf(prefs(ctx).getBoolean("col_$id", default)) }
    return remember(st) {
        object : MutableState<Boolean> by st {
            override var value: Boolean
                get() = st.value
                set(v) { st.value = v; prefs(ctx).edit().putBoolean("col_$id", v).apply() }
        }
    }
}

@Composable
internal fun Chevron(collapsed: Boolean, tint: Color = muted(.5f)) {
    val rot by animateFloatAsState(if (collapsed) -90f else 0f, label = "chev")
    Icon(Icons.Filled.ExpandMore, if (collapsed) tr("Déplier") else tr("Replier"), tint = tint, modifier = Modifier.size(22.dp).rotate(rot))
}

/** Carte à titre ; touche le titre pour la replier / déplier. */
@Composable
internal fun SectionCard(
    title: String, action: (@Composable () -> Unit)? = null, collapsible: Boolean = true,
    id: String = I18n.key(title), defaultCollapsed: Boolean = false, content: @Composable ColumnScope.() -> Unit
) {
    var collapsed by rememberCollapsed("card_$id", defaultCollapsed)
    val folded = collapsible && collapsed
    val th = LocalHTheme.current
    Surface(
        Modifier.fillMaxWidth().padding(bottom = 12.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = th.cardAlpha), shape = th.cardShape(),
        border = th.border?.let { androidx.compose.foundation.BorderStroke(if (th.keyStyle == KeyStyle.HARD) 2.dp else 1.dp, it) }
    , contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 30.dp).clip(RoundedCornerShape(8.dp)).then(if (collapsible) Modifier.clickable { collapsed = !collapsed } else Modifier),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, fontWeight = if (th.head != null) FontWeight.Normal else FontWeight.Bold, fontSize = (14f * th.headScale).sp, fontFamily = th.head, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
                if (!folded) action?.invoke()
                if (collapsible) { Spacer(Modifier.width(4.dp)); Chevron(folded) }
            }
            AnimatedVisibility(!folded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(Modifier.padding(top = 10.dp)) { content() }
            }
        }
    }
}

@Composable
internal fun MiniBar(name: String, photo: String?, frac: Float, right: String, color: Color, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).then(if (onClick != null) Modifier.clickable { onClick() } else Modifier).padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(name, photo, 30.dp); Spacer(Modifier.width(10.dp))
        Text(name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground)
        Box(Modifier.width(72.dp).height(8.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(frac.coerceIn(0.04f, 1f)).clip(RoundedCornerShape(50)).background(color))
        }
        Spacer(Modifier.width(10.dp)); Text(right, fontSize = 12.sp, color = muted(), modifier = Modifier.widthIn(min = 48.dp), textAlign = TextAlign.End)
    }
}

/** Contrôle segmenté maison (pas de scroll horizontal). */
@Composable
internal fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(3.dp)) {
        options.forEachIndexed { i, o ->
            val sel = i == selected
            Box(Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).background(if (sel) MaterialTheme.colorScheme.primary else Color.Transparent).clickable { onSelect(i) }.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                Text(o, fontSize = 12.5.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium, color = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, maxLines = 1)
            }
        }
    }
}

/** Histogramme cliquable : [onBar] reçoit l'index de la barre touchée. */
@Composable
internal fun BarChart(values: List<Int>, labels: List<String>, color: Color, height: Dp = 120.dp, highlight: Int = -1, onBar: ((Int) -> Unit)? = null) {
    val max = (values.maxOrNull() ?: 1).coerceAtLeast(1)
    val track = MaterialTheme.colorScheme.surfaceVariant
    Column(Modifier.fillMaxWidth()) {
        Text(tr("max {0}", max), fontSize = 10.sp, color = muted(.45f), modifier = Modifier.align(Alignment.End))
        Canvas(Modifier.fillMaxWidth().height(height).then(if (onBar != null && values.isNotEmpty()) Modifier.pointerInput(values) {
            detectTapGestures { o -> val i = (o.x / (size.width.toFloat() / values.size)).toInt().coerceIn(0, values.size - 1); onBar(i) }
        } else Modifier)) {
            val n = values.size.coerceAtLeast(1); val gap = size.width / n; val bw = (gap * 0.66f).coerceAtLeast(2f)
            values.forEachIndexed { i, v ->
                val x = i * gap + (gap - bw) / 2
                drawRoundRect(track, Offset(x, 0f), Size(bw, size.height), CornerRadius(bw / 3, bw / 3))
                val bh = if (v > 0) (v.toFloat() / max) * size.height else 0f
                if (bh > 0) drawRoundRect(if (i == highlight) color else color.copy(alpha = .85f), Offset(x, size.height - bh), Size(bw, bh.coerceAtLeast(4f)), CornerRadius(bw / 3, bw / 3))
            }
        }
        if (labels.isNotEmpty()) {
            val step = if (labels.size <= 13) 1 else (labels.size + 5) / 6
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                labels.forEachIndexed { i, l -> Text(if (i % step == 0) l else "", Modifier.weight(1f), fontSize = 9.5.sp, color = muted(.55f), textAlign = TextAlign.Center, maxLines = 1) }
            }
        }
    }
}

/* ---------- Formatage ---------- */
internal fun keyOf(n: String): String { val d = n.filter { it.isDigit() }; return if (d.length > 9) d.takeLast(9) else d }
internal fun fmtDur(s: Int): String { val h = s / 3600; val m = (s % 3600) / 60; val r = s % 60; return when { h > 0 -> "${h}h${m.toString().padStart(2, '0')}"; m == 0 -> "$r s"; else -> "$m:${r.toString().padStart(2, '0')}" } }
internal fun fmtClock(s: Long): String {
    val d = s / 86400; val h = (s % 86400) / 3600; val m = (s % 3600) / 60
    return when { d > 0 -> tr("{0}j {1}h", d, h); h > 0 -> "${h}h${m.toString().padStart(2, '0')}"; else -> "${m} min" }
}
internal fun daysAgo(ts: Long): Int = ((System.currentTimeMillis() - ts) / 86_400_000L).toInt()
internal fun agoText(ts: Long): String { val d = daysAgo(ts); return when { d <= 0 -> tr("aujourd'hui"); d == 1 -> tr("hier"); d < 30 -> tr("{0} j", d); d < 365 -> tr("{0} mois", d / 30); else -> trn(d / 365, "{0} an", "{0} ans") } }
private val MONTHS = listOf("janv.", "févr.", "mars", "avr.", "mai", "juin", "juil.", "août", "sept.", "oct.", "nov.", "déc.")
internal fun monthName(m: Int) = tr(MONTHS[m.coerceIn(0, 11)])
internal fun fmtDate(ts: Long): String { val d = Calendar.getInstance().apply { timeInMillis = ts }; return "${d.get(Calendar.DAY_OF_MONTH)} ${monthName(d.get(Calendar.MONTH))} ${d.get(Calendar.YEAR)}" }
internal fun fmtWhen(ts: Long): String {
    val now = Calendar.getInstance(); val d = Calendar.getInstance().apply { timeInMillis = ts }
    val hm = "%02d:%02d".format(d.get(Calendar.HOUR_OF_DAY), d.get(Calendar.MINUTE))
    val sameDay = now.get(Calendar.YEAR) == d.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == d.get(Calendar.DAY_OF_YEAR)
    now.add(Calendar.DAY_OF_YEAR, -1)
    val yest = now.get(Calendar.YEAR) == d.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == d.get(Calendar.DAY_OF_YEAR)
    return when { sameDay -> hm; yest -> tr("Hier {0}", hm); else -> "%02d/%02d/%02d".format(d.get(Calendar.DAY_OF_MONTH), d.get(Calendar.MONTH) + 1, d.get(Calendar.YEAR) % 100) }
}

/** Mise en forme lisible : 06 12 34 56 78 / +33 6 12 34 56 78. */
internal fun prettyNumber(raw: String): String {
    val s = raw.replace(" ", "")
    if (s.any { !it.isDigit() && it != '+' }) return s
    return when {
        s.startsWith("+33") && s.length > 3 -> ("+33 " + s.substring(3).let { r -> (if (r.isNotEmpty()) r.take(1) + " " else "") + r.drop(1).chunked(2).joinToString(" ") }).trim()
        s.startsWith("0") && s.length <= 10 -> s.chunked(2).joinToString(" ")
        else -> s
    }
}
