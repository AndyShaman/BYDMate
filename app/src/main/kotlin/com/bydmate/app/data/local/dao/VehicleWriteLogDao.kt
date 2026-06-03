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
}
