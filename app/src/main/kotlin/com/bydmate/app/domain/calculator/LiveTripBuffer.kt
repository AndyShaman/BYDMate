package com.bydmate.app.domain.calculator

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory rolling buffer of (mileage, totalElec) samples for the current
 * ignition session. Used by RangeAvgSource to compute a sliding-window average
 * over the most recent driven kilometers of the current session.
 *
 * Resets automatically when [onSample] sees a new sessionId or null sessionId
 * (ignition off).
 *
 * Includes idle-time totalElec growth (HVAC, 12V) by design — this is real
 * energy spent. Pollution from short-distance idle is suppressed at a higher
 * level by RangeAvgSource (live weight = 0 when sessionKm < 3).
 *
 * Not persisted across process death. On restore, buffer rebuilds from polls
 * within seconds; range falls back to historical-only meanwhile.
 */
@Singleton
class LiveTripBuffer @Inject constructor() {
    private data class Sample(val mileage: Double, val totalElec: Double, val timestamp: Long)

    private val samples = ArrayDeque<Sample>()
    private var currentSessionId: Long? = null
    private val mutex = Mutex()

    suspend fun onSample(mileage: Double?, totalElec: Double?, sessionId: Long?): Unit = mutex.withLock {
        if (sessionId == null) {
            samples.clear()
            currentSessionId = null
            return@withLock
        }
        if (sessionId != currentSessionId) {
            samples.clear()
            currentSessionId = sessionId
        }
        if (mileage == null || totalElec == null) return@withLock
        // DiPars startup race: occasional `Mileage:0` before the CAN bus delivers
        // the real odometer. Real BYD vehicles always ship far above 1 km, so a
        // sub-1-km reading is always a glitch.
        if (mileage < MIN_VALID_MILEAGE_KM) return@withLock
        val last = samples.lastOrNull()
        if (last != null) {
            if (mileage < last.mileage) return@withLock // odometer regression
            if (mileage - last.mileage > MAX_VALID_JUMP_KM) {
                // Stale baseline (legacy startup-race row, DiPars hiccup):
                // wipe and re-anchor on the new reading instead of letting a
                // fake huge dKm into the sliding window.
                samples.clear()
            }
        }
        samples.addLast(Sample(mileage, totalElec, System.currentTimeMillis()))
        while (samples.size > MAX_SAMPLES) samples.removeFirst()
    }

    suspend fun reset(): Unit = mutex.withLock {
        samples.clear()
        currentSessionId = null
    }

    suspend fun sessionKm(): Double = mutex.withLock {
        if (samples.size < 2) return@withLock 0.0
        (samples.last().mileage - samples.first().mileage).coerceAtLeast(0.0)
    }

    suspend fun sampleCount(): Int = mutex.withLock { samples.size }

    /**
     * Avg consumption (kWh/100km) over the most recent [windowKm] kilometers
     * of the current session. Returns null when:
     *   - fewer than 2 samples
     *   - dKm <= 0 (samples collapsed at same mileage)
     *   - dKwh < 0 (BMS recalibration during window)
     */
    suspend fun avgOverLastKm(windowKm: Double): Double? = mutex.withLock {
        if (samples.size < 2) return@withLock null
        val newest = samples.last()
        val cutoff = newest.mileage - windowKm
        // Earliest sample with mileage >= cutoff. Falls back to oldest if all are within window.
        val anchor = samples.firstOrNull { it.mileage >= cutoff } ?: samples.first()
        val dKm = newest.mileage - anchor.mileage
        val dKwh = newest.totalElec - anchor.totalElec
        if (dKm <= 0.0) return@withLock null
        if (dKwh < 0.0) return@withLock null
        dKwh / dKm * 100.0
    }

    companion object {
        const val MAX_SAMPLES = 1000
        const val MIN_VALID_MILEAGE_KM = 1.0
        const val MAX_VALID_JUMP_KM = 100.0
    }
}
