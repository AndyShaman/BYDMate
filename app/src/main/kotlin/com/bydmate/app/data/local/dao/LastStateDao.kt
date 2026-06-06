package com.bydmate.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bydmate.app.data.local.entity.LastStateEntity

@Dao
interface LastStateDao {
    @Query("SELECT * FROM last_state WHERE id = 1")
    suspend fun getCurrent(): LastStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: LastStateEntity)

    /**
     * Mark a new open trip in last_state. Writes to row id=1 (creating it if absent
     * via UPSERT semantics on the caller's side — most callers will read getCurrent()
     * first and merge). Called by TripRecorder on trip open.
     *
     * Uses COALESCE to preserve an existing openTripId if one is already set.
     * Returns the number of rows affected (0 if row id=1 does not exist yet).
     */
    @Query(
        """
        UPDATE last_state
        SET open_trip_id = COALESCE(open_trip_id, :startTs),
            trip_start_ts = :startTs,
            trip_start_soc = :startSoc,
            trip_start_mileage = :startMileage,
            ts = :now
        WHERE id = 1
        """
    )
    suspend fun openTrip(startTs: Long, startSoc: Int?, startMileage: Double?, now: Long): Int

    @Query(
        """
        UPDATE last_state
        SET open_trip_id = NULL,
            trip_start_ts = NULL,
            trip_start_soc = NULL,
            trip_start_mileage = NULL
        WHERE id = 1
        """
    )
    suspend fun clearOpenTrip()
}
