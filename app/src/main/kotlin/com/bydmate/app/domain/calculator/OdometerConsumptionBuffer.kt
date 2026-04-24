package com.bydmate.app.domain.calculator

import com.bydmate.app.data.local.dao.OdometerSampleDao
import com.bydmate.app.data.local.entity.OdometerSampleEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Singleton

data class BufferStatus(
    val rowCount: Int,
    val newestMileageKm: Double?,
    val oldestMileageKm: Double?,
    val recentAvg: Double,
    val shortAvg: Double?,
)

/**
 * Persistent rolling window of (mileage, totalElec, soc, sessionId, ts) snapshots.
 * Single source of truth for widget consumption display, trend arrow, and range
 * estimation. See spec 3.1 for algorithm details.
 *
 * SessionId boundary: pairs of consecutive samples (by mileage ASC) with
 * different sessionId values are skipped during averaging — they straddle an
 * ignition cycle, so the energy delta does not reflect "consumption while
 * driving" (could be overnight BMS drain, AC charging, etc).
 */
@Singleton
class OdometerConsumptionBuffer(
    private val dao: OdometerSampleDao,
    private val fallbackEmaProvider: suspend () -> Double,
) : ConsumptionAvgSource {
    private val mutex = Mutex()

    suspend fun onSample(
        mileage: Double?,
        totalElec: Double?,
        socPercent: Int?,
        sessionId: Long?,
    ): Unit = mutex.withLock {
        // Charging is handled by the built-in delta guards: mileage stays constant
        // during charging, so MIN_MILEAGE_DELTA suppresses inserts and dKm<=0
        // skips the pair during averaging. An explicit isCharging flag was
        // dropped in v2.4.7 because chargeGunState semantics differ per model
        // and the mis-detection silently blocked all driving samples.
        if (mileage == null) return@withLock
        val prev = dao.last()
        if (prev != null) {
            if (mileage < prev.mileageKm) return@withLock // odometer regression
            if (mileage - prev.mileageKm > 100.0) return@withLock // unrealistic jump
            val sameSession = prev.sessionId == sessionId
            val tinyMove = mileage - prev.mileageKm < MIN_MILEAGE_DELTA
            if (sameSession && tinyMove) return@withLock // spam suppression
        }
        dao.insert(
            OdometerSampleEntity(
                mileageKm = mileage,
                totalElecKwh = totalElec,
                socPercent = socPercent,
                sessionId = sessionId,
                timestamp = System.currentTimeMillis(),
            )
        )
        val newest = mileage
        dao.trimBelow(newest - WINDOW_KM - TRIM_HYSTERESIS_KM)
        val n = dao.count()
        if (n > MAX_BUFFER_ROWS) dao.deleteOldest(n - MAX_BUFFER_ROWS)
    }

    override suspend fun recentAvgConsumption(): Double = mutex.withLock { computeAvg(WINDOW_KM, fallbackOnShort = true) }

    suspend fun shortAvgConsumption(): Double? = mutex.withLock {
        val v = computeAvg(SHORT_WINDOW_KM, fallbackOnShort = false)
        if (v.isNaN()) null else v
    }

    suspend fun status(): BufferStatus = mutex.withLock {
        val all = dao.windowFrom(0.0)
        BufferStatus(
            rowCount = all.size,
            newestMileageKm = all.lastOrNull()?.mileageKm,
            oldestMileageKm = all.firstOrNull()?.mileageKm,
            recentAvg = computeAvgUnlocked(all, WINDOW_KM, fallbackOnShort = true),
            shortAvg = run {
                val v = computeAvgUnlocked(all, SHORT_WINDOW_KM, fallbackOnShort = false)
                if (v.isNaN()) null else v
            },
        )
    }

    private suspend fun computeAvg(windowKm: Double, fallbackOnShort: Boolean): Double {
        val newest = dao.last() ?: return if (fallbackOnShort) fallbackEmaProvider() else Double.NaN
        val samples = dao.windowFrom(newest.mileageKm - windowKm)
        return computeAvgUnlocked(samples, windowKm, fallbackOnShort)
    }

    private suspend fun computeAvgUnlocked(
        samples: List<OdometerSampleEntity>,
        windowKm: Double,
        fallbackOnShort: Boolean,
    ): Double {
        if (samples.size < 2) return if (fallbackOnShort) fallbackEmaProvider() else Double.NaN
        var totalKm = 0.0
        var totalKwh = 0.0
        for (i in 1..samples.lastIndex) {
            val prev = samples[i - 1]
            val cur = samples[i]
            if (prev.sessionId != cur.sessionId) continue
            val pe = prev.totalElecKwh
            val ce = cur.totalElecKwh
            if (pe == null || ce == null) continue
            val dKm = cur.mileageKm - prev.mileageKm
            val dKwh = ce - pe
            if (dKm <= 0.0 || dKwh < 0.0) continue
            totalKm += dKm
            totalKwh += dKwh
        }
        val minOk = if (fallbackOnShort) MIN_BUFFER_KM else windowKm
        if (totalKm < minOk) return if (fallbackOnShort) fallbackEmaProvider() else Double.NaN
        return totalKwh / totalKm * 100.0
    }

    companion object {
        const val WINDOW_KM = 25.0
        const val SHORT_WINDOW_KM = 2.0
        const val MIN_BUFFER_KM = 5.0
        const val MIN_MILEAGE_DELTA = 0.05
        const val MAX_BUFFER_ROWS = 500
        const val TRIM_HYSTERESIS_KM = 1.0
    }
}
