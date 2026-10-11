package com.bydmate.app.ui.widget

import com.bydmate.app.data.nativestack.VehicleKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetHybridSlotsTest {

    /**
     * FloatingWidgetView's kind defaults to EV (a Composable cannot be reflected on, so the default
     * is pinned through the gate it feeds): EV and an unconfirmed car keep km/% and the consumption.
     */
    @Test
    fun `only a confirmed hybrid swaps the right slots for fuel`() {
        assertFalse(widgetShowsFuel(VehicleKind.EV))
        assertFalse(widgetShowsFuel(VehicleKind.UNKNOWN))
        assertTrue(widgetShowsFuel(VehicleKind.HYBRID))
    }

    @Test
    fun `the tank slot shows the car's percent or a dash`() {
        assertEquals("54%", widgetFuelPercentText(54))
        assertEquals("0%", widgetFuelPercentText(0))
        assertEquals("—", widgetFuelPercentText(null))
    }

    @Test
    fun `the fuel range slot shows bare km or a dash`() {
        assertEquals("512", widgetFuelRangeText(512))
        assertEquals("—", widgetFuelRangeText(null))
    }
}
