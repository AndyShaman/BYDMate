package com.bydmate.app.ui.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetButtonLayoutTest {

    @Test fun `pocket is button plus gap`() {
        assertEquals(62, WidgetButtonLayout.pocketPx(buttonPx = 50, gapPx = 12))
    }

    @Test fun `expanded window keeps width and adds one pocket to height`() {
        val box = WidgetButtonLayout.expandedWindow(
            collapsedX = 800, collapsedY = 400,
            panelWpx = 260, panelHpx = 108,
            buttonPx = 50, gapPx = 12,
            screenW = 1920, screenH = 1200,
        )
        assertEquals(260, box.width)
        assertEquals(108 + 62, box.height)
    }

    @Test fun `expanded window keeps x and y fixed so panel stays put`() {
        val box = WidgetButtonLayout.expandedWindow(
            collapsedX = 800, collapsedY = 400,
            panelWpx = 260, panelHpx = 108,
            buttonPx = 50, gapPx = 12,
            screenW = 1920, screenH = 1200,
        )
        assertEquals(800, box.x)
        assertEquals(400, box.y)
    }

    @Test fun `near the left edge x is unchanged since the window does not grow left`() {
        val box = WidgetButtonLayout.expandedWindow(
            collapsedX = 20, collapsedY = 400,
            panelWpx = 260, panelHpx = 108,
            buttonPx = 50, gapPx = 12,
            screenW = 1920, screenH = 1200,
        )
        assertEquals(20, box.x)
        assertEquals(260, box.width)
    }

    @Test fun `near the bottom edge the expanded window is clamped to fit`() {
        val box = WidgetButtonLayout.expandedWindow(
            collapsedX = 800, collapsedY = 1160,
            panelWpx = 260, panelHpx = 108,
            buttonPx = 50, gapPx = 12,
            screenW = 1920, screenH = 1200,
        )
        assertEquals(1200 - (108 + 62), box.y)
    }
}
