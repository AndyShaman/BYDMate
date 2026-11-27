package com.bydmate.app.service

/**
 * Throttles the hybrid battery-range diagnostic log line to at most once per km of odometer travel.
 */
internal object HybridRangeTrace {
    private const val STEP_KM = 1.0

    fun due(lastLoggedKm: Double?, odometerKm: Double?): Boolean {
        if (odometerKm == null) return false
        if (lastLoggedKm == null) return true
        val delta = odometerKm - lastLoggedKm
        return delta >= STEP_KM || delta < 0.0
    }
}
