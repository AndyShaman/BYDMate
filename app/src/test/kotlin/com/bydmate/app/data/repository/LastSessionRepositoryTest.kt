package com.bydmate.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LastSessionRepositoryTest {

    @Test
    fun `snapshot is null when no session recorded`() {
        val repo = LastSessionRepository()
        assertNull(repo.snapshot())
    }

    @Test
    fun `onSessionStart populates start fields and clears end fields`() {
        val repo = LastSessionRepository()
        // Simulate a previous ended session
        repo.onSessionStart(soc = 80, ts = 1_000L)
        repo.onSessionEnd(soc = 60, ts = 2_000L)

        // New session starts — end fields must be cleared
        repo.onSessionStart(soc = 95, ts = 3_000L)
        val snap = requireNotNull(repo.snapshot())

        assertEquals(95, snap.startSoc)
        assertEquals(3_000L, snap.startTs)
        assertNull("endSoc must be cleared on new session start", snap.endSoc)
        assertNull("endTs must be cleared on new session start", snap.endTs)
    }

    @Test
    fun `onSessionEnd populates end fields without touching start`() {
        val repo = LastSessionRepository()
        repo.onSessionStart(soc = 75, ts = 1_000L)
        repo.onSessionEnd(soc = 50, ts = 5_000L)

        val snap = requireNotNull(repo.snapshot())
        assertEquals(75, snap.startSoc)
        assertEquals(1_000L, snap.startTs)
        assertEquals(50, snap.endSoc)
        assertEquals(5_000L, snap.endTs)
    }

    @Test
    fun `null soc is stored as null (autoservice unavailable at session boundary)`() {
        val repo = LastSessionRepository()
        repo.onSessionStart(soc = null, ts = 1_000L)
        repo.onSessionEnd(soc = null, ts = 2_000L)

        val snap = requireNotNull(repo.snapshot())
        assertNull(snap.startSoc)
        assertNull(snap.endSoc)
        assertEquals(1_000L, snap.startTs)
        assertEquals(2_000L, snap.endTs)
    }

    @Test
    fun `snapshot is non-null after onSessionStart`() {
        val repo = LastSessionRepository()
        repo.onSessionStart(soc = 90, ts = 1_000L)
        assertNotNull(repo.snapshot())
    }

    @Test
    fun `onSessionEnd without prior start preserves null start fields`() {
        val repo = LastSessionRepository()
        repo.onSessionEnd(soc = 70, ts = 5_000L)

        val snap = requireNotNull(repo.snapshot())
        assertNull(snap.startSoc)
        assertNull(snap.startTs)
        assertEquals(70, snap.endSoc)
        assertEquals(5_000L, snap.endTs)
    }
}
