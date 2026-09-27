package com.bydmate.app.service

import android.location.Location
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

// Pure JVM tests for the top-level helper that decides lastLocationFix at service (re)start.
// Review: location and its live/seed origin used to be two separate fields (a Location StateFlow
// plus a @Volatile Boolean), so a restart with no fresh seed left a previous run's "live" fix
// looking current. nextSnapshotOnRestart always returns a whole, correctly-live snapshot.
class TrackingServiceLocationFixTest {

    @Test fun `fresh lastKnown becomes the new snapshot, never live`() {
        val previous = TrackingService.LocationFix(mockk(), isLive = true)
        val lastKnown = mockk<Location>()
        val next = nextSnapshotOnRestart(previous, lastKnown)
        assertSame(lastKnown, next?.location)
        assertFalse(next!!.isLive)
    }

    @Test fun `no lastKnown demotes a retained live snapshot instead of keeping it live`() {
        val previous = TrackingService.LocationFix(mockk(), isLive = true)
        val next = nextSnapshotOnRestart(previous, lastKnown = null)
        assertSame(previous.location, next?.location)
        assertFalse(next!!.isLive)
    }

    @Test fun `no lastKnown and no previous stays null`() {
        assertNull(nextSnapshotOnRestart(previous = null, lastKnown = null))
    }

    @Test fun `no lastKnown leaves an already-demoted snapshot as is`() {
        val previous = TrackingService.LocationFix(mockk(), isLive = false)
        val next = nextSnapshotOnRestart(previous, lastKnown = null)
        assertEquals(previous, next)
    }
}
