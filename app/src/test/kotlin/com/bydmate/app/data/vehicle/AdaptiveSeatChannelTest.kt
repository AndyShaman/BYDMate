package com.bydmate.app.data.vehicle

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveSeatChannelTest {
    private class FakeStore(var w: SeatChannel = SeatChannel.UNKNOWN) : SeatChannelStore {
        override fun winner() = w
        override fun setWinner(c: SeatChannel) { w = c }
    }
    private class RecordingWriter(val script: (String, Int) -> WriteOutcome) : SeatWriter {
        val calls = mutableListOf<Pair<String, Int>>()
        override suspend fun write(actionName: String, value: Int): WriteOutcome {
            calls += actionName to value; return script(actionName, value)
        }
    }

    @Test fun `unknown primary REAL persists PRIMARY and skips fallback`() = runTest {
        val store = FakeStore()
        val writer = RecordingWriter { _, _ -> WriteOutcome.REAL }
        val ch = AdaptiveSeatChannel(writer, store)
        assertTrue(ch.actuate(SeatGroup.DRIVER_VENT, 1))
        assertEquals(SeatChannel.PRIMARY, store.winner())
        // primary = switch then level; no fallback action present
        assertTrue(writer.calls.any { it.first == "driver_seat_vent_switch" && it.second == 1 })
        assertTrue(writer.calls.any { it.first == "driver_seat_vent_level" && it.second == 1 })
        assertFalse(writer.calls.any { it.first.endsWith("_fallback") })
    }

    @Test fun `unknown primary PERMANENT triggers fallback REAL persists FALLBACK`() = runTest {
        val store = FakeStore()
        val writer = RecordingWriter { name, _ ->
            if (name == "driver_seat_vent_switch") WriteOutcome.PERMANENT_DENIED else WriteOutcome.REAL
        }
        val ch = AdaptiveSeatChannel(writer, store)
        assertTrue(ch.actuate(SeatGroup.DRIVER_VENT, 3))
        assertEquals(SeatChannel.FALLBACK, store.winner())
        // fallback value = level+1 = 4
        assertTrue(writer.calls.any { it.first == "driver_seat_vent_fallback" && it.second == 4 })
    }

    @Test fun `unknown primary NOOP triggers fallback`() = runTest {
        val store = FakeStore()
        val writer = RecordingWriter { name, _ ->
            if (name == "driver_seat_heat_switch") WriteOutcome.NOOP else WriteOutcome.REAL
        }
        val ch = AdaptiveSeatChannel(writer, store)
        assertTrue(ch.actuate(SeatGroup.DRIVER_HEAT, 1))
        assertEquals(SeatChannel.FALLBACK, store.winner())
    }

    @Test fun `transient primary does NOT change winner and does NOT call fallback`() = runTest {
        val store = FakeStore()
        val writer = RecordingWriter { _, _ -> WriteOutcome.TRANSIENT }
        val ch = AdaptiveSeatChannel(writer, store)
        assertFalse(ch.actuate(SeatGroup.DRIVER_VENT, 1))
        assertEquals(SeatChannel.UNKNOWN, store.winner())
        assertFalse(writer.calls.any { it.first.endsWith("_fallback") })
    }

    @Test fun `cached PRIMARY uses primary directly`() = runTest {
        val store = FakeStore(SeatChannel.PRIMARY)
        val writer = RecordingWriter { _, _ -> WriteOutcome.REAL }
        val ch = AdaptiveSeatChannel(writer, store)
        assertTrue(ch.actuate(SeatGroup.PASSENGER_VENT, 0))
        // off = switch=2 only (2=off on dev=1000; 0 is a silent no-op), no level
        assertEquals(listOf("passenger_seat_vent_switch" to 2), writer.calls)
    }

    @Test fun `cached FALLBACK uses fallback directly with off=1`() = runTest {
        val store = FakeStore(SeatChannel.FALLBACK)
        val writer = RecordingWriter { _, _ -> WriteOutcome.REAL }
        val ch = AdaptiveSeatChannel(writer, store)
        assertTrue(ch.actuate(SeatGroup.PASSENGER_VENT, 0))
        assertEquals(listOf("passenger_seat_vent_fallback" to 1), writer.calls)
    }

    @Test fun `both channels fail keeps UNKNOWN and returns false`() = runTest {
        val store = FakeStore()
        val writer = RecordingWriter { _, _ -> WriteOutcome.PERMANENT_DENIED }
        val ch = AdaptiveSeatChannel(writer, store)
        assertFalse(ch.actuate(SeatGroup.DRIVER_HEAT, 2))
        assertEquals(SeatChannel.UNKNOWN, store.winner())
    }

    // ── Level-count safety (5 stages vs 3): probe with a universal value, never the
    //    requested high stage; a high-stage no-op must not un-learn the channel. ──────

    @Test fun `fallback probe uses universal stage-1 value before applying requested stage`() = runTest {
        val store = FakeStore()
        val writer = RecordingWriter { name, _ ->
            if (name == "driver_seat_vent_switch") WriteOutcome.PERMANENT_DENIED else WriteOutcome.REAL
        }
        val ch = AdaptiveSeatChannel(writer, store)
        assertTrue(ch.actuate(SeatGroup.DRIVER_VENT, 5))
        assertEquals(SeatChannel.FALLBACK, store.winner())
        // probe at stage-1 (value 2) precedes the requested stage-5 apply (value 6)
        val fb = writer.calls.filter { it.first == "driver_seat_vent_fallback" }
        assertEquals(listOf(2, 6), fb.map { it.second })
    }

    @Test fun `applied high stage NOOP on 3-stage car keeps FALLBACK winner`() = runTest {
        val store = FakeStore()
        // 3-stage car: dev=1000 denied, fallback stage-1 probe REAL, stage-5 value NOOP
        val writer = RecordingWriter { name, value ->
            when {
                name == "driver_seat_heat_switch" -> WriteOutcome.PERMANENT_DENIED
                name == "driver_seat_heat_fallback" && value == 6 -> WriteOutcome.NOOP
                else -> WriteOutcome.REAL
            }
        }
        val ch = AdaptiveSeatChannel(writer, store)
        assertTrue(ch.actuate(SeatGroup.DRIVER_HEAT, 5))    // probe REAL → channel confirmed
        assertEquals(SeatChannel.FALLBACK, store.winner())  // NOT un-learned by the stage-5 NOOP
    }

    @Test fun `cached FALLBACK high stage NOOP keeps winner and returns false`() = runTest {
        val store = FakeStore(SeatChannel.FALLBACK)
        val writer = RecordingWriter { _, value -> if (value == 6) WriteOutcome.NOOP else WriteOutcome.REAL }
        val ch = AdaptiveSeatChannel(writer, store)
        assertFalse(ch.actuate(SeatGroup.PASSENGER_HEAT, 5))   // stage 5 → value 6 → NOOP
        assertEquals(SeatChannel.FALLBACK, store.winner())     // cached path never resets
        assertEquals(listOf("passenger_seat_heat_fallback" to 6), writer.calls)  // single direct write
    }
}
