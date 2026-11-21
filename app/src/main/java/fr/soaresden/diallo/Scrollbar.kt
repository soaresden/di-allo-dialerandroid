package fr.soaresden.diallo

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Ascenseur pour une LazyColumn : le pouce montre où on est, et on peut le saisir
 * (ou toucher la barre) pour aller directement plus haut / plus bas.
 * À placer dans une Box par-dessus la liste, aligné à droite.
 */
@Composable
internal fun LazyScrollbar(state: LazyListState, modifier: Modifier = Modifier) {
    val info = state.layoutInfo
    val total = info.totalItemsCount
    val visible = info.visibleItemsInfo
    if (total == 0 || visible.isEmpty()) return
    val shown = visible.count { it.offset + it.size <= info.viewportEndOffset && it.offset >= info.viewportStartOffset }.coerceAtLeast(1)
    if (shown >= total) return   // tout tient à l'écran : pas d'ascenseur
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    val first = visible.first()
    val exact = first.index + if (first.size > 0) (-first.offset).toFloat() / first.size else 0f
    val pos = (exact / (total - shown).coerceAtLeast(1)).coerceIn(0f, 1f)
    val w by animateFloatAsState(if (dragging) 9f else 5f, label = "sb")
    val primary = MaterialTheme.colorScheme.primary

    BoxWithConstraints(modifier.fillMaxHeight().width(26.dp)) {
        val hPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        fun jump(y: Float) { val f = (y / hPx).coerceIn(0f, 1f); scope.launch { state.scrollToItem(((total - 1) * f).toInt()) } }
        val maxH = maxHeight
        val thumb = (maxH * (shown.toFloat() / total)).coerceAtLeast(40.dp).coerceAtMost(maxH)
        Box(
            Modifier.fillMaxSize()
                .pointerInput(total) { detectTapGestures { jump(it.y) } }
                .pointerInput(total) {
                    detectVerticalDragGestures(
                        onDragStart = { dragging = true; jump(it.y) },
                        onDragEnd = { dragging = false }, onDragCancel = { dragging = false }
                    ) { change, _ -> change.consume(); jump(change.position.y) }
                }
        ) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(end = 4.dp)
                    .offset(y = (maxH - thumb) * pos)
                    .width(w.dp).height(thumb)
                    .clip(RoundedCornerShape(50))
                    .background(primary.copy(alpha = if (dragging) .85f else .4f))
            )
        }
    }
}
