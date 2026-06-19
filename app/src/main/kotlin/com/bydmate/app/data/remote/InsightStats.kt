package com.bydmate.app.data.remote

/**
 * Aggregated driving statistics for local insight rules and LLM prompts.
 * Built once per refresh from Room — no GPS or personal identifiers.
 */
data class InsightStats(
    val recentTripCount: Int,
    val recentKm: Double,
    val recentKwh: Double,
    val recentAvgCons: Double,
    val recentAvgSpeed: Double,
    val shortTripCount: Int,
    val prevTripCount: Int,
    val prevKm: Double = 0.0,
    val prevAvgCons: Double,
    /** Week-over-week consumption change, percent. Null when no previous week data. */
    val consumptionChangePct: Double?,
    val bestTripCons: Double?,
    val bestTripKm: Double?,
    val worstTripCons: Double?,
    val worstTripKm: Double?,
    val recentCost: Double,
    val currencyCode: String,
    val drainKwh: Double,
    val drainHours: Double,
    val voltage12v: Double?,
    /** Last-half minus first-half 12V average over up to 7 days. */
    val v12TrendDelta: Double?,
    val v12Min: Double?,
    val cellDeltaMv: Double?,
    val avgExteriorTemp: Int?,
    // Charging (last 7 days)
    val acKwhWeek: Double = 0.0,
    val dcKwhWeek: Double = 0.0,
    val acSessionCount: Int = 0,
    val dcSessionCount: Int = 0,
    val acCostPerKwh: Double? = null,
    val dcCostPerKwh: Double? = null,
    val chargeCostWeek: Double = 0.0,
    // Night idle: sessions started 22:00–05:59
    val nightDrainKwh: Double = 0.0,
    val nightDrainSharePct: Double? = null,
)
