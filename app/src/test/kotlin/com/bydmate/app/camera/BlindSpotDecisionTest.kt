package com.bydmate.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Live Leopard 3 2026-07-31, fid 950009900: 1=off, 2=left, 4=right, 6=hazard.
// gear (DiParsData.kt:20): 1=P, 2=R, 3=N, 4=D — R is what closes the pipeline.

class BlindSpotDecisionTest {

    private fun input(
        blink: Int? = 2,
        speedKmh: Float? = 40f,
        gearIsReverse: Boolean = false,
        thresholdKmh: Int = 20,
        telemetryAgeMs: Long = 0L,
    ) = BlindSpotInput(blink, speedKmh, gearIsReverse, thresholdKmh, telemetryAgeMs)

    @Test fun `left turn signal above threshold shows the left camera`() {
        val d = decideBlindSpot(input(blink = 2))
        assertEquals(BlindSpotSide.LEFT, d.show)
        assertTrue(d.cameraWarm)
    }

    @Test fun `right turn signal above threshold shows the right camera`() {
        assertEquals(BlindSpotSide.RIGHT, decideBlindSpot(input(blink = 4)).show)
    }

    @Test fun `reverse gear closes everything`() {
        val d = decideBlindSpot(input(blink = 2, gearIsReverse = true))
        assertEquals(BlindSpotSide.NONE, d.show)
        assertFalse(d.cameraWarm)
    }

    @Test fun `stale telemetry hides the window but keeps the camera warm`() {
        val d = decideBlindSpot(input(blink = 2, telemetryAgeMs = 751L))
        assertEquals(BlindSpotSide.NONE, d.show)
        assertTrue(d.cameraWarm)
    }

    @Test fun `telemetry exactly at the watchdog limit is still fresh`() {
        assertEquals(BlindSpotSide.LEFT, decideBlindSpot(input(blink = 2, telemetryAgeMs = 750L)).show)
    }

    @Test fun `off and hazard never show a camera`() {
        assertEquals(BlindSpotSide.NONE, decideBlindSpot(input(blink = 1)).show)
        assertEquals(BlindSpotSide.NONE, decideBlindSpot(input(blink = 6)).show)
        assertEquals(BlindSpotSide.NONE, decideBlindSpot(input(blink = null)).show)
    }

    @Test fun `transient blink value 9 is treated as off`() {
        assertEquals(BlindSpotSide.NONE, decideBlindSpot(input(blink = 9)).show)
    }

    @Test fun `below threshold nothing is shown`() {
        assertEquals(BlindSpotSide.NONE, decideBlindSpot(input(blink = 2, speedKmh = 19.9f)).show)
        assertEquals(BlindSpotSide.LEFT, decideBlindSpot(input(blink = 2, speedKmh = 20f)).show)
    }

    @Test fun `missing speed hides the window and cools the camera`() {
        val d = decideBlindSpot(input(blink = 2, speedKmh = null))
        assertEquals(BlindSpotSide.NONE, d.show)
        assertFalse(d.cameraWarm)
    }

    @Test fun `camera stays warm inside the hysteresis band below the threshold`() {
        assertTrue(decideBlindSpot(input(blink = 1, speedKmh = 15f)).cameraWarm)
        assertFalse(decideBlindSpot(input(blink = 1, speedKmh = 14.9f)).cameraWarm)
    }
}

class BlindSpotArmedTest {

    @Test fun `disabled feature never arms the loop`() {
        assertFalse(blindSpotArmed(enabled = false, gear = 4, speedKmh = 80, thresholdKmh = 20))
    }

    @Test fun `arms inside the warm band and stays off below it`() {
        assertTrue(blindSpotArmed(enabled = true, gear = 4, speedKmh = 15, thresholdKmh = 20))
        assertFalse(blindSpotArmed(enabled = true, gear = 4, speedKmh = 14, thresholdKmh = 20))
    }

    @Test fun `reverse and unknown speed do not arm`() {
        assertFalse(blindSpotArmed(enabled = true, gear = 2, speedKmh = 40, thresholdKmh = 20))
        assertFalse(blindSpotArmed(enabled = true, gear = 4, speedKmh = null, thresholdKmh = 20))
    }
}

class BlindSpotTelemetryGateTest {

    private val valid = BlindSpotSample(blink = 2, speedKmh = 40f, gear = 4)

    private fun gate(now: Long = 0L) = BlindSpotTelemetryGate().apply { reset(now) }

    @Test fun `a snapshot without gear is not valid`() {
        assertFalse(BlindSpotSample(blink = 2, speedKmh = 40f, gear = null).isValid)
        assertFalse(BlindSpotSample(blink = null, speedKmh = 40f, gear = 4).isValid)
        assertFalse(BlindSpotSample(blink = 2, speedKmh = null, gear = 4).isValid)
        assertTrue(valid.isValid)
    }

    @Test fun `valid snapshot resets the age and is remembered`() {
        val gate = gate()
        val state = gate.onSample(valid, 1_000L)
        assertEquals(0L, state.ageMs)
        assertFalse(state.mustClose)
        assertEquals(valid, state.lastValid)
    }

    @Test fun `age counts from the last fully valid snapshot`() {
        val gate = gate()
        gate.onSample(valid, 1_000L)
        val state = gate.onSample(BlindSpotSample(blink = 2, speedKmh = 40f, gear = null), 1_500L)
        assertEquals(500L, state.ageMs)
        assertFalse(state.mustClose)
        // The last known-good sample is what reverse detection falls back to.
        assertEquals(4, state.lastValid?.gear)
    }

    @Test fun `invalid snapshots for longer than three seconds close the pipeline`() {
        val gate = gate()
        gate.onSample(valid, 1_000L)
        val partial = BlindSpotSample(blink = 2, speedKmh = 40f, gear = null)
        assertFalse(gate.onSample(partial, 4_000L).mustClose)
        assertTrue(gate.onSample(partial, 4_001L).mustClose)
    }

    @Test fun `silence from the arming point also closes the pipeline`() {
        // Never a valid snapshot: the age is unknown and the loss timer runs from arming.
        val early = gate(now = 0L).onSample(null, 1_000L)
        assertEquals(Long.MAX_VALUE, early.ageMs)
        assertFalse(early.mustClose)  // a single failed read is a hiccup
        assertTrue(gate(now = 0L).onSample(null, 3_001L).mustClose)
    }

    @Test fun `two dead reads in a row close the pipeline immediately`() {
        val gate = gate()
        gate.onSample(valid, 1_000L)
        assertFalse(gate.onSample(null, 1_150L).mustClose)
        assertTrue(gate.onSample(null, 1_300L).mustClose)
    }

    @Test fun `a good read between two failures clears the failure count`() {
        val gate = gate()
        gate.onSample(valid, 1_000L)
        gate.onSample(null, 1_150L)
        gate.onSample(valid, 1_300L)
        assertFalse(gate.onSample(null, 1_450L).mustClose)
    }
}
