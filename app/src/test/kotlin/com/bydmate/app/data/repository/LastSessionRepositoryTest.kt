package com.bydmate.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class LastSessionRepositoryTest {

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `no match when nothing recorded`() {
        val repo = LastSessionRepository(ctx())
        assertNull(repo.takeMatch(1_000L))
    }

    @Test
    fun `completed session matched by endTs containment then consumed`() {
        val repo = LastSessionRepository(ctx())
        repo.onSessionStart(soc = 80, ts = 1_000L)
        repo.onSessionEnd(soc = 60, ts = 2_000L)

        val m = repo.takeMatch(2_000L)
        assertEquals(80, m?.startSoc)
        assertEquals(60, m?.endSoc)
        // consumed — a second match for the same window returns null
        assertNull(repo.takeMatch(2_000L))
    }

    @Test
    fun `endTs within tolerance after session end still matches`() {
        val repo = LastSessionRepository(ctx())
        repo.onSessionStart(soc = 90, ts = 10_000L)
        repo.onSessionEnd(soc = 70, ts = 20_000L)
        // trip endTs 25s after session end — inside the +30s tolerance
        assertEquals(70, repo.takeMatch(25_000L)?.endSoc)
    }

    @Test
    fun `bookmarks survive a process restart (persisted)`() {
        val context = ctx()
        val repo1 = LastSessionRepository(context)
        repo1.onSessionStart(soc = 55, ts = 1_000L)
        repo1.onSessionEnd(soc = 40, ts = 2_000L)

        // New instance, same Context — simulates a process restart
        val repo2 = LastSessionRepository(context)
        val m = repo2.takeMatch(2_000L)
        assertEquals(55, m?.startSoc)
        assertEquals(40, m?.endSoc)
    }

    @Test
    fun `multiple sessions each match independently`() {
        val repo = LastSessionRepository(ctx())
        repo.onSessionStart(soc = 80, ts = 1_000L)
        repo.onSessionEnd(soc = 70, ts = 2_000L)
        repo.onSessionStart(soc = 65, ts = 5_000L)
        repo.onSessionEnd(soc = 50, ts = 6_000L)

        assertEquals(50, repo.takeMatch(6_000L)?.endSoc)
        assertEquals(70, repo.takeMatch(2_000L)?.endSoc)
    }

    @Test
    fun `best match picks the session whose end is nearest the trip end`() {
        val repo = LastSessionRepository(ctx())
        // two overlapping windows both contain tripEnd = 2_500
        repo.onSessionStart(soc = 80, ts = 1_000L)
        repo.onSessionEnd(soc = 70, ts = 2_000L)   // end 2000, |2500-2000| = 500
        repo.onSessionStart(soc = 60, ts = 1_500L)
        repo.onSessionEnd(soc = 55, ts = 2_400L)   // end 2400, |2500-2400| = 100 -> nearest
        assertEquals(55, repo.takeMatch(2_500L)?.endSoc)
    }

    @Test
    fun `lazy init fills start soc captured null at session start tick`() {
        val repo = LastSessionRepository(ctx())
        repo.onSessionStart(soc = null, ts = 1_000L)  // cold-start sentinel
        repo.fillStartSocIfMissing(77)
        repo.onSessionEnd(soc = 60, ts = 2_000L)
        assertEquals(77, repo.takeMatch(2_000L)?.startSoc)
    }

    @Test
    fun `lazy init does not overwrite an already captured start soc`() {
        val repo = LastSessionRepository(ctx())
        repo.onSessionStart(soc = 90, ts = 1_000L)
        repo.fillStartSocIfMissing(50)
        repo.onSessionEnd(soc = 60, ts = 2_000L)
        assertEquals(90, repo.takeMatch(2_000L)?.startSoc)
    }

    @Test
    fun `pending session not yet ended is not matchable`() {
        val repo = LastSessionRepository(ctx())
        repo.onSessionStart(soc = 80, ts = 1_000L)
        assertNull(repo.takeMatch(1_000L))
    }

    @Test
    fun `session end without a prior start is not stored`() {
        val repo = LastSessionRepository(ctx())
        repo.onSessionEnd(soc = 70, ts = 5_000L)  // no start -> no window to match on
        assertNull(repo.takeMatch(5_000L))
    }

    @Test
    fun `oldest bookmark is trimmed when count cap exceeded`() {
        val repo = LastSessionRepository(ctx())
        // 25 isolated sessions (spacing 100s >> 30s tolerance); window n = [n*100k .. n*100k+10k]
        for (n in 1..25) {
            val start = n * 100_000L
            repo.onSessionStart(soc = 100, ts = start)
            repo.onSessionEnd(soc = 90, ts = start + 10_000L)
        }
        // session #1 (end 110_000) dropped by the count cap (keeps last 20)
        assertNull(repo.takeMatch(110_000L))
        // session #25 (end 2_510_000) still present
        assertEquals(90, repo.takeMatch(2_510_000L)?.endSoc)
    }

    @Test
    fun `bookmark older than max age is trimmed`() {
        val repo = LastSessionRepository(ctx())
        val day = 86_400_000L
        repo.onSessionStart(soc = 80, ts = 1_000L)
        repo.onSessionEnd(soc = 70, ts = 2_000L)
        // a new session 8 days later triggers age-trim of the stale one
        repo.onSessionStart(soc = 60, ts = 8 * day)
        repo.onSessionEnd(soc = 50, ts = 8 * day + 1_000L)
        assertNull("stale bookmark should be age-trimmed", repo.takeMatch(2_000L))
        assertEquals(50, repo.takeMatch(8 * day + 1_000L)?.endSoc)
    }

    @Test
    fun `live soc persisted each tick survives a power-cut and reconciles to a bookmark`() {
        val context = ctx()
        val repo1 = LastSessionRepository(context)
        repo1.onSessionStart(soc = 80, ts = 1_000L)
        repo1.updateLiveSoc(soc = 75, ts = 2_000L)
        repo1.updateLiveSoc(soc = 72, ts = 3_000L)
        // power-cut at ignition-off: onSessionEnd never fires, process dies

        // next ignition-on, fresh process reads the persisted open session
        val repo2 = LastSessionRepository(context)
        repo2.reconcileStaleOpenSession(now = 3_000L + 40_000L, idleMs = 30_000L)

        val m = repo2.takeMatch(3_000L)
        assertEquals(80, m?.startSoc)
        assertEquals(72, m?.endSoc)   // last live reading before the cut
    }

    @Test
    fun `open session still live is not reconciled`() {
        val repo = LastSessionRepository(ctx())
        repo.onSessionStart(soc = 80, ts = 1_000L)
        repo.updateLiveSoc(soc = 75, ts = 2_000L)
        // only 10s since the last tick — still driving, must not close
        repo.reconcileStaleOpenSession(now = 2_000L + 10_000L, idleMs = 30_000L)
        assertNull(repo.takeMatch(2_000L))
    }

    @Test
    fun `reconcile is a no-op when no open session exists`() {
        val repo = LastSessionRepository(ctx())
        repo.reconcileStaleOpenSession(now = 100_000L, idleMs = 30_000L)
        assertNull(repo.takeMatch(100_000L))
    }

    @Test
    fun `onSessionEnd falls back to last live soc when the end-tick soc is null`() {
        val repo = LastSessionRepository(ctx())
        repo.onSessionStart(soc = 80, ts = 1_000L)
        repo.updateLiveSoc(soc = 60, ts = 2_000L)
        // engine-off sentinels-out the SOC fid at the closing tick
        repo.onSessionEnd(soc = null, ts = 3_000L)
        assertEquals(60, repo.takeMatch(3_000L)?.endSoc)
    }

    @Test
    fun `persisted pending drive is invisible to takeMatch until reconciled - importer must reconcile first`() {
        // Reproduces the trip-SOC dash bug: a drive that ended without onSessionEnd (DiLink
        // dies instantly at ignition-off) leaves a stale `pending` on disk. An energydata
        // import that runs before reconcile (e.g. BYDMateApp.onCreate.runSync, which fires
        // before TrackingService.onCreate's reconcile) sees nothing and would write null SOC.
        // HistoryImporter.doSync now reconciles first; this locks in that contract.
        val context = ctx()
        val repo1 = LastSessionRepository(context)
        repo1.onSessionStart(soc = 88, ts = 1_000_000L)
        repo1.updateLiveSoc(soc = 84, ts = 1_060_000L)   // ~1 min drive, power-cut, no onSessionEnd

        // Fresh process at next ignition-on; the energydata trip ended at 1_060_000.
        val repo2 = LastSessionRepository(context)
        // Before reconcile the promoted bookmark is absent — this is the dash bug.
        assertNull(repo2.takeMatch(1_060_000L))
        // After reconcile (what doSync now does up front) the same match yields the SOC.
        repo2.reconcileStaleOpenSession(now = 1_060_000L + 60_000L, idleMs = 10_000L)
        val m = repo2.takeMatch(1_060_000L)
        assertEquals(88, m?.startSoc)
        assertEquals(84, m?.endSoc)
    }

    @Test
    fun `updateLiveSoc fills start soc when it was null at the start tick`() {
        val repo = LastSessionRepository(ctx())
        repo.onSessionStart(soc = null, ts = 1_000L)   // cold-start sentinel
        repo.updateLiveSoc(soc = 77, ts = 2_000L)
        repo.onSessionEnd(soc = 60, ts = 3_000L)
        assertEquals(77, repo.takeMatch(3_000L)?.startSoc)
    }

    /**
     * Fake SharedPreferences (MockK) with a write counter, so the updateLiveSoc
     * prefs-write gate below can be asserted directly instead of poking at
     * private fields. Mirrors LocalePreferencesTest's mockSetup.
     */
    private fun mockCountingPrefs(): Pair<Context, IntArray> {
        val store = mutableMapOf<String, Any?>()
        val writeCount = IntArray(1)
        val editor = mockk<SharedPreferences.Editor>()
        every { editor.putString(any(), any()) } answers {
            store[firstArg<String>()] = secondArg<String?>(); editor
        }
        every { editor.remove(any()) } answers {
            store.remove(firstArg<String>()); editor
        }
        every { editor.apply() } answers { writeCount[0]++ }
        val prefs = mockk<SharedPreferences>()
        every { prefs.getString(any(), any()) } answers {
            store[firstArg<String>()] as String? ?: secondArg<String?>()
        }
        every { prefs.edit() } returns editor
        val ctx = mockk<Context>()
        every { ctx.getSharedPreferences(any(), any()) } returns prefs
        return ctx to writeCount
    }

    @Test
    fun `updateLiveSoc skips the prefs write when soc is unchanged within the heartbeat window`() {
        val (ctx, writeCount) = mockCountingPrefs()
        val repo = LastSessionRepository(ctx)
        repo.onSessionStart(soc = 80, ts = 1_000L)
        val afterStart = writeCount[0]

        repo.updateLiveSoc(soc = 75, ts = 2_000L)   // first live tick -- no prior persisted soc, always writes
        repo.updateLiveSoc(soc = 75, ts = 2_500L)   // same soc, 500ms later -- well inside the 15s heartbeat, skipped

        assertEquals(afterStart + 1, writeCount[0])
    }

    /**
     * The heartbeat backstop must persist an unchanged-soc tick BEFORE END_TOLERANCE_MS
     * (30s) elapses. On a hard power-cut the persisted endTs is what
     * reconcileStaleOpenSession finalizes the trip with, and HistoryImporter only matches
     * the energydata trip end within endTs + 30s. A heartbeat >= 30s (e.g. the old 60s)
     * would let endTs lag past that window and silently drop the trip's SOC enrichment.
     * This guard advances exactly END_TOLERANCE_MS, so it fails for any heartbeat that
     * exceeds the tolerance (the old 60s) — it pins HEARTBEAT_MS <= END_TOLERANCE_MS.
     * The actual 15s / 2x-margin design choice is documented at the constant itself.
     */
    @Test
    fun `updateLiveSoc forces a write within END_TOLERANCE_MS even with an unchanged soc`() {
        val (ctx, writeCount) = mockCountingPrefs()
        val repo = LastSessionRepository(ctx)
        repo.onSessionStart(soc = 80, ts = 1_000L)
        repo.updateLiveSoc(soc = 75, ts = 2_000L)
        val afterFirstTick = writeCount[0]

        // Unchanged soc for the whole parked tail; by the 30s tolerance the heartbeat
        // must already have flushed a fresh endTs to disk (would FAIL at the old 60s).
        repo.updateLiveSoc(soc = 75, ts = 2_000L + 30_000L)

        assertTrue(
            "heartbeat must persist an unchanged-soc tick within END_TOLERANCE_MS (30s) " +
                "to keep the power-cut endTs inside HistoryImporter's match window",
            writeCount[0] > afterFirstTick,
        )
    }

    @Test
    fun `session end always persists regardless of the updateLiveSoc gate`() {
        val (ctx, writeCount) = mockCountingPrefs()
        val repo = LastSessionRepository(ctx)
        repo.onSessionStart(soc = 80, ts = 1_000L)
        repo.updateLiveSoc(soc = 75, ts = 2_000L)
        repo.updateLiveSoc(soc = 75, ts = 2_500L)   // gate-skipped, but pending stays accurate in memory
        val beforeEnd = writeCount[0]

        repo.onSessionEnd(soc = 75, ts = 3_000L)

        assertTrue("onSessionEnd must always persist", writeCount[0] > beforeEnd)
        assertEquals(75, repo.takeMatch(3_000L)?.endSoc)
    }
}
