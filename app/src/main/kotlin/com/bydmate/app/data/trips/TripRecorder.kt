package com.bydmate.app.data.trips

import com.bydmate.app.data.local.EnergyDataDeadDetector
import com.bydmate.app.data.local.EnergyDataReader
import com.bydmate.app.data.local.dao.LastStateDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.nativestack.VehicleKind
import com.bydmate.app.data.remote.DiParsData
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.roundToLong

@Singleton
class TripRecorder @Inject @Suppress("LongParameterList") constructor( // injected dependencies + test clocks
    private val tripDao: TripDao,
    private val lastStateDao: LastStateDao,
    private val energyDataReader: EnergyDataReader,
    private val deadDetector: EnergyDataDeadDetector,
    private val batteryCapacityKwh: suspend () -> Double,
    private val now: () -> Long = { System.currentTimeMillis() },
    /** Car type; every DM-i branch below is behind HYBRID, EV and UNKNOWN take the old path. */
    private val kind: () -> VehicleKind = { VehicleKind.UNKNOWN },
) {
    // In-memory mirror of last_state.open_trip_*; rebuilt at cold-start from DAO.
    internal data class Open(
        val startTs: Long,
        val startSoc: Int?,
        val startMileage: Double?,
        val startTotalElec: Double? = null,
        /** Outside temperature when the trip opened, °C. */
        val startExteriorTemp: Int? = null,
        /** DM-i only: lifetime fuel counter at open, L. */
        val startFuelTotal: Double? = null,
        /** DM-i only: engine odometer at open, km. */
        val startMileageHev: Double? = null,
    )
    internal var open: Open? = null

    private val hybridEdge = HybridIgnitionEdge()
    /** DM-i: the counters of the tick at [HybridIgnitionEdge.lastOnMs], the trip's end edge. */
    private var lastOnFuel: Double? = null
    private var lastOnHev: Double? = null

    private var nullPowerStreak = 0
    private fun ignitionOn(data: DiParsData): Boolean {
        if (kind() == VehicleKind.HYBRID) {
            val nowMs = now()
            val on = hybridEdge.update(data, nowMs)
            // Taken from the same tick as the trip's end time; a counter that tick missed stays
            // missing rather than borrowing an older reading.
            if (on && hybridEdge.lastOnMs == nowMs) {
                lastOnFuel = data.fuelTotalL
                lastOnHev = data.mileageHevKm?.toDouble()
            }
            return on
        }
        if (data.powerState != null) { nullPowerStreak = 0; return data.powerState == 2 }
        nullPowerStreak++
        val fallback = nullPowerStreak >= 5
        return fallback && (data.gear == 4 || ((data.speed ?: 0) > 0))
    }

    suspend fun consume(data: DiParsData) {
        val ignitionOn = ignitionOn(data)
        // Issue #63: feed the dead-leftover detector on every tick, even while passive —
        // it is the only component that can flip this device to native recording.
        deadDetector.onTick(ignitionOn, data.mileage)
        val active = !energyDataReader.isAvailable() || deadDetector.isDead()
        if (!active) return  // passive on live energydata — never write trips or open-trip state

        val cur = open
        when {
            cur == null && ignitionOn -> openTrip(data)
            cur != null && !ignitionOn -> close(cur, data)
        }
    }

    private suspend fun openTrip(data: DiParsData) {
        val startTs = now()
        val hybrid = kind() == VehicleKind.HYBRID
        val startFuel = if (hybrid) data.fuelTotalL else null
        val startHev = if (hybrid) data.mileageHevKm?.toDouble() else null
        open = Open(startTs, data.soc, data.mileage, data.totalElecConsumption, data.exteriorTemp, startFuel, startHev)
        Log.i(TAG, "trip open: ts=$startTs soc=${data.soc} ext_temp=${data.exteriorTemp}")
        // Spec §96-100: persist open trip to last_state so cold-start can resume.
        val updated = lastStateDao.openTrip(
            startTs = startTs,
            startSoc = data.soc,
            startMileage = data.mileage,
            startTotalElec = data.totalElecConsumption,
            startExteriorTemp = data.exteriorTemp,
            now = startTs,
        )
        if (updated == 0) {
            // Very-first-ever tick before the loop has written a snapshot: create the row
            // (snapshot columns default/NULL -- the loop's own write for this same tick
            // fills them in) then retry the trip-columns-only UPDATE, so this fallback
            // cannot clobber a snapshot written concurrently by SharedAdaptiveLoop.
            lastStateDao.insertSnapshotRowIfAbsent(
                ts = startTs, soc = null, mileage = null, totalElec = null,
                ignition = null, energydataAvailable = 0,
            )
            lastStateDao.openTrip(
                startTs = startTs,
                startSoc = data.soc,
                startMileage = data.mileage,
                startTotalElec = data.totalElecConsumption,
                startExteriorTemp = data.exteriorTemp,
                now = startTs,
            )
        }
        if (hybrid) lastStateDao.setOpenTripCounters(startFuel, startHev)
    }

    /**
     * BMS lifetime-consumption counter (0.1 kWh granularity) beats the integer SOC
     * delta (1% SOC = ~0.7 kWh quantum — issue #53: dashes on short trips, inflated
     * per-100km on others). Negative/zero delta (counter reset or unsupported fid)
     * falls back to the SOC estimate. A DM-i hybrid gets no SOC estimate: the engine charges
     * the pack on the way, so a SOC delta is not what the trip drew (null instead).
     */
    private fun computeKwh(startElec: Double?, endElec: Double?, startSoc: Int?, endSoc: Int?, cap: Double): Double? {
        if (startElec != null && endElec != null) {
            val elecDelta = endElec - startElec
            if (elecDelta > 0) return elecDelta
        }
        if (kind() == VehicleKind.HYBRID) return null
        val socDelta = (startSoc ?: 0) - (endSoc ?: 0)
        return if (socDelta > 0) socDelta / 100.0 * cap else null
    }

    /**
     * Odometer delta of one trip, or null when the two readings cannot belong to the same
     * drive: the odometer scale changed under an open trip (the firmware catalog resolved
     * mid-session and moved the odometer fid) or the baseline came from a startup race.
     * The trip itself is still recorded, only the distance is dropped.
     */
    private fun plausibleDistance(start: Double?, end: Double?): Double? {
        if (start == null || end == null) return null
        val delta = (end - start).coerceAtLeast(0.0)
        if (delta > MAX_PLAUSIBLE_TRIP_KM) {
            Log.w(TAG, "trip distance implausible: start=$start end=$end delta=$delta → dropped")
            return null
        }
        return delta
    }

    /**
     * Odometer at both ends of a trip, rounded to 0.1 km. A reading under 1 km is the startup
     * zero, not the odometer; a pair further apart than a plausible drive belongs to two
     * different odometer scales, so neither end is trusted.
     */
    private fun odometerPair(start: Double?, end: Double?): Pair<Double?, Double?> {
        val s = start?.takeIf { it >= MIN_VALID_ODOMETER_KM }
        val e = end?.takeIf { it >= MIN_VALID_ODOMETER_KM }
        if (s != null && e != null && abs(e - s) > MAX_PLAUSIBLE_TRIP_KM) return null to null
        return s?.let(::round1) to e?.let(::round1)
    }

    private fun round1(v: Double): Double = (v * 10.0).roundToLong() / 10.0

    /**
     * DM-i fuel columns of one trip from the counters at its two edges: raw readings as read,
     * litres only for a delta in [0, [MAX_TRIP_LITERS]], engine km only for a delta in
     * [0, distance + 1]. A missing edge leaves the derived value null. Not HYBRID: all null.
     */
    private fun fuelColumns(
        startFuel: Double?,
        endFuel: Double?,
        startHev: Double?,
        endHev: Double?,
        distanceKm: Double?,
    ): TripFuel {
        if (kind() != VehicleKind.HYBRID) return TripFuel()
        return TripFuel(
            fuelLiters = TripFuel.liters(startFuel, endFuel),
            hevKm = TripFuel.hevKm(startHev, endHev, distanceKm),
            fuelTotalStart = startFuel,
            fuelTotalEnd = endFuel,
            mileageHevStart = startHev,
            mileageHevEnd = endHev,
        )
    }

    private suspend fun close(open: Open, end: DiParsData) {
        val cap = batteryCapacityKwh()
        val kwh = computeKwh(open.startTotalElec, end.totalElecConsumption, open.startSoc, end.soc, cap)
        val distance = plausibleDistance(open.startMileage, end.mileage)
        val per100 = if (kwh != null && distance != null && distance > 0) kwh / distance * 100.0 else null
        val (odoStart, odoEnd) = odometerPair(open.startMileage, end.mileage)
        // The confirming tick comes OFF_AFTER_MS after the car stopped and may miss a counter.
        val fuel = fuelColumns(
            open.startFuelTotal, end.fuelTotalL ?: lastOnFuel,
            open.startMileageHev, end.mileageHevKm?.toDouble() ?: lastOnHev, distance,
        )
        tripDao.insert(
            TripEntity(
                startTs = open.startTs,
                // The hybrid edge drops OFF_AFTER_MS after the car stopped; the trip ends at the
                // last tick the car was on, not at the moment the silence was confirmed.
                endTs = if (kind() == VehicleKind.HYBRID) {
                    hybridEdge.lastOnMs.takeIf { it >= open.startTs } ?: now()  // resumed trip, no live tick
                } else {
                    now()
                },
                distanceKm = distance,
                kwhConsumed = kwh,
                kwhPer100km = per100,
                socStart = open.startSoc,
                socEnd = end.soc,
                exteriorTemp = open.startExteriorTemp,
                exteriorTempEnd = end.exteriorTemp,
                odometerStartKm = odoStart,
                odometerEndKm = odoEnd,
                source = TripSource.NATIVE_POLLING,
                fuelLiters = fuel.fuelLiters,
                hevKm = fuel.hevKm,
                fuelTotalStart = fuel.fuelTotalStart,
                fuelTotalEnd = fuel.fuelTotalEnd,
                mileageHevStart = fuel.mileageHevStart,
                mileageHevEnd = fuel.mileageHevEnd,
            )
        )
        if (kind() == VehicleKind.HYBRID) {
            Log.i(TAG, "trip close fuel: l=${fuel.fuelLiters} hev=${fuel.hevKm} " +
                "total=${fuel.fuelTotalStart}/${fuel.fuelTotalEnd} hevOdo=${fuel.mileageHevStart}/${fuel.mileageHevEnd}")
        }
        Log.i(TAG, "trip close: km=$distance kwh=$kwh " +
            "ext_temp=${open.startExteriorTemp}/${end.exteriorTemp} odo=$odoStart/$odoEnd")
        this.open = null
        lastOnFuel = null
        lastOnHev = null
        lastStateDao.clearOpenTrip()
    }

    /** Call once before subscribing to the loop. */
    suspend fun reconcileColdStart() {
        val state = lastStateDao.getCurrent() ?: return
        if (state.openTripId == null || state.tripStartTs == null) return
        val gap = now() - state.ts
        val active = !energyDataReader.isAvailable() || deadDetector.isDead()
        val staleGap = 5 * 60 * 1_000L
        if (gap < staleGap) {
            if (active) {
                open = Open(
                    state.tripStartTs, state.tripStartSoc, state.tripStartMileage,
                    state.tripStartTotalElec, state.tripStartExteriorTemp,
                    state.tripStartFuelTotal, state.tripStartMileageHev,
                )
                // The new edge starts off with an empty odometer window; without the seed the
                // first silent tick would close the trip that was running a moment ago.
                if (kind() == VehicleKind.HYBRID) hybridEdge.seedOn(now())
                Log.i(TAG, "trip resume (cold start): ts=${state.tripStartTs} ext_temp=${state.tripStartExteriorTemp}")
            }
            return
        }
        if (active) {
            val kwh = computeKwh(state.tripStartTotalElec, state.totalElec, state.tripStartSoc, state.soc, batteryCapacityKwh())
            val distance = plausibleDistance(state.tripStartMileage, state.mileage)
            val per100 = if (kwh != null && distance != null && distance > 0) kwh / distance * 100.0 else null
            val (odoStart, odoEnd) = odometerPair(state.tripStartMileage, state.mileage)
            // The end edge is what the last snapshot tick left in last_state; null there leaves
            // litres and engine km null, the start readings are still kept.
            val fuel = fuelColumns(
                state.tripStartFuelTotal, state.fuelTotal, state.tripStartMileageHev, state.mileageHev, distance,
            )
            tripDao.insert(
                TripEntity(
                    startTs = state.tripStartTs,
                    endTs = state.ts,
                    distanceKm = distance,
                    kwhConsumed = kwh,
                    kwhPer100km = per100,
                    socStart = state.tripStartSoc,
                    socEnd = state.soc,
                    exteriorTemp = state.tripStartExteriorTemp,
                    odometerStartKm = odoStart,
                    odometerEndKm = odoEnd,
                    source = TripSource.NATIVE_POLLING,
                    fuelLiters = fuel.fuelLiters,
                    hevKm = fuel.hevKm,
                    fuelTotalStart = fuel.fuelTotalStart,
                    fuelTotalEnd = fuel.fuelTotalEnd,
                    mileageHevStart = fuel.mileageHevStart,
                    mileageHevEnd = fuel.mileageHevEnd,
                )
            )
            Log.i(TAG, "trip close (cold start): km=$distance " +
                "ext_temp=${state.tripStartExteriorTemp} odo=$odoStart/$odoEnd")
        }
        lastStateDao.clearOpenTrip()
    }

    private companion object {
        const val TAG = "TripRecorder"

        /** Upper bound of a single trip's distance; above it the odometer delta is a
         *  scale change or a bad baseline, not a drive. */
        const val MAX_PLAUSIBLE_TRIP_KM = 1500.0

        /** Startup guard: the odometer fid reads 0 before the car reports it. */
        const val MIN_VALID_ODOMETER_KM = 1.0
    }
}
