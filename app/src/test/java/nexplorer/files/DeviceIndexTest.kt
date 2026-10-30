package nexplorer.files

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DeviceIndexTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun touch(root: File, path: String) = File(root, path).apply { parentFile!!.mkdirs(); writeText("x") }

    @Test
    fun scansEveryRootForAllFiles() {
        val internal = tmp.newFolder("internal")
        val sd = tmp.newFolder("sd")
        touch(internal, "Books/a.pdf")
        touch(sd, "Docs/b.epub")
        touch(sd, "Music/c.mp3")
        touch(sd, ".thumbnails/t.jpg")
        val names = DeviceIndex.scan(listOf(internal, sd)).map { it.name }.sorted()
        assertEquals(listOf("a.pdf", "b.epub", "c.mp3"), names)
    }

    @Test
    fun skipsHiddenPaths() {
        val sd = tmp.newFolder("sd")
        touch(sd, "Keep/a.pdf")
        touch(sd, "Secret/b.pdf")
        FolderScanner.hidden = setOf(File(sd, "Secret").path)
        try {
            assertEquals(listOf("a.pdf"), DeviceIndex.scan(listOf(sd)).map { it.name })
        } finally {
            FolderScanner.hidden = emptySet()
        }
    }
}
