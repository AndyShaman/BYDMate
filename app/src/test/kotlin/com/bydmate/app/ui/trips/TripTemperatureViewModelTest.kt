package com.bydmate.app.ui.trips

import com.bydmate.app.data.local.entity.TripEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripTemperatureViewModelTest {

    private fun trip(start: Int?, end: Int?) =
        TripEntity(startTs = 0L, exteriorTemp = start, exteriorTempEnd = end)

    @Test
    fun `both ends known are averaged`() {
        assertEquals(-5.0, TripTemperatureViewModel.tripTemperature(trip(-7, -3))!!, 1e-9)
    }

    @Test
    fun `one known end is used on its own`() {
        assertEquals(-7.0, TripTemperatureViewModel.tripTemperature(trip(-7, null))!!, 1e-9)
        assertEquals(-3.0, TripTemperatureViewModel.tripTemperature(trip(null, -3))!!, 1e-9)
    }

    /** Trips recorded before the app collected temperature stay out of the chart. */
    @Test
    fun `a trip without any temperature drops out`() {
        assertNull(TripTemperatureViewModel.tripTemperature(trip(null, null)))
    }
}
