package com.bydmate.app.data.charging

import org.junit.Assert.assertEquals
import org.junit.Test

class ChargingTypeClassifierTest {

    private val classifier = ChargingTypeClassifier()

    @Test
    fun `gun_state 2 maps to AC`() {
        assertEquals("AC", classifier.fromGunState(2))
    }

    @Test
    fun `gun_state 3 maps to DC`() {
        assertEquals("DC", classifier.fromGunState(3))
    }

    @Test
    fun `gun_state 4 GB_DC maps to DC`() {
        assertEquals("DC", classifier.fromGunState(4))
    }

    @Test
    fun `gun_state 1 NONE returns null`() {
        assertEquals(null, classifier.fromGunState(1))
    }

    @Test
    fun `gun_state null returns null`() {
        assertEquals(null, classifier.fromGunState(null))
    }

    @Test
    fun `gun_state 0 returns null (unknown)`() {
        assertEquals(null, classifier.fromGunState(0))
    }

    @Test
    fun `heuristic returns DC when kwh per hour exceeds 15`() {
        // 30 kWh in 1 hour = 30 kW avg → clearly DC
        assertEquals("DC", classifier.heuristicByPower(kwhCharged = 30.0, hours = 1.0))
    }

    @Test
    fun `heuristic returns DC at boundary (just above 15 kW per hour)`() {
        assertEquals("DC", classifier.heuristicByPower(kwhCharged = 16.0, hours = 1.0))
    }

    @Test
    fun `heuristic returns AC when kwh per hour is 15 or below`() {
        assertEquals("AC", classifier.heuristicByPower(kwhCharged = 15.0, hours = 1.0))
        assertEquals("AC", classifier.heuristicByPower(kwhCharged = 7.0, hours = 1.0))
    }

    @Test
    fun `heuristic handles fractional hours correctly`() {
        // 10 kWh in 0.25 h = 40 kW avg → DC
        assertEquals("DC", classifier.heuristicByPower(kwhCharged = 10.0, hours = 0.25))
        // 10 kWh in 2 h = 5 kW avg → AC
        assertEquals("AC", classifier.heuristicByPower(kwhCharged = 10.0, hours = 2.0))
    }

    @Test
    fun `heuristic returns AC for zero hours (degenerate, default to safe)`() {
        // Zero or negative duration is meaningless — default to AC (cheaper tariff).
        assertEquals("AC", classifier.heuristicByPower(kwhCharged = 5.0, hours = 0.0))
        assertEquals("AC", classifier.heuristicByPower(kwhCharged = 5.0, hours = -1.0))
    }

    @Test
    fun `heuristic returns AC when kwh is zero`() {
        assertEquals("AC", classifier.heuristicByPower(kwhCharged = 0.0, hours = 1.0))
    }
}
