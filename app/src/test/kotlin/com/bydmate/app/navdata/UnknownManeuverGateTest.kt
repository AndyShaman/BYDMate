package com.bydmate.app.navdata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The unknown-maneuver log: one line per distinct raw value per [UnknownManeuverGate.REPEAT_MS]
 *  with a floor between two lines, nothing while a maneuver is recognised, without a distance or
 *  without a guided route; only the head of a screen text goes into a line. */
class UnknownManeuverGateTest {

    private val gate = UnknownManeuverGate(minIntervalMs = 30_000L)
    private val lines = mutableListOf<String>()

    /** One guidance read at [atMs]: logs [value] when the read qualifies and the gate lets it through. */
    private fun read(value: String, atMs: Long, maneuverGaode: Int = 0, active: Boolean = true, distance: Int = 300) {
        if (UnknownManeuverGate.applies(distance, maneuverGaode) { active } && gate.take(value, atMs)) lines.add(value)
    }

    @Test fun `a steady unrecognised value logs once within the window`() {
        read("Turn right", T0)
        read("Turn right", T0 + 1_000)
        read("Turn right", T0 + 31_000)
        read("Turn right", T0 + 299_000)
        assertEquals(listOf("Turn right"), lines)
    }

    @Test fun `the same value logs again once the window has passed, not before`() {
        read("Turn right", T0)
        read("Turn right", T0 + UnknownManeuverGate.REPEAT_MS - 1)
        assertEquals(listOf("Turn right"), lines)
        read("Turn right", T0 + UnknownManeuverGate.REPEAT_MS)
        assertEquals(listOf("Turn right", "Turn right"), lines)
        read("Turn right", T0 + UnknownManeuverGate.REPEAT_MS + 31_000)
        assertEquals(2, lines.size)
    }

    @Test fun `a distinct value logs once the interval has passed`() {
        read("Turn right", T0)
        read("Keep left", T0 + 5_000)        // within the interval: not logged and not remembered
        assertEquals(listOf("Turn right"), lines)
        read("Keep left", T0 + 31_000)
        assertEquals(listOf("Turn right", "Keep left"), lines)
    }

    @Test fun `a blink through a recognised maneuver does not log the value again within the window`() {
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
    }

    @Test fun `nothing when guidance is inactive`() {
        read("Turn right", T0, active = false)
        assertTrue(lines.isEmpty())
        assertFalse(UnknownManeuverGate.applies(distanceMeters = 300, maneuverGaode = 0) { false })
        assertTrue(UnknownManeuverGate.applies(distanceMeters = 300, maneuverGaode = 0) { true })
    }

    @Test fun `nothing without a distance`() {
        read("Turn right", T0, distance = 0)
        assertTrue(lines.isEmpty())
    }

    @Test fun `the route state is read only for a distance with code 0`() {
        var hubReads = 0
        val hub = { hubReads++; true }
        assertFalse(UnknownManeuverGate.applies(distanceMeters = 300, maneuverGaode = 2, guidanceActive = hub))
        assertFalse(UnknownManeuverGate.applies(distanceMeters = 0, maneuverGaode = 0, guidanceActive = hub))
        assertEquals(0, hubReads)
        assertTrue(UnknownManeuverGate.applies(distanceMeters = 300, maneuverGaode = 0, guidanceActive = hub))
        assertEquals(1, hubReads)
    }

    @Test fun `reset starts a fresh episode`() {
        read("Turn right", T0)
        gate.reset()
        read("Turn right", T0 + 1_000)
        assertEquals(listOf("Turn right", "Turn right"), lines)
    }

    @Test fun `a value past the cap evicts the oldest instead of being dropped`() {
        val fast = UnknownManeuverGate(minIntervalMs = 0L)
        repeat(UnknownManeuverGate.MAX_VALUES) { i -> assertTrue(fast.take("phrase $i", T0 + i)) }
        assertTrue(fast.take("phrase 16", T0 + 16))   // the 17th distinct value is logged
        assertTrue(fast.take("phrase 0", T0 + 17))    // the oldest was forgotten
        assertFalse(fast.take("phrase 2", T0 + 18))   // the others are still remembered
    }

    @Test fun `quote keeps null, empty and long values apart`() {
        assertEquals("null", UnknownManeuverGate.quote(null))
        assertEquals("\"\"", UnknownManeuverGate.quote(""))
        assertEquals("\"notification_lane_change_sdl\"", UnknownManeuverGate.quote("notification_lane_change_sdl"))
        assertEquals("\"" + "x".repeat(120) + "\"…", UnknownManeuverGate.quote("x".repeat(200)))
        assertEquals("\"" + "x".repeat(120) + "\"", UnknownManeuverGate.quote("x".repeat(120)))
    }

    @Test fun `a screen text keeps only its head and its length`() {
        assertEquals("null", UnknownManeuverGate.textHead(null))
        assertEquals("\"\" len=0", UnknownManeuverGate.textHead(""))
        assertEquals("\"\" len=2", UnknownManeuverGate.textHead("  "))
        assertEquals("\"Turn right\" len=10", UnknownManeuverGate.textHead("Turn right"))
        assertEquals("\"Keep left at the\" len=16", UnknownManeuverGate.textHead("Keep left at the"))
        assertEquals("\"Turn right onto Baker\"… len=28", UnknownManeuverGate.textHead("Turn right onto Baker Street"))
        assertEquals("\"" + "x".repeat(48) + "\"… len=200", UnknownManeuverGate.textHead("x".repeat(200)))
    }

    @Test fun `a street after the maneuver words never gets into the head`() {
        listOf(
            "Turn right onto Baker Street",
            "Turn right onto the Baker Street",
            "Turn right onto Baker Street",
            "Turn  right\tonto\nBaker Street",
        ).forEach { desc ->
            val head = UnknownManeuverGate.textHead(desc)
            assertFalse(head, "Street" in head)
            assertTrue(head, head.endsWith("… len=${desc.length}"))
        }
    }

    private companion object {
        const val T0 = 1_000_000L
    }
}
