package nexplorer.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class TextMeasureTest {
    private val table: (Char) -> Float? = { c -> if (c == 'a' || c == 'б') 0.5f else null }
    private val runs = ArrayList<String>()
    private val fallback: (String) -> Float = { runs.add(it); it.length * 1f }

    @Test
    fun knownLettersFromTableOthersAsWholeRuns() {
        val m = TextMeasure.mixed(table, fallback)
        assertEquals(1f + 2f + 0.5f, m("aaشیб"), 0.001f)
        assertEquals(listOf("شی"), runs)
    }

    @Test
    fun allKnownNeverCallsFallback() {
        val m = TextMeasure.mixed(table, fallback)
        assertEquals(1.5f, m("aбa"), 0.001f)
        assertEquals(emptyList<String>(), runs)
    }

    @Test
    fun emptyIsZero() {
        assertEquals(0f, TextMeasure.mixed(table, fallback)(""), 0.001f)
    }
}
