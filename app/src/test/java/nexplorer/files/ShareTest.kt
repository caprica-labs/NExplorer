package nexplorer.files

import org.junit.Assert.assertEquals
import org.junit.Test

class ShareTest {
    @Test
    fun toggleAddsAndRemoves() {
        val one = Picking.toggle(emptySet(), "/a")
        assertEquals(setOf("/a"), one)
        assertEquals(setOf("/a", "/b"), Picking.toggle(one, "/b"))
        assertEquals(emptySet<String>(), Picking.toggle(one, "/a"))
    }

    @Test
    fun sameTypeKeptForAllFiles() {
        assertEquals("application/pdf", Share.commonType(listOf("application/pdf", "application/pdf")))
    }

    @Test
    fun mixedApplicationTypesShareTheirFamily() {
        assertEquals("application/*", Share.commonType(listOf("application/pdf", "application/epub+zip")))
    }

    @Test
    fun unrelatedTypesFallBackToAnything() {
        assertEquals("*/*", Share.commonType(listOf("application/pdf", "text/plain")))
        assertEquals("*/*", Share.commonType(emptyList()))
    }

    @Test
    fun mimeFromExtension() {
        assertEquals("application/pdf", Share.mimeFor("Book.PDF"))
        assertEquals("application/epub+zip", Share.mimeFor("x.epub"))
        assertEquals("text/plain", Share.mimeFor("notes.txt"))
        assertEquals("application/octet-stream", Share.mimeFor("archive.xyz"))
    }

    @Test
    fun picturesAreZippedSideBySide() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        try {
            val a = java.io.File(dir, "page-1.png").apply { writeBytes(ByteArray(300) { it.toByte() }) }
            val b = java.io.File(dir, "page-2.png").apply { writeBytes(byteArrayOf(9, 8, 7)) }
            val out = java.io.File(dir, "pics.zip")
            Share.zip(listOf(a, b), out)
            java.util.zip.ZipFile(out).use { z ->
                assertEquals(listOf("page-1.png", "page-2.png"), z.entries().toList().map { it.name })
                assertEquals(a.readBytes().toList(), z.getInputStream(z.getEntry("page-1.png")).readBytes().toList())
            }
            assertEquals("image/png", Share.mimeFor("page-1.png"))
            assertEquals("application/zip", Share.mimeFor("pics.zip"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
