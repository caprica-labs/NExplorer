package nexplorer.export

import android.graphics.Bitmap
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.StructuredText
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import nexplorer.doc.PdfLock
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Picture format for PDF to Image. */
enum class ImageFormat(val label: String, val ext: String) { Jpeg("JPG", "jpg"), Png("PNG", "png") }

object PdfExport {
    private const val MAX_PIXELS = 36_000_000f

    private inline fun <T> withDoc(src: File, password: String?, use: (Document) -> T): T {
        val doc = PdfLock.open(src.path, password)
        try {
            return use(doc)
        } finally {
            doc.destroy()
        }
    }

    fun pageCount(src: File, password: String?): Int = withDoc(src, password) { it.countPages() }

    fun toText(src: File, password: String?, out: File, onPage: (Int) -> Unit = {}) = withDoc(src, password) { doc ->
        out.parentFile?.mkdirs()
        out.bufferedWriter(Charsets.UTF_8).use { w ->
            for (i in 0 until doc.countPages()) {
                if (i > 0) w.write("\n\n")
                val page = doc.loadPage(i)
                try {
                    val st = page.toStructuredText()
                    try { w.write(st.asText().trimEnd()) } finally { st.destroy() }
                } finally {
                    page.destroy()
                }
                onPage(i + 1)
            }
        }
    }

    fun toWord(src: File, password: String?, out: File, onPage: (Int) -> Unit = {}) = withDoc(src, password) { doc ->
        val first = doc.loadPage(0).let { p -> try { p.bounds } finally { p.destroy() } }
        out.parentFile?.mkdirs()
        out.outputStream().buffered().use { stream ->
            val word = DocxWriter.Builder(stream, first.x1 - first.x0, first.y1 - first.y0)
            for (i in 0 until doc.countPages()) {
                val page = doc.loadPage(i)
                try {
                    val b = page.bounds
                    val st = page.toStructuredText()
                    val paras = try { paragraphs(st) } finally { st.destroy() }
                    val picture = if (paras.isEmpty()) {
                        val bmp = AndroidDrawDevice.drawPage(page, Matrix(150f / 72f))
                        val jpeg = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
                        bmp.recycle()
                        WPicture(jpeg, b.x1 - b.x0, b.y1 - b.y0)
                    } else {
                        null
                    }
                    word.add(WPage(paras, picture))
                } finally {
                    page.destroy()
                }
                onPage(i + 1)
            }
            word.finish()
        }
    }

    private fun paragraphs(st: StructuredText): List<WPara> = st.blocks.orEmpty().mapNotNull { block ->
        val lines = block.lines ?: return@mapNotNull null
        val runs = mutableListOf<WRun>()
        val text = StringBuilder()
        var style: WRun? = null
        fun flush() {
            val s = style ?: return
            if (text.isNotEmpty()) runs += s.copy(text = text.toString())
            text.clear()
        }
        lines.forEachIndexed { li, line ->
            if (li > 0 && text.isNotEmpty() && !text.endsWith("-") && !text.endsWith(" ")) text.append(' ')
            for (ch in line.chars.orEmpty()) {
                val q = ch.quad
                val h = sqrt((q.ll_x - q.ul_x) * (q.ll_x - q.ul_x) + (q.ll_y - q.ul_y) * (q.ll_y - q.ul_y))
                val rgb = ch.argb and 0xFFFFFF
                val s = WRun(
                    text = "",
                    bold = ch.flags and StructuredText.CHAR_FLAGS_BOLD != 0,
                    underline = ch.flags and StructuredText.CHAR_FLAGS_UNDERLINE != 0,
                    color = rgb.takeIf { it != 0 },
                    sizePt = (h / 1.15f * 2).roundToInt() / 2f,
                )
                if (style == null || !sameLook(style!!, s)) { flush(); style = s }
                text.appendCodePoint(ch.c)
            }
        }
        flush()
        val all = runs.joinToString("") { it.text }
        if (all.isBlank()) null else WPara(runs, rtl = isRtl(all))
    }

    private fun sameLook(a: WRun, b: WRun) =
        a.bold == b.bold && a.underline == b.underline && a.color == b.color && abs((a.sizePt ?: 0f) - (b.sizePt ?: 0f)) < 1f

    fun isRtl(text: String): Boolean {
        var rtl = 0
        var ltr = 0
        text.forEach { c ->
            when (Character.getDirectionality(c)) {
                Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> rtl++
                Character.DIRECTIONALITY_LEFT_TO_RIGHT -> ltr++
            }
        }
        return rtl > ltr
    }

    fun toImages(src: File, password: String?, out: File, format: ImageFormat, dpi: Int, onPage: (Int) -> Unit = {}): List<File> =
        withDoc(src, password) { doc ->
            val count = doc.countPages()
            val digits = count.toString().length.coerceAtLeast(2)
            if (count > 1) out.mkdirs() else out.parentFile?.mkdirs()
            (0 until count).map { i ->
                val page = doc.loadPage(i)
                val file = if (count > 1) File(out, "page-${(i + 1).toString().padStart(digits, '0')}.${format.ext}") else out
                try {
                    val b = page.bounds
                    var k = dpi / 72f
                    val px = (b.x1 - b.x0) * k * (b.y1 - b.y0) * k
                    if (px > MAX_PIXELS) k *= sqrt(MAX_PIXELS / px)
                    val bmp = AndroidDrawDevice.drawPage(page, Matrix(k))
                    try {
                        file.outputStream().buffered().use {
                            if (format == ImageFormat.Png) bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                            else bmp.compress(Bitmap.CompressFormat.JPEG, 92, it)
                        }
                    } finally {
                        bmp.recycle()
                    }
                } finally {
                    page.destroy()
                }
                onPage(i + 1)
                file
            }
        }
}
