package nexplorer.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class QuadTest {
    private val full = Quad.FULL

    @Test
    fun cornerMovesOnlyThatCorner() {
        val q = full.moveCorner(0, 0.2f, 0.1f)
        assertEquals(P(0.2f, 0.1f), q.points[0])
        assertEquals(full.points.drop(1), q.points.drop(1))
    }

    @Test
    fun sideMovesBothItsCorners() {
        val q = full.moveSide(0, dx = 0f, dy = 0.1f)
        assertEquals(P(0f, 0.1f), q.points[0])
        assertEquals(P(1f, 0.1f), q.points[1])
        assertEquals(full.points[2], q.points[2])
        assertEquals(full.points[3], q.points[3])
    }

    @Test
    fun movesStayInsideTheImage() {
        val q = full.moveCorner(2, 1.5f, -0.3f)
        assertEquals(P(1f, 0f), q.points[2])
        val s = full.moveSide(1, dx = 0.5f, dy = 0f)
        assertEquals(1f, s.points[1].x, 0.0001f)
    }

    @Test
    fun handlesAreCornersThenSideMiddles() {
        val h = full.handles()
        assertEquals(8, h.size)
        assertEquals(P(0.5f, 0f), h[4])
        assertEquals(P(1f, 0.5f), h[5])
    }

    @Test
    fun displayMappingRoundTripsForEveryTurnAndMirror() {
        for (rot in listOf(0, 90, 180, 270)) for (mir in listOf(false, true)) {
            val p = P(0.2f, 0.7f)
            val d = Orient.toDisplay(p, rot, mir)
            val back = Orient.fromDisplay(d, rot, mir)
            assertEquals("rot=$rot mir=$mir", p.x, back.x, 0.0001f)
            assertEquals("rot=$rot mir=$mir", p.y, back.y, 0.0001f)
        }
        assertEquals(P(1f, 0f), Orient.toDisplay(P(0f, 0f), 90, false))
        assertEquals(P(0.8f, 0.3f), Orient.toDisplay(P(0.2f, 0.3f), 0, true))
    }

    @Test
    fun fullQuadIsNotACrop() {
        assertTrue(Quad.FULL.isFull())
        assertFalse(Quad.FULL.moveCorner(0, 0.1f, 0f).isFull())
    }
}

class PageSizeTest {
    @Test
    fun a4AndLetterInPoints() {
        val a4 = PageSizes.ALL.first { it.name == "A4" }
        assertEquals(595f, a4.w, 1f)
        assertEquals(842f, a4.h, 1f)
        val letter = PageSizes.ALL.first { it.name == "Letter" }
        assertEquals(612f, letter.w, 0.5f)
        assertEquals(792f, letter.h, 0.5f)
        assertEquals("A4", PageSizes.DEFAULT.name)
    }

    @Test
    fun sizesDescribedInTheirOwnUnits() {
        assertEquals("210 × 297 mm", PageSizes.ALL.first { it.name == "A4" }.dims)
        assertEquals("8.5 × 11 in", PageSizes.ALL.first { it.name == "Letter" }.dims)
        assertEquals("7.25 × 10.5 in", PageSizes.ALL.first { it.name == "Executive" }.dims)
        assertEquals("", PageSizes.FIT.dims)
    }

    @Test
    fun hasIsoUsAndJisSizes() {
        val names = PageSizes.ALL.map { it.name }
        for (n in listOf("A0", "A10", "B5", "C5", "Legal", "Tabloid", "Executive", "JIS B5")) {
            assertTrue(n, n in names)
        }
    }

    @Test
    fun portraitImageFitsCenteredOnPortraitPage() {
        val p = PageSizes.place(imageW = 1000, imageH = 2000, size = PageSize("T", 100f, 100f))
        assertEquals(100f, p.pageW, 0.01f)
        assertEquals(100f, p.pageH, 0.01f)
        assertEquals(50f, p.w, 0.01f)
        assertEquals(100f, p.h, 0.01f)
        assertEquals(25f, p.x, 0.01f)
        assertEquals(0f, p.y, 0.01f)
    }

    @Test
    fun landscapeImageGetsLandscapePage() {
        val a4 = PageSizes.DEFAULT
        val p = PageSizes.place(imageW = 3000, imageH = 2000, size = a4)
        assertEquals(a4.h, p.pageW, 0.01f)
        assertEquals(a4.w, p.pageH, 0.01f)
    }

    @Test
    fun fitToImageUsesTheImageShape() {
        val p = PageSizes.place(imageW = 1200, imageH = 600, size = PageSizes.FIT)
        assertEquals(2f, p.pageW / p.pageH, 0.001f)
        assertEquals(0f, p.x, 0.01f)
        assertEquals(p.pageW, p.w, 0.01f)
    }
}

class PdfNumTest {
    @Test
    fun noExponentsEver() {
        assertEquals("0", pdfNum(-3.0517578E-5f))
        assertEquals("0", pdfNum(0f))
        assertEquals("595.276", pdfNum(595.2756f))
        assertEquals("12.5", pdfNum(12.5f))
        assertEquals("-7", pdfNum(-7f))
        assertEquals("100000", pdfNum(1e5f))
    }

    @Test
    fun theScreenshotThatBrokeIt() {
        val p = PageSizes.place(1084, 2412, PageSizes.DEFAULT)
        for (v in listOf(p.w, p.h, p.x, p.y)) assertFalse(pdfNum(v), 'E' in pdfNum(v))
    }
}

class ScanListsTest {
    @Test
    fun moveItem() {
        assertEquals(listOf("b", "c", "a", "d"), Lists.move(listOf("a", "b", "c", "d"), from = 0, to = 2))
        assertEquals(listOf("d", "a", "b", "c"), Lists.move(listOf("a", "b", "c", "d"), from = 3, to = 0))
        assertEquals(listOf("a", "b"), Lists.move(listOf("a", "b"), from = 1, to = 1))
    }

    @Test
    fun pickingKeepsOrderAndRenumbers() {
        var picks = emptyList<String>()
        picks = Lists.togglePick(picks, "x")
        picks = Lists.togglePick(picks, "y")
        picks = Lists.togglePick(picks, "z")
        assertEquals(listOf("x", "y", "z"), picks)
        picks = Lists.togglePick(picks, "y")
        assertEquals(listOf("x", "z"), picks)
    }

    @Test
    fun defaultName() {
        assertEquals("NExplorer_2026-10-26_09.05.pdf", ScanNames.default(LocalDateTime.of(2026, 10, 26, 9, 5)))
    }

    @Test
    fun pdfExtensionAddedWhenMissing() {
        assertEquals("Notes.pdf", ScanNames.withExtension("Notes", "pdf"))
        assertEquals("Notes.PDF", ScanNames.withExtension("Notes.PDF", "pdf"))
        assertEquals("NExplorer.pdf", ScanNames.withExtension("  ", "pdf"))
    }
}

class FilterTest {
    private fun apply(m: FloatArray, r: Int, g: Int, b: Int): Triple<Float, Float, Float> {
        fun ch(row: Int) = (m[row * 5] * r + m[row * 5 + 1] * g + m[row * 5 + 2] * b + m[row * 5 + 4]).coerceIn(0f, 255f)
        return Triple(ch(0), ch(1), ch(2))
    }

    @Test
    fun blackAndWhiteSplitsLightFromDark() {
        val m = Filter.BlackWhite.matrix!!
        assertEquals(255f, apply(m, 200, 200, 200).first, 0.5f)
        assertEquals(0f, apply(m, 60, 60, 60).first, 0.5f)
    }

    @Test
    fun grayscaleMakesChannelsEqual() {
        val (r, g, b) = apply(Filter.Grayscale.matrix!!, 200, 50, 10)
        assertEquals(r, g, 0.5f)
        assertEquals(g, b, 0.5f)
    }

    @Test
    fun lightenAndEnhanceBrightenPaper() {
        assertTrue(apply(Filter.Lighten.matrix!!, 200, 200, 200).first > 200f)
        assertTrue(apply(Filter.Enhance.matrix!!, 220, 220, 220).first > 220f)
    }

    @Test
    fun originalChangesNothing() {
        assertEquals(null, Filter.Original.matrix)
    }
}

class SizeEstimateTest {
    @Test
    fun totalsAddPagesAndPdfOverhead() {
        val perPage = listOf(mapOf(100 to 1000L, 50 to 400L), mapOf(100 to 3000L, 50 to 600L))
        val t = SizeEstimate.total(perPage, listOf(100, 50))
        assertEquals(1000L + 3000L + 2 * SizeEstimate.PAGE_OVERHEAD + SizeEstimate.FILE_OVERHEAD, t[100])
        assertEquals(400L + 600L + 2 * SizeEstimate.PAGE_OVERHEAD + SizeEstimate.FILE_OVERHEAD, t[50])
    }

    @Test
    fun readableSizes() {
        assertEquals("≈ 850 KB", SizeEstimate.label(850L * 1024))
        assertEquals("≈ 2.4 MB", SizeEstimate.label((2.4 * 1024 * 1024).toLong()))
        assertEquals("≈ 1 KB", SizeEstimate.label(300))
    }
}
