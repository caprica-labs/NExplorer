package nexplorer.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class PrefetchTest {
    @Test
    fun nearestPagesFirstAlternatingSides() {
        assertEquals(listOf(5, 6, 4, 7, 3), Prefetch.order(current = 5, count = 100, radius = 2))
    }

    @Test
    fun tenEachSideByDefault() {
        val o = Prefetch.order(current = 50, count = 100)
        assertEquals(21, o.size)
        assertEquals(40, o.min())
        assertEquals(60, o.max())
    }

    @Test
    fun pdfsReadFivePagesEachSide() {
        val o = Prefetch.order(current = 50, count = 100, radius = Prefetch.PDF_RADIUS)
        assertEquals(11, o.size)
        assertEquals(45, o.min())
        assertEquals(55, o.max())
    }

    @Test
    fun budgetNeverExceedsTheWindow() {
        assertEquals(11, Prefetch.pagesThatFit(cacheBytes = 1L shl 30, bytesPerPage = 6L shl 20, radius = Prefetch.PDF_RADIUS))
    }

    @Test
    fun clippedAtDocumentEdges() {
        assertEquals(listOf(0, 1, 2), Prefetch.order(current = 0, count = 3, radius = 10))
        assertEquals(listOf(9, 8, 7), Prefetch.order(current = 9, count = 10, radius = 2))
    }

    @Test
    fun pagesThatFitInBudget() {
        assertEquals(8, Prefetch.pagesThatFit(cacheBytes = 64L shl 20, bytesPerPage = 6L shl 20))
        assertEquals(21, Prefetch.pagesThatFit(cacheBytes = 1L shl 30, bytesPerPage = 6L shl 20))
        assertEquals(1, Prefetch.pagesThatFit(cacheBytes = 1L shl 20, bytesPerPage = 6L shl 20))
    }
}
