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
    private val writer = SeatWriter { name, value -> writes += name to value; writeOutcome }

    private fun channel(config: Int?, vararg status: Int?, throwOnConfig: Boolean = false): SpoilerChannel {
        val queue = ArrayDeque(status.toList())
        return SpoilerChannel(writer) { fid ->
            reads += fid
            when (fid) {
                WriteAllowlist.SPOILER_CONFIG_FID -> if (throwOnConfig) error("boom") else config
                WriteAllowlist.SPOILER_STATUS_FID -> queue.removeFirstOrNull()
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

    @Test fun `a transient write is unreachable and not read back`() = runTest {
        writeOutcome = WriteOutcome.TRANSIENT
        val out = channel(2, 6, 2).actuate(up = true)
        assertEquals(SpoilerChannel.Result.UNREACHABLE, out.result)
        assertFalse(out.written)
        assertEquals(1, writes.size)
        assertEquals(1, statusReads()) // only the "before" read
    }
}
