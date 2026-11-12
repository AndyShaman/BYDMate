package com.bydmate.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.bydmate.app.data.local.entity.BatterySnapshotEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BatterySnapshotDao {
    @Insert
    suspend fun insert(snapshot: BatterySnapshotEntity): Long

    @Query("SELECT * FROM battery_snapshots ORDER BY timestamp DESC")
    fun getAll(): Flow<List<BatterySnapshotEntity>>

    @Query("SELECT * FROM battery_snapshots ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLast(): BatterySnapshotEntity?
}
