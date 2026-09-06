package com.bydmate.app.service

import android.content.SharedPreferences
import android.os.SystemClock

/**
 * Rate limiter for the Android 10 a11y recovery (force-stop + re-bind): the recovery kills our own
 * process, so an ungated retry loop would restart the app forever.
 *
 * Policy: a streak of failed attempts is counted; while the streak is short (< [MAX_QUICK_ATTEMPTS])
 * an attempt is allowed immediately, afterwards only once per [MIN_INTERVAL_MS]. A successful bind
 * ([markBound]) resets the streak, so every new ignition cycle gets a fresh immediate attempt (field
 * log 2026-09-06: the second off/on within 10 min was blocked by a pure time gate), while a firmware
 * where the recovery never works settles into the slow 10-minute cadence.
 *
 * Clock: SystemClock.elapsedRealtime(), which survives the force-stop (same boot) and cannot be moved
 * by the head unit's GPS/NTP time correction. After a reboot the counter restarts from ~0, below the
 * stored value: treated as "new boot, allowed" (the stuck state itself does not survive a reboot).
 */
object A11yRecoveryGate {
    const val KEY_LAST_ATTEMPT_ELAPSED_MS = "a11y_recovery_last_elapsed_ms"
    const val KEY_FAIL_STREAK = "a11y_recovery_fail_streak"
    const val MIN_INTERVAL_MS = 10 * 60 * 1000L
    const val MAX_QUICK_ATTEMPTS = 2

    fun shouldAttempt(lastAttemptElapsedMs: Long, nowElapsedMs: Long, failStreak: Int): Boolean =
        failStreak < MAX_QUICK_ATTEMPTS ||
            lastAttemptElapsedMs == 0L ||
            nowElapsedMs < lastAttemptElapsedMs ||
            nowElapsedMs - lastAttemptElapsedMs >= MIN_INTERVAL_MS

    fun shouldAttempt(prefs: SharedPreferences, nowElapsedMs: Long = SystemClock.elapsedRealtime()): Boolean =
        shouldAttempt(
            prefs.getLong(KEY_LAST_ATTEMPT_ELAPSED_MS, 0L),
            nowElapsedMs,
            prefs.getInt(KEY_FAIL_STREAK, 0),
        )

    /**
     * commit(), not apply(): the caller is about to be force-stopped. Returns the commit result:
     * if the mark did not persist, the caller must NOT recover, or the next process would see the
     * old state and force-stop again on every start. Increments the fail streak; [markBound] clears it.
     */
    fun markAttempt(prefs: SharedPreferences, nowElapsedMs: Long = SystemClock.elapsedRealtime()): Boolean =
        prefs.edit()
            .putLong(KEY_LAST_ATTEMPT_ELAPSED_MS, nowElapsedMs)
            .putInt(KEY_FAIL_STREAK, prefs.getInt(KEY_FAIL_STREAK, 0) + 1)
            .commit()

    /** The a11y service connected: the streak is over. apply() is fine here, nothing kills us. */
    fun markBound(prefs: SharedPreferences) {
        if (prefs.getInt(KEY_FAIL_STREAK, 0) != 0) prefs.edit().putInt(KEY_FAIL_STREAK, 0).apply()
    }
}
