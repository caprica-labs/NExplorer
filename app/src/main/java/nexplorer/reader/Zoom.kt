package nexplorer.reader

import kotlin.math.abs

object Zoom {
    const val MIN = 1f
    const val MAX = 5f

    fun clampOffset(offset: Float, scale: Float, size: Float): Float =
        offset.coerceIn(size - size * scale, 0f)

    enum class DragTarget { Pan, Content }

    fun dragTarget(
        dx: Float,
        dy: Float,
        verticalAxis: Boolean,
        scrollingList: Boolean,
        contentCanScroll: Boolean,
        canPanAlong: Boolean,
    ): DragTarget {
        val along = abs(if (verticalAxis) dy else dx)
        val cross = abs(if (verticalAxis) dx else dy)
        if (cross > along) return DragTarget.Pan
        return if (scrollingList) {
            if (contentCanScroll) DragTarget.Content else DragTarget.Pan
        } else {
            if (canPanAlong) DragTarget.Pan else DragTarget.Content
        }
    }

    fun zoomAround(scale: Float, offset: Float, centroid: Float, factor: Float): Pair<Float, Float> {
        val newScale = (scale * factor).coerceIn(MIN, MAX)
        val contentPoint = (centroid - offset) / scale
        return newScale to (centroid - contentPoint * newScale)
    }
}
