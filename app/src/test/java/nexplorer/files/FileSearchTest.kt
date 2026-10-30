package nexplorer.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FileSearchTest {
    private fun files(vararg names: String) = names.map { File("/s/$it") }

    @Test
    fun prefixMatchesFirst() {
        val r = FileSearch.filter(files("uncle.pdf", "Clean.pdf"), "cle")
        assertEquals(listOf("Clean.pdf", "uncle.pdf"), r.map { it.name })
    }

    @Test
    fun caseInsensitive() {
        assertEquals(1, FileSearch.filter(files("DUNE.epub"), "dune").size)
    }

    @Test
    fun literalSpecialChars() {
        assertEquals(1, FileSearch.filter(files("c++ primer.pdf", "c.pdf"), "c++").size)
        assertEquals(1, FileSearch.filter(files("x(a).epub", "xa.epub"), "(a)").size)
    }

    @Test
    fun hiddenExcluded() {
        FolderScanner.hidden = setOf("/s/sub")
        try {
            val all = listOf(File("/s/sub/book.pdf"), File("/s/book2.pdf"))
            assertEquals(listOf("book2.pdf"), FileSearch.filter(all, "book").map { it.name })
        } finally {
            FolderScanner.hidden = emptySet()
        }
    }

    @Test
    fun blankQueryReturnsEmpty() {
        assertTrue(FileSearch.filter(files("a.pdf"), "  ").isEmpty())
    }

    @Test
    fun limitRespected() {
        val many = (1..50).map { File("/s/book$it.pdf") }
        assertEquals(10, FileSearch.filter(many, "book", limit = 10).size)
    }
}
