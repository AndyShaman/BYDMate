package com.bydmate.app.domain.calculator

import com.bydmate.app.data.local.entity.ChargePointEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConsumptionCalculator @Inject constructor() {

    // Real kWh from delta SOC (always non-negative for trips)
    fun realKwh(socStart: Int, socEnd: Int, batteryCapacityKwh: Double): Double {
        val kwh = (socStart - socEnd) / 100.0 * batteryCapacityKwh
        return kwh.coerceAtLeast(0.0)
    }

    // Consumption per 100 km (null if distance too small)
    fun kwhPer100km(kwh: Double, distanceKm: Double): Double? {
        if (distanceKm < 0.1) return null
        return kwh / distanceKm * 100.0
    }

    // Charge energy via power integration: sum(|power| * interval / 3600)
    fun chargePowerIntegration(chargePoints: List<ChargePointEntity>): Double {
        if (chargePoints.size < 2) return 0.0
        var totalKwh = 0.0
        for (i in 1 until chargePoints.size) {
            val prev = chargePoints[i - 1]
            val curr = chargePoints[i]
            val powerKw = prev.powerKw ?: continue
            val intervalSec = (curr.timestamp - prev.timestamp) / 1000.0
            totalKwh += kotlin.math.abs(powerKw) * intervalSec / 3600.0
        }
        return totalKwh
    }

    // Charge energy fallback via delta SOC
    fun chargeKwhFromSoc(socStart: Int, socEnd: Int, batteryCapacityKwh: Double): Double {
        return (socEnd - socStart) / 100.0 * batteryCapacityKwh
    }
}
