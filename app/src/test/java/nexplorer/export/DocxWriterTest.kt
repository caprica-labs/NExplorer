package nexplorer.export

import nexplorer.doc.DocxToHtml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class DocxWriterTest {
    private fun docx(pages: List<WPage>): ByteArray =
        ByteArrayOutputStream().also { DocxWriter.write(pages, 595f, 842f, it) }.toByteArray()

    private fun parts(bytes: ByteArray): Map<String, String> {
        val m = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) { val e = z.nextEntry ?: break; m[e.name] = String(z.readBytes()) }
        }
        return m
    }

    @Test
    fun readsBackWithStyles() {
        val bytes = docx(listOf(WPage(listOf(WPara(listOf(WRun("Hello "), WRun("bold", bold = true), WRun(" and "), WRun("under", underline = true)))))))
        val html = DocxToHtml.convert(ByteArrayInputStream(bytes))
        assertTrue(html, html.contains("Hello <b>bold</b> and <u>under</u>"))
    }

    @Test
    fun pageBreaksRtlAndPictures() {
        val pic = WPicture(byteArrayOf(1, 2, 3), 595f, 842f)
        val bytes = docx(
            listOf(
                WPage(listOf(WPara(listOf(WRun("سلام")), rtl = true))),
                WPage(emptyList(), picture = pic),
            ),
        )
        val p = parts(bytes)
        val doc = p.getValue("word/document.xml")
        assertTrue(doc.contains("<w:bidi/>"))
        assertTrue(doc.contains("<w:rtl/>"))
        assertEquals(1, Regex("w:type=\"page\"").findAll(doc).count())
        assertTrue(p.containsKey("word/media/page1.jpeg"))
        assertTrue(p.getValue("word/_rels/document.xml.rels").contains("media/page1.jpeg"))
        // A4 in twentieths of a point.
        assertTrue(doc.contains("<w:pgSz w:w=\"11900\" w:h=\"16840\"/>"))
    }

    @Test
    fun escapesAndDropsBadCharacters() {
        assertEquals("a&lt;b&amp;c d", DocxWriter.escape("a<b&c\td\u0001"))
    }

    @Test
    fun colorAndSize() {
        val doc = parts(docx(listOf(WPage(listOf(WPara(listOf(WRun("x", color = 0xE53935, sizePt = 14f)))))))).getValue("word/document.xml")
        assertTrue(doc.contains("<w:color w:val=\"E53935\"/>"))
        assertTrue(doc.contains("<w:sz w:val=\"28\"/>"))
    }
}
