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
    ) = BlindSpotInput(
        blink, speedKmh, gearIsReverse, thresholdKmh, telemetryAgeMs,
        nativeCameraForeground = false,
    )

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

    /** The factory 360 owns the screen while it is up; our windows would only overlap it. */
    @Test fun `native 360 in the foreground hides the window but keeps the camera warm`() {
        val d = decideBlindSpot(input(blink = 2).copy(nativeCameraForeground = true))
        assertEquals(BlindSpotSide.NONE, d.show)
        assertTrue("camera must stay warm so the view returns instantly", d.cameraWarm)
    }

    /** The very same tick without the 360 shows the side, so only the 360 held it back. */
    @Test fun `the same blinker shows again once the native 360 is gone`() {
        assertEquals(
            BlindSpotSide.LEFT,
            decideBlindSpot(input(blink = 2).copy(nativeCameraForeground = false)).show,
        )
    }

    /** Reverse still wins: the factory rear view is up, and nothing of ours stays warm. */
    @Test fun `reverse closes everything even while the native 360 is up`() {
        val d = decideBlindSpot(
            input(blink = 2, gearIsReverse = true).copy(nativeCameraForeground = true),
        )
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

class BlindSpotArmedReasonTest {

    @Test fun `disabled feature reports disabled regardless of speed or gear`() {
        assertEquals(
            "disabled",
            blindSpotArmedReason(enabled = false, gear = 4, speedKmh = 80, thresholdKmh = 20))
    }

    @Test fun `speed below the warm band names the gap`() {
        assertEquals(
            "speed 0 < 15",
            blindSpotArmedReason(enabled = true, gear = 4, speedKmh = 0, thresholdKmh = 20))
    }

    @Test fun `speed inside the warm band is ok`() {
        assertEquals(
            "ok",
            blindSpotArmedReason(enabled = true, gear = 4, speedKmh = 30, thresholdKmh = 20))
    }

    @Test fun `reverse gear wins over speed`() {
        assertEquals(
            "reverse",
            blindSpotArmedReason(enabled = true, gear = 2, speedKmh = 80, thresholdKmh = 20))
    }

    @Test fun `missing speed is its own reason`() {
        assertEquals(
            "no speed",
            blindSpotArmedReason(enabled = true, gear = 4, speedKmh = null, thresholdKmh = 20))
    }
}

class BlindSpotFrameStallTest {

    private fun stalled(
        shown: Boolean = true,
        hasValidFrame: Boolean = true,
        lastFrameAt: Long = 1_000L,
        now: Long = 2_500L,
    ) = blindSpotFrameStalled(shown, hasValidFrame, lastFrameAt, now)

    @Test fun `a shown window silent for the stall window is stalled`() {
        assertTrue(stalled())
    }

    @Test fun `a hidden window is never stalled`() {
        // Off screen it may legitimately stop being redrawn.
        assertFalse(stalled(shown = false))
    }

    @Test fun `before the first honest frame the first-frame timeout owns the case`() {
        assertFalse(stalled(hasValidFrame = false))
    }

    @Test fun `a window that never delivered an update is not stalled`() {
        assertFalse(stalled(lastFrameAt = 0L))
    }

    @Test fun `just under the stall window is still alive`() {
        assertFalse(stalled(now = 2_499L))
        assertTrue(stalled(now = 2_500L))
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

class BlindSpotScreenRoutingTest {

    private val main = BlindSpotScreen.MAIN
    private val cluster = BlindSpotScreen.CLUSTER

    private fun route(side: BlindSpotSide, bothOnMain: Boolean, bothOnCluster: Boolean, hasCluster: Boolean) =
        blindSpotScreen(side, bothOnMain, bothOnCluster, hasCluster)

    @Test fun `without a cluster display both cameras go to the main screen whatever the opt-ins`() {
        for (onMain in listOf(false, true)) for (onCluster in listOf(false, true)) {
            assertEquals(main, route(BlindSpotSide.LEFT, onMain, onCluster, hasCluster = false))
            assertEquals(main, route(BlindSpotSide.RIGHT, onMain, onCluster, hasCluster = false))
        }
    }

    @Test fun `default with a cluster display - left on the cluster, right on the main screen`() {
        assertEquals(cluster, route(BlindSpotSide.LEFT, false, false, hasCluster = true))
        assertEquals(main, route(BlindSpotSide.RIGHT, false, false, hasCluster = true))
    }

    @Test fun `both on main keeps both on the main screen`() {
        assertEquals(main, route(BlindSpotSide.LEFT, true, false, hasCluster = true))
        assertEquals(main, route(BlindSpotSide.RIGHT, true, false, hasCluster = true))
    }

    @Test fun `both on cluster puts both on the cluster`() {
        assertEquals(cluster, route(BlindSpotSide.LEFT, false, true, hasCluster = true))
        assertEquals(cluster, route(BlindSpotSide.RIGHT, false, true, hasCluster = true))
    }

    @Test fun `both opt-ins set at once - main wins, the cluster is not touched`() {
        assertEquals(main, route(BlindSpotSide.LEFT, true, true, hasCluster = true))
        assertEquals(main, route(BlindSpotSide.RIGHT, true, true, hasCluster = true))
    }

    @Test fun `the routing reason names the rule that decided`() {
        assertEquals("no_cluster_display", blindSpotRoutingReason(false, true, hasClusterDisplay = false))
        assertEquals("both_on_main", blindSpotRoutingReason(true, true, hasClusterDisplay = true))
        assertEquals("both_on_cluster", blindSpotRoutingReason(false, true, hasClusterDisplay = true))
        assertEquals("default", blindSpotRoutingReason(false, false, hasClusterDisplay = true))
    }
}

class BlindSpotMainScreenCoverageTest {

    @Test fun `the right window covers the main screen only while it sits there`() {
        assertTrue(blindSpotCoversMainScreen(BlindSpotSide.RIGHT, leftOnMainScreen = false, rightOnMainScreen = true))
        assertTrue(blindSpotCoversMainScreen(BlindSpotSide.RIGHT, leftOnMainScreen = true, rightOnMainScreen = true))
        assertFalse(blindSpotCoversMainScreen(BlindSpotSide.RIGHT, leftOnMainScreen = false, rightOnMainScreen = false))
    }

    @Test fun `the left window covers it only as the mirrored fallback`() {
        assertFalse(blindSpotCoversMainScreen(BlindSpotSide.LEFT, leftOnMainScreen = false, rightOnMainScreen = true))
        assertTrue(blindSpotCoversMainScreen(BlindSpotSide.LEFT, leftOnMainScreen = true, rightOnMainScreen = true))
    }

    @Test fun `nothing shown covers nothing`() {
        assertFalse(blindSpotCoversMainScreen(BlindSpotSide.NONE, leftOnMainScreen = false, rightOnMainScreen = true))
        assertFalse(blindSpotCoversMainScreen(BlindSpotSide.NONE, leftOnMainScreen = true, rightOnMainScreen = true))
    }
}
