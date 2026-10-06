package com.bydmate.app.data.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WriteLogGateTest {

    private val gate = WriteLogGate(streamGapMs = 15_000L, quietWindowMs = 60_000L)

    @Test fun `sparse user writes are all logged`() {
        // Hazard on, off 20 s later, on again a minute later: each one is a line.
        assertEquals(0, gate.onWrite("1001:42", 1, true, 0L))
        assertEquals(0, gate.onWrite("1001:42", 1, true, 20_000L))
        assertEquals(0, gate.onWrite("1001:42", 1, true, 80_000L))
    }

    @Test fun `a streaming fid gets a line a minute with the count it left out`() {
        val lines = (0L..120_000L step 1_500L).mapNotNull { gate.onWrite("1028:1138753552", 1, true, it) }

        // The first write, then one per minute: 39 writes between 0 and 60 s, 39 more to 120 s.
        assertEquals(listOf(0, 39, 39), lines)
    }

    @Test fun `a refused write and a changed status are always logged`() {
        gate.onWrite("1028:5", 1, true, 0L)
        assertNull(gate.onWrite("1028:5", 1, true, 1_500L))

        assertEquals(1, gate.onWrite("1028:5", -1, false, 3_000L))
        assertEquals(0, gate.onWrite("1028:5", 1, true, 4_500L))
        assertNull(gate.onWrite("1028:5", 1, true, 6_000L))
        assertEquals(1, gate.onWrite("1028:5", 0, true, 7_500L))
        assertEquals(0, gate.onWrite("1028:5", null, false, 9_000L))
    }

    @Test fun `fids are gated apart`() {
        gate.onWrite("a", 1, true, 0L)
        assertNull(gate.onWrite("a", 1, true, 1_000L))
        assertEquals(0, gate.onWrite("b", 1, true, 1_000L))
    }
}
