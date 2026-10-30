package nexplorer

import org.junit.Assert.assertEquals
import org.junit.Test

class SharedNamesTest {
    @Test
    fun keepsTheNameAndAddsATypeOnlyWhenMissing() {
        assertEquals("photo.jpg", SharedNames.of("photo.jpg", "png", "Shared file"))
        assertEquals("photo.jpg", SharedNames.of("photo", "jpg", "Shared file"))
        assertEquals(".bashrc", SharedNames.of(".bashrc", null, "Shared file"))
        assertEquals("Shared file.pdf", SharedNames.of(null, "pdf", "Shared file"))
        assertEquals("a_b.txt", SharedNames.of(" a/b.txt ", null, "Shared file"))
    }
}
