package com.bydmate.app.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SteeringWheelButtonsTest {

    @Test fun `name table covers our and competitor known keycodes`() {
        // 7 Leopard-3-validated / OpenBYD-known codes + 4 DiLink 5.0 generic codes (601-604)
        // reported on Tang L / Atto 3 dumpsys input traces. Adding a new keycode to
        // KNOWN_BUTTON_NAMES without listing it here breaks the test on purpose — we never want
        // a silently unlabelled keycode to ship.
        val expected = setOf(351, 305, 309, 310, 320, 321, 383, 601, 602, 603, 604)
        assertEquals(expected, KNOWN_BUTTON_NAMES.keys)
    }

    @Test fun `every known keycode maps to a real string resource`() {
        // R.string ids are non-zero generated ints; 0 would mean a missing resource reference.
        assertTrue(KNOWN_BUTTON_NAMES.values.all { it != 0 })
    }
}
