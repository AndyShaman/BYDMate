package com.bydmate.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #199 notification trace: a post line when the notification changes shape, else once a
 *  minute; a removal line at most once a minute, and the post after a logged removal is logged. */
class NaviNotifTraceGateTest {

    private val gate = NaviNotifTraceGate()
    private val rich = NaviNotifTraceGate.postKey(id = 1, ongoing = true, kind = "rich", maneuverGaode = 2)

    @Test fun `a steady post logs once a minute`() {
        assertTrue(gate.takePost(rich, T0))
        assertFalse(gate.takePost(rich, T0 + 1_000))
        assertFalse(gate.takePost(rich, T0 + 59_999))
        assertTrue(gate.takePost(rich, T0 + 60_000))
    }

    @Test fun `a change of shape logs at once`() {
        gate.takePost(rich, T0)
        assertTrue(gate.takePost(NaviNotifTraceGate.postKey(1, true, "rich", 0), T0 + 1_000))
        assertTrue(gate.takePost(NaviNotifTraceGate.postKey(1, true, "extras", 0), T0 + 2_000))
        assertTrue(gate.takePost(NaviNotifTraceGate.postKey(1, false, "extras", 0), T0 + 3_000))
        assertTrue(gate.takePost(NaviNotifTraceGate.postKey(2, false, "extras", 0), T0 + 4_000))
        assertTrue(gate.takePost(rich, T0 + 5_000))
    }

    @Test fun `another maneuver code of the same shape is no change`() {
        gate.takePost(rich, T0)
        assertFalse(gate.takePost(NaviNotifTraceGate.postKey(1, true, "rich", 5), T0 + 1_000))
    }

    @Test fun `removals log at most once a minute`() {
        assertTrue(gate.takeRemoval(T0))
        assertFalse(gate.takeRemoval(T0 + 59_999))
        assertTrue(gate.takeRemoval(T0 + 60_000))
    }

    @Test fun `the post after a logged removal is logged, after a silent one it is not`() {
        gate.takePost(rich, T0)
        gate.takeRemoval(T0 + 1_000)
        assertTrue(gate.takePost(rich, T0 + 2_000))
        assertFalse(gate.takeRemoval(T0 + 3_000))
        assertFalse(gate.takePost(rich, T0 + 4_000))
    }

    @Test fun `the lines carry numbers and ids, no text`() {
        assertEquals(
            "navi notif posted: pkg=ru.yandex.yandexnavi id=1 ongoing=true channel=navi kind=rich man=2 dist=300 roadLen=6",
            NaviNotifTraceGate.postLine("ru.yandex.yandexnavi", 1, true, "navi", "rich", 2, 300, 6),
        )
        assertEquals(
            "navi notif posted: pkg=ru.yandex.yandexmaps id=3 ongoing=false channel=null kind=empty man=0 dist=0 roadLen=0",
            NaviNotifTraceGate.postLine("ru.yandex.yandexmaps", 3, false, null, "empty", 0, 0, 0),
        )
        assertEquals("navi notif removed: pkg=ru.yandex.yandexnavi id=1",
            NaviNotifTraceGate.removedLine("ru.yandex.yandexnavi", 1))
    }

    private companion object {
        const val T0 = 1_000_000L
    }
}
