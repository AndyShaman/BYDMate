package com.bydmate.app.data.vehicle

import android.content.Context
import android.os.Build
import android.util.Log
import com.bydmate.app.data.autoservice.AdbOnDeviceClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single owner of the helper daemon's lifecycle. Idempotent — ensureRunning()
 * converges to "one alive helper of THIS app version".
 *
 * Version awareness (the stale-daemon fix): the daemon is a detached app_process
 * under shell uid that survives app reinstalls and reboots-less updates. A daemon
 * spawned by a previous app version still answers the binder ping but lacks any
 * handler added in the update — so newer ops (sentry mode, assistant disable)
 * silently failed until a head-unit reboot. The authoritative staleness signal is
 * a live query to the daemon itself (TX_GET_VERSION): the daemon's CLASSPATH is
 * frozen at spawn time, so BuildConfig.VERSION_CODE in the running JVM reflects
 * exactly which APK spawned it. A pref-based bookkeeping value can drift (e.g.
 * daemon re-spawned by a boot path after the pref was already updated); the live
 * query cannot.
 */
@Singleton
class HelperBootstrap @Inject constructor(
    private val adb: AdbOnDeviceClient,
    private val helper: HelperClient,
    @ApplicationContext private val context: Context,
) {
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    // Serializes ensureRunning(): TrackingService start, the foreground refresh, and the
    // cluster star-control path all call it independently. Without this, two callers race
    // into the kill→spawn path and fight over the daemon's lock + binder name — the very
    // stale-daemon hazard this class exists to prevent.
    private val lock = Mutex()

    /**
     * Returns true if a helper daemon matching the current app version is reachable
     * after this call. Serialized: concurrent callers run one at a time.
     */
    suspend fun ensureRunning(): Boolean = lock.withLock { ensureRunningLocked() }

    /**
     * Order of operations:
     *   1. Ask the live daemon which versionCode it was built from (TX_GET_VERSION). The daemon's
     *      CLASSPATH is frozen at spawn time, so this reply is authoritative. If it matches the
     *      installed versionCode, the daemon is current — reuse it.
     *   2. Otherwise the daemon is stale (wrong version, too old for TX_GET_VERSION, or dead).
     *      Kill when either the binder ping succeeds (isAlive) OR the ps-level check shows a
     *      process still holding the lock (helperHeartbeat). The binder check alone is
     *      insufficient: after an uninstall+reinstall the app uid changes, and the daemon's
     *      UID gate rejects TX_PING, so isAlive()=false while the process is very much alive.
     *   3. Spawn via app_process; poll daemonVersion() up to [POLL_ATTEMPTS] times — this
     *      simultaneously confirms liveness AND that the fresh daemon carries the right version.
     *      A spawn that answers with the wrong version is treated as a failure (return false).
     *   4. Persist the versionCode (secondary bookkeeping) ONLY after the version poll succeeds.
     *      A failed kill or spawn dispatch bails without persisting.
     */
    private suspend fun ensureRunningLocked(): Boolean {
        val want = installedVersionCode()
        // If our own versionCode cannot be read (should never happen), degrade gracefully:
        // trust the binder ping rather than hammering the daemon with kill+spawn on every call.
        if (want == VERSION_READ_FAILED) return helper.isAlive()
        // Live query is authoritative: daemon's CLASSPATH is frozen at spawn time, so
        // VERSION_CODE in the running JVM reflects which APK it was spawned from.
        if (helper.daemonVersion() == want) return true
        // Daemon is stale (wrong version, too old for TX_GET_VERSION) or dead.
        // Kill when either the binder ping responds (isAlive) OR the process is visible in ps
        // (helperHeartbeat): a process holding the file lock must be killed before we spawn even
        // if its UID gate has gone mute (uid change after reinstall makes every transact fail).
        if (helper.isAlive() || adb.helperHeartbeat()) {
            // If the kill could not even be dispatched (no ADB connection / exec threw), we have
            // no evidence the stale daemon is gone. Bail rather than spawn over a possibly-live
            // old daemon; the next call retries.
            if (!adb.killHelper()) {
                Log.w(TAG, "killHelper dispatch failed; not spawning to avoid stale daemon")
                return false
            }
            // kill -9 is async, and the old daemon holds the exclusive lock + binder name until
            // it actually dies. The fresh daemon uses a NON-blocking tryLock() and exits cleanly
            // if the lock is still held — which would leave the STALE daemon registered, so
            // daemonVersion() below would falsely confirm the new version. Wait (bounded) for the
            // old process to disappear before spawning.
            var stillAlive = adb.helperHeartbeat()
            var attempts = 0
            var killRounds = 0
            while (stillAlive && killRounds < KILL_ROUNDS) {
                attempts = 0
                while (stillAlive && attempts < KILL_CONFIRM_ATTEMPTS) {
                    delay(KILL_CONFIRM_INTERVAL_MS)
                    attempts++
                    stillAlive = adb.helperHeartbeat()
                }
                killRounds++
                // The first kill can be lost on a stale ADB socket (field incident 2026-07-05:
                // 26 consecutive bails over 2 minutes). One re-dispatched kill per ensureRunning
                // call is cheap and bounded; more rounds would just stack latency on a daemon
                // that genuinely refuses to die.
                if (stillAlive && killRounds < KILL_ROUNDS && !adb.killHelper()) break
            }
            // If the stale daemon refuses to die, do NOT spawn over it: the fresh daemon would
            // lose the lock race and exit, and daemonVersion() would then reflect the OLD daemon
            // and wrongly confirm a fresh version. Bail so the next ensureRunning() retries the
            // kill instead of silently accepting a stale daemon.
            if (stillAlive) {
                Log.w(TAG, "stale helper still alive after kill; not spawning to avoid lock race")
                return false
            }
        }
        // If the spawn dispatch itself failed, do NOT fall through to the poll: a stale daemon (or
        // any leftover process) answering daemonVersion() would otherwise get the current
        // versionCode persisted against it, masking that no fresh daemon was actually launched.
        if (!adb.spawnHelper()) {
            Log.w(TAG, "spawnHelper dispatch failed; not persisting version")
            return false
        }
        repeat(POLL_ATTEMPTS) {
            delay(POLL_INTERVAL_MS)
            // daemonVersion() == want confirms both liveness and that the fresh daemon carries
            // the right handlers. A spawn that answers a wrong version is treated as failure.
            if (helper.daemonVersion() == want) {
                prefs.edit().putLong(KEY_SPAWNED_VERSION, want).apply()
                return true
            }
        }
        val log = adb.readHelperLog()
        Log.w(TAG, "helper unreachable after spawn; daemon log:\n$log")
        recordSpawnFailure(log)
        return false
    }

    /** Cheap reachability check — no side effects. */
    suspend fun isHealthy(): Boolean = helper.isAlive()

    /**
     * Keeps the tail of the daemon log from the last "spawned but unreachable" round, so the
     * diagnostic dump can show WHY the daemon never came up (#64). Logcat has usually rotated
     * by the time the user files the report.
     */
    private fun recordSpawnFailure(log: String?) {
        val tail = log?.lines()?.filter { it.isNotBlank() }?.takeLast(FAIL_LOG_LINES)
            ?.joinToString("\n")
            ?.takeIf { it.isNotEmpty() }
            ?: "(daemon log unavailable)"
        prefs.edit()
            .putLong(KEY_LAST_FAIL_TS, System.currentTimeMillis())
            .putString(KEY_LAST_FAIL_LOG, tail)
            .apply()
    }

    /** Last recorded spawn failure, or null if the daemon has never failed to come up. */
    fun lastSpawnFailure(): SpawnFailure? {
        val ts = prefs.getLong(KEY_LAST_FAIL_TS, 0L)
        if (ts <= 0L) return null
        return SpawnFailure(ts, prefs.getString(KEY_LAST_FAIL_LOG, "").orEmpty())
    }

    /** Timestamp + daemon log tail of a failed spawn. */
    data class SpawnFailure(val ts: Long, val logTail: String)

    /** Installed versionCode of our own package; a distinct sentinel if it cannot be read
     *  (never expected — our own package always exists). The sentinel differs from the stored
     *  default so a read failure can never be mistaken for "same version, reuse". */
    private fun installedVersionCode(): Long = runCatching {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode
        else @Suppress("DEPRECATION") pi.versionCode.toLong()
    }.getOrDefault(VERSION_READ_FAILED)

    companion object {
        private const val TAG = "HelperBootstrap"
        private const val PREFS = "helper"
        private const val KEY_SPAWNED_VERSION = "spawned_version_code"
        private const val KEY_LAST_FAIL_TS = "helper_last_fail_ts"
        private const val KEY_LAST_FAIL_LOG = "helper_last_fail_log"
        private const val FAIL_LOG_LINES = 10
        private const val VERSION_READ_FAILED = Long.MIN_VALUE
        private const val POLL_ATTEMPTS = 15
        private const val POLL_INTERVAL_MS = 200L
        // Bounded wait for the killed daemon to actually exit before respawning.
        private const val KILL_CONFIRM_ATTEMPTS = 10
        private const val KILL_CONFIRM_INTERVAL_MS = 100L
        // Kill-then-wait rounds before giving up on a stale daemon (the initial kill counts as
        // round 1; one extra kill is re-dispatched before round 2 if it is still alive).
        private const val KILL_ROUNDS = 2
    }
}
