package com.bydmate.app.domain.calculator

/**
 * Computes the floating widget's big consumption number, a live forecast of
 * the final trip-average that will land in TripEntity once ignition cycles off.
 *
 * Pure stateless function. Three regimes:
 *   - parked / no active trip:  show last trip's average (or 25-km fallback)
 *   - active trip < 0.3 km:     show last trip's average (volatile zone)
 *   - active trip 0.3..2.0 km:  linear blend lastTripAvg to currentTripAvg
 *   - active trip >= 2.0 km:    show currentTripAvg (= tripKwh / tripKm * 100)
 *
 * Converges to TripEntity by definition: when the trip closes,
 * currentTripAvg equals the final recorded average.
 */
object BigNumberCalculator {

    private const val FADE_START_KM = 0.3
    private const val FADE_END_KM = 2.0

    fun computeDisplay(
        tripKm: Double?,
        tripKwh: Double?,
        lastTripAvg: Double?,
        recentAvg25km: Double,
        sessionActive: Boolean,
    ): Double? {
        val parkingFallback: () -> Double? = {
            lastTripAvg ?: recentAvg25km.takeIf { it > 0.01 }
        }

        if (!sessionActive) return parkingFallback()
        if (tripKm == null || tripKwh == null) return parkingFallback()
        if (tripKwh <= 0.0) return parkingFallback()  // BMS recal glitch or no energy counted yet
        if (tripKm <= 0.01) return parkingFallback()

        val currentTripAvg = tripKwh / tripKm * 100.0

        if (tripKm < FADE_START_KM) return parkingFallback()

        if (tripKm < FADE_END_KM) {
            if (lastTripAvg == null) return currentTripAvg
            val weight = (tripKm - FADE_START_KM) / (FADE_END_KM - FADE_START_KM)
            return weight * currentTripAvg + (1.0 - weight) * lastTripAvg
        }

        return currentTripAvg
    }
}
