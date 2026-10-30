package nexplorer.doc

import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.util.Base64

object WordHtml {
    fun of(file: File, css: String = ""): String = file.inputStream().use {
        if (file.name.endsWith(".doc", ignoreCase = true)) DocToHtml.convert(it, css) else DocxToHtml.convert(it, css)
    }
}

object DocToHtml {
    class Unsupported(message: String) : IllegalArgumentException(message)

    fun convert(input: InputStream, css: String = ""): String {
        val cfb = Cfb(input.readBytes())
        val word = cfb.stream("WordDocument") ?: throw IllegalArgumentException("Not a Word document")
        require(word.size >= 0x200 && word.u16(0) == 0xA5EC) { "Not a Word document" }
        if (word.u16(2) < 0x6A) throw Unsupported("Word 95 and older files aren't supported")
        val flags = word.u16(0x0A)
        if (flags and 0x0100 != 0) throw Unsupported("This Word file is password-protected")
        val table = cfb.stream(if (flags and 0x0200 != 0) "1Table" else "0Table")
            ?: throw IllegalArgumentException("Not a Word document")
        return Converter(Fib(word), word, table, cfb.stream("Data")).html(css)
    }

    private class Fib(private val words: ByteArray) {
        private val lw = 34 + words.u16(32) * 2 + 2
        private val count = words.u16(lw + words.u16(lw - 2) * 4)
        private val fcLcb = lw + words.u16(lw - 2) * 4 + 2

        val ccpText get() = words.i32(lw + 3 * 4)

        fun at(i: Int): Pair<Int, Int> = if (i < count) words.i32(fcLcb + i * 8) to words.i32(fcLcb + i * 8 + 4) else 0 to 0

        companion object {
            const val STSHF = 1
            const val FFN = 15
            const val BTE_CHPX = 12
            const val BTE_PAPX = 13
            const val CLX = 33
            const val PLF_LST = 73
            const val PLF_LFO = 74
        }
    }

    // ---- Formatting ----

    private class Style(val sti: Int, val base: Int, val papx: ByteArray, val chpx: ByteArray)

    private class Para {
        var istd = 0
        var inTable = false
        var rowEnd = false
        var ilfo = 0
        var ilvl = 0
        var jc = 0
    }

    private data class Chars(
        var bold: Boolean = false,
        var italic: Boolean = false,
        var underline: Boolean = false,
        var strike: Boolean = false,
        var vert: Int = 0,
        var hps: Int = 20,
        var hidden: Boolean = false,
        var spec: Boolean = false,
        var data: Boolean = false,
        var picAt: Int = -1,
        var font: Int = -1,
        var symbol: Int = -1,
    )

    private class Runs<T>(items: List<Triple<Int, Int, T>>) {
        private val sorted = items.sortedBy { it.first }
        private val starts = IntArray(sorted.size) { sorted[it].first }

        fun at(fc: Int): T? {
            var i = java.util.Arrays.binarySearch(starts, fc)
            if (i < 0) i = -i - 2
            val r = sorted.getOrNull(i) ?: return null
            return if (fc < r.second) r.third else null
        }
    }

    private class Converter(private val fib: Fib, private val w: ByteArray, private val t: ByteArray, private val data: ByteArray?) {
        private val styles = styles()
        private val fonts = fonts()
        private val listKinds = lists()
        private val chpx = chpxRuns()
        private val papx = papxRuns()
        private val normalHps = chars(0, EMPTY).hps.takeIf { it > 0 } ?: 20

        fun html(css: String): String {
            val n = fib.ccpText.coerceIn(0, MAX_CHARS)
            val text = CharArray(n)
            val fcs = IntArray(n)
            pieces(text, fcs)

            val out = StringBuilder()
            var openList: String? = null
            fun closeList() { openList?.let { out.append("</$it>") }; openList = null }
            var inTable = false
            var rowOpen = false
            var cellOpen = false
            val fields = ArrayList<Boolean>()

            var start = 0
            for (end in 0 until n) {
                val c = text[end]
                if (c != '\r' && c != '\u0007' && c != '\u000C') continue
                val para = para(papx.at(fcs[end]))
                val inner = StringBuilder()
                inline(text, fcs, start, end, para, fields, inner)
                start = end + 1

                if (para.inTable) {
                    if (!inTable) { closeList(); out.append("<table>"); inTable = true }
                    if (para.rowEnd) {
                        if (cellOpen) { closeList(); out.append("</td>"); cellOpen = false }
                        if (rowOpen) out.append("</tr>")
                        rowOpen = false
                        continue
                    }
                    if (!rowOpen) { out.append("<tr>"); rowOpen = true }
                    if (!cellOpen) { out.append("<td>"); cellOpen = true }
                } else if (inTable) {
                    if (cellOpen) { closeList(); out.append("</td>") }
                    if (rowOpen) out.append("</tr>")
                    out.append("</table>")
                    inTable = false; rowOpen = false; cellOpen = false
                }

                val align = when (para.jc) { 1 -> " style=\"text-align:center\""; 2 -> " style=\"text-align:right\""; else -> "" }
                val heading = heading(para.istd)
                val list = if (heading == null && para.ilfo > 0) listKind(para.ilfo, para.ilvl) else NONE
                if (list != NONE) {
                    val tag = if (list == NUMBERED) "ol" else "ul"
                    if (openList != tag) { closeList(); out.append("<$tag>"); openList = tag }
                    out.append("<li dir=\"auto\"$align>").append(inner).append("</li>")
                } else {
                    closeList()
                    val tag = heading?.let { "h$it" } ?: "p"
                    out.append("<$tag dir=\"auto\"$align>").append(inner).append("</$tag>")
                }
                if (c == '\u0007' && cellOpen) { closeList(); out.append("</td>"); cellOpen = false }
            }
            closeList()
            if (inTable) {
                if (cellOpen) out.append("</td>")
                if (rowOpen) out.append("</tr>")
                out.append("</table>")
            }
            return DocxToHtml.wrap(out.toString(), css)
        }

        private fun inline(text: CharArray, fcs: IntArray, from: Int, to: Int, para: Para, fields: ArrayList<Boolean>, sb: StringBuilder) {
            val heading = heading(para.istd) != null
            var lastRun: ByteArray? = null
            var props = Chars()
            var open: Chars? = null
            val seg = StringBuilder()

            fun flush() {
                val p = open ?: return
                if (seg.isNotEmpty()) {
                    val tags = listOfNotNull(
                        "b".takeIf { p.bold }, "i".takeIf { p.italic }, "u".takeIf { p.underline }, "s".takeIf { p.strike },
                        when (p.vert) { 1 -> "sup"; 2 -> "sub"; else -> null },
                    )
                    val em = if (heading || p.hps == normalHps || p.hps <= 0) null else p.hps.toDouble() / normalHps
                    if (em != null) sb.append("<span style=\"font-size:${"%.2f".format(java.util.Locale.ROOT, em)}em\">")
                    tags.forEach { sb.append("<$it>") }
                    sb.append(seg)
                    tags.asReversed().forEach { sb.append("</$it>") }
                    if (em != null) sb.append("</span>")
                }
                seg.setLength(0)
                open = null
            }

            for (i in from until to) {
                val c = text[i]
                when (c) {
                    '\u0013' -> { fields.add(false); continue }
                    '\u0014' -> { if (fields.isNotEmpty()) fields[fields.size - 1] = true; continue }
                    '\u0015' -> { if (fields.isNotEmpty()) fields.removeAt(fields.size - 1); continue }
                }
                if (fields.any { !it }) continue
                val run = chpx.at(fcs[i]) ?: EMPTY
                if (run !== lastRun) { props = chars(para.istd, run); lastRun = run }
                if (props.hidden) continue

                if (c == '\u0001' && props.spec) {
                    picture(props)?.let { flush(); sb.append(it) }
                    continue
                }
                if (c == '\u000B') { flush(); sb.append("<br/>"); continue }
                val piece: String = when {
                    props.spec && props.symbol >= 0 -> symbol(props.font, props.symbol)?.let(::escape) ?: continue
                    isSymbolFont(props.font) && (c.code in 0xF020..0xF0FF || c.code in 0x20..0xFF) ->
                        symbol(props.font, c.code)?.let(::escape) ?: continue
                    c == '\t' -> " "
                    c == '\u001E' -> "-"
                    c < ' ' || props.spec || c.code in 0xF000..0xF0FF -> continue
                    else -> escape(c)
                }
                if (open != props) { flush(); open = props.copy() }
                seg.append(piece)
            }
            flush()
        }

        private fun picture(p: Chars): String? {
            val d = data ?: return null
            if (p.data || p.picAt < 0 || p.picAt + 0x44 > d.size) return null
            val at = p.picAt
            val end = (at + d.i32(at)).coerceAtMost(d.size)
            var start = at + d.u16(at + 4)
            if (d.u16(at + 6) == 0x66) start += 1 + d.u8(start)
            val (type, bytes) = blip(d, start, end, 0) ?: return null
            val widthPt = d.u16(at + 28).toLong() * d.u16(at + 32) / 1000 / 20
            val size = if (widthPt > 0) " style=\"width:${widthPt}pt\"" else ""
            return "<img$size src=\"data:image/$type;base64,${Base64.getEncoder().encodeToString(bytes)}\"/>"
        }

        private fun blip(d: ByteArray, from: Int, to: Int, depth: Int): Pair<String, ByteArray>? {
            var p = from
            while (p + 8 <= to) {
                val verInst = d.u16(p)
                val type = d.u16(p + 2)
                val len = d.i32(p + 4)
                val body = p + 8
                val end = body + len
                if (len < 0 || end > to) return null
                when {
                    type == 0xF01D || type == 0xF02A || type == 0xF01E -> {
                        val ids = if ((verInst ushr 4) and 1 == 1) 32 else 16
                        val s = body + ids + 1
                        if (s < end) return (if (type == 0xF01E) "png" else "jpeg") to d.copyOfRange(s, end)
                    }
                    type == 0xF007 && len > 36 -> blip(d, body + 36 + d.u8(body + 33), end, depth + 1)?.let { return it }
                    verInst and 0xF == 0xF && depth < 8 -> blip(d, body, end, depth + 1)?.let { return it }
                }
                p = end
            }
            return null
        }

        private fun isSymbolFont(font: Int) = fonts.getOrNull(font).equals("Symbol", ignoreCase = true)

        private fun symbol(font: Int, code: Int): Char? =
            if (isSymbolFont(font)) SYMBOL.getOrNull((code and 0xFF) - 0x20)?.takeIf { it != ' ' || (code and 0xFF) == 0x20 } else null

        private fun heading(istd: Int): Int? {
            var s = istd
            repeat(10) {
                val st = styles.getOrNull(s) ?: return null
                if (st.sti in 1..9) return st.sti.coerceAtMost(6)
                if (st.sti == 62) return 1
                s = st.base
            }
            return null
        }

        private fun chain(istd: Int): List<Style> {
            val out = ArrayList<Style>()
            var s = istd
            while (out.size < 10) {
                val st = styles.getOrNull(s) ?: break
                out.add(st)
                s = st.base
            }
            return out.asReversed()
        }

        private fun para(run: Pair<Int, ByteArray>?): Para {
            val p = Para()
            val (istd, grpprl) = run ?: (0 to EMPTY)
            p.istd = istd
            chain(istd).forEach { applyPara(p, it.papx) }
            applyPara(p, grpprl)
            return p
        }

        private fun applyPara(p: Para, g: ByteArray) = sprms(g) { op, at ->
            when (op) {
                0x2416 -> p.inTable = g.u8(at) != 0
                0x2417 -> p.rowEnd = g.u8(at) != 0
                0x6649 -> if (g.i32(at) > 0) p.inTable = true
                0x460B -> p.ilfo = g.s16(at)
                0x260A -> p.ilvl = g.u8(at)
                0x2403, 0x2461 -> p.jc = g.u8(at)
            }
        }

        private fun chars(istdPara: Int, direct: ByteArray): Chars {
            val c = Chars()
            chain(istdPara).forEach { applyChars(c, it.chpx) }
            var charStyle = 0
            sprms(direct) { op, at -> if (op == 0x4A30) charStyle = direct.u16(at) }
            if (charStyle > 0) chain(charStyle).forEach { applyChars(c, it.chpx) }
            applyChars(c, direct)
            return c
        }

        private fun applyChars(c: Chars, g: ByteArray) = sprms(g) { op, at ->
            val v = g.u8(at)
            when (op) {
                0x0835 -> c.bold = toggle(c.bold, v)
                0x0836 -> c.italic = toggle(c.italic, v)
                0x0837 -> c.strike = toggle(c.strike, v)
                0x083C, 0x0800 -> c.hidden = toggle(c.hidden, v)
                0x2A3E -> c.underline = g.u8(at) != 0
                0x2A48 -> c.vert = g.u8(at)
                0x4A43 -> c.hps = g.u16(at)
                0x0855 -> c.spec = g.u8(at) != 0
                0x0806 -> c.data = g.u8(at) != 0
                0x6A03 -> c.picAt = g.i32(at)
                0x4A4F -> c.font = g.u16(at)
                0x6A09 -> { c.font = g.u16(at); c.symbol = g.u16(at + 2) }
            }
        }

        private fun listKind(ilfo: Int, ilvl: Int): Int = listKinds.getOrNull(ilfo - 1)?.let { it.getOrNull(ilvl) ?: it.firstOrNull() } ?: NONE

        // ---- Reading the tables ----

        private fun pieces(text: CharArray, fcs: IntArray) {
            val (fc, lcb) = fib.at(Fib.CLX)
            var p = fc
            val end = (fc + lcb).coerceAtMost(t.size)
            while (p < end) {
                when (t.u8(p)) {
                    1 -> p += 3 + t.u16(p + 1)
                    2 -> {
                        val size = t.i32(p + 1)
                        val base = p + 5
                        val count = (size - 4) / 12
                        for (i in 0 until count) {
                            val cpStart = t.i32(base + i * 4)
                            val cpEnd = t.i32(base + (i + 1) * 4)
                            val raw = t.i32(base + (count + 1) * 4 + i * 8 + 2)
                            val compressed = raw and 0x40000000 != 0
                            val pos = if (compressed) (raw and 0x3FFFFFFF) / 2 else raw and 0x3FFFFFFF
                            for (cp in maxOf(cpStart, 0) until minOf(cpEnd, text.size)) {
                                val k = cp - cpStart
                                val at = if (compressed) pos + k else pos + 2 * k
                                fcs[cp] = at
                                text[cp] = when {
                                    compressed -> if (at < w.size) CP1252[w.u8(at)] else ' '
                                    at + 1 < w.size -> w.u16(at).toChar()
                                    else -> ' '
                                }
                            }
                        }
                        return
                    }
                    else -> return
                }
            }
        }

        private fun chpxRuns(): Runs<ByteArray> = Runs(bte(Fib.BTE_CHPX).flatMap { off ->
            val crun = w.u8(off + 511)
            (0 until crun).map { j ->
                val b = w.u8(off + (crun + 1) * 4 + j)
                val g = if (b == 0) EMPTY else w.slice(off + b * 2 + 1, w.u8(off + b * 2))
                Triple(w.i32(off + j * 4), w.i32(off + (j + 1) * 4), g)
            }
        })

        private fun papxRuns(): Runs<Pair<Int, ByteArray>> = Runs(bte(Fib.BTE_PAPX).flatMap { off ->
            val cpara = w.u8(off + 511)
            (0 until cpara).map { j ->
                val b = w.u8(off + (cpara + 1) * 4 + j * 13)
                val value = if (b == 0) 0 to EMPTY else {
                    val o = off + b * 2
                    val cb = w.u8(o)
                    val (s, len) = if (cb != 0) (o + 1) to (2 * cb - 1) else (o + 2) to (2 * w.u8(o + 1))
                    w.u16(s) to w.slice(s + 2, len - 2)
                }
                Triple(w.i32(off + j * 4), w.i32(off + (j + 1) * 4), value)
            }
        })

        private fun bte(i: Int): List<Int> {
            val (fc, lcb) = fib.at(i)
            if (lcb < 4) return emptyList()
            val n = (lcb - 4) / 8
            return (0 until n).map { (t.i32(fc + (n + 1) * 4 + it * 4) and 0x3FFFFF) * 512 }.filter { it + 512 <= w.size }
        }

        private fun styles(): List<Style?> {
            val (fc, lcb) = fib.at(Fib.STSHF)
            if (lcb < 4) return emptyList()
            val cbStshi = t.u16(fc)
            val cstd = t.u16(fc + 2)
            val baseSize = t.u16(fc + 4)
            var p = fc + 2 + cbStshi
            val out = ArrayList<Style?>()
            repeat(cstd) {
                val cb = t.u16(p)
                p += 2
                out.add(if (cb == 0) null else runCatching { style(p, baseSize) }.getOrNull())
                p += cb
            }
            return out
        }

        private fun style(s: Int, baseSize: Int): Style {
            val sti = t.u16(s) and 0x0FFF
            val stk = t.u16(s + 2) and 0x000F
            val base = t.u16(s + 2) ushr 4
            val cupx = t.u16(s + 4) and 0x000F
            var q = s + baseSize
            q += 2 + t.u16(q) * 2 + 2
            val upx = ArrayList<ByteArray>()
            repeat(cupx) {
                val cb = t.u16(q)
                upx.add(t.slice(q + 2, cb))
                q += 2 + cb + (cb and 1)
            }
            return when (stk) {
                1 -> Style(sti, base, upx.getOrNull(0)?.let { if (it.size >= 2) it.copyOfRange(2, it.size) else EMPTY } ?: EMPTY, upx.getOrNull(1) ?: EMPTY)
                2 -> Style(sti, base, EMPTY, upx.getOrNull(0) ?: EMPTY)
                else -> Style(sti, base, EMPTY, EMPTY)
            }
        }

        private fun fonts(): List<String> {
            val (fc, lcb) = fib.at(Fib.FFN)
            if (lcb < 4) return emptyList()
            val count = t.u16(fc)
            var p = fc + 4
            return List(count) {
                val cb = t.u8(p)
                val name = StringBuilder()
                var q = p + 1 + 39
                while (q + 1 < p + 1 + cb && t.u16(q) != 0) { name.append(t.u16(q).toChar()); q += 2 }
                p += 1 + cb
                name.toString()
            }
        }

        private fun lists(): List<List<Int>> = runCatching {
            val (fcLst, lcbLst) = fib.at(Fib.PLF_LST)
            val (fcLfo, lcbLfo) = fib.at(Fib.PLF_LFO)
            if (lcbLst < 2 || lcbLfo < 4) return emptyList()
            val cLst = t.u16(fcLst)
            val byId = HashMap<Int, List<Int>>()
            var q = fcLst + 2 + cLst * 28
            for (i in 0 until cLst) {
                val o = fcLst + 2 + i * 28
                val levels = if (t.u8(o + 26) and 1 != 0) 1 else 9
                byId[t.i32(o)] = List(levels) {
                    val nfc = t.u8(q + 4)
                    q += 28 + t.u8(q + 24) + t.u8(q + 25)
                    q += 2 + t.u16(q) * 2
                    when (nfc) { 23 -> BULLET; 255 -> NONE; else -> NUMBERED }
                }
            }
            List(t.i32(fcLfo).coerceIn(0, 10000)) { byId[t.i32(fcLfo + 4 + it * 16)] ?: emptyList() }
        }.getOrDefault(emptyList())
    }

    private fun toggle(cur: Boolean, v: Int) = when (v) { 0 -> false; 1 -> true; 0x81 -> !cur; else -> cur }

    private fun escape(c: Char): String = when (c) { '&' -> "&amp;"; '<' -> "&lt;"; '>' -> "&gt;"; else -> c.toString() }

    private inline fun sprms(g: ByteArray, f: (op: Int, at: Int) -> Unit) {
        var i = 0
        while (i + 2 <= g.size) {
            val op = g.u16(i)
            i += 2
            val size = when (op ushr 13) {
                0, 1 -> 1
                2, 4, 5 -> 2
                3 -> 4
                7 -> 3
                else -> if (i >= g.size) break else if (op == 0xD608 || op == 0xD606) g.u16(i) + 1 else g.u8(i) + 1
            }
            if (i + size > g.size) break
            f(op, i)
            i += size
        }
    }

    private const val NONE = 0
    private const val BULLET = 1
    private const val NUMBERED = 2

    private const val MAX_CHARS = 20_000_000
    private val EMPTY = ByteArray(0)

    private val CP1252: CharArray = String(ByteArray(256) { it.toByte() }, Charset.forName("windows-1252"))
        .toCharArray().also { a -> for (i in a.indices) if (a[i] == '�') a[i] = i.toChar() }

    private const val SYMBOL =
        " !∀#∃%&∋()∗+,−./0123456789:;<=>?" +
            "≅ΑΒΧΔΕΦΓΗΙϑΚΛΜΝΟΠΘΡΣΤΥςΩΞΨΖ[∴]⊥_" +
            "‾αβχδεφγηιϕκλμνοπθρστυϖωξψζ{|}∼ " +
            "                                " +
            "€ϒ′≤⁄∞ƒ♣♦♥♠↔←↑→↓°±″≥×∝∂•÷≠≡≈…⏐⎯↵" +
            "ℵℑℜ℘⊗⊕∅∩∪⊃⊇⊄⊂⊆∈∉∠∇®©™∏√⋅¬∧∨⇔⇐⇑⇒⇓" +
            "◊〈®©™∑⎛⎜⎝⎡⎢⎣⎧⎨⎩⎪ 〉∫⌠⎮⌡⎞⎟⎠⎤⎥⎦⎫⎬⎭ "
}

private class Cfb(private val b: ByteArray) {
    private val sector: Int
    private val fat: IntArray
    private val miniFat: IntArray
    private val miniStream: ByteArray
    private val top = HashMap<String, Pair<Int, Int>>()

    init {
        require(b.size >= 512 && b.i32(0) == 0xE011CFD0.toInt() && b.i32(4) == 0xE11AB1A1.toInt()) { "Not a Word document" }
        sector = 1 shl b.u16(0x1E).coerceIn(7, 16)
        val perSector = sector / 4
        val fatSectors = ArrayList<Int>()
        for (i in 0 until 109) b.i32(0x4C + i * 4).takeIf { it >= 0 }?.let(fatSectors::add)
        var difat = b.i32(0x44)
        var guard = 0
        while (difat >= 0 && guard++ < 10_000 && offset(difat) + sector <= b.size) {
            val o = offset(difat)
            for (i in 0 until perSector - 1) b.i32(o + i * 4).takeIf { it >= 0 }?.let(fatSectors::add)
            difat = b.i32(o + (perSector - 1) * 4)
        }
        fat = IntArray(fatSectors.size * perSector) { i ->
            val o = offset(fatSectors[i / perSector]) + (i % perSector) * 4
            if (o + 4 <= b.size) b.i32(o) else -1
        }
        miniFat = read(b.i32(0x3C), Int.MAX_VALUE).let { m -> IntArray(m.size / 4) { m.i32(it * 4) } }
        val dir = read(b.i32(0x30), Int.MAX_VALUE)
        fun entry(i: Int) = i * 128
        val root = entry(0)
        miniStream = read(dir.i32(root + 0x74), dir.i32(root + 0x78))
        val todo = ArrayDeque<Int>().apply { add(dir.i32(root + 0x4C)) }
        val seen = HashSet<Int>()
        while (todo.isNotEmpty()) {
            val i = todo.removeFirst()
            if (i < 0 || entry(i) + 128 > dir.size || !seen.add(i)) continue
            val e = entry(i)
            val nameLen = dir.u16(e + 0x40).coerceIn(0, 64)
            val name = String(dir, e, (nameLen - 2).coerceAtLeast(0), Charsets.UTF_16LE)
            if (dir.u8(e + 0x42) == 2) top[name] = dir.i32(e + 0x74) to dir.i32(e + 0x78)
            todo.add(dir.i32(e + 0x44))
            todo.add(dir.i32(e + 0x48))
        }
    }

    fun stream(name: String): ByteArray? {
        val (start, size) = top[name] ?: return null
        if (size < 0) return null
        if (size >= 4096) return read(start, size)
        val out = ByteArray(size)
        var s = start
        var pos = 0
        var guard = 0
        while (pos < size && s >= 0 && guard++ <= miniFat.size) {
            val o = s * 64
            val len = minOf(64, size - pos, miniStream.size - o)
            if (len <= 0) break
            System.arraycopy(miniStream, o, out, pos, len)
            pos += len
            s = miniFat.getOrElse(s) { -1 }
        }
        return out
    }

    private fun offset(s: Int) = (s + 1) * sector

    private fun read(start: Int, size: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var s = start
        var guard = 0
        while (s >= 0 && out.size() < size && guard++ <= fat.size) {
            val o = offset(s)
            val len = minOf(sector, size - out.size(), b.size - o)
            if (len <= 0) break
            out.write(b, o, len)
            s = fat.getOrElse(s) { -1 }
        }
        return out.toByteArray()
    }
}

private fun ByteArray.u8(o: Int) = this[o].toInt() and 0xFF
private fun ByteArray.u16(o: Int) = u8(o) or (u8(o + 1) shl 8)
private fun ByteArray.s16(o: Int) = u16(o).toShort().toInt()
private fun ByteArray.i32(o: Int) = u16(o) or (u16(o + 2) shl 16)
private fun ByteArray.slice(o: Int, len: Int): ByteArray = copyOfRange(o, (o + len).coerceAtMost(size).coerceAtLeast(o))
