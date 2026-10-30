package nexplorer.doc

import nexplorer.files.FormatKind
import nexplorer.files.Formats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComicArchiveTest {
    @Test
    fun picturesAreKeptAndOtherFilesSkipped() {
        assertTrue(ComicArchive.isPicture("Chapter 1/Page 01.JPG"))
        assertTrue(ComicArchive.isPicture("p.webp"))
        assertFalse(ComicArchive.isPicture("ComicInfo.xml"))
        assertFalse(ComicArchive.isPicture("Thumbs.db"))
    }

    @Test
    fun aChangedFileGetsANewCacheName() {
        val f = kotlin.io.path.createTempFile(suffix = ".cbr").toFile()
        try {
            f.writeText("one")
            val first = ComicArchive.cacheName(f)
            assertEquals(first, ComicArchive.cacheName(f))
            f.writeText("longer content")
            assertNotEquals(first, ComicArchive.cacheName(f))
            assertTrue(first.endsWith(".cbz"))
        } finally {
            f.delete()
        }
    }

    @Test
    fun cbrIsAReadableComic() {
        assertTrue(Formats.isReadable("Saga #1.CBR"))
        assertEquals(FormatKind.FIXED, Formats.kindOf("x.cbr"))
    }
}
