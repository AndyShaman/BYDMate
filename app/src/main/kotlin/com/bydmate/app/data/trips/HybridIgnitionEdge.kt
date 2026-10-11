package com.bydmate.app.data.trips

import com.bydmate.app.data.remote.DiParsData

/**
 * Ignition state of a DM-i hybrid, where powerState alone is not trusted (Song Pro DM-i).
 * On while any of three signs holds: powerState == 2, the engine turns, or the odometer grew
 * within the last [ODOMETER_WINDOW_MS]. Off only after [OFF_AFTER_MS] with none of them.
 */
class HybridIgnitionEdge {

    private val odometer = ArrayDeque<Pair<Long, Double>>()
    private var on = false

    /** Epoch ms of the last tick where the car was on (showed a sign of running); 0 before the first. */
    var lastOnMs = 0L
        private set

    /**
     * A trip resumed after a process restart was running when the old process died: start on,
     * so the [OFF_AFTER_MS] silence is counted from [nowMs] rather than closing it on the first tick.
     */
    fun seedOn(nowMs: Long) {
        on = true
        lastOnMs = nowMs
    }

    fun update(data: DiParsData, nowMs: Long): Boolean {
        val rpm = data.engineRpm
        // Fed on every tick, so the window is full when powerState and rpm fall silent.
        val grew = odometerGrew(data.mileage, nowMs)
        val evidence = data.powerState == 2 || (rpm != null && rpm > 0) || grew
        if (evidence) {
            on = true
            lastOnMs = nowMs
        } else if (on && nowMs - lastOnMs >= OFF_AFTER_MS) {
            on = false
        }
        return on
    }

    private fun odometerGrew(mileage: Double?, nowMs: Long): Boolean {
        while (odometer.isNotEmpty() && nowMs - odometer.first().first > ODOMETER_WINDOW_MS) odometer.removeFirst()
        if (mileage == null) return false
        val grew = odometer.any { mileage > it.second }
        odometer.addLast(nowMs to mileage)
        return grew
    }

    companion object {
        const val ODOMETER_WINDOW_MS = 60_000L
        const val OFF_AFTER_MS = 90_000L
    }
}
