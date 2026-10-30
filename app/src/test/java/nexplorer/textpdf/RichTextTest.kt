package nexplorer.textpdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RichTextTest {
    private val plain = Style()
    private val bold = Style(bold = true)
    private val red = Style(color = 0)

    @Test
    fun typingAddsCharactersInTheTypingStyle() {
        val t = RichText.plain("Hello", plain).edit("Hello world", bold)
        assertEquals(listOf(Run(5, plain), Run(6, bold)), t.runs)
    }

    @Test
    fun typingInTheMiddleSplitsTheRun() {
        val t = RichText.plain("abcd", plain).edit("abXcd", red)
        assertEquals(listOf(Run(2, plain), Run(1, red), Run(2, plain)), t.runs)
    }

    @Test
    fun deletingKeepsTheRestsStyles() {
        val t = RichText.of("abcd", listOf(plain, bold, bold, plain)).edit("ad", plain)
        assertEquals(listOf(Run(2, plain)), t.runs)
        val u = RichText.of("abcd", listOf(plain, bold, bold, plain)).edit("abd", plain)
        assertEquals(listOf(Run(1, plain), Run(1, bold), Run(1, plain)), u.runs)
    }

    @Test
    fun repeatedLettersStillDiffCorrectly() {
        val t = RichText.of("aa", listOf(bold, plain)).edit("a", plain)
        assertEquals("a", t.text)
        assertEquals(1, t.runs.sumOf { it.length })
    }

    @Test
    fun replacingASelection() {
        val t = RichText.plain("one two three", plain).edit("one 2 three", bold)
        assertEquals(listOf(Run(4, plain), Run(1, bold), Run(6, plain)), t.runs)
    }

    @Test
    fun restyleOnlyTheSelection() {
        val t = RichText.plain("Hello world", plain).restyle(6, 11) { it.copy(bold = true) }
        assertEquals(listOf(Run(6, plain), Run(5, bold)), t.runs)
        assertEquals(listOf(bold, bold), t.stylesIn(6, 8))
    }

    @Test
    fun styleBeforeTheCursor() {
        val t = RichText.of("ab", listOf(plain, bold))
        assertEquals(plain, t.styleBefore(0))
        assertEquals(plain, t.styleBefore(1))
        assertEquals(bold, t.styleBefore(2))
        assertEquals(null, RichText.EMPTY.styleBefore(0))
    }

    @Test
    fun htmlKeepsLinesStylesAndEscapes() {
        val t = RichText.of("a<b\n\nc", listOf(bold, bold, bold, plain, plain, red))
        val html = t.toHtml()
        assertTrue(html, html.contains("font-weight:bold;\">a&lt;b</span>"))
        assertEquals(3, Regex("<p dir=\"auto\">").findAll(html).count())
        assertTrue(html, html.contains("&#160;"))
        assertTrue(html, html.contains("color:#e63836;"))
    }

    @Test
    fun cssForEveryOption() {
        assertEquals("font-size:18pt;color:#000000;font-weight:bold;text-decoration:underline;",
            RichText.css(Style(size = 18f, bold = true, underline = true)))
    }
}

class RichTextSpeedTest {
    @Test
    fun longTextsStayQuick() {
        var t = RichText.plain("x".repeat(200_000))
        val started = System.nanoTime()
        repeat(500) { i -> t = t.edit(t.text + "y", if (i % 2 == 0) Style(bold = true) else Style()) }
        val ms = (System.nanoTime() - started) / 1_000_000
        org.junit.Assert.assertEquals(200_500, t.text.length)
        org.junit.Assert.assertTrue("took $ms ms", ms < 3000)
    }
}
