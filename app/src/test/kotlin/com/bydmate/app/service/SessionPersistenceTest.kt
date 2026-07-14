package com.bydmate.app.service

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P5: save() must skip the fsync write when the session anchor is unchanged and
 * the last write is still within WRITE_GRANULARITY_MS. A new session's very
 * first save() must never be delayed by that window -- lastActiveTs feeds the
 * SESSION_IDLE_CLOSE_MS=10s staleness check in PersistedSession.isStale(), and a
 * delayed anchor write risks resuming a stale prior session after a crash (the
 * ghost-session class PersistedSessionStalenessTest guards against).
 */
class SessionPersistenceTest {

    private fun mockCountingPrefs(): Pair<Context, IntArray> {
        val store = mutableMapOf<String, Any?>()
        val writeCount = IntArray(1)
        val editor = mockk<SharedPreferences.Editor>()
        every { editor.putLong(any(), any()) } answers {
            store[firstArg<String>()] = secondArg<Long>(); editor
        }
        every { editor.apply() } answers { writeCount[0]++ }
        val prefs = mockk<SharedPreferences>()
        every { prefs.edit() } returns editor
        val ctx = mockk<Context>()
        every { ctx.applicationContext } returns ctx
        every { ctx.getSharedPreferences(any(), any()) } returns prefs
        return ctx to writeCount
    }

    @Test
    fun `two saves within the granularity window write once`() {
        val (ctx, writeCount) = mockCountingPrefs()
        val sp = SessionPersistence(ctx)

        sp.save(sessionStartedAt = 1_000L, lastActiveTs = 1_000L)   // first save -- always writes
        sp.save(sessionStartedAt = 1_000L, lastActiveTs = 3_000L)   // same session, 2s later -- inside the 5s window, skipped

        assertEquals(1, writeCount[0])
    }

    @Test
    fun `a save past the granularity window writes again`() {
        val (ctx, writeCount) = mockCountingPrefs()
        val sp = SessionPersistence(ctx)

        sp.save(sessionStartedAt = 1_000L, lastActiveTs = 1_000L)
        sp.save(sessionStartedAt = 1_000L, lastActiveTs = 1_000L + 5_000L)   // exactly at the 5s boundary -- writes

        assertEquals(2, writeCount[0])
    }

    @Test
    fun `a new session's first save is never delayed by the granularity window`() {
        val (ctx, writeCount) = mockCountingPrefs()
        val sp = SessionPersistence(ctx)

        sp.save(sessionStartedAt = 1_000L, lastActiveTs = 1_000L)
        // A different session starting 1s later must not be swallowed by the
        // 5s granularity gate -- its anchor would otherwise never reach disk.
        sp.save(sessionStartedAt = 2_000L, lastActiveTs = 2_000L)

        assertEquals(2, writeCount[0])
    }
}
