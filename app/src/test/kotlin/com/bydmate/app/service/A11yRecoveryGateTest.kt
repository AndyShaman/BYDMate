package com.bydmate.app.service

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate keeps the Android 10 a11y recovery (force-stop of our own process) to one attempt
 * per 10 minutes, including when the head unit's clock jumps backwards after boot.
 */
class A11yRecoveryGateTest {

    // elapsedRealtime-style values: milliseconds since boot.
    private val now = 3 * 60 * 60 * 1000L

    // A long streak: the time gate is what decides.
    private val streak = A11yRecoveryGate.MAX_QUICK_ATTEMPTS

    @Test
    fun `never attempted is allowed`() {
        assertTrue(A11yRecoveryGate.shouldAttempt(lastAttemptElapsedMs = 0L, nowElapsedMs = now, failStreak = 0))
    }

    @Test
    fun `short fail streak is allowed immediately even right after the last attempt`() {
        assertTrue(A11yRecoveryGate.shouldAttempt(now - 5_000L, now, failStreak = 1))
    }

    @Test
    fun `nine minutes after the last attempt is blocked once the streak is long`() {
        assertFalse(A11yRecoveryGate.shouldAttempt(now - 9 * 60 * 1000L, now, streak))
    }

    @Test
    fun `exactly the interval is allowed`() {
        assertTrue(A11yRecoveryGate.shouldAttempt(now - A11yRecoveryGate.MIN_INTERVAL_MS, now, streak))
    }

    @Test
    fun `smaller uptime than the stored one means a reboot and is allowed`() {
        assertTrue(A11yRecoveryGate.shouldAttempt(lastAttemptElapsedMs = now, nowElapsedMs = 30_000L, failStreak = streak))
    }

    @Test
    fun `wall clock is not consulted so a time correction cannot unlock a retry`() {
        // The gate only ever sees elapsedRealtime; a one-hour wall-clock jump leaves it unchanged.
        val last = now - 2 * 60 * 1000L
        assertFalse(A11yRecoveryGate.shouldAttempt(last, now, streak))
        assertFalse(A11yRecoveryGate.shouldAttempt(last, now + 5 * 1000L, streak))
    }

    @Test
    fun `markAttempt reports a failed commit so the caller skips the force-stop`() {
        val editor = mockk<SharedPreferences.Editor>()
        every { editor.putLong(A11yRecoveryGate.KEY_LAST_ATTEMPT_ELAPSED_MS, now) } returns editor
        every { editor.putInt(A11yRecoveryGate.KEY_FAIL_STREAK, 1) } returns editor
        every { editor.commit() } returns false
        val prefs = mockk<SharedPreferences>()
        every { prefs.edit() } returns editor
        every { prefs.getInt(A11yRecoveryGate.KEY_FAIL_STREAK, 0) } returns 0

        assertFalse(A11yRecoveryGate.markAttempt(prefs, now))
    }

    @Test
    fun `markAttempt reports a successful commit`() {
        val editor = mockk<SharedPreferences.Editor>()
        every { editor.putLong(A11yRecoveryGate.KEY_LAST_ATTEMPT_ELAPSED_MS, now) } returns editor
        every { editor.putInt(A11yRecoveryGate.KEY_FAIL_STREAK, 3) } returns editor
        every { editor.commit() } returns true
        val prefs = mockk<SharedPreferences>()
        every { prefs.edit() } returns editor
        every { prefs.getInt(A11yRecoveryGate.KEY_FAIL_STREAK, 0) } returns 2

        assertTrue(A11yRecoveryGate.markAttempt(prefs, now))
    }

    @Test
    fun `markBound clears the streak so the next ignition cycle recovers immediately`() {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putInt(A11yRecoveryGate.KEY_FAIL_STREAK, 0) } returns editor
        val prefs = mockk<SharedPreferences>()
        every { prefs.edit() } returns editor
        every { prefs.getInt(A11yRecoveryGate.KEY_FAIL_STREAK, 0) } returns 2

        A11yRecoveryGate.markBound(prefs)
        io.mockk.verify { editor.putInt(A11yRecoveryGate.KEY_FAIL_STREAK, 0); editor.apply() }
    }
}
