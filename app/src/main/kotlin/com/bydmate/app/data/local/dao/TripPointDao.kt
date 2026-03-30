package com.bydmate.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.bydmate.app.data.local.entity.TripPointEntity

@Dao
interface TripPointDao {
    @Insert
    suspend fun insertAll(points: List<TripPointEntity>)

    @Query("SELECT * FROM trip_points WHERE trip_id = :tripId ORDER BY timestamp ASC")
    suspend fun getByTripId(tripId: Long): List<TripPointEntity>

    @Query("SELECT * FROM trip_points WHERE trip_id IN (:tripIds) ORDER BY trip_id, timestamp ASC")
    suspend fun getByTripIds(tripIds: List<Long>): List<TripPointEntity>

    @Query("SELECT COUNT(*) FROM trip_points")
    suspend fun getCount(): Int

    /**
     * Delete old points keeping every Nth one per trip.
     * Points older than [cutoff] are thinned to ~1 per [intervalMs].
     */
    @Query("""
        DELETE FROM trip_points
        WHERE timestamp < :cutoff
        AND id NOT IN (
            SELECT MIN(id) FROM trip_points
            WHERE timestamp < :cutoff
            GROUP BY trip_id, (timestamp / :intervalMs)
        )
    """)
    suspend fun thinOldPoints(cutoff: Long, intervalMs: Long): Int

    @Query("SELECT * FROM trip_points WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp ASC")
    suspend fun getByTimeRange(from: Long, to: Long): List<TripPointEntity>

    @Query("UPDATE trip_points SET trip_id = :tripId WHERE timestamp BETWEEN :from AND :to AND trip_id = 0")
    suspend fun attachToTrip(tripId: Long, from: Long, to: Long): Int

    @Insert
    suspend fun insert(point: TripPointEntity): Long
}
