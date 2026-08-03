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
        var nextRc: Int = 0
        override fun fireEvent(topic: Long, payload: ByteArray): Int {
            events.add(topic to payload)
            return nextRc
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

    @Test fun `camera alert takes over icon distance and arrow`() {
        NavGuidanceHub.updateFromNotification(
            NavGuidanceHub.RichUpdate(
                maneuverGaode = 2, distanceMeters = 900, road = "A",
                cameraAlert = "camera", cameraDistanceMeters = 400, cameraIconPng = byteArrayOf(7),
            ),
            nowMs = 1000L,
        )
        val sink = FakeSink()
        HudPushLoop(sink, nowMsProvider = { 1000L }).tick(wasActive = false)
        val expected = HudProtobufBuilder.buildFrameSafe(
            maneuverGaode = 2, distanceMeters = 400, road = "A",
            etaString = null, totalDistMeters = 0, speedLimit = 0,
            maneuverIconPng = byteArrayOf(7), speedSignPng = null,
            suppressArrow = true,
        )
        assertArrayEquals(expected, sink.events.single().second)
    }

    @Test fun `camera without icon and distance falls back to maneuver values`() {
        NavGuidanceHub.updateFromNotification(
            NavGuidanceHub.RichUpdate(
                maneuverGaode = 2, distanceMeters = 900, road = "A",
                cameraAlert = "camera", cameraDistanceMeters = 0, cameraIconPng = null,
            ),
            nowMs = 1000L,
        )
        val sink = FakeSink()
        HudPushLoop(sink, nowMsProvider = { 1000L }).tick(wasActive = false)
        val expected = HudProtobufBuilder.buildFrameSafe(
            maneuverGaode = 2, distanceMeters = 900, road = "A",
            etaString = null, totalDistMeters = 0, speedLimit = 0,
            maneuverIconPng = HudIconLoader.iconFor(2), speedSignPng = null,
            suppressArrow = true,
        )
        assertArrayEquals(expected, sink.events.single().second)
    }

    @Test fun `notification png used when icon pack has no maneuver`() {
        NavGuidanceHub.updateFromNotification(
            NavGuidanceHub.RichUpdate(maneuverGaode = 0, distanceMeters = 300, road = "A",
                maneuverPng = byteArrayOf(5)),
            nowMs = 1000L,
        )
        val sink = FakeSink()
        HudPushLoop(sink, nowMsProvider = { 1000L }).tick(wasActive = false)
        val expected = HudProtobufBuilder.buildFrameSafe(
            maneuverGaode = 0, distanceMeters = 300, road = "A",
            etaString = null, totalDistMeters = 0, speedLimit = 0,
            maneuverIconPng = byteArrayOf(5), speedSignPng = null,
        )
        assertArrayEquals(expected, sink.events.single().second)
    }

    @Test fun `expired maneuver drops icon and arrow but keeps the speed limit`() {
        NavGuidanceHub.updateFromNotification(
            NavGuidanceHub.RichUpdate(maneuverGaode = 2, distanceMeters = 250, road = "A",
                maneuverPng = byteArrayOf(5)),
            nowMs = 1000L,
        )
        val now = 1000L + NavGuidanceHub.MANEUVER_TIMEOUT_MS + 1
        NavGuidanceHub.update(
            NavGuidance(distanceMeters = 250, road = "A", speedLimit = 60),
            NavGuidanceHub.Source.A11Y, nowMs = now,
        )
        val sink = FakeSink()
        HudPushLoop(sink, nowMsProvider = { now }).tick(wasActive = true)
        val expected = HudProtobufBuilder.buildFrameSafe(
            maneuverGaode = 0, distanceMeters = 250, road = "A",
            etaString = null, totalDistMeters = 0, speedLimit = 60,
            maneuverIconPng = null, speedSignPng = HudSpeedSign.render(60),
        )
        assertArrayEquals(expected, sink.events.single().second)
    }

    @Test fun `running line appears beyond 3 km`() {
        NavGuidanceHub.updateFromNotification(
            NavGuidanceHub.RichUpdate(maneuverGaode = 2, distanceMeters = 250, road = "Минское шоссе",
                etaSeconds = 3900, totalDistMeters = 12_000),
            nowMs = 1_000_000_000_000L,
        )
        val loop = HudPushLoop(FakeSink(), nowMsProvider = { 1_000_000_000_000L })
        val line = loop.runningLine(NavGuidanceHub.snapshot(1_000_000_000_000L))
        assertEquals("Минское шоссе | 01:05 мин | ${loop.etaString(3900)}", line)
    }

    @Test fun `running line absent for short remainder`() {
        NavGuidanceHub.updateFromNotification(
            NavGuidanceHub.RichUpdate(distanceMeters = 250, road = "ул. Ленина",
                etaSeconds = 600, totalDistMeters = 2000),
            nowMs = 1000L,
        )
        val loop = HudPushLoop(FakeSink(), nowMsProvider = { 1000L })
        assertEquals("ул. Ленина", loop.runningLine(NavGuidanceHub.snapshot(1000L)))
    }

    @Test fun `tick_updates_diag_counters_and_records_nonzero_rc`() {
        activeHub(nowMs = 1000L)
        val sink = FakeSink()
        val loop = HudPushLoop(sink, nowMsProvider = { 1000L })
        loop.tick(wasActive = false)
        assertEquals(1L, loop.framesSent)
        assertTrue(loop.lastFrameTs > 0L)
        assertEquals(0, loop.lastRc)
        assertEquals(0L, loop.nonZeroRcCount)

        sink.nextRc = -5
        loop.tick(wasActive = true)
        assertEquals(2L, loop.framesSent)
        assertEquals(-5, loop.lastRc)
        assertEquals(1L, loop.nonZeroRcCount)
    }
}
