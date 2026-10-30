package nexplorer.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

fun Modifier.dragToSelect(
    list: LazyListState,
    keys: () -> List<String>,
    selected: () -> Set<String>,
    onSelect: (Set<String>) -> Unit,
): Modifier = composed {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val edge = with(density) { 72.dp.toPx() }
    val fastest = with(density) { 1000.dp.toPx() }
    val currentKeys by rememberUpdatedState(keys)
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)

    pointerInput(list) {
        fun keyAt(y: Float): Any? {
            val rows = list.layoutInfo.visibleItemsInfo
            if (rows.isEmpty()) return null
            return (rows.firstOrNull { y >= it.offset && y < it.offset + it.size }
                ?: if (y < rows.first().offset) rows.first() else rows.last()).key
        }

        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val notHeld = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                var gone = false
                while (!gone) {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    val c = e.changes.firstOrNull { it.id == down.id }
                    gone = c == null || !c.pressed || e.changes.size > 1 || c.isConsumed ||
                        (c.position - down.position).getDistance() > viewConfiguration.touchSlop
                }
                true
            }
            if (notHeld != null) return@awaitEachGesture

            val order = currentKeys()
            val from = order.indexOf(keyAt(down.position.y) as? String ?: return@awaitEachGesture)
            if (from < 0) return@awaitEachGesture
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            val before = currentSelected()
            val adding = order[from] !in before
            var y = down.position.y
            var to = -1
            fun reach() {
                val i = order.indexOf(keyAt(y) as? String ?: return)
                if (i < 0 || i == to) return
                to = i
                val range = order.subList(minOf(from, to), maxOf(from, to) + 1)
                currentOnSelect(if (adding) before + range else before - range.toSet())
            }
            reach()

            val autoScroll = scope.launch {
                var then = withFrameNanos { it }
                while (isActive) {
                    val now = withFrameNanos { it }
                    val seconds = (now - then) / 1e9f
                    then = now
                    val step = seconds * when {
                        y < edge -> -fastest * ((edge - y) / edge).coerceIn(0f, 1f)
                        y > size.height - edge -> fastest * ((y - (size.height - edge)) / edge).coerceIn(0f, 1f)
                        else -> 0f
                    }
                    if (step != 0f) {
                        list.scrollBy(step)
                        reach()
                    }
                }
            }
            try {
                while (true) {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    e.changes.forEach { it.consume() }
                    val c = e.changes.firstOrNull { it.id == down.id }
                    if (c == null || !c.pressed) break
                    y = c.position.y
                    reach()
                }
            } finally {
                autoScroll.cancel()
            }
        }
    }
}
