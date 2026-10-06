package com.bydmate.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.bydmate.app.data.local.entity.VehicleWriteLogEntity

@Dao
interface VehicleWriteLogDao {
    @Insert suspend fun insert(entity: VehicleWriteLogEntity)

    @Query("SELECT * FROM vehicle_write_log ORDER BY ts DESC LIMIT 200")
    suspend fun getLast200(): List<VehicleWriteLogEntity>

    /** The [limit] newest rows, newest first: the dump's vehicle writes section. */
    @Query("SELECT * FROM vehicle_write_log ORDER BY id DESC LIMIT :limit")
    suspend fun getLatest(limit: Int): List<VehicleWriteLogEntity>

    /** Keeps the [keep] newest rows; returns how many were deleted. A diagnostics table: nothing
     *  else bounds it. */
    @Query("DELETE FROM vehicle_write_log WHERE id NOT IN (SELECT id FROM vehicle_write_log ORDER BY id DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int): Int
}
