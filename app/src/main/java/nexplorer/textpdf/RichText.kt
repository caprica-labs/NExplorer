package nexplorer.textpdf

import nexplorer.reader.TextBox
import kotlin.math.roundToInt

data class Style(
    val size: Float = TextBox.DEFAULT_SIZE,
    val color: Int = TextBox.DEFAULT_COLOR,
    val bold: Boolean = false,
    val underline: Boolean = false,
)

data class Run(val length: Int, val style: Style)

data class RichText(val text: String, val runs: List<Run>) {
    private fun splitAt(list: List<Run>, at: Int): Pair<List<Run>, List<Run>> {
        val before = ArrayList<Run>()
        val after = ArrayList<Run>()
        var pos = 0
        for (r in list) {
            when {
                pos + r.length <= at -> before += r
                pos >= at -> after += r
                else -> { before += r.copy(length = at - pos); after += r.copy(length = pos + r.length - at) }
            }
            pos += r.length
        }
        return before to after
    }

    fun styleBefore(pos: Int): Style? {
        if (text.isEmpty()) return null
        val i = (pos - 1).coerceIn(0, text.length - 1)
        var seen = 0
        for (r in runs) {
            seen += r.length
            if (i < seen) return r.style
        }
        return runs.last().style
    }

    fun edit(newText: String, typing: Style): RichText {
        if (newText == text) return this
        var start = 0
        val maxStart = minOf(text.length, newText.length)
        while (start < maxStart && text[start] == newText[start]) start++
        var end = 0
        val maxEnd = minOf(text.length, newText.length) - start
        while (end < maxEnd && text[text.length - 1 - end] == newText[newText.length - 1 - end]) end++
        val inserted = newText.length - start - end
        val left = splitAt(runs, start).first
        val right = splitAt(runs, text.length - end).second
        return RichText(newText, joined(left + Run(inserted, typing) + right))
    }

    fun restyle(start: Int, end: Int, change: (Style) -> Style): RichText {
        val a = start.coerceIn(0, text.length)
        val b = end.coerceIn(a, text.length)
        if (a == b) return this
        val (left, rest) = splitAt(runs, a)
        val (mid, right) = splitAt(rest, b - a)
        return RichText(text, joined(left + mid.map { it.copy(style = change(it.style)) } + right))
    }

    fun stylesIn(start: Int, end: Int): List<Style> {
        val a = start.coerceIn(0, text.length)
        val b = end.coerceIn(a, text.length)
        return splitAt(splitAt(runs, a).second, b - a).first.flatMap { r -> List(r.length) { r.style } }
    }

    fun toHtml(): String = buildString {
        append("<body>")
        var pos = 0
        val lines = mutableListOf<MutableList<Pair<String, Style>>>(mutableListOf())
        for (r in runs) {
            val piece = text.substring(pos, pos + r.length)
            pos += r.length
            piece.split('\n').forEachIndexed { i, part ->
                if (i > 0) lines.add(mutableListOf())
                if (part.isNotEmpty()) lines.last().add(part to r.style)
            }
        }
        for (line in lines) {
            append("<p dir=\"auto\">")
            if (line.isEmpty()) append("&#160;")
            for ((part, style) in line) {
                append("<span style=\"").append(css(style)).append("\">").append(escape(part)).append("</span>")
            }
            append("</p>")
        }
        append("</body>")
    }

    companion object {
        val EMPTY = RichText("", emptyList())

        fun plain(text: String, style: Style = Style()): RichText =
            if (text.isEmpty()) EMPTY else RichText(text, listOf(Run(text.length, style)))

        private fun joined(list: List<Run>): List<Run> {
            val out = ArrayList<Run>()
            for (r in list) {
                if (r.length <= 0) continue
                val last = out.lastOrNull()
                if (last != null && last.style == r.style) out[out.lastIndex] = last.copy(length = last.length + r.length) else out += r
            }
            return out
        }

        fun of(text: String, styles: List<Style>): RichText {
            require(text.length == styles.size)
            val runs = mutableListOf<Run>()
            for (s in styles) {
                val last = runs.lastOrNull()
                if (last != null && last.style == s) runs[runs.lastIndex] = last.copy(length = last.length + 1)
                else runs.add(Run(1, s))
            }
            return RichText(text, runs)
        }

        fun css(s: Style): String {
            val rgb = TextBox.COLORS[s.color]
            fun hex(v: Float) = "%02x".format((v * 255).roundToInt().coerceIn(0, 255))
            return buildString {
                append("font-size:").append(s.size.roundToInt()).append("pt;")
                append("color:#").append(hex(rgb[0])).append(hex(rgb[1])).append(hex(rgb[2])).append(';')
                if (s.bold) append("font-weight:bold;")
                if (s.underline) append("text-decoration:underline;")
            }
        }

        fun escape(t: String): String = buildString {
            for (c in t) when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\t' -> append("&#160;&#160;&#160;&#160;")
                else -> append(c)
            }
        }
    }
}
