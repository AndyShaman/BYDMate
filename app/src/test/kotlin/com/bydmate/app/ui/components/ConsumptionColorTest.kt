package com.bydmate.app.ui.components

import androidx.compose.ui.graphics.Color
import com.bydmate.app.ui.theme.ConsumptionBad
import com.bydmate.app.ui.theme.ConsumptionGood
import com.bydmate.app.ui.theme.ConsumptionMid
import org.junit.Assert.assertEquals
import org.junit.Test

class ConsumptionColorTest {

    private class Case(val label: String, val value: Double, val good: Double, val bad: Double, val expected: Color)

    @Test
    fun `default thresholds 20-30 pick Good, Mid and Bad, good exclusive and bad inclusive`() {
        val cases = listOf(
            Case("value below good returns Good", 15.0, 20.0, 30.0, ConsumptionGood),
            // good is exclusive (<), so 20.0 itself falls into Mid
            Case("value at good boundary returns Mid", 20.0, 20.0, 30.0, ConsumptionMid),
            // bad is inclusive (<=), so 30.0 itself is Mid
            Case("value at bad boundary returns Mid", 30.0, 20.0, 30.0, ConsumptionMid),
            Case("value above bad returns Bad", 35.0, 20.0, 30.0, ConsumptionBad),
        )
        for (c in cases) {
            assertEquals("case: ${c.label}", c.expected, consumptionColor(c.value, good = c.good, bad = c.bad))
        }
    }

    // Custom user thresholds: with the old hardcoded 20/30 logic each of these would have another colour.
    @Test
    fun `custom user thresholds drive the colour instead of the hardcoded 20-30`() {
        val cases = listOf(
            Case("tightened 15-22: 18 is Mid not Good", 18.0, 15.0, 22.0, ConsumptionMid),
            Case("tightened 15-22: 25 is Bad not Mid", 25.0, 15.0, 22.0, ConsumptionBad),
            Case("relaxed 25-40: 22 is Good not Mid", 22.0, 25.0, 40.0, ConsumptionGood),
            Case("relaxed 25-40: 35 is Mid not Bad", 35.0, 25.0, 40.0, ConsumptionMid),
        )
        for (c in cases) {
            assertEquals("case: ${c.label}", c.expected, consumptionColor(c.value, good = c.good, bad = c.bad))
        }
    }
}
