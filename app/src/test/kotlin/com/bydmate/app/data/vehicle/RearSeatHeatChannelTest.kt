package com.bydmate.app.data.vehicle

import com.bydmate.app.data.nativestack.FidCatalog
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RearSeatHeatChannelTest {
    private val stateFid = RearSeatCatalogs.STATE_LEFT
    private val hasFid = RearSeatCatalogs.HAS_LEFT

    private val writes = mutableListOf<Pair<String, Int>>()
    private val reads = mutableListOf<Int>()
    private var outcome = WriteOutcome.REAL

    /** Per fid, the answers in order; the last one repeats. */
    private fun channel(
        queues: Map<Int, List<Int?>>,
        catalog: FidCatalog? = RearSeatCatalogs.dl4,
    ): RearSeatHeatChannel {
        val pos = mutableMapOf<Int, Int>()
        return RearSeatHeatChannel(
            SeatWriter { name, value -> writes += name to value; outcome },
            RearSeatReader { fid ->
                reads += fid
                val q = queues[fid] ?: return@RearSeatReader null
                val i = pos.getOrDefault(fid, 0)
                pos[fid] = i + 1
                q[minOf(i, q.size - 1)]
            },
            { catalog },
        )
    }

    private fun states(has: Int?, before: Int?, vararg after: Int?) =
        mapOf(hasFid to listOf(has), stateFid to listOf(before) + after.toList())

    @Test fun `a state that follows is OK and writes level plus one`() = runTest {
        val out = channel(states(1, 1, 2)).actuate(RearSeat.LEFT, 1)
        assertEquals(RearSeatHeatChannel.Result.OK, out.result)
        assertEquals(listOf("rear_left_seat_heat" to 2), writes)
    }

    @Test fun `off writes 1`() = runTest {
        val out = channel(states(1, 3, 1)).actuate(RearSeat.LEFT, 0)
        assertEquals(RearSeatHeatChannel.Result.OK, out.result)
        assertEquals(listOf("rear_left_seat_heat" to 1), writes)
    }

    @Test fun `has not 1 is NotEquipped and writes nothing`() = runTest {
        for (has in listOf(0, 2, 65535)) {
            val out = channel(states(has, 1, 2)).actuate(RearSeat.LEFT, 1)
            assertEquals("has=$has", RearSeatHeatChannel.Result.NOT_EQUIPPED, out.result)
        }
        assertTrue(writes.isEmpty())
    }

    @Test fun `an unreadable has is unreachable and writes nothing`() = runTest {
        for (has in listOf(null, -10011)) {
            val out = channel(states(has, 1, 2)).actuate(RearSeat.LEFT, 1)
            assertEquals("has=$has", RearSeatHeatChannel.Result.UNREACHABLE, out.result)
        }
        assertTrue(writes.isEmpty())
    }

    @Test fun `no catalog is unreachable, a catalog without the symbols is NotEquipped`() = runTest {
        val q = states(1, 1, 2)
        assertEquals(RearSeatHeatChannel.Result.UNREACHABLE, channel(q, catalog = null).actuate(RearSeat.LEFT, 1).result)
        assertEquals(RearSeatHeatChannel.Result.NOT_EQUIPPED, channel(q, catalog = FidCatalog.EMPTY).actuate(RearSeat.LEFT, 1).result)
        assertTrue(writes.isEmpty())
        assertFalse(stateFid in reads)
    }

    @Test fun `a write the daemon did not take is unreachable and not read back`() = runTest {
        outcome = WriteOutcome.TRANSIENT
        val out = channel(states(1, 1, 2)).actuate(RearSeat.LEFT, 1)
        assertEquals(RearSeatHeatChannel.Result.UNREACHABLE, out.result)
        assertEquals(1, reads.count { it == stateFid }) // the before-read only
    }

    @Test fun `a state that stays where it was is no effect`() = runTest {
        val out = channel(states(1, 1, 1, 1)).actuate(RearSeat.LEFT, 2)
        assertEquals(RearSeatHeatChannel.Result.NO_EFFECT, out.result)
    }

    @Test fun `an unreadable or foreign state is unconfirmed`() = runTest {
        val out = channel(states(1, 1, null, 0)).actuate(RearSeat.LEFT, 2)
        assertEquals(RearSeatHeatChannel.Result.UNCONFIRMED, out.result)
    }

    @Test fun `a late state is still OK`() = runTest {
        val out = channel(states(1, 1, 1, 2)).actuate(RearSeat.LEFT, 2)
        assertEquals(RearSeatHeatChannel.Result.OK, out.result)
    }

    @Test fun `equipped only when the full catalog and has 1`() = runTest {
        assertTrue(channel(states(1, 1)).equipped(RearSeat.LEFT))
        assertFalse(channel(states(0, 1)).equipped(RearSeat.LEFT))
        assertFalse(channel(states(null, 1)).equipped(RearSeat.LEFT))
        assertFalse(channel(states(1, 1), catalog = null).equipped(RearSeat.LEFT))
        assertFalse(channel(states(1, 1), catalog = FidCatalog.EMPTY).equipped(RearSeat.LEFT))
    }
}
