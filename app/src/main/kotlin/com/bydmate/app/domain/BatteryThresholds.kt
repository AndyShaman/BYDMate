package com.bydmate.app.domain

/**
 * The one set of colour thresholds for the 12 V reading and the charge percent.
 * Every screen compares strictly below: warning under *_WARN, critical under *_CRIT.
 */
object BatteryThresholds {
    const val V12_WARN = 12.5
    const val V12_CRIT = 12.0
    const val SOC_WARN = 20
    const val SOC_CRIT = 10
}
