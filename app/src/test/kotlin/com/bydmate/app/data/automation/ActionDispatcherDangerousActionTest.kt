package com.bydmate.app.data.automation

import com.bydmate.app.data.local.entity.ActionDef
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * П7 origin-based agent defense: the dangerous tier gate. Dangerous = door
 * unlock, rear-trunk open, disabling sentry, or placing a call. Front trunk,
 * windows, and other params are excluded. Pure companion functions.
 */
class ActionDispatcherDangerousActionTest {

    private fun isRearTrunkOpen(cmd: String) = ActionDispatcher.isRearTrunkOpenCommand(cmd)
    private fun isDangerous(action: ActionDef) = ActionDispatcher.isDangerousAction(action)

    // --- isRearTrunkOpenCommand ---

    @Test fun `rear trunk open command matches`() {
        assertTrue(isRearTrunkOpen("开后备箱"))
    }

    @Test fun `rear trunk close command does not match`() {
        assertFalse(isRearTrunkOpen("关后备箱"))
    }

    @Test fun `front trunk open command does not match rear trunk`() {
        assertFalse(isRearTrunkOpen("前备箱打开"))
    }

    // --- isDangerousAction ---

    @Test fun `door unlock param is dangerous`() {
        assertTrue(isDangerous(ActionDef("车门解锁", "", kind = "param")))
    }

    @Test fun `rear trunk open param is dangerous`() {
        assertTrue(isDangerous(ActionDef("开后备箱", "", kind = "param")))
    }

    @Test fun `front trunk open param is not dangerous`() {
        assertFalse(isDangerous(ActionDef("前备箱打开", "", kind = "param")))
    }

    @Test fun `window param is not dangerous`() {
        assertFalse(isDangerous(ActionDef("关闭车窗", "", kind = "param")))
    }

    @Test fun `sentry off is dangerous`() {
        assertTrue(isDangerous(ActionDef("sentry", "", kind = "sentry", payload = "0")))
    }

    @Test fun `sentry on is not dangerous`() {
        assertFalse(isDangerous(ActionDef("sentry", "", kind = "sentry", payload = "1")))
    }

    @Test fun `call is always dangerous`() {
        assertTrue(isDangerous(ActionDef("", "", kind = "call", payload = "{}")))
    }

    @Test fun `notification is not dangerous`() {
        assertFalse(isDangerous(ActionDef("x", "", kind = "notification")))
    }
}
