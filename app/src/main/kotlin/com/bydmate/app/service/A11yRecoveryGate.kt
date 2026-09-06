package com.bydmate.app.service

import android.content.SharedPreferences
import android.os.SystemClock

/**
 * Rate limiter for the Android 10 a11y recovery (force-stop + re-bind): the recovery kills our own
 * process, so an ungated retry loop would restart the app forever. One attempt per 10 minutes.
 *
 * Clock: SystemClock.elapsedRealtime(), which survives the force-stop (same boot) and cannot be
 * moved by the head unit's GPS/NTP time correction; wall-clock jumps must not unlock a second
 * attempt. After a reboot the counter restarts from ~0, which is below the stored value: that is
 * treated as "new boot, allowed" (the stuck state itself does not survive a reboot).
 */
object A11yRecoveryGate {
    const val KEY_LAST_ATTEMPT_ELAPSED_MS = "a11y_recovery_last_elapsed_ms"
    const val MIN_INTERVAL_MS = 10 * 60 * 1000L

    fun shouldAttempt(lastAttemptElapsedMs: Long, nowElapsedMs: Long): Boolean =
        lastAttemptElapsedMs == 0L ||
            nowElapsedMs < lastAttemptElapsedMs ||
            nowElapsedMs - lastAttemptElapsedMs >= MIN_INTERVAL_MS

    fun shouldAttempt(prefs: SharedPreferences, nowElapsedMs: Long = SystemClock.elapsedRealtime()): Boolean =
        shouldAttempt(prefs.getLong(KEY_LAST_ATTEMPT_ELAPSED_MS, 0L), nowElapsedMs)

    /**
     * commit(), not apply(): the caller is about to be force-stopped. Returns the commit result:
     * if the mark did not persist, the caller must NOT recover, or the next process would see the
     * old timestamp and force-stop again on every start.
     */
    fun markAttempt(prefs: SharedPreferences, nowElapsedMs: Long = SystemClock.elapsedRealtime()): Boolean =
        prefs.edit().putLong(KEY_LAST_ATTEMPT_ELAPSED_MS, nowElapsedMs).commit()
}
