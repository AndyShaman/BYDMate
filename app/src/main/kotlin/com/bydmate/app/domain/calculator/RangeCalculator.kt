package com.bydmate.app.domain.calculator

import com.bydmate.app.data.repository.SettingsRepository
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
    private val manualCalculator: ManualRangeCalculator = ManualRangeCalculator(),
    private val methodProvider: suspend () -> String = { SettingsRepository.RANGE_CALC_AUTO },
    private val manualTableProvider: suspend () -> List<SettingsRepository.ManualRangePoint> = { emptyList() },
) {
    /**
     * Returns full estimation breakdown, or null when inputs are insufficient.
     *
     * When SettingsRepository.KEY_RANGE_CALC_METHOD is "manual", delegates to
     * [ManualRangeCalculator] (a user-edited temperature table) instead of the
     * historical/live consumption blend below. [exteriorTempC] is only consulted
     * in that mode.
     *
     *   remaining_kwh = SOC * cap / 100 - socInterpolator.carryOver(totalElec, soc)
     *   range_km      = remaining_kwh / recent_avg * 100
     */
    suspend fun estimateDetailed(soc: Int?, totalElecKwh: Double?, exteriorTempC: Int? = null): RangeEstimate? {
        if (methodProvider() == SettingsRepository.RANGE_CALC_MANUAL) {
            return manualCalculator.estimateDetailed(
                soc = soc,
                temperatureC = exteriorTempC,
                table = manualTableProvider(),
                fallbackCapacityKwh = capacityProvider(),
            )
        }

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
    suspend fun estimate(soc: Int?, totalElecKwh: Double?, exteriorTempC: Int? = null): Double? =
        estimateDetailed(soc, totalElecKwh, exteriorTempC)?.rangeKm

    companion object {
        /** Plausible EV battery capacity bounds for the user-entered setting, kWh. */
        val CAPACITY_SANE_KWH = 1.0..1000.0
    }
}
