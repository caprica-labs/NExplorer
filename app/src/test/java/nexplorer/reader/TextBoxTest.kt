package nexplorer.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class TextBoxTest {
    private val page = 400f to 600f

    private val half: (String) -> Float = { it.length * 0.5f }

    @Test
    fun resizeFromBottomRightKeepsTopLeft() {
        val r = TextBox.Rect(100f, 100f, 200f, 50f)
        val out = TextBox.resizeBottomRight(r, rotation = 0f, fingerX = 350f, fingerY = 180f, minH = 20f)
        assertEquals(100f, out.x, 0.01f)
        assertEquals(100f, out.y, 0.01f)
        assertEquals(250f, out.w, 0.01f)
        assertEquals(80f, out.h, 0.01f)
    }

    @Test
    fun resizeNeverBelowMinimumOrTextHeight() {
        val r = TextBox.Rect(100f, 100f, 200f, 50f)
        val out = TextBox.resizeBottomRight(r, rotation = 0f, fingerX = 0f, fingerY = 0f, minH = 30f)
        assertEquals(TextBox.MIN_W, out.w, 0.01f)
        assertEquals(30f, out.h, 0.01f)
    }

    @Test
    fun resizeRotatedBoxKeepsItsTopLeftCornerOnScreen() {
        val r = TextBox.Rect(100f, 100f, 100f, 40f)
        val before = TextBox.corner(r, 90f, 0f, 0f)
        val out = TextBox.resizeBottomRight(r, rotation = 90f, fingerX = before.first - 60f, fingerY = before.second + 150f, minH = 20f)
        val after = TextBox.corner(out, 90f, 0f, 0f)
        assertEquals(before.first, after.first, 0.01f)
        assertEquals(before.second, after.second, 0.01f)
        assertEquals(150f, out.w, 0.01f)
        assertEquals(60f, out.h, 0.01f)
    }

    @Test
    fun moveStaysOnPage() {
        val r = TextBox.Rect(10f, 10f, 100f, 50f)
        assertEquals(TextBox.Rect(0f, 0f, 100f, 50f), TextBox.move(r, -50f, -50f, page.first, page.second))
        assertEquals(TextBox.Rect(300f, 550f, 100f, 50f), TextBox.move(r, 900f, 900f, page.first, page.second))
    }

    @Test
    fun newBoxStartsAtTapAndFitsPage() {
        val r = TextBox.newAt(390f, 590f, 12f, page.first, page.second)
        assertEquals(page.first, r.x + r.w, 0.01f)
        assertEquals(page.second, r.y + r.h, 0.01f)
    }

    @Test
    fun wrapsWordsToWidth() {
        val lines = TextBox.wrap("aaa bbb ccc", maxWidth = 40f, size = 10f, measure = half)
        assertEquals(listOf("aaa bbb", "ccc"), lines)
    }

    @Test
    fun keepsTypedLineBreaks() {
        assertEquals(listOf("ab", "", "cd"), TextBox.wrap("ab\n\ncd", 100f, 10f, half))
    }

    @Test
    fun breaksWordsLongerThanTheBox() {
        assertEquals(listOf("abcdefgh", "ij"), TextBox.wrap("abcdefghij", 40f, 10f, half))
    }

    @Test
    fun neededHeightGrowsWithLines() {
        val one = TextBox.neededHeight(1, 10f)
        val three = TextBox.neededHeight(3, 10f)
        assertEquals(2 * 10f * TextBox.LINE_HEIGHT, three - one, 0.01f)
        assertEquals(one, TextBox.neededHeight(0, 10f), 0.01f)
    }

    @Test
    fun rotationSnapsNearRightAnglesAndWraps() {
        assertEquals(90f, TextBox.snap(92f), 0.01f)
        assertEquals(47f, TextBox.snap(47f), 0.01f)
        assertEquals(0f, TextBox.snap(358f), 0.01f)
        assertEquals(270f, TextBox.snap(-90f), 0.01f)
        assertEquals(0f, TextBox.rotateClockwise(270f), 0.01f)
        assertEquals(135f, TextBox.rotateClockwise(45f), 0.01f)
    }

    @Test
    fun paletteHasRainbowPlusBlackAndWhite() {
        assertEquals(9, TextBox.COLORS.size)
        assertEquals(listOf(0f, 0f, 0f), TextBox.COLORS[7].toList())
        assertEquals(listOf(1f, 1f, 1f), TextBox.COLORS[8].toList())
    }
}
