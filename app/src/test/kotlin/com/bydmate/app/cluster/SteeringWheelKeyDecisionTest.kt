package com.bydmate.app.cluster

import org.junit.Assert.assertEquals
import org.junit.Test

class SteeringWheelKeyDecisionTest {

    @Test fun `right star down while enabled toggles and is consumed`() {
        assertEquals(StarDecision.CONSUME_AND_TOGGLE, starDecision(RIGHT_STAR_KEYCODE, isDown = true, enabled = true))
    }

    @Test fun `right star up while enabled is consumed without toggling`() {
        assertEquals(StarDecision.CONSUME, starDecision(RIGHT_STAR_KEYCODE, isDown = false, enabled = true))
    }

    @Test fun `right star while disabled passes through to native action`() {
        assertEquals(StarDecision.PASS_THROUGH, starDecision(RIGHT_STAR_KEYCODE, isDown = true, enabled = false))
    }

    @Test fun `left star and right-star long-press always pass through`() {
        assertEquals(StarDecision.PASS_THROUGH, starDecision(305, isDown = true, enabled = true))  // left star short
        assertEquals(StarDecision.PASS_THROUGH, starDecision(352, isDown = true, enabled = true))  // right star LONG
        assertEquals(StarDecision.PASS_THROUGH, starDecision(309, isDown = true, enabled = true))  // cluster carousel
    }
}
