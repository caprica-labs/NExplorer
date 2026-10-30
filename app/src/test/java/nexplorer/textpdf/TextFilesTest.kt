package nexplorer.textpdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextFilesTest {
    @Test
    fun utf8WithAndWithoutMark() {
        assertEquals("سلام\nmir", TextFiles.decode("سلام\r\nmir".toByteArray()))
        assertEquals("abc", TextFiles.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "abc".toByteArray()))
    }

    @Test
    fun utf16BothWays() {
        assertEquals("Привет", TextFiles.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Привет".toByteArray(Charsets.UTF_16LE)))
        assertEquals("Привет", TextFiles.decode(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "Привет".toByteArray(Charsets.UTF_16BE)))
    }

    @Test
    fun olderEncodingsAreNotUtf8() {
        assertTrue(TextFiles.isUtf8("سلام mir".toByteArray()))
        assertTrue(TextFiles.isUtf8(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "سلام".toByteArray(Charsets.UTF_16LE)))
        assertTrue(TextFiles.isUtf8(ByteArray(0)))
        // "سلام" in Windows-1256.
        assertFalse(TextFiles.isUtf8(byteArrayOf(0xD3.toByte(), 0xE1.toByte(), 0xC7.toByte(), 0xE3.toByte())))
        assertFalse(TextFiles.isUtf8(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte())))
    }

    @Test
    fun oldMacLineEnds() {
        assertEquals("a\nb", TextFiles.decode("a\rb".toByteArray()))
    }

    @Test
    fun savingKeepsTheFilesLayout() {
        for (original in listOf(
            "a\r\nb".toByteArray(),
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "x\ny".toByteArray(),
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "سلام\r\nmir".toByteArray(Charsets.UTF_16LE),
        )) {
            val layout = TextFiles.layoutOf(original)
            assertEquals(original.toList(), TextFiles.encode(TextFiles.decode(original), layout).toList())
        }
    }
}
