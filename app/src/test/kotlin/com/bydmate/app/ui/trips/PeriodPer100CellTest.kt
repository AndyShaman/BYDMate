package com.bydmate.app.ui.trips

import org.junit.Assert.assertEquals
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
}
