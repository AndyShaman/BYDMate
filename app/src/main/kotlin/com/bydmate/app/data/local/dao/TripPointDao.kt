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
}
