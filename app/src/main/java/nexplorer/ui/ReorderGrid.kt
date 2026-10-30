package nexplorer.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex

@Composable
fun <T> ReorderGrid(
    items: List<T>,
    key: (T) -> Any,
    onMove: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
    onTap: ((Int) -> Unit)? = null,
    cell: @Composable (item: T, index: Int, lifted: Boolean) -> Unit,
) {
    var dragging by remember { mutableStateOf<Int?>(null) }
    var drag by remember { mutableStateOf(Offset.Zero) }
    val grid = rememberLazyGridState()
    val move by rememberUpdatedState(onMove)
    val tap by rememberUpdatedState(onTap)

    LazyVerticalGrid(
        GridCells.Fixed(3),
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                if (up.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis) return@awaitEachGesture
                grid.layoutInfo.visibleItemsInfo.firstOrNull {
                    up.position.x >= it.offset.x && up.position.x < it.offset.x + it.size.width &&
                        up.position.y >= it.offset.y && up.position.y < it.offset.y + it.size.height
                }?.let { tap?.invoke(it.index) }
            }
        }.pointerInput(Unit) {
            fun itemAt(p: Offset) = grid.layoutInfo.visibleItemsInfo.firstOrNull {
                p.x >= it.offset.x && p.x < it.offset.x + it.size.width &&
                    p.y >= it.offset.y && p.y < it.offset.y + it.size.height
            }
            detectDragGesturesAfterLongPress(
                onDragStart = { p -> itemAt(p)?.let { dragging = it.index; drag = Offset.Zero } },
                onDrag = { change, amount ->
                    change.consume()
                    val from = dragging ?: return@detectDragGesturesAfterLongPress
                    drag += amount
                    val info = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.index == from } ?: return@detectDragGesturesAfterLongPress
                    val center = Offset(
                        info.offset.x + info.size.width / 2f + drag.x,
                        info.offset.y + info.size.height / 2f + drag.y,
                    )
                    val target = itemAt(center) ?: return@detectDragGesturesAfterLongPress
                    if (target.index != from) {
                        move(from, target.index)
                        drag += Offset(
                            (info.offset.x - target.offset.x).toFloat(),
                            (info.offset.y - target.offset.y).toFloat(),
                        )
                        dragging = target.index
                    }
                },
                onDragEnd = { dragging = null; drag = Offset.Zero },
                onDragCancel = { dragging = null; drag = Offset.Zero },
            )
        },
        state = grid,
        userScrollEnabled = dragging == null,
    ) {
        itemsIndexed(items, key = { _, item -> key(item) }) { i, item ->
            val lifted = i == dragging
            Box(
                Modifier
                    .zIndex(if (lifted) 1f else 0f)
                    .graphicsLayer {
                        if (lifted) {
                            translationX = drag.x
                            translationY = drag.y
                            scaleX = 1.06f
                            scaleY = 1.06f
                            shadowElevation = 12f
                        }
                    },
            ) {
                cell(item, i, lifted)
            }
        }
    }
}
