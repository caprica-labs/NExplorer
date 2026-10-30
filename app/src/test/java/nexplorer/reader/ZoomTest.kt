package nexplorer.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomTest {
    @Test
    fun noPanAtScaleOne() {
        assertEquals(0f, Zoom.clampOffset(-30f, scale = 1f, size = 100f))
    }

    @Test
    fun panLimitedToContentEdges() {
        assertEquals(-100f, Zoom.clampOffset(-500f, scale = 2f, size = 100f))
        assertEquals(0f, Zoom.clampOffset(40f, scale = 2f, size = 100f))
        assertEquals(-60f, Zoom.clampOffset(-60f, scale = 2f, size = 100f))
    }

    @Test
    fun zoomKeepsPointUnderFingers() {
        val z = Zoom.zoomAround(scale = 1f, offset = 0f, centroid = 50f, factor = 2f)
        assertEquals(2f, z.first)
        assertEquals(-50f, z.second)
    }

    @Test
    fun scaleClampedBetweenOneAndFive() {
        assertEquals(5f, Zoom.zoomAround(4f, 0f, 0f, 3f).first)
        assertEquals(1f, Zoom.zoomAround(1.2f, 0f, 0f, 0.5f).first)
    }
}
