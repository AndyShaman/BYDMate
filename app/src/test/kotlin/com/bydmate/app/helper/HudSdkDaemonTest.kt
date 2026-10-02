package com.bydmate.app.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** TX_HUD_SDK body: way 3's instrument SDK calls found by reflection, never a crash. */
class HudSdkDaemonTest {

    /** Stand-in for BYDAutoInstrumentDevice: records the calls, answers like the SDK. */
    class FakeInstrument(private val answer: Int = 0, private val deny: Boolean = false) {
        val calls = mutableListOf<String>()
        fun sendSimpleGuidanceInfo(simpleType: Int, distance: Int): Int = answer("guidance $simpleType/$distance")
        fun sendNextPathName(name: String): Int = answer("road $name")
        fun sendRestRouteInfo(restHour: Int, restMinute: Int, restMileage: Long): Int =
            answer("rest $restHour/$restMinute/$restMileage")
        private fun answer(call: String): Int {
            calls += call
            if (deny) throw SecurityException("[set] permission deny! Main St")
            return answer
        }
    }

    class OldInstrument

    private val guidance = HudSdkInvocation.guidance(2, 300)
    private val road = HudSdkInvocation.pathName("Main St")
    private val rest = HudSdkInvocation.restRoute(1, 5, 12_000L)

    @Test fun `calls each SDK method with its arguments and returns the answer`() {
        val device = FakeInstrument(answer = 0)
        listOf(guidance, road, rest).forEach {
            assertEquals(HelperBinderProtocol.HUD_NAVI_CALLED to 0, hudSdkOutcome(it) { device })
        }
        assertEquals(listOf("guidance 2/300", "road Main St", "rest 1/5/12000"), device.calls)
    }

    @Test fun `negative SDK answer is forwarded, not hidden`() {
        val device = FakeInstrument(answer = -2147482645)
        assertEquals(HelperBinderProtocol.HUD_NAVI_CALLED to -2147482645, hudSdkOutcome(guidance) { device })
    }

    @Test fun `firmware without the method answers absent`() {
        listOf(guidance, road, rest).forEach {
            assertEquals(HelperBinderProtocol.HUD_NAVI_ABSENT to 0, hudSdkOutcome(it) { OldInstrument() })
        }
    }

    @Test fun `SDK exception answers threw`() {
        val device = FakeInstrument(deny = true)
        assertEquals(HelperBinderProtocol.HUD_NAVI_THREW to 0, hudSdkOutcome(road) { device })
        assertTrue(device.calls.isNotEmpty())
    }

    @Test fun `device that cannot be built answers threw`() {
        assertEquals(HelperBinderProtocol.HUD_NAVI_THREW to 0, hudSdkOutcome(rest) { error("daemon has no system Context") })
    }
}
