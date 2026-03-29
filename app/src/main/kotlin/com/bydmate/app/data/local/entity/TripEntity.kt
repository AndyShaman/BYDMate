package com.bydmate.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "trips",
    indices = [Index(value = ["start_ts"])]
)
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "start_ts") val startTs: Long,
    @ColumnInfo(name = "end_ts") val endTs: Long? = null,
    @ColumnInfo(name = "distance_km") val distanceKm: Double? = null,
    @ColumnInfo(name = "kwh_consumed") val kwhConsumed: Double? = null,
    @ColumnInfo(name = "kwh_per_100km") val kwhPer100km: Double? = null,
    @ColumnInfo(name = "soc_start") val socStart: Int? = null,
    @ColumnInfo(name = "soc_end") val socEnd: Int? = null,
    @ColumnInfo(name = "temp_avg_c") val tempAvgC: Double? = null,
    @ColumnInfo(name = "avg_speed_kmh") val avgSpeedKmh: Double? = null,
    @ColumnInfo(name = "bat_temp_avg") val batTempAvg: Double? = null,
    @ColumnInfo(name = "bat_temp_max") val batTempMax: Double? = null,
    @ColumnInfo(name = "bat_temp_min") val batTempMin: Double? = null,
    val cost: Double? = null,
    @ColumnInfo(name = "exterior_temp") val exteriorTemp: Int? = null
)
