package com.bydmate.app.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteeringWheelKeyDecisionTest {

    @Test fun `trigger down while enabled toggles and is consumed`() {
        assertEquals(
            StarDecision.CONSUME_AND_TOGGLE,
            starDecision(351, isDown = true, enabled = true, triggerKeyCode = 351),
        )
    }

    @Test fun `trigger up while enabled is consumed without toggling`() {
        assertEquals(
            StarDecision.CONSUME,
            starDecision(351, isDown = false, enabled = true, triggerKeyCode = 351),
        )
    }

    @Test fun `trigger while disabled passes through to native action`() {
        assertEquals(
            StarDecision.PASS_THROUGH,
            starDecision(351, isDown = true, enabled = false, triggerKeyCode = 351),
        )
    }

    @Test fun `a non-trigger key passes through even when enabled`() {
        // Right star is 351, but the user assigned the LEFT star (305): 351 must now be native.
        assertEquals(
            StarDecision.PASS_THROUGH,
            starDecision(351, isDown = true, enabled = true, triggerKeyCode = 305),
        )
    }

    @Test fun `the assigned non-default key toggles`() {
        assertEquals(
            StarDecision.CONSUME_AND_TOGGLE,
            starDecision(305, isDown = true, enabled = true, triggerKeyCode = 305),
        )
    }

    @Test fun `sentinel NO_TRIGGER_KEYCODE never matches — no key toggles projection`() {
        // Until the user has captured a button (learn mode) or chosen one manually, EVERY
        // steering-wheel key must pass through to the native handler. This is the safe default
        // for cars we have not validated (Tang L, generic DiLink 5.0).
        for (code in listOf(351, 305, 320, 309, 310, 24)) {
            assertEquals(
                "code=$code must not toggle when no trigger is assigned",
                StarDecision.PASS_THROUGH,
                starDecision(code, isDown = true, enabled = true, triggerKeyCode = NO_TRIGGER_KEYCODE),
            )
        }
    }

    @Test fun `default trigger constant is the right star`() {
        assertEquals(351, DEFAULT_TRIGGER_KEYCODE)
    }

    @Test fun `system keys, 360-view and carousel are not assignable`() {
        // System keys
        assertFalse(isAssignable(24)) // VOLUME_UP
        assertFalse(isAssignable(25)) // VOLUME_DOWN
        assertFalse(isAssignable(26)) // POWER
        assertFalse(isAssignable(4))  // BACK
        assertFalse(isAssignable(3))  // HOME
        assertFalse(isAssignable(82)) // MENU
        assertFalse(isAssignable(5))  // CALL
        assertFalse(isAssignable(6))  // ENDCALL
        // Safety-critical / reserved steering-wheel buttons
        assertFalse(isAssignable(310)) // 360 view
        assertFalse(isAssignable(309)) // cluster carousel
    }

    @Test fun `steering-wheel buttons are assignable`() {
        assertTrue(isAssignable(351)) // right star
        assertTrue(isAssignable(305)) // left star
        assertTrue(isAssignable(320)) // voice assistant
        assertTrue(isAssignable(321)) // aux left
        assertTrue(isAssignable(383)) // aux right
    }

    @Test fun `learn captures an assignable key on the down edge`() {
        assertEquals(LearnAction.CAPTURE, learnDecision(305, isDown = true))
    }

    @Test fun `learn rejects a blocked key on the down edge`() {
        assertEquals(LearnAction.REJECT, learnDecision(309, isDown = true)) // carousel blocked
        assertEquals(LearnAction.REJECT, learnDecision(24, isDown = true))  // volume blocked
    }

    @Test fun `learn consumes the up edge silently`() {
        assertEquals(LearnAction.CONSUME, learnDecision(305, isDown = false))
        assertEquals(LearnAction.CONSUME, learnDecision(309, isDown = false))
    }
}
