package com.bydmate.app.data.remote

import android.content.res.Resources
import com.bydmate.app.R

/**
 * Offline insight generator: deterministic rules + localized templates.
 * Replaces the cloud LLM for title, summary, and recommendation bullets.
 */
object LocalInsightEngine {

    data class TextResult(
        val title: String,
        val summary: String,
        val insights: List<String>,
    )

    private data class Candidate(
        val priority: Int,
        val title: String,
        val summary: String,
        val insight: String? = null,
    )

    private data class InsightBullet(
        val priority: Int,
        val text: String,
    )

    fun generate(stats: InsightStats, res: Resources): TextResult {
        val candidates = mutableListOf<Candidate>()
        val bullets = mutableListOf<InsightBullet>()

        val changePct = stats.consumptionChangePct
        val shortPct = if (stats.recentTripCount > 0) {
            stats.shortTripCount * 100.0 / stats.recentTripCount
        } else 0.0

        // --- Title / summary drivers (highest priority wins) ---

        when {
            stats.voltage12v != null && stats.voltage12v < 11.8 -> {
                candidates += Candidate(
                    priority = 90,
                    title = res.getString(R.string.local_insight_12v_critical_title),
                    summary = res.getString(
                        R.string.local_insight_12v_critical_summary,
                        stats.voltage12v,
                    ),
                )
            }
            stats.cellDeltaMv != null && stats.cellDeltaMv > 50.0 -> {
                candidates += Candidate(
                    priority = 85,
                    title = res.getString(R.string.local_insight_cell_critical_title),
                    summary = res.getString(
                        R.string.local_insight_cell_critical_summary,
                        stats.cellDeltaMv,
                    ),
                )
            }
            changePct != null && changePct > 15.0 -> {
                candidates += Candidate(
                    priority = 80,
                    title = res.getString(R.string.local_insight_consumption_up_title, changePct),
                    summary = summaryForConsumptionRise(stats, shortPct, res),
                )
            }
            stats.nightDrainKwh >= 4.0 &&
                (stats.nightDrainSharePct ?: 0.0) >= 35.0 -> {
                candidates += Candidate(
                    priority = 78,
                    title = res.getString(R.string.local_insight_night_idle_title),
                    summary = res.getString(
                        R.string.local_insight_night_idle_summary,
                        stats.nightDrainKwh,
                        stats.nightDrainSharePct!!.toInt(),
                    ),
                )
            }
            changePct != null && changePct > 5.0 -> {
                candidates += Candidate(
                    priority = 70,
                    title = res.getString(R.string.local_insight_consumption_up_title, changePct),
                    summary = summaryForConsumptionRise(stats, shortPct, res),
                )
            }
            changePct != null && changePct < -5.0 -> {
                candidates += Candidate(
                    priority = 60,
                    title = res.getString(R.string.local_insight_consumption_down_title, -changePct),
                    summary = res.getString(
                        R.string.local_insight_consumption_down_summary,
                        stats.recentAvgCons,
                    ),
                )
            }
            else -> {
                candidates += Candidate(
                    priority = 10,
                    title = res.getString(R.string.local_insight_consumption_stable_title),
                    summary = res.getString(
                        R.string.local_insight_consumption_stable_summary,
                        stats.recentAvgCons,
                    ),
                )
            }
        }

        // --- Insight bullets (top 5 by priority) ---

        if (stats.nightDrainKwh >= 0.3) {
            val share = stats.nightDrainSharePct?.toInt() ?: 0
            bullets += InsightBullet(
                priority = 78,
                text = res.getString(
                    R.string.local_insight_night_idle,
                    stats.nightDrainKwh,
                    share,
                ),
            )
        }

        val acCost = stats.acCostPerKwh
        val dcCost = stats.dcCostPerKwh
        if (stats.acKwhWeek >= 3.0 && stats.dcKwhWeek >= 3.0 &&
            acCost != null && dcCost != null && dcCost > acCost * 1.15
        ) {
            val pctMore = ((dcCost / acCost - 1.0) * 100.0).toInt()
            bullets += InsightBullet(
                priority = 74,
                text = res.getString(
                    R.string.local_insight_dc_more_expensive,
                    dcCost,
                    acCost,
                    pctMore,
                    stats.currencyCode,
                ),
            )
        }

        if (stats.dcSessionCount >= 2 && stats.dcKwhWeek > stats.acKwhWeek * 1.5 &&
            stats.dcKwhWeek >= 15.0
        ) {
            bullets += InsightBullet(
                priority = 72,
                text = res.getString(
                    R.string.local_insight_dc_heavy,
                    stats.dcKwhWeek,
                    stats.dcSessionCount,
                    stats.acKwhWeek,
                ),
            )
        }

        if (stats.acSessionCount >= 2 && stats.dcSessionCount == 0 && stats.acKwhWeek >= 8.0) {
            bullets += InsightBullet(
                priority = 45,
                text = res.getString(
                    R.string.local_insight_ac_only,
                    stats.acKwhWeek,
                    stats.acSessionCount,
                ),
            )
        }

        if (shortPct >= 40.0 && stats.shortTripCount >= 2) {
            bullets += InsightBullet(
                priority = 60,
                text = res.getString(
                    R.string.local_insight_short_trips,
                    stats.shortTripCount,
                    stats.recentTripCount,
                    shortPct.toInt(),
                ),
            )
        }

        val drainShare = if (stats.recentKwh > 0.1) stats.drainKwh / stats.recentKwh * 100.0 else 0.0
        if (stats.drainKwh >= 2.0 && (drainShare >= 15.0 || stats.drainKwh >= 5.0)) {
            val timeLabel = if (stats.drainHours < 1.0) {
                res.getString(R.string.insight_idle_minutes, "%.0f".format(stats.drainHours * 60))
            } else {
                res.getString(R.string.insight_idle_hours, "%.1f".format(stats.drainHours))
            }
            bullets += InsightBullet(
                priority = 55,
                text = res.getString(
                    R.string.local_insight_idle,
                    stats.drainKwh,
                    timeLabel,
                    drainShare.toInt(),
                ),
            )
        }

        if (stats.voltage12v != null && stats.voltage12v in 11.8..12.39) {
            bullets += InsightBullet(
                priority = 50,
                text = res.getString(R.string.local_insight_12v_low, stats.voltage12v),
            )
        } else if (stats.v12TrendDelta != null && stats.v12TrendDelta < -0.1 && stats.v12Min != null) {
            val first = stats.v12Min
            val last = stats.v12Min + stats.v12TrendDelta * 2
            bullets += InsightBullet(
                priority = 50,
                text = res.getString(R.string.local_insight_12v_falling, first, last),
            )
        }

        if (stats.cellDeltaMv != null && stats.cellDeltaMv in 31.0..50.0) {
            bullets += InsightBullet(
                priority = 48,
                text = res.getString(R.string.local_insight_cell_delta, stats.cellDeltaMv),
            )
        }

        if (stats.avgExteriorTemp != null && stats.avgExteriorTemp <= 5 &&
            changePct != null && changePct > 3.0
        ) {
            bullets += InsightBullet(
                priority = 46,
                text = res.getString(
                    R.string.local_insight_cold,
                    stats.avgExteriorTemp,
                    stats.recentAvgCons,
                ),
            )
        }

        if (stats.bestTripCons != null && stats.worstTripCons != null &&
            stats.worstTripCons - stats.bestTripCons >= 5.0
        ) {
            bullets += InsightBullet(
                priority = 40,
                text = res.getString(
                    R.string.local_insight_best_worst,
                    stats.bestTripCons,
                    stats.worstTripCons,
                ),
            )
        }

        if (changePct != null && changePct <= -5.0) {
            bullets += InsightBullet(
                priority = 62,
                text = res.getString(
                    R.string.local_insight_consumption_improved,
                    -changePct,
                ),
            )
        }

        if (stats.recentAvgSpeed >= 75.0 && stats.recentAvgCons >= 17.0 && stats.recentKm >= 80.0) {
            bullets += InsightBullet(
                priority = 58,
                text = res.getString(
                    R.string.local_insight_highway_speed,
                    stats.recentAvgSpeed,
                    stats.recentAvgCons,
                ),
            )
        }

        if (stats.recentAvgCons >= 28.0 && stats.recentKm >= 30.0) {
            bullets += InsightBullet(
                priority = 57,
                text = res.getString(
                    R.string.local_insight_high_consumption,
                    stats.recentAvgCons,
                ),
            )
        }

        if (stats.drainHours >= 0.3 && stats.drainKwh / stats.drainHours >= 1.2) {
            bullets += InsightBullet(
                priority = 56,
                text = res.getString(
                    R.string.local_insight_idle_rate_high,
                    stats.drainKwh / stats.drainHours,
                ),
            )
        }

        if (stats.prevKm >= 50.0 && stats.recentKm > stats.prevKm * 1.2) {
            bullets += InsightBullet(
                priority = 54,
                text = res.getString(
                    R.string.local_insight_km_up,
                    stats.prevKm,
                    stats.recentKm,
                ),
            )
        }

        if (stats.avgExteriorTemp != null && stats.avgExteriorTemp >= 25 &&
            stats.recentAvgCons >= 22.0
        ) {
            bullets += InsightBullet(
                priority = 53,
                text = res.getString(
                    R.string.local_insight_heat_ac,
                    stats.avgExteriorTemp,
                    stats.recentAvgCons,
                ),
            )
        }

        if (stats.prevTripCount >= 3 &&
            stats.recentTripCount > stats.prevTripCount * 1.2
        ) {
            bullets += InsightBullet(
                priority = 52,
                text = res.getString(
                    R.string.local_insight_trips_more,
                    stats.recentTripCount,
                    stats.prevTripCount,
                ),
            )
        }

        if (stats.bestTripCons != null && stats.bestTripKm != null &&
            stats.bestTripCons <= 15.0 && stats.bestTripKm >= 15.0
        ) {
            bullets += InsightBullet(
                priority = 51,
                text = res.getString(
                    R.string.local_insight_best_trip,
                    stats.bestTripCons,
                    stats.bestTripKm,
                ),
            )
        }

        if (stats.recentAvgCons <= 16.0 && stats.recentKm >= 50.0) {
            bullets += InsightBullet(
                priority = 49,
                text = res.getString(
                    R.string.local_insight_low_consumption,
                    stats.recentAvgCons,
                    stats.recentKm,
                ),
            )
        }

        if (stats.acSessionCount >= 1 && stats.dcSessionCount >= 1 &&
            stats.acKwhWeek + stats.dcKwhWeek >= 5.0
        ) {
            bullets += InsightBullet(
                priority = 47,
                text = res.getString(
                    R.string.local_insight_mixed_charging,
                    stats.acKwhWeek,
                    stats.dcKwhWeek,
                ),
            )
        }

        if (stats.recentKm >= 250.0) {
            bullets += InsightBullet(
                priority = 44,
                text = res.getString(
                    R.string.local_insight_weekly_mileage,
                    stats.recentKm,
                    stats.recentTripCount,
                ),
            )
        }

        if (stats.recentCost > 0 && stats.recentKm >= 20.0) {
            val costPer100 = stats.recentCost / stats.recentKm * 100.0
            bullets += InsightBullet(
                priority = 42,
                text = res.getString(
                    R.string.local_insight_cost_per_100,
                    costPer100,
                    stats.recentCost,
                    stats.currencyCode,
                ),
            )
        }

        if (stats.chargeCostWeek > 0 && stats.acKwhWeek + stats.dcKwhWeek > 0) {
            bullets += InsightBullet(
                priority = 38,
                text = res.getString(
                    R.string.local_insight_charge_cost_week,
                    stats.chargeCostWeek,
                    stats.currencyCode,
                    stats.acKwhWeek + stats.dcKwhWeek,
                ),
            )
        } else if (stats.recentCost > 0 && stats.recentKm > 0) {
            bullets += InsightBullet(
                priority = 35,
                text = res.getString(
                    R.string.local_insight_cost,
                    stats.recentCost,
                    stats.currencyCode,
                ),
            )
        }

        if (stats.nightDrainSharePct != null && stats.nightDrainKwh >= 0.1 &&
            stats.nightDrainSharePct < 20.0 && stats.drainKwh >= 1.0
        ) {
            bullets += InsightBullet(
                priority = 34,
                text = res.getString(
                    R.string.local_insight_low_night_idle,
                    stats.nightDrainSharePct.toInt(),
                ),
            )
        }

        if (drainShare in 0.1..8.0 && stats.drainKwh >= 0.5 && stats.recentKwh >= 5.0) {
            bullets += InsightBullet(
                priority = 33,
                text = res.getString(
                    R.string.local_insight_low_idle_share,
                    drainShare.toInt(),
                ),
            )
        }

        if (stats.voltage12v != null && stats.voltage12v >= 12.5) {
            bullets += InsightBullet(
                priority = 32,
                text = res.getString(R.string.local_insight_12v_ok, stats.voltage12v),
            )
        }

        if (stats.cellDeltaMv != null && stats.cellDeltaMv in 1.0..25.0) {
            bullets += InsightBullet(
                priority = 31,
                text = res.getString(R.string.local_insight_cell_ok, stats.cellDeltaMv),
            )
        }

        val winner = candidates.maxByOrNull { it.priority }!!
        val insights = (listOfNotNull(winner.insight) + bullets
            .sortedByDescending { it.priority }
            .map { it.text })
            .distinct()
            .take(5)

        return TextResult(
            title = winner.title,
            summary = winner.summary,
            insights = insights,
        )
    }

    fun notEnoughData(res: Resources): TextResult = TextResult(
        title = res.getString(R.string.local_insight_not_enough_data_title),
        summary = res.getString(R.string.local_insight_not_enough_data_summary),
        insights = emptyList(),
    )

    private fun summaryForConsumptionRise(
        stats: InsightStats,
        shortPct: Double,
        res: Resources,
    ): String {
        if (shortPct >= 40.0 && stats.shortTripCount >= 2) {
            return res.getString(R.string.local_insight_consumption_up_summary_short)
        }
        if (stats.prevAvgCons > 0) {
            return res.getString(
                R.string.local_insight_consumption_up_summary,
                stats.prevAvgCons,
                stats.recentAvgCons,
            )
        }
        return res.getString(
            R.string.local_insight_consumption_stable_summary,
            stats.recentAvgCons,
        )
    }
}
