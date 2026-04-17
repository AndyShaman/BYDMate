package com.bydmate.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.bydmate.app.data.local.entity.PlaceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaceDao {
    @Insert
    suspend fun insert(place: PlaceEntity): Long

    @Update
    suspend fun update(place: PlaceEntity)

    @Delete
    suspend fun delete(place: PlaceEntity)

    @Query("SELECT * FROM places ORDER BY name ASC")
    fun getAll(): Flow<List<PlaceEntity>>

    @Query("SELECT * FROM places ORDER BY name ASC")
    suspend fun getAllSnapshot(): List<PlaceEntity>

    @Query("SELECT * FROM places WHERE id = :id")
    suspend fun getById(id: Long): PlaceEntity?

    @Query("SELECT COUNT(*) FROM places")
    suspend fun getCount(): Int
}
