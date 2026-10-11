package com.bydmate.app.data.trips

import android.util.Log
import com.bydmate.app.data.local.dao.CounterSnapshotDao
import com.bydmate.app.data.local.entity.CounterSnapshotEntity
import com.bydmate.app.data.nativestack.VehicleKind
import com.bydmate.app.data.remote.DiParsData
import javax.inject.Inject

/**
 * Writes the DM-i counters to counter_snapshots from the poll tick (#184): at the hybrid
 * ignition edges and every [PERIODIC_MS] while on. HistoryImporter pairs these rows with the
 * energydata trips, so it runs whether TripRecorder is active or passive on this car.
 *
 * Unscoped on purpose: TrackingService gets a fresh instance per service start, which is when
 * rows older than [RETENTION_MS] are pruned (once, on the first HYBRID tick).
 */
class CounterSnapshotWriter @Inject constructor(
    private val dao: CounterSnapshotDao,
) {
    private val edge = HybridIgnitionEdge()
    private var wasOn = false
    private var lastWriteMs = 0L
    private var pruned = false
    /** The last tick that showed a running car: what an ignition_off row records. */
    private var lastOnData: DiParsData? = null

    suspend fun onTick(data: DiParsData, kind: VehicleKind, nowMs: Long) {
        if (kind != VehicleKind.HYBRID) return
        if (!pruned) {
            dao.deleteOlderThan(nowMs - RETENTION_MS)
            pruned = true
        }
        val on = edge.update(data, nowMs)
        val reason = reasonFor(on, nowMs)
        wasOn = on
        if (on && edge.lastOnMs == nowMs) lastOnData = data
        if (reason == null) return
        // The off edge is only known OFF_AFTER_MS after the car stopped; the row carries the
        // time and counters of the last running tick, so it lands next to the trip's real end.
        val turnedOff = reason == CounterSnapshotEntity.REASON_IGNITION_OFF
        val source = if (turnedOff) lastOnData ?: data else data
        val ts = if (turnedOff) edge.lastOnMs else nowMs
        dao.insert(
            CounterSnapshotEntity(
                ts = ts,
                fuelTotal = source.fuelTotalL,
                mileageHev = source.mileageHevKm?.toDouble(),
                mileageEv = source.mileageEvKm?.toDouble(),
                odometer = source.mileage,
                reason = reason,
            )
        )
        lastWriteMs = nowMs
        Log.i(TAG, "snapshot $reason ts=$ts fuel=${source.fuelTotalL} hev=${source.mileageHevKm} " +
            "ev=${source.mileageEvKm} odo=${source.mileage}")
    }

    /** Why this tick writes a row, or null when it does not. */
    private fun reasonFor(on: Boolean, nowMs: Long): String? = when {
        on && !wasOn -> CounterSnapshotEntity.REASON_IGNITION_ON
        !on && wasOn -> CounterSnapshotEntity.REASON_IGNITION_OFF
        on && nowMs - lastWriteMs >= PERIODIC_MS -> CounterSnapshotEntity.REASON_PERIODIC
        else -> null
    }

    companion object {
        private const val TAG = "CounterSnapshot"
        const val PERIODIC_MS = 10 * 60 * 1000L
        const val RETENTION_MS = 90L * 24 * 60 * 60 * 1000
    }
}
