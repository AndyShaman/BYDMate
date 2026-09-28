package com.bydmate.app.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** TX_HUD_NAVI_STATUS body: sendAutoNaviStatus found by reflection, never a crash. */
class HudNaviStatusDaemonTest {

    /** Stand-in for BYDAutoInstrumentDevice: records the calls, answers like the SDK. */
    class FakeInstrument(private val answer: Int = 0, private val deny: Boolean = false) {
        val calls = mutableListOf<Int>()
        fun sendAutoNaviStatus(status: Int): Int {
            calls += status
            if (deny) throw SecurityException("[setInt] permission deny!")
            return answer
        }
    }

    class NoNaviInstrument

    @Test fun `calls the SDK with the status and returns its answer`() {
        val device = FakeInstrument(answer = 0)
        assertEquals(HelperBinderProtocol.HUD_NAVI_CALLED to 0, sendAutoNaviStatusOutcome(2) { device })
        assertEquals(listOf(2), device.calls)
    }

    @Test fun `negative SDK answer is forwarded, not hidden`() {
        val device = FakeInstrument(answer = -2147482645)
        assertEquals(HelperBinderProtocol.HUD_NAVI_CALLED to -2147482645, sendAutoNaviStatusOutcome(4) { device })
    }

    @Test fun `probe reports the method without calling it`() {
        val device = FakeInstrument()
        assertEquals(HelperBinderProtocol.HUD_NAVI_CALLED to 0,
            sendAutoNaviStatusOutcome(HelperBinderProtocol.HUD_NAVI_PROBE) { device })
        assertTrue(device.calls.isEmpty())
    }

    @Test fun `firmware without the method answers absent`() {
        assertEquals(HelperBinderProtocol.HUD_NAVI_ABSENT to 0, sendAutoNaviStatusOutcome(2) { NoNaviInstrument() })
        assertEquals(HelperBinderProtocol.HUD_NAVI_ABSENT to 0,
            sendAutoNaviStatusOutcome(HelperBinderProtocol.HUD_NAVI_PROBE) { NoNaviInstrument() })
    }

    @Test fun `SDK SecurityException answers threw`() {
        val device = FakeInstrument(deny = true)
        assertEquals(HelperBinderProtocol.HUD_NAVI_THREW to 0, sendAutoNaviStatusOutcome(2) { device })
    }

    @Test fun `device that cannot be built answers threw`() {
        assertEquals(HelperBinderProtocol.HUD_NAVI_THREW to 0,
            sendAutoNaviStatusOutcome(2) { error("daemon has no system Context") })
    }
}
