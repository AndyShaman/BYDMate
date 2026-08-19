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

    @Test fun `knob press while disabled passes through to the native source switch`() {
        assertEquals(
            KnobDecision.PASS_THROUGH,
            knobDecision(VOLUME_KNOB_PRESS_KEYCODE, isDown = true, enabled = false),
        )
    }

    @Test fun `a non-knob key passes through even when the knob feature is on`() {
        assertEquals(KnobDecision.PASS_THROUGH, knobDecision(351, isDown = true, enabled = true))
    }

    @Test fun `knob down while enabled sends play pause and is consumed`() {
        assertEquals(
            KnobDecision.CONSUME_AND_PLAY_PAUSE,
            knobDecision(VOLUME_KNOB_PRESS_KEYCODE, isDown = true, enabled = true),
        )
    }

    @Test fun `knob up while enabled is consumed without a second play pause`() {
        assertEquals(
            KnobDecision.CONSUME,
            knobDecision(VOLUME_KNOB_PRESS_KEYCODE, isDown = false, enabled = true),
        )
    }

    @Test fun `knob keycode is KEYCODE_AUTO_MEDIA_PLAY_PAUSE`() {
        assertEquals(353, VOLUME_KNOB_PRESS_KEYCODE)
    }

    @Test fun `assigned key down fires the bound rules`() {
        assertEquals(
            SteeringKeyDecision.FIRE,
            steeringKeyDecision(305, isDown = true, assigned = true),
        )
    }

    @Test fun `assigned key up is consumed so the native action never fires`() {
        assertEquals(
            SteeringKeyDecision.CONSUME,
            steeringKeyDecision(305, isDown = false, assigned = true),
        )
    }

    @Test fun `unassigned key down passes through`() {
        assertEquals(
            SteeringKeyDecision.PASS_THROUGH,
            steeringKeyDecision(305, isDown = true, assigned = false),
        )
    }

    @Test fun `unassigned key up passes through`() {
        assertEquals(
            SteeringKeyDecision.PASS_THROUGH,
            steeringKeyDecision(305, isDown = false, assigned = false),
        )
    }
}
