package com.bydmate.app.domain.calculator

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class Trend { NONE, DOWN, FLAT, UP }

data class ConsumptionState(
    /** null when neither EMA nor trip-avg are usable (e.g. fresh install, no trip) */
    val displayValue: Double?,
    val trend: Trend,
)

/**
 * Accumulates current-trip consumption and decides what to display on the floating widget.
 *
 * Number:
 *   - No active trip or trip age < 3 min → weeklyEma (or null when EMA is 0)
 *   - Active trip, age ≥ 3 min, tripKm > 0.1 → trip-avg (tripKwh / tripKm * 100)
 *   - Else → weeklyEma fallback
 *
 * Trend: compares trip-avg against weeklyEma with hysteresis + 60-sec debounce.
 * Stays NONE while we're still showing EMA.
 *
 * Pure state machine — no timers. Caller feeds `now` on each sample (3-sec polling cadence).
 */
object ConsumptionAggregator {

    private const val PREWARMUP_MS = 180_000L       // 3 min
    private const val MIN_TRIP_KM = 0.1             // 100 m
    private const val DEBOUNCE_MS = 60_000L         // 60 sec

    private const val ENTER_DOWN = 0.95
    private const val ENTER_UP = 1.05
    private const val EXIT_DOWN_TO_FLAT = 0.97
    private const val EXIT_UP_TO_FLAT = 1.03

    private val _state = MutableStateFlow(ConsumptionState(null, Trend.NONE))
    val state: StateFlow<ConsumptionState> = _state

    // Trip accumulation state
    private var currentTripStart: Long? = null
    private var mileageStart: Double? = null
    private var totalElecStart: Double? = null

    // Trend debounce state
    private var committedTrend: Trend = Trend.NONE
    private var candidateTrend: Trend = Trend.NONE
    private var candidateSince: Long = 0L

    /**
     * Called every polling tick (~3 sec).
     *
     * @param now system millis (pass System.currentTimeMillis() in prod, synthetic in tests)
     * @param tripStartedAt start timestamp of active trip, or null when IDLE
     * @param mileageKm DiPars odometer in km, or null when missing
     * @param totalElecKwh DiPars cumulative electricity consumption in kWh, or null
     * @param weeklyEma 7-day EMA from TripRepository; 0.0 means cold-install / no history
     */
    @Synchronized
    fun onSample(
        now: Long,
        tripStartedAt: Long?,
        mileageKm: Double?,
        totalElecKwh: Double?,
        weeklyEma: Double,
    ) {
        // Trip ended — clean trip-state
        if (tripStartedAt == null) {
            clearTripState()
            publish(displayOrNull(weeklyEma), Trend.NONE)
            return
        }

        // New trip — capture baselines on first sample with valid mileage/elec
        if (currentTripStart != tripStartedAt) {
            currentTripStart = tripStartedAt
            mileageStart = null
            totalElecStart = null
            candidateTrend = Trend.NONE
            committedTrend = Trend.NONE
        }
        if (mileageStart == null && mileageKm != null) mileageStart = mileageKm
        if (totalElecStart == null && totalElecKwh != null) totalElecStart = totalElecKwh

        val age = now - tripStartedAt
        val tripKm = if (mileageKm != null && mileageStart != null) mileageKm - mileageStart!! else null
        val tripKwh = if (totalElecKwh != null && totalElecStart != null) totalElecKwh - totalElecStart!! else null

        val tripAvg: Double? = if (tripKm != null && tripKm > MIN_TRIP_KM && tripKwh != null && tripKwh > 0) {
            tripKwh / tripKm * 100.0
        } else null

        // Prewarmup or no trip-avg yet → show EMA, no arrow
        if (age < PREWARMUP_MS || tripAvg == null) {
            clearTrendCandidate()
            publish(displayOrNull(weeklyEma), Trend.NONE)
            return
        }

        // Past prewarmup and trip-avg valid — show trip-avg
        val candidate = if (weeklyEma <= 0.01) Trend.FLAT
        else candidateFor(committedTrend, tripAvg / weeklyEma)

        updateDebounce(now, candidate)
        publish(tripAvg, if (weeklyEma <= 0.01) Trend.NONE else committedTrend)
    }

    @Synchronized
    fun reset() {
        clearTripState()
        committedTrend = Trend.NONE
        candidateTrend = Trend.NONE
        candidateSince = 0L
        _state.value = ConsumptionState(null, Trend.NONE)
    }

    private fun clearTripState() {
        currentTripStart = null
        mileageStart = null
        totalElecStart = null
        committedTrend = Trend.NONE
        candidateTrend = Trend.NONE
        candidateSince = 0L
    }

    private fun clearTrendCandidate() {
        candidateTrend = Trend.NONE
        committedTrend = Trend.NONE
        candidateSince = 0L
    }

    private fun candidateFor(current: Trend, ratio: Double): Trend = when (current) {
        Trend.NONE, Trend.FLAT -> when {
            ratio < ENTER_DOWN -> Trend.DOWN
            ratio > ENTER_UP -> Trend.UP
            else -> Trend.FLAT
        }
        Trend.DOWN -> when {
            ratio > ENTER_UP -> Trend.UP
            ratio >= EXIT_DOWN_TO_FLAT -> Trend.FLAT
            else -> Trend.DOWN
        }
        Trend.UP -> when {
            ratio < ENTER_DOWN -> Trend.DOWN
            ratio <= EXIT_UP_TO_FLAT -> Trend.FLAT
            else -> Trend.UP
        }
    }

    private fun updateDebounce(now: Long, candidate: Trend) {
        if (candidate == committedTrend) {
            // Committed already matches → no-op, reset candidate to match
            candidateTrend = candidate
            candidateSince = now
            return
        }
        if (candidate != candidateTrend) {
            candidateTrend = candidate
            candidateSince = now
            return
        }
        if (now - candidateSince >= DEBOUNCE_MS) {
            committedTrend = candidate
        }
    }

    private fun displayOrNull(value: Double): Double? = if (value > 0.01) value else null

    private fun publish(display: Double?, trend: Trend) {
        _state.value = ConsumptionState(display, trend)
    }
}
