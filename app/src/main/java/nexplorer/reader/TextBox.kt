package nexplorer.reader

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

object TextBox {
    data class Rect(val x: Float, val y: Float, val w: Float, val h: Float)

    const val MIN_W = 40f
    const val MIN_H = 16f
    const val DEFAULT_W = 160f

    const val LINE_HEIGHT = 1.2f

    const val PAD = 4f

    val SIZES = listOf(8f, 10f, 12f, 14f, 18f, 24f, 32f)
    const val DEFAULT_SIZE = 12f

    val COLORS: List<FloatArray> = listOf(
        floatArrayOf(0.90f, 0.22f, 0.21f),
        floatArrayOf(0.98f, 0.55f, 0.0f),
        floatArrayOf(0.99f, 0.85f, 0.21f),
        floatArrayOf(0.26f, 0.63f, 0.28f),
        floatArrayOf(0.12f, 0.53f, 0.90f),
        floatArrayOf(0.25f, 0.32f, 0.71f),
        floatArrayOf(0.56f, 0.14f, 0.67f),
        floatArrayOf(0f, 0f, 0f),
        floatArrayOf(1f, 1f, 1f),
    )
    const val DEFAULT_COLOR = 7

    fun newAt(x: Float, y: Float, size: Float, pageW: Float, pageH: Float): Rect =
        move(Rect(x, y, DEFAULT_W.coerceAtMost(pageW), neededHeight(1, size).coerceAtMost(pageH)), 0f, 0f, pageW, pageH)

    fun move(r: Rect, dx: Float, dy: Float, pageW: Float, pageH: Float): Rect =
        r.copy(
            x = (r.x + dx).coerceIn(0f, (pageW - r.w).coerceAtLeast(0f)),
            y = (r.y + dy).coerceIn(0f, (pageH - r.h).coerceAtLeast(0f)),
        )

    fun wrap(text: String, maxWidth: Float, size: Float, measure: (String) -> Float): List<String> {
        fun width(s: CharSequence) = measure(s.toString()) * size
        val out = ArrayList<String>()
        for (paragraph in text.split('\n')) {
            var line = StringBuilder()
            for (word in paragraph.split(' ')) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (width(candidate) <= maxWidth) {
                    line = StringBuilder(candidate)
                    continue
                }
                if (line.isNotEmpty()) out.add(line.toString())
                line = StringBuilder()
                for (c in word) {
                    if (line.isNotEmpty() && width("$line$c") > maxWidth) {
                        out.add(line.toString())
                        line = StringBuilder()
                    }
                    line.append(c)
                }
            }
            out.add(line.toString())
        }
        return out
    }

    fun neededHeight(lines: Int, size: Float): Float = max(lines, 1) * size * LINE_HEIGHT + PAD

    // ---- Rotation ----

    fun corner(r: Rect, rotation: Float, fx: Float, fy: Float): Pair<Float, Float> {
        val cx = r.x + r.w / 2
        val cy = r.y + r.h / 2
        val (dx, dy) = rotate((fx - 0.5f) * r.w, (fy - 0.5f) * r.h, rotation)
        return (cx + dx) to (cy + dy)
    }

    fun fractions(r: Rect, rotation: Float, x: Float, y: Float): Pair<Float, Float> {
        val (dx, dy) = rotate(x - (r.x + r.w / 2), y - (r.y + r.h / 2), -rotation)
        return (dx / r.w + 0.5f) to (dy / r.h + 0.5f)
    }

    fun resizeBottomRight(r: Rect, rotation: Float, fingerX: Float, fingerY: Float, minH: Float, keepShape: Boolean = false): Rect {
        val (ax, ay) = corner(r, rotation, 0f, 0f)
        val (vx, vy) = rotate(fingerX - ax, fingerY - ay, -rotation)
        val ratio = r.w / r.h
        val w = if (keepShape) max(max(vx, vy * ratio), MIN_W) else max(vx, MIN_W)
        val h = if (keepShape) w / ratio else max(vy, max(minH, MIN_H))
        val (hx, hy) = rotate(w / 2, h / 2, rotation)
        val cx = ax + hx
        val cy = ay + hy
        return Rect(cx - w / 2, cy - h / 2, w, h)
    }

    fun snap(degrees: Float): Float {
        val d = ((degrees % 360f) + 360f) % 360f
        val right = (d / 90f).roundToInt() * 90f
        return if (abs(d - right) <= 4f) right % 360f else d
    }

    fun rotateClockwise(degrees: Float): Float = snap(degrees + 90f)

    private fun rotate(x: Float, y: Float, degrees: Float): Pair<Float, Float> {
        val a = Math.toRadians(degrees.toDouble())
        val c = cos(a).toFloat()
        val s = sin(a).toFloat()
        return (x * c - y * s) to (x * s + y * c)
    }
}
