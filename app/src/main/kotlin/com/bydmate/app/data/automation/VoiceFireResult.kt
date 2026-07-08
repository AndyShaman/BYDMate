package com.bydmate.app.data.automation

/** Outcome of an on-demand voice-triggered rule fire. */
sealed interface VoiceFireResult {
    data class Fired(val success: Boolean) : VoiceFireResult
    data object ParkRequired : VoiceFireResult   // rule.requirePark and not in P
    data object SpeedUnknown : VoiceFireResult    // window-open action but no snapshot
    data object Confirming : VoiceFireResult       // confirm overlay shown, runs async
    data object NotFound : VoiceFireResult         // rule missing / disabled / no actions
}
