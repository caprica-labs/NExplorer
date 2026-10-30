package nexplorer.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RecycleBinTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val root get() = tmp.root
    private fun file(dir: File, name: String, text: String = "x") =
        File(dir, name).apply { parentFile!!.mkdirs(); writeText(text) }

    @Test
    fun deletedFileWaitsInTheBinWithWhereItCameFrom() {
        val f = file(File(root, "Docs"), "a.pdf", "hello")
        RecycleBin.put(f, listOf(root), now = 1000L)
        assertFalse(f.exists())
        val item = RecycleBin.items(listOf(root)).single()
        assertEquals("a.pdf", item.file.name)
        assertEquals("hello", item.file.readText())
        assertEquals(f.path, item.original.path)
        assertEquals(1000L, item.deletedAt)
        assertTrue(Explorer.list(root, ExplorerSort()).none { it.file.name == RecycleBin.FOLDER })
    }

    @Test
    fun recoverPutsItBackEvenIfItsFolderWasDeleted() {
        val f = file(File(root, "Docs"), "a.pdf", "hello")
        RecycleBin.put(f, listOf(root))
        File(root, "Docs").deleteRecursively()
        val back = RecycleBin.restore(RecycleBin.items(listOf(root)).single())
        assertEquals(f.path, back.path)
        assertEquals("hello", back.readText())
        assertTrue(RecycleBin.items(listOf(root)).isEmpty())
    }

    @Test
    fun recoverKeepsBothWhenTheNameIsTakenAgain() {
        val f = file(root, "a.pdf", "old")
        RecycleBin.put(f, listOf(root))
        file(root, "a.pdf", "new")
        val back = RecycleBin.restore(RecycleBin.items(listOf(root)).single())
        assertEquals("a (2).pdf", back.name)
        assertEquals("old", back.readText())
        assertEquals("new", File(root, "a.pdf").readText())
    }

    @Test
    fun foldersGoInWhole() {
        val dir = File(root, "Trip")
        file(dir, "1.jpg")
        file(File(dir, "more"), "2.jpg")
        RecycleBin.put(dir, listOf(root))
        val item = RecycleBin.items(listOf(root)).single()
        assertTrue(item.file.isDirectory)
        assertEquals(2, RecycleBin.count(item))
        RecycleBin.restore(item)
        assertTrue(File(dir, "more/2.jpg").isFile)
    }

    @Test
    fun itemsOlderThan30DaysAreRemovedForGood() {
        val day = 24 * 60 * 60 * 1000L
        RecycleBin.put(file(root, "old.pdf"), listOf(root), now = 0L)
        RecycleBin.put(file(root, "new.pdf"), listOf(root), now = 25 * day)
        RecycleBin.purge(listOf(root), now = 31 * day)
        assertEquals(listOf("new.pdf"), RecycleBin.items(listOf(root)).map { it.file.name })
    }

    @Test
    fun deleteForGoodEmptiesItsPlace() {
        RecycleBin.put(file(root, "a.pdf"), listOf(root))
        val item = RecycleBin.items(listOf(root)).single()
        assertTrue(RecycleBin.delete(item))
        assertTrue(RecycleBin.items(listOf(root)).isEmpty())
        assertFalse(item.holder.exists())
    }

    @Test
    fun copyReportsEveryByteAndCanBeStopped() {
        val src = file(root, "big.bin", "x".repeat(600_000))
        val dest = File(root, "out").apply { mkdirs() }
        var bytes = 0L
        FileOps.copyTo(src, dest, FileOps.OnConflict.KeepBoth) { bytes += it }
        assertEquals(600_000L, bytes)
        val stopped = runCatching {
            FileOps.copyTo(src, dest, FileOps.OnConflict.KeepBoth) { n -> bytes += n; if (bytes > 700_000) error("Cancelled") }
        }
        assertTrue(stopped.isFailure)
        assertEquals(listOf("big.bin"), dest.list()!!.toList())
    }
}
