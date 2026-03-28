package com.bydmate.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "charges")
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
    val lon: Double? = null
)
