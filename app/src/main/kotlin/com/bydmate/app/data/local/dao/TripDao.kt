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

    @Query("SELECT * FROM trips WHERE cost IS NULL AND kwh_consumed IS NOT NULL")
    suspend fun getTripsWithoutCost(): List<TripEntity>

    @Query("""
        SELECT COALESCE(SUM(distance_km), 0.0) as totalKm,
               COALESCE(SUM(kwh_consumed), 0.0) as totalKwh,
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
               COUNT(*) as tripCount,
               COALESCE(SUM(cost), 0.0) as totalCost
        FROM (
            SELECT kwh_consumed, distance_km, cost FROM trips
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
}

data class TripSummary(
    val totalKm: Double,
    val totalKwh: Double,
    val tripCount: Int = 0,
    val totalCost: Double = 0.0
)
