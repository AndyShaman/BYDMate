package com.bydmate.app.domain.calculator

import com.bydmate.app.data.repository.TripRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Consumption source for RangeCalculator. Blends:
 *   - historical: weighted avg of last 3 completed trips (via TripRepository),
 *     50/30/20 weights, minKm=3, fallback 18 kWh/100km.
 *   - live: avg over last [LIVE_WINDOW_KM] km of current session (via LiveTripBuffer).
 *
 * Live weight scales linearly from 0 at [LIVE_WEIGHT_FLOOR_KM] to [LIVE_WEIGHT_CAP]
 * at [LIVE_WEIGHT_FULL_KM], capped at [LIVE_WEIGHT_CAP] beyond.
 *
 * Always returns a positive number — UI must display a value, never a dash.
 */
@Singleton
class RangeAvgSource(
    private val historicalProvider: suspend () -> Double,
    private val liveAvgProvider: suspend () -> Double?,
    private val sessionKmProvider: suspend () -> Double,
) : ConsumptionAvgSource {

    @Inject constructor(
        tripRepository: TripRepository,
        liveBuffer: LiveTripBuffer,
    ) : this(
        historicalProvider = {
            tripRepository.getWeightedHistoricalAvg(
                minKm = MIN_HISTORICAL_KM,
                weights = HISTORICAL_WEIGHTS,
                fallback = FALLBACK_KWH_PER_100KM,
            )
        },
        liveAvgProvider = { liveBuffer.avgOverLastKm(LIVE_WINDOW_KM) },
        sessionKmProvider = { liveBuffer.sessionKm() },
    )

    override suspend fun recentAvgConsumption(): Double {
        val historical = historicalProvider()
        val live = liveAvgProvider() ?: return historical
        val sessionKm = sessionKmProvider()
        val w = liveWeight(sessionKm)
        return w * live + (1.0 - w) * historical
    }

    private fun liveWeight(sessionKm: Double): Double {
        if (sessionKm <= LIVE_WEIGHT_FLOOR_KM) return 0.0
        if (sessionKm >= LIVE_WEIGHT_FULL_KM) return LIVE_WEIGHT_CAP
        val span = LIVE_WEIGHT_FULL_KM - LIVE_WEIGHT_FLOOR_KM
        return (sessionKm - LIVE_WEIGHT_FLOOR_KM) / span * LIVE_WEIGHT_CAP
    }

    companion object {
        const val LIVE_WINDOW_KM = 10.0
        const val LIVE_WEIGHT_FLOOR_KM = 3.0
        const val LIVE_WEIGHT_FULL_KM = 25.0
        const val LIVE_WEIGHT_CAP = 0.5
        const val MIN_HISTORICAL_KM = 3.0
        const val FALLBACK_KWH_PER_100KM = 18.0
        val HISTORICAL_WEIGHTS = listOf(0.5, 0.3, 0.2)
    }
}
