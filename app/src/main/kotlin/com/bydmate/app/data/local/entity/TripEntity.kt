package com.bydmate.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "trips",
    indices = [Index(value = ["start_ts"]), Index(value = ["byd_id"])]
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
    /** Outside temperature at the start of the drive, °C; null on trips recorded before it was collected. */
    @ColumnInfo(name = "exterior_temp") val exteriorTemp: Int? = null,
    /** Outside temperature at the finish, °C. */
    @ColumnInfo(name = "exterior_temp_end") val exteriorTempEnd: Int? = null,
    /** Odometer at the start of the drive, km; null on trips recorded before it was collected. */
    @ColumnInfo(name = "odometer_start_km") val odometerStartKm: Double? = null,
    /** Odometer at the finish, km. */
    @ColumnInfo(name = "odometer_end_km") val odometerEndKm: Double? = null,
    @ColumnInfo(name = "source", defaultValue = "live") val source: String = "live",
    @ColumnInfo(name = "byd_id") val bydId: Long? = null,
    // DM-i hybrid (#184), null on every EV trip. Litres and engine km come only from the car's
    // own counters read at both edges of the trip; a missing edge leaves them null, never a guess.
    @ColumnInfo(name = "fuel_liters") val fuelLiters: Double? = null,
    @ColumnInfo(name = "hev_km") val hevKm: Double? = null,
    /** Lifetime fuel counter at the start of the trip, L. */
    @ColumnInfo(name = "fuel_total_start") val fuelTotalStart: Double? = null,
    @ColumnInfo(name = "fuel_total_end") val fuelTotalEnd: Double? = null,
    /** Engine odometer at the start of the trip, km. */
    @ColumnInfo(name = "mileage_hev_start") val mileageHevStart: Double? = null,
    @ColumnInfo(name = "mileage_hev_end") val mileageHevEnd: Double? = null,
    /** Fuel part of [cost]; null while the price per litre is not set. */
    @ColumnInfo(name = "fuel_cost") val fuelCost: Double? = null,
)
