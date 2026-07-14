package com.bydmate.app.service

import android.util.Log
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
        repeat(attempts) { attempt ->
            if (runCatching { isGranted() }.getOrDefault(false)) {
                if (attempt > 0) Log.i(TAG, "$name confirmed granted ($reason, try ${attempt + 1})")
                return
            }
            val ok = runCatching { reassert() }.getOrDefault(false)
            Log.i(TAG, "$name not granted; re-asserted ($reason, try ${attempt + 1}) -> $ok")
            delay(retryDelayMs)
        }
        if (!runCatching { isGranted() }.getOrDefault(false)) {
            Log.w(TAG, "$name still not granted after $attempts tries ($reason)")
        }
    }

    companion object {
        private const val TAG = "GrantSelfHeal"
        const val ATTEMPTS = 6
        const val RETRY_MS = 5_000L
    }
}
