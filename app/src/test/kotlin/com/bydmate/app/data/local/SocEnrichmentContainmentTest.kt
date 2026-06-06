package com.bydmate.app.data.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the SOC enrichment containment check in HistoryImporter.doSync().
 *
 * Logic under test (extracted for isolation):
 *   withinSession = endTsMs in sessionStartTs..(sessionEndTs + 30_000L)
 *
 * Semantics: a trip is enriched when its endTs falls inside the session window.
 * This is a containment check on endTs, NOT an intersection check.
 */
class SocEnrichmentContainmentTest {

    // Mirror of the inline check in HistoryImporter.doSync()
    private fun withinSession(
        tripStartTsMs: Long,
        tripEndTsMs: Long,
        sessionStartTs: Long,
        sessionEndTs: Long,
        toleranceMs: Long = 30_000L,
    ): Boolean = tripEndTsMs in sessionStartTs..(sessionEndTs + toleranceMs)

    // Session: 10_000 .. 60_000  (+ 30s tolerance = 90_000)
    private val sessionStart = 10_000L
    private val sessionEnd   = 60_000L

    @Test
    fun `trip fully inside session gets enrichment`() {
        // trip 20_000..50_000 — entirely within session
        assertTrue(withinSession(20_000L, 50_000L, sessionStart, sessionEnd))
    }

    @Test
    fun `trip endTs on session start boundary gets enrichment`() {
        // endTs == sessionStart — barely inside
        assertTrue(withinSession(5_000L, 10_000L, sessionStart, sessionEnd))
    }

    @Test
    fun `trip endTs within tolerance window gets enrichment`() {
        // endTs = sessionEnd + 15_000 < sessionEnd + 30_000 → inside tolerance
        assertTrue(withinSession(50_000L, 75_000L, sessionStart, sessionEnd))
    }

    @Test
    fun `trip endTs exactly at tolerance boundary gets enrichment`() {
        // endTs == sessionEnd + 30_000 — last valid ms
        assertTrue(withinSession(50_000L, 90_000L, sessionStart, sessionEnd))
    }

    @Test
    fun `trip endTs one ms past tolerance boundary does not get enrichment`() {
        // endTs = sessionEnd + 30_001 — just outside tolerance
        assertFalse(withinSession(50_000L, 90_001L, sessionStart, sessionEnd))
    }

    @Test
    fun `trip startTs before session but endTs inside gets enrichment`() {
        // Trip started before session, ended inside — endTs containment means YES
        assertTrue(withinSession(1_000L, 40_000L, sessionStart, sessionEnd))
    }

    @Test
    fun `trip startTs inside session but endTs after tolerance does not get enrichment`() {
        // OLD intersection logic would have returned true; containment returns false
        assertFalse(withinSession(30_000L, 100_000L, sessionStart, sessionEnd))
    }

    @Test
    fun `trip entirely before session does not get enrichment`() {
        assertFalse(withinSession(1_000L, 5_000L, sessionStart, sessionEnd))
    }
}
