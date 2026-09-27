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

    /** The newest entry of every rule: the card status line under the rule name. */
    @Query(
        "SELECT * FROM automation_log WHERE id IN (SELECT (SELECT m.id FROM automation_log m " +
            "WHERE m.rule_id = r.rule_id ORDER BY m.triggered_at DESC, m.id DESC LIMIT 1) " +
            "FROM (SELECT DISTINCT rule_id FROM automation_log) r)"
    )
    fun getLastPerRule(): Flow<List<RuleLogEntity>>

    @Query("DELETE FROM automation_log WHERE triggered_at < :before")
    suspend fun deleteOlderThan(before: Long): Int

    @Query("SELECT * FROM automation_log ORDER BY triggered_at DESC LIMIT :limit")
    suspend fun getRecentList(limit: Int): List<RuleLogEntity>

    /** Keeps the [keep] newest rows, deletes the rest; returns how many went. */
    @Query(
        "DELETE FROM automation_log WHERE id NOT IN " +
            "(SELECT id FROM automation_log ORDER BY triggered_at DESC, id DESC LIMIT :keep)"
    )
    suspend fun trimToNewest(keep: Int): Int
}
