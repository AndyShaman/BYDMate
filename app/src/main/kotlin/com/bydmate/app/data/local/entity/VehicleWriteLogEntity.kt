package com.bydmate.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "vehicle_write_log")
data class VehicleWriteLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val actionName: String,
    val dev: Int,
    val fid: Int,
    val requested: Int,
    val readback: Int?,
    val status: Int,
    val error: String?,
    // defaultValue keeps the Room-generated schema for v15 in sync with the
    // MIGRATION_14_15 DDL ("INTEGER NOT NULL DEFAULT 0"). Without this the
    // schema validator fails on app start.
    @ColumnInfo(defaultValue = "0") val validated: Boolean,
)
