package com.bydmate.app.domain.calculator

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class Trend { NONE, DOWN, FLAT, UP }

data class ConsumptionState(
    /** kWh/100km to show on widget; null when nothing meaningful yet. */
    val displayValue: Double?,
    val trend: Trend,
)

/**
 * Stateless trend computer for the floating widget.
 *
 * TrackingService computes recent and short averages via OdometerConsumptionBuffer
 * and pushes them in via onSample. Aggregator's only stateful job is hysteresis +
 * debounce on the trend arrow.
 */
object ConsumptionAggregator {

    private const val DEBOUNCE_MS = 30_000L
    private const val ENTER_DOWN = 0.90
    private const val ENTER_UP = 1.10
    private const val EXIT_DOWN_TO_FLAT = 0.95
    private const val EXIT_UP_TO_FLAT = 1.05

    private val _state = MutableStateFlow(ConsumptionState(null, Trend.NONE))
    val state: StateFlow<ConsumptionState> = _state

    private var committedTrend: Trend = Trend.NONE
    private var candidateTrend: Trend = Trend.NONE
    private var candidateSince: Long = 0L

    /**
     * @param recentAvg     25-km rolling average from OdometerConsumptionBuffer.
     *                      Pass 0 (or negative) when buffer fallbacks to nothing.
     * @param shortAvg      2-km short window. Null when buffer has < 2 km of valid data.
     */
    @Synchronized
    fun onSample(
        now: Long,
        recentAvg: Double,
        shortAvg: Double?,
    ) {
        val display = if (recentAvg > 0.01) recentAvg else null
        if (display == null || shortAvg == null) {
            committedTrend = Trend.NONE
            candidateTrend = Trend.NONE
            candidateSince = 0L
            publish(display, Trend.NONE)
            return
        }
        val ratio = shortAvg / recentAvg
        val candidate = candidateFor(committedTrend, ratio)
        updateDebounce(now, candidate)
        publish(display, committedTrend)
    }

    @Synchronized
    fun reset() {
        committedTrend = Trend.NONE
        candidateTrend = Trend.NONE
        candidateSince = 0L
        _state.value = ConsumptionState(null, Trend.NONE)
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

    private fun publish(display: Double?, trend: Trend) {
        _state.value = ConsumptionState(display, trend)
    }
}
