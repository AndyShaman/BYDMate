package com.bydmate.app.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteeringDoublePressTest {

    @Test fun `window constant is 450 ms`() {
        assertEquals(450L, SteeringDoublePress.WINDOW_MS)
    }

    @Test fun `second press inside window returns DOUBLE and clears pending deadline`() {
        val detector = SteeringDoublePress()

        assertEquals(DoublePressVerdict.PENDING, detector.onDown(305, 1_000L))
        assertEquals(DoublePressVerdict.DOUBLE, detector.onDown(305, 1_400L))
        assertFalse(detector.onDeadline(305, 1_450L))
    }

    @Test fun `single press fires on deadline and a following press starts fresh`() {
        val detector = SteeringDoublePress()

        assertEquals(DoublePressVerdict.PENDING, detector.onDown(305, 1_000L))
        assertTrue(detector.onDeadline(305, 1_450L))
        assertEquals(DoublePressVerdict.PENDING, detector.onDown(305, 1_460L))
    }

    @Test fun `second press after window starts a new pending press`() {
        val detector = SteeringDoublePress()

        assertEquals(DoublePressVerdict.PENDING, detector.onDown(305, 1_000L))
        assertTrue(detector.onDeadline(305, 1_450L))
        assertEquals(DoublePressVerdict.PENDING, detector.onDown(305, 1_451L))
    }

    @Test fun `two keys track independent pending presses`() {
        val detector = SteeringDoublePress()

        assertEquals(DoublePressVerdict.PENDING, detector.onDown(305, 1_000L))
        assertEquals(DoublePressVerdict.PENDING, detector.onDown(351, 1_100L))
        assertEquals(DoublePressVerdict.DOUBLE, detector.onDown(305, 1_200L))
        assertTrue(detector.onDeadline(351, 1_550L))
    }
}
