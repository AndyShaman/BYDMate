package com.bydmate.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Pure JVM test of the helper-daemon watchdog respawn cooldown gate. Touching the companion
// does NOT instantiate the Android Service — see TrackingServiceButtonBridgeTest.
class WatchdogGateTest {

    @Test fun `respawn allowed on the very first health-check failure`() {
        // lastAttemptMs = 0L is the service field's initial value before any respawn was tried.
        assertTrue(TrackingService.shouldAttemptRespawn(nowMs = 1_000_000L, lastAttemptMs = 0L))
    }

    @Test fun `respawn blocked inside the cooldown window`() {
        val last = 1_000_000L
        assertFalse(TrackingService.shouldAttemptRespawn(nowMs = last + 59_999L, lastAttemptMs = last))
    }

    @Test fun `respawn allowed exactly at the cooldown boundary`() {
        val last = 1_000_000L
        assertTrue(TrackingService.shouldAttemptRespawn(nowMs = last + 60_000L, lastAttemptMs = last))
    }

    @Test fun `respawn allowed once the cooldown window has fully elapsed`() {
        val last = 1_000_000L
        assertTrue(TrackingService.shouldAttemptRespawn(nowMs = last + 120_000L, lastAttemptMs = last))
    }
}
