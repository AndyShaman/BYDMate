package com.bydmate.app.service

import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.delay

/**
 * One verify-and-retry engine for every daemon-backed self-grant (star a11y key filter,
 * notification-listener access). The system applies these grants asynchronously and loses
 * races around boot, APK updates and daemon restarts; a single fire-and-forget attempt
 * silently strands the feature until the next service restart (field defect: the
 * notification-listener grant never landed on-car, leaving the navi mirror and real music
 * sessions dead). Every grant therefore runs the same loop: check the TRUE system-side
 * state first, re-assert only when the grant is actually missing, retry with a delay, and
 * never disturb a healthy grant.
 */
class GrantSelfHeal(
    private val name: String,
    private val isGranted: () -> Boolean,
    private val reassert: suspend () -> Boolean,
    private val attempts: Int = ATTEMPTS,
    private val retryDelayMs: Long = RETRY_MS,
) {
    suspend fun ensure(reason: String) {
        val reasserts = ArrayList<Boolean>(attempts)
        repeat(attempts) { attempt ->
            if (runCatching { isGranted() }.getOrDefault(false)) {
                if (attempt > 0) Log.i(TAG, "$name confirmed granted ($reason, try ${attempt + 1})")
                record(name, reason, attempt + 1, reasserts, granted = true)
                return
            }
            val ok = runCatching { reassert() }.getOrDefault(false)
            reasserts.add(ok)
            Log.i(TAG, "$name not granted; re-asserted ($reason, try ${attempt + 1}) -> $ok")
            delay(retryDelayMs)
        }
        val granted = runCatching { isGranted() }.getOrDefault(false)
        if (!granted) {
            Log.w(TAG, "$name still not granted after $attempts tries ($reason)")
        }
        record(name, reason, attempts, reasserts, granted)
    }

    /**
     * One ensure() run. [reasserts] holds the daemon's answer per try (empty when the grant was
     * already there on the first check), which separates the two field failure modes: all-false
     * reasserts = the daemon path itself is broken, true reasserts with granted=false = the
     * system reverts the setting behind our back.
     */
    data class Event(
        val ts: Long,
        val name: String,
        val reason: String,
        val tries: Int,
        val reasserts: List<Boolean>,
        val granted: Boolean,
    )

    companion object {
        private const val TAG = "GrantSelfHeal"
        const val ATTEMPTS = 6
        const val RETRY_MS = 5_000L
        const val HISTORY_SIZE = 16

        private val historyLock = Any()
        private val historyRing = ArrayDeque<Event>(HISTORY_SIZE)

        /** Last [HISTORY_SIZE] runs across every grant instance, oldest first. */
        fun history(): List<Event> = synchronized(historyLock) { historyRing.toList() }

        @VisibleForTesting
        fun clearHistory() {
            synchronized(historyLock) { historyRing.clear() }
        }

        private fun record(
            name: String,
            reason: String,
            tries: Int,
            reasserts: List<Boolean>,
            granted: Boolean,
        ) {
            val event = Event(
                ts = System.currentTimeMillis(),
                name = name,
                reason = reason,
                tries = tries,
                reasserts = reasserts.toList(),
                granted = granted,
            )
            synchronized(historyLock) {
                if (historyRing.size >= HISTORY_SIZE) historyRing.removeFirst()
                historyRing.addLast(event)
            }
        }
    }
}
