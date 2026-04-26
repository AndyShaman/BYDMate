package com.bydmate.app.data.charging

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Classifies a charging session as AC or DC.
 *
 * Preferred path (live): use the `gun_state` value captured at handshake.
 * Fallback (catch-up): use kwh / hours heuristic — DC chargers deliver
 * far more than 20 kW averaged across a session; home AC chargers cap
 * around 7-11 kW. The 20 kW boundary cleanly separates them.
 *
 * The user can always override via the optional finalize prompt (Phase 3).
 */
@Singleton
class ChargingTypeClassifier @Inject constructor() {

    companion object {
        /** kWh-per-hour boundary between AC and DC. */
        const val DC_AVG_POWER_KW_THRESHOLD = 20.0
    }

    /**
     * Maps the raw gun_state autoservice value to "AC"/"DC", or null if
     * the gun is disconnected (state 1) or unknown.
     *   1 = NONE
     *   2 = AC
     *   3 = DC
     *   4 = GB_DC (treat as DC for UI/tariff purposes)
     */
    fun fromGunState(gunState: Int?): String? = when (gunState) {
        2 -> "AC"
        3, 4 -> "DC"
        else -> null
    }

    /**
     * Heuristic for catch-up paths where gun_state is no longer available.
     * Returns "DC" if avg power > threshold; "AC" otherwise (and as a safe
     * default when inputs are degenerate — picks the cheaper tariff).
     */
    fun heuristicByPower(kwhCharged: Double, hours: Double): String {
        if (kwhCharged <= 0.0 || hours <= 0.0) return "AC"
        val avgKw = kwhCharged / hours
        return if (avgKw > DC_AVG_POWER_KW_THRESHOLD) "DC" else "AC"
    }
}
