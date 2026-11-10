package com.bydmate.app.ui.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetGestureLogicTest {

    @Test fun `left zone is strictly inside the first third and never for an unmeasured view`() {
        class Case(val label: String, val eventX: Float, val viewWidth: Int, val expected: Boolean)
        val cases = listOf(
            Case("left of the one-third boundary", 50f, 300, true),
            Case("past the one-third boundary", 150f, 300, false),
            Case("exactly at boundary (strict less-than)", 100f, 300, false),
            Case("unmeasured view width", 0f, 0, false),
        )
        for (c in cases) {
            assertEquals(
                "case: ${c.label}",
                c.expected,
                WidgetGestureLogic.isLeftZone(eventX = c.eventX, viewWidth = c.viewWidth, fraction = 1f / 3f),
            )
        }
    }

    @Test fun `double tap is a second tap within the window, edge included`() {
        class Case(val label: String, val prevUpMs: Long, val nowMs: Long, val expected: Boolean)
        val cases = listOf(
            Case("inside the window", 1000L, 1200L, true),
            Case("exactly the window edge (inclusive)", 1000L, 1250L, true),
            Case("past the window", 1000L, 1400L, false),
            Case("no previous tap", 0L, 200L, false),
        )
        for (c in cases) {
            assertEquals(
                "case: ${c.label}",
                c.expected,
                WidgetGestureLogic.isWithinDoubleTapWindow(prevUpMs = c.prevUpMs, nowMs = c.nowMs, windowMs = 250L),
            )
        }
    }
}
