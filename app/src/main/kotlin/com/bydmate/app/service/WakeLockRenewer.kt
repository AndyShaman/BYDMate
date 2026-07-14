package com.bydmate.app.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps a timeout-guarded PARTIAL_WAKE_LOCK alive for as long as the tracking
 * service runs (AC-10): a single acquire(30 min) silently expires on longer trips.
 * Re-acquiring a non-reference-counted lock resets its timeout, so the CPU
 * guarantee survives trips of any length while the timeout still protects
 * against a leak if the service dies without release().
 */
class WakeLockRenewer(
    private val scope: CoroutineScope,
    private val acquire: () -> Unit,
    private val renewIntervalMs: Long = RENEW_INTERVAL_MS,
) {
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                acquire()
                delay(renewIntervalMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        /** Refresh every 20 min against a 30-min timeout — one missed beat is survivable. */
        const val RENEW_INTERVAL_MS = 20 * 60 * 1000L
        const val TIMEOUT_MS = 30 * 60 * 1000L
    }
}
