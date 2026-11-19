package com.bydmate.app.ui.dashboard

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/** TRIP 1 / TRIP 2 cards show kilometres with one decimal, like the details dialog. */
class TripCounterKmFormatTest {

    @Test fun `one decimal, point in English locale`() {
        assertEquals("412.3", formatTripCounterKm(412.34, Locale.US))
    }

    @Test fun `decimal comma in Russian locale`() {
        assertEquals("412,3", formatTripCounterKm(412.34, Locale("ru")))
    }

    @Test fun `a whole number still shows a decimal`() {
        assertEquals("5.0", formatTripCounterKm(5.0, Locale.US))
    }

    @Test fun `no counter reads zero`() {
        assertEquals("0.0", formatTripCounterKm(null, Locale.US))
    }

    @Test fun `four digits`() {
        assertEquals("1234.6", formatTripCounterKm(1234.56, Locale.US))
    }

    @Test fun `rounds, never truncates`() {
        assertEquals("1.0", formatTripCounterKm(0.96, Locale.US))
    }
}
