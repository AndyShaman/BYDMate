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
        /** kWh-per-hour boundary between AC and DC. AC physically caps at ~11 kW
         *  (3-phase 16A); DC starts at 22 kW (CCS slow). 15 kW splits cleanly. */
        const val DC_AVG_POWER_KW_THRESHOLD = 15.0
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

    /**
     * Live path: classify by the directly observed motor power magnitude
     * (DiPars 发动机功率, kW; negative when energy flows into battery).
     * Cleaner than the kwh/hours heuristic — works correctly for short
     * sessions where the heuristic's `HEURISTIC_HOURS=1.0` assumption
     * underestimates the true power by an order of magnitude.
     */
    fun fromObservedPowerKw(observedKwAbs: Double?): String? {
        if (observedKwAbs == null || observedKwAbs <= 0.0) return null
        return if (observedKwAbs > DC_AVG_POWER_KW_THRESHOLD) "DC" else "AC"
    }
}
