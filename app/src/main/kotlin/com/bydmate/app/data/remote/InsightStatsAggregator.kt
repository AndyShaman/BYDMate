package com.bydmate.app.data.remote

import com.bydmate.app.data.local.entity.ChargeEntity
import com.bydmate.app.data.local.entity.IdleDrainEntity
import java.util.Calendar

/** Pure helpers for [InsightStats] aggregation — unit-testable without Room. */
object InsightStatsAggregator {

    data class ChargingWeek(
        val acKwh: Double,
        val dcKwh: Double,
        val acSessions: Int,
        val dcSessions: Int,
        val acCostPerKwh: Double?,
        val dcCostPerKwh: Double?,
        val totalCost: Double,
    )

    /** Idle sessions that started between 22:00 and 05:59 local time. */
    fun nightDrainKwh(drains: List<IdleDrainEntity>): Double {
        val cal = Calendar.getInstance()
        return drains.sumOf { drain ->
            val kwh = drain.kwhConsumed ?: return@sumOf 0.0
            if (isNightHour(cal, drain.startTs)) kwh else 0.0
        }
    }

    fun isNightHour(cal: Calendar, timestampMs: Long): Boolean {
        cal.timeInMillis = timestampMs
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        return hour >= 22 || hour < 6
    }

    fun chargingWeek(charges: List<ChargeEntity>): ChargingWeek {
        val ac = charges.filter { it.type.equals("AC", ignoreCase = true) }
        val dc = charges.filter { it.type.equals("DC", ignoreCase = true) }
        val acKwh = ac.sumOf { it.kwhCharged ?: 0.0 }
        val dcKwh = dc.sumOf { it.kwhCharged ?: 0.0 }
        val acCost = ac.sumOf { it.cost ?: 0.0 }
        val dcCost = dc.sumOf { it.cost ?: 0.0 }
        return ChargingWeek(
            acKwh = acKwh,
            dcKwh = dcKwh,
            acSessions = ac.size,
            dcSessions = dc.size,
            acCostPerKwh = if (acKwh > 0.1 && acCost > 0) acCost / acKwh else null,
            dcCostPerKwh = if (dcKwh > 0.1 && dcCost > 0) dcCost / dcKwh else null,
            totalCost = acCost + dcCost,
        )
    }
}
