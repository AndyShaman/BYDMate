package com.bydmate.app.ui.battery

import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.SocRed
import com.bydmate.app.ui.theme.SocYellow
import com.bydmate.app.ui.theme.TextPrimary
import org.junit.Assert.assertEquals
import org.junit.Test

class CellDeltaColorTest {

    // --- Inside the LFP diagnostic band (SOC 20-80%) ---

    @Test
    fun `in band — delta below 50 mV is green`() {
        assertEquals(AccentGreen, cellDeltaColor(0.049, soc = 50))
    }

    @Test
    fun `in band — delta at 50 mV is yellow`() {
        assertEquals(SocYellow, cellDeltaColor(0.050, soc = 50))
    }

    @Test
    fun `in band — delta just below 90 mV is yellow`() {
        assertEquals(SocYellow, cellDeltaColor(0.089, soc = 50))
    }

    @Test
    fun `in band — delta at 90 mV is red`() {
        assertEquals(SocRed, cellDeltaColor(0.090, soc = 50))
    }

    @Test
    fun `real voltage subtraction hits exact boundaries despite fp noise`() {
        // The dialog feeds max-min directly: 3.43 - 3.34 = 0.0899999… in IEEE 754,
        // which without rounding would misread a true 90 mV delta as yellow.
        assertEquals(SocRed, cellDeltaColor(3.43 - 3.34, soc = 50))
        assertEquals(SocYellow, cellDeltaColor(3.39 - 3.34, soc = 50))
        assertEquals(AccentGreen, cellDeltaColor(3.384 - 3.34, soc = 50))
    }

    @Test
    fun `band boundaries 20 and 80 are inclusive`() {
        assertEquals(SocRed, cellDeltaColor(0.350, soc = 20))
        assertEquals(SocRed, cellDeltaColor(0.350, soc = 80))
    }

    // --- Outside the band: neutral, no warnings (issue #113) ---

    @Test
    fun `at full charge a large delta is neutral`() {
        // 350 mV near 100% SOC is normal LFP top-of-charge divergence, not a defect.
        assertEquals(TextPrimary, cellDeltaColor(0.350, soc = 100))
    }

    @Test
    fun `just outside the band is neutral`() {
        assertEquals(TextPrimary, cellDeltaColor(0.350, soc = 19))
        assertEquals(TextPrimary, cellDeltaColor(0.350, soc = 81))
    }

    @Test
    fun `unknown soc is neutral`() {
        assertEquals(TextPrimary, cellDeltaColor(0.350, soc = null))
    }

    @Test
    fun `unknown delta is neutral`() {
        assertEquals(TextPrimary, cellDeltaColor(null, soc = 50))
    }
}
