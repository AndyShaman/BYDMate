package com.bydmate.app.data.vehicle

import android.content.pm.PackageManager
import android.util.Log
import com.bydmate.app.diagnostics.Trace
import com.bydmate.app.diagnostics.TraceArea
import com.bydmate.app.platform.LegacyHeadUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The stock BYD assistant that the "disable the native assistant" toggle switches off and back on
 * through the helper daemon (TX_SET_APP_HIDDEN, `pm disable-user` / `pm enable`): the autovoice
 * family on every car, plus com.byd.vrassistant, the stock voice UI of Android 10 head units
 * (PR #261, ATTO 3 on DiLink 3.0), there and only where it is installed.
 */
object NativeAssistant {
    const val AUTOVOICE = "com.byd.autovoice"
    // Siblings the daemon disables together with AUTOVOICE (HelperDaemon.TX_SET_APP_HIDDEN): the
    // wake/recognition engine and TTS output. Single source of truth, reused by the daemon so the
    // family is never duplicated as literals in two places.
    const val AUTOVOICE_ENGINE = "com.byd.autovoice.engine"
    const val AUTOVOICE_TTS = "com.byd.autovoice.tts"
    val AUTOVOICE_FAMILY = listOf(AUTOVOICE, AUTOVOICE_ENGINE, AUTOVOICE_TTS)
    const val VRASSISTANT = "com.byd.vrassistant"
    // Recorded by LogRecorder: the line is the daemon call's outcome.
    private const val TAG = "HelperClient"

    // Serializes setDisabled so a startup reconciliation and a user toggle can never interleave
    // their two setAppHidden calls (autovoice, then vrassistant on legacy head units): whichever
    // sequence starts last also finishes last, never split across the other one.
    private val mutex = Mutex()

    suspend fun setDisabled(
        helperClient: HelperClient,
        packageManager: PackageManager,
        disabled: Boolean,
        legacyHeadUnit: Boolean = LegacyHeadUnit.isAndroid10,
    ) = mutex.withLock {
        helperClient.setAppHidden(AUTOVOICE, disabled)
        if (legacyHeadUnit && isInstalled(packageManager, VRASSISTANT)) {
            val ok = helperClient.setAppHidden(VRASSISTANT, disabled)
            Log.i(TAG, "$VRASSISTANT disabled=$disabled ok=$ok")
        }
    }

    /**
     * The toggle's reading while the driver never chose here: on when a package this toggle
     * manages on this car is disabled by a user (`pm disable-user`), as a BYDMate uninstalled with
     * the toggle on leaves it. Switching the toggle off then enables it again. A package that is
     * missing or cannot be read counts as enabled. Logged only when the system turns the toggle
     * on: an untouched assistant reads off, as before, on every opening of the settings.
     */
    fun disabledInSystem(packageManager: PackageManager, legacyHeadUnit: Boolean = LegacyHeadUnit.isAndroid10): Boolean {
        val managed = if (legacyHeadUnit) AUTOVOICE_FAMILY + VRASSISTANT else AUTOVOICE_FAMILY
        val disabledByUser = managed.filter { pkg ->
            runCatching { packageManager.getApplicationEnabledSetting(pkg) }.getOrNull() ==
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
        }
        if (disabledByUser.isEmpty()) return false
        val packages = disabledByUser.joinToString(",")
        Log.i(TAG, "native assistant: no saved choice, toggle shown on from the system: disabled by user $packages")
        Trace.event(TraceArea.APP, "native-assistant-from-system", "packages" to packages)
        return true
    }

    // A package we disabled earlier is still installed: its enabled setting reads DISABLED_USER.
    private fun isInstalled(packageManager: PackageManager, pkg: String): Boolean =
        runCatching { packageManager.getApplicationEnabledSetting(pkg) }.isSuccess
}
