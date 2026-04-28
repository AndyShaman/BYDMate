package com.bydmate.app.domain.calculator

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTripBufferTest {

    @Test fun `empty buffer returns null avg and 0 sessionKm`() = runBlocking {
        val b = LiveTripBuffer()
        assertNull(b.avgOverLastKm(10.0))
        assertEquals(0.0, b.sessionKm(), 0.001)
    }

    @Test fun `single sample is not enough`() = runBlocking {
        val b = LiveTripBuffer()
        b.onSample(mileage = 100.0, totalElec = 500.0, sessionId = 1L)
        assertNull(b.avgOverLastKm(10.0))
        assertEquals(0.0, b.sessionKm(), 0.001)
    }

    @Test fun `two samples 5 km apart 1 kWh consumed give 20 per 100`() = runBlocking {
        val b = LiveTripBuffer()
        b.onSample(100.0, 500.0, 1L)
        b.onSample(105.0, 501.0, 1L)
        // 1 kWh / 5 km × 100 = 20 kWh/100km
        assertEquals(20.0, b.avgOverLastKm(10.0)!!, 0.01)
        assertEquals(5.0, b.sessionKm(), 0.001)
    }

    @Test fun `sliding window takes only last N km`() = runBlocking {
        val b = LiveTripBuffer()
        // city 5 km at 20 kWh/100 (1 kWh)
        b.onSample(100.0, 500.0, 1L)
        b.onSample(105.0, 501.0, 1L)
        // highway 10 km at 30 kWh/100 (3 kWh)
        b.onSample(115.0, 504.0, 1L)
        // sessionKm = 15
        assertEquals(15.0, b.sessionKm(), 0.001)
        // window=10: cutoff at mileage 105 → first sample with mileage>=105 is 105.0 → dKm=10, dKwh=3 → 30
        assertEquals(30.0, b.avgOverLastKm(10.0)!!, 0.01)
        // window=15: includes everything → dKm=15, dKwh=4 → 26.67
        assertEquals(26.666, b.avgOverLastKm(15.0)!!, 0.01)
    }

    @Test fun `session change clears buffer`() = runBlocking {
        val b = LiveTripBuffer()
        b.onSample(100.0, 500.0, 1L)
        b.onSample(105.0, 501.0, 1L)
        b.onSample(200.0, 600.0, 2L)  // new session
        // After session change, only the new sample remains.
        assertNull(b.avgOverLastKm(10.0))
        assertEquals(0.0, b.sessionKm(), 0.001)
    }

    @Test fun `null sessionId clears buffer`() = runBlocking {
        val b = LiveTripBuffer()
        b.onSample(100.0, 500.0, 1L)
        b.onSample(105.0, 501.0, 1L)
        b.onSample(105.0, 501.5, null)  // ignition off
        assertNull(b.avgOverLastKm(10.0))
        assertEquals(0.0, b.sessionKm(), 0.001)
    }

    @Test fun `negative dKwh returns null avg`() = runBlocking {
        val b = LiveTripBuffer()
        b.onSample(100.0, 500.0, 1L)
        b.onSample(105.0, 499.0, 1L)  // BMS recalibration drop
        assertNull(b.avgOverLastKm(10.0))
    }

    @Test fun `out of order mileage skipped`() = runBlocking {
        val b = LiveTripBuffer()
        b.onSample(105.0, 501.0, 1L)
        b.onSample(100.0, 500.0, 1L)  // odometer regression — ignored
        // Only first sample stays — sessionKm=0 (need 2 samples), avg null.
        assertNull(b.avgOverLastKm(10.0))
    }

    @Test fun `null mileage or totalElec ignored`() = runBlocking {
        val b = LiveTripBuffer()
        b.onSample(null, 500.0, 1L)
        b.onSample(100.0, null, 1L)
        assertNull(b.avgOverLastKm(10.0))
    }

    @Test fun `buffer caps at MAX_SAMPLES`() = runBlocking {
        val b = LiveTripBuffer()
        repeat(2000) { i ->
            b.onSample(100.0 + i * 0.01, 500.0 + i * 0.001, 1L)
        }
        // Buffer trimmed; latest samples preserved. SessionKm reflects what's stored.
        assertTrue(b.sessionKm() > 0.0)
        assertTrue(b.sampleCount() <= LiveTripBuffer.MAX_SAMPLES)
    }

    @Test fun `reset clears state`() = runBlocking {
        val b = LiveTripBuffer()
        b.onSample(100.0, 500.0, 1L)
        b.onSample(105.0, 501.0, 1L)
        b.reset()
        assertNull(b.avgOverLastKm(10.0))
        assertEquals(0.0, b.sessionKm(), 0.001)
    }

    @Test fun `Mileage zero startup race is rejected`() = runBlocking {
        val b = LiveTripBuffer()
        b.onSample(0.0, 500.0, 1L)         // DiPars startup race
        b.onSample(50000.0, 502.0, 1L)     // real odometer
        // First glitch sample skipped → only one valid sample stored,
        // sessionKm=0 (need 2), avg=null. Without guard sessionKm would be ~50000.
        assertEquals(0.0, b.sessionKm(), 0.001)
        assertEquals(1, b.sampleCount())
        assertNull(b.avgOverLastKm(10.0))
    }

    @Test fun `excessive mileage jump triggers re-anchor`() = runBlocking {
        val b = LiveTripBuffer()
        b.onSample(100.0, 500.0, 1L)
        b.onSample(105.0, 501.0, 1L)
        b.onSample(50000.0, 502.0, 1L)     // > 100 km jump → wipe + reseed
        // Only the post-jump sample remains.
        assertEquals(1, b.sampleCount())
        assertEquals(0.0, b.sessionKm(), 0.001)
        assertNull(b.avgOverLastKm(10.0))
        // Subsequent normal samples build a fresh session.
        b.onSample(50005.0, 503.0, 1L)
        assertEquals(5.0, b.sessionKm(), 0.001)
        assertEquals(20.0, b.avgOverLastKm(10.0)!!, 0.01)
    }
}
