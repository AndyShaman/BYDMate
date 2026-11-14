package com.bydmate.app.data.vehicle

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Presence check, one write and the status readback of the spoiler up/down channel. */
class SpoilerChannelTest {
    private val writes = mutableListOf<Pair<String, Int>>()
    private val reads = mutableListOf<Int>()
    private var writeOutcome = WriteOutcome.REAL
    /** Values of the car's own "not now" fids; a fid not listed here fails to read. */
    private val checks = mutableMapOf<Int, Int?>()
    /** The automatic-mode status, read by read; empty = the read fails. */
    private val auto = ArrayDeque<Int?>()
    /** Moving by default, so the standstill guard stays out of the tests that are not about it. */
    private var speedKmh: Int? = 50
    private val writer = SeatWriter { name, value -> writes += name to value; writeOutcome }

    private fun channel(config: Int?, vararg status: Int?, throwOnConfig: Boolean = false): SpoilerChannel {
        val queue = ArrayDeque(status.toList())
        return SpoilerChannel(writer, speed = { speedKmh }) { fid ->
            reads += fid
            when (fid) {
                WriteAllowlist.SPOILER_CONFIG_FID -> if (throwOnConfig) error("boom") else config
                WriteAllowlist.SPOILER_STATUS_FID -> queue.removeFirstOrNull()
                WriteAllowlist.SPOILER_AUTO_MODE_FID -> if (auto.isEmpty()) error("auto mode unread") else auto.removeFirst()
                else -> if (fid in checks) checks[fid] else error("unexpected fid $fid")
            }
        }
    }

    private fun statusReads() = reads.count { it == WriteAllowlist.SPOILER_STATUS_FID }

    @Test fun `no spoiler config values are not equipped and write nothing`() = runTest {
        for (config in listOf(0, 1, 3, 65535)) {
            val out = channel(config).actuate(up = true)
            assertEquals("config=$config", SpoilerChannel.Result.NOT_EQUIPPED, out.result)
            assertFalse(out.written)
        }
        assertTrue(writes.isEmpty())
    }

    @Test fun `an unreadable config is unreachable and writes nothing`() = runTest {
        assertEquals(SpoilerChannel.Result.UNREACHABLE, channel(null).actuate(true).result)
        assertEquals(SpoilerChannel.Result.UNREACHABLE, channel(-10011).actuate(true).result)
        assertEquals(SpoilerChannel.Result.UNREACHABLE, channel(2, throwOnConfig = true).actuate(true).result)
        assertTrue(writes.isEmpty())
    }

    // The BYD voice assistant refuses the same command on these values (ElectricTailApiImpl.GetAllError).
    @Test fun `repair mode, a spoiler warning, motor protection and the navigation pilot refuse without a write`() = runTest {
        val refusals = listOf(
            WriteAllowlist.SPOILER_REPAIR_FID to 1,
            WriteAllowlist.SPOILER_WARNING_FID to 1, // speed too high
            WriteAllowlist.SPOILER_WARNING_FID to 2,
            WriteAllowlist.SPOILER_WARNING_FID to 3,
            WriteAllowlist.SPOILER_WARNING_FID to 4,
            WriteAllowlist.SPOILER_THERMAL_FID to 1,
            WriteAllowlist.SPOILER_PILOT_FID to 4,
            WriteAllowlist.SPOILER_PILOT_FID to 6,
            WriteAllowlist.SPOILER_PILOT_FID to 8,
        )
        for ((fid, value) in refusals) {
            checks.clear()
            checks[fid] = value
            val out = channel(2, 6, 2).actuate(up = true)
            assertEquals("fid=$fid value=$value", SpoilerChannel.Result.BLOCKED, out.result)
            assertFalse(out.written)
        }
        assertTrue(writes.isEmpty())
    }

    @Test fun `clear checks and checks that fail to read do not hold the command`() = runTest {
        val clear = mapOf(
            WriteAllowlist.SPOILER_REPAIR_FID to 0, WriteAllowlist.SPOILER_WARNING_FID to 0,
            WriteAllowlist.SPOILER_THERMAL_FID to 0, WriteAllowlist.SPOILER_PILOT_FID to 5,
        )
        // 65535 and -10011 are how a fid the car does not serve answers: BYD's code sends then too.
        val unserved = clear.keys.associateWith { 65535 }
        // Only the values BYD names refuse; a neighbour value is not a refusal.
        val other = mapOf(
            WriteAllowlist.SPOILER_REPAIR_FID to 2, WriteAllowlist.SPOILER_WARNING_FID to 5,
            WriteAllowlist.SPOILER_THERMAL_FID to 2, WriteAllowlist.SPOILER_PILOT_FID to 7,
        )
        val failed = clear.keys.associateWith<Int, Int?> { null }
        for (values in listOf(clear, unserved, other, failed, mapOf(WriteAllowlist.SPOILER_WARNING_FID to -10011))) {
            checks.clear()
            checks.putAll(values)
            writes.clear()
            assertEquals("$values", SpoilerChannel.Result.OK, channel(2, 6, 2).actuate(up = true).result)
            assertEquals(listOf("spoiler_up" to 1), writes)
        }
    }

    @Test fun `up writes once and succeeds when the spoiler starts opening`() = runTest {
        val out = channel(2, 6, 6, 2).actuate(up = true)
        assertEquals(SpoilerChannel.Result.OK, out.result)
        assertEquals(listOf("spoiler_up" to 1), writes)
        assertEquals(2, out.state)
        assertTrue(out.written)
    }

    @Test fun `up on an already opened spoiler is ok after one read`() = runTest {
        val out = channel(2, 5, 5).actuate(up = true)
        assertEquals(SpoilerChannel.Result.OK, out.result)
        assertEquals(1, statusReads() - 1) // minus the "before" read
    }

    @Test fun `down writes 2 and succeeds when the spoiler starts closing`() = runTest {
        val out = channel(2, 5, 3).actuate(up = false)
        assertEquals(SpoilerChannel.Result.OK, out.result)
        assertEquals(listOf("spoiler_down" to 2), writes)
    }

    @Test fun `down on an already closed spoiler is ok`() = runTest {
        assertEquals(SpoilerChannel.Result.OK, channel(2, 6, 6).actuate(up = false).result)
    }

    // The same error code before and after says nothing about the spoiler: not "no effect".
    @Test fun `a status that reads the same error code throughout is unconfirmed`() = runTest {
        for (code in listOf(65535, -10011)) {
            val out = channel(2, code, code, code, code, code, code, code).actuate(up = true)
            assertEquals("code=$code", SpoilerChannel.Result.UNCONFIRMED, out.result)
        }
    }

    @Test fun `a status that never changes is no effect after six reads and one write`() = runTest {
        val out = channel(2, 6, 6, 6, 6, 6, 6, 6).actuate(up = true)
        assertEquals(SpoilerChannel.Result.NO_EFFECT, out.result)
        assertEquals(1, writes.size)
        assertEquals(6, statusReads() - 1)
    }

    @Test fun `anti-pinch status stops the readback and is not retried`() = runTest {
        val out = channel(2, 6, 6, 4, 6, 6).actuate(up = true)
        assertEquals(SpoilerChannel.Result.ANTI_PINCH, out.result)
        assertEquals(1, writes.size)
        assertEquals(2, statusReads() - 1)
    }

    @Test fun `failed status reads after the write are unconfirmed`() = runTest {
        val out = channel(2, 6).actuate(up = true) // queue empty after "before": every read is null
        assertEquals(SpoilerChannel.Result.UNCONFIRMED, out.result)
        assertEquals(1, writes.size)
    }

    // ── Standstill guard: BYD's assistant will not lower a raised spoiler in a standing car by
    // voice, it asks the driver to look around and tap (GetAutomaticTailCloseStatus) ──────────

    @Test fun `down on a raised spoiler in a standing car is refused without a write`() = runTest {
        checks[WriteAllowlist.SPOILER_POSITION_FID] = 100
        for (speed in listOf(0, 3, null)) { // an unknown speed does not prove the car is moving
            speedKmh = speed
            val out = channel(2, 5, 3).actuate(up = false)
            assertEquals("speed=$speed", SpoilerChannel.Result.BLOCKED, out.result)
            assertTrue(out.verdict, out.verdict.startsWith(SPOILER_STANDING))
            assertFalse(out.written)
        }
        assertTrue(writes.isEmpty())
    }

    @Test fun `down is sent while driving, when the spoiler is down, or when it is already closing`() = runTest {
        // position, speed, status before the write
        for ((position, speed, before) in listOf(Triple(100, 4, 5), Triple(10, 0, 6), Triple(100, 0, 3))) {
            checks[WriteAllowlist.SPOILER_POSITION_FID] = position
            speedKmh = speed
            writes.clear()
            val out = channel(2, before, 3).actuate(up = false)
            assertEquals("position=$position speed=$speed before=$before", SpoilerChannel.Result.OK, out.result)
            assertEquals(listOf("spoiler_down" to 2), writes)
        }
    }

    @Test fun `up is never held by the standstill guard`() = runTest {
        checks[WriteAllowlist.SPOILER_POSITION_FID] = 100
        speedKmh = 0
        assertEquals(SpoilerChannel.Result.OK, channel(2, 6, 2).actuate(up = true).result)
        assertEquals(listOf("spoiler_up" to 1), writes)
    }

    // ── Automatic mode on (fid 1276182596 = 1), read back on the mode status (2 = automatic) ──

    @Test fun `auto on writes 1 once and succeeds when the mode reads automatic`() = runTest {
        auto.addAll(listOf(1, 1, 2))
        val out = channel(2, 6).autoOn()
        assertEquals(SpoilerChannel.Result.OK, out.result)
        assertEquals(listOf("spoiler_auto_on" to 1), writes)
        assertEquals(2, out.state)
        assertTrue(out.written)
    }

    @Test fun `auto on with the mode already automatic is ok without a write`() = runTest {
        auto.add(2)
        val out = channel(2, 6).autoOn()
        assertEquals(SpoilerChannel.Result.OK, out.result)
        assertFalse(out.written)
        assertTrue(writes.isEmpty())
    }

    // Automatic mode lowers a raised spoiler at standstill, so the same guard holds it.
    @Test fun `auto on with a raised spoiler in a standing car is refused without a write`() = runTest {
        auto.add(1)
        checks[WriteAllowlist.SPOILER_POSITION_FID] = 100
        speedKmh = 0
        val out = channel(2, 5).autoOn()
        assertEquals(SpoilerChannel.Result.BLOCKED, out.result)
        assertTrue(out.verdict, out.verdict.startsWith(SPOILER_STANDING))
        assertTrue(writes.isEmpty())
    }

    @Test fun `auto on writes nothing without a spoiler or on the car's own refusal`() = runTest {
        auto.add(1)
        assertEquals(SpoilerChannel.Result.NOT_EQUIPPED, channel(1).autoOn().result)
        assertEquals(SpoilerChannel.Result.UNREACHABLE, channel(null).autoOn().result)
        checks[WriteAllowlist.SPOILER_WARNING_FID] = 1
        assertEquals(SpoilerChannel.Result.BLOCKED, channel(2, 6).autoOn().result)
        assertTrue(writes.isEmpty())
    }

    @Test fun `auto on that leaves the mode manual is no effect after one write`() = runTest {
        auto.addAll(listOf(1, 1, 1, 1, 1, 1, 1))
        val out = channel(2, 6).autoOn()
        assertEquals(SpoilerChannel.Result.NO_EFFECT, out.result)
        assertEquals(1, writes.size)
    }

    @Test fun `auto on with an unreadable mode after the write is unconfirmed`() = runTest {
        auto.add(1) // every later read fails
        val out = channel(2, 6).autoOn()
        assertEquals(SpoilerChannel.Result.UNCONFIRMED, out.result)
        assertEquals(1, writes.size)
    }

    @Test fun `a transient auto on write is unreachable`() = runTest {
        writeOutcome = WriteOutcome.TRANSIENT
        auto.addAll(listOf(1, 2))
        val out = channel(2, 6).autoOn()
        assertEquals(SpoilerChannel.Result.UNREACHABLE, out.result)
        assertFalse(out.written)
        assertEquals(1, writes.size)
    }

    @Test fun `a transient write is unreachable and not read back`() = runTest {
        writeOutcome = WriteOutcome.TRANSIENT
        val out = channel(2, 6, 2).actuate(up = true)
        assertEquals(SpoilerChannel.Result.UNREACHABLE, out.result)
        assertFalse(out.written)
        assertEquals(1, writes.size)
        assertEquals(1, statusReads()) // only the "before" read
    }
}
