package com.bydmate.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.bydmate.app.data.local.entity.TripEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {
    @Insert
    suspend fun insert(trip: TripEntity): Long

    @Update
    suspend fun update(trip: TripEntity)

    @Query("SELECT * FROM trips ORDER BY start_ts DESC")
    fun getAll(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun getById(id: Long): TripEntity?

    @Query("SELECT * FROM trips WHERE start_ts >= :from AND start_ts <= :to ORDER BY start_ts DESC")
    fun getByDateRange(from: Long, to: Long): Flow<List<TripEntity>>

    @Query("""
        SELECT COALESCE(SUM(distance_km), 0.0) as totalKm,
               COALESCE(SUM(kwh_consumed), 0.0) as totalKwh,
               COALESCE(SUM(fuel_liters), 0.0) as totalFuelLiters,
               COUNT(*) as tripCount,
               COALESCE(SUM(cost), 0.0) as totalCost
        FROM trips
        WHERE start_ts >= :dayStart AND start_ts <= :dayEnd
    """)
    suspend fun getTodaySummary(dayStart: Long, dayEnd: Long): TripSummary

    @Query("SELECT * FROM trips ORDER BY start_ts DESC LIMIT 1")
    fun getLastTrip(): Flow<TripEntity?>

    @Query("SELECT * FROM trips ORDER BY start_ts DESC LIMIT :limit")
    fun getRecent(limit: Int = 5): Flow<List<TripEntity>>

    @Query("SELECT COUNT(*) FROM trips")
    suspend fun getCount(): Int

    @Query("SELECT * FROM trips WHERE byd_id = :bydId LIMIT 1")
    suspend fun getByBydId(bydId: Long): TripEntity?

    @Query("SELECT * FROM trips WHERE soc_start IS NULL AND source = 'energydata'")
    suspend fun getTripsWithoutSoc(): List<TripEntity>

    @Query("SELECT * FROM trips WHERE cost IS NULL AND (kwh_consumed IS NOT NULL OR fuel_liters IS NOT NULL)")
    suspend fun getTripsWithoutCost(): List<TripEntity>

    @Query("""
        SELECT COALESCE(SUM(distance_km), 0.0) as totalKm,
               COALESCE(SUM(kwh_consumed), 0.0) as totalKwh,
               COALESCE(SUM(fuel_liters), 0.0) as totalFuelLiters,
               COUNT(*) as tripCount,
               COALESCE(SUM(cost), 0.0) as totalCost
        FROM trips
        WHERE start_ts >= :from AND start_ts <= :to
    """)
    suspend fun getPeriodSummary(from: Long, to: Long): TripSummary

    @Query("SELECT * FROM trips WHERE source = 'live'")
    suspend fun getLiveTrips(): List<TripEntity>

    @Query("SELECT * FROM trips WHERE start_ts >= :minTs AND start_ts <= :maxTs LIMIT 1")
    suspend fun getByStartTsRange(minTs: Long, maxTs: Long): TripEntity?

    @Query("SELECT * FROM trips ORDER BY start_ts")
    suspend fun getAllSnapshot(): List<TripEntity>

    @Query("DELETE FROM trips WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM trips WHERE COALESCE(distance_km, 0.0) = 0.0 AND source = 'energydata'")
    suspend fun deleteZeroKmTrips(): Int

    /** Trips with sufficient SOC delta for battery capacity estimation. */
    @Query("""
        SELECT * FROM trips
        WHERE soc_start IS NOT NULL AND soc_end IS NOT NULL
          AND kwh_consumed IS NOT NULL AND kwh_consumed > 0
          AND (soc_start - soc_end) >= :minSocDelta
        ORDER BY start_ts DESC LIMIT :limit
    """)
    suspend fun getTripsForCapacityEstimate(minSocDelta: Int = 10, limit: Int = 20): List<TripEntity>

    @Query("""
        SELECT COALESCE(SUM(kwh_consumed), 0.0) as totalKwh,
               COALESCE(SUM(distance_km), 0.0) as totalKm,
               COALESCE(SUM(fuel_liters), 0.0) as totalFuelLiters,
               COUNT(*) as tripCount,
               COALESCE(SUM(cost), 0.0) as totalCost
        FROM (
            SELECT kwh_consumed, distance_km, fuel_liters, cost FROM trips
            WHERE distance_km > 0 AND kwh_consumed > 0
            ORDER BY start_ts DESC LIMIT :maxTrips
        )
    """)
    suspend fun getRecentSummary(maxTrips: Int = 30): TripSummary

    @Query("""
        SELECT * FROM trips
        WHERE distance_km >= 1
          AND kwh_consumed > 0
          AND (kwh_consumed * 100.0 / distance_km) <= 50
        ORDER BY start_ts DESC LIMIT :limit
    """)
    suspend fun getRecentForEma(limit: Int = 10): List<TripEntity>

    @Query("""
        SELECT * FROM trips
        WHERE distance_km >= 2 AND kwh_consumed > 0 AND start_ts >= :fromTs
        ORDER BY start_ts DESC
    """)
    suspend fun getForEmaSince(fromTs: Long): List<TripEntity>

    @Query("""
        SELECT * FROM trips
        WHERE distance_km >= :minKm AND kwh_consumed > 0
        ORDER BY start_ts DESC LIMIT :limit
    """)
    suspend fun getRecentForEmaFiltered(minKm: Double, limit: Int): List<TripEntity>

    /** Trip 1/2 counter aggregate since a reset anchor. Filter is by trip END so the
     *  trip a reset happened inside still lands in the counter whole — the caller
     *  subtracts the pre-reset correction ONLY once the straddling row actually lands
     *  (straddling* buckets), and never more than the straddling row contributed.
     *  Idle (parking drain) rows are zero-km trips written by HistoryImporter —
     *  their kWh/cost is inside the totals; the idle bucket only splits it out for
     *  display. Only driving rows are counted in straddling buckets: corrections always
     *  describe driving sessions, never idle drain.
     *  Landed-session buckets (landedSession*) = driving rows already written to Room
     *  for the current live session (start_ts >= :sessionStart). The live contribution
     *  in compute() is treated as the not-yet-landed remainder: live - landedSession,
     *  clamped >= 0. This prevents double-counting when TripRecorder closes a trip at
     *  the DRIVE->ACC transition while the live session is still active (powerState >= 1).
     *  Window integrity is guaranteed by the TrackingService liveWholeSession flag: when
     *  the flag is false the counter suppresses live km/kWh entirely (Room-only mode).
     *  @param sessionStart lower bound of the live-session window; Long.MAX_VALUE when no
     *  active session (DAO returns zero for all landedSession buckets). */
    @Query(
        """
        SELECT COALESCE(SUM(distance_km), 0.0) AS totalKm,
               COALESCE(SUM(kwh_consumed), 0.0) AS totalKwh,
               COALESCE(SUM(cost), 0.0) AS totalCost,
               COALESCE(SUM(CASE WHEN COALESCE(distance_km, 0) > 0 THEN COALESCE(kwh_consumed, 0) ELSE 0 END), 0.0) AS drivingKwh,
               COALESCE(SUM(CASE WHEN COALESCE(distance_km, 0) <= 0 THEN COALESCE(kwh_consumed, 0) ELSE 0 END), 0.0) AS idleKwh,
               COUNT(CASE WHEN COALESCE(distance_km, 0) > 0 THEN 1 END) AS tripCount,
               COALESCE(SUM(CASE WHEN COALESCE(distance_km, 0) > 0 AND end_ts IS NOT NULL THEN end_ts - start_ts ELSE 0 END), 0) AS drivingMs,
               COALESCE(SUM(CASE WHEN start_ts < :from AND COALESCE(distance_km, 0) > 0 THEN COALESCE(distance_km, 0) ELSE 0 END), 0.0) AS straddlingKm,
               COALESCE(SUM(CASE WHEN start_ts < :from AND COALESCE(distance_km, 0) > 0 THEN COALESCE(kwh_consumed, 0) ELSE 0 END), 0.0) AS straddlingKwh,
               COALESCE(SUM(CASE WHEN start_ts < :from AND COALESCE(distance_km, 0) > 0 AND end_ts IS NOT NULL THEN end_ts - start_ts ELSE 0 END), 0) AS straddlingMs,
               COUNT(CASE WHEN start_ts < :from AND COALESCE(distance_km, 0) > 0 THEN 1 END) AS straddlingTripCount,
               COALESCE(SUM(CASE WHEN start_ts >= :sessionStart AND COALESCE(distance_km, 0) > 0 THEN COALESCE(distance_km, 0) ELSE 0 END), 0.0) AS landedSessionKm,
               COALESCE(SUM(CASE WHEN start_ts >= :sessionStart AND COALESCE(distance_km, 0) > 0 THEN COALESCE(kwh_consumed, 0) ELSE 0 END), 0.0) AS landedSessionKwh,
               COALESCE(SUM(CASE WHEN start_ts >= :sessionStart AND COALESCE(distance_km, 0) > 0 AND end_ts IS NOT NULL THEN end_ts - start_ts ELSE 0 END), 0) AS landedSessionMs
        FROM trips WHERE COALESCE(end_ts, start_ts) >= :from
        """
    )
    fun observeCounterStats(from: Long, sessionStart: Long): Flow<TripCounterStats>
}

data class TripSummary(
    val totalKm: Double,
    val totalKwh: Double,
    val totalFuelLiters: Double = 0.0,
    val tripCount: Int = 0,
    val totalCost: Double = 0.0
)

data class TripCounterStats(
    val totalKm: Double,
    val totalKwh: Double,
    val totalCost: Double,
    val drivingKwh: Double,
    val idleKwh: Double,
    val tripCount: Int,
    val drivingMs: Long,
    /** Km contributed by the trip that straddled the reset anchor (start_ts < from, driving only).
     *  Zero when no such trip has landed yet. Used as the cap on Room-side correction subtraction
     *  so a lost straddling trip never makes the correction bite into later trips. */
    val straddlingKm: Double,
    /** kWh contributed by the straddling trip (same row as straddlingKm). */
    val straddlingKwh: Double,
    /** Duration ms contributed by the straddling trip (same row as straddlingKm). */
    val straddlingMs: Long,
    /** Number of driving rows straddling the reset anchor (same rows as straddlingKm);
     *  subtracted from tripCount when the reset excludes the straddling row whole. */
    val straddlingTripCount: Int,
    /** Km from driving rows already written to Room for the current live session
     *  (start_ts >= sessionStart). Subtracted from the live contribution so Room and live
     *  don't double-count the same trip when TripRecorder closes at DRIVE→ACC while the
     *  session is still alive. Zero when sessionStart = Long.MAX_VALUE (no active session). */
    val landedSessionKm: Double,
    /** kWh from driving rows already landed for the current live session. */
    val landedSessionKwh: Double,
    /** Duration ms from driving rows already landed for the current live session. */
    val landedSessionMs: Long,
)
