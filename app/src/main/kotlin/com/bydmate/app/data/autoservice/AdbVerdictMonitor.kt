package com.bydmate.app.data.autoservice

import android.util.Log
import com.bydmate.app.data.vehicle.HelperBootstrap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of the ADB control-channel verdict shown in the Dashboard header and in
 * Settings. It only reads state other components already hold — [recompute] never opens a
 * connection; [recheck] is the one user-initiated path that tries to bring the channel up.
 */
@Singleton
class AdbVerdictMonitor @Inject constructor(
    private val helperBootstrap: HelperBootstrap,
    private val adbClient: AdbOnDeviceClient,
    private val adbRestoreManager: AdbRestoreManager,
    private val adbRestoreSystem: AdbRestoreSystem,
    @AdbRestoreScope private val scope: CoroutineScope,
) {
    private val _verdict = MutableStateFlow<AdbVerdict?>(null)
    /** Null = nothing to show: before the start gate, service stopped, or a restore in flight. */
    val verdict: StateFlow<AdbVerdict?> = _verdict.asStateFlow()

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    // Open once GATE_MS passed since the service started: until then the daemon is still
    // coming up and every verdict would be a false alarm.
    @Volatile private var gateOpen = false
    // Between onServiceStarted and onServiceStopped; nothing publishes a verdict outside it.
    @Volatile private var started = false
    private var gateJob: Job? = null
    private var restoreJob: Job? = null
    private val recheckRunning = AtomicBoolean(false)

    fun isRestoreEnabled(): Boolean = adbRestoreManager.isEnabled()

    @Synchronized
    fun onServiceStarted() {
        // A repeated start replaces the jobs of the previous one instead of stacking them.
        gateJob?.cancel()
        restoreJob?.cancel()
        gateOpen = false
        started = true
        gateJob = scope.launch {
            delay(GATE_MS)
            gateOpen = true
            recompute()
        }
        restoreJob = scope.launch { adbRestoreManager.state.collect { recompute() } }
    }

    @Synchronized
    fun onServiceStopped() {
        gateJob?.cancel()
        restoreJob?.cancel()
        gateJob = null
        restoreJob = null
        gateOpen = false
        started = false
        _verdict.value = null
    }

    /** Re-evaluates from current state; a no-op before the start gate. */
    suspend fun recompute() {
        if (gateOpen) publish()
    }

    /** User-initiated check: runs the restore attempt and the daemon bootstrap, then publishes. */
    fun recheck(trigger: String) {
        if (!recheckRunning.compareAndSet(false, true)) return
        _checking.value = true
        scope.launch {
            try {
                logFailure("restore attempt") { adbRestoreManager.attemptIfNeeded(trigger) }
                logFailure("ensureRunning") { helperBootstrap.ensureRunning() }
                publish()
            } finally {
                _checking.value = false
                recheckRunning.set(false)
            }
        }
    }

    fun enableRestoreAndRecheck() {
        adbRestoreManager.setEnabled(true)
        recheck(TRIGGER_DIALOG)
    }

    private suspend fun publish() {
        val healthy = safeRead(false) { helperBootstrap.isHealthy() }
        val connected = safeRead(false) { adbClient.isConnected() }
        val failure = safeRead(null) { adbClient.lastConnectFailure() }
        val restore = adbRestoreManager.state.value
        val wifi = safeRead(0) { adbRestoreSystem.readAdbWifiEnabled() }
        val everAlive = safeRead(false) { helperBootstrap.daemonEverAlive() }
        val next = evaluateAdbVerdict(healthy, connected, failure, restore, wifi, everAlive)
        // Same lock as onServiceStopped: a publish racing a stop cannot land after the reset.
        val previous = synchronized(this) {
            if (!started) return
            _verdict.getAndUpdate { next }
        }
        if (previous != next) {
            Log.i(TAG, "verdict=$next healthy=$healthy connected=$connected failure=$failure " +
                "restore=$restore wifi=$wifi everAlive=$everAlive")
        }
    }

    // Every platform read degrades to a safe default: a verdict must never crash the service.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> safeRead(default: T, read: suspend () -> T): T =
        try {
            read()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "read failed: ${e.message}")
            default
        }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun logFailure(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$what failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "AdbVerdict"
        const val GATE_MS = 15_000L
        private const val TRIGGER_DIALOG = "verdict_dialog"
    }
}
