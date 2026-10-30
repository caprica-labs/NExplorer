package nexplorer.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CreditsTest {
    private val dir = File("src/main/assets/licenses")

    @Test
    fun everyLicenceTextShipsWithTheApp() {
        Licence.entries.forEach { l ->
            val f = File(dir, l.file)
            assertTrue("${l.file} is missing", f.isFile && f.length() > 300)
        }
    }

    @Test
    fun noTextIsLeftUnused() {
        val used = Licence.entries.map { it.file }.toSet()
        dir.listFiles().orEmpty().forEach { assertTrue("${it.name} isn't used", it.name in used) }
    }

    @Test
    fun wrappedLinesJoinButParagraphsAndListsStay() {
        val text = "Permission is granted\nto anyone.\n\nCopyright © A\nCopyright © B\n1. First\n  indented\nend"
        assertEquals(
            "Permission is granted to anyone.\n\nCopyright © A\nCopyright © B\n1. First\n  indented end",
            reflow(text),
        )
    }

    @Test
    fun everyCreditIsFilledIn() {
        Credits.groups.flatMap { it.second }.forEach { c ->
            assertTrue(c.name, c.name.isNotBlank() && c.what.isNotBlank() && c.copyright.startsWith("Copyright ©"))
        }
    }
}
