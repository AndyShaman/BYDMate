package com.bydmate.app.domain.cost

import com.bydmate.app.data.local.entity.ChargeEntity
import com.bydmate.app.data.local.entity.TariffPeriodEntity
import com.bydmate.app.data.local.entity.TariffPeriodEntity.Companion.TRIP_RULE_CHARGES
import com.bydmate.app.data.local.entity.TariffPeriodEntity.Companion.TRIP_RULE_DC
import com.bydmate.app.data.local.entity.TariffPeriodEntity.Companion.TRIP_RULE_HOME

/** Price of one kWh in the pack at a point in time, and where it came from. */
data class BatteryPrice(
    /** Finish of the charge the price was computed at. */
    val sourceTs: Long,
    val pricePerKwh: Double,
)

/**
 * Pure, DB-free view of the user's tariff periods. Nothing here touches Room, so every
 * pricing rule is unit-testable on plain lists.
 */
class TariffSchedule(periods: List<TariffPeriodEntity>) {

    /** Ascending by start; the earliest period also covers everything before its start. */
    val periods: List<TariffPeriodEntity> = periods.sortedBy { it.startTs }

    val isEmpty: Boolean get() = periods.isEmpty()

    /**
     * The period covering [ts]: the last one that started at or before it. Timestamps older
     * than the first period fall back to that first period, so history is never unpriced.
     */
    fun periodAt(ts: Long): TariffPeriodEntity? =
        periods.lastOrNull { it.startTs <= ts } ?: periods.firstOrNull()

    /** Cost of a charge under the period covering its start; null when the kWh is unknown. */
    fun chargeCost(startTs: Long, type: String?, kwhCharged: Double?, meterKwh: Double?): Double? {
        val period = periodAt(startTs) ?: return null
        return chargeCost(period, type, kwhCharged, meterKwh)
    }

    companion object {
        /** A trip priced by the `charges` rule ignores charges older than this. */
        const val CHARGES_RULE_MAX_AGE_MS = 60L * 24 * 60 * 60 * 1000

        fun isDc(type: String?): Boolean = type.equals("DC", ignoreCase = true)

        fun lossPctFor(period: TariffPeriodEntity, type: String?): Double =
            (if (isDc(type)) period.dcLossPct else period.acLossPct).coerceIn(0.0, 90.0)

        fun rateFor(period: TariffPeriodEntity, type: String?): Double =
            if (isDc(type)) period.dcRate else period.homeRate

        /**
         * Energy actually paid for: the meter reading when the driver typed one in, otherwise
         * the pack intake grossed up by the period's losses (the on-board charger and the
         * cable eat a share of every kWh that leaves the wall socket).
         */
        fun energyPaid(period: TariffPeriodEntity, type: String?, kwhCharged: Double?, meterKwh: Double?): Double? {
            if (meterKwh != null && meterKwh > 0.0) return meterKwh
            val kwh = kwhCharged?.takeIf { it > 0.0 } ?: return null
            return kwh / (1.0 - lossPctFor(period, type) / 100.0)
        }

        fun chargeCost(
            period: TariffPeriodEntity,
            type: String?,
            kwhCharged: Double?,
            meterKwh: Double?,
        ): Double? = energyPaid(period, type, kwhCharged, meterKwh)?.let { it * rateFor(period, type) }

        /**
         * Trip price per kWh for the fixed rules. `charges` is not decided here — it needs the
         * charge history, see [BatteryPriceTimeline] — so it returns null and the caller falls
         * back to the period's home rate.
         */
        fun fixedTripRate(period: TariffPeriodEntity): Double? = when (period.tripRule) {
            TRIP_RULE_HOME -> period.homeRate
            TRIP_RULE_DC -> period.dcRate
            TRIP_RULE_CHARGES -> null
            else -> period.tripRule.replace(',', '.').trim().toDoubleOrNull() ?: period.homeRate
        }
    }
}

/**
 * Weighted-average price of one kWh sitting in the pack, walked forward over the charge
 * history: every completed session mixes its own price into whatever was left in the
 * battery (`soc_start × capacity`), exactly like topping up a tank at a new price.
 *
 * [charges] must be completed, ascending by start, with a kWh figure and a cost.
 */
class BatteryPriceTimeline(charges: List<ChargeEntity>, capacityKwh: Double) {

    private val points: List<BatteryPrice> = buildList {
        var price: Double? = null
        for (charge in charges) {
            val kwh = charge.kwhCharged?.takeIf { it > 0.0 }
            val cost = charge.cost
            if (kwh == null || cost == null) continue
            val remainKwh = ((charge.socStart ?: 0).coerceIn(0, 100)) / 100.0 * capacityKwh
            val previous = price
            price = if (previous == null || remainKwh <= 0.0) cost / kwh
            else (remainKwh * previous + cost) / (remainKwh + kwh)
            // A charge can only influence trips that start after it ended.
            add(BatteryPrice(sourceTs = charge.endTs ?: charge.startTs, pricePerKwh = price))
        }
    }.sortedBy { it.sourceTs }

    /** Latest price at or before [ts], or null when nothing was charged yet. */
    fun priceAt(ts: Long): BatteryPrice? = points.lastOrNull { it.sourceTs <= ts }

    /**
     * Price for a trip starting at [ts]: null when the last charge is older than
     * [TariffSchedule.CHARGES_RULE_MAX_AGE_MS], which means the caller must fall back.
     */
    fun tripPriceAt(ts: Long): BatteryPrice? =
        priceAt(ts)?.takeIf { ts - it.sourceTs <= TariffSchedule.CHARGES_RULE_MAX_AGE_MS }
}
