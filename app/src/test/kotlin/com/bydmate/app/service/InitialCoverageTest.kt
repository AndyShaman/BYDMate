package com.bydmate.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Pure JVM tests for the companion helper that decides the initial liveWholeSession
// flag at service (re)start. No Android context required.
class InitialCoverageTest {

    @Test fun `valid restore with baselines ok returns true`() {
        assertTrue(TrackingService.computeInitialCoverage(restoredBaselinesOk = true, retainedAnchor = false))
    }

    @Test fun `valid restore with baselines missing returns false`() {
        assertFalse(TrackingService.computeInitialCoverage(restoredBaselinesOk = false, retainedAnchor = false))
    }

    @Test fun `no valid restore with retained companion anchor returns false`() {
        // Same-process restart: load() returned null or stale, but a session anchor
        // survived in the companion from the previous service instance.
        assertFalse(TrackingService.computeInitialCoverage(restoredBaselinesOk = null, retainedAnchor = true))
    }

    @Test fun `no valid restore with no retained anchor returns true`() {
        // Fresh start: no prior companion anchor and no persisted session to restore.
        assertTrue(TrackingService.computeInitialCoverage(restoredBaselinesOk = null, retainedAnchor = false))
    }
}
