package com.bydmate.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "trip_points",
    indices = [Index("trip_id")]
)
data class TripPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "trip_id") val tripId: Long,
    val timestamp: Long,
    val lat: Double,
    val lon: Double,
    @ColumnInfo(name = "speed_kmh") val speedKmh: Double? = null
)
