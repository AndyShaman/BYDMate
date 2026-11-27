package com.bydmate.app.ui.trips

import com.bydmate.app.data.local.entity.TripEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PeriodPer100CellTest {

    @Test
    fun `hybrid period with both kWh and fuel shows both values uncolored`() {
        val expected = "%.1f + %.1f %s".format(8.8, 3.4 / 215.0 * 100.0, "L")
        assertEquals(
            Per100Cell(expected, colored = false),
            periodPer100Cell(8.8, 3.4, 215.0, "L"),
        )
    }

    @Test
    fun `period without both positive kWh and fuel falls back to per100Cell`() {
        assertEquals(
            per100Cell(10.2, null, null, "L"),
            periodPer100Cell(10.2, null, null, "L"),
        )
        assertEquals(
            per100Cell(null, 3.4, 215.0, "L"),
            periodPer100Cell(null, 3.4, 215.0, "L"),
        )
        assertEquals(
            per100Cell(10.2, 0.0, 100.0, "L"),
            periodPer100Cell(10.2, 0.0, 100.0, "L"),
        )
    }

    @Test
    fun `hybrid period without kWh on any trip falls back to litres-only cell`() {
        val trips = listOf(
            TripEntity(startTs = 1L, distanceKm = 50.0, kwhConsumed = null),
            TripEntity(startTs = 2L, distanceKm = 50.0, kwhConsumed = null),
        )
        val kwhPer100 = periodKwhPer100(trips)
        assertNull(kwhPer100)
        assertEquals(
            per100Cell(null, 4.0, 100.0, "л"),
            periodPer100Cell(kwhPer100, 4.0, 100.0, "л"),
        )
    }

    @Test
    fun `periodKwhPer100 divides summed kWh by summed distance and returns null on zero distance`() {
        val mixed = listOf(
            TripEntity(startTs = 1L, distanceKm = 50.0, kwhConsumed = 5.0),
            TripEntity(startTs = 2L, distanceKm = 50.0, kwhConsumed = null),
        )
        assertEquals(5.0, periodKwhPer100(mixed)!!, 1e-9)

        val zeroDistance = listOf(
            TripEntity(startTs = 1L, distanceKm = 0.0, kwhConsumed = 5.0),
        )
        assertNull(periodKwhPer100(zeroDistance))
    }
}
