package nexplorer.ui.reader

import android.graphics.RectF
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.runtime.rememberUpdatedState
import nexplorer.reader.Selection
import nexplorer.reader.TextPos
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import nexplorer.ui.theme.AccentBlue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

data class PagePlace(val left: Float, val top: Float, val k: Float, val width: Float, val height: Float) {
    fun distance(w: Offset): Float {
        val dx = maxOf(left - w.x, 0f, w.x - (left + width))
        val dy = maxOf(top - w.y, 0f, w.y - (top + height))
        return dx + dy
    }
}

@Composable
internal fun SelectionHandles(
    sel: Selection,
    placed: Map<Int, PagePlace>,
    rects: Map<Int, List<RectF>>,
    onMove: (window: Offset, fixed: TextPos, done: Boolean) -> Unit,
) {
    var layer by remember { mutableStateOf<LayoutCoordinates?>(null) }

    fun window(page: Int, x: Float, y: Float): Offset? = placed[page]?.let { Offset(it.left + x * it.k, it.top + y * it.k) }
    val first = rects[sel.start.page]?.firstOrNull()
    val last = rects[sel.end.page]?.lastOrNull()
    val startTip = first?.let { window(sel.start.page, it.left, it.bottom) }
    val startIn = first?.let { window(sel.start.page, it.left + 1f, it.centerY()) }
    val endTip = last?.let { window(sel.end.page, it.right, it.bottom) }
    val endIn = last?.let { window(sel.end.page, it.right - 1f, it.centerY()) }
    Box(Modifier.fillMaxSize().onGloballyPositioned { layer = it }) {
        val l = layer ?: return@Box
        Handle(startTip?.let { l.windowToLocal(it) }, startIn) { w, done -> onMove(w, sel.end, done) }
        Handle(endTip?.let { l.windowToLocal(it) }, endIn) { w, done -> onMove(w, sel.start, done) }
    }
}

@Composable
internal fun Handle(tip: Offset?, inText: Offset?, onMove: (window: Offset, done: Boolean) -> Unit) {
    val density = LocalDensity.current
    val touch = 40.dp
    val half = with(density) { touch.toPx() } / 2
    val move by rememberUpdatedState(onMove)
    var lastTip by remember { mutableStateOf(Offset.Zero) }
    if (tip != null) lastTip = tip
    val anchor by rememberUpdatedState(inText)
    val me = remember { mutableStateOf<LayoutCoordinates?>(null) }
    var downAt by remember { mutableStateOf<Offset?>(null) }
    Box(
        Modifier
            .offset { IntOffset((lastTip.x - half).roundToInt(), lastTip.y.roundToInt()) }
            .size(touch)
            .alpha(if (tip != null) 1f else 0f)
            .onGloballyPositioned { me.value = it }
            .pointerInput(Unit) {
                awaitEachGesture { downAt = me.value?.localToWindow(awaitFirstDown(requireUnconsumed = false).position) }
            }
            .pointerInput(Unit) {
                var gap = Offset.Zero
                var at = Offset.Zero
                fun finger(p: Offset) = me.value?.localToWindow(p) ?: p
                detectDragGestures(
                    onDragStart = { p ->
                        val f = downAt ?: finger(p)
                        gap = f - (anchor ?: f)
                        at = finger(p) - gap
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        at = finger(change.position) - gap
                        move(at, false)
                    },
                    onDragEnd = { move(at, true) },
                    onDragCancel = { move(at, true) },
                )
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Canvas(Modifier.size(18.dp)) {
            drawLine(AccentBlue, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height / 2), strokeWidth = 4f)
            drawCircle(AccentBlue, radius = size.width / 3, center = Offset(size.width / 2, size.height * 2 / 3))
        }
    }
}
