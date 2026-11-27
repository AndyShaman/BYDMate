package com.bydmate.app.data.automation

import com.bydmate.app.data.local.entity.ActionDef
import org.junit.Assert.assertEquals
import org.junit.Test

/** #457: pacing gap between adjacent rule steps. */
class RuleStepPacingTest {

    @Test fun `gapBefore inserts 150 ms only between two adjacent param steps`() {
        val param = ActionDef("空调手动", "Manual")
        val delay = ActionDef("", "Pause", kind = "delay", payload = "1000")
        val notificationSound = ActionDef("", "Notify", kind = "notification_sound", payload = """{"title":"hi"}""")
        val appLaunch = ActionDef("", "Launch", kind = "app_launch", payload = """{"packageName":"com.example"}""")

        assertEquals(150L, RuleStepPacing.PARAM_GAP_MS)
        assertEquals(0L, RuleStepPacing.gapBefore(null, param))
        assertEquals(150L, RuleStepPacing.gapBefore(param, param))
        assertEquals(0L, RuleStepPacing.gapBefore(param, delay))
        assertEquals(0L, RuleStepPacing.gapBefore(delay, param))
        assertEquals(0L, RuleStepPacing.gapBefore(param, notificationSound))
        assertEquals(0L, RuleStepPacing.gapBefore(appLaunch, param))
    }
}
