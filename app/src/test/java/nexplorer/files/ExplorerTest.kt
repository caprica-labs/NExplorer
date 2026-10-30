package nexplorer.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class ExplorerTest {
    private fun e(name: String, dir: Boolean = false, size: Long = 0, date: Long = 0, count: Int = 0) =
        Entry(File("/x/$name"), dir, count, size, date)

    @Test
    fun typesDecideHowFilesOpen() {
        assertEquals(FileType.Image, FileTypes.of("IMG_1.HEIC", false))
        assertEquals(FileType.Document, FileTypes.of("a.pdf", false))
        assertEquals(FileType.Document, FileTypes.of("comic.cbr", false))
        assertEquals(FileType.Text, FileTypes.of("notes.txt", false))
        assertEquals(FileType.Text, FileTypes.of("data.json", false))
        assertEquals(FileType.Archive, FileTypes.of("x.7z", false))
        assertEquals(FileType.Other, FileTypes.of("song.mp3", false))
        assertEquals(FileType.Folder, FileTypes.of("Music", true))
    }

    @Test
    fun foldersComeFirstWhateverTheOrder() {
        val items = listOf(e("b.pdf", size = 5), e("Zeta", dir = true), e("a.txt", size = 9), e("alpha", dir = true))
        assertEquals(listOf("alpha", "Zeta", "a.txt", "b.pdf"), Explorer.sorted(items, ExplorerSort()).map { it.file.name })
        assertEquals(
            listOf("Zeta", "alpha", "a.txt", "b.pdf"),
            Explorer.sorted(items, ExplorerSort(SortBy.Size)).map { it.file.name },
        )
        assertEquals(
            listOf("alpha", "Zeta", "b.pdf", "a.txt"),
            Explorer.sorted(items, ExplorerSort(SortBy.Size, descending = true)).map { it.file.name },
        )
    }

    @Test
    fun sortByDateAndType() {
        val items = listOf(e("new.pdf", date = 30), e("old.txt", date = 10), e("mid.epub", date = 20))
        assertEquals(listOf("new.pdf", "mid.epub", "old.txt"), Explorer.sorted(items, ExplorerSort(SortBy.Date)).map { it.file.name })
        assertEquals(listOf("old.txt", "mid.epub", "new.pdf"), Explorer.sorted(items, ExplorerSort(SortBy.Date, descending = true)).map { it.file.name })
        assertEquals(listOf("mid.epub", "new.pdf", "old.txt"), Explorer.sorted(items, ExplorerSort(SortBy.Type)).map { it.file.name })
    }

    @Test
    fun sortIsRemembered() {
        val s = ExplorerSort(SortBy.Date, descending = true)
        assertEquals(s, ExplorerSort.decode(s.encode()))
        assertEquals(ExplorerSort(), ExplorerSort.decode("junk"))
    }

    @Test
    fun archivePathsCantEscape() {
        assertEquals("a/b.txt", Archives.safePath("/a/b.txt"))
        assertEquals("a/b.txt", Archives.safePath("../../a/./b.txt"))
        assertEquals("dir/x.png", Archives.safePath("C:\\dir\\x.png"))
        assertNull(Archives.safePath("../.."))
    }

    @Test
    fun zipHoldsFoldersAndFiles() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        try {
            val folder = File(dir, "Book").apply { mkdirs() }
            File(folder, "ch1.txt").writeText("one")
            File(folder, "sub").mkdirs()
            File(folder, "sub/ch2.txt").writeText("two")
            val single = File(dir, "cover.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val out = File(dir, "Book.zip")
            var files = 0
            Archives.zip(listOf(folder, single, out), out) { files = it }
            assertEquals(3, files)
            ZipFile(out).use { z ->
                val names = z.entries().toList().map { it.name }.toSet()
                assertTrue(names.containsAll(listOf("Book/", "Book/ch1.txt", "Book/sub/", "Book/sub/ch2.txt", "cover.png")))
                assertEquals("two", z.getInputStream(z.getEntry("Book/sub/ch2.txt")).readBytes().decodeToString())
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun newFileIsEmptyAndChecksTheName() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        try {
            val f = Explorer.newFile(dir, "notes.md")
            assertTrue(f.isFile && f.length() == 0L)
            assertTrue(runCatching { Explorer.newFile(dir, "notes.md") }.exceptionOrNull() is IllegalStateException)
            assertTrue(runCatching { Explorer.newFile(dir, "  ") }.exceptionOrNull() is IllegalArgumentException)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun newFolderChecksTheName() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        try {
            assertTrue(Explorer.newFolder(dir, " Notes ").isDirectory)
            assertTrue(runCatching { Explorer.newFolder(dir, "Notes") }.exceptionOrNull() is IllegalStateException)
            assertTrue(runCatching { Explorer.newFolder(dir, "a/b") }.exceptionOrNull() is IllegalArgumentException)
        } finally {
            dir.deleteRecursively()
        }
    }
}
