package com.bydmate.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.bydmate.app.data.local.entity.ChargeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChargeDao {
    @Insert
    suspend fun insert(charge: ChargeEntity): Long

    @Update
    suspend fun update(charge: ChargeEntity)

    @Query("SELECT * FROM charges ORDER BY start_ts DESC")
    fun getAll(): Flow<List<ChargeEntity>>

    @Query("SELECT * FROM charges WHERE id = :id")
    suspend fun getById(id: Long): ChargeEntity?

    @Query("SELECT * FROM charges WHERE start_ts >= :from AND start_ts <= :to ORDER BY start_ts DESC")
    fun getByDateRange(from: Long, to: Long): Flow<List<ChargeEntity>>

    @Query("""
        SELECT COUNT(*) as sessionCount,
               COALESCE(SUM(kwh_charged), 0.0) as totalKwh,
               COALESCE(SUM(cost), 0.0) as totalCost
        FROM charges
        WHERE start_ts >= :from AND start_ts <= :to
    """)
    suspend fun getPeriodSummary(from: Long, to: Long): ChargeSummary

    @Query("SELECT * FROM charges ORDER BY start_ts DESC LIMIT 1")
    fun getLastCharge(): Flow<ChargeEntity?>

    @Query("SELECT * FROM charges WHERE status = 'SUSPENDED' ORDER BY start_ts DESC LIMIT 1")
    suspend fun getLastSuspendedCharge(): ChargeEntity?

    @Query("SELECT * FROM charges WHERE status != 'COMPLETED' AND start_ts < :cutoffTs")
    suspend fun getStaleSessions(cutoffTs: Long): List<ChargeEntity>

    @Query("SELECT * FROM charges ORDER BY start_ts DESC LIMIT 1")
    suspend fun getLastChargeSync(): ChargeEntity?

    @Query("""
        SELECT * FROM charges
        WHERE status = 'COMPLETED' AND (cell_voltage_min IS NOT NULL OR voltage_12v IS NOT NULL)
        ORDER BY start_ts DESC LIMIT 30
    """)
    suspend fun getRecentChargesWithBatteryData(): List<ChargeEntity>

    /**
     * Returns the highest lifetime_kwh_at_finish recorded by an autoservice
     * detector, or null if no autoservice-sourced sessions exist yet.
     * Used as the catch-up baseline.
     */
    @Query("""
        SELECT MAX(lifetime_kwh_at_finish) FROM charges
        WHERE detection_source LIKE 'autoservice%'
          AND lifetime_kwh_at_finish IS NOT NULL
    """)
    suspend fun getMaxLifetimeKwhAtFinish(): Double?
}

data class ChargeSummary(
    val sessionCount: Int,
    val totalKwh: Double,
    val totalCost: Double
)
