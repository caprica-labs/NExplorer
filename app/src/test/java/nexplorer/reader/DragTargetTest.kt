package nexplorer.reader

import nexplorer.reader.Zoom.DragTarget
import org.junit.Assert.assertEquals
import org.junit.Test

class DragTargetTest {
    @Test
    fun sidewaysDragInVerticalListPans() {
        assertEquals(DragTarget.Pan, Zoom.dragTarget(dx = 30f, dy = 5f, verticalAxis = true, scrollingList = true, contentCanScroll = true, canPanAlong = true))
    }

    @Test
    fun verticalDragInVerticalListScrollsTheList() {
        assertEquals(DragTarget.Content, Zoom.dragTarget(dx = 6f, dy = -40f, verticalAxis = true, scrollingList = true, contentCanScroll = true, canPanAlong = true))
    }

    @Test
    fun atListEndAlongDragPans() {
        assertEquals(DragTarget.Pan, Zoom.dragTarget(dx = 0f, dy = 40f, verticalAxis = true, scrollingList = true, contentCanScroll = false, canPanAlong = true))
    }

    @Test
    fun pagedModePansWithinPageThenTurns() {
        assertEquals(DragTarget.Pan, Zoom.dragTarget(dx = -40f, dy = 0f, verticalAxis = false, scrollingList = false, contentCanScroll = true, canPanAlong = true))
        assertEquals(DragTarget.Content, Zoom.dragTarget(dx = -40f, dy = 0f, verticalAxis = false, scrollingList = false, contentCanScroll = true, canPanAlong = false))
    }
}
