package com.bydmate.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #199 notification trace: a post line when the notification changes shape (at most one
 *  such line per 5 s, the changes in between counted), else once a minute; a removal line at
 *  most once a minute, and the post after a logged removal is logged. */
class NaviNotifTraceGateTest {

    private val gate = NaviNotifTraceGate()
    private val rich = NaviNotifTraceGate.postKey(id = 1, ongoing = true, kind = "rich", maneuverGaode = 2)
    private val stub = NaviNotifTraceGate.postKey(id = 1, ongoing = true, kind = "stub", maneuverGaode = 0)

    @Test fun `a steady post logs once a minute`() {
        assertEquals(0, gate.takePost(rich, T0))
        assertNull(gate.takePost(rich, T0 + 1_000))
        assertNull(gate.takePost(rich, T0 + 59_999))
        assertEquals(0, gate.takePost(rich, T0 + 60_000))
    }

    @Test fun `a change of shape logs at once when the last line is 5 s old`() {
        gate.takePost(rich, T0)
        assertEquals(0, gate.takePost(NaviNotifTraceGate.postKey(1, true, "rich", 0), T0 + 5_000))
        assertEquals(0, gate.takePost(NaviNotifTraceGate.postKey(1, true, "extras", 0), T0 + 10_000))
        assertEquals(0, gate.takePost(NaviNotifTraceGate.postKey(1, false, "extras", 0), T0 + 15_000))
        assertEquals(0, gate.takePost(NaviNotifTraceGate.postKey(2, false, "extras", 0), T0 + 20_000))
    }

    @Test fun `another maneuver code of the same shape is no change`() {
        gate.takePost(rich, T0)
        assertNull(gate.takePost(NaviNotifTraceGate.postKey(1, true, "rich", 5), T0 + 6_000))
    }

    @Test fun `a flapping shape logs at most once per 5 s and counts the changes it skipped`() {
        val printed = (0 until 30).mapNotNull { gate.takePost(if (it % 2 == 0) rich else stub, T0 + it * 1_000L) }
        assertTrue(printed.size <= 7)
        assertEquals(listOf(0, 4, 4, 4, 4, 4), printed)
        // Every post is a change: 6 lines, 24 counted, the last 4 still pending.
        assertEquals(30, printed.size + printed.sum() + 4)
    }

    @Test fun `removals log at most once a minute`() {
        assertTrue(gate.takeRemoval(T0))
        assertFalse(gate.takeRemoval(T0 + 59_999))
        assertTrue(gate.takeRemoval(T0 + 60_000))
    }

    @Test fun `the post after a logged removal is logged, after a silent one it is not`() {
        gate.takePost(rich, T0)
        assertTrue(gate.takeRemoval(T0 + 1_000))
        // A post queued before the removal's line was written does not take its place.
        assertNull(gate.takePost(rich, T0 + 1_500))
        gate.removalWritten()
        assertEquals(0, gate.takePost(rich, T0 + 2_000))
        assertFalse(gate.takeRemoval(T0 + 3_000))
        assertNull(gate.takePost(rich, T0 + 4_000))
    }

    @Test fun `the lines carry numbers and ids, no text`() {
        assertEquals(
            "navi notif posted: pkg=ru.yandex.yandexnavi id=1 ongoing=true channel=navi kind=rich man=2 dist=300 " +
                "roadLen=6 skippedChanges=3",
            NaviNotifTraceGate.postLine("ru.yandex.yandexnavi", 1, true, "navi", "rich", 2, 300, 6, 3),
        )
        assertEquals(
            "navi notif posted: pkg=ru.yandex.yandexmaps id=3 ongoing=false channel=null kind=empty man=0 dist=0 " +
                "roadLen=0 skippedChanges=0",
            NaviNotifTraceGate.postLine("ru.yandex.yandexmaps", 3, false, null, "empty", 0, 0, 0, 0),
        )
        assertEquals("navi notif removed: pkg=ru.yandex.yandexnavi id=1",
            NaviNotifTraceGate.removedLine("ru.yandex.yandexnavi", 1))
    }

    private companion object {
        const val T0 = 1_000_000L
    }
}
