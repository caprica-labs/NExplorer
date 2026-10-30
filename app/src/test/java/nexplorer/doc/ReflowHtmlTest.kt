package nexplorer.doc

import org.junit.Assert.assertTrue
import org.junit.Test

class ReflowHtmlTest {
    @Test
    fun joinsLinesIntoParagraphs() {
        val html = ReflowHtml.fromPageTexts(listOf("Line one\nline two\n\nNext para"))
        assertTrue(html, html.contains("<p>Line one line two</p><p>Next para</p>"))
    }

    @Test
    fun escapesAndSeparatesPages() {
        val html = ReflowHtml.fromPageTexts(listOf("a<b", "c&d"))
        assertTrue(html, html.contains("<p>a&lt;b</p>"))
        assertTrue(html, html.contains("<p>c&amp;d</p>"))
    }

    @Test
    fun blankPagesSkipped() {
        val html = ReflowHtml.fromPageTexts(listOf("   ", "x"))
        assertTrue(html, html.contains("<body><p>x</p></body>"))
    }
}
