package nexplorer.files

import nexplorer.files.FileOps.OnConflict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileManageTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(dir: File, name: String, text: String = "x") =
        File(dir, name).apply { parentFile!!.mkdirs(); writeText(text) }

    @Test
    fun renameFile() {
        val a = file(tmp.root, "a.pdf")
        val b = FileOps.rename(a, "b.pdf")
        assertEquals("b.pdf", b.name)
        assertTrue(b.exists())
        assertFalse(a.exists())
    }

    @Test(expected = IllegalStateException::class)
    fun renameOntoExistingFails() {
        file(tmp.root, "b.pdf")
        FileOps.rename(file(tmp.root, "a.pdf"), "b.pdf")
    }

    @Test(expected = IllegalArgumentException::class)
    fun renameRejectsSlash() {
        FileOps.rename(file(tmp.root, "a.pdf"), "x/y.pdf")
    }

    @Test(expected = IllegalArgumentException::class)
    fun renameRejectsBlank() {
        FileOps.rename(file(tmp.root, "a.pdf"), "   ")
    }

    @Test
    fun uniqueNameAddsNumberBeforeExtension() {
        file(tmp.root, "Report.pdf")
        assertEquals("Report (2).pdf", FileOps.uniqueName(tmp.root, "Report.pdf"))
        file(tmp.root, "Report (2).pdf")
        assertEquals("Report (3).pdf", FileOps.uniqueName(tmp.root, "Report.pdf"))
        File(tmp.root, "Books").mkdirs()
        assertEquals("Books (2)", FileOps.uniqueName(tmp.root, "Books"))
        assertEquals("New.pdf", FileOps.uniqueName(tmp.root, "New.pdf"))
    }

    @Test
    fun clashDetected() {
        val dest = tmp.newFolder("dest")
        val src = file(tmp.root, "a.pdf")
        assertFalse(FileOps.wouldClash(src, dest))
        file(dest, "a.pdf")
        assertTrue(FileOps.wouldClash(src, dest))
    }

    @Test
    fun copyFolderRecursivelyKeepsOriginal() {
        val src = tmp.newFolder("Books")
        file(src, "sub/a.pdf")
        val dest = tmp.newFolder("dest")
        val out = FileOps.copyTo(src, dest, OnConflict.KeepBoth)
        assertTrue(File(out, "sub/a.pdf").exists())
        assertTrue(File(src, "sub/a.pdf").exists())
    }

    @Test
    fun copyKeepBothAddsNumber() {
        val dest = tmp.newFolder("dest")
        file(dest, "a.pdf", "old")
        val out = FileOps.copyTo(file(tmp.root, "a.pdf", "new"), dest, OnConflict.KeepBoth)
        assertEquals("a (2).pdf", out.name)
        assertEquals("old", File(dest, "a.pdf").readText())
    }

    @Test
    fun copyReplaceOverwrites() {
        val dest = tmp.newFolder("dest")
        file(dest, "a.pdf", "old")
        val out = FileOps.copyTo(file(tmp.root, "a.pdf", "new"), dest, OnConflict.Replace)
        assertEquals("a.pdf", out.name)
        assertEquals("new", out.readText())
    }

    @Test
    fun failedReplaceKeepsTheOldFile() {
        val dest = tmp.newFolder("dest")
        file(dest, "a.pdf", "old")
        val missing = File(tmp.root, "a.pdf")
        assertTrue(runCatching { FileOps.copyTo(missing, dest, OnConflict.Replace) }.isFailure)
        assertEquals("old", File(dest, "a.pdf").readText())
        assertEquals(listOf("a.pdf"), dest.list()!!.toList())
    }

    @Test
    fun replaceLeavesNothingBehind() {
        val dest = tmp.newFolder("dest")
        file(dest, "Books/old.pdf")
        val src = tmp.newFolder("Books")
        file(src, "new.pdf")
        FileOps.copyTo(src, dest, OnConflict.Replace)
        assertEquals(listOf("Books"), dest.list()!!.toList())
    }

    @Test
    fun replacingAFolderWithSomethingInsideItRefused() {
        val dest = tmp.newFolder("dest")
        val inner = File(dest, "Books/Books").apply { mkdirs() }
        file(inner, "a.pdf")
        assertTrue(runCatching { FileOps.moveTo(inner, dest, OnConflict.Replace) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(File(inner, "a.pdf").exists())
    }

    @Test
    fun moveFile() {
        val dest = tmp.newFolder("dest")
        val src = file(tmp.root, "a.pdf")
        val out = FileOps.moveTo(src, dest, OnConflict.KeepBoth)
        assertFalse(src.exists())
        assertEquals(File(dest, "a.pdf"), out)
        assertTrue(out.exists())
    }

    @Test
    fun moveReplaceFolder() {
        val dest = tmp.newFolder("dest")
        file(dest, "Books/old.pdf")
        val src = tmp.newFolder("Books")
        file(src, "new.pdf")
        val out = FileOps.moveTo(src, dest, OnConflict.Replace)
        assertTrue(File(out, "new.pdf").exists())
        assertFalse(File(out, "old.pdf").exists())
        assertFalse(src.exists())
    }

    @Test(expected = IllegalArgumentException::class)
    fun moveFolderIntoItselfRefused() {
        val src = tmp.newFolder("Books")
        val inner = File(src, "inner").apply { mkdirs() }
        FileOps.moveTo(src, inner, OnConflict.KeepBoth)
    }

    @Test
    fun moveIntoSameFolderDoesNothing() {
        val src = file(tmp.root, "a.pdf")
        assertEquals(src, FileOps.moveTo(src, tmp.root, OnConflict.KeepBoth))
        assertTrue(src.exists())
    }
}
