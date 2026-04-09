package com.bydmate.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName = "automation_rules")
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val enabled: Boolean = true,
    @ColumnInfo(name = "trigger_logic") val triggerLogic: String = "AND",
    val triggers: String,   // JSON: [TriggerDef]
    val actions: String,    // JSON: [ActionDef]
    @ColumnInfo(name = "cooldown_seconds") val cooldownSeconds: Int = 60,
    @ColumnInfo(name = "require_park") val requirePark: Boolean = false,
    @ColumnInfo(name = "confirm_before_execute") val confirmBeforeExecute: Boolean = false,
    @ColumnInfo(name = "last_triggered_at") val lastTriggeredAt: Long? = null,
    @ColumnInfo(name = "trigger_count") val triggerCount: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)

data class TriggerDef(
    val param: String,
    val chineseName: String,
    val operator: String,
    val value: String,
    val displayName: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("param", param)
        put("chineseName", chineseName)
        put("operator", operator)
        put("value", value)
        put("displayName", displayName)
    }

    companion object {
        fun fromJson(json: JSONObject) = TriggerDef(
            param = json.getString("param"),
            chineseName = json.getString("chineseName"),
            operator = json.getString("operator"),
            value = json.getString("value"),
            displayName = json.getString("displayName")
        )

        fun listFromJson(jsonStr: String): List<TriggerDef> = try {
            val arr = JSONArray(jsonStr)
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            Log.e("TriggerDef", "Failed to parse: ${e.message}")
            emptyList()
        }

        fun listToJson(list: List<TriggerDef>): String {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }
    }
}

data class ActionDef(
    val command: String,
    val displayName: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("command", command)
        put("displayName", displayName)
    }

    companion object {
        fun fromJson(json: JSONObject) = ActionDef(
            command = json.getString("command"),
            displayName = json.getString("displayName")
        )

        fun listFromJson(jsonStr: String): List<ActionDef> = try {
            val arr = JSONArray(jsonStr)
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            Log.e("ActionDef", "Failed to parse: ${e.message}")
            emptyList()
        }

        fun listToJson(list: List<ActionDef>): String {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }
    }
}
