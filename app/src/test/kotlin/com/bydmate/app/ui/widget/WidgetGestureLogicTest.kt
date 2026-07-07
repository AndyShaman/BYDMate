package com.bydmate.app.ui.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetGestureLogicTest {

    // --- isLeftZone ---

    @Test fun `touch left of the one-third boundary is left zone`() {
        assertTrue(WidgetGestureLogic.isLeftZone(eventX = 50f, viewWidth = 300, fraction = 1f / 3f))
    }

    @Test fun `touch past the one-third boundary is not left zone`() {
        assertFalse(WidgetGestureLogic.isLeftZone(eventX = 150f, viewWidth = 300, fraction = 1f / 3f))
    }

    @Test fun `exactly at boundary is not left zone (strict less-than)`() {
        assertFalse(WidgetGestureLogic.isLeftZone(eventX = 100f, viewWidth = 300, fraction = 1f / 3f))
    }

    @Test fun `unmeasured view width is never left zone`() {
        assertFalse(WidgetGestureLogic.isLeftZone(eventX = 0f, viewWidth = 0, fraction = 1f / 3f))
    }

    // --- isWithinDoubleTapWindow ---

    @Test fun `second tap inside the window is a double tap`() {
        assertTrue(WidgetGestureLogic.isWithinDoubleTapWindow(prevUpMs = 1000L, nowMs = 1200L, windowMs = 250L))
    }

    @Test fun `second tap at exactly the window edge counts (inclusive)`() {
        assertTrue(WidgetGestureLogic.isWithinDoubleTapWindow(prevUpMs = 1000L, nowMs = 1250L, windowMs = 250L))
    }

    @Test fun `second tap past the window is not a double tap`() {
        assertFalse(WidgetGestureLogic.isWithinDoubleTapWindow(prevUpMs = 1000L, nowMs = 1400L, windowMs = 250L))
    }

    @Test fun `no previous tap is not a double tap`() {
        assertFalse(WidgetGestureLogic.isWithinDoubleTapWindow(prevUpMs = 0L, nowMs = 200L, windowMs = 250L))
    }
}
