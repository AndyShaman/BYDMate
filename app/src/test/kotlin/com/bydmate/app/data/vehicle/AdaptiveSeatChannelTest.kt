package com.bydmate.app.data.vehicle

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveSeatChannelTest {
    /** Mirrors SeatChannelStorePrefs: in-memory re-probe budget, re-armed by setWinner(UNKNOWN). */
    private class FakeStore(var w: SeatChannel = SeatChannel.UNKNOWN) : SeatChannelStore {
        private var spent = false
        override fun winner() = w
        override fun setWinner(c: SeatChannel) {
            if (c == SeatChannel.UNKNOWN) spent = false
            w = c
        }
        override fun reprobeExhausted() = spent
        override fun claimReprobe(): Boolean = if (spent) false else { spent = true; true }
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

    // ── Readback verification: a status=1 write that actuates nothing (Song L / Han EV,
    //    #74/#98/#109) must not cement PRIMARY, while Leopard 3 keeps its current path. ──

    private class ScriptedReadback(val script: (SeatGroup, Int) -> Int?) : SeatReadback {
        var reads = 0
        override suspend fun read(group: SeatGroup): Int? { reads++; return script(group, reads) }
    }

    @Test fun `primary REAL confirmed by readback persists PRIMARY and skips fallback`() = runTest {
        val store = FakeStore()
        val writer = RecordingWriter { _, _ -> WriteOutcome.REAL }
        val rb = ScriptedReadback { _, _ -> 1 }   // status enum: 1 = on
        val ch = AdaptiveSeatChannel(writer, store, rb)

        assertTrue(ch.actuate(SeatGroup.DRIVER_VENT, 1))
        assertEquals(SeatChannel.PRIMARY, store.winner())
        assertEquals("one confirming read is enough", 1, rb.reads)
        assertFalse(writer.calls.any { it.first.endsWith("_fallback") })
    }

    @Test fun `switching off is verified against the off code 2`() = runTest {
        val store = FakeStore()
        val writer = RecordingWriter { _, _ -> WriteOutcome.REAL }
        val rb = ScriptedReadback { _, _ -> 2 }
        val ch = AdaptiveSeatChannel(writer, store, rb)

        assertTrue(ch.actuate(SeatGroup.DRIVER_VENT, 0))
        assertEquals(SeatChannel.PRIMARY, store.winner())
    }

    @Test fun `primary REAL with a dead status fid falls back to the second channel`() = runTest {
        val store = FakeStore()
        val writer = RecordingWriter { _, _ -> WriteOutcome.REAL }
        val rb = ScriptedReadback { _, _ -> 0 }   // Song L: fid stays 0 while the write says status=1
        val ch = AdaptiveSeatChannel(writer, store, rb)

        assertTrue(ch.actuate(SeatGroup.DRIVER_HEAT, 1))
        assertEquals("both reads must disagree before downgrading", 2, rb.reads)
        assertEquals(SeatChannel.FALLBACK, store.winner())
        assertTrue(writer.calls.any { it.first == "driver_seat_heat_fallback" })
    }

    @Test fun `a late but agreeing second read confirms PRIMARY`() = runTest {
        val store = FakeStore()
        val writer = RecordingWriter { _, _ -> WriteOutcome.REAL }
        val rb = ScriptedReadback { _, read -> if (read == 1) 2 else 1 }  // stale "off" first, then on
        val ch = AdaptiveSeatChannel(writer, store, rb)

        assertTrue(ch.actuate(SeatGroup.DRIVER_HEAT, 1))
        assertEquals(SeatChannel.PRIMARY, store.winner())
        assertFalse(writer.calls.any { it.first.endsWith("_fallback") })
    }

    @Test fun `a readback that throws leaves the write REAL`() = runTest {
        val store = FakeStore()
        val writer = RecordingWriter { _, _ -> WriteOutcome.REAL }
        val ch = AdaptiveSeatChannel(writer, store, SeatReadback { throw IllegalStateException("daemon down") })

        assertTrue(ch.actuate(SeatGroup.DRIVER_VENT, 1))
        assertEquals(SeatChannel.PRIMARY, store.winner())
        assertFalse(writer.calls.any { it.first.endsWith("_fallback") })
    }

    @Test fun `a readback that cannot complete leaves the write REAL`() = runTest {
        val store = FakeStore()
        val writer = RecordingWriter { _, _ -> WriteOutcome.REAL }
        val rb = ScriptedReadback { _, _ -> null }   // daemon unreachable — no evidence either way
        val ch = AdaptiveSeatChannel(writer, store, rb)

        assertTrue(ch.actuate(SeatGroup.DRIVER_VENT, 1))
        assertEquals(SeatChannel.PRIMARY, store.winner())
        assertEquals("a failed read ends the probe immediately", 1, rb.reads)
    }

    @Test fun `cached PRIMARY contradicted re-probes and lands on the fallback`() = runTest {
        val store = FakeStore(SeatChannel.PRIMARY)
        val writer = RecordingWriter { _, _ -> WriteOutcome.REAL }
        val rb = ScriptedReadback { _, _ -> 0 }
        val ch = AdaptiveSeatChannel(writer, store, rb)

        assertTrue(ch.actuate(SeatGroup.DRIVER_VENT, 1))
        assertEquals(SeatChannel.FALLBACK, store.winner())
        assertTrue(writer.calls.any { it.first == "driver_seat_vent_fallback" })
    }

    /** A fallback that does not confirm itself must leave the learned PRIMARY in place and
     *  must not be probed again: the seat would otherwise flicker through a dead fallback on
     *  every single command just because this model never wired its status fids. */
    @Test fun `contradicted cached PRIMARY re-probes once and keeps PRIMARY when the fallback is a no-op`() = runTest {
        val store = FakeStore(SeatChannel.PRIMARY)
        val writer = RecordingWriter { name, _ ->
            if (name.endsWith("_fallback")) WriteOutcome.NOOP else WriteOutcome.REAL
        }
        val ch = AdaptiveSeatChannel(writer, store, ScriptedReadback { _, _ -> 0 })

        assertFalse(ch.actuate(SeatGroup.DRIVER_VENT, 1))
        assertTrue("first contradiction re-probes", writer.calls.any { it.first.endsWith("_fallback") })
        assertEquals("an unconfirmed fallback must not un-learn PRIMARY", SeatChannel.PRIMARY, store.winner())

        writer.calls.clear()
        assertFalse(ch.actuate(SeatGroup.DRIVER_VENT, 1))
        assertFalse("second contradiction must not re-probe", writer.calls.any { it.first.endsWith("_fallback") })
        assertEquals(SeatChannel.PRIMARY, store.winner())
    }

    /** Same guard with a TRANSIENT fallback (car off during the re-probe): the one probe per
     *  process is spent, and the cached PRIMARY survives it. */
    @Test fun `contradicted cached PRIMARY re-probes once and keeps PRIMARY when the fallback is transient`() = runTest {
        val store = FakeStore(SeatChannel.PRIMARY)
        val writer = RecordingWriter { name, _ ->
            if (name.endsWith("_fallback")) WriteOutcome.TRANSIENT else WriteOutcome.REAL
        }
        val ch = AdaptiveSeatChannel(writer, store, ScriptedReadback { _, _ -> 0 })

        assertFalse(ch.actuate(SeatGroup.DRIVER_HEAT, 1))
        assertTrue(writer.calls.any { it.first.endsWith("_fallback") })
        assertEquals(SeatChannel.PRIMARY, store.winner())

        writer.calls.clear()
        assertFalse(ch.actuate(SeatGroup.DRIVER_HEAT, 1))
        assertFalse("second contradiction must not re-probe", writer.calls.any { it.first.endsWith("_fallback") })
        assertEquals(SeatChannel.PRIMARY, store.winner())
    }

    /** The spent re-probe also closes the UNKNOWN branch: a store that reports UNKNOWN after
     *  the one probe was used must not restart the fallback hunt on the next command. */
    @Test fun `an UNKNOWN winner after the spent re-probe does not probe the fallback again`() = runTest {
        val store = FakeStore(SeatChannel.PRIMARY)
        val writer = RecordingWriter { name, _ ->
            if (name.endsWith("_fallback")) WriteOutcome.NOOP else WriteOutcome.REAL
        }
        val ch = AdaptiveSeatChannel(writer, store, ScriptedReadback { _, _ -> 0 })

        ch.actuate(SeatGroup.DRIVER_VENT, 1)        // spends the single re-probe
        store.w = SeatChannel.UNKNOWN
        writer.calls.clear()

        assertFalse(ch.actuate(SeatGroup.DRIVER_VENT, 1))
        assertFalse(writer.calls.any { it.first.endsWith("_fallback") })
    }

    /** The manual "reset seat channel" action stores UNKNOWN, which re-arms the spent probe —
     *  the budget lives in the store, so the reset actually sends the next command back
     *  through the fallback instead of being a no-op until the process restarts. */
    @Test fun `a manual reset re-arms the spent fallback re-probe`() = runTest {
        val store = FakeStore(SeatChannel.PRIMARY)
        val writer = RecordingWriter { name, _ ->
            if (name.endsWith("_fallback")) WriteOutcome.NOOP else WriteOutcome.REAL
        }
        val ch = AdaptiveSeatChannel(writer, store, ScriptedReadback { _, _ -> 0 })

        assertFalse(ch.actuate(SeatGroup.DRIVER_VENT, 1))   // spends the single re-probe
        assertTrue(writer.calls.any { it.first.endsWith("_fallback") })

        writer.calls.clear()
        assertFalse(ch.actuate(SeatGroup.DRIVER_VENT, 1))
        assertFalse("without a reset the probe stays spent", writer.calls.any { it.first.endsWith("_fallback") })

        store.setWinner(SeatChannel.UNKNOWN)                // manual reset from the settings screen
        writer.calls.clear()
        assertFalse(ch.actuate(SeatGroup.DRIVER_VENT, 1))
        assertTrue("a reset must re-open the fallback probe", writer.calls.any { it.first.endsWith("_fallback") })

        // The re-armed probe spends itself again on the NOOP verdict — one probe per reset.
        writer.calls.clear()
        assertFalse(ch.actuate(SeatGroup.DRIVER_VENT, 1))
        assertFalse("the re-armed probe is single-shot too", writer.calls.any { it.first.endsWith("_fallback") })
    }

    /** UNKNOWN winner with both channels dead: the fallback verdict is definitive, so the probe
     *  is spent and later commands stop flickering the seat through a dead channel. */
    @Test fun `an UNKNOWN winner probes a dead fallback only once`() = runTest {
        val store = FakeStore()
        val writer = RecordingWriter { _, _ -> WriteOutcome.NOOP }
        val ch = AdaptiveSeatChannel(writer, store)

        assertFalse(ch.actuate(SeatGroup.DRIVER_HEAT, 1))
        assertTrue(writer.calls.any { it.first.endsWith("_fallback") })

        writer.calls.clear()
        assertFalse(ch.actuate(SeatGroup.DRIVER_HEAT, 1))
        assertEquals(SeatChannel.UNKNOWN, store.winner())
        assertFalse("a dead fallback is probed once", writer.calls.any { it.first.endsWith("_fallback") })
    }

    /** A TRANSIENT fallback is no verdict at all (car off during the probe), so it must not
     *  spend the budget — otherwise a working fallback stays unreachable until a manual reset. */
    @Test fun `a transient fallback probe keeps the budget for the next command`() = runTest {
        val store = FakeStore()
        val writer = RecordingWriter { name, _ ->
            if (name.endsWith("_fallback")) WriteOutcome.TRANSIENT else WriteOutcome.PERMANENT_DENIED
        }
        val ch = AdaptiveSeatChannel(writer, store)

        assertFalse(ch.actuate(SeatGroup.DRIVER_HEAT, 1))
        writer.calls.clear()

        assertFalse(ch.actuate(SeatGroup.DRIVER_HEAT, 1))
        assertTrue("a transient probe must be retried", writer.calls.any { it.first.endsWith("_fallback") })
    }

    // ── Production readback mapping: only the driver groups have a validated status fid,
    //    so a passenger command can never be contradicted (WriteAllowlist.SEAT_STATUS_FIDS). ──

    /** Mirrors VehicleApiImpl.readSeatStatus: no status fid for the group → null. */
    private fun mappedReadback(value: Int) = SeatReadback { group ->
        WriteAllowlist.SEAT_STATUS_FIDS[group]?.let { value }
    }

    @Test fun `passenger commands are never contradicted whatever the status fid reads`() = runTest {
        for (value in listOf(0, 1, 2, 3, 5, 65535, -10011)) {
            for (group in listOf(SeatGroup.PASSENGER_HEAT, SeatGroup.PASSENGER_VENT)) {
                for (level in listOf(0, 2, 5)) {
                    val store = FakeStore(SeatChannel.PRIMARY)
                    val writer = RecordingWriter { _, _ -> WriteOutcome.REAL }
                    val ch = AdaptiveSeatChannel(writer, store, mappedReadback(value))

                    assertTrue("$group level=$level read=$value", ch.actuate(group, level))
                    assertEquals("$group level=$level read=$value", SeatChannel.PRIMARY, store.winner())
                    assertFalse(
                        "$group level=$level read=$value",
                        writer.calls.any { it.first.endsWith("_fallback") },
                    )
                }
            }
        }
    }

    @Test fun `driver on is verified against 1 and driver off against 2 through the production mapping`() = runTest {
        val onStore = FakeStore()
        val onWriter = RecordingWriter { _, _ -> WriteOutcome.REAL }
        assertTrue(AdaptiveSeatChannel(onWriter, onStore, mappedReadback(1)).actuate(SeatGroup.DRIVER_HEAT, 3))
        assertEquals(SeatChannel.PRIMARY, onStore.winner())

        val offStore = FakeStore()
        val offWriter = RecordingWriter { _, _ -> WriteOutcome.REAL }
        assertTrue(AdaptiveSeatChannel(offWriter, offStore, mappedReadback(2)).actuate(SeatGroup.DRIVER_HEAT, 0))
        assertEquals(SeatChannel.PRIMARY, offStore.winner())

        // off is NOT verified against 0: a status fid stuck at 0 is the dead-fid signal.
        val deadStore = FakeStore()
        val deadWriter = RecordingWriter { _, _ -> WriteOutcome.REAL }
        AdaptiveSeatChannel(deadWriter, deadStore, mappedReadback(0)).actuate(SeatGroup.DRIVER_HEAT, 0)
        assertEquals(SeatChannel.FALLBACK, deadStore.winner())
    }

    @Test fun `cached PRIMARY with a dead status fid but a NOOP write keeps the winner`() = runTest {
        val store = FakeStore(SeatChannel.PRIMARY)
        // No contradiction: the write itself was a no-op, which the cached path has always
        // reported as a plain failure without touching the remembered channel.
        val writer = RecordingWriter { _, _ -> WriteOutcome.NOOP }
        val rb = ScriptedReadback { _, _ -> 0 }
        val ch = AdaptiveSeatChannel(writer, store, rb)

        assertFalse(ch.actuate(SeatGroup.DRIVER_VENT, 1))
        assertEquals(SeatChannel.PRIMARY, store.winner())
        assertEquals("no readback after a rejected write", 0, rb.reads)
        assertFalse(writer.calls.any { it.first.endsWith("_fallback") })
    }
}
