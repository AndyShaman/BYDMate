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
}

data class TripSummary(
    val totalKm: Double,
    val totalKwh: Double,
    val tripCount: Int = 0,
    val totalCost: Double = 0.0
)
