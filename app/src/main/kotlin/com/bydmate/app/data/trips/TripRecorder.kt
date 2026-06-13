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
        val startTotalElec: Double? = null,
    )
    internal var open: Open? = null

    private var nullPowerStreak = 0
    private fun ignitionOn(data: DiParsData): Boolean {
        if (data.powerState != null) { nullPowerStreak = 0; return data.powerState == 2 }
        nullPowerStreak++
        val fallback = nullPowerStreak >= 5
        return fallback && (data.gear == 4 || ((data.speed ?: 0) > 0))
    }

    suspend fun consume(data: DiParsData) {
        val active = !energyDataReader.isAvailable()
        if (!active) return  // passive on Leopard 3 — never write trips or open-trip state

        val ignitionOn = ignitionOn(data)
        val cur = open
        when {
            cur == null && ignitionOn -> openTrip(data)
            cur != null && !ignitionOn -> close(cur, data)
        }
    }

    private suspend fun openTrip(data: DiParsData) {
        val startTs = now()
        open = Open(startTs, data.soc, data.mileage, data.totalElecConsumption)
        // Spec §96-100: persist open trip to last_state so cold-start can resume.
        val updated = lastStateDao.openTrip(
            startTs = startTs,
            startSoc = data.soc,
            startMileage = data.mileage,
            startTotalElec = data.totalElecConsumption,
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
                    tripStartTotalElec = data.totalElecConsumption,
                    energydataAvailable = 0,
                )
            )
        }
    }

    /**
     * BMS lifetime-consumption counter (0.1 kWh granularity) beats the integer SOC
     * delta (1% SOC = ~0.7 kWh quantum — issue #53: dashes on short trips, inflated
     * per-100km on others). Negative/zero delta (counter reset or unsupported fid)
     * falls back to the SOC estimate.
     */
    private fun computeKwh(startElec: Double?, endElec: Double?, startSoc: Int?, endSoc: Int?, cap: Double): Double? {
        if (startElec != null && endElec != null) {
            val elecDelta = endElec - startElec
            if (elecDelta > 0) return elecDelta
        }
        val socDelta = (startSoc ?: 0) - (endSoc ?: 0)
        return if (socDelta > 0) socDelta / 100.0 * cap else null
    }

    private suspend fun close(open: Open, end: DiParsData) {
        val cap = batteryCapacityKwh()
        val kwh = computeKwh(open.startTotalElec, end.totalElecConsumption, open.startSoc, end.soc, cap)
        val distance = if (open.startMileage != null && end.mileage != null)
            (end.mileage - open.startMileage).coerceAtLeast(0.0) else null
        val per100 = if (kwh != null && distance != null && distance > 0) kwh / distance * 100.0 else null
        tripDao.insert(
            TripEntity(
                startTs = open.startTs,
                endTs = now(),
                distanceKm = distance,
                kwhConsumed = kwh,
                kwhPer100km = per100,
                socStart = open.startSoc,
                socEnd = end.soc,
                source = TripSource.NATIVE_POLLING,
            )
        )
        this.open = null
        lastStateDao.clearOpenTrip()
    }

    /** Call once before subscribing to the loop. */
    suspend fun reconcileColdStart() {
        val state = lastStateDao.getCurrent() ?: return
        if (state.openTripId == null || state.tripStartTs == null) return
        val gap = now() - state.ts
        val active = !energyDataReader.isAvailable()
        val staleGap = 5 * 60 * 1_000L
        if (gap < staleGap) {
            if (active) {
                open = Open(state.tripStartTs, state.tripStartSoc, state.tripStartMileage, state.tripStartTotalElec)
            }
            return
        }
        if (active) {
            val kwh = computeKwh(state.tripStartTotalElec, state.totalElec, state.tripStartSoc, state.soc, batteryCapacityKwh())
            val distance = if (state.tripStartMileage != null && state.mileage != null)
                (state.mileage - state.tripStartMileage).coerceAtLeast(0.0) else null
            val per100 = if (kwh != null && distance != null && distance > 0) kwh / distance * 100.0 else null
            tripDao.insert(
                TripEntity(
                    startTs = state.tripStartTs,
                    endTs = state.ts,
                    distanceKm = distance,
                    kwhConsumed = kwh,
                    kwhPer100km = per100,
                    socStart = state.tripStartSoc,
                    socEnd = state.soc,
                    source = TripSource.NATIVE_POLLING,
                )
            )
        }
        lastStateDao.clearOpenTrip()
    }
}
