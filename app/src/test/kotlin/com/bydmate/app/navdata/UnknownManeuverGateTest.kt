package com.bydmate.app.navdata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The unknown-maneuver log: one line per distinct raw value with a floor between two lines,
 *  nothing while a maneuver is recognised, without a distance or without a guided route. */
class UnknownManeuverGateTest {

    private val gate = UnknownManeuverGate(minIntervalMs = 30_000L)
    private val lines = mutableListOf<String>()

    /** One guidance read at [atMs]: logs [value] when the read qualifies and the gate lets it through. */
    private fun read(value: String, atMs: Long, maneuverGaode: Int = 0, active: Boolean = true, distance: Int = 300) {
        if (UnknownManeuverGate.applies(active, distance, maneuverGaode) && gate.take(value, atMs)) lines.add(value)
    }

    @Test fun `a steady unrecognised value logs once`() {
        read("Turn right", T0)
        read("Turn right", T0 + 1_000)
        read("Turn right", T0 + 31_000)
        read("Turn right", T0 + 600_000)
        assertEquals(listOf("Turn right"), lines)
    }

    @Test fun `a distinct value logs once the interval has passed`() {
        read("Turn right", T0)
        read("Keep left", T0 + 5_000)        // within the interval: not logged and not remembered
        assertEquals(listOf("Turn right"), lines)
        read("Keep left", T0 + 31_000)
        assertEquals(listOf("Turn right", "Keep left"), lines)
    }

    @Test fun `a blink through a recognised maneuver does not log the value again`() {
        read("Turn right", T0)
        read("Поверните направо", T0 + 31_000, maneuverGaode = 2)
        read("Turn right", T0 + 62_000)
        read("Поверните направо", T0 + 93_000, maneuverGaode = 2)
        read("Turn right", T0 + 124_000)
        assertEquals(listOf("Turn right"), lines)
    }

    @Test fun `nothing while the code is above zero`() {
        read("Поверните направо", T0, maneuverGaode = 2)
        read("Turn right", T0 + 31_000, maneuverGaode = 1)
        assertTrue(lines.isEmpty())
        assertFalse(UnknownManeuverGate.applies(guidanceActive = true, distanceMeters = 300, maneuverGaode = 2))
    }

    @Test fun `nothing when guidance is inactive`() {
        read("Turn right", T0, active = false)
        assertTrue(lines.isEmpty())
        assertFalse(UnknownManeuverGate.applies(guidanceActive = false, distanceMeters = 300, maneuverGaode = 0))
    }

    @Test fun `nothing without a distance`() {
        read("Turn right", T0, distance = 0)
        assertTrue(lines.isEmpty())
        assertFalse(UnknownManeuverGate.applies(guidanceActive = true, distanceMeters = 0, maneuverGaode = 0))
        assertTrue(UnknownManeuverGate.applies(guidanceActive = true, distanceMeters = 300, maneuverGaode = 0))
    }

    @Test fun `reset starts a fresh episode`() {
        read("Turn right", T0)
        gate.reset()
        read("Turn right", T0 + 1_000)
        assertEquals(listOf("Turn right", "Turn right"), lines)
    }

    @Test fun `distinct values are capped per episode`() {
        repeat(UnknownManeuverGate.MAX_VALUES + 3) { i -> read("phrase $i", T0 + i * 31_000L) }
        assertEquals(UnknownManeuverGate.MAX_VALUES, lines.size)
    }

    @Test fun `quote keeps null, empty and long values apart`() {
        assertEquals("null", UnknownManeuverGate.quote(null))
        assertEquals("\"\"", UnknownManeuverGate.quote(""))
        assertEquals("\"Turn right\"", UnknownManeuverGate.quote("Turn right"))
        assertEquals("\"" + "x".repeat(120) + "\"…", UnknownManeuverGate.quote("x".repeat(200)))
        assertEquals("\"" + "x".repeat(120) + "\"", UnknownManeuverGate.quote("x".repeat(120)))
    }

    private companion object {
        const val T0 = 1_000_000L
    }
}
