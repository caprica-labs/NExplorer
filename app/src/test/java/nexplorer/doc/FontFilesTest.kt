package nexplorer.doc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FontFilesTest {
    private val phone = setOf(
        "NotoSansCJK-Regular.ttc", "NotoNaskhArabic-Regular.ttf", "NotoNaskhArabic-Bold.ttf",
        "NotoSansDevanagari-VF.ttf", "NotoSansDevanagariUI-VF.ttf", "NotoSansThai-Regular.ttf", "NotoSansThai-Bold.ttf",
        "NotoSerifTibetan-VF.ttf", "NotoSansOldItalic-Regular.ttf", "NotoSansSymbols-Regular-Subsetted.ttf",
    )

    @Test
    fun picksThePhonesFontForEachScript() {
        assertEquals("NotoSansCJK-Regular.ttc" to 0, FontFiles.pick("JP", false, phone))
        assertEquals("NotoSansCJK-Regular.ttc" to 2, FontFiles.pick("SC", false, phone))
        assertEquals("NotoNaskhArabic-Regular.ttf" to 0, FontFiles.pick("Arabic", false, phone))
        assertEquals("NotoNaskhArabic-Bold.ttf" to 0, FontFiles.pick("Arabic", true, phone))
        assertEquals("NotoSansDevanagari-VF.ttf" to 0, FontFiles.pick("Devanagari", false, phone))
        assertEquals("NotoSansThai-Bold.ttf" to 0, FontFiles.pick("Thai", true, phone))
        assertEquals("NotoSerifTibetan-VF.ttf" to 0, FontFiles.pick("Tibetan", false, phone))
        assertEquals("NotoSansOldItalic-Regular.ttf" to 0, FontFiles.pick("Old_Italic", false, phone))
        // Symbols and math amid Latin text.
        assertEquals("NotoSansSymbols-Regular-Subsetted.ttf" to 0, FontFiles.pick("Latin", false, phone))
        assertNull(FontFiles.pick("Klingon", false, phone))
    }
}
