package nexplorer.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JumpToPageTest {
    @Test
    fun validNumberGivesZeroBasedIndex() {
        assertEquals(0, parsePageInput("1", pageCount = 12))
        assertEquals(11, parsePageInput(" 12 ", pageCount = 12))
    }

    @Test
    fun outOfRangeOrJunkIsNull() {
        assertNull(parsePageInput("0", 12))
        assertNull(parsePageInput("13", 12))
        assertNull(parsePageInput("", 12))
        assertNull(parsePageInput("abc", 12))
        assertNull(parsePageInput("-3", 12))
    }
}
