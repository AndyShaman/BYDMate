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

    @Test fun `learn companion window is 300 ms`() {
        assertEquals(300L, LEARN_COMPANION_WINDOW_MS)
    }

    @Test fun `a different assignable key inside the window is a companion of the learned press`() {
        // Atto 3: the mic button sends 304, then 327 for the same press.
        assertTrue(isLearnCompanion(327, isDown = true, primaryKeyCode = 304, primaryAtMs = 1_000, nowMs = 1_000))
        assertTrue(isLearnCompanion(327, isDown = true, primaryKeyCode = 304, primaryAtMs = 1_000, nowMs = 1_300))
    }

    @Test fun `a key after the window is not a companion`() {
        assertFalse(isLearnCompanion(327, isDown = true, primaryKeyCode = 304, primaryAtMs = 1_000, nowMs = 1_301))
        assertFalse(isLearnCompanion(327, isDown = true, primaryKeyCode = 304, primaryAtMs = 1_000, nowMs = 999))
    }

    @Test fun `the learned key again is not a companion`() {
        assertFalse(isLearnCompanion(304, isDown = true, primaryKeyCode = 304, primaryAtMs = 1_000, nowMs = 1_100))
    }

    @Test fun `a non-assignable key is not a companion`() {
        assertFalse(isLearnCompanion(309, isDown = true, primaryKeyCode = 304, primaryAtMs = 1_000, nowMs = 1_100))
        assertFalse(isLearnCompanion(24, isDown = true, primaryKeyCode = 304, primaryAtMs = 1_000, nowMs = 1_100))
    }

    @Test fun `an up edge is not a companion`() {
        assertFalse(isLearnCompanion(327, isDown = false, primaryKeyCode = 304, primaryAtMs = 1_000, nowMs = 1_100))
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

    // Atto 3 field timing: 304 DOWN, 304 UP, then 327 DOWN 3-59 ms after the UP. A held press
    // outlives a window counted from the DOWN, so the primary's UP restarts it.
    @Test fun learn_window_restarts_from_the_primary_up_edge() {
        assertEquals(1_500L, learnWindowAnchor(keyCode = 304, primaryKeyCode = 304, anchorMs = 1_000L, eventMs = 1_500L))
        assertTrue(isLearnCompanion(327, isDown = true, primaryKeyCode = 304, primaryAtMs = 1_500L, nowMs = 1_559L))
    }

    @Test fun learn_window_is_not_moved_by_another_key() {
        assertEquals(1_000L, learnWindowAnchor(keyCode = 327, primaryKeyCode = 304, anchorMs = 1_000L, eventMs = 1_500L))
    }

    @Test fun text_keys_are_not_traceable() {
        val textKeys = listOf(7, 16, 17, 18, 29, 54, 55, 62, 66, 67, 61, 77, 144, 153, 163, 81, 216, 217)
        textKeys.forEach { assertFalse("keycode $it", isTraceableKey(it)) }
    }

    @Test fun steering_media_and_dpad_keys_are_traceable() {
        val keys = listOf(304, 305, 327, 351, 360, 24, 25, 85, 87, 88, 19, 20, 21, 22, 23, 6, 78, 80, 82, 143, 164, 215, 218)
        keys.forEach { assertTrue("keycode $it", isTraceableKey(it)) }
    }
}
