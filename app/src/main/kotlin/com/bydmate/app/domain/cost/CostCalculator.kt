package com.bydmate.app.domain.cost

import android.util.Log
import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.local.dao.TariffPeriodDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.entity.TariffPeriodEntity
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.local.entity.TariffPeriodEntity.Companion.TRIP_RULE_CHARGES
import com.bydmate.app.data.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/** What one [CostCalculator.recalculate] pass touched. */
data class RecalcResult(
    val charges: Int = 0,
    val trips: Int = 0,
    val skippedManual: Int = 0,
)

/** Measured AC/DC losses derived from the charges where a meter reading was typed in. */
data class MeasuredLosses(
    val acPct: Double?,
    val acSamples: Int,
    val dcPct: Double?,
    val dcSamples: Int,
) {
    val hasAny: Boolean get() = acSamples > 0 || dcSamples > 0
}

/**
 * The single place where a price is attached to a charge or a trip. Everything else —
 * the charging detector, the voice agent, the history importer, the settings screen —
 * goes through here, so a rate change made retroactively lands everywhere at once.
 */
@Singleton
class CostCalculator @Inject constructor(
    private val tariffPeriodDao: TariffPeriodDao,
    private val chargeDao: ChargeDao,
    private val tripDao: TripDao,
    private val settingsRepository: SettingsRepository,
) {

    /**
     * Current periods. A fresh install has none (the seeding migration only runs on an
     * upgrade), so the first read creates one out of the flat tariff settings.
     */
    suspend fun schedule(): TariffSchedule {
        val stored = tariffPeriodDao.getAllAsc()
        if (stored.isNotEmpty()) return TariffSchedule(stored)
        val seed = TariffPeriodEntity(
            startTs = 0L,
            homeRate = settingsRepository.getHomeTariff(),
            dcRate = settingsRepository.getDcTariff(),
            tripRule = settingsRepository.getTripCostTariffKey(),
        )
        tariffPeriodDao.insert(seed)
        Log.i(TAG, "period seeded start=0 home=${seed.homeRate} dc=${seed.dcRate} " +
            "loss=${seed.acLossPct}/${seed.dcLossPct} rule=${seed.tripRule}")
        return TariffSchedule(tariffPeriodDao.getAllAsc())
    }

    /** Price for a charge that is being created right now (detector, agent, manual add). */
    suspend fun costForNewCharge(
        startTs: Long,
        type: String?,
        kwhCharged: Double?,
        meterKwh: Double? = null,
    ): Double? {
        val period = schedule().periodAt(startTs) ?: return null
        val cost = TariffSchedule.chargeCost(period, type, kwhCharged, meterKwh)
        Log.d(TAG, "charge cost ts=$startTs type=$type kwh=$kwhCharged meter=$meterKwh " +
            "rate=${TariffSchedule.rateFor(period, type)} loss=${TariffSchedule.lossPctFor(period, type)}% -> $cost")
        return cost
    }

    /**
     * Re-prices every charge and trip in [fromTs]..[toTs] against the current periods.
     * Charges the user priced by hand are left alone. Charges go first: the trips priced by
     * the `charges` rule read the costs this pass just wrote.
     */
    suspend fun recalculate(fromTs: Long, toTs: Long): RecalcResult {
        Log.i(TAG, "recalc start from=$fromTs to=$toTs")
        val schedule = schedule()
        if (schedule.isEmpty) {
            Log.w(TAG, "recalc skipped: no tariff periods")
            return RecalcResult()
        }

        val chargePass = repriceCharges(schedule, fromTs, toTs)

        val capacity = settingsRepository.getBatteryCapacity()
        // Built after the charge pass so the weighted price sees the new costs, and from the
        // FULL history so a trip at the start of the range is still priced off older charges.
        val timeline = BatteryPriceTimeline(chargeDao.getCompletedForPricing(toTs), capacity)
        val tripsUpdated = repriceTrips(schedule, timeline, fromTs, toTs)

        Log.i(TAG, "recalc from=$fromTs to=$toTs charges=${chargePass.charges} trips=$tripsUpdated " +
            "skippedManual=${chargePass.skippedManual}")
        return chargePass.copy(trips = tripsUpdated)
    }

    /** Charge half of [recalculate]; the trip count of the result stays at zero. */
    private suspend fun repriceCharges(
        schedule: TariffSchedule,
        fromTs: Long,
        toTs: Long,
    ): RecalcResult {
        var updated = 0
        var skippedManual = 0
        for (charge in chargeDao.getInRangeAsc(fromTs, toTs)) {
            if (charge.costManual) {
                skippedManual++
                continue
            }
            val cost = schedule.periodAt(charge.startTs)?.let { period ->
                TariffSchedule.chargeCost(period, charge.type, charge.kwhCharged, charge.meterKwh)
            }
            if (cost != null && charge.cost != cost) {
                chargeDao.update(charge.copy(cost = cost))
                updated++
            }
        }
        return RecalcResult(charges = updated, skippedManual = skippedManual)
    }

    /** Trip half of [recalculate]; returns how many rows changed price. */
    private suspend fun repriceTrips(
        schedule: TariffSchedule,
        timeline: BatteryPriceTimeline,
        fromTs: Long,
        toTs: Long,
    ): Int {
        var updated = 0
        for (trip in tripDao.getWithEnergyInRange(fromTs, toTs)) {
            val kwh = trip.kwhConsumed
            val period = schedule.periodAt(trip.startTs)
            if (kwh == null || period == null) continue
            val cost = kwh * tripRate(period, trip.startTs, timeline)
            if (trip.cost != cost) {
                tripDao.update(trip.copy(cost = cost))
                updated++
            }
        }
        return updated
    }

    /**
     * Prices trips that carry energy but no cost yet, each by its own period. Trips that
     * already have a cost are left untouched — this is the import path, not a recalculation.
     */
    suspend fun priceUncostedTrips(trips: List<TripEntity>): Int {
        val priceable = trips.filter { it.kwhConsumed != null && it.cost == null }
        if (priceable.isEmpty()) return 0
        val schedule = schedule()
        if (schedule.isEmpty) return 0
        val timeline = BatteryPriceTimeline(
            chargeDao.getCompletedForPricing(priceable.maxOf { it.startTs }),
            settingsRepository.getBatteryCapacity(),
        )
        var priced = 0
        for (trip in priceable) {
            val kwh = trip.kwhConsumed
            val period = schedule.periodAt(trip.startTs)
            if (kwh == null || period == null) continue
            tripDao.update(trip.copy(cost = kwh * tripRate(period, trip.startTs, timeline)))
            priced++
        }
        Log.i(TAG, "priced uncosted trips=$priced")
        return priced
    }

    /** Price per kWh for one trip under [period]; logs the `charges` rule decision. */
    private fun tripRate(
        period: TariffPeriodEntity,
        tripStartTs: Long,
        timeline: BatteryPriceTimeline,
    ): Double {
        TariffSchedule.fixedTripRate(period)?.let { return it }
        val priced = timeline.tripPriceAt(tripStartTs)
        val rate = priced?.pricePerKwh ?: period.homeRate
        Log.d(TAG, "trip cost rule=$TRIP_RULE_CHARGES ts=$tripStartTs price=$rate " +
            "fallback=${priced == null}")
        return rate
    }

    /**
     * Average losses the driver actually measured: for every session with a meter reading,
     * `1 - pack intake / meter`. Feeds the hint under the periods list; null when nothing
     * of that type was ever measured.
     */
    suspend fun measuredLosses(): MeasuredLosses {
        val samples = chargeDao.getWithMeterReading()
            .mapNotNull { charge ->
                val meter = charge.meterKwh ?: return@mapNotNull null
                val kwh = charge.kwhCharged ?: return@mapNotNull null
                if (meter <= kwh || meter <= 0.0) return@mapNotNull null
                TariffSchedule.isDc(charge.type) to (1.0 - kwh / meter) * 100.0
            }
        val ac = samples.filterNot { it.first }.map { it.second }
        val dc = samples.filter { it.first }.map { it.second }
        return MeasuredLosses(
            acPct = ac.takeIf { it.isNotEmpty() }?.average(),
            acSamples = ac.size,
            dcPct = dc.takeIf { it.isNotEmpty() }?.average(),
            dcSamples = dc.size,
        )
    }

    companion object {
        const val TAG = "TARIFF"
    }
}
