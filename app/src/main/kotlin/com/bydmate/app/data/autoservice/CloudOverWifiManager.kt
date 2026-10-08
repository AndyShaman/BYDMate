package com.bydmate.app.data.autoservice

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/** The user's toggle plus the data profile we switched away from, so switching off can return it. */
interface CloudOverWifiPreferences {
    fun isEnabled(): Boolean
    fun setEnabled(enabled: Boolean)

    /** The car's own data profile from before our switch to `double_apn`; null when we changed nothing. */
    fun savedProfile(): String?

    fun setSavedProfile(profile: String?)
}

/** SharedPreferences-backed production implementation, file "cloud_over_wifi". */
class CloudOverWifiPreferencesImpl(context: Context) : CloudOverWifiPreferences {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    override fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    override fun savedProfile(): String? = prefs.getString(KEY_SAVED_PROFILE, null)

    // commit, not apply: the profile to return to must be on disk before the broadcast that
    // changes it goes out, or a process death in between leaves nothing to switch back to.
    override fun setSavedProfile(profile: String?) {
        prefs.edit().putString(KEY_SAVED_PROFILE, profile).commit()
    }

    companion object {
        const val PREFS_NAME = "cloud_over_wifi"
        const val KEY_ENABLED = "enabled"
        const val KEY_SAVED_PROFILE = "saved_profile"
    }
}

/** The platform calls [CloudOverWifiManager] makes outside the ADB shell. */
interface CloudOverWifiSystem {
    /** True when a Wi-Fi network with validated internet is up. */
    fun hasValidatedWifi(): Boolean

    suspend fun sleep(ms: Long)
}

/** Production wiring of [CloudOverWifiSystem] against the head unit. */
@Singleton
class AndroidCloudOverWifiSystem @Inject constructor(
    @ApplicationContext private val context: Context,
) : CloudOverWifiSystem {

    override fun hasValidatedWifi(): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@runCatching false
        cm.allNetworks.any { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@any false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    }.getOrDefault(false)

    override suspend fun sleep(ms: Long) = delay(ms)
}

/** What the cloud-over-Wi-Fi feature is doing; rendered as the status line under its toggle. */
sealed class CloudOverWifiState {
    /** Toggle is off and nothing is left to return — no status line. */
    data object Disabled : CloudOverWifiState()

    /** The on-device ADB shell does not answer, so nothing can be read or changed. */
    data object NoAdb : CloudOverWifiState()

    /** The car has its own cellular data: the cloud already runs the stock way, we touch nothing. */
    data object OwnCellular : CloudOverWifiState()

    /** No Wi-Fi with validated internet yet. */
    data object WaitingInternet : CloudOverWifiState()

    /** The car did not take the `double_apn` data profile. */
    data object ProfileRejected : CloudOverWifiState()

    /** We asked the cloud to connect and it did not. */
    data object CloudSilent : CloudOverWifiState()

    /** The BYD cloud link is up. */
    data object Connected : CloudOverWifiState()

    /** Toggle is off but the car is not confirmed back on the profile we switched away from yet. */
    data object ReturnPending : CloudOverWifiState()
}

/**
 * Brings the BYD cloud (master account: OTA, NFC key, remote control from the BYD phone app) up
 * over Wi-Fi on cars whose native Chinese SIM has no cellular link outside China (#310).
 *
 * The recipe is OpenWIFI 2.0's, run through our own on-device ADB shell: switch the data
 * profile to `double_apn` (the car keeps the cloud channel off its dead cellular APN), then ask
 * `cloudmanager` to connect. A car with a live cellular link is left completely alone. Switching
 * the toggle off returns the profile we changed, and only if we changed it.
 *
 * Triggers: the toggle, service start, and Wi-Fi with validated internet appearing (settled for
 * [WIFI_SETTLE_MS], repeated events coalesce). Concurrent attempts are coalesced into one rerun,
 * the same way as [AdbRestoreManager]. There is no periodic watchdog.
 */
@Suppress("TooManyFunctions") // the attempt steps, the return of the profile and the shell reads
@Singleton
class CloudOverWifiManager @Inject constructor(
    private val prefs: CloudOverWifiPreferences,
    private val adb: AdbOnDeviceClient,
    private val system: CloudOverWifiSystem,
    @AdbRestoreScope private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow<CloudOverWifiState>(CloudOverWifiState.Disabled)
    val state: StateFlow<CloudOverWifiState> = _state.asStateFlow()

    /** Label of the last trigger that ran an attempt — for the dump. */
    @Volatile
    var lastTrigger: String = "none"
        private set

    // Attempts are serialized: one holds the lock across the profile and cloud settle waits.
    private val mutex = Mutex()

    // A trigger arriving mid-attempt reruns it once instead of being dropped — above all the
    // toggle-off one, which has to return the profile the running attempt may just have changed.
    // Holds the label of the latest trigger not yet run.
    private val pendingTrigger = AtomicReference<String?>(null)

    // Test seam: runs right before the attempt lock is released.
    internal var beforeUnlockForTest: suspend () -> Unit = {}

    // The pending Wi-Fi attempt; a newer Wi-Fi event restarts its settle wait.
    private var wifiJob: Job? = null

    fun isEnabled(): Boolean = prefs.isEnabled()

    /** Persists the toggle. Switching off runs the return of whatever we changed. */
    fun setEnabled(enabled: Boolean) {
        prefs.setEnabled(enabled)
        Log.i(TAG, "toggle enabled=$enabled saved=${prefs.savedProfile()}")
        if (!enabled) {
            transition(CloudOverWifiState.Disabled, "toggle off")
            requestAttempt(TRIGGER_TOGGLE_OFF)
        }
    }

    /** [attemptIfNeeded] in the manager's own scope, for callers without a lasting one. */
    fun requestAttempt(trigger: String) {
        scope.launch {
            runCatching { attemptIfNeeded(trigger) }
                .onFailure {
                    if (it is CancellationException) throw it
                    Log.w(TAG, "attempt from $trigger failed: ${it.message}")
                }
        }
    }

    /**
     * Wi-Fi with validated internet appeared. The attempt waits [WIFI_SETTLE_MS] for the link to
     * settle; an event inside that window restarts the wait, so a flapping network costs one attempt.
     */
    @Synchronized
    fun onWifiValidated() {
        if (!prefs.isEnabled() && prefs.savedProfile() == null) return
        wifiJob?.cancel()
        wifiJob = scope.launch {
            system.sleep(WIFI_SETTLE_MS)
            runCatching { attemptIfNeeded(TRIGGER_WIFI) }
                .onFailure {
                    if (it is CancellationException) throw it
                    Log.w(TAG, "attempt from $TRIGGER_WIFI failed: ${it.message}")
                }
        }
    }

    /**
     * Runs one attempt; a call that arrives while one runs is coalesced into a single rerun.
     *
     * The request is published before the lock is tried, and the holder looks for one again after
     * releasing it, so a request landing between the holder's last check and its unlock is run.
     */
    suspend fun attemptIfNeeded(trigger: String) {
        pendingTrigger.set(trigger)
        var ran = false
        while (pendingTrigger.get() != null && mutex.tryLock()) {
            try {
                var next = pendingTrigger.getAndSet(null)
                while (next != null) {
                    runOnce(next, rerun = ran)
                    ran = true
                    next = pendingTrigger.getAndSet(null)
                }
            } finally {
                beforeUnlockForTest()
                mutex.unlock()
            }
        }
        if (!ran) Log.d(TAG, "attempt already in flight, coalescing trigger=$trigger")
    }

    @Suppress("TooGenericExceptionCaught") // a failed pass must not drop the rerun queued behind it
    private suspend fun runOnce(trigger: String, rerun: Boolean) {
        if (rerun) Log.d(TAG, "rerunning for a trigger that arrived mid-attempt")
        lastTrigger = trigger
        Log.i(TAG, "attempt start trigger=$trigger enabled=${prefs.isEnabled()}")
        try {
            attemptLocked()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "attempt failed: ${e.message}")
        }
    }

    private suspend fun attemptLocked() {
        if (!prefs.isEnabled()) {
            val saved = prefs.savedProfile()
            if (saved != null) restore(saved) else transition(CloudOverWifiState.Disabled, "toggle off")
            return
        }
        if (adb.connect().isFailure) {
            transition(CloudOverWifiState.NoAdb, "adb connect refused")
            return
        }
        val before = readProfile() ?: run {
            transition(CloudOverWifiState.NoAdb, "getprop got no answer")
            return
        }
        Log.i(TAG, "profile $before")
        if (before.cellular) {
            transition(CloudOverWifiState.OwnCellular, "apn1=${before.apn1State} apn3=${before.apn3State}")
            return
        }
        if (!system.hasValidatedWifi()) {
            transition(CloudOverWifiState.WaitingInternet, "no wifi with validated internet")
            return
        }
        if (!before.ready && !switchProfile(before)) return

        if (parseCloudConnected(execLogged(CMD_CLOUD_STATUS)) == true) {
            transition(CloudOverWifiState.Connected, "cloud already connected")
            return
        }
        if (!clearToChange(CMD_CLOUD_START, CloudOverWifiState.OwnCellular)) return
        if (!stillWanted()) return
        execLogged(CMD_CLOUD_START)
        system.sleep(CLOUD_SETTLE_MS)
        if (parseCloudConnected(execLogged(CMD_CLOUD_STATUS)) == true) {
            transition(CloudOverWifiState.Connected, "cloud connected after start")
        } else {
            transition(CloudOverWifiState.CloudSilent, "cloud not connected ${CLOUD_SETTLE_MS / 1000}s after start")
        }
    }

    /**
     * Switches to `double_apn`; false when it did not take. The profile to return to is saved
     * right before the broadcast goes out, and dropped again once the car is seen still on it.
     */
    private suspend fun switchProfile(before: Profile): Boolean {
        // A blank current profile (property never set) falls back to the factory one.
        val back = prefs.savedProfile() ?: before.effective
        if (!back.matches(PROFILE_NAME)) {
            transition(CloudOverWifiState.ProfileRejected, "no valid profile to return to: '$back'")
            return false
        }
        if (!clearToChange(DOUBLE_APN, CloudOverWifiState.OwnCellular)) return false
        if (!stillWanted()) return false
        if (prefs.savedProfile() == null) {
            prefs.setSavedProfile(back)
            Log.i(TAG, "saved profile to return to: $back")
        }
        if (execLogged(radioConfig(DOUBLE_APN)) == null) {
            transition(CloudOverWifiState.NoAdb, "switch broadcast got no answer, return of $back kept")
            return false
        }
        system.sleep(PROFILE_SETTLE_MS)
        val after = readProfile()
        Log.i(TAG, "profile after switch $after")
        if (after == null) {
            transition(CloudOverWifiState.NoAdb, "profile unreadable after switch, return of $back kept")
            return false
        }
        if (!after.ready) {
            if (after.effective == back && after.effective == before.effective && after.apn1Disable == before.apn1Disable) {
                prefs.setSavedProfile(null)
                Log.i(TAG, "car stayed on $back, nothing to return")
            }
            transition(CloudOverWifiState.ProfileRejected, "double_apn not taken")
            return false
        }
        return true
    }

    /**
     * Stops the cloud and returns the profile we switched away from. The saved profile is dropped
     * only once the car reads back on it; any doubt keeps it for the next attempt.
     */
    private suspend fun restore(saved: String) {
        if (adb.connect().isFailure) {
            transition(CloudOverWifiState.NoAdb, "adb connect refused, return of $saved pending")
            return
        }
        val current = readProfile() ?: run {
            transition(CloudOverWifiState.NoAdb, "getprop got no answer, return of $saved pending")
            return
        }
        if (current.effective == saved) {
            prefs.setSavedProfile(null)
            transition(CloudOverWifiState.Disabled, "car already on $saved, nothing sent")
            return
        }
        if (!clearToChange(CMD_CLOUD_STOP, CloudOverWifiState.ReturnPending)) return
        execLogged(CMD_CLOUD_STOP)
        system.sleep(STOP_SETTLE_MS)
        if (!clearToChange(saved, CloudOverWifiState.ReturnPending)) return
        if (execLogged(radioConfig(saved)) == null) {
            transition(CloudOverWifiState.NoAdb, "broadcast got no answer, return of $saved pending")
            return
        }
        system.sleep(PROFILE_SETTLE_MS)
        val after = readProfile()
        Log.i(TAG, "profile after return to $saved: $after")
        if (after?.apnType != saved) {
            transition(CloudOverWifiState.ReturnPending, "car not confirmed on $saved")
            return
        }
        prefs.setSavedProfile(null)
        transition(CloudOverWifiState.Disabled, "returned profile $saved")
    }

    /**
     * Re-reads the cellular link right before a changing command: false, with [cellularState] or
     * [CloudOverWifiState.NoAdb] set, when it is up or cannot be read.
     */
    private suspend fun clearToChange(what: String, cellularState: CloudOverWifiState): Boolean {
        val apn1 = getprop(PROP_APN1_STATE)
        val apn3 = getprop(PROP_APN3_STATE)
        if (apn1 == null || apn3 == null) {
            transition(CloudOverWifiState.NoAdb, "apn state unreadable before $what: apn1=$apn1 apn3=$apn3")
            return false
        }
        if (apn1 in CELLULAR_UP || apn3 in CELLULAR_UP) {
            transition(cellularState, "cellular up before $what: apn1=$apn1 apn3=$apn3")
            return false
        }
        return true
    }

    /** False once the toggle went off mid-attempt; the coalesced toggle-off rerun takes it from there. */
    private fun stillWanted(): Boolean {
        if (prefs.isEnabled()) return true
        Log.i(TAG, "attempt abandoned: toggle went off mid-attempt")
        return false
    }

    private data class Profile(
        val apnType: String,
        val factory: String,
        val apn1Disable: String,
        val apn1State: String,
        val apn3State: String,
    ) {
        val cellular: Boolean get() = apn1State in CELLULAR_UP || apn3State in CELLULAR_UP
        /** The profile the car runs: a blank property (never set) means the factory one. */
        val effective: String get() = apnType.ifBlank { factory }
        val ready: Boolean get() = apnType == DOUBLE_APN && apn1Disable == "1"
    }

    /** Null when any read gets no answer: an unknown cellular state must never pass for "down". */
    private suspend fun readProfile(): Profile? {
        return Profile(
            apnType = getprop(PROP_APN_TYPE) ?: return null,
            factory = getprop(PROP_FACTORY_APN_TYPE) ?: return null,
            apn1Disable = getprop(PROP_APN1_DISABLE) ?: return null,
            apn1State = getprop(PROP_APN1_STATE) ?: return null,
            apn3State = getprop(PROP_APN3_STATE) ?: return null,
        )
    }

    private suspend fun getprop(name: String): String? = adb.exec("getprop $name")?.trim()

    private suspend fun execLogged(cmd: String): String? {
        val out = adb.exec(cmd)
        Log.i(TAG, "exec '$cmd' -> ${out?.trim()?.take(200)}")
        return out
    }

    /** One diagnostics line; read-only. */
    suspend fun dumpLine(): String {
        val head = "cloud_over_wifi=${prefs.isEnabled()}/${_state.value} trigger=$lastTrigger " +
            "saved=${prefs.savedProfile()}"
        if (adb.connect().isFailure) return "$head adb=unavailable"
        val p = readProfile() ?: return "$head adb=no_answer"
        val cloud = parseCloudConnected(adb.exec(CMD_CLOUD_STATUS))
        return "$head profile=${p.apnType} factory=${p.factory} apn1_disable=${p.apn1Disable} " +
            "apn1=${p.apn1State} apn3=${p.apn3State} cloud_tcp=$cloud " +
            "tcp_step=${getprop("sys.tcp_step")} reg_errcode=${getprop("sys.tcp_reg_errcode")} " +
            "app_reg=${getprop("persist.sys.cloud.app_reg_status")} " +
            "sim=${getprop("gsm.sim.operator.numeric")}/${getprop("gsm.sim.operator.iso-country")}"
    }

    private fun transition(next: CloudOverWifiState, reason: String) {
        _state.value = next
        Log.i(TAG, "$next reason=$reason")
    }

    companion object {
        private const val TAG = "CloudOverWifi"

        const val TRIGGER_TOGGLE_OFF = "toggle_off"
        const val TRIGGER_WIFI = "wifi_validated"

        const val DOUBLE_APN = "double_apn"
        const val PROFILE_SETTLE_MS = 3_000L
        const val CLOUD_SETTLE_MS = 5_000L
        const val STOP_SETTLE_MS = 1_000L
        const val WIFI_SETTLE_MS = 15_000L

        private const val PROP_APN_TYPE = "persist.sys.byd.apn_type"
        private const val PROP_FACTORY_APN_TYPE = "ro.build.byd.apn_type"
        private const val PROP_APN1_DISABLE = "persist.radio.net.lte.apn1.disable"
        private const val PROP_APN1_STATE = "net.lte.apn1.state"
        private const val PROP_APN3_STATE = "net.lte.apn3.state"
        private const val CMD_CLOUD_STATUS = "service call cloudmanager 7"
        private const val CMD_CLOUD_START = "service call cloudmanager 1 i32 4"
        private const val CMD_CLOUD_STOP = "service call cloudmanager 1 i32 -5"
        private val CELLULAR_UP = setOf("connect", "connected")

        // A profile name ends up in a shell command line: nothing but [a-z_] may reach it.
        private val PROFILE_NAME = Regex("^[a-z_]+$")
        private val PARCEL_WORD = Regex("""\b[0-9a-fA-F]{8}\b""")

        private fun radioConfig(apnType: String): String =
            "am broadcast --user 0 -a com.byd.action.RADIO_CONFIG -p com.android.phone -f 0x01000000 " +
                "--es opt_name set_default_data --es apn_type $apnType"

        /**
         * `service call cloudmanager 7` → `Result: Parcel(00000000 00000001 '........')`: the first
         * word is the status (0 = ok), the second the TCP link (1 = up). Null for anything else.
         */
        fun parseCloudConnected(output: String?): Boolean? {
            val body = output?.substringAfter("Parcel(", "")?.substringBefore("'") ?: return null
            val words = PARCEL_WORD.findAll(body).map { it.value.toLong(16) }.toList()
            if (words.size < 2 || words[0] != 0L) return null
            return when (words[1]) {
                1L -> true
                0L -> false
                else -> null
            }
        }
    }
}
