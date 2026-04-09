package com.bydmate.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.bydmate.app.data.local.entity.RuleLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RuleLogDao {
    @Insert
    suspend fun insert(log: RuleLogEntity): Long

    @Query("SELECT * FROM automation_log ORDER BY triggered_at DESC")
    fun getAll(): Flow<List<RuleLogEntity>>

    @Query("SELECT * FROM automation_log WHERE rule_id = :ruleId ORDER BY triggered_at DESC")
    fun getByRule(ruleId: Long): Flow<List<RuleLogEntity>>

    @Query("SELECT * FROM automation_log ORDER BY triggered_at DESC LIMIT :limit")
    fun getRecent(limit: Int = 50): Flow<List<RuleLogEntity>>

    @Query("DELETE FROM automation_log WHERE triggered_at < :before")
    suspend fun deleteOlderThan(before: Long): Int
}
