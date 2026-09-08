package com.bydmate.app.data.autoservice

/**
 * What the ADB restore feature is doing right now. Rendered as a single status line
 * under the toggle in Settings and logged on every transition (tag `AdbRestore`).
 *
 * `data object` throughout so a log line reads `AdbRestore: NotNeeded reason=…` instead of the
 * default identity hash — the dump is the only view we get of a car we cannot touch.
 */
sealed class AdbRestoreState {
    /** Toggle is off — nothing is attempted and no status line is shown. */
    data object Disabled : AdbRestoreState()

    /** The classic port answers on its own: this firmware does not need the feature. */
    data object NotNeeded : AdbRestoreState()

    /** WRITE_SECURE_SETTINGS was never granted, so wireless debugging cannot be switched on. */
    data object NeedsActivation : AdbRestoreState()

    /** No Wi-Fi connection — Android refuses to enable wireless debugging without one. */
    data object WaitingWifi : AdbRestoreState()

    /** The system reset our write: the "Allow wireless debugging on this network?" dialog is waiting. */
    data object NeedsDialog : AdbRestoreState()

    /** Discovery / TLS / tcpip in progress. */
    data object Connecting : AdbRestoreState()

    /** The classic port answers again after our intervention, at [atMs]. */
    data class Restored(val atMs: Long) : AdbRestoreState()

    /** The attempt ended without a working port; [reason] is a short technical cause for the log. */
    data class Failed(val reason: String) : AdbRestoreState()
}
