package com.bydmate.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.bydmate.app.data.local.entity.CounterSnapshotEntity

@Dao
interface CounterSnapshotDao {

    @Insert
    suspend fun insert(snapshot: CounterSnapshotEntity): Long

    /**
     * The snapshot closest to [ts] within ±[windowMs] that carries a counter, or null when none
     * is that close. A row with both counters null is no trip edge and is skipped.
     */
    @Query("""
        SELECT * FROM counter_snapshots
        WHERE ts BETWEEN :ts - :windowMs AND :ts + :windowMs
          AND (fuel_total IS NOT NULL OR mileage_hev IS NOT NULL)
        ORDER BY ABS(ts - :ts) ASC, ts ASC
        LIMIT 1
    """)
    suspend fun nearest(ts: Long, windowMs: Long): CounterSnapshotEntity?

    @Query("DELETE FROM counter_snapshots WHERE ts < :ts")
    suspend fun deleteOlderThan(ts: Long)
}
