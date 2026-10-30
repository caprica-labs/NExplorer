package nexplorer.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileOpsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun deletesFile() {
        val f = tmp.newFile("a.pdf")
        assertTrue(FileOps.deleteFromDevice(f))
        assertFalse(f.exists())
    }

    @Test
    fun deletesFolderRecursively() {
        val d = tmp.newFolder("d")
        File(d, "sub").mkdirs()
        File(d, "sub/x.pdf").writeText("x")
        File(d, "y.epub").writeText("y")
        assertTrue(FileOps.deleteFromDevice(d))
        assertFalse(d.exists())
    }

    @Test
    fun countAllIncludesNonReadableAndHidden() {
        val d = tmp.newFolder("d")
        File(d, "a.pdf").writeText("x")
        File(d, "photo.jpg").writeText("x")
        File(d, ".thumbs").mkdirs()
        File(d, ".thumbs/t.jpg").writeText("x")
        assertEquals(3, FileOps.countAll(d))
    }

    @Test
    fun missingTargetReturnsTrue() {
        assertTrue(FileOps.deleteFromDevice(File(tmp.root, "nope")))
    }
}
