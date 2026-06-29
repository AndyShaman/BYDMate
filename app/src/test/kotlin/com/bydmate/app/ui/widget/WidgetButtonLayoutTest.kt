package com.bydmate.app.ui.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetButtonLayoutTest {

    @Test fun `pocket is button plus gap`() {
        assertEquals(62, WidgetButtonLayout.pocketPx(buttonPx = 50, gapPx = 12))
    }

    @Test fun `expanded window adds one pocket to width and height`() {
        val box = WidgetButtonLayout.expandedWindow(
            collapsedX = 800, collapsedY = 400,
            panelWpx = 260, panelHpx = 108,
            buttonPx = 50, gapPx = 12,
            screenW = 1920, screenH = 1200,
        )
        assertEquals(260 + 62, box.width)
        assertEquals(108 + 62, box.height)
    }

    @Test fun `expanded window shifts x left by one pocket so panel stays put`() {
        val box = WidgetButtonLayout.expandedWindow(
            collapsedX = 800, collapsedY = 400,
            panelWpx = 260, panelHpx = 108,
            buttonPx = 50, gapPx = 12,
            screenW = 1920, screenH = 1200,
        )
        assertEquals(800 - 62, box.x)
        assertEquals(400, box.y)
    }

    @Test fun `near the left edge the expanded window is clamped to zero`() {
        val box = WidgetButtonLayout.expandedWindow(
            collapsedX = 20, collapsedY = 400,
            panelWpx = 260, panelHpx = 108,
            buttonPx = 50, gapPx = 12,
            screenW = 1920, screenH = 1200,
        )
        assertEquals(0, box.x)
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
