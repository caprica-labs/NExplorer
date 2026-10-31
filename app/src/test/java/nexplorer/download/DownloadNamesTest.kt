package nexplorer.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadNamesTest {
    @Test
    fun nameFromDisposition() {
        assertEquals("report.pdf", DownloadNames.fromDisposition("attachment; filename=\"report.pdf\""))
        assertEquals("plain.zip", DownloadNames.fromDisposition("attachment; filename=plain.zip"))
        assertEquals("a \"b\".txt", DownloadNames.fromDisposition("attachment; filename=\"a \\\"b\\\".txt\""))
        assertEquals("résumé.pdf", DownloadNames.fromDisposition("attachment; filename=\"x.pdf\"; filename*=UTF-8''r%C3%A9sum%C3%A9.pdf"))
        assertNull(DownloadNames.fromDisposition("inline"))
        assertNull(DownloadNames.fromDisposition(null))
    }

    @Test
    fun nameFromUrl() {
        assertEquals("file name.epub", DownloadNames.fromUrl("https://example.com/books/file%20name.epub?x=1#top"))
        assertEquals("a+b.zip", DownloadNames.fromUrl("https://example.com/a+b.zip"))
        assertNull(DownloadNames.fromUrl("https://example.com/"))
        assertNull(DownloadNames.fromUrl("https://example.com"))
    }

    @Test
    fun namesCantLeaveTheFolder() {
        assertEquals("passwd", DownloadNames.clean("../../etc/passwd"))
        assertEquals("evil.sh", DownloadNames.clean("..\\..\\evil.sh"))
        assertEquals("hidden", DownloadNames.clean(".hidden"))
        assertEquals("download", DownloadNames.clean(".."))
        assertEquals("download", DownloadNames.clean("   "))
        assertEquals("a_b_c_.txt", DownloadNames.clean("a:b*c?.txt"))
        assertEquals("line_break", DownloadNames.clean("line\nbreak"))
    }

    @Test
    fun longNamesKeepTheirExtension() {
        val name = DownloadNames.clean("x".repeat(300) + ".pdf")
        assertEquals(150, name.length)
        assertTrue(name.endsWith(".pdf"))
    }

    @Test
    fun extensionAddedOnlyWhenMissing() {
        assertEquals("report.pdf", DownloadNames.pick(null, "https://example.com/report", "pdf"))
        assertEquals("report.txt", DownloadNames.pick(null, "https://example.com/report.txt", "pdf"))
        assertEquals("download", DownloadNames.pick(null, "https://example.com/", null))
        assertEquals("passwd", DownloadNames.pick("attachment; filename=\"../../passwd\"", "https://example.com/x", null))
    }

    @Test
    fun rangeTotal() {
        assertEquals(2000L, DownloadNames.rangeTotal("bytes 100-1999/2000"))
        assertNull(DownloadNames.rangeTotal("bytes 100-1999/*"))
        assertNull(DownloadNames.rangeTotal(null))
    }

    @Test
    fun onlyWebLinks() {
        assertEquals("https://example.com/a.pdf", Downloads.normalize("  https://example.com/a.pdf "))
        assertEquals("https://example.com/a.pdf", Downloads.normalize("example.com/a.pdf"))
        assertEquals("http://example.com/a", Downloads.normalize("http://example.com/a"))
        assertNull(Downloads.normalize("file:///sdcard/secret.txt"))
        assertNull(Downloads.normalize("ftp://example.com/a"))
        assertNull(Downloads.normalize("javascript:alert(1)"))
        assertNull(Downloads.normalize("two words"))
        assertNull(Downloads.normalize(""))
        assertFalse(Downloads.normalize("https://example.com")!!.startsWith("http://"))
    }
}
