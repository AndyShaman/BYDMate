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

    fun cellDeltaTone(maxCellV: Double?, minCellV: Double?): String {
        if (maxCellV == null || minCellV == null) return "good"
        // Round to 2 decimal places to avoid IEEE 754 floating-point noise
        // e.g. 3.37 - 3.34 = 0.030000000000000025 without rounding
        val delta = Math.round((maxCellV - minCellV) * 100.0) / 100.0
        return when {
            delta <= 0.03 -> "good"
            delta <= 0.05 -> "warning"
            else -> "critical"
        }
    }

    fun worst(vararg tones: String): String {
        val rank = mapOf("good" to 0, "warning" to 1, "critical" to 2)
        return tones.maxByOrNull { rank[it] ?: 0 } ?: "good"
    }
}
