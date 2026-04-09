package com.bydmate.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.bydmate.app.data.local.entity.RuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RuleDao {
    @Insert
    suspend fun insert(rule: RuleEntity): Long

    @Update
    suspend fun update(rule: RuleEntity)

    @Delete
    suspend fun delete(rule: RuleEntity)

    @Query("SELECT * FROM automation_rules ORDER BY created_at DESC")
    fun getAll(): Flow<List<RuleEntity>>

    @Query("SELECT * FROM automation_rules WHERE enabled = 1")
    suspend fun getEnabled(): List<RuleEntity>

    @Query("SELECT * FROM automation_rules WHERE id = :id")
    suspend fun getById(id: Long): RuleEntity?

    @Query("UPDATE automation_rules SET last_triggered_at = :ts, trigger_count = trigger_count + 1 WHERE id = :id")
    suspend fun updateLastTriggered(id: Long, ts: Long)

    @Query("UPDATE automation_rules SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("SELECT COUNT(*) FROM automation_rules")
    suspend fun getCount(): Int
}
