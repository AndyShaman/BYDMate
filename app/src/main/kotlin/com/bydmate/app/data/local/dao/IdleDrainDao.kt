package com.bydmate.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.bydmate.app.data.local.entity.IdleDrainEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface IdleDrainDao {
    @Insert
    suspend fun insert(drain: IdleDrainEntity): Long

    @Update
    suspend fun update(drain: IdleDrainEntity)

    @Query("SELECT * FROM idle_drains WHERE start_ts >= :from AND start_ts <= :to ORDER BY start_ts DESC")
    fun getByDateRange(from: Long, to: Long): Flow<List<IdleDrainEntity>>

    @Query("""
        SELECT COALESCE(SUM(kwh_consumed), 0.0)
        FROM idle_drains
        WHERE start_ts >= :dayStart AND start_ts <= :dayEnd
    """)
    suspend fun getTodayDrainKwh(dayStart: Long, dayEnd: Long): Double

    @Query("SELECT COUNT(*) FROM idle_drains")
    suspend fun getCount(): Int

    @Query("SELECT COALESCE(SUM(kwh_consumed), 0.0) FROM idle_drains")
    suspend fun getTotalKwh(): Double

    @Query("DELETE FROM idle_drains")
    suspend fun deleteAll()

    @Query("""
        SELECT COALESCE(SUM(CAST(end_ts - start_ts AS REAL) / 3600000.0), 0.0)
        FROM idle_drains
        WHERE start_ts >= :dayStart AND start_ts <= :dayEnd AND end_ts IS NOT NULL
    """)
    suspend fun getTodayDrainHours(dayStart: Long, dayEnd: Long): Double

    @Query("SELECT COALESCE(SUM(kwh_consumed), 0.0) FROM idle_drains WHERE start_ts >= :since")
    suspend fun getKwhSince(since: Long): Double

    @Query("""
        SELECT COALESCE(SUM(CAST(end_ts - start_ts AS REAL) / 3600000.0), 0.0)
        FROM idle_drains WHERE start_ts >= :since AND end_ts IS NOT NULL
    """)
    suspend fun getHoursSince(since: Long): Double

    @Query("SELECT COALESCE(SUM(kwh_consumed), 0.0) FROM idle_drains WHERE start_ts >= :from AND start_ts < :to")
    suspend fun getKwhBetween(from: Long, to: Long): Double
}
