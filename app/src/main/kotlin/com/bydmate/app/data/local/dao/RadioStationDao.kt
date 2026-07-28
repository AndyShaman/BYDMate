package com.bydmate.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.bydmate.app.data.local.entity.RadioStationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RadioStationDao {
    @Insert
    suspend fun insert(station: RadioStationEntity): Long

    @Update
    suspend fun update(station: RadioStationEntity)

    @Delete
    suspend fun delete(station: RadioStationEntity)

    @Query("SELECT * FROM radio_stations ORDER BY created_at ASC")
    fun getAll(): Flow<List<RadioStationEntity>>

    @Query("SELECT * FROM radio_stations ORDER BY created_at ASC")
    suspend fun getAllSnapshot(): List<RadioStationEntity>

    @Query("SELECT * FROM radio_stations WHERE id = :id")
    suspend fun getById(id: Long): RadioStationEntity?
}
