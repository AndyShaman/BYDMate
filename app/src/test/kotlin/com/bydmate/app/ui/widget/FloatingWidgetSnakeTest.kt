package com.bydmate.app.ui.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingWidgetSnakeTest {

    @Test fun `phase zero starts the segment at the beginning of the path`() {
        val (start, end) = snakeSegment(phase = 0f, length = 100f, fraction = 0.35f)
        assertEquals(0f, start, 0.001f)
        assertEquals(35f, end, 0.001f)
    }

    @Test fun `mid phase offsets the segment without wrapping`() {
        val (start, end) = snakeSegment(phase = 0.3f, length = 100f, fraction = 0.35f)
        assertEquals(30f, start, 0.001f)
        assertEquals(65f, end, 0.001f)
    }

    @Test fun `segment wraps around when it runs past the path length`() {
        val (start, end) = snakeSegment(phase = 0.9f, length = 100f, fraction = 0.35f)
        assertEquals(90f, start, 0.001f)
        assertEquals(25f, end, 0.001f) // end < start signals wrap-around to the caller
    }

    @Test fun `phase at exactly 1 wraps back to phase 0`() {
        val (start, end) = snakeSegment(phase = 1f, length = 100f, fraction = 0.35f)
        assertEquals(0f, start, 0.001f)
        assertEquals(35f, end, 0.001f)
    }

    @Test fun `zero length path returns a zero segment`() {
        val (start, end) = snakeSegment(phase = 0.5f, length = 0f, fraction = 0.35f)
        assertEquals(0f, start, 0.001f)
        assertEquals(0f, end, 0.001f)
    }
}
