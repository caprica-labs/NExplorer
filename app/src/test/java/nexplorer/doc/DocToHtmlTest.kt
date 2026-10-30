package nexplorer.doc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class DocToHtmlTest {
    private val html by lazy {
        javaClass.classLoader!!.getResourceAsStream("doctest.doc")!!.use { DocToHtml.convert(it) }
    }

    @Test
    fun headingsAndText() {
        assertTrue(html, Regex("<h1[^>]*>(<b>)?Old Word test(</b>)?</h1>").containsMatchIn(html))
        assertTrue(html, Regex("<h2[^>]*>(<b>)?Lists(</b>)?</h2>").containsMatchIn(html))
        assertTrue(html, html.contains("Last line."))
    }

    @Test
    fun characterFormatting() {
        assertTrue(html, html.contains("<b>bold</b>"))
        assertTrue(html, html.contains("<i>italic</i>"))
        assertTrue(html, html.contains("<u>underlined</u>"))
        assertTrue(html, html.contains("<sub>2</sub>"))
        assertTrue(html, html.contains("<sup>2</sup>"))
        assertTrue(html, Regex("font-size:\\d\\.\\d\\dem\">big<").containsMatchIn(html))
    }

    @Test
    fun otherScriptsAndSymbols() {
        assertTrue(html, html.contains("∮ ℝ ∀x∈ℕ ≤ ≠ ∞ α β γ"))
        assertTrue(html, html.contains("مرحبا"))
        assertTrue(html, html.contains("你好"))
    }

    @Test
    fun lists() {
        assertTrue(html, html.contains("<ul><li dir=\"auto\">Bullet one</li><li dir=\"auto\">Bullet two</li></ul>"))
        assertTrue(html, html.contains("<ol><li dir=\"auto\">Number one</li><li dir=\"auto\">Number two</li></ol>"))
    }

    @Test
    fun table() {
        val t = html.substringAfter("<table>").substringBefore("</table>")
        assertEquals(t, 2, Regex("<tr>").findAll(t).count())
        assertEquals(t, 4, Regex("<td>").findAll(t).count())
        assertTrue(t, t.contains("B2 <b>bold</b>"))
    }

    @Test
    fun linkShowsItsTextNotItsCode() {
        assertTrue(html, html.contains("A link: <u>example.com</u> and a picture:"))
        assertTrue(html, !html.contains("HYPERLINK"))
    }

    @Test
    fun picture() {
        assertTrue(html, Regex("<img style=\"width:\\d+pt\" src=\"data:image/png;base64,iVBOR").containsMatchIn(html))
    }

    @Test(expected = IllegalArgumentException::class)
    fun notAWordFile() {
        DocToHtml.convert(ByteArrayInputStream(ByteArray(1024)))
    }
}
