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

    @Query("SELECT COUNT(*) FROM charge_points")
    suspend fun getCount(): Int

    @Query("""
        DELETE FROM charge_points
        WHERE timestamp < :cutoff
        AND id NOT IN (
            SELECT MIN(id) FROM charge_points
            WHERE timestamp < :cutoff
            GROUP BY charge_id, (timestamp / :intervalMs)
        )
    """)
    suspend fun thinOldPoints(cutoff: Long, intervalMs: Long): Int
}
