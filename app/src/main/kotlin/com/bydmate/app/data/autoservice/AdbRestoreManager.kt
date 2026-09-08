package com.bydmate.app.data.autoservice

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

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
) {

    private val _state = MutableStateFlow<AdbRestoreState>(AdbRestoreState.Disabled)
    val state: StateFlow<AdbRestoreState> = _state.asStateFlow()

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

    /** True while the toggle is on. */
    fun isEnabled(): Boolean = prefs.isEnabled()

    /**
     * Persists the toggle. Turning it off also switches wireless debugging back off, so the
     * feature leaves nothing enabled behind it (only possible while the permission is granted).
     */
    fun setEnabled(enabled: Boolean) {
        prefs.setEnabled(enabled)
        if (!enabled) {
            // Invalidate any attempt in flight BEFORE the write, so it cannot squeeze its own
            // enable-write in between and leave wireless debugging on.
            generation.incrementAndGet()
            if (system.hasWriteSecureSettings()) system.writeAdbWifiEnabled(0)
            transition(AdbRestoreState.Disabled, "toggle off")
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
        if (!mutex.tryLock()) {
            rerunRequested.set(true)
            Log.d(TAG, "attempt already in flight, coalescing trigger=$trigger")
            return
        }
        try {
            // Ignore a request left over from before this lock was taken: this very call is it.
            rerunRequested.set(false)
            var again = true
            while (again) {
                val gen = generation.get()
                Log.i(TAG, "attempt start trigger=$trigger gen=$gen")
                try {
                    attemptLocked(gen)
                } catch (e: Exception) {
                    transitionIfCurrent(
                        gen, AdbRestoreState.Failed(e.message ?: e.javaClass.simpleName), "exception")
                }
                // Only triggers that arrived DURING the attempt above rerun it, and the flag is
                // cleared as it is read, so a quiet system ends the loop after one pass.
                again = rerunRequested.compareAndSet(true, false)
                if (again) Log.d(TAG, "rerunning for a trigger that arrived mid-attempt")
            }
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun attemptLocked(gen: Int) {
        if (!prefs.isEnabled()) {
            transition(AdbRestoreState.Disabled, "toggle off")
            return
        }
        if (system.classicConnect()) {
            if (abandoned(gen)) return
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

        if (!enableWirelessDebugging(gen, network)) return

        transition(AdbRestoreState.Connecting, "discovering tls port")
        val port = system.discoverTlsPort(DISCOVERY_TIMEOUT_MS)
        if (abandonedAfterEnable(gen)) return
        if (port == null) {
            transition(AdbRestoreState.Failed("mDNS timeout"), "no tls service in ${DISCOVERY_TIMEOUT_MS / 1000}s")
            return
        }

        val answer = system.restartTcpip(port)
        // A tcpip:5555 adbd has already executed cannot be taken back, and we deliberately do not
        // compensate by closing the port: an open classic port is the normal state on every older
        // firmware, it is harmless, and closing it would kill the user's own ADB session.
        if (abandonedAfterEnable(gen)) return
        if (answer == null || !answer.contains(TCPIP_OK_MARKER)) {
            transition(AdbRestoreState.Failed("tcpip refused"), "answer=${answer?.take(120)}")
            return
        }

        // adbd restarts its listener asynchronously — the port is not up the moment it answers.
        for (attempt in 1..CLASSIC_RETRIES) {
            system.sleep(CLASSIC_RETRY_DELAY_MS)
            if (abandonedAfterEnable(gen)) return
            if (system.classicConnect()) {
                if (abandonedAfterEnable(gen)) return
                transition(AdbRestoreState.Restored(system.nowMs()), "port 5555 back after $attempt check(s)")
                runCatching { system.ensureHelperRunning() }
                    .onSuccess { Log.i(TAG, "helper after restore: $it") }
                    .onFailure { Log.w(TAG, "helper bootstrap after restore failed: ${it.message}") }
                return
            }
        }
        transition(AdbRestoreState.Failed("port 5555 did not come up"), "after $CLASSIC_RETRIES checks")
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
     * Re-writing on every trigger would spawn a dialog every time, so one write per network per
     * cooldown window is all we do. Returns true when wireless debugging is on.
     */
    private suspend fun enableWirelessDebugging(gen: Int, network: String): Boolean {
        if (system.readAdbWifiEnabled() == 1) return true

        val now = system.nowMs()
        val sameNetwork = prefs.lastWriteNetwork() == network
        val withinCooldown = now - prefs.lastWriteAtMs() < WRITE_COOLDOWN_MS
        if (sameNetwork && withinCooldown) {
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
        const val CLASSIC_RETRIES = 5
        const val CLASSIC_RETRY_DELAY_MS = 2_000L

        private const val TCPIP_OK_MARKER = "restarting in TCP mode port: 5555"
    }
}
