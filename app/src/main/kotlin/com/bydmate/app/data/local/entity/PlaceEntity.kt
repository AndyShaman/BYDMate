package com.bydmate.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "places")
data class PlaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val lat: Double,
    val lon: Double,
    @ColumnInfo(name = "radius_m") val radiusM: Int = 50,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)
