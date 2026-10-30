package nexplorer.doc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocxToHtmlTest {
    private val ns = """xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" """ +
        """xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" """ +
        """xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main""""

    private fun docx(body: String, extra: Map<String, ByteArray> = emptyMap()): ByteArrayInputStream {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("word/document.xml"))
            z.write("""<?xml version="1.0"?><w:document $ns><w:body>$body</w:body></w:document>""".toByteArray())
            extra.forEach { (name, bytes) -> z.putNextEntry(ZipEntry(name)); z.write(bytes) }
        }
        return ByteArrayInputStream(out.toByteArray())
    }

    private fun run(text: String, bold: Boolean = false) =
        "<w:r>${if (bold) "<w:rPr><w:b/></w:rPr>" else ""}<w:t xml:space=\"preserve\">$text</w:t></w:r>"

    private fun numbered(text: String) =
        "<w:p><w:pPr><w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"1\"/></w:numPr></w:pPr>${run(text)}</w:p>"

    @Test
    fun paragraphAndBold() {
        val html = DocxToHtml.convert(docx("<w:p>${run("Hello ")}${run("world", bold = true)}</w:p>"))
        assertTrue(html, html.contains("<p dir=\"auto\">Hello <b>world</b></p>"))
    }

    @Test
    fun headingStyle() {
        val p = "<w:p><w:pPr><w:pStyle w:val=\"Heading1\"/></w:pPr>${run("Title")}</w:p>"
        assertTrue(DocxToHtml.convert(docx(p)).contains("<h1 dir=\"auto\">Title</h1>"))
    }

    @Test
    fun escapesText() {
        val html = DocxToHtml.convert(docx("<w:p>${run("a&lt;b&amp;c")}</w:p>"))
        assertTrue(html, html.contains("a&lt;b&amp;c"))
    }

    @Test
    fun bulletList() {
        val html = DocxToHtml.convert(docx(numbered("one") + numbered("two")))
        assertEquals(1, Regex("<ul>").findAll(html).count())
        assertTrue(html, html.contains("<ul><li dir=\"auto\">one</li><li dir=\"auto\">two</li></ul>"))
    }

    @Test
    fun tableCells() {
        val t = "<w:tbl><w:tr><w:tc><w:p>${run("A")}</w:p></w:tc><w:tc><w:p>${run("B")}</w:p></w:tc></w:tr></w:tbl>"
        val html = DocxToHtml.convert(docx(t))
        assertTrue(html, html.contains("<table><tr><td><p dir=\"auto\">A</p></td><td><p dir=\"auto\">B</p></td></tr></table>"))
    }

    @Test
    fun imageAsDataUri() {
        val rels = """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId5" Target="media/image1.png"/></Relationships>"""
        val p = "<w:p><w:r><w:drawing><a:blip r:embed=\"rId5\"/></w:drawing></w:r></w:p>"
        val html = DocxToHtml.convert(
            docx(p, mapOf("word/_rels/document.xml.rels" to rels.toByteArray(), "word/media/image1.png" to byteArrayOf(1, 2, 3))),
        )
        assertTrue(html, html.contains("src=\"data:image/png;base64,"))
    }

    @Test
    fun underlineStylesAndNone() {
        fun u(v: String) = "<w:p><w:r><w:rPr><w:u w:val=\"$v\"/></w:rPr><w:t>x</w:t></w:r></w:p>"
        assertTrue(DocxToHtml.convert(docx(u("single"))).contains("<u>x</u>"))
        assertTrue(DocxToHtml.convert(docx(u("double"))).contains("<u>x</u>"))
        assertTrue(!DocxToHtml.convert(docx(u("none"))).contains("<u>"))
    }

    @Test
    fun pictureKeepsItsWordWidth() {
        val rels = """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId5" Target="media/image1.png"/></Relationships>"""
        val p = "<w:p><w:r><w:drawing><wp:inline xmlns:wp=\"x\"><wp:extent cx=\"1828800\" cy=\"914400\"/>" +
            "<a:blip r:embed=\"rId5\"/></wp:inline></w:drawing></w:r></w:p>"
        val html = DocxToHtml.convert(
            docx(p, mapOf("word/_rels/document.xml.rels" to rels.toByteArray(), "word/media/image1.png" to byteArrayOf(1, 2, 3))),
        )
        assertTrue(html, html.contains("<img style=\"width:144pt\" src=\"data:image/png;base64,"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingDocumentXmlThrows() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> z.putNextEntry(ZipEntry("other.txt")); z.write(1) }
        DocxToHtml.convert(ByteArrayInputStream(out.toByteArray()))
    }
}
