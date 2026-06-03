package com.bydmate.app.data.vehicle

import android.util.Log
import com.bydmate.app.data.autoservice.AdbOnDeviceClient
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single owner of the helper daemon's lifecycle. Idempotent — ensureRunning()
 * converges to "one alive helper": if the binder ping already succeeds it returns
 * immediately, otherwise it spawns via app_process and polls the ping. Duplicate
 * spawns are harmless — the daemon takes an exclusive file lock and a second
 * instance exits cleanly.
 */
@Singleton
class HelperBootstrap @Inject constructor(
    private val adb: AdbOnDeviceClient,
    private val helper: HelperClient,
) {
    /**
     * Returns true if the helper binder service is reachable after this call.
     *
     * Order of operations:
     *   1. Ping the daemon via binder. If alive, return true immediately.
     *   2. Dispatch spawn via app_process (CLASSPATH = app's own base.apk).
     *   3. Poll the binder ping up to [POLL_ATTEMPTS] times at [POLL_INTERVAL_MS] ms.
     *   4. On exhausted poll, read the daemon log for diagnostics and return false.
     */
    suspend fun ensureRunning(): Boolean {
        if (helper.isAlive()) return true
        if (!adb.spawnHelper()) { Log.w(TAG, "spawnHelper dispatch failed") }
        repeat(POLL_ATTEMPTS) {
            delay(POLL_INTERVAL_MS)
            if (helper.isAlive()) return true
        }
        val log = adb.readHelperLog()
        Log.w(TAG, "helper unreachable after spawn; daemon log:\n$log")
        return false
    }

    /** Cheap reachability check — no side effects. */
    suspend fun isHealthy(): Boolean = helper.isAlive()

    companion object {
        private const val TAG = "HelperBootstrap"
        private const val POLL_ATTEMPTS = 15
        private const val POLL_INTERVAL_MS = 200L
    }
}
