package nexplorer.reader

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SignatureTest {
    @Test
    fun drawingIsMovedToTheCornerWithRoomForThePen() {
        val s = Signatures.fromDrawing("1", "Me", 7, 4f, listOf(floatArrayOf(100f, 50f, 140f, 80f)))!!
        val pad = 4f / 2 + 1
        assertEquals(40f + 2 * pad, s.w, 0.001f)
        assertEquals(30f + 2 * pad, s.h, 0.001f)
        assertArrayEquals(floatArrayOf(pad, pad, 40f + pad, 30f + pad), s.strokes[0], 0.001f)
    }

    @Test
    fun aDotGetsASecondPointAndNothingDrawnIsNull() {
        val s = Signatures.fromDrawing("1", "Dot", 0, 2f, listOf(floatArrayOf(5f, 5f)))!!
        assertEquals(4, s.strokes[0].size)
        assertNull(Signatures.fromDrawing("1", "None", 0, 2f, listOf(floatArrayOf())))
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val s = Signatures.fromDrawing("42", "Full\nname", 4, 3.5f, listOf(floatArrayOf(0f, 0f, 10f, 5f, 20f, 0f), floatArrayOf(3f, 3f, 4f, 4f)))!!
        val back = Signatures.decode("42", Signatures.encode(s))
        assertNotNull(back)
        back!!
        assertEquals("Full name", back.name)
        assertEquals(4, back.color)
        assertEquals(3.5f, back.width, 0f)
        assertEquals(s.w, back.w, 0f)
        assertEquals(2, back.strokes.size)
        assertArrayEquals(s.strokes[0], back.strokes[0], 0f)
        assertNull(Signatures.decode("x", "only a name"))
    }

    @Test
    fun newBoxKeepsTheShapeAndStaysOnThePage() {
        val s = Signature("1", "", 0, 2f, 300f, 100f, listOf(floatArrayOf(0f, 0f, 1f, 1f)))
        val r = Signatures.newAt(s, 500f, 10f, 595f, 842f)
        assertEquals(3f, r.w / r.h, 0.001f)
        assertEquals(Signatures.START_W, r.w, 0.001f)
        assertEquals(595f - r.w, r.x, 0.001f)
    }

    @Test
    fun smoothingKeepsTheEndsAndAddsPoints() {
        val s = floatArrayOf(0f, 0f, 10f, 10f, 20f, 0f, 30f, 10f)
        val out = Signatures.smooth(s)
        assertArrayEquals(floatArrayOf(0f, 0f), out.copyOfRange(0, 2), 0f)
        assertArrayEquals(floatArrayOf(30f, 10f), out.copyOfRange(out.size - 2, out.size), 0f)
        assertEquals(2 + 2 * 4 * 2 + 2, out.size)
        for (i in out.indices step 2) assertEquals(true, out[i] in 0f..30f && out[i + 1] in 0f..10f)
    }

    @Test
    fun fractionsUndoCorner() {
        val r = TextBox.Rect(100f, 200f, 80f, 40f)
        for (rot in listOf(0f, 30f, 90f, 215f)) {
            val (x, y) = TextBox.corner(r, rot, 0.25f, 0.9f)
            val (fx, fy) = TextBox.fractions(r, rot, x, y)
            assertEquals(0.25f, fx, 0.001f)
            assertEquals(0.9f, fy, 0.001f)
        }
    }

    @Test
    fun resizeKeepingShape() {
        val r = TextBox.Rect(0f, 0f, 100f, 50f)
        val out = TextBox.resizeBottomRight(r, 0f, 300f, 60f, TextBox.MIN_H, keepShape = true)
        assertEquals(300f, out.w, 0.001f)
        assertEquals(150f, out.h, 0.001f)
    }
}
