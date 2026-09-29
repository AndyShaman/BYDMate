package com.bydmate.app.ui.settings

import com.bydmate.app.service.A11yRecoveryGate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The `--- autostart ---` dump section: the whole start chain the app kept on disk and the a11y
 * recovery gate as the next attempt would see it.
 */
class AutostartDiagnosticsTest {

    private val now = 3 * 60 * 60 * 1000L

    @Test fun `every stored chain entry is printed, oldest first, blank lines dropped`() {
        // ChainLog's first append leaves a leading empty line.
        val chain = "\n08:01:02 BootReceiver: android.intent.action.BOOT_COMPLETED\n08:01:03 Worker doWork started\n" +
            "08:01:04 TrackingService onCreate"
        val lines = AutostartDiagnostics.format(chain, failStreak = 0, lastAttemptElapsedMs = 0L, nowElapsedMs = now)
        assertEquals(
            listOf(
                "chain_log:",
                "  08:01:02 BootReceiver: android.intent.action.BOOT_COMPLETED",
                "  08:01:03 Worker doWork started",
                "  08:01:04 TrackingService onCreate",
                "a11y_recovery_fail_streak: 0",
                "a11y_recovery_last_attempt_elapsed_ms: (never)",
                "elapsed_realtime_ms: $now",
                "a11y_recovery_allowed_now: yes",
            ),
            lines,
        )
    }

    @Test fun `an empty chain log says so`() {
        val lines = AutostartDiagnostics.format(null, failStreak = 0, lastAttemptElapsedMs = 0L, nowElapsedMs = now)
        assertEquals("chain_log: (empty)", lines.first())
        assertEquals(
            "chain_log: (empty)",
            AutostartDiagnostics.format("\n", failStreak = 0, lastAttemptElapsedMs = 0L, nowElapsedMs = now).first(),
        )
    }

    @Test fun `a gate that refuses shows the streak, the last attempt and the remaining wait`() {
        val last = now - 4 * 60 * 1000L
        val lines = AutostartDiagnostics.format(
            null, failStreak = A11yRecoveryGate.MAX_QUICK_ATTEMPTS, lastAttemptElapsedMs = last, nowElapsedMs = now,
        )
        assertEquals(
            listOf(
                "chain_log: (empty)",
                "a11y_recovery_fail_streak: ${A11yRecoveryGate.MAX_QUICK_ATTEMPTS}",
                "a11y_recovery_last_attempt_elapsed_ms: $last",
                "elapsed_realtime_ms: $now",
                "a11y_recovery_allowed_now: no (wait_ms=${6 * 60 * 1000L})",
            ),
            lines,
        )
    }
}
