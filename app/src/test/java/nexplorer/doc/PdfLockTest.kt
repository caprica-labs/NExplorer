package nexplorer.doc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfLockTest {
    @Test
    fun nothingBlockedAllowsEverything() {
        assertEquals(-4, PdfLock.permissions(emptySet()))
    }

    @Test
    fun blockingClearsOnlyThoseBits() {
        val p = PdfLock.permissions(setOf(Restriction.Printing, Restriction.Copying))
        assertEquals(0, p and 4)
        assertEquals(0, p and 2048)
        assertEquals(0, p and 16)
        assertEquals(8, p and 8)
        assertEquals(32, p and 32)
        assertEquals(512, p and 512)
        assertTrue(p < 0)
    }

    @Test
    fun ownerPasswordUsedOnlyWithRestrictions() {
        assertTrue(PdfLock.options("a", "b", emptySet()).contains("owner-password=a,"))
        assertTrue(PdfLock.options("a", "b", setOf(Restriction.Editing)).contains("owner-password=b,"))
        assertTrue(PdfLock.options("", "b", setOf(Restriction.Editing)).contains("user-password=,"))
    }

    @Test
    fun commasAreRefused() {
        assertFalse(PdfLock.usable("a,b"))
        assertTrue(PdfLock.usable("a=b c"))
    }
}
