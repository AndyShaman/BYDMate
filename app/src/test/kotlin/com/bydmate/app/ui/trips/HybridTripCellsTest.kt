package com.bydmate.app.ui.trips

import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.nativestack.VehicleKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HybridTripCellsTest {

    // The cells format with the device locale (as every number on the screen does).
    private fun f(v: Double) = "%.1f".format(v)

    /** Today's EV cell: «%.1f» of kWh/100, coloured; «—» uncoloured without a figure. */
    @Test
    fun `a trip without fuel keeps today's kWh per 100`() {
        assertEquals(Per100Cell(f(15.0), colored = true), per100Cell(15.0, null, 21.3, "л"))
        assertEquals(Per100Cell(f(15.0), colored = true), per100Cell(15.0, 0.0, 21.3, "л"))
        assertEquals(Per100Cell("—", colored = false), per100Cell(null, null, 21.3, "л"))
    }

    @Test
    fun `a trip with fuel shows litres per 100 km of the whole trip, uncoloured`() {
        assertEquals(Per100Cell("${f(4.375)} л", colored = false), per100Cell(6.4, 2.8, 64.0, "л"))
    }

    @Test
    fun `fuel without distance falls back to the kWh cell`() {
        assertEquals(Per100Cell(f(6.4), colored = true), per100Cell(6.4, 2.8, 0.0, "л"))
        assertEquals(Per100Cell(f(6.4), colored = true), per100Cell(6.4, 2.8, null, "л"))
    }

    @Test
    fun `the litres cell shows one decimal or a dash`() {
        assertEquals(f(2.8), litersCell(2.8))
        assertEquals(f(0.0), litersCell(0.0))
        assertEquals("—", litersCell(null))
    }

    /** Главная on an EV: время 2.5 and five columns of 1, exactly as before the hybrid work. */
    @Test
    fun `EV and unconfirmed cars keep today's six column weights`() {
        val today = listOf(2.5f, 1f, 1f, 1f, 1f, 1f)
        assertEquals(today, columnWeights(VehicleKind.EV))
        assertEquals(today, columnWeights(VehicleKind.UNKNOWN))
        assertEquals(listOf(2.5f, 1f, 1f, 1f, 1f, 1f, 1f), columnWeights(VehicleKind.HYBRID))
    }

    @Test
    fun `EV and unconfirmed cars keep today's trip list widths`() {
        val today = listOf(44, 48, 58, 44, 44, 56)
        assertEquals(today, tripsColumnWidthsDp(VehicleKind.EV))
        assertEquals(today, tripsColumnWidthsDp(VehicleKind.UNKNOWN))
        assertEquals(listOf(44, 48, 58, 44, 44, 56, 56), tripsColumnWidthsDp(VehicleKind.HYBRID))
    }

    @Test
    fun `day and month litres sum only the trips that carry them`() {
        assertNull(sumLiters(listOf(TripEntity(startTs = 1L))))
        assertEquals(
            4.0,
            sumLiters(listOf(TripEntity(startTs = 1L, fuelLiters = 2.8), TripEntity(startTs = 2L), TripEntity(startTs = 3L, fuelLiters = 1.2)))!!,
            1e-9,
        )
    }
}
