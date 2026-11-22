package fr.soaresden.diallo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Choix du thème en direct : volet fin à droite, l'appli reste visible (et utilisable) à gauche. */
object ThemePicker {
    val open = mutableStateOf(false)
}

@Composable
internal fun ThemePanel(themeId: String, onTheme: (String) -> Unit, tab: Int, onTab: (Int) -> Unit, onClose: () -> Unit) {
    val original = remember { themeId }
    val cs = MaterialTheme.colorScheme
    var auto by remember { mutableStateOf(false) }
    val cycle = listOf(0, 1, 2, 4, 5)
    // ▶ fait défiler les écrans pour voir le thème partout
    LaunchedEffect(auto) {
        while (auto) { delay(2600); onTab(cycle[(cycle.indexOf(tab).coerceAtLeast(0) + 1) % cycle.size]) }
    }
    BackHandler { onClose() }
    Surface(
        Modifier.width(96.dp).fillMaxHeight(), color = cs.surface, contentColor = cs.onSurface, shadowElevation = 10.dp
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 6.dp)) {
            // valider / annuler
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(10.dp)).background(cs.surfaceVariant).clickable { onTheme(original); onClose() }, contentAlignment = Alignment.Center) {
                    Text("↺", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                Box(Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(10.dp)).background(cs.primary).clickable { onClose() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Check, tr("Valider"), tint = cs.onPrimary, modifier = Modifier.size(20.dp))
                }
            }
            // écran affiché derrière
            Text(tr("Voir"), fontSize = 10.sp, color = muted(.6f), modifier = Modifier.padding(top = 8.dp, bottom = 3.dp))
            val tabs: List<Pair<Int, ImageVector>> = listOf(0 to Icons.Filled.Dialpad, 1 to Icons.Filled.Schedule, 2 to Icons.Filled.Person, 4 to Icons.Filled.BarChart, 5 to Icons.Filled.Settings)
            (tabs.map { it as Pair<Int, ImageVector>? } + listOf(null)).chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { item ->
                        val on = if (item == null) auto else !auto && tab == item.first
                        Box(
                            Modifier.weight(1f).height(28.dp).clip(RoundedCornerShape(8.dp)).background(if (on) cs.primary else cs.surfaceVariant)
                                .clickable { if (item == null) auto = !auto else { auto = false; onTab(item.first) } },
                            contentAlignment = Alignment.Center
                        ) {
                            if (item == null) Text(if (auto) "⏸" else "▶", fontSize = 13.sp, color = if (on) cs.onPrimary else cs.onSurface)
                            else Icon(item.second, null, tint = if (on) cs.onPrimary else cs.onSurface, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            val all = listOf<HTheme?>(null) + THEMES
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = all.indexOfFirst { (it?.id ?: "auto") == original }.coerceAtLeast(0))
            LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                items(all, key = { it?.id ?: "auto" }) { t -> MiniSwatch(t, (t?.id ?: "auto") == themeId) { onTheme(t?.id ?: "auto") } }
            }
        }
    }
}

@Composable
private fun MiniSwatch(t: HTheme?, selected: Boolean, onClick: () -> Unit) {
    val shown = t ?: resolveTheme("auto", androidx.compose.foundation.isSystemInDarkTheme())
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .border(if (selected) 2.5.dp else 1.dp, if (selected) cs.primary else cs.outlineVariant, RoundedCornerShape(12.dp))
            .clickable { onClick() }
    ) {
        Box(Modifier.fillMaxWidth().height(58.dp).background(shown.brush()), contentAlignment = Alignment.Center) {
            DecorLayer(shown, Modifier.matchParentSize(), animate = false)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("1", "2").forEach { d ->
                    Box(Modifier.size(24.dp).themedKey(shown, shown.keyShapeOf()), contentAlignment = Alignment.Center) {
                        Text(d, fontFamily = shown.num, fontSize = (11f * shown.numScale).sp, color = if (shown.keyStyle == KeyStyle.NEON) shown.primary else shown.text)
                    }
                }
                Box(Modifier.size(24.dp).clip(shown.keyShapeOf()).background(CallGreen))
            }
        }
        Text(
            t?.name?.let { tr(it) } ?: tr("Auto"), fontSize = 10.sp, lineHeight = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().background(if (selected) cs.primary.copy(alpha = .15f) else Color.Transparent).padding(horizontal = 3.dp, vertical = 4.dp)
        )
    }
}
