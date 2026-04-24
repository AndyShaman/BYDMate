package com.bydmate.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Snapshot of (mileage, totalElec, soc) taken from the DiPars live stream
 * on every polling tick where mileage advanced by >= MIN_MILEAGE_DELTA OR
 * sessionId changed.
 *
 * Used by OdometerConsumptionBuffer to compute the rolling 25-km recent avg
 * (and 2-km short avg) consumption. sessionId is a boundary marker — pairs
 * of samples with different sessionId values are skipped during averaging
 * (a pair across an ignition cycle is not a meaningful "drove this far on
 * this much energy" segment).
 */
@Entity(
    tableName = "odometer_samples",
    indices = [Index("mileage_km"), Index("session_id")]
)
data class OdometerSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "mileage_km") val mileageKm: Double,
    @ColumnInfo(name = "total_elec_kwh") val totalElecKwh: Double?,
    @ColumnInfo(name = "soc_percent") val socPercent: Int?,
    @ColumnInfo(name = "session_id") val sessionId: Long?,
    @ColumnInfo(name = "timestamp") val timestamp: Long,
)
