package com.bydmate.app.domain.calculator

import javax.inject.Singleton

/** Test-friendly seam: production binding is OdometerConsumptionBuffer. */
interface ConsumptionAvgSource {
    suspend fun recentAvgConsumption(): Double
}

/**
 * Intermediate values produced by the range estimation.
 *
 *   rangeKm       = remainingKwh / avgKwhPer100 * 100
 *   remainingKwh  = SOC * capacityKwh / 100 - carryOver
 */
data class RangeEstimate(
    val rangeKm: Double,
    val avgKwhPer100: Double,
    val capacityKwh: Double,
    val remainingKwh: Double,
)

@Singleton
class RangeCalculator(
    private val buffer: ConsumptionAvgSource,
    private val capacityProvider: suspend () -> Double,
    private val socInterpolator: SocInterpolator,
) {
    /**
     * Returns full estimation breakdown, or null when inputs are insufficient.
     *
     *   remaining_kwh = SOC * cap / 100 - socInterpolator.carryOver(totalElec, soc)
     *   range_km      = remaining_kwh / recent_avg * 100
     */
    suspend fun estimateDetailed(soc: Int?, totalElecKwh: Double?): RangeEstimate? {
        if (soc == null || soc <= 0 || soc > 100) return null
        val cap = capacityProvider()
        // Capacity is a free-form user setting: outside the sane EV range it is a
        // typo, not a battery (also rejects NaN/Infinity via the range check).
        if (cap !in CAPACITY_SANE_KWH) return null
        val avg = buffer.recentAvgConsumption()
        if (!avg.isFinite() || avg <= 0.0) return null
        val carry = socInterpolator.carryOver(totalElecKwh, soc)
        if (!carry.isFinite()) return null
        val remainingKwh = (soc / 100.0) * cap - carry
        if (!remainingKwh.isFinite() || remainingKwh <= 0.0) return null
        val rangeKm = remainingKwh / avg * 100.0
        if (!rangeKm.isFinite()) return null
        return RangeEstimate(
            rangeKm = rangeKm,
            avgKwhPer100 = avg,
            capacityKwh = cap,
            remainingKwh = remainingKwh,
        )
    }

    /** Returns estimated range in km, or null when inputs are insufficient. */
    suspend fun estimate(soc: Int?, totalElecKwh: Double?): Double? =
        estimateDetailed(soc, totalElecKwh)?.rangeKm

    companion object {
        /** Plausible EV battery capacity bounds for the user-entered setting, kWh. */
        val CAPACITY_SANE_KWH = 1.0..1000.0
    }
}
