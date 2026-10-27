package com.bydmate.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bydmate.app.data.local.entity.TripTombstoneEntity

@Dao
interface TripTombstoneDao {
    // REPLACE: a tombstone left for an id is outdated once a trip holding that id again is deleted (#348)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tombstone: TripTombstoneEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM trip_tombstones WHERE byd_id = :bydId)")
    suspend fun exists(bydId: Long): Boolean

    /**
     * True when the tombstone for [bydId] was left by another trip that had this id (#348): its
     * start is outside [windowMs] of [startTs], or it has no start and [ignoreLegacy] is set.
     */
    @Query("""
        SELECT EXISTS(SELECT 1 FROM trip_tombstones WHERE byd_id = :bydId AND
            CASE WHEN start_ts IS NULL THEN :ignoreLegacy ELSE ABS(start_ts - :startTs) > :windowMs END)
    """)
    suspend fun isForAnotherTrip(bydId: Long, startTs: Long, windowMs: Long, ignoreLegacy: Boolean): Boolean
}
