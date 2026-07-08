package com.bydmate.app.voice

import com.bydmate.app.data.remote.DiParsData

/** Runtime gate for voice sessions: whether voice is enabled (master switch)
 *  and the current vehicle snapshot used to enforce dispatcher safety blocks
 *  (e.g. the >80 km/h window-open block). Injected so the orchestrator stays
 *  free of Context/service statics and is unit-testable. */
interface VoiceGate {
    fun isEnabled(): Boolean
    fun vehicleSnapshot(): DiParsData?

    /**
     * Override language selected in Settings ("RU" or "EN"), or null to
     * follow the app locale. Read from SharedPreferences("voice") so
     * SteeringWheelKeyService and VoiceController can access it without
     * querying Room on a background thread.
     */
    fun preferredLang(): VoiceLang?

    /** True when the user enabled spoken agent answers (Settings mirror). */
    fun ttsEnabled(): Boolean
}
