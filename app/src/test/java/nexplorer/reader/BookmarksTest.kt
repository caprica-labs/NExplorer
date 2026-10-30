package nexplorer.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarksTest {
    @Test
    fun encodeDecodeRoundTrip() {
        val list = listOf(Bookmark(0f, "Page 1"), Bookmark(0.5f, "Chapter\ttwo\nstarts"), Bookmark(1f, ""))
        val back = Bookmarks.decode(Bookmarks.encode(list))
        assertEquals(listOf(Bookmark(0f, "Page 1"), Bookmark(0.5f, "Chapter two starts"), Bookmark(1f, "")), back)
    }

    @Test
    fun decodeSkipsBrokenLines() {
        assertEquals(emptyList<Bookmark>(), Bookmarks.decode(null))
        assertEquals(listOf(Bookmark(0.25f, "ok")), Bookmarks.decode("junk\nx\ty\n0.25\tok"))
    }

    @Test
    fun toggleAddsThenRemoves() {
        val one = Bookmarks.toggle(emptyList(), page = 11, pageCount = 40)
        assertEquals("Page 12", one.single().name)
        assertTrue(Bookmarks.isMarked(one, 11, 40))
        assertFalse(Bookmarks.isMarked(one, 10, 40))
        assertEquals(emptyList<Bookmark>(), Bookmarks.toggle(one, 11, 40))
    }

    @Test
    fun keptInReadingOrder() {
        val list = Bookmarks.toggle(Bookmarks.toggle(emptyList(), 30, 40), 3, 40)
        assertEquals(listOf("Page 4", "Page 31"), list.map { it.name })
    }

    @Test
    fun currentEntryIsLastStartedOne() {
        val toc = listOf(TocEntry("A", 0, 0), TocEntry("group", 0, -1), TocEntry("B", 1, 5), TocEntry("C", 0, 9))
        assertEquals(0, Bookmarks.currentEntry(toc, 4))
        assertEquals(2, Bookmarks.currentEntry(toc, 5))
        assertEquals(3, Bookmarks.currentEntry(toc, 20))
    }

    @Test
    fun remapKeepsHowFarThrough() {
        val toc = listOf(TocEntry("A", 0, 0), TocEntry("B", 0, 50), TocEntry("x", 0, -1), TocEntry("C", 0, 100))
        assertEquals(listOf(0, 100, -1, 200), Bookmarks.remap(toc, 101, 201).map { it.page })
    }
}
