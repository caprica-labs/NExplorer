package nexplorer.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EditedCopyTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun recognisesEditedCopies() {
        assertTrue(EditedCopy.isEditedCopy(File("/x/report_EditedByNExplorer.pdf")))
        assertFalse(EditedCopy.isEditedCopy(File("/x/report.pdf")))
        assertFalse(EditedCopy.isEditedCopy(File("/x/report_EditedByNExplorer copy.pdf")))
    }

    @Test
    fun takenNamesGetANumberBeforeTheSuffix() {
        val dir = tmp.root
        val original = File(dir, "report.pdf")
        assertEquals("report_EditedByNExplorer.pdf", EditedCopy.target(original, dir).name)
        File(dir, "report_EditedByNExplorer.pdf").writeText("x")
        val second = EditedCopy.target(original, dir)
        assertEquals("report (2)_EditedByNExplorer.pdf", second.name)
        assertTrue(EditedCopy.isEditedCopy(second))
    }
}
