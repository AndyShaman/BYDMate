package com.bydmate.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.bydmate.app.data.local.entity.ChargePointEntity

@Dao
interface ChargePointDao {
    @Insert
    suspend fun insertAll(points: List<ChargePointEntity>)

    @Query("SELECT * FROM charge_points WHERE charge_id = :chargeId ORDER BY timestamp ASC")
    suspend fun getByChargeId(chargeId: Long): List<ChargePointEntity>
}
