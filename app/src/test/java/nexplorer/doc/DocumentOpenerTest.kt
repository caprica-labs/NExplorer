package nexplorer.doc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DocumentOpenerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun zeroByteFile() {
        assertEquals("This file is empty", DocumentOpener.precheck(tmp.newFile("a.pdf")))
    }

    @Test
    fun unknownExtension() {
        val f = tmp.newFile("a.mp3").apply { writeText("x") }
        assertEquals("This format isn't supported", DocumentOpener.precheck(f))
    }

    @Test
    fun okFileReturnsNull() {
        val f = tmp.newFile("a.epub").apply { writeText("x") }
        assertNull(DocumentOpener.precheck(f))
    }
}
