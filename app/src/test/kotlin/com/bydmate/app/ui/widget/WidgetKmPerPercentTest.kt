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
    fun `missing, zero, negative or non finite inputs yield null`() {
        class Case(val label: String, val rangeKm: Double?, val soc: Int?)
        val cases = listOf(
            Case("null range", null, 80),
            Case("null soc", 240.0, null),
            Case("zero soc (divide by zero)", 240.0, 0),
            Case("negative soc", 240.0, -3),
            Case("negative range", -5.0, 80),
            Case("NaN range", Double.NaN, 80),
            Case("infinite range", Double.POSITIVE_INFINITY, 80),
        )
        for (c in cases) {
            assertNull("case: ${c.label}", widgetKmPerPercent(rangeKm = c.rangeKm, soc = c.soc))
        }
    }
}
