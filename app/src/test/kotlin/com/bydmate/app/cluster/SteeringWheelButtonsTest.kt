package com.bydmate.app.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SteeringWheelButtonsTest {

    @Test fun `name table covers our and competitor known keycodes`() {
        val expected = setOf(351, 305, 309, 310, 320, 321, 383)
        assertEquals(expected, KNOWN_BUTTON_NAMES.keys)
    }

    @Test fun `every known keycode maps to a real string resource`() {
        // R.string ids are non-zero generated ints; 0 would mean a missing resource reference.
        assertTrue(KNOWN_BUTTON_NAMES.values.all { it != 0 })
    }
}
