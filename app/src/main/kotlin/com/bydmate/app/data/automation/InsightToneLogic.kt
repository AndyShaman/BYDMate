// app/src/main/kotlin/com/bydmate/app/data/automation/InsightToneLogic.kt
package com.bydmate.app.data.automation

object InsightToneLogic {
    fun consumptionTone(changePct: Double?): String = when {
        changePct == null -> "good"
        changePct <= 5.0 -> "good"
        changePct <= 15.0 -> "warning"
        else -> "critical"
    }

    fun voltage12vTone(volts: Double?): String = when {
        volts == null -> "good"
        volts < 11.8 -> "critical"
        volts < 12.4 -> "warning"
        else -> "good"
    }

    fun cellDeltaTone(maxCellV: Double?, minCellV: Double?, soc: Int?): String {
        if (maxCellV == null || minCellV == null) return "good"
        // Delta is diagnostic only on the LFP plateau (SOC 20-80%, BYD's own check
        // band). Near full charge the leader cell hits the knee first, so a large
        // delta there is normal physics, not a defect (#113).
        if (soc == null || soc !in 20..80) return "good"
        // Round to millivolt precision to avoid IEEE 754 floating-point noise
        // e.g. 3.37 - 3.34 = 0.030000000000000025 without rounding
        val delta = Math.round((maxCellV - minCellV) * 1000.0) / 1000.0
        return when {
            delta < 0.050 -> "good"
            delta < 0.090 -> "warning"
            else -> "critical"
        }
    }

    fun worst(vararg tones: String): String {
        val rank = mapOf("good" to 0, "warning" to 1, "critical" to 2)
        return tones.maxByOrNull { rank[it] ?: 0 } ?: "good"
    }
}
