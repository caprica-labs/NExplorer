package nexplorer.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FolderScannerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun touch(root: File, path: String) {
        val f = File(root, path)
        f.parentFile!!.mkdirs()
        f.writeText("x")
    }

    @Test
    fun countsRecursively() {
        val root = tmp.root
        touch(root, "a.pdf")
        touch(root, "sub/b.epub")
        touch(root, "sub/c.jpg")
        assertEquals(2, FolderScanner.countReadable(root))
    }

    @Test
    fun childrenFoldersFirstAndHidesEmpty() {
        val root = tmp.root
        touch(root, "zeta.pdf")
        touch(root, "Alpha/x.txt")
        File(root, "empty").mkdirs()
        val kids = FolderScanner.children(root)
        assertEquals(listOf("Alpha", "zeta.pdf"), kids.map { it.file.name })
        assertEquals(1, kids[0].readableCount)
        assertTrue(kids[0].isDir)
    }

    @Test
    fun skipsHiddenAndAndroidData() {
        val root = tmp.root
        touch(root, ".thumbs/a.pdf")
        touch(root, "Android/data/b.pdf")
        assertEquals(0, FolderScanner.countReadable(root))
    }

    @Test
    fun countCachedUntilInvalidated() {
        val root = tmp.root
        touch(root, "a.pdf")
        assertEquals(1, FolderScanner.countReadable(root))
        touch(root, "b.pdf")
        assertEquals(1, FolderScanner.countReadable(root))
        FolderScanner.invalidate()
        assertEquals(2, FolderScanner.countReadable(root))
    }

    @Test
    fun hiddenFolderExcludedFromCountsAndChildren() {
        val root = tmp.root
        touch(root, "Keep/a.pdf")
        touch(root, "Secret/b.pdf")
        touch(root, "Secret/c.pdf")
        FolderScanner.hidden = setOf(File(root, "Secret").path)
        try {
            assertEquals(1, FolderScanner.countReadable(root))
            assertEquals(listOf("Keep"), FolderScanner.children(root).map { it.file.name })
        } finally {
            FolderScanner.hidden = emptySet()
        }
    }

    @Test
    fun hiddenFileExcluded() {
        val root = tmp.root
        touch(root, "a.pdf")
        touch(root, "b.pdf")
        FolderScanner.hidden = setOf(File(root, "a.pdf").path)
        try {
            assertEquals(listOf("b.pdf"), FolderScanner.children(root).map { it.file.name })
        } finally {
            FolderScanner.hidden = emptySet()
        }
    }

    @Test
    fun isHiddenMatchesDescendantsOnly() {
        FolderScanner.hidden = setOf("/s/Books")
        try {
            assertTrue(FolderScanner.isHidden(File("/s/Books")))
            assertTrue(FolderScanner.isHidden(File("/s/Books/x/y.pdf")))
            assertFalse(FolderScanner.isHidden(File("/s/Bookshelf/y.pdf")))
        } finally {
            FolderScanner.hidden = emptySet()
        }
    }

    @Test
    fun missingDirIsZero() {
        val gone = File(tmp.root, "gone")
        assertEquals(0, FolderScanner.countReadable(gone))
        assertTrue(FolderScanner.children(gone).isEmpty())
    }
}
