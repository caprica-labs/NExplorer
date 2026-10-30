package nexplorer.reader

import android.graphics.PointF
import android.graphics.RectF

enum class Direction { Vertical, Horizontal }
enum class Movement { Scroll, Paged }
enum class ReaderTheme {
    Light, Dark;

    companion object {
        fun fromStored(value: String?, appDark: Boolean): ReaderTheme = when (value) {
            "Dark" -> Dark
            "FollowApp" -> if (appDark) Dark else Light
            else -> Light
        }
    }
}

enum class AppTheme { FollowSystem, Light, Dark }

data class ViewSettings(
    val direction: Direction = Direction.Vertical,
    val movement: Movement = Movement.Scroll,
    val reflow: Boolean = false,
    val textSizePct: Int = 100,
) {
    fun encode() = "$direction|$movement|$reflow|$textSizePct"

    companion object {
        val DEFAULT = ViewSettings()

        fun decode(raw: String?): ViewSettings = runCatching {
            val (d, m, r, t) = raw!!.split('|')
            ViewSettings(Direction.valueOf(d), Movement.valueOf(m), r.toBooleanStrict(), t.toInt())
        }.getOrDefault(DEFAULT)
    }
}

data class TextPos(val page: Int, val x: Float, val y: Float) {
    fun before(o: TextPos): Boolean = page < o.page || (page == o.page && (y < o.y - 2f || (kotlin.math.abs(y - o.y) <= 2f && x < o.x)))
}

data class Selection(val start: TextPos, val end: TextPos, val all: Boolean = false) {
    val pages: IntRange get() = start.page..end.page

    fun moved(pos: TextPos, moveStart: Boolean): Selection {
        val (s, e) = if (moveStart) pos to end else start to pos
        return if (e.before(s)) Selection(e, s) else Selection(s, e)
    }
}

data class Hit(val page: Int, val rects: List<RectF>)

data class ReaderSearchState(
    val query: String = "",
    val hits: List<Hit> = emptyList(),
    val current: Int = -1,
) {
    val counterText: String
        get() = if (hits.isEmpty()) "0 of 0" else "${current + 1} of ${hits.size}"

    fun next() = if (hits.isEmpty()) this else copy(current = (current + 1).mod(hits.size))
    fun prev() = if (hits.isEmpty()) this else copy(current = (current - 1).mod(hits.size))
}

fun parsePageInput(input: String, pageCount: Int): Int? =
    input.trim().toIntOrNull()?.takeIf { it in 1..pageCount }?.minus(1)

fun ViewSettings.withTextSize(delta: Int) = copy(textSizePct = (textSizePct + delta).coerceIn(60, 200))
