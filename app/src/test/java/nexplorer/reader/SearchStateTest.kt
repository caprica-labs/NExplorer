package nexplorer.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchStateTest {
    private fun hits(n: Int) = List(n) { Hit(page = it, rects = emptyList()) }

    @Test
    fun noMatches() {
        val s = ReaderSearchState(query = "zzz")
        assertEquals("0 of 0", s.counterText)
        assertEquals(s, s.next())
        assertEquals(s, s.prev())
    }

    @Test
    fun nextWraps() {
        assertEquals(0, ReaderSearchState("a", hits(3), current = 2).next().current)
    }

    @Test
    fun prevWraps() {
        assertEquals(2, ReaderSearchState("a", hits(3), current = 0).prev().current)
    }

    @Test
    fun counterOneBased() {
        assertEquals("1 of 5", ReaderSearchState("a", hits(5), current = 0).counterText)
    }
}
