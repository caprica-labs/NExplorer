package nexplorer.reader

import nexplorer.doc.PositionMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ViewSettingsTest {
    @Test
    fun defaultsAreVerticalScrollReflowOff() {
        val d = ViewSettings.DEFAULT
        assertEquals(Direction.Vertical, d.direction)
        assertEquals(Movement.Scroll, d.movement)
        assertFalse(d.reflow)
        assertEquals(100, d.textSizePct)
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val s = ViewSettings(Direction.Horizontal, Movement.Paged, reflow = true, textSizePct = 130)
        assertEquals(s, ViewSettings.decode(s.encode()))
    }

    @Test
    fun badStoredValueFallsBackToDefault() {
        assertEquals(ViewSettings.DEFAULT, ViewSettings.decode(null))
        assertEquals(ViewSettings.DEFAULT, ViewSettings.decode("garbage"))
    }

    @Test
    fun oldFollowAppThemeBecomesWhatItLookedLike() {
        assertEquals(ReaderTheme.Dark, ReaderTheme.fromStored("FollowApp", appDark = true))
        assertEquals(ReaderTheme.Light, ReaderTheme.fromStored("FollowApp", appDark = false))
        assertEquals(ReaderTheme.Dark, ReaderTheme.fromStored("Dark", appDark = false))
        assertEquals(ReaderTheme.Light, ReaderTheme.fromStored(null, appDark = true))
    }

    @Test
    fun textSizeClamped() {
        val s = ViewSettings.DEFAULT
        assertEquals(110, s.withTextSize(10).textSizePct)
        assertEquals(200, s.copy(textSizePct = 200).withTextSize(10).textSizePct)
        assertEquals(60, s.copy(textSizePct = 60).withTextSize(-10).textSizePct)
    }
}

class PositionMapperTest {
    @Test
    fun toFraction() {
        assertEquals(0f, PositionMapper.toFraction(0, 10))
        assertEquals(1f, PositionMapper.toFraction(9, 10))
        assertEquals(0f, PositionMapper.toFraction(0, 1))
    }

    @Test
    fun toPage() {
        assertEquals(10, PositionMapper.toPage(0.5f, 21))
        assertEquals(4, PositionMapper.toPage(1.7f, 5))
        assertEquals(0, PositionMapper.toPage(0.3f, 1))
        assertEquals(0, PositionMapper.toPage(-1f, 5))
    }
}

class SelectionTest {
    @Test
    fun readingOrder() {
        val a = TextPos(1, 50f, 100f)
        org.junit.Assert.assertTrue(a.before(TextPos(2, 0f, 0f)))
        org.junit.Assert.assertTrue(a.before(TextPos(1, 10f, 200f)))
        org.junit.Assert.assertTrue(a.before(TextPos(1, 60f, 101f)))
        org.junit.Assert.assertFalse(a.before(TextPos(1, 40f, 99f)))
    }

    @Test
    fun movingAnEndPastTheOtherSwapsThem() {
        val s = Selection(TextPos(1, 0f, 100f), TextPos(1, 0f, 200f))
        val m = s.moved(TextPos(0, 10f, 10f), moveStart = false)
        assertEquals(TextPos(0, 10f, 10f), m.start)
        assertEquals(TextPos(1, 0f, 100f), m.end)
        assertEquals(0..1, m.pages)
    }
}
