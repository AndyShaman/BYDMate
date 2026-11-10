package com.bydmate.app.ui.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class DragGestureLogicTest {

    @Test
    fun `isTap is true up to the threshold inclusive and false past it`() {
        class Case(val label: String, val upX: Int, val upY: Int, val expected: Boolean)
        val cases = listOf(
            Case("same point", 100, 100, true),
            // dx=5, dy=5 → dist ≈ 7.07, below 8
            Case("7px diagonal at threshold 8", 105, 105, true),
            Case("10px horizontal at threshold 8", 110, 100, false),
        )
        for (c in cases) {
            assertEquals("case: ${c.label}", c.expected, DragGestureLogic.isTap(100, 100, c.upX, c.upY, thresholdPx = 8))
        }
        assertEquals("case: exactly threshold (inclusive)", true, DragGestureLogic.isTap(0, 0, 8, 0, thresholdPx = 8))
    }

    @Test
    fun `isInsideTrash is center-to-center distance within the radius inclusive`() {
        class Case(val label: String, val widgetX: Int, val expected: Boolean)
        val cases = listOf(
            Case("widget center equal to trash center", 500, true),
            Case("40 px from trash center, radius 48", 540, true),
            Case("60 px from trash center, radius 48", 560, false),
            Case("exactly at radius (inclusive)", 548, true),
        )
        for (c in cases) {
            assertEquals("case: ${c.label}", c.expected, DragGestureLogic.isInsideTrash(500, 900, c.widgetX, 900, radiusPx = 48))
        }
    }

    @Test
    fun `clampToScreen keeps the widget fully visible`() {
        class Case(val label: String, val x: Int, val y: Int, val expected: Pair<Int, Int>)
        val cases = listOf(
            Case("fully inside screen is unchanged", 100, 100, 100 to 100),
            Case("past right edge is clamped to fit", 1800, 100, (1920 - 220) to 100),
            Case("negative position is clamped to zero", -20, -5, 0 to 0),
            Case("past bottom is clamped", 100, 1200, 100 to (1200 - 78)),
        )
        for (c in cases) {
            val clamped = DragGestureLogic.clampToScreen(
                x = c.x, y = c.y,
                widgetWidth = 220, widgetHeight = 78,
                screenWidth = 1920, screenHeight = 1200,
            )
            assertEquals("case: ${c.label}", c.expected, clamped.first to clamped.second)
        }
    }
}
