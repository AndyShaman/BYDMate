package com.bydmate.app.data.automation

/** Outcome of an on-demand voice-triggered rule fire. */
sealed interface VoiceFireResult {
    /** success=true means the rule's actions were launched, not that they finished; the
     *  non-confirm path in [AutomationEngine.fireVoiceRule] hands them off to its own
     *  service-lifetime scope so the caller (e.g. a cancelled voice UI job) can't abort them
     *  mid-sequence. */
    data class Fired(val success: Boolean) : VoiceFireResult
    data object ParkRequired : VoiceFireResult   // rule.requirePark and not in P
    data object SpeedUnknown : VoiceFireResult    // window-open action but no snapshot
    data object Confirming : VoiceFireResult       // confirm overlay shown, runs async
    data object NotFound : VoiceFireResult         // rule missing / disabled / no actions
}
