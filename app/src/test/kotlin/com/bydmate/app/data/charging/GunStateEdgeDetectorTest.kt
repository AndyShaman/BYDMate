package com.bydmate.app.data.charging

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class GunStateEdgeDetectorTest {

    @Test
    fun `cold start with null reading does not fire`() {
        val d = GunStateEdgeDetector()
        assertFalse(d.onSample(null))
        assertNull(d.previous)
    }

    @Test
    fun `cold start with NONE does not fire`() {
        val d = GunStateEdgeDetector()
        assertFalse(d.onSample(1))
        assertEquals(1, d.previous)
    }

    @Test
    fun `cold start with AC does not fire`() {
        // First reading sets the baseline. Without a prior known state we
        // cannot tell whether the gun was just inserted or has been there
        // for a while — defer to cold-start catch-up in the service.
        val d = GunStateEdgeDetector()
        assertFalse(d.onSample(2))
        assertEquals(2, d.previous)
    }

    @Test
    fun `inserted then disconnected fires edge`() {
        val d = GunStateEdgeDetector()
        d.onSample(1)             // baseline NONE
        d.onSample(2)             // AC inserted (no edge yet)
        assertTrue(d.onSample(1)) // disconnected → EDGE
    }

    @Test
    fun `DC connected then disconnected fires edge`() {
        val d = GunStateEdgeDetector()
        d.onSample(3)             // baseline DC (cold start, no edge)
        assertTrue(d.onSample(1)) // disconnected → EDGE
    }

    @Test
    fun `AC_DC connected then disconnected fires edge`() {
        val d = GunStateEdgeDetector()
        d.onSample(4)             // baseline AC_DC
        assertTrue(d.onSample(1))
    }

    @Test
    fun `VTOL connected then disconnected fires edge`() {
        val d = GunStateEdgeDetector()
        d.onSample(5)             // VTOL
        assertTrue(d.onSample(1))
    }

    @Test
    fun `connected stable does not fire`() {
        val d = GunStateEdgeDetector()
        d.onSample(2)
        assertFalse(d.onSample(2))
        assertFalse(d.onSample(2))
    }

    @Test
    fun `transition between connected states does not fire`() {
        // Hypothetical: AC → DC handover during the same physical session.
        // No disconnect → no edge.
        val d = GunStateEdgeDetector()
        d.onSample(2)
        assertFalse(d.onSample(3))
        assertFalse(d.onSample(4))
    }

    @Test
    fun `null read after connected keeps previous, no edge`() {
        // autoservice transient sentinel — must not be confused with a real
        // disconnect, otherwise we'd record phantom rows on every read glitch.
        val d = GunStateEdgeDetector()
        d.onSample(2)
        assertFalse(d.onSample(null))
        assertEquals(2, d.previous)
        // And the next real disconnect should still fire from prev=2.
        assertTrue(d.onSample(1))
    }

    @Test
    fun `null read after disconnected keeps previous, no edge`() {
        val d = GunStateEdgeDetector()
        d.onSample(1)
        assertFalse(d.onSample(null))
        assertEquals(1, d.previous)
    }

    @Test
    fun `disconnect already disconnected does not fire`() {
        val d = GunStateEdgeDetector()
        d.onSample(1)
        assertFalse(d.onSample(1))
    }

    @Test
    fun `insertion 1 to 2 does not fire`() {
        val d = GunStateEdgeDetector()
        d.onSample(1)
        assertFalse(d.onSample(2))
        assertEquals(2, d.previous)
    }

    @Test
    fun `full cycle end-to-end`() {
        // Park, insert, charge, disconnect — only the last call fires.
        val d = GunStateEdgeDetector()
        assertFalse(d.onSample(1))   // park, no gun
        assertFalse(d.onSample(2))   // insert AC
        assertFalse(d.onSample(2))   // charging
        assertFalse(d.onSample(2))
        assertFalse(d.onSample(null)) // transient read failure mid-charge
        assertFalse(d.onSample(2))
        assertTrue(d.onSample(1))    // disconnect → fire
        assertFalse(d.onSample(1))   // stable disconnected
    }

    @Test
    fun `reset clears state`() {
        val d = GunStateEdgeDetector()
        d.onSample(2)
        d.reset()
        assertNull(d.previous)
        // After reset, first non-null reading is baseline again.
        assertFalse(d.onSample(2))
    }
}
