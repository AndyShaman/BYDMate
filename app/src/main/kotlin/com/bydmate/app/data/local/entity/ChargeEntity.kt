package com.bydmate.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "charges",
    indices = [
        Index(value = ["start_ts"]),
        Index(value = ["status"])
    ]
)
data class ChargeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "start_ts") val startTs: Long,
    @ColumnInfo(name = "end_ts") val endTs: Long? = null,
    @ColumnInfo(name = "soc_start") val socStart: Int? = null,
    @ColumnInfo(name = "soc_end") val socEnd: Int? = null,
    @ColumnInfo(name = "kwh_charged") val kwhCharged: Double? = null,
    @ColumnInfo(name = "kwh_charged_soc") val kwhChargedSoc: Double? = null,
    @ColumnInfo(name = "max_power_kw") val maxPowerKw: Double? = null,
    val type: String? = null, // "AC" or "DC"
    val cost: Double? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    @ColumnInfo(name = "bat_temp_avg") val batTempAvg: Double? = null,
    @ColumnInfo(name = "bat_temp_max") val batTempMax: Double? = null,
    @ColumnInfo(name = "bat_temp_min") val batTempMin: Double? = null,
    @ColumnInfo(name = "avg_power_kw") val avgPowerKw: Double? = null,
    val status: String = "COMPLETED",
    @ColumnInfo(name = "cell_voltage_min") val cellVoltageMin: Double? = null,
    @ColumnInfo(name = "cell_voltage_max") val cellVoltageMax: Double? = null,
    @ColumnInfo(name = "voltage_12v") val voltage12v: Double? = null,
    @ColumnInfo(name = "exterior_temp") val exteriorTemp: Int? = null,
    @ColumnInfo(name = "merged_count") val mergedCount: Int = 0,
    // v12: autoservice catch-up trace
    @ColumnInfo(name = "lifetime_kwh_at_start") val lifetimeKwhAtStart: Double? = null,
    @ColumnInfo(name = "lifetime_kwh_at_finish") val lifetimeKwhAtFinish: Double? = null,
    @ColumnInfo(name = "gun_state") val gunState: Int? = null,
    @ColumnInfo(name = "detection_source") val detectionSource: String? = null
)
