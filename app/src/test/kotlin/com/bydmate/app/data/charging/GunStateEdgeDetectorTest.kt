package com.bydmate.app.data.charging

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class GunStateEdgeDetectorTest {

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
