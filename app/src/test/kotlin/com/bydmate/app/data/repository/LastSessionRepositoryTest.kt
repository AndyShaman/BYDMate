package com.bydmate.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
