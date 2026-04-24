package com.bydmate.app.domain.calculator

import javax.inject.Singleton

/** Test-friendly seam: production binding is OdometerConsumptionBuffer. */
interface ConsumptionAvgSource {
    suspend fun recentAvgConsumption(): Double
}

@Singleton
class RangeCalculator(
    private val buffer: ConsumptionAvgSource,
    private val capacityProvider: suspend () -> Double,
    private val socInterpolator: SocInterpolator,
) {
    /**
     * Returns estimated range in km, or null when inputs are insufficient.
     *
     *   remaining_kwh = SOC × cap / 100 - socInterpolator.carryOver(totalElec, soc)
     *   range_km      = remaining_kwh / recent_avg × 100
     */
    suspend fun estimate(soc: Int?, totalElecKwh: Double?): Double? {
        if (soc == null || soc <= 0) return null
        val cap = capacityProvider()
        if (cap <= 0.0) return null
        val avg = buffer.recentAvgConsumption()
        if (avg <= 0.0) return null
        val carry = socInterpolator.carryOver(totalElecKwh, soc)
        val remainingKwh = (soc / 100.0) * cap - carry
        if (remainingKwh <= 0.0) return null
        return remainingKwh / avg * 100.0
    }
}
