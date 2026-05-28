package com.bydmate.app.data.trips

import com.bydmate.app.data.local.EnergyDataReader
import com.bydmate.app.data.local.dao.LastStateDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.entity.LastStateEntity
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.remote.DiParsData
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TripRecorder @Inject constructor(
    private val tripDao: TripDao,
    private val lastStateDao: LastStateDao,
    private val energyDataReader: EnergyDataReader,
    private val batteryCapacityKwh: suspend () -> Double,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    // In-memory mirror of last_state.open_trip_*; rebuilt at cold-start from DAO.
    internal data class Open(
        val startTs: Long,
        val startSoc: Int?,
        val startMileage: Double?,
    )
    internal var open: Open? = null

    suspend fun consume(data: DiParsData) {
        val active = !energyDataReader.isAvailable()
        if (!active) return  // passive on Leopard 3 — never write trips or open-trip state

        val ignitionOn = data.powerState == 2  // DRIVE mode in DiParsData enum
        val cur = open
        when {
            cur == null && ignitionOn -> openTrip(data)
            cur != null && !ignitionOn -> close(cur, data)
        }
    }

    private suspend fun openTrip(data: DiParsData) {
        val startTs = now()
        open = Open(startTs, data.soc, data.mileage)
        // Spec §96-100: persist open trip to last_state so cold-start can resume.
        val updated = lastStateDao.openTrip(
            startTs = startTs,
            startSoc = data.soc,
            startMileage = data.mileage,
            now = startTs,
        )
        if (updated == 0) {
            // Very-first-ever tick before the loop has written a snapshot.
            lastStateDao.upsert(
                LastStateEntity(
                    id = 1,
                    ts = startTs,
                    soc = data.soc,
                    mileage = data.mileage,
                    ignition = data.powerState,
                    openTripId = startTs,
                    tripStartTs = startTs,
                    tripStartSoc = data.soc,
                    tripStartMileage = data.mileage,
                    energydataAvailable = 0,
                )
            )
        }
    }

    private suspend fun close(open: Open, end: DiParsData) {
        val cap = batteryCapacityKwh()
        val socDelta = (open.startSoc ?: 0) - (end.soc ?: 0)
        val kwh = if (socDelta > 0) socDelta / 100.0 * cap else null
        val distance = if (open.startMileage != null && end.mileage != null)
            (end.mileage - open.startMileage).coerceAtLeast(0.0) else null
        tripDao.insert(
            TripEntity(
                startTs = open.startTs,
                endTs = now(),
                distanceKm = distance,
                kwhConsumed = kwh,
                socStart = open.startSoc,
                socEnd = end.soc,
                source = TripSource.NATIVE_POLLING,
            )
        )
        this.open = null
        lastStateDao.clearOpenTrip()
    }
}
