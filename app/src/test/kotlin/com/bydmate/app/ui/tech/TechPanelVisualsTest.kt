package com.bydmate.app.ui.tech

import com.bydmate.app.ui.tech.TechPanelVisuals.TempZone
import org.junit.Assert.assertEquals
import org.junit.Test

class TechPanelVisualsTest {

    // --- cell range scale ---

    @Test
    fun `LFP pack keeps the default 3_25 - 3_40 scale`() {
        val (lo, hi) = TechPanelVisuals.cellScale(3.301, 3.312)
        assertEquals(3.25, lo, 1e-9)
        assertEquals(3.40, hi, 1e-9)
    }

    @Test
    fun `scale expands below and above the LFP window`() {
        val (lo, hi) = TechPanelVisuals.cellScale(3.10, 4.05)
        assertEquals(3.05, lo, 1e-9)
        assertEquals(4.10, hi, 1e-9)
    }

    // --- temperature zones ---

    @Test
    fun `zone boundaries are 90 and 120`() {
        assertEquals(TempZone.OK, TechPanelVisuals.tempZone(89))
        assertEquals(TempZone.WARM, TechPanelVisuals.tempZone(90))
        assertEquals(TempZone.WARM, TechPanelVisuals.tempZone(119))
        assertEquals(TempZone.HOT, TechPanelVisuals.tempZone(120))
    }

    // --- tyre deviation ---

    @Test
    fun `equal pressures never deviate`() {
        assertEquals(
            listOf(false, false, false, false),
            TechPanelVisuals.tyreDeviates(listOf(250, 250, 250, 250)),
        )
    }

    @Test
    fun `a tyre far from the average is flagged`() {
        // mean of 252 / 255 / 221 / 260 = 247, so 221 is 26 kPa off against a 24.7 threshold.
        assertEquals(
            listOf(false, false, true, false),
            TechPanelVisuals.tyreDeviates(listOf(252, 255, 221, 260)),
        )
    }

    @Test
    fun `a missing reading is never flagged and is left out of the average`() {
        // mean of 300 / 250 / 250 = 266.67; only 300 is more than 10% off.
        assertEquals(
            listOf(true, false, false, false),
            TechPanelVisuals.tyreDeviates(listOf(300, 250, 250, null)),
        )
    }

    @Test
    fun `fewer than two readings flag nothing`() {
        assertEquals(
            listOf(false, false, false, false),
            TechPanelVisuals.tyreDeviates(listOf(180, null, null, null)),
        )
    }
}
