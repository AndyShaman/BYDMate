package com.bydmate.app.service

import android.location.Location
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class TelemetryLocationGateTest {

    private fun locationAged(ageMs: Long, nowMs: Long): Location {
        val loc = mockk<Location>()
        every { loc.time } returns nowMs - ageMs
        return loc
    }

    @Test
    fun `disabled toggle returns null even with a fresh fix`() {
        val now = 1_000_000L
        assertNull(TrackingService.locationForTelemetry(false, locationAged(0, now), now))
    }

    @Test
    fun `fresh fix passes through when enabled`() {
        val now = 1_000_000L
        val loc = locationAged(5_000, now)
        assertSame(loc, TrackingService.locationForTelemetry(true, loc, now))
    }

    @Test
    fun `stale fix is dropped - a stale coordinate would pin the ABRP marker`() {
        val now = 1_000_000L
        assertNull(TrackingService.locationForTelemetry(true, locationAged(61_000, now), now))
    }

    @Test
    fun `null location returns null when enabled`() {
        assertNull(TrackingService.locationForTelemetry(true, null, 1_000_000L))
    }

    @Test
    fun `future timestamp fix is rejected as not fresh - clock rollback guard`() {
        val now = 1_000_000L
        val loc = mockk<Location>()
        every { loc.time } returns now + 5_000  // fix timestamped in the future
        assertNull(TrackingService.locationForTelemetry(true, loc, now))
    }
}
