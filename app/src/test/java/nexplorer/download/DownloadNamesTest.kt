package nexplorer.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

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
        assertEquals("https://example.com/a.pdf", DownloadNames.normalize("  https://example.com/a.pdf "))
        assertEquals("https://example.com/a.pdf", DownloadNames.normalize("example.com/a.pdf"))
        assertEquals("http://example.com/a", DownloadNames.normalize("http://example.com/a"))
        assertNull(DownloadNames.normalize("file:///sdcard/secret.txt"))
        assertNull(DownloadNames.normalize("ftp://example.com/a"))
        assertNull(DownloadNames.normalize("javascript:alert(1)"))
        assertNull(DownloadNames.normalize("two words"))
        assertNull(DownloadNames.normalize(""))
        assertFalse(DownloadNames.normalize("https://example.com")!!.startsWith("http://"))
    }

    @Test
    fun httpBecomesHttps() {
        assertEquals("https://example.com/a", DownloadNames.secure("http://example.com/a"))
        assertEquals("https://example.com/a", DownloadNames.secure("https://example.com/a"))
    }

    @Test
    fun linkInSharedText() {
        assertEquals("https://example.com/a.zip", DownloadNames.linkIn("Get it here: https://example.com/a.zip."))
        assertEquals("https://example.com/b", DownloadNames.linkIn("(https://example.com/b)"))
        assertNull(DownloadNames.linkIn("no link here"))
        assertNull(DownloadNames.linkIn(null))
    }

    @Test
    fun partsCoverTheWholeFile() {
        val parts = DownloadPlan.segments(10_000_000, threads = 4, minSize = 1_048_576)
        assertEquals(4, parts.size)
        assertEquals(0L, parts.first().first)
        assertEquals(9_999_999L, parts.last().last)
        parts.zipWithNext().forEach { (a, b) -> assertEquals(a.last + 1, b.first) }
    }

    @Test
    fun partsRespectTheMinimumSize() {
        assertEquals(2, DownloadPlan.segments(2_500_000, threads = 16, minSize = 1_048_576).size)
        assertEquals(1, DownloadPlan.segments(500_000, threads = 8, minSize = 1_048_576).size)
        assertEquals(0, DownloadPlan.segments(-1, threads = 8, minSize = 1_048_576).size)
    }

    @Test
    fun scheduleTimes() {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(2026, Calendar.NOVEMBER, 1, 1, 30)
        val now = cal.timeInMillis
        val today = DownloadPlan.nextAt(now, 2 * 60 + 1, Calendar.getInstance(TimeZone.getTimeZone("UTC")))
        assertEquals(31L * 60_000, today - now)
        val tomorrow = DownloadPlan.nextAt(now, 60, Calendar.getInstance(TimeZone.getTimeZone("UTC")))
        assertEquals((24 * 60 - 30).toLong() * 60_000, tomorrow - now)
        assertEquals("02:01", DownloadPlan.clock(121))
        assertEquals("23:59", DownloadPlan.clock(1439))
    }
}
