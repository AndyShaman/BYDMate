package com.bydmate.app.domain.calculator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BigNumberCalculatorTest {

    @Test fun `parking with last trip avg returns it`() {
        val r = BigNumberCalculator.computeDisplay(
            tripKm = null, tripKwh = null,
            lastTripAvg = 18.0, recentAvg25km = 0.0,
            sessionActive = false,
        )
        assertEquals(18.0, r!!, 0.001)
    }

    @Test fun `parking without last trip falls back to recentAvg25km`() {
        val r = BigNumberCalculator.computeDisplay(
            tripKm = null, tripKwh = null,
            lastTripAvg = null, recentAvg25km = 21.5,
            sessionActive = false,
        )
        assertEquals(21.5, r!!, 0.001)
    }

    @Test fun `parking without anything returns null`() {
        val r = BigNumberCalculator.computeDisplay(
            tripKm = null, tripKwh = null,
            lastTripAvg = null, recentAvg25km = 0.0,
            sessionActive = false,
        )
        assertNull(r)
    }

    @Test fun `active session below 0_3 km shows lastTripAvg`() {
        val r = BigNumberCalculator.computeDisplay(
            tripKm = 0.1, tripKwh = 0.05,
            lastTripAvg = 18.0, recentAvg25km = 0.0,
            sessionActive = true,
        )
        assertEquals(18.0, r!!, 0.001)
    }

    @Test fun `blend at exactly 0_3 km returns full lastTripAvg`() {
        val r = BigNumberCalculator.computeDisplay(
            tripKm = 0.3, tripKwh = 0.06,
            lastTripAvg = 18.0, recentAvg25km = 0.0,
            sessionActive = true,
        )
        assertEquals(18.0, r!!, 0.001)
    }

    @Test fun `blend at exactly 1_15 km is 50_50`() {
        // Midpoint of 0.3..2.0 fade zone: weight = (1.15 - 0.3) / 1.7 = 0.5
        val r = BigNumberCalculator.computeDisplay(
            tripKm = 1.15, tripKwh = 0.23,
            lastTripAvg = 18.0, recentAvg25km = 0.0,
            sessionActive = true,
        )
        assertEquals(19.0, r!!, 0.001)
    }

    @Test fun `blend at 2_0 km returns full currentTripAvg`() {
        val r = BigNumberCalculator.computeDisplay(
            tripKm = 2.0, tripKwh = 0.4,
            lastTripAvg = 18.0, recentAvg25km = 0.0,
            sessionActive = true,
        )
        assertEquals(20.0, r!!, 0.001)
    }

    @Test fun `active session above 2_0 km returns currentTripAvg`() {
        val r = BigNumberCalculator.computeDisplay(
            tripKm = 25.0, tripKwh = 4.5,
            lastTripAvg = 22.0, recentAvg25km = 0.0,
            sessionActive = true,
        )
        assertEquals(18.0, r!!, 0.001)
    }

    @Test fun `blend with no last trip returns currentTripAvg`() {
        val r = BigNumberCalculator.computeDisplay(
            tripKm = 1.0, tripKwh = 0.2,
            lastTripAvg = null, recentAvg25km = 0.0,
            sessionActive = true,
        )
        assertEquals(20.0, r!!, 0.001)
    }

    @Test fun `below 0_3 km without last trip falls back to recentAvg25km`() {
        val r = BigNumberCalculator.computeDisplay(
            tripKm = 0.1, tripKwh = 0.05,
            lastTripAvg = null, recentAvg25km = 21.0,
            sessionActive = true,
        )
        assertEquals(21.0, r!!, 0.001)
    }

    @Test fun `null tripKwh falls back to lastTripAvg`() {
        val r = BigNumberCalculator.computeDisplay(
            tripKm = 5.0, tripKwh = null,
            lastTripAvg = 18.0, recentAvg25km = 0.0,
            sessionActive = true,
        )
        assertEquals(18.0, r!!, 0.001)
    }

    @Test fun `null tripKm falls back to lastTripAvg`() {
        val r = BigNumberCalculator.computeDisplay(
            tripKm = null, tripKwh = 0.5,
            lastTripAvg = 18.0, recentAvg25km = 0.0,
            sessionActive = true,
        )
        assertEquals(18.0, r!!, 0.001)
    }

    @Test fun `negative tripKwh treated as null`() {
        val r = BigNumberCalculator.computeDisplay(
            tripKm = 5.0, tripKwh = -0.1,
            lastTripAvg = 18.0, recentAvg25km = 0.0,
            sessionActive = true,
        )
        assertEquals(18.0, r!!, 0.001)
    }

    @Test fun `zero tripKwh during active session falls back to lastTripAvg`() {
        // Regression guard for v2.5.2 codex audit WARN 1: caller passes null on
        // negative delta (BMS recal), but if 0.0 ever reaches the calculator we
        // must not show "0.0 kWh/100km" on the widget. Falls back to lastTripAvg.
        val r = BigNumberCalculator.computeDisplay(
            tripKm = 5.0, tripKwh = 0.0,
            lastTripAvg = 18.0, recentAvg25km = 0.0,
            sessionActive = true,
        )
        assertEquals(18.0, r!!, 0.001)  // 0.0 kWh is not a real trip average, fallback applies
    }

    @Test fun `zero tripKm produces fallback even at active session`() {
        val r = BigNumberCalculator.computeDisplay(
            tripKm = 0.0, tripKwh = 0.5,
            lastTripAvg = 18.0, recentAvg25km = 0.0,
            sessionActive = true,
        )
        assertEquals(18.0, r!!, 0.001)
    }
}
