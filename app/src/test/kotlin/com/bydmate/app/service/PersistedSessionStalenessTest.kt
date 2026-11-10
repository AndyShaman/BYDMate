package com.bydmate.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistedSessionStalenessTest {

    @Test
    fun `fresh session within grace window is not stale`() {
        val p = PersistedSession(sessionStartedAt = 1_000L, lastActiveTs = 5_000L)
        // now is 20 sec after lastActiveTs, threshold is 30 sec
        assertFalse(p.isStale(now = 25_000L, idleCloseMs = 30_000L))
    }

    @Test
    fun `session idle exactly at threshold is stale`() {
        val p = PersistedSession(sessionStartedAt = 1_000L, lastActiveTs = 5_000L)
        assertTrue(p.isStale(now = 35_000L, idleCloseMs = 30_000L))
    }

    @Test
    fun `session idle longer than threshold is stale`() {
        val p = PersistedSession(sessionStartedAt = 1_000L, lastActiveTs = 5_000L)
        assertTrue(p.isStale(now = 100_000L, idleCloseMs = 30_000L))
    }

    @Test
    fun `repro v2_4_5 ghost session — 11h43m trip-time on Song`() {
        // User report: machine drove 5 min, parked 20 min, process was killed
        // before 30-sec idle-close fired. Prefs hold the 25-min-old anchor.
        // Next ignition-on is 11 hours later — without the staleness guard
        // the widget would render trip-time as "11 ч 43 мин".
        val driveStart = 0L
        val lastActive = 25 * 60_000L             // 25 min into the dead session
        val nextIgnition = 11 * 3_600_000L + 43 * 60_000L + lastActive
        val p = PersistedSession(
            sessionStartedAt = driveStart,
            lastActiveTs = lastActive,
        )
        assertTrue(p.isStale(now = nextIgnition, idleCloseMs = 30_000L))
    }

    @Test
    fun `process restart within the 30-sec grace window keeps the anchor`() {
        // Legitimate case we must NOT break: sys-kill mid-drive, restart 10 sec later.
        // The session must resume — that's the whole point of SessionPersistence.
        val p = PersistedSession(
            sessionStartedAt = 1_000_000L,
            lastActiveTs = 1_050_000L,
        )
        assertFalse(p.isStale(now = 1_060_000L, idleCloseMs = 30_000L))
    }
}
