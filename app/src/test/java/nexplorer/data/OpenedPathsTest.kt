package nexplorer.data

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenedPathsTest {
    private val set = setOf("/s/Books/a.pdf", "/s/Books/sub/b.epub", "/s/Bookshelf/c.pdf", "/s/x.pdf")

    @Test
    fun renamedFileKeepsItsMark() {
        assertEquals(
            setOf("/s/Books/A2.pdf", "/s/Books/sub/b.epub", "/s/Bookshelf/c.pdf", "/s/x.pdf"),
            OpenedPaths.moved(set, "/s/Books/a.pdf", "/s/Books/A2.pdf"),
        )
    }

    @Test
    fun movedFolderTakesItsFilesMarksButNotLookalikes() {
        assertEquals(
            setOf("/s/Library/a.pdf", "/s/Library/sub/b.epub", "/s/Bookshelf/c.pdf", "/s/x.pdf"),
            OpenedPaths.moved(set, "/s/Books", "/s/Library"),
        )
    }

    @Test
    fun deletedFolderDropsItsMarks() {
        assertEquals(setOf("/s/Bookshelf/c.pdf", "/s/x.pdf"), OpenedPaths.removed(set, "/s/Books"))
        assertEquals(set - "/s/x.pdf", OpenedPaths.removed(set, "/s/x.pdf"))
    }
}
