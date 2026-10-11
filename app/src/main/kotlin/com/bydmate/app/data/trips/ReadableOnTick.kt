package com.bydmate.app.data.trips

import com.bydmate.app.data.remote.DiParsData

/**
 * DM-i (#184): the ON tick whose counters are a usable trip end edge. The one rule shared by
 * TripRecorder (trip close, last_state) and CounterSnapshotWriter (the ignition_off row).
 *
 * After parking the CAN counters read null while the odometer window still says ON: such a tick
 * keeps the readable one before it. Once the car moved past that tick its counters are no end
 * edge, so the null tick replaces them: no litres, never a guess.
 */
class ReadableOnTick {
    var tickMs: Long? = null
        private set
    var fuelTotalL: Double? = null
        private set
    var mileageHevKm: Double? = null
        private set
    var mileageEvKm: Double? = null
        private set
    var odometerKm: Double? = null
        private set

    /** [isOnTick]: the car was on at [nowMs] by its own evidence. True when the edge moved here. */
    fun update(data: DiParsData, nowMs: Long, isOnTick: Boolean): Boolean {
        val fuel = data.fuelTotalL
        val hev = data.mileageHevKm?.toDouble()
        val prevOdometer = odometerKm
        val moved = data.mileage == null || prevOdometer == null || data.mileage > prevOdometer
        val readable = fuel != null || hev != null
        if (!isOnTick || !(readable || moved)) return false
        tickMs = nowMs
        fuelTotalL = fuel
        mileageHevKm = hev
        mileageEvKm = data.mileageEvKm?.toDouble()
        odometerKm = data.mileage
        return true
    }

    /** A resumed trip: the edge the previous process left in last_state. */
    fun seed(tickMs: Long, fuelTotalL: Double?, mileageHevKm: Double?, odometerKm: Double?) {
        this.tickMs = tickMs
        this.fuelTotalL = fuelTotalL
        this.mileageHevKm = mileageHevKm
        this.mileageEvKm = null
        this.odometerKm = odometerKm
    }

    fun clear() {
        tickMs = null
        fuelTotalL = null
        mileageHevKm = null
        mileageEvKm = null
        odometerKm = null
    }
}
