package nexplorer.files

import org.junit.Assert.assertEquals
import org.junit.Test

class ArchiveTreeTest {
    private val items = listOf(
        Archives.Item("Book/ch2.txt", false, 20),
        Archives.Item("Book/ch1.txt", false, 10),
        Archives.Item("cover.png", false, 5),
        Archives.Item("Art/a/b.png", false, 1),
        Archives.Item("Book", true, 0),
    )

    @Test
    fun topShowsFoldersFirstThenFiles() {
        assertEquals(listOf("Art", "Book", "cover.png"), Archives.children(items, "").map { it.name })
        assertEquals(listOf(true, true, false), Archives.children(items, "").map { it.isDir })
    }

    @Test
    fun insideAFolder() {
        assertEquals(listOf("ch1.txt", "ch2.txt"), Archives.children(items, "Book").map { it.name })
        assertEquals(listOf("a"), Archives.children(items, "Art").map { it.name })
        assertEquals(listOf("Art/a/b.png"), Archives.children(items, "Art/a").map { it.path })
    }
}
