package com.bydmate.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Records a battery capacity snapshot taken at each charge session end.
 * Used for tracking battery degradation over time.
 */
@Entity(
    tableName = "battery_snapshots",
    indices = [Index(value = ["timestamp"])]
)
data class BatterySnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    @ColumnInfo(name = "odometer_km") val odometerKm: Double? = null,
    @ColumnInfo(name = "soc_start") val socStart: Int,
    @ColumnInfo(name = "soc_end") val socEnd: Int,
    @ColumnInfo(name = "kwh_charged") val kwhCharged: Double,
    @ColumnInfo(name = "calculated_capacity_kwh") val calculatedCapacityKwh: Double? = null,
    @ColumnInfo(name = "soh_percent") val sohPercent: Double? = null,
    @ColumnInfo(name = "cell_delta_v") val cellDeltaV: Double? = null,
    @ColumnInfo(name = "bat_temp_avg") val batTempAvg: Double? = null,
    @ColumnInfo(name = "charge_id") val chargeId: Long? = null
)
