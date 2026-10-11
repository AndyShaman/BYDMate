package com.bydmate.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.bydmate.app.data.local.entity.CounterSnapshotEntity

@Dao
interface CounterSnapshotDao {

    @Insert
    suspend fun insert(snapshot: CounterSnapshotEntity): Long

    /** The snapshot closest to [ts] within ±[windowMs], or null when none is that close. */
    @Query("""
        SELECT * FROM counter_snapshots
        WHERE ts BETWEEN :ts - :windowMs AND :ts + :windowMs
        ORDER BY ABS(ts - :ts) ASC, ts ASC
        LIMIT 1
    """)
    suspend fun nearest(ts: Long, windowMs: Long): CounterSnapshotEntity?

    @Query("DELETE FROM counter_snapshots WHERE ts < :ts")
    suspend fun deleteOlderThan(ts: Long)
}
