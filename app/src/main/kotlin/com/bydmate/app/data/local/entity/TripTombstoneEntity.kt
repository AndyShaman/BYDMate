package com.bydmate.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Tombstone for a manually deleted energydata-backed trip (issue #81). Import paths
 * consult it so the trip is never re-imported: deduplicateWithExisting() re-reads ALL
 * source records, and watermark resets (cleanupIdleDrainV2) re-read everything too.
 */
@Entity(tableName = "trip_tombstones")
data class TripTombstoneEntity(
    @PrimaryKey @ColumnInfo(name = "byd_id") val bydId: Long
)
