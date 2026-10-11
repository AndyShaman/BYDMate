package com.bydmate.app.ui.dashboard

import com.bydmate.app.data.local.entity.TripEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HybridPeriodTotalsTest {

    @Test
    fun `EV trips give no fuel sums and the whole cost as electricity`() {
        val totals = hybridPeriodTotals(
            listOf(
                TripEntity(startTs = 1L, distanceKm = 20.0, cost = 1.5),
                TripEntity(startTs = 2L, distanceKm = 10.0, cost = 0.5),
            )
        )
        assertNull(totals.liters)
        assertNull(totals.hevKm)
        assertNull(totals.fuelCost)
        assertEquals(2.0, totals.elecCost!!, 1e-9)
    }

    @Test
    fun `fuel sums add only the trips that carry them`() {
        val totals = hybridPeriodTotals(
            listOf(
                TripEntity(startTs = 1L, distanceKm = 64.0, cost = 3.0, fuelLiters = 2.8, hevKm = 38.0, fuelCost = 2.0),
                TripEntity(startTs = 2L, distanceKm = 40.0, cost = 4.0, fuelLiters = 1.2, hevKm = 20.0),
                TripEntity(startTs = 3L, distanceKm = 21.3, cost = 1.0),
            )
        )
        assertEquals(4.0, totals.liters!!, 1e-9)
        assertEquals(58.0, totals.hevKm!!, 1e-9)
        assertEquals(2.0, totals.fuelCost!!, 1e-9)
        // 3.0 − 2.0, then 4.0 (no fuel price: the whole cost), then 1.0.
        assertEquals(6.0, totals.elecCost!!, 1e-9)
    }

    @Test
    fun `no trips give no sums at all`() {
        val totals = hybridPeriodTotals(emptyList())
        assertNull(totals.liters)
        assertNull(totals.elecCost)
    }

    @Test
    fun `litres per 100 count every km of the period`() {
        assertEquals(1.84, periodLitersPer100(7.6, 412.6)!!, 0.01)
        assertNull(periodLitersPer100(null, 412.6))
        assertNull(periodLitersPer100(7.6, 0.0))
    }
}
