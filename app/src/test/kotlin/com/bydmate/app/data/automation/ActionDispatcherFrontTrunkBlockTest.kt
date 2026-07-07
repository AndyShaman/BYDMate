package com.bydmate.app.data.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the front-trunk (frunk) speed-0 open predicate. Open is a powered
 * external panel gated to standstill; close and the rear trunk are not opens here.
 */
class ActionDispatcherFrontTrunkBlockTest {

    private fun isOpen(cmd: String) = ActionDispatcher.isFrontTrunkOpenCommand(cmd)

    @Test fun `front trunk open is an open`() {
        assertTrue(isOpen("前备箱打开"))
    }

    @Test fun `front trunk close is not an open`() {
        assertFalse(isOpen("前备箱关闭"))
    }

    @Test fun `rear trunk open is not a front trunk open`() {
        assertFalse(isOpen("开后备箱"))
    }

    @Test fun `window open is not a front trunk open`() {
        assertFalse(isOpen("主驾打开100"))
    }
}
