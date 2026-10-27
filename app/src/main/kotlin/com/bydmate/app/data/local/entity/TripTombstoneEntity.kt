package com.bydmate.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Tombstone for a manually deleted energydata-backed trip (issue #81). Import paths
 * consult it so the trip is never re-imported: deduplicateWithExisting() re-reads ALL
 * source records, and watermark resets (cleanupIdleDrainV2) re-read everything too.
 *
 * [startTs] is the deleted trip's start: energydata `_id` restarts from 1 when the car recreates
 * its DB (#348), so the id alone cannot tell the deleted trip from a new one with the same id.
 * Null on tombstones written before schema 24.
 */
@Entity(tableName = "trip_tombstones")
data class TripTombstoneEntity(
    @PrimaryKey @ColumnInfo(name = "byd_id") val bydId: Long,
    @ColumnInfo(name = "start_ts") val startTs: Long? = null
)
