package com.bydmate.app.domain

/**
 * The one set of colour thresholds for the 12 V reading, the charge percent and the battery
 * temperature. The 12 V and the charge percent compare strictly below: warning under *_WARN,
 * critical under *_CRIT.
 */
object BatteryThresholds {
    const val V12_WARN = 12.5
    const val V12_CRIT = 12.0
    const val SOC_WARN = 20
    const val SOC_CRIT = 10

    /** Degrees C. Below COLD_WARN and above HOT_WARN is a warning, above HOT_CRIT is critical. */
    const val BAT_TEMP_COLD_WARN = 5
    const val BAT_TEMP_HOT_WARN = 45
    const val BAT_TEMP_HOT_CRIT = 50

    /**
     * 0 normal, 1 warning, 2 critical. A cold pack is winter, not a fault, so cold is a warning
     * at most; only overheating is critical.
     */
    fun batTempSeverity(tempC: Int): Int = when {
        tempC > BAT_TEMP_HOT_CRIT -> 2
        tempC < BAT_TEMP_COLD_WARN || tempC > BAT_TEMP_HOT_WARN -> 1
        else -> 0
    }
}
