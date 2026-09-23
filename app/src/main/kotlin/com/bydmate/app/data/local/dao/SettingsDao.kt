package com.bydmate.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.bydmate.app.data.local.entity.SettingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SettingsDao {
    @Query("SELECT value FROM settings WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Query("SELECT value FROM settings WHERE `key` = :key")
    fun observe(key: String): Flow<String?>

    /** Several keys in one SELECT — a consistent snapshot, the read-side twin of [setAll]. */
    @Query("SELECT * FROM settings WHERE `key` IN (:keys)")
    suspend fun getMany(keys: List<String>): List<SettingEntity>

    @Upsert
    suspend fun set(setting: SettingEntity)

    /** Upserts all rows in a single Room transaction — all or nothing. */
    @Upsert
    suspend fun setAll(settings: List<SettingEntity>)

    @Query("SELECT * FROM settings")
    fun getAll(): Flow<List<SettingEntity>>
}
