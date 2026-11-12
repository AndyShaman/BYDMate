package com.bydmate.app.data.trips

import org.junit.Assert.assertEquals
import org.junit.Test

class TripSourceTest {
    @Test
    fun `constants match wire values used in DB`() {
        assertEquals("live", TripSource.LIVE)
        assertEquals("energydata", TripSource.ENERGYDATA)
        assertEquals("native_polling", TripSource.NATIVE_POLLING)
    }
}
