package com.bydmate.app.voice

import com.bydmate.app.data.remote.DiParsData

/** Runtime gate for voice sessions: whether voice is enabled (master switch)
 *  and the current vehicle snapshot used to enforce dispatcher safety blocks
 *  (e.g. the >80 km/h window-open block). Injected so the orchestrator stays
 *  free of Context/service statics and is unit-testable. */
interface VoiceGate {
    fun isEnabled(): Boolean
    fun vehicleSnapshot(): DiParsData?

    /** Age of [vehicleSnapshot] in milliseconds, or null when unknown (no snapshot yet).
     *  Default null keeps plain fakes honest: "unknown" rather than "fresh". */
    fun snapshotAgeMs(): Long? = null

    /** The range estimate the dashboard and the widget show, km; null when there is none. */
    fun rangeKm(): Double? = null

    /** True when the user enabled spoken agent answers (Settings mirror). */
    fun ttsEnabled(): Boolean

    /** True when the session closes itself after a done command or a final agent answer. */
    fun closeAfterCommand(): Boolean = false
}
