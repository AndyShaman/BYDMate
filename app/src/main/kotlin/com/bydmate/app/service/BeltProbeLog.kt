package com.bydmate.app.service

import android.os.SystemClock
import android.util.Log
import com.bydmate.app.data.remote.DiParsData

/**
 * The `belt probe:` line: every rear belt reading next to the rear seat occupancy, so a log of
 * a rear passenger buckling up shows which fids follow it (the Instrument ones the conditions
 * use, or the Setting candidates). Logged when any of them changes, at most once per
 * [MIN_GAP_MS]; a change inside the gap is logged when the gap is over.
 */
internal class BeltProbeLog(private val clock: () -> Long = { SystemClock.elapsedRealtime() }) {

    private var lastLine: String? = null
    private var lastAt = 0L

    /** The line to log for [data], or null when nothing changed or the last one is too recent. */
    @Synchronized
    fun line(data: DiParsData): String? {
        val line = "belt probe: instrument RL=${data.seatbeltRL} RM=${data.seatbeltRM} RR=${data.seatbeltRR} " +
            "setting RL=${data.rearBeltSettingRL} RM=${data.rearBeltSettingRM} RR=${data.rearBeltSettingRR} " +
            "occupancy RL=${data.occupancyRL} RM=${data.occupancyRM} RR=${data.occupancyRR}"
        if (line == lastLine) return null
        val now = clock()
        if (lastLine != null && now - lastAt < MIN_GAP_MS) return null
        lastLine = line
        lastAt = now
        return line
    }

    fun onSnapshot(data: DiParsData) {
        line(data)?.let { Log.i(TAG, it) }
    }

    companion object {
        private const val TAG = "TrackingService"
        const val MIN_GAP_MS = 5_000L
    }
}
