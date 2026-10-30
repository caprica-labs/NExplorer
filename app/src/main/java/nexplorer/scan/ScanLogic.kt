package nexplorer.scan

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class P(val x: Float, val y: Float)

data class Quad(val points: List<P>) {
    fun handles(): List<P> = points + (0 until 4).map { i ->
        val a = points[i]
        val b = points[(i + 1) % 4]
        P((a.x + b.x) / 2, (a.y + b.y) / 2)
    }

    fun moveCorner(i: Int, x: Float, y: Float): Quad =
        Quad(points.toMutableList().also { it[i] = P(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f)) })

    fun moveSide(side: Int, dx: Float, dy: Float): Quad {
        val a = side
        val b = (side + 1) % 4
        val pa = points[a]
        val pb = points[b]
        val sx = dx.coerceIn(-minOf(pa.x, pb.x), 1f - maxOf(pa.x, pb.x))
        val sy = dy.coerceIn(-minOf(pa.y, pb.y), 1f - maxOf(pa.y, pb.y))
        return Quad(points.toMutableList().also {
            it[a] = P(pa.x + sx, pa.y + sy)
            it[b] = P(pb.x + sx, pb.y + sy)
        })
    }

    fun isFull() = this == FULL

    companion object {
        val FULL = Quad(listOf(P(0f, 0f), P(1f, 0f), P(1f, 1f), P(0f, 1f)))
    }
}

object Orient {
    fun toDisplay(p: P, rotation: Int, mirrored: Boolean): P {
        val r = when (((rotation % 360) + 360) % 360) {
            90 -> P(1 - p.y, p.x)
            180 -> P(1 - p.x, 1 - p.y)
            270 -> P(p.y, 1 - p.x)
            else -> p
        }
        return if (mirrored) P(1 - r.x, r.y) else r
    }

    fun fromDisplay(p: P, rotation: Int, mirrored: Boolean): P {
        val m = if (mirrored) P(1 - p.x, p.y) else p
        return when (((rotation % 360) + 360) % 360) {
            90 -> P(m.y, 1 - m.x)
            180 -> P(1 - m.x, 1 - m.y)
            270 -> P(1 - m.y, m.x)
            else -> m
        }
    }
}

enum class Filter(val label: String, val matrix: FloatArray?) {
    Original("Original", null),
    Enhance(
        "Enhance",
        floatArrayOf(
            1.35f, 0f, 0f, 0f, -20f,
            0f, 1.35f, 0f, 0f, -20f,
            0f, 0f, 1.35f, 0f, -20f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
    Grayscale(
        "Grayscale",
        floatArrayOf(
            0.299f, 0.587f, 0.114f, 0f, 0f,
            0.299f, 0.587f, 0.114f, 0f, 0f,
            0.299f, 0.587f, 0.114f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
    BlackWhite(
        "B&W",
        run {
            val k = 12f
            val off = -128f * k + 128f - 30f * k
            floatArrayOf(
                0.299f * k, 0.587f * k, 0.114f * k, 0f, off,
                0.299f * k, 0.587f * k, 0.114f * k, 0f, off,
                0.299f * k, 0.587f * k, 0.114f * k, 0f, off,
                0f, 0f, 0f, 1f, 0f,
            )
        },
    ),
    Lighten(
        "Lighten",
        floatArrayOf(
            1.1f, 0f, 0f, 0f, 35f,
            0f, 1.1f, 0f, 0f, 35f,
            0f, 0f, 1.1f, 0f, 35f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
}

data class PageSize(val name: String, val w: Float, val h: Float, val dims: String = "")

data class Placement(val pageW: Float, val pageH: Float, val x: Float, val y: Float, val w: Float, val h: Float)

object PageSizes {
    private fun num(v: Float) = if (v % 1f == 0f) v.toInt().toString() else v.toString()
    private fun mm(name: String, w: Float, h: Float) = PageSize(name, w * 72f / 25.4f, h * 72f / 25.4f, "${num(w)} × ${num(h)} mm")
    private fun inch(name: String, w: Float, h: Float) = PageSize(name, w * 72f, h * 72f, "${num(w)} × ${num(h)} in")

    val FIT = PageSize("Fit to image", 0f, 0f)

    val ALL: List<PageSize> = listOf(FIT) +
        listOf(
            mm("A0", 841f, 1189f), mm("A1", 594f, 841f), mm("A2", 420f, 594f), mm("A3", 297f, 420f),
            mm("A4", 210f, 297f), mm("A5", 148f, 210f), mm("A6", 105f, 148f), mm("A7", 74f, 105f),
            mm("A8", 52f, 74f), mm("A9", 37f, 52f), mm("A10", 26f, 37f),
            mm("B0", 1000f, 1414f), mm("B1", 707f, 1000f), mm("B2", 500f, 707f), mm("B3", 353f, 500f),
            mm("B4", 250f, 353f), mm("B5", 176f, 250f), mm("B6", 125f, 176f), mm("B7", 88f, 125f),
            mm("B8", 62f, 88f), mm("B9", 44f, 62f), mm("B10", 31f, 44f),
            mm("C4", 229f, 324f), mm("C5", 162f, 229f), mm("C6", 114f, 162f),
        ) +
        // North American sizes.
        listOf(
            inch("Letter", 8.5f, 11f), inch("Legal", 8.5f, 14f), inch("Tabloid", 11f, 17f),
            inch("Ledger", 17f, 11f), inch("Executive", 7.25f, 10.5f), inch("Statement", 5.5f, 8.5f),
            inch("Half Letter", 5.5f, 8.5f), inch("Government Letter", 8f, 10.5f),
            inch("Government Legal", 8.5f, 13f), inch("Junior Legal", 5f, 8f),
            inch("ANSI C", 17f, 22f), inch("ANSI D", 22f, 34f), inch("ANSI E", 34f, 44f),
        ) +
        // Japanese and other common sizes.
        listOf(
            mm("JIS B4", 257f, 364f), mm("JIS B5", 182f, 257f), mm("JIS B6", 128f, 182f),
            mm("Folio", 210f, 330f), mm("Quarto", 229f, 279f),
            inch("Index card 3×5", 3f, 5f), inch("Index card 4×6", 4f, 6f), inch("Index card 5×8", 5f, 8f),
        )

    val DEFAULT: PageSize = ALL.first { it.name == "A4" }

    fun place(imageW: Int, imageH: Int, size: PageSize): Placement {
        if (size == FIT || size.w <= 0f) {
            val w = 595f
            val h = w * imageH / imageW
            return Placement(w, h, 0f, 0f, w, h)
        }
        val landscape = imageW > imageH
        val pw = if (landscape) maxOf(size.w, size.h) else minOf(size.w, size.h)
        val ph = if (landscape) minOf(size.w, size.h) else maxOf(size.w, size.h)
        val scale = minOf(pw / imageW, ph / imageH)
        val w = imageW * scale
        val h = imageH * scale
        return Placement(pw, ph, (pw - w) / 2, (ph - h) / 2, w, h)
    }
}

fun pdfNum(v: Float): String =
    java.math.BigDecimal(v.toDouble()).setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

object Lists {
    fun <T> move(list: List<T>, from: Int, to: Int): List<T> {
        if (from == to) return list
        val m = list.toMutableList()
        val item = m.removeAt(from)
        m.add(to, item)
        return m
    }

    fun <T> togglePick(picks: List<T>, item: T): List<T> = if (item in picks) picks - item else picks + item
}

object ScanNames {
    private val format = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm")

    fun default(now: LocalDateTime = LocalDateTime.now(), prefix: String = "NExplorer") = "${prefix}_${now.format(format)}.pdf"

    fun withExtension(name: String, ext: String): String {
        val n = name.trim().ifEmpty { "NExplorer" }
        return if (ext.isEmpty() || n.lowercase().endsWith(".${ext.lowercase()}")) n else "$n.$ext"
    }
}

object SizeEstimate {
    const val MAX_SIDE = 4096

    const val PAGE_OVERHEAD = 400L
    const val FILE_OVERHEAD = 1000L

    fun total(perPage: List<Map<Int, Long>>, qualities: List<Int>): Map<Int, Long> =
        qualities.associateWith { q -> perPage.sumOf { it[q] ?: 0L } + perPage.size * PAGE_OVERHEAD + FILE_OVERHEAD }

    fun label(bytes: Long): String {
        val kb = bytes / 1024.0
        return if (kb < 1000) "≈ ${maxOf(1, kb.toInt())} KB" else "≈ %.1f MB".format(kb / 1024)
    }
}
