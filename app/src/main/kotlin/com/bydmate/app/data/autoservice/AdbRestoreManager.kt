package com.bydmate.app.data.autoservice

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

/** The manager's own long-lived scope: retries must outlive the screen that started them. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AdbRestoreScope

/**
 * Brings the classic ADB port back after a reboot on firmwares (DiLink builds from mid-2026,
 * «2606») where BYD dropped the init hook that used to keep port 5555 open. Without it the
 * helper daemon never starts and every write feature of the app is dead until the user
 * re-enables ADB by hand.
 *
 * The path is Android's own wireless debugging: switch `adb_wifi_enabled` on (needs
 * WRITE_SECURE_SETTINGS, self-granted earlier over the still-alive classic channel), find the
 * `_adb-tls-connect._tcp` port over mDNS, connect to it with the same RSA key the daemon
 * already trusts (it is in `/data/misc/adb/adb_keys`, so no pairing code), and ask the daemon
 * for `tcpip:5555`.
 *
 * Two facts the user cannot be spared: Android refuses to enable wireless debugging without a
 * Wi-Fi connection, and the first enable on each network raises a system dialog that only the
 * user can confirm. Both are surfaced as states, never worked around.
 */
@Singleton
class AdbRestoreManager @Inject constructor(
    private val prefs: AdbRestorePreferences,
    private val system: AdbRestoreSystem,
    @AdbRestoreScope private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow<AdbRestoreState>(AdbRestoreState.Disabled)
    val state: StateFlow<AdbRestoreState> = _state.asStateFlow()

    /** Label of the last trigger that ran an attempt, and how many retries it has cost — for the dump. */
    @Volatile
    var lastTrigger: String = "none"
        private set

    @Volatile
    var retryCount: Int = 0
        private set

    // The retry wave. NeedsDialog is the one verdict the user can lift without touching the app
    // (the system dialog is waiting), so it is the one we keep re-checking; every other verdict is
    // either terminal or has its own event to wake us.
    private var retryJob: Job? = null

    // Attempts are serialized: mDNS discovery alone holds the lock for up to 45 s.
    private val mutex = Mutex()

    // A trigger that arrives while an attempt is running is coalesced into one rerun instead of
    // being dropped. Dropping it lost the trigger that matters most: the one right after the
    // user switched the feature back on, whose generation the running attempt cannot serve.
    private val rerunRequested = AtomicBoolean(false)

    // Bumped whenever the user switches the feature off. An attempt spends most of its life
    // suspended (discovery alone runs up to 45 s), so a running attempt has to notice that the
    // toggle went off under it — otherwise it would re-enable wireless debugging, run tcpip and
    // overwrite Disabled, right after the user asked for the opposite.
    private val generation = AtomicInteger(0)

    // True once THIS locked attempt's own write to adb_wifi_enabled got reverted by the system —
    // never when a write was merely suppressed by the cooldown. Reset once per call to runAttempt,
    // not per pass of its while loop: a coalesced rerun that lands on a suppressed write must not
    // erase the real revert an earlier pass of the same attempt already saw — nothing changed since
    // the wave that verdict would start, so afterAttempt still has to see it after the loop ends.
    private var writeWasReverted = false

    // Set once a wave spends its whole budget without the dialog being confirmed, to the network
    // it was fought on. While the current network matches, passive triggers (watchdog, service
    // start) may still send their own cooldown-gated write, but must not start a new wave over
    // it — that is what let the dialog keep nagging for the rest of the trip.
    private var exhaustedNetwork: String? = null

    /** True while the toggle is on. */
    fun isEnabled(): Boolean = prefs.isEnabled()

    /**
     * Persists the toggle. Turning it off also switches wireless debugging back off, so the
     * feature leaves nothing enabled behind it (only possible while the permission is granted).
     */
    fun setEnabled(enabled: Boolean) {
        prefs.setEnabled(enabled)
        if (!enabled) {
            stopRetryWave("toggle off")
            // Invalidate any attempt in flight BEFORE the write, so it cannot squeeze its own
            // enable-write in between and leave wireless debugging on.
            generation.incrementAndGet()
            if (system.hasWriteSecureSettings()) system.writeAdbWifiEnabled(0)
            transition(AdbRestoreState.Disabled, "toggle off")
        } else {
            // A fresh "on" is the user asking us to try again on whatever network they are on now.
            exhaustedNetwork = null
        }
    }

    /**
     * Runs one restore attempt if the situation calls for one. Safe to call from any trigger
     * (service start, Wi-Fi appearing, helper watchdog); a call that arrives while an attempt is
     * running is coalesced into a single rerun once that attempt finishes.
     *
     * [trigger] is a short label naming the call site; it lands in the log so a dump shows which
     * event set the restore going.
     */
    suspend fun attemptIfNeeded(trigger: String = TRIGGER_UNKNOWN) {
        // afterAttempt runs inside runAttempt now, while the lock from the attempt it is judging
        // is still held — see the comment on that call for why.
        runAttempt(trigger)
    }

    /**
     * [attemptIfNeeded] for callers that have no scope of their own to run it in — the Settings
     * screen, whose composition ends the moment the user leaves it, taking the helper bootstrap
     * at the end of a successful restore down with it.
     */
    fun requestAttempt(trigger: String) {
        scope.launch {
            runCatching { attemptIfNeeded(trigger) }
                .onFailure { Log.w(TAG, "attempt from $trigger failed: ${it.message}") }
        }
    }

    /** Runs an attempt under the lock; false when the call was coalesced into a running one. */
    private suspend fun runAttempt(trigger: String, isRetry: Boolean = false): Boolean {
        if (!mutex.tryLock()) {
            rerunRequested.set(true)
            Log.d(TAG, "attempt already in flight, coalescing trigger=$trigger")
            return false
        }
        // Set when a rerun request arrives while we are dying from cancellation: the while loop
        // below never gets to re-check it once we rethrow, so it is handed to a fresh coroutine
        // in `finally`, after the mutex it would need is actually free.
        var handOffRerun = false
        try {
            // Ignore a request left over from before this lock was taken: this very call is it.
            rerunRequested.set(false)
            // Reset once for the whole locked attempt, not per pass: a coalesced rerun's own pass
            // (e.g. hitting the write cooldown) must not erase the true verdict an earlier pass
            // already produced this same attempt — afterAttempt reads it once, after the loop ends.
            writeWasReverted = false
            var again = true
            while (again) {
                val gen = generation.get()
                lastTrigger = trigger
                Log.i(TAG, "attempt start trigger=$trigger gen=$gen")
                try {
                    // An explicit "settings" trigger is the user opening the screen to confirm the
                    // dialog right now — the write cooldown exists for triggers nobody is watching
                    // for, and must not make them wait out a wave's own cooldown too.
                    attemptLocked(gen, isRetry, force = trigger == "settings")
                } catch (e: CancellationException) {
                    // Switching the toggle off cancels the retry wave, which kills this coroutine
                    // at its next sleep — possibly the settle wait right after our own enable-write.
                    // The attempt is over, but the setting it changed is still ours to undo, and the
                    // rollback is plain non-suspending code, so it runs even on a dead coroutine.
                    abandonedAfterEnable(gen)
                    if (rerunRequested.compareAndSet(true, false)) handOffRerun = true
                    throw e
                } catch (e: Exception) {
                    transitionIfCurrent(
                        gen, AdbRestoreState.Failed(e.message ?: e.javaClass.simpleName), "exception")
                }
                // Only triggers that arrived DURING the attempt above rerun it, and the flag is
                // cleared as it is read, so a quiet system ends the loop after one pass.
                again = rerunRequested.compareAndSet(true, false)
                if (again) Log.d(TAG, "rerunning for a trigger that arrived mid-attempt")
            }
            // Judge the verdict while the mutex from THIS attempt is still held: releasing it
            // first would let another trigger's own attempt run and reset writeWasReverted (or
            // start its own suppressed write) before this verdict got read, silencing a wave that
            // a real revert should have started. Skipped for a retry pass — the wave loop that
            // launched it decides for itself whether to continue, and calling stopRetryWave from
            // here would cancel the very coroutine currently running it. Also skipped when the
            // loop above threw (the throw jumps straight past this to `finally`): a cancelled
            // attempt has nothing to judge, setEnabled(false) already published Disabled.
            if (!isRetry) afterAttempt(trigger)
        } finally {
            mutex.unlock()
            // This coroutine is on its way out from the CancellationException above; the request
            // it is carrying runs in the manager's own scope, which the cancellation did not touch.
            if (handOffRerun) requestAttempt("rerun after cancel")
        }
        return true
    }

    /**
     * Keeps the retry wave in step with the verdict the attempt just produced: NeedsDialog means
     * the user has not confirmed the system dialog yet, and the confirmation produces no event we
     * can subscribe to — polling for it is the only way. Every other verdict ends the wave.
     */
    @Synchronized
    private fun afterAttempt(trigger: String) {
        val state = _state.value
        // The user explicitly opened settings: whatever a past wave gave up on, this is a new
        // attempt at their own request and deserves a real shot, not a memory of last time.
        if (trigger == "settings") exhaustedNetwork = null
        if (state === AdbRestoreState.NeedsDialog) {
            // A cooldown-suppressed write changed nothing — same dialog state as before this
            // call, so it must not read as a fresh reason to start polling.
            if (!writeWasReverted) return
            val network = system.wifiNetwork()
            if (network != null && network == exhaustedNetwork) {
                Log.i(TAG, "retry wave skipped, budget spent on $network")
                return
            }
            startRetryWave(trigger)
        } else {
            if (state is AdbRestoreState.Restored || state === AdbRestoreState.NotNeeded) {
                exhaustedNetwork = null
            }
            // WaitingWifi and Failed are transient: a radio blip under a passive trigger must not
            // kill the wave polling for the dialog the user may be looking at right now. Only the
            // verdicts that settle the dialog question end it.
            if (state !== AdbRestoreState.WaitingWifi && state !is AdbRestoreState.Failed) {
                stopRetryWave(state.toString())
            }
        }
    }

    /**
     * Starts the 2 s / 5 s / 10 s / 15 s … wave, bounded by [RETRY_BUDGET_MS] so a car parked all
     * day on a network the user will never approve does not keep raising the dialog. A wave already
     * in flight is left alone — the triggers that arrive during one are exactly what it is for.
     *
     * Called from [afterAttempt] while the attempt's own mutex is still held: `scope.launch` only
     * schedules the wave's coroutine, it does not run it inline, and the coroutine's first move
     * once it does run is a sleep, THEN a (non-blocking) `mutex.tryLock()` — so there is no attempt
     * to reacquire a lock this call site is holding, and no deadlock.
     */
    private fun startRetryWave(trigger: String) {
        if (retryJob?.isActive == true) return
        retryCount = 0
        retryJob = scope.launch {
            val deadline = system.nowMs() + RETRY_BUDGET_MS
            var index = 0
            while (true) {
                val waitMs = RETRY_DELAYS_MS.getOrElse(index) { RETRY_DELAYS_MS.last() }
                if (system.nowMs() + waitMs > deadline) {
                    exhaustedNetwork = prefs.lastWriteNetwork()
                    Log.i(TAG, "retry budget of ${RETRY_BUDGET_MS / 1000}s spent, waiting for a new trigger")
                    return@launch
                }
                index++
                retryCount = index
                Log.i(TAG, "retry #$index in ${waitMs / 1000}s (trigger=$trigger, reason=${_state.value})")
                system.sleep(waitMs)
                runAttempt("retry#$index", isRetry = true)
                if (_state.value !== AdbRestoreState.NeedsDialog) {
                    Log.i(TAG, "retry wave done after #$index, state=${_state.value}")
                    return@launch
                }
            }
        }
    }

    @Synchronized
    private fun stopRetryWave(reason: String) {
        val job = retryJob ?: return
        retryJob = null
        if (job.isActive) Log.i(TAG, "retry wave cancelled reason=$reason")
        job.cancel()
    }

    private suspend fun attemptLocked(gen: Int, isRetry: Boolean, force: Boolean) {
        if (!prefs.isEnabled()) {
            transition(AdbRestoreState.Disabled, "toggle off")
            return
        }
        if (system.classicConnect()) {
            if (abandoned(gen)) return
            // The classic port answering is the one moment a shell command can reach us: take the
            // permission now so the next reboot can be restored without it (idempotent, see TrackingService).
            if (!system.hasWriteSecureSettings()) {
                val granted = system.selfGrantWriteSecureSettings()
                Log.i(TAG, "self-grant WRITE_SECURE_SETTINGS over classic port: $granted")
            }
            transition(AdbRestoreState.NotNeeded, "classic port alive")
            return
        }
        if (abandoned(gen)) return
        if (!system.hasWriteSecureSettings()) {
            transition(AdbRestoreState.NeedsActivation, "no WRITE_SECURE_SETTINGS")
            return
        }
        val network = system.wifiNetwork()
        if (network == null) {
            transition(AdbRestoreState.WaitingWifi, "no wifi")
            return
        }

        if (!enableWirelessDebugging(gen, network, isRetry, force)) return

        transition(AdbRestoreState.Connecting, "discovering tls port")

        // adbd publishes its own TLS port; when it is there, mDNS discovery (up to 45 s) is pure
        // waiting. A stale or wrong value costs one short poll and then falls through to mDNS.
        val propPort = system.tlsPortFromProperty()?.takeIf { it > 0 }
        if (propPort != null) {
            Log.i(TAG, "tls port from prop=$propPort")
            when (runTcpip(gen, propPort, TCPIP_POLL_TIMEOUT_MS)) {
                TcpipOutcome.RESTORED, TcpipOutcome.ABANDONED -> return
                TcpipOutcome.FAILED ->
                    Log.i(TAG, "prop port $propPort did not bring 5555 back, falling back to mDNS")
            }
        }

        val port = system.discoverTlsPort(DISCOVERY_TIMEOUT_MS)
        if (abandonedAfterEnable(gen)) return
        if (port == null) {
            // A tcpip we already sent to the property port can land after its poll window closed,
            // so the port is checked once more before anything is called a failure — reporting
            // Failed with 5555 answering would also skip the helper bootstrap.
            if (system.classicConnect()) {
                if (abandonedAfterEnable(gen)) return
                markRestored("port 5555 alive despite mDNS timeout")
                return
            }
            transition(AdbRestoreState.Failed("mDNS timeout"), "no tls service in ${DISCOVERY_TIMEOUT_MS / 1000}s")
            return
        }

        when (runTcpip(gen, port, TCPIP_POLL_TIMEOUT_MS)) {
            TcpipOutcome.RESTORED, TcpipOutcome.ABANDONED -> return
            TcpipOutcome.FAILED ->
                transition(AdbRestoreState.Failed("classic port silent after tcpip"), "tls port $port")
        }
    }

    private enum class TcpipOutcome { RESTORED, ABANDONED, FAILED }

    /**
     * Asks the daemon on [port] for `tcpip:5555` and waits up to [pollTimeoutMs] for the classic
     * port to answer. Publishes Restored (and starts the helper) on success; the caller decides
     * what a failure means, since a failed first candidate is not yet a failed attempt.
     */
    private suspend fun runTcpip(gen: Int, port: Int, pollTimeoutMs: Long): TcpipOutcome {
        val answer = system.restartTcpip(port)
        // A tcpip:5555 adbd has already executed cannot be taken back, and we deliberately do not
        // compensate by closing the port: an open classic port is the normal state on every older
        // firmware, it is harmless, and closing it would kill the user's own ADB session.
        if (abandonedAfterEnable(gen)) return TcpipOutcome.ABANDONED
        if (answer == null) {
            // On 2606+ firmwares adbd restarts into TCP mode before flushing the tcpip reply, so
            // the TLS socket sees EOF even though the restore is about to succeed. A null answer
            // is therefore not a verdict by itself — only the classic port below is.
            Log.i(TAG, "tcpip answer=null (EOF); polling classic port up to ${pollTimeoutMs / 1000}s")
        }

        // adbd restarts its listener asynchronously — the port is not up the moment tcpip answers.
        // Any outcome of the tcpip call above (the normal restart message, a null/EOF answer) is
        // handled identically: only the classic port coming alive is a success.
        val pollAttempts = (pollTimeoutMs / TCPIP_POLL_INTERVAL_MS).toInt()
        for (attempt in 1..pollAttempts) {
            system.sleep(TCPIP_POLL_INTERVAL_MS)
            if (abandonedAfterEnable(gen)) return TcpipOutcome.ABANDONED
            if (system.classicConnect()) {
                if (abandonedAfterEnable(gen)) return TcpipOutcome.ABANDONED
                markRestored("port 5555 back after $attempt check(s)")
                return TcpipOutcome.RESTORED
            }
        }
        Log.i(TAG, "port 5555 silent after tcpip on $port (answer=${answer?.take(120)})")
        return TcpipOutcome.FAILED
    }

    /** Publishes the success state and brings the helper daemon up behind it. */
    private suspend fun markRestored(reason: String) {
        transition(AdbRestoreState.Restored(system.nowMs()), reason)
        runCatching { system.ensureHelperRunning() }
            .onSuccess { Log.i(TAG, "helper after restore: $it") }
            .onFailure {
                // A cancellation here (toggle off while the helper bootstrap is in flight) must
                // keep unwinding into runAttempt's own catch, or a rerun requested right after
                // gets coalesced into this already-cancelled coroutine and silently dropped: it
                // sets rerunRequested, but nothing left to read it ever reaches that check again.
                if (it is CancellationException) throw it
                Log.w(TAG, "helper bootstrap after restore failed: ${it.message}")
            }
    }

    /**
     * True once the attempt that started at [gen] no longer speaks for the user: the toggle was
     * switched off (or off and on again) while it was suspended. The caller must return without
     * any further side effect and without touching the state — [setEnabled] already published
     * Disabled, and a later attempt owns whatever comes next.
     */
    private fun abandoned(gen: Int): Boolean {
        if (generation.get() == gen && prefs.isEnabled()) return false
        Log.i(TAG, "attempt abandoned reason=toggle changed mid-attempt")
        return true
    }

    /**
     * [abandoned] for the part of the attempt that runs after wireless debugging was switched on.
     * The toggle can go off in the window between the last check and the write itself, and then
     * the switch-off write lands first — so an abandoned attempt undoes its own enable-write
     * rather than leaving wireless debugging on under a switch the user turned off.
     */
    private fun abandonedAfterEnable(gen: Int): Boolean {
        if (!abandoned(gen)) return false
        if (system.hasWriteSecureSettings()) {
            system.writeAdbWifiEnabled(0)
            Log.i(TAG, "rolled adb_wifi_enabled back to 0 after abandonment")
        }
        return true
    }

    /**
     * Writes `adb_wifi_enabled=1` and verifies it stuck. The system silently reverts the write
     * within a few hundred ms while the "Allow wireless debugging on this network?" dialog is
     * unconfirmed — that revert is the only signal that the dialog is waiting.
     *
     * Re-writing on every trigger would spawn a dialog every time, so writes on one network are
     * spaced out: closely inside a retry wave (bounded to five minutes, with the user expected to
     * be looking at the dialog right now), far apart for any other trigger, so a stray event hours
     * later cannot start nagging. [force] lifts the cooldown for the one trigger that is the user
     * themselves acting right now (opening Settings) — nobody is nagged by a dialog they asked for.
     * Returns true when wireless debugging is on.
     */
    private suspend fun enableWirelessDebugging(
        gen: Int,
        network: String,
        isRetry: Boolean,
        force: Boolean,
    ): Boolean {
        if (system.readAdbWifiEnabled() == 1) return true

        val now = system.nowMs()
        val interval = if (isRetry) WRITE_RETRY_INTERVAL_MS else WRITE_COOLDOWN_MS
        val sameNetwork = prefs.lastWriteNetwork() == network
        val withinCooldown = now - prefs.lastWriteAtMs() < interval
        if (!force && sameNetwork && withinCooldown) {
            transition(AdbRestoreState.NeedsDialog, "write suppressed, cooldown for $network")
            return false
        }

        // Last check before the one side effect the user just asked us not to perform.
        if (abandoned(gen)) return false
        prefs.recordWrite(network, now)
        if (!system.writeAdbWifiEnabled(1)) {
            transition(AdbRestoreState.Failed("cannot enable wireless debugging"), "settings write refused")
            return false
        }
        system.sleep(SETTINGS_SETTLE_MS)
        if (abandonedAfterEnable(gen)) return false
        if (system.readAdbWifiEnabled() != 1) {
            writeWasReverted = true
            transition(AdbRestoreState.NeedsDialog, "setting reverted by system")
            return false
        }
        return true
    }

    private fun transition(next: AdbRestoreState, reason: String) {
        _state.value = next
        Log.i(TAG, "$next reason=$reason")
    }

    /** [transition], unless the attempt at [gen] has been abandoned meanwhile. */
    private fun transitionIfCurrent(gen: Int, next: AdbRestoreState, reason: String) {
        if (abandoned(gen)) return
        transition(next, reason)
    }

    companion object {
        private const val TAG = "AdbRestore"

        /** Fallback trigger label — production call sites all pass their own. */
        const val TRIGGER_UNKNOWN = "unknown"

        /** How long the system takes to revert an unconfirmed enable, with margin. */
        const val SETTINGS_SETTLE_MS = 1_500L
        const val DISCOVERY_TIMEOUT_MS = 45_000L
        const val WRITE_COOLDOWN_MS = 10 * 60 * 1000L

        /** Shortest gap between two enable-writes on one network inside a retry wave. */
        const val WRITE_RETRY_INTERVAL_MS = 15_000L
        const val TCPIP_POLL_TIMEOUT_MS = 15_000L
        const val TCPIP_POLL_INTERVAL_MS = 1_000L

        /** Retry cadence after a NeedsDialog verdict; the last value repeats. */
        val RETRY_DELAYS_MS = listOf(2_000L, 5_000L, 10_000L, 15_000L)

        /** A wave gives the user five minutes to confirm the dialog, then stops until a new trigger. */
        const val RETRY_BUDGET_MS = 5 * 60 * 1000L
    }
}
