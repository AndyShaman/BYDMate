package com.bydmate.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * DM-i hybrid counters as the car reported them at one moment (#184): written by
 * CounterSnapshotWriter at the ignition edges and every 10 min while on, so HistoryImporter can
 * give an energydata trip its litres from a snapshot pair at its own start and end.
 */
@Entity(
    tableName = "counter_snapshots",
    indices = [Index("ts")]
)
data class CounterSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "ts") val ts: Long,
    /** Lifetime fuel used, L. */
    @ColumnInfo(name = "fuel_total") val fuelTotal: Double?,
    /** Odometer driven on the engine, km. */
    @ColumnInfo(name = "mileage_hev") val mileageHev: Double?,
    /** Odometer driven on the battery, km. */
    @ColumnInfo(name = "mileage_ev") val mileageEv: Double?,
    @ColumnInfo(name = "odometer") val odometer: Double?,
    /** [REASON_IGNITION_ON], [REASON_IGNITION_OFF], [REASON_PERIODIC] or [REASON_COLD_START]. */
    @ColumnInfo(name = "reason") val reason: String,
) {
    companion object {
        const val REASON_IGNITION_ON = "ignition_on"
        const val REASON_IGNITION_OFF = "ignition_off"
        const val REASON_PERIODIC = "periodic"
        /** Written at a service start from the counters last_state kept when the process died. */
        const val REASON_COLD_START = "cold_start"
    }
}
