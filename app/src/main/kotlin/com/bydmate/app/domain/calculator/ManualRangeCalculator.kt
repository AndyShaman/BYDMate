package com.bydmate.app.domain.calculator

import com.bydmate.app.data.repository.SettingsRepository.ManualRangePoint
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Alternative to [RangeCalculator]'s learned historical/live consumption blend: a
 * user-edited temperature -> consumption table, ported from the nordpool1hprices
 * companion app's BYD Atto 3 reference data (BatteryTemperatureCompensation /
 * RangeCompensator). Selected via SettingsRepository.KEY_RANGE_CALC_METHOD = "manual"
 * for users who'd rather tune a fixed table than rely on the learned average.
 */
@Singleton
class ManualRangeCalculator @Inject constructor() {

    /**
     * Linear interpolation of consumption (kWh/100km) between the table's temperature
     * points, clamped to the table's own range at the extremes. Falls back to the
     * nordpool1hprices default (16.3 kWh/100km, i.e. 0.163 kWh/km) if the table is empty.
     */
    fun interpolatedConsumption(temperatureC: Double, table: List<ManualRangePoint>): Double {
        if (table.isEmpty()) return 16.3
        val sorted = table.sortedBy { it.temperatureC }
        val temp = temperatureC.coerceIn(sorted.first().temperatureC.toDouble(), sorted.last().temperatureC.toDouble())

        sorted.find { it.temperatureC.toDouble() == temp }?.let { return it.consumptionKwhPer100Km }

        val below = sorted.last { it.temperatureC <= temp }
        val above = sorted.first { it.temperatureC >= temp }
        if (below.temperatureC == above.temperatureC) return below.consumptionKwhPer100Km

        val ratio = (temp - below.temperatureC) / (above.temperatureC - below.temperatureC)
        return below.consumptionKwhPer100Km + (above.consumptionKwhPer100Km - below.consumptionKwhPer100Km) * ratio
    }

    /** Same interpolation for the optional "range at 100% SOC" column. Null if no row has one. */
    fun interpolatedRangeAt100Soc(temperatureC: Double, table: List<ManualRangePoint>): Double? {
        val withRange = table.filter { it.rangeKmAt100Soc != null }
        if (withRange.isEmpty()) return null
        val sorted = withRange.sortedBy { it.temperatureC }
        val temp = temperatureC.coerceIn(sorted.first().temperatureC.toDouble(), sorted.last().temperatureC.toDouble())

        sorted.find { it.temperatureC.toDouble() == temp }?.let { return it.rangeKmAt100Soc }

        val below = sorted.last { it.temperatureC <= temp }
        val above = sorted.first { it.temperatureC >= temp }
        if (below.temperatureC == above.temperatureC) return below.rangeKmAt100Soc

        val belowRange = below.rangeKmAt100Soc ?: return null
        val aboveRange = above.rangeKmAt100Soc ?: return null
        val ratio = (temp - below.temperatureC) / (above.temperatureC - below.temperatureC)
        return belowRange + (aboveRange - belowRange) * ratio
    }

    /**
     * Mirrors RangeCalculator.estimateDetailed's shape so callers can treat both
     * calculators interchangeably. [fallbackCapacityKwh] is the user's plain battery
     * capacity setting, used only when the table has no range-at-100%-SOC data point
     * to derive an effective capacity from at this temperature.
     */
    fun estimateDetailed(
        soc: Int?,
        temperatureC: Int?,
        table: List<ManualRangePoint>,
        fallbackCapacityKwh: Double,
    ): RangeEstimate? {
        if (soc == null || soc <= 0 || soc > 100) return null
        val temp = (temperatureC ?: 20).toDouble()

        val consumption = interpolatedConsumption(temp, table)
        if (!consumption.isFinite() || consumption <= 0.0) return null

        val rangeAt100 = interpolatedRangeAt100Soc(temp, table)
        val effectiveCapacityKwh = if (rangeAt100 != null && rangeAt100 > 0.0) {
            rangeAt100 * consumption / 100.0
        } else {
            fallbackCapacityKwh
        }
        if (!effectiveCapacityKwh.isFinite() || effectiveCapacityKwh <= 0.0) return null

        val remainingKwh = (soc / 100.0) * effectiveCapacityKwh
        if (!remainingKwh.isFinite() || remainingKwh <= 0.0) return null

        val rangeKm = remainingKwh / consumption * 100.0
        if (!rangeKm.isFinite()) return null

        return RangeEstimate(
            rangeKm = rangeKm,
            avgKwhPer100 = consumption,
            capacityKwh = effectiveCapacityKwh,
            remainingKwh = remainingKwh,
        )
    }
}
