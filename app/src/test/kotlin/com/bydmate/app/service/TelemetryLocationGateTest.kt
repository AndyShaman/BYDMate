package com.bydmate.app.service

import android.location.Location
import io.mockk.every
import io.mockk.mockk
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

    private fun fix(bearing: Float?): Location {
        val loc = mockk<Location>()
        every { loc.latitude } returns 53.9
        every { loc.longitude } returns 27.56
        every { loc.hasBearing() } returns (bearing != null)
        every { loc.bearing } returns (bearing ?: 0f)
        return loc
    }

    @Test
    fun `withLocation adds lat lon heading to a copy and leaves the base payload alone`() {
        val base = JSONObject().put("soc", 80)
        val out = withLocation(base, fix(bearing = 90f))
        assertEquals(53.9, out.getDouble("lat"), 1e-9)
        assertEquals(27.56, out.getDouble("lon"), 1e-9)
        assertEquals(90.0, out.getDouble("heading"), 1e-9)
        assertEquals(80, out.getInt("soc"))
        assertFalse(base.has("lat"))
        assertFalse(base.has("lon"))
        assertFalse(base.has("heading"))
    }

    @Test
    fun `withLocation omits heading when the fix has no bearing`() {
        val out = withLocation(JSONObject().put("soc", 80), fix(bearing = null))
        assertTrue(out.has("lat"))
        assertTrue(out.has("lon"))
        assertFalse(out.has("heading"))
    }

    @Test
    fun `withLocation returns the same object when there is no location`() {
        val base = JSONObject().put("soc", 80)
        assertSame(base, withLocation(base, null))
    }
}
