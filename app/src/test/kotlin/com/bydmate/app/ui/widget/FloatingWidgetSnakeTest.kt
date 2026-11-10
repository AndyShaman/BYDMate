package com.bydmate.app.ui.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingWidgetSnakeTest {

    @Test fun `segment wraps around when it runs past the path length`() {
        val (start, end) = snakeSegment(phase = 0.9f, length = 100f, fraction = 0.35f)
        assertEquals(90f, start, 0.001f)
        assertEquals(25f, end, 0.001f) // end < start signals wrap-around to the caller
    }

    @Test fun `zero length path returns a zero segment`() {
        val (start, end) = snakeSegment(phase = 0.5f, length = 0f, fraction = 0.35f)
        assertEquals(0f, start, 0.001f)
        assertEquals(0f, end, 0.001f)
    }
}
