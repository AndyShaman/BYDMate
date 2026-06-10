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
    @ColumnInfo(name = "energydata_available") val energydataAvailable: Int = 0
)
