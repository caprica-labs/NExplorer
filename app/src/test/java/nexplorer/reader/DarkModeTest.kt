package nexplorer.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DarkModeTest {
    private fun apply(r: Int, g: Int, b: Int): Triple<Float, Float, Float> {
        val m = DarkMode.matrix
        fun ch(row: Int) = (m[row * 5] * r + m[row * 5 + 1] * g + m[row * 5 + 2] * b + m[row * 5 + 4]).coerceIn(0f, 255f)
        return Triple(ch(0), ch(1), ch(2))
    }

    @Test
    fun whiteBecomesBlackAndBlackBecomesWhite() {
        val w = apply(255, 255, 255)
        assertEquals(0f, w.first, 1f); assertEquals(0f, w.second, 1f); assertEquals(0f, w.third, 1f)
        val k = apply(0, 0, 0)
        assertEquals(255f, k.first, 1f); assertEquals(255f, k.second, 1f); assertEquals(255f, k.third, 1f)
    }

    @Test
    fun yellowHighlightStaysYellowish() {
        val (r, g, b) = apply(0xFF, 0xF5, 0x9D)
        assertTrue("r=$r g=$g b=$b", r > b && g > b)
    }

    @Test
    fun greenStaysGreenish() {
        val (r, g, b) = apply(0xA5, 0xD6, 0xA7)
        assertTrue("r=$r g=$g b=$b", g > r && g > b)
    }
}
