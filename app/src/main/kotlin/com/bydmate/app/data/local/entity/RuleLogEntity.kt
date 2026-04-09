package com.bydmate.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "automation_log",
    indices = [
        Index(value = ["triggered_at"]),
        Index(value = ["rule_id"])
    ]
)
data class RuleLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "rule_id") val ruleId: Long,
    @ColumnInfo(name = "rule_name") val ruleName: String,
    @ColumnInfo(name = "triggered_at") val triggeredAt: Long,
    @ColumnInfo(name = "triggers_snapshot") val triggersSnapshot: String,
    @ColumnInfo(name = "actions_result") val actionsResult: String,
    val success: Boolean
)
