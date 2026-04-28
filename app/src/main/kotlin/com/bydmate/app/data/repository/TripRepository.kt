package com.bydmate.app.data.repository

import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.dao.TripPointDao
import com.bydmate.app.data.local.dao.TripSummary
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.local.entity.TripPointEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TripRepository @Inject constructor(
    private val tripDao: TripDao,
    private val tripPointDao: TripPointDao
) {
    // Cached EMA consumption (kWh/100km). Null = needs recompute.
    // Shared between DashboardViewModel and TrackingService via @Singleton.
    @Volatile private var cachedEmaConsumption: Double? = null
    private val emaMutex = Mutex()

    suspend fun insertTrip(trip: TripEntity): Long {
        val id = tripDao.insert(trip)
        invalidateEmaCache()
        return id
    }

    suspend fun updateTrip(trip: TripEntity) {
        tripDao.update(trip)
        invalidateEmaCache()
    }

    suspend fun getTripById(id: Long): TripEntity? = tripDao.getById(id)

    fun getAllTrips(): Flow<List<TripEntity>> = tripDao.getAll()

    fun getTripsByDateRange(from: Long, to: Long): Flow<List<TripEntity>> =
        tripDao.getByDateRange(from, to)

    suspend fun getTodaySummary(dayStart: Long, dayEnd: Long): TripSummary =
        tripDao.getTodaySummary(dayStart, dayEnd)

    fun getLastTrip(): Flow<TripEntity?> = tripDao.getLastTrip()

    fun getRecentTrips(limit: Int = 5): Flow<List<TripEntity>> = tripDao.getRecent(limit)

    suspend fun getTripCount(): Int = tripDao.getCount()

    suspend fun getRecentAvgConsumption(): Double {
        val summary = tripDao.getRecentSummary()
        return if (summary.totalKm > 0) summary.totalKwh / summary.totalKm * 100.0 else 0.0
    }

    /**
     * Exponential Moving Average of consumption (kWh/100km) over recent trips.
     * Reacts to style/season changes without flipping on outliers.
     * Formula: ema_n = alpha * trip_n + (1 - alpha) * ema_{n-1}, oldest → newest.
     * Excludes trips < 1 km and consumption > 50 kWh/100km (sanity ceiling).
     * Cached via @Singleton; invalidated on insertTrip/updateTrip.
     */
    suspend fun getEmaConsumption(alpha: Double = 0.3, limit: Int = 10): Double = emaMutex.withLock {
        cachedEmaConsumption?.let { return it }
        val trips = tripDao.getRecentForEma(limit)
        if (trips.isEmpty()) {
            cachedEmaConsumption = 0.0
            return 0.0
        }
        val ordered = trips.asReversed()
        val first = ordered.first()
        var ema = (first.kwhConsumed ?: 0.0) / (first.distanceKm ?: 1.0) * 100.0
        for (t in ordered.drop(1)) {
            val km = t.distanceKm ?: continue
            val kwh = t.kwhConsumed ?: continue
            if (km <= 0.0) continue
            val c = kwh / km * 100.0
            ema = alpha * c + (1.0 - alpha) * ema
        }
        cachedEmaConsumption = ema
        ema
    }

    /**
     * EMA of consumption (kWh/100km) over trips from the last N days.
     * Uses the same formula as [getEmaConsumption] but bounded by time window —
     * reacts to recent driving style / season shifts.
     *
     * Returns 0.0 when no eligible trips (cold install, all trips too old).
     */
    suspend fun getWeeklyEmaConsumption(windowDays: Int = 7, alpha: Double = 0.3): Double {
        val fromTs = System.currentTimeMillis() - windowDays * 24L * 60L * 60L * 1000L
        val trips = tripDao.getForEmaSince(fromTs)
        if (trips.isEmpty()) return 0.0
        var ema: Double? = null
        for (t in trips.asReversed()) {
            val km = t.distanceKm ?: continue
            val kwh = t.kwhConsumed ?: continue
            if (km <= 0.0) continue
            val c = kwh / km * 100.0
            ema = if (ema == null) c else alpha * c + (1.0 - alpha) * ema
        }
        return ema ?: 0.0
    }

    /**
     * EMA of consumption (kWh/100km) over the most recent [count] trips
     * with distanceKm >= [minKm]. Used as baseline for widget trend arrow —
     * calendar-independent, so a quiet week doesn't inflate the number.
     *
     * Returns 0.0 when fewer than 3 eligible trips — caller should fall back
     * to getWeeklyEmaConsumption() (cold install / sparse history).
     */
    suspend fun getRecentTripsEmaConsumption(
        count: Int = 10,
        minKm: Double = 1.0,
        alpha: Double = 0.3,
    ): Double {
        val trips = tripDao.getRecentForEmaFiltered(minKm, count)
        if (trips.size < 3) return 0.0
        var ema: Double? = null
        for (t in trips.asReversed()) {
            val km = t.distanceKm ?: continue
            val kwh = t.kwhConsumed ?: continue
            if (km <= 0.0) continue
            val c = kwh / km * 100.0
            ema = if (ema == null) c else alpha * c + (1.0 - alpha) * ema
        }
        return ema ?: 0.0
    }

    /**
     * Weighted historical consumption (kWh/100km) over the most recent trips with
     * distance_km >= [minKm]. Used as the historical component for range estimation.
     *
     * Trips are taken newest-first, paired with [weights] in order. If fewer trips
     * qualify than weights, weights are renormalized over available trips. If no
     * trips qualify, returns [fallback].
     *
     * Default weights 50/30/20 mirror Rivian's published approach.
     */
    suspend fun getWeightedHistoricalAvg(
        minKm: Double = 3.0,
        weights: List<Double> = listOf(0.5, 0.3, 0.2),
        fallback: Double = 18.0,
    ): Double {
        require(weights.isNotEmpty()) { "weights must not be empty" }
        val trips = tripDao.getRecentForEmaFiltered(minKm = minKm, limit = weights.size)
        val tripAvgs = trips.mapNotNull { t ->
            val km = t.distanceKm ?: return@mapNotNull null
            val kwh = t.kwhConsumed ?: return@mapNotNull null
            if (km <= 0.0 || kwh < 0.0) return@mapNotNull null
            kwh / km * 100.0
        }
        if (tripAvgs.isEmpty()) return fallback
        val active = weights.take(tripAvgs.size)
        val sumW = active.sum()
        if (sumW <= 0.0) return fallback
        var avg = 0.0
        for (i in tripAvgs.indices) {
            avg += tripAvgs[i] * (active[i] / sumW)
        }
        return avg
    }

    /**
     * Average consumption (kWh/100km) of the most recent eligible completed trip.
     * Used by the floating widget's big-number when no active trip is in progress
     * and during the first 500 m of a freshly started trip (smooth fade from
     * "your last drive" to "your current drive").
     *
     * Eligible: distanceKm >= 1.0 AND kwhConsumed > 0 (filtering done in DAO).
     * Returns null when no eligible trip exists (cold install).
     */
    suspend fun getLastTripAvgConsumption(): Double? {
        val pick = tripDao.getRecentForEma(limit = 1).firstOrNull() ?: return null
        val km = pick.distanceKm ?: return null
        val kwh = pick.kwhConsumed ?: return null
        if (km <= 0.0) return null
        return kwh / km * 100.0
    }

    private fun invalidateEmaCache() {
        cachedEmaConsumption = null
    }

    suspend fun insertTripPoints(points: List<TripPointEntity>) =
        tripPointDao.insertAll(points)

    suspend fun getTripPoints(tripId: Long): List<TripPointEntity> =
        tripPointDao.getByTripId(tripId)

    suspend fun getTripPointsByTripIds(tripIds: List<Long>): Map<Long, List<TripPointEntity>> =
        tripPointDao.getByTripIds(tripIds).groupBy { it.tripId }

    suspend fun getPeriodSummary(from: Long, to: Long) = tripDao.getPeriodSummary(from, to)

    suspend fun getTripPointsByTimeRange(from: Long, to: Long): List<TripPointEntity> =
        tripPointDao.getByTimeRange(from, to)

    suspend fun getTripsForCapacityEstimate(): List<TripEntity> =
        tripDao.getTripsForCapacityEstimate()
}
