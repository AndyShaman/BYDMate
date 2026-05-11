package com.bydmate.app.data.remote

/**
 * Pure adaptive cadence policy for Iternio Telemetry sends.
 *
 * State precedence: CHARGING > PARKED > DRIVING. Charging always wins when
 * the gun is connected, because losing fast-charge SOC samples is the
 * single most visible degradation on ABRP charts.
 *
 * Intervals are tuned to:
 *  - DRIVING — 1 s. Iternio rates sources by samples-per-10s for `power`
 *    and `speed`; 1 Hz is what teslamate-abrp / OVMS use during drives.
 *  - CHARGING — 8 s. Under the 10-s Iternio accuracy window with margin;
 *    matches teslamate-abrp's ~6-10s charging cadence.
 *  - PARKED — 30 s. Keeps the session alive on ABRP without spamming the
 *    radio when nothing is changing.
 */
object IternioIntervalPolicy {

    enum class TelemetryState { DRIVING, CHARGING, PARKED }

    fun classify(charging: Boolean, parked: Boolean): TelemetryState = when {
        charging -> TelemetryState.CHARGING
        parked -> TelemetryState.PARKED
        else -> TelemetryState.DRIVING
    }

    fun intervalSec(state: TelemetryState): Int = when (state) {
        TelemetryState.DRIVING -> 1
        TelemetryState.CHARGING -> 8
        TelemetryState.PARKED -> 30
    }

    /**
     * Classify from DiPars-only signals — cheap per-tick check that avoids
     * an autoservice ADB read just to pick a cadence.
     *
     * Charging: gun whitelist {2=AC, 3=DC, 4=AC_DC, 5=VTOL} mirrors
     * `DCFC_GUN_STATES` in IternioTelemetryClient. On Leopard 3 DiPars only
     * emits 0/1/2; the wider set covers other firmwares.
     *
     * Parked: `gear == 1` is the existing is_parked semantic. Null gear is
     * the DiPars reduced-payload case — collapse to PARKED unless we have
     * non-zero `speed`, which means the car is actually moving even when
     * the gear field went silent. Speed is used ONLY as the null-gear
     * fallback, not as a primary signal — gear remains authoritative when
     * present.
     */
    fun classifyFromDiPars(data: com.bydmate.app.data.remote.DiParsData): TelemetryState {
        val charging = data.chargeGunState in CHARGING_GUN_STATES
        val gear = data.gear
        val parked = when {
            gear == 1 -> true
            gear != null -> false
            else -> (data.speed ?: 0) <= 0
        }
        return classify(charging = charging, parked = parked)
    }

    private val CHARGING_GUN_STATES = setOf(2, 3, 4, 5)
}
