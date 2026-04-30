package com.bydmate.app.ui.components

import com.bydmate.app.ui.theme.ConsumptionBad
import com.bydmate.app.ui.theme.ConsumptionGood
import com.bydmate.app.ui.theme.ConsumptionMid
import org.junit.Assert.assertEquals
import org.junit.Test

class ConsumptionColorTest {

    @Test
    fun `default thresholds 20-30 — value below good returns Good`() {
        assertEquals(ConsumptionGood, consumptionColor(15.0, good = 20.0, bad = 30.0))
    }

    @Test
    fun `default thresholds — value at good boundary returns Mid`() {
        // good is exclusive (<), so 20.0 itself falls into Mid
        assertEquals(ConsumptionMid, consumptionColor(20.0, good = 20.0, bad = 30.0))
    }

    @Test
    fun `default thresholds — value at bad boundary returns Mid`() {
        // bad is inclusive (<=), so 30.0 itself is Mid
        assertEquals(ConsumptionMid, consumptionColor(30.0, good = 20.0, bad = 30.0))
    }

    @Test
    fun `default thresholds — value above bad returns Bad`() {
        assertEquals(ConsumptionBad, consumptionColor(35.0, good = 20.0, bad = 30.0))
    }

    // --- Custom user thresholds (this is the bug we're fixing) ---

    @Test
    fun `tightened thresholds 15-22 — 18 is Mid not Good`() {
        // With hardcoded 20/30 logic 18.0 was Good. With user thresholds it must be Mid.
        assertEquals(ConsumptionMid, consumptionColor(18.0, good = 15.0, bad = 22.0))
    }

    @Test
    fun `tightened thresholds 15-22 — 25 is Bad not Mid`() {
        // With hardcoded 20/30 logic 25.0 was Mid. With user thresholds it must be Bad.
        assertEquals(ConsumptionBad, consumptionColor(25.0, good = 15.0, bad = 22.0))
    }

    @Test
    fun `relaxed thresholds 25-40 — 22 is Good not Mid`() {
        // With hardcoded 20/30 logic 22.0 was Mid. With user thresholds it must be Good.
        assertEquals(ConsumptionGood, consumptionColor(22.0, good = 25.0, bad = 40.0))
    }

    @Test
    fun `relaxed thresholds 25-40 — 35 is Mid not Bad`() {
        // With hardcoded 20/30 logic 35.0 was Bad. With user thresholds it must be Mid.
        assertEquals(ConsumptionMid, consumptionColor(35.0, good = 25.0, bad = 40.0))
    }
}
