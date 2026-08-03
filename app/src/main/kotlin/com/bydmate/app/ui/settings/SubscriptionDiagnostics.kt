package com.bydmate.app.ui.settings

/**
 * Observe-mode fid subscription counters for the diagnostic dump: whether each channel
 * ever fired, how far it drifted from the poll, and whether it went sick.
 */
internal object SubscriptionDiagnostics {
    fun format(lines: List<String>?): List<String> =
        if (lines.isNullOrEmpty()) listOf("unavailable") else lines
}
