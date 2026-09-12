package com.bydmate.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// VadimV, 2026-09-12: energydata wrote 54681,2 kWh for a 38,2 km trip whose SOC went 92 -> 84.

class TripEnergySanityTest {

    @Test fun `plausible value passes through unchanged`() {
        assertEquals(
            5.6,
            TripEnergySanity.kwhFor(5.6, tripKm = 38.2, socStart = 92, socEnd = 84, capacityKwh = 72.9)!!,
            0.0,
        )
    }

    @Test fun `value above the absolute ceiling falls back to the SOC delta`() {
        assertEquals(
            4.8,
            TripEnergySanity.kwhFor(54681.2, tripKm = 38.2, socStart = 92, socEnd = 84, capacityKwh = 60.0)!!,
            1e-9,
        )
    }

    @Test fun `a hybrid trip above its own pack capacity is kept`() {
        // DM-i: the engine recharges the pack on the way, so 30 kWh out of a 20 kWh battery
        // over 200 km is an ordinary 15 per 100.
        assertEquals(
            30.0,
            TripEnergySanity.kwhFor(30.0, tripKm = 200.0, socStart = 92, socEnd = 84, capacityKwh = 20.0)!!,
            0.0,
        )
    }

    @Test fun `value above 150 per 100 km falls back below the absolute ceiling`() {
        // 40 kWh on 10 km = 400 per 100 km, far under the 500 kWh ceiling.
        assertEquals(
            7.29,
            TripEnergySanity.kwhFor(40.0, tripKm = 10.0, socStart = 50, socEnd = 40, capacityKwh = 72.9)!!,
            1e-9,
        )
    }

    @Test fun `a short trip is never judged by its per 100 figure`() {
        // 0,2 km with the heater on = 200 per 100 km, and it is real (VadimV's list has
        // honest 0,1 km / 0,1 kWh records too).
        assertEquals(
            0.4,
            TripEnergySanity.kwhFor(0.4, tripKm = 0.2, socStart = 92, socEnd = 84, capacityKwh = 72.9)!!,
            0.0,
        )
    }

    @Test fun `the same per 100 figure above the distance gate falls back`() {
        // 12 kWh on 6 km = 200 per 100 km, past the 5 km gate.
        assertEquals(
            5.832,
            TripEnergySanity.kwhFor(12.0, tripKm = 6.0, socStart = 92, socEnd = 84, capacityKwh = 72.9)!!,
            1e-9,
        )
    }

    @Test fun `negative value is implausible`() {
        assertNull(TripEnergySanity.kwhFor(-3.0, tripKm = 10.0, socStart = null, socEnd = null, capacityKwh = 72.9))
    }

    @Test fun `without SOC the implausible value is dropped`() {
        assertNull(TripEnergySanity.kwhFor(54681.2, tripKm = 38.2, socStart = null, socEnd = null, capacityKwh = 72.9))
    }

    @Test fun `SOC that did not drop is not a fallback`() {
        assertNull(TripEnergySanity.kwhFor(54681.2, tripKm = 38.2, socStart = 84, socEnd = 84, capacityKwh = 72.9))
    }

    @Test fun `capacity unset leaves nothing to fall back to`() {
        assertNull(TripEnergySanity.kwhFor(54681.2, tripKm = 38.2, socStart = 92, socEnd = 84, capacityKwh = 0.0))
    }

    @Test fun `only the absolute ceiling judges a zero-km record`() {
        assertTrue(TripEnergySanity.isPlausible(TripEnergySanity.MAX_KWH_PER_TRIP, tripKm = 0.0))
        assertFalse(TripEnergySanity.isPlausible(TripEnergySanity.MAX_KWH_PER_TRIP + 0.1, tripKm = 0.0))
        assertFalse(TripEnergySanity.isPlausible(-0.1, tripKm = 0.0))
    }

    @Test fun `per100 is null for a dropped value and for a zero-km trip`() {
        assertNull(TripEnergySanity.per100For(null, 38.2))
        assertNull(TripEnergySanity.per100For(4.8, 0.0))
        assertEquals(12.0, TripEnergySanity.per100For(6.0, 50.0)!!, 1e-9)
    }
}
