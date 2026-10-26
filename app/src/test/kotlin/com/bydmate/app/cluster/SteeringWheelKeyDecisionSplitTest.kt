package com.bydmate.app.cluster

import org.junit.Assert.assertEquals
import org.junit.Test

class SteeringWheelKeyDecisionSplitTest {
    @Test fun `split key first down toggles`() {
        assertEquals(SplitKeyDecision.TOGGLE,
            splitKeyDecision(383, isDown = true, repeatCount = 0, splitEnabled = true, splitKeyCode = 383))
    }
    @Test fun `split key up is consumed so its native action never fires`() {
        assertEquals(SplitKeyDecision.CONSUME,
            splitKeyDecision(383, isDown = false, repeatCount = 0, splitEnabled = true, splitKeyCode = 383))
    }
    @Test fun `a held split key is consumed without toggling again`() {
        assertEquals(SplitKeyDecision.CONSUME,
            splitKeyDecision(383, isDown = true, repeatCount = 1, splitEnabled = true, splitKeyCode = 383))
    }
    @Test fun `feature off passes the key on to rules and native`() {
        for (down in listOf(true, false)) {
            assertEquals(SplitKeyDecision.PASS_THROUGH,
                splitKeyDecision(383, isDown = down, repeatCount = 0, splitEnabled = false, splitKeyCode = 383))
        }
    }
    @Test fun `no key assigned passes every key through`() {
        for (code in listOf(0, 351, 383)) {
            assertEquals(SplitKeyDecision.PASS_THROUGH,
                splitKeyDecision(code, isDown = true, repeatCount = 0, splitEnabled = true, splitKeyCode = 0))
        }
    }
    @Test fun `other keys pass through`() {
        assertEquals(SplitKeyDecision.PASS_THROUGH,
            splitKeyDecision(351, isDown = true, repeatCount = 0, splitEnabled = true, splitKeyCode = 383))
        assertEquals(SplitKeyDecision.PASS_THROUGH,
            splitKeyDecision(351, isDown = false, repeatCount = 0, splitEnabled = true, splitKeyCode = 383))
    }
}
