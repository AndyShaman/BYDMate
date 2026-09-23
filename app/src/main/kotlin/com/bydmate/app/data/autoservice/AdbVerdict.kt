package com.bydmate.app.data.autoservice

/** What the user is told about the ADB control channel. */
enum class AdbVerdict { OK, NOT_ENABLED, OFF_AFTER_REBOOT, NO_ACCESS, HELPER_DOWN }

/**
 * Pure verdict table — first match wins. Null means "show nothing": a restore attempt is in
 * flight and would bring the port back within seconds, so any verdict would only flicker.
 */
@Suppress("LongParameterList") // one parameter per input of the verdict table
internal fun evaluateAdbVerdict(
    daemonHealthy: Boolean,
    adbConnected: Boolean,
    lastFailure: AdbConnectFailure?,
    restoreState: AdbRestoreState,
    adbWifiEnabled: Int,
    daemonEverAlive: Boolean,
): AdbVerdict? = when {
    daemonHealthy -> AdbVerdict.OK
    adbConnected -> AdbVerdict.HELPER_DOWN
    restoreState is AdbRestoreState.Connecting -> null
    lastFailure == AdbConnectFailure.AUTH_REJECTED ||
        restoreState is AdbRestoreState.NeedsDialog -> AdbVerdict.NO_ACCESS
    adbWifiEnabled == 0 && !daemonEverAlive -> AdbVerdict.NOT_ENABLED
    else -> AdbVerdict.OFF_AFTER_REBOOT
}
