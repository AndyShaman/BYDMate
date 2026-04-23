package com.bydmate.app.domain.calculator

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class Trend { NONE, DOWN, FLAT, UP }

data class ConsumptionState(
    /** null when the widget should show a dash (either no session or first 500 m). */
    val displayValue: Double?,
    val trend: Trend,
)

/**
 * Snapshot TrackingService persists to SharedPreferences so a mid-trip process kill
 * does not lose the session anchor. Re-handed to [ConsumptionAggregator.onSample] on
 * the first sample after restart.
 */
data class SessionBaseline(
    val sessionStartedAt: Long,
    val mileageStart: Double,
    val totalElecStart: Double,
)

/**
 * Per-session consumption aggregator for the floating widget.
 *
 * Session boundary is decided by TrackingService (primary signal: `powerState`
 * transition 0 → ≥1 for start, ≥1 → 0 for end; fallback: `TripTracker.state == DRIVING`
 * when `powerState` is unreliable). This class treats `sessionStartedAt` as an opaque
 * session ID — when it changes, per-session state resets.
 *
 * Display value (kWh / 100 km):
 *   - No session       → baseline EMA (ambient), null if EMA is 0.
 *   - cum dist < 0.5 km → null (first-500 m suppression).
 *   - 0.5–5 km         → cumulative session average since ignition-on.
 *   - ≥ 5 km           → rolling window over the last ≥5 km of driving.
 *
 * Trend arrow activates only when cumulative distance ≥ 2 km. It compares
 * `displayValue` to `baselineEma` with hysteresis (0.95/1.05 entry, 0.97/1.03 exit)
 * and a 60-second debounce.
 *
 * Persistence: [currentSessionBaseline] returns the captured `mileageStart` /
 * `totalElecStart` so TrackingService can save them. On process restart the saved
 * snapshot is passed back via `persistedBaseline`, and `onSample` resumes cumulative
 * mode from the ignition-on anchor instead of re-capturing from the current tick.
 * The rolling window itself is not persisted — it naturally refills over the first
 * few kilometres after restart.
 */
object ConsumptionAggregator {

    private const val MIN_DISPLAY_KM = 0.5      // first-500m suppression
    private const val MIN_TREND_KM = 2.0        // arrow activates from 2 km
    private const val ROLLING_WINDOW_KM = 5.0   // rolling window span
    private const val DEBOUNCE_MS = 60_000L

    private const val ENTER_DOWN = 0.95
    private const val ENTER_UP = 1.05
    private const val EXIT_DOWN_TO_FLAT = 0.97
    private const val EXIT_UP_TO_FLAT = 1.03

    private val _state = MutableStateFlow(ConsumptionState(null, Trend.NONE))
    val state: StateFlow<ConsumptionState> = _state

    // Per-session baseline
    private var sessionId: Long? = null
    private var mileageStart: Double? = null
    private var elecStart: Double? = null

    // Rolling buffer of (mileage, totalElec) tick snapshots trimmed so oldest pair
    // lies just past the 5-km horizon from the newest.
    private val window = ArrayDeque<Pair<Double, Double>>()

    // Trend debounce
    private var committedTrend: Trend = Trend.NONE
    private var candidateTrend: Trend = Trend.NONE
    private var candidateSince: Long = 0L

    @Synchronized
    fun onSample(
        now: Long,
        sessionStartedAt: Long?,
        mileageKm: Double?,
        totalElecKwh: Double?,
        baselineEma: Double,
        persistedBaseline: SessionBaseline? = null,
    ) {
        if (sessionStartedAt == null) {
            clearSessionState()
            publish(displayOrNull(baselineEma), Trend.NONE)
            return
        }

        // First sample for this session ID — set up baselines
        if (sessionId != sessionStartedAt) {
            sessionId = sessionStartedAt
            window.clear()
            committedTrend = Trend.NONE
            candidateTrend = Trend.NONE
            candidateSince = 0L
            if (persistedBaseline != null &&
                persistedBaseline.sessionStartedAt == sessionStartedAt
            ) {
                mileageStart = persistedBaseline.mileageStart
                elecStart = persistedBaseline.totalElecStart
            } else {
                mileageStart = mileageKm
                elecStart = totalElecKwh
            }
        } else {
            // Late-capture if first tick lacked data
            if (mileageStart == null && mileageKm != null) mileageStart = mileageKm
            if (elecStart == null && totalElecKwh != null) elecStart = totalElecKwh
        }

        if (mileageKm == null || totalElecKwh == null ||
            mileageStart == null || elecStart == null
        ) {
            publish(null, Trend.NONE)
            return
        }

        val cumKm = mileageKm - mileageStart!!
        val cumKwh = totalElecKwh - elecStart!!

        pushRolling(mileageKm, totalElecKwh)

        if (cumKm < MIN_DISPLAY_KM) {
            clearTrendCandidate()
            publish(null, Trend.NONE)
            return
        }

        val display = rollingAverage() ?: cumulativeAverage(cumKm, cumKwh)
        if (display == null) {
            clearTrendCandidate()
            publish(null, Trend.NONE)
            return
        }

        if (cumKm < MIN_TREND_KM) {
            clearTrendCandidate()
            publish(display, Trend.NONE)
            return
        }

        if (baselineEma <= 0.01) {
            publish(display, Trend.NONE)
            return
        }

        val ratio = display / baselineEma
        val candidate = candidateFor(committedTrend, ratio)
        updateDebounce(now, candidate)
        publish(display, committedTrend)
    }

    private fun cumulativeAverage(cumKm: Double, cumKwh: Double): Double? =
        if (cumKm > 0 && cumKwh > 0) cumKwh / cumKm * 100.0 else null

    private fun pushRolling(mileage: Double, elec: Double) {
        val last = window.lastOrNull()
        if (last != null && mileage < last.first) {
            // Mileage regression — odometer glitch or restore with stale tick; skip.
            return
        }
        window.addLast(mileage to elec)
        // Trim front while the second-oldest snapshot still covers the 5-km window.
        while (window.size >= 2) {
            val second = window.elementAt(1)
            if (window.last().first - second.first >= ROLLING_WINDOW_KM) {
                window.removeFirst()
            } else break
        }
    }

    private fun rollingAverage(): Double? {
        if (window.size < 2) return null
        val first = window.first()
        val last = window.last()
        val km = last.first - first.first
        val kwh = last.second - first.second
        return if (km >= ROLLING_WINDOW_KM && kwh > 0) kwh / km * 100.0 else null
    }

    @Synchronized
    fun currentSessionBaseline(): SessionBaseline? {
        val id = sessionId ?: return null
        val m = mileageStart ?: return null
        val e = elecStart ?: return null
        return SessionBaseline(id, m, e)
    }

    @Synchronized
    fun reset() {
        clearSessionState()
        _state.value = ConsumptionState(null, Trend.NONE)
    }

    private fun clearSessionState() {
        sessionId = null
        mileageStart = null
        elecStart = null
        window.clear()
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
