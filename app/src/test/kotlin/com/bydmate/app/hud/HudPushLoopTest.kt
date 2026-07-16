package com.bydmate.app.hud

import com.bydmate.app.navdata.NavGuidance
import com.bydmate.app.navdata.NavGuidanceHub
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HudPushLoopTest {

    private class FakeSink : HudEventSink {
        val events = mutableListOf<Pair<Long, ByteArray>>()
        override fun fireEvent(topic: Long, payload: ByteArray): Int {
            events.add(topic to payload)
            return 0
        }
    }

    @Before fun reset() = NavGuidanceHub.reset()

    private fun activeHub(nowMs: Long) {
        NavGuidanceHub.update(
            NavGuidance(maneuverGaode = 2, distanceMeters = 250, road = "A", speedLimit = 0),
            NavGuidanceHub.Source.A11Y, nowMs = nowMs,
        )
    }

    @Test fun `inactive hub sends nothing`() {
        val sink = FakeSink()
        val loop = HudPushLoop(sink, nowMsProvider = { 1000L })
        assertFalse(loop.tick(wasActive = false))
        assertTrue(sink.events.isEmpty())
    }

    @Test fun `active hub sends frame on navi topic`() {
        activeHub(nowMs = 1000L)
        val sink = FakeSink()
        val loop = HudPushLoop(sink, nowMsProvider = { 1000L })
        assertTrue(loop.tick(wasActive = false))
        assertEquals(HudSomeIpBridge.TOPIC_NAVI, sink.events.single().first)
        val expected = HudProtobufBuilder.buildFrameSafe(
            maneuverGaode = 2, distanceMeters = 250, road = "A",
            etaString = null, totalDistMeters = 0, speedLimit = 0,
            maneuverIconPng = HudIconLoader.iconFor(2), speedSignPng = null,
        )
        assertArrayEquals(expected, sink.events.single().second)
    }

    @Test fun `guidance end sends single clear frame`() {
        activeHub(nowMs = 1000L)
        val sink = FakeSink()
        val loop = HudPushLoop(sink, nowMsProvider = { 1000L })
        assertTrue(loop.tick(wasActive = false))     // guidance frame
        NavGuidanceHub.reset()
        assertFalse(loop.tick(wasActive = true))     // clear frame, counter 0
        assertFalse(loop.tick(wasActive = false))    // silence
        assertEquals(2, sink.events.size)
        assertArrayEquals(HudProtobufBuilder.buildClearFrame(0), sink.events[1].second)
    }

    @Test fun `speed sign toggle off builds frame without sign`() {
        NavGuidanceHub.update(
            NavGuidance(maneuverGaode = 2, distanceMeters = 250, road = "A", speedLimit = 60),
            NavGuidanceHub.Source.A11Y, nowMs = 1000L,
        )
        val sink = FakeSink()
        val loop = HudPushLoop(sink, speedSignEnabled = { false }, nowMsProvider = { 1000L })
        loop.tick(wasActive = false)
        val expected = HudProtobufBuilder.buildFrameSafe(
            maneuverGaode = 2, distanceMeters = 250, road = "A",
            etaString = null, totalDistMeters = 0, speedLimit = 60,
            maneuverIconPng = HudIconLoader.iconFor(2), speedSignPng = null,
        )
        assertArrayEquals(expected, sink.events.single().second)
    }

    @Test fun `eta string format`() {
        val loop = HudPushLoop(FakeSink(), nowMsProvider = { 1_000_000_000_000L })
        assertEquals(null, loop.etaString(0))
        assertTrue(loop.etaString(1620)!!.matches(Regex("""\d{2}:\d{2}""")))
    }
}
