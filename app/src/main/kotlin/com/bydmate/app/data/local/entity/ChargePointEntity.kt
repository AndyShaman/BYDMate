package com.bydmate.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "charge_points",
    foreignKeys = [
        ForeignKey(
            entity = ChargeEntity::class,
            parentColumns = ["id"],
            childColumns = ["charge_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("charge_id")]
)
data class ChargePointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "charge_id") val chargeId: Long,
    val timestamp: Long,
    @ColumnInfo(name = "power_kw") val powerKw: Double? = null,
    val soc: Int? = null,
    @ColumnInfo(name = "bat_temp") val batTemp: Int? = null
)
