package com.bydmate.app.ui.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DragGestureLogicTest {

    // --- isTap (press + release within threshold) ---

    @Test
    fun `same point is a tap`() {
        assertTrue(DragGestureLogic.isTap(100, 100, 100, 100, thresholdPx = 8))
    }

    @Test
    fun `move of 7px diagonal is a tap at threshold 8`() {
        // dx=5, dy=5 → dist ≈ 7.07, below 8
        assertTrue(DragGestureLogic.isTap(100, 100, 105, 105, thresholdPx = 8))
    }

    @Test
    fun `move of 10px horizontal is a drag at threshold 8`() {
        assertFalse(DragGestureLogic.isTap(100, 100, 110, 100, thresholdPx = 8))
    }

    @Test
    fun `move of exactly threshold is still a tap (inclusive)`() {
        assertTrue(DragGestureLogic.isTap(0, 0, 8, 0, thresholdPx = 8))
    }

    // --- isInsideTrash (center-to-center distance ≤ radius) ---

    @Test
    fun `widget center equal to trash center is inside`() {
        assertTrue(DragGestureLogic.isInsideTrash(500, 900, 500, 900, radiusPx = 48))
    }

    @Test
    fun `widget center 40 px from trash center is inside radius 48`() {
        assertTrue(DragGestureLogic.isInsideTrash(500, 900, 540, 900, radiusPx = 48))
    }

    @Test
    fun `widget center 60 px from trash center is outside radius 48`() {
        assertFalse(DragGestureLogic.isInsideTrash(500, 900, 560, 900, radiusPx = 48))
    }

    @Test
    fun `widget center exactly at radius is inside (inclusive)`() {
        assertTrue(DragGestureLogic.isInsideTrash(500, 900, 548, 900, radiusPx = 48))
    }

    // --- clampToScreen (keeps widget fully visible) ---

    @Test
    fun `position fully inside screen is unchanged`() {
        val (x, y) = DragGestureLogic.clampToScreen(
            x = 100, y = 100,
            widgetWidth = 220, widgetHeight = 78,
            screenWidth = 1920, screenHeight = 1200,
        )
        assertEquals(100, x)
        assertEquals(100, y)
    }

    @Test
    fun `position past right edge is clamped to fit`() {
        val (x, _) = DragGestureLogic.clampToScreen(
            x = 1800, y = 100,
            widgetWidth = 220, widgetHeight = 78,
            screenWidth = 1920, screenHeight = 1200,
        )
        assertEquals(1920 - 220, x)
    }

    @Test
    fun `negative position is clamped to zero`() {
        val (x, y) = DragGestureLogic.clampToScreen(
            x = -20, y = -5,
            widgetWidth = 220, widgetHeight = 78,
            screenWidth = 1920, screenHeight = 1200,
        )
        assertEquals(0, x)
        assertEquals(0, y)
    }

    @Test
    fun `position past bottom is clamped`() {
        val (_, y) = DragGestureLogic.clampToScreen(
            x = 100, y = 1200,
            widgetWidth = 220, widgetHeight = 78,
            screenWidth = 1920, screenHeight = 1200,
        )
        assertEquals(1200 - 78, y)
    }
}
