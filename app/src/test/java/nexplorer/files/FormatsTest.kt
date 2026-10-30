package nexplorer.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatsTest {
    @Test
    fun isReadableIgnoresCase() {
        assertTrue(Formats.isReadable("A.PDF"))
        assertFalse(Formats.isReadable("a.zip"))
    }

    @Test
    fun kindOf() {
        assertEquals(FormatKind.DOCX, Formats.kindOf("x.docx"))
        assertEquals(FormatKind.DOCX, Formats.kindOf("Old.DOC"))
        assertEquals(FormatKind.REFLOWABLE, Formats.kindOf("x.epub"))
        assertEquals(FormatKind.FIXED, Formats.kindOf("x.pdf"))
        assertNull(Formats.kindOf("x.mp3"))
    }
}
