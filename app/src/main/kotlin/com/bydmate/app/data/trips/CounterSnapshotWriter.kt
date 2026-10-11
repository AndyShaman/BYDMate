package com.bydmate.app.data.trips

import android.util.Log
import com.bydmate.app.data.local.dao.CounterSnapshotDao
import com.bydmate.app.data.local.entity.CounterSnapshotEntity
import com.bydmate.app.data.local.entity.LastStateEntity
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
    /** The trip's end edge: what an ignition_off row records. */
    private val endEdge = ReadableOnTick()

    suspend fun onTick(data: DiParsData, kind: VehicleKind, nowMs: Long) {
        if (kind != VehicleKind.HYBRID) return
        if (!pruned) {
            dao.deleteOlderThan(nowMs - RETENTION_MS)
            pruned = true
        }
        val on = edge.update(data, nowMs)
        val reason = reasonFor(on, nowMs)
        wasOn = on
        endEdge.update(data, nowMs, isOnTick = on && edge.lastOnMs == nowMs)
        if (reason == null) return
        // The off edge is only known OFF_AFTER_MS after the car stopped; the row carries the
        // time and counters of the trip's end edge, so it lands next to the trip's real end.
        val edgeMs = endEdge.tickMs
        val row = when {
            reason != CounterSnapshotEntity.REASON_IGNITION_OFF -> rowOf(nowMs, data, reason)
            edgeMs != null -> CounterSnapshotEntity(
                ts = edgeMs,
                fuelTotal = endEdge.fuelTotalL,
                mileageHev = endEdge.mileageHevKm,
                mileageEv = endEdge.mileageEvKm,
                odometer = endEdge.odometerKm,
                reason = reason,
            )
            else -> rowOf(edge.lastOnMs, data, reason)
        }
        if (reason == CounterSnapshotEntity.REASON_IGNITION_OFF) endEdge.clear()
        dao.insert(row)
        lastWriteMs = nowMs
        Log.i(TAG, "snapshot $reason ts=${row.ts} fuel=${row.fuelTotal} hev=${row.mileageHev} " +
            "ev=${row.mileageEv} odo=${row.odometer}")
    }

    /**
     * At a service start: the head unit dies with the car, so the ignition_off row is often never
     * written. The last ON tick TripRecorder kept in [LastStateEntity] becomes the end edge of the
     * trip that ended at power-off, unless a row within [COLD_START_WINDOW_MS] already covers it.
     */
    suspend fun onColdStart(state: LastStateEntity?, kind: VehicleKind) {
        if (kind != VehicleKind.HYBRID || state == null) return
        if (state.onFuelTotal == null && state.onMileageHev == null) return
        val ts = state.onTs ?: state.ts
        if (dao.nearest(ts, COLD_START_WINDOW_MS) != null) return
        dao.insert(
            CounterSnapshotEntity(
                ts = ts,
                fuelTotal = state.onFuelTotal,
                mileageHev = state.onMileageHev,
                mileageEv = null,
                odometer = state.mileage,
                reason = CounterSnapshotEntity.REASON_COLD_START,
            )
        )
        Log.i(TAG, "snapshot ${CounterSnapshotEntity.REASON_COLD_START} ts=$ts onTs=${state.onTs} " +
            "fuel=${state.onFuelTotal} hev=${state.onMileageHev} odo=${state.mileage}")
    }

    private fun rowOf(ts: Long, data: DiParsData, reason: String) = CounterSnapshotEntity(
        ts = ts,
        fuelTotal = data.fuelTotalL,
        mileageHev = data.mileageHevKm?.toDouble(),
        mileageEv = data.mileageEvKm?.toDouble(),
        odometer = data.mileage,
        reason = reason,
    )

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
        const val COLD_START_WINDOW_MS = 90_000L
    }
}
