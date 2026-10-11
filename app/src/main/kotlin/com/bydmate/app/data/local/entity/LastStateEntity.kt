package com.bydmate.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Single-row table (id always 1) that persists the last-seen vehicle snapshot.
 * Used by SharedAdaptiveLoop to recover the open trip after a cold start.
 *
 * Distinct from in-memory LastSessionRepository, which keeps SOC bookmarks
 * for HistoryImporter SOC enrichment within a single session.
 */
@Entity(tableName = "last_state")
data class LastStateEntity(
    @PrimaryKey val id: Int = 1,
    @ColumnInfo(name = "ts") val ts: Long,
    @ColumnInfo(name = "soc") val soc: Int? = null,
    @ColumnInfo(name = "mileage") val mileage: Double? = null,
    @ColumnInfo(name = "total_elec") val totalElec: Double? = null,
    @ColumnInfo(name = "ignition") val ignition: Int? = null,
    @ColumnInfo(name = "open_trip_id") val openTripId: Long? = null,
    @ColumnInfo(name = "trip_start_ts") val tripStartTs: Long? = null,
    @ColumnInfo(name = "trip_start_soc") val tripStartSoc: Int? = null,
    @ColumnInfo(name = "trip_start_mileage") val tripStartMileage: Double? = null,
    @ColumnInfo(name = "trip_start_total_elec") val tripStartTotalElec: Double? = null,
    @ColumnInfo(name = "trip_start_exterior_temp") val tripStartExteriorTemp: Int? = null,
    @ColumnInfo(name = "energydata_available") val energydataAvailable: Int = 0,
    /** DM-i only: lifetime fuel counter when the open trip started, L. */
    @ColumnInfo(name = "trip_start_fuel_total") val tripStartFuelTotal: Double? = null,
    /** DM-i only: engine odometer when the open trip started, km. */
    @ColumnInfo(name = "trip_start_mileage_hev") val tripStartMileageHev: Double? = null,
    /** DM-i only: time of the last tick the car was on; the end edge of a trip closed at cold start. */
    @ColumnInfo(name = "on_ts") val onTs: Long? = null,
    /** DM-i only: lifetime fuel counter at [onTs], L; null when that tick missed it. */
    @ColumnInfo(name = "on_fuel_total") val onFuelTotal: Double? = null,
    /** DM-i only: engine odometer at [onTs], km. */
    @ColumnInfo(name = "on_mileage_hev") val onMileageHev: Double? = null,
    /** DM-i only: odometer at [onTs], km; the baseline a resumed trip compares later ticks with. */
    @ColumnInfo(name = "on_mileage") val onMileage: Double? = null,
)
