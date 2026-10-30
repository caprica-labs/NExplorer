package nexplorer.reader

import kotlin.math.max
import kotlin.math.min

class Signature(
    val id: String,
    val name: String,
    val color: Int,
    val width: Float,
    val w: Float,
    val h: Float,
    val strokes: List<FloatArray>,
) {
    fun copy(name: String = this.name, color: Int = this.color) = Signature(id, name, color, width, w, h, strokes)
}

object Signatures {
    const val START_W = 150f
    const val START_H = 70f

    fun fromDrawing(id: String, name: String, color: Int, width: Float, strokes: List<FloatArray>): Signature? {
        val drawn = strokes.filter { it.size >= 2 }
        if (drawn.isEmpty()) return null
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (s in drawn) for (i in s.indices step 2) {
            x0 = min(x0, s[i]); x1 = max(x1, s[i]); y0 = min(y0, s[i + 1]); y1 = max(y1, s[i + 1])
        }
        val pad = width / 2 + 1
        val moved = drawn.map { s ->
            val p = if (s.size == 2) floatArrayOf(s[0], s[1], s[0] + 0.1f, s[1]) else smooth(s)
            FloatArray(p.size) { i -> p[i] - (if (i % 2 == 0) x0 else y0) + pad }
        }
        return Signature(id, name, color, width, x1 - x0 + 2 * pad, y1 - y0 + 2 * pad, moved)
    }

    fun smooth(s: FloatArray, steps: Int = 4): FloatArray {
        val n = s.size / 2
        if (n < 3) return s
        val out = ArrayList<Float>(s.size * steps)
        out += s[0]; out += s[1]
        var sx = s[0]; var sy = s[1]
        for (i in 1 until n - 1) {
            val cx = s[2 * i]; val cy = s[2 * i + 1]
            val ex = (cx + s[2 * i + 2]) / 2; val ey = (cy + s[2 * i + 3]) / 2
            for (k in 1..steps) {
                val t = k.toFloat() / steps
                val u = 1 - t
                out += u * u * sx + 2 * u * t * cx + t * t * ex
                out += u * u * sy + 2 * u * t * cy + t * t * ey
            }
            sx = ex; sy = ey
        }
        out += s[2 * n - 2]; out += s[2 * n - 1]
        return out.toFloatArray()
    }

    fun encode(s: Signature): String = buildString {
        append(s.name.replace(Regex("[\\r\\n]+"), " ")).append('\n')
        append("${s.color} ${s.width} ${s.w} ${s.h}")
        for (stroke in s.strokes) append('\n').append(stroke.joinToString(" "))
    }

    fun decode(id: String, text: String): Signature? = runCatching {
        val lines = text.lines()
        val head = lines[1].trim().split(' ').map { it.toFloat() }
        val strokes = lines.drop(2).filter { it.isNotBlank() }.map { l -> l.trim().split(' ').map { it.toFloat() }.toFloatArray() }
        require(head.size == 4 && strokes.isNotEmpty() && strokes.all { it.size >= 2 && it.size % 2 == 0 })
        Signature(id, lines[0], head[0].toInt().coerceIn(0, TextBox.COLORS.lastIndex), head[1], head[2], head[3], strokes)
    }.getOrNull()

    fun newAt(s: Signature, x: Float, y: Float, pageW: Float, pageH: Float): TextBox.Rect {
        val scale = min(min(START_W / s.w, START_H / s.h), min(pageW / s.w, pageH / s.h))
        return TextBox.move(TextBox.Rect(x, y, s.w * scale, s.h * scale), 0f, 0f, pageW, pageH)
    }
}
