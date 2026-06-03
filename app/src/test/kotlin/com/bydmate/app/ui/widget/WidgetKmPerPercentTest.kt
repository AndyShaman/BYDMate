package com.bydmate.app.ui.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetKmPerPercentTest {

    @Test
    fun `range divided by soc gives km per one percent`() {
        // 279 km at 93% → 3.0 km per 1%
        assertEquals(3.0, widgetKmPerPercent(rangeKm = 279.0, soc = 93)!!, 0.001)
    }

    @Test
    fun `null range yields null`() {
        assertNull(widgetKmPerPercent(rangeKm = null, soc = 80))
    }

    @Test
    fun `null soc yields null`() {
        assertNull(widgetKmPerPercent(rangeKm = 240.0, soc = null))
    }

    @Test
    fun `zero soc yields null to avoid divide by zero`() {
        assertNull(widgetKmPerPercent(rangeKm = 240.0, soc = 0))
    }

    @Test
    fun `negative soc yields null`() {
        assertNull(widgetKmPerPercent(rangeKm = 240.0, soc = -3))
    }

    @Test
    fun `non finite or negative range yields null`() {
        assertNull(widgetKmPerPercent(rangeKm = -5.0, soc = 80))
        assertNull(widgetKmPerPercent(rangeKm = Double.NaN, soc = 80))
        assertNull(widgetKmPerPercent(rangeKm = Double.POSITIVE_INFINITY, soc = 80))
    }
}
