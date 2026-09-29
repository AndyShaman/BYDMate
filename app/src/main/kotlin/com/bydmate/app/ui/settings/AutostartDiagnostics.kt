package com.bydmate.app.ui.settings

import com.bydmate.app.service.A11yRecoveryGate

/**
 * The `--- autostart ---` dump section: every entry of the persisted start chain (BootReceiver ->
 * WorkManager -> TrackingService, oldest first) and the Android 10 a11y recovery gate as the next
 * attempt would see it, so a dump tells whether our own recovery was allowed to force-stop the app.
 */
internal object AutostartDiagnostics {

    fun format(chainLog: String?, failStreak: Int, lastAttemptElapsedMs: Long, nowElapsedMs: Long): List<String> {
        val out = ArrayList<String>()
        // ChainLog's first append leaves a leading empty line.
        val entries = chainLog.orEmpty().lines().filter { it.isNotBlank() }
        if (entries.isEmpty()) {
            out += "chain_log: (empty)"
        } else {
            out += "chain_log:"
            entries.forEach { out += "  $it" }
        }
        out += "a11y_recovery_fail_streak: $failStreak"
        out += "a11y_recovery_last_attempt_elapsed_ms: " +
            if (lastAttemptElapsedMs == 0L) "(never)" else lastAttemptElapsedMs.toString()
        out += "elapsed_realtime_ms: $nowElapsedMs"
        val waitMs = A11yRecoveryGate.remainingWaitMs(lastAttemptElapsedMs, nowElapsedMs, failStreak)
        out += "a11y_recovery_allowed_now: " + if (waitMs == 0L) "yes" else "no (wait_ms=$waitMs)"
        return out
    }
}
