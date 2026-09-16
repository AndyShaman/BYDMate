package com.bydmate.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.bydmate.app.data.local.entity.TariffPeriodEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TariffPeriodDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(period: TariffPeriodEntity): Long

    @Update
    suspend fun update(period: TariffPeriodEntity)

    @Delete
    suspend fun delete(period: TariffPeriodEntity)

    /** Ascending by start: the order [com.bydmate.app.domain.cost.TariffSchedule] expects. */
    @Query("SELECT * FROM tariff_periods ORDER BY start_ts ASC")
    suspend fun getAllAsc(): List<TariffPeriodEntity>

    @Query("SELECT * FROM tariff_periods ORDER BY start_ts DESC")
    fun observeAll(): Flow<List<TariffPeriodEntity>>

    /** The period already sitting on that date, if any: `start_ts` is unique. */
    @Query("SELECT * FROM tariff_periods WHERE start_ts = :startTs LIMIT 1")
    suspend fun getByStartTs(startTs: Long): TariffPeriodEntity?

    @Query("SELECT COUNT(*) FROM tariff_periods")
    suspend fun count(): Int
}
