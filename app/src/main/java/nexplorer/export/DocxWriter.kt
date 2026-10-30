package nexplorer.export

import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

data class WRun(val text: String, val bold: Boolean = false, val underline: Boolean = false, val color: Int? = null, val sizePt: Float? = null)

data class WPara(val runs: List<WRun>, val rtl: Boolean = false)

class WPicture(val jpeg: ByteArray, val widthPt: Float, val heightPt: Float)

/** One PDF page's worth of content. */
data class WPage(val paras: List<WPara>, val picture: WPicture? = null)

object DocxWriter {
    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val EMU_PER_PT = 12700
    private const val MARGIN_PT = 72f

    fun write(pages: List<WPage>, pageWPt: Float, pageHPt: Float, out: OutputStream) {
        val builder = Builder(out, pageWPt, pageHPt)
        pages.forEach(builder::add)
        builder.finish()
    }

    class Builder(out: OutputStream, private val pageWPt: Float, private val pageHPt: Float) {
        private val zip = ZipOutputStream(out)
        private val bodyFile = File.createTempFile("docx-body", ".xml")
        private val body = bodyFile.bufferedWriter(Charsets.UTF_8)
        private var pages = 0
        private var pictures = 0

        fun add(page: WPage) {
            if (pages++ > 0) body.write("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
            page.picture?.let { pic ->
                pictures++
                zip.putNextEntry(ZipEntry("word/media/page$pictures.jpeg")); zip.write(pic.jpeg); zip.closeEntry()
                body.write(picture(pictures, pic, pageWPt - 2 * MARGIN_PT, pageHPt - 2 * MARGIN_PT))
            }
            page.paras.forEach { body.write(para(it)) }
        }

        fun finish() {
            try {
                val pgW = (pageWPt * 20).roundToInt()
                val pgH = (pageHPt * 20).roundToInt()
                val margin = (MARGIN_PT * 20).roundToInt()
                body.write("<w:sectPr><w:pgSz w:w=\"$pgW\" w:h=\"$pgH\"/><w:pgMar w:top=\"$margin\" w:right=\"$margin\" w:bottom=\"$margin\" w:left=\"$margin\" w:header=\"0\" w:footer=\"0\" w:gutter=\"0\"/></w:sectPr>")
                body.close()
                fun put(name: String, text: String) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
                put("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="jpeg" ContentType="image/jpeg"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/></Types>""")
                put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""")
                val rels = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rIdStyles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
                for (i in 1..pictures) {
                    rels.append("<Relationship Id=\"rIdImg$i\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/page$i.jpeg\"/>")
                }
                rels.append("</Relationships>")
                put("word/_rels/document.xml.rels", rels.toString())
                put("word/styles.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:styles xmlns:w="$W"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Arial" w:hAnsi="Arial" w:cs="Arial" w:eastAsia="Arial"/><w:sz w:val="22"/><w:szCs w:val="22"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="120"/></w:pPr></w:pPrDefault></w:docDefaults></w:styles>""")
                zip.putNextEntry(ZipEntry("word/document.xml"))
                zip.write(
                    ("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="$W" xmlns:r="$R" """ +
                        """xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" """ +
                        """xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" """ +
                        """xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"><w:body>""").toByteArray(),
                )
                bodyFile.inputStream().use { it.copyTo(zip) }
                zip.write("</w:body></w:document>".toByteArray())
                zip.closeEntry()
                zip.close()
            } finally {
                runCatching { body.close() }
                bodyFile.delete()
            }
        }
    }

    private fun para(p: WPara): String = buildString {
        append("<w:p>")
        if (p.rtl) append("<w:pPr><w:bidi/></w:pPr>")
        for (r in p.runs) {
            if (r.text.isEmpty()) continue
            append("<w:r><w:rPr>")
            if (r.bold) append("<w:b/><w:bCs/>")
            if (r.underline) append("<w:u w:val=\"single\"/>")
            r.color?.let { append("<w:color w:val=\"").append("%06X".format(it and 0xFFFFFF)).append("\"/>") }
            r.sizePt?.let { val hp = (it * 2).roundToInt().coerceIn(8, 400); append("<w:sz w:val=\"$hp\"/><w:szCs w:val=\"$hp\"/>") }
            if (p.rtl) append("<w:rtl/>")
            append("</w:rPr><w:t xml:space=\"preserve\">").append(escape(r.text)).append("</w:t></w:r>")
        }
        append("</w:p>")
    }

    private fun picture(n: Int, pic: WPicture, maxW: Float, maxH: Float): String {
        val k = minOf(1f, maxW / pic.widthPt, maxH / pic.heightPt)
        val cx = (pic.widthPt * k * EMU_PER_PT).toLong()
        val cy = (pic.heightPt * k * EMU_PER_PT).toLong()
        return "<w:p><w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">" +
            "<wp:extent cx=\"$cx\" cy=\"$cy\"/><wp:docPr id=\"$n\" name=\"Page $n\"/>" +
            "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
            "<pic:pic><pic:nvPicPr><pic:cNvPr id=\"$n\" name=\"page$n.jpeg\"/><pic:cNvPicPr/></pic:nvPicPr>" +
            "<pic:blipFill><a:blip r:embed=\"rIdImg$n\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
            "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>" +
            "</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"
    }

    fun escape(t: String): String = buildString {
        t.forEach { c ->
            when {
                c == '&' -> append("&amp;")
                c == '<' -> append("&lt;")
                c == '>' -> append("&gt;")
                c == '\t' || c == '\n' || c == '\r' -> append(' ')
                c < ' ' || c == '￾' || c == '￿' -> Unit
                else -> append(c)
            }
        }
    }
}
