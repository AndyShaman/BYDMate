package com.bydmate.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "trip_points",
    indices = [Index("trip_id"), Index("timestamp")]
)
data class TripPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "trip_id") val tripId: Long,
    val timestamp: Long,
    val lat: Double,
    val lon: Double,
    @ColumnInfo(name = "speed_kmh") val speedKmh: Double? = null,
    /** Metres above sea level as the fix reported it; the trip window draws it as the elevation shadow. */
    val altitude: Double? = null,
    /** Battery power at this tick, W: positive while drawing, negative while charging (regen). */
    @ColumnInfo(name = "battery_power_w") val batteryPowerW: Double? = null,
    /**
     * Net battery energy since the tracker run started, Wh (rises on draw, falls on regen).
     * The difference of two points of the same [energyRun] is the exact energy between them,
     * even after DataThinningWorker removed the points in between.
     */
    @ColumnInfo(name = "battery_energy_wh") val batteryEnergyWh: Double? = null,
    /** Id of the tracker run [batteryEnergyWh] belongs to (clock value when the tracker was created). */
    @ColumnInfo(name = "energy_run") val energyRun: Long? = null,
)
