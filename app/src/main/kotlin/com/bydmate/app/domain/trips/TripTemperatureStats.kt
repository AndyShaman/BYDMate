package com.bydmate.app.domain.trips

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Everything the «Расход и температура» screen draws, computed off the trip list alone: the
 * scatter points, the axis ranges, the median trend and the three range medians. Kept free of
 * Compose so the numbers can be tested; the screen only formats what comes out of here.
 *
 * A trip joins the picture when it knows its outside temperature and its own consumption, and
 * is long enough for that consumption to mean anything — under five kilometres a cold start
 * dominates the average and the point would only add noise. [trips] is the already-filtered
 * list of drives that carry a temperature.
 */
class TripTemperatureStats(trips: List<Trip>) {

    /** One trip as the chart sees it. [tempC] is already averaged over start and finish. */
    data class Trip(val tempC: Double, val kwhPer100km: Double, val km: Double)

    /** A drawn dot: [big] marks the long trips, whose consumption is the more trustworthy one. */
    data class Point(val tempC: Double, val kwhPer100km: Double, val big: Boolean)

    /** Inclusive axis bounds. */
    data class Range(val min: Double, val max: Double)

    /** Median consumption of one 5 °C bin, placed at the bin's centre. */
    data class TrendPoint(val tempC: Double, val kwhPer100km: Double)

    /** What the screen shows instead of, or next to, the chart. */
    enum class State { NOT_ENOUGH, NARROW_RANGE, OK }

    /** The one-line closing sentence. [WINTER_NOT_HIGHER]: both medians exist, the cold one is not above. */
    enum class Verdict { WINTER_HIGHER, WINTER_NOT_HIGHER, NARROW_RANGE, NONE }

    /** Trips that carry a temperature at all — the counter every state shows. */
    val tripsWithTemp: Int = trips.size

    val points: List<Point> = trips
        .filter { it.km >= MIN_TRIP_KM && it.kwhPer100km > 0.0 }
        .map { Point(it.tempC, it.kwhPer100km, it.km > BIG_POINT_KM) }
        .sortedBy { it.tempC }

    val shown: Int = points.size

    private val bins: Map<Int, List<Point>> = points.groupBy { floor(it.tempC / BIN_WIDTH_C).toInt() }

    /** Number of filled 5 °C bins — the trend gate and the log line both use it. */
    val filledBins: Int = bins.size

    val tempSpanC: Double =
        if (points.isEmpty()) 0.0 else points.maxOf { it.tempC } - points.minOf { it.tempC }

    val state: State = when {
        tripsWithTemp < MIN_TRIPS -> State.NOT_ENOUGH
        tempSpanC < MIN_SPAN_C -> State.NARROW_RANGE
        else -> State.OK
    }

    /** Temperature axis: data rounded outwards to 5 °C, never narrower than 20 °C. */
    val tempAxis: Range = if (points.isEmpty()) Range(-20.0, 0.0) else axis(
        lo = floor(points.minOf { it.tempC } / AXIS_STEP_C) * AXIS_STEP_C,
        hi = ceil(points.maxOf { it.tempC } / AXIS_STEP_C) * AXIS_STEP_C,
    )

    /** Consumption axis: at least the 15..45 window, widened to fit the data. */
    val consumptionAxis: Range = if (points.isEmpty()) {
        Range(DEFAULT_KWH_MIN, DEFAULT_KWH_MAX)
    } else {
        Range(
            minOf(DEFAULT_KWH_MIN, floor(points.minOf { it.kwhPer100km } / AXIS_STEP_C) * AXIS_STEP_C),
            maxOf(DEFAULT_KWH_MAX, ceil(points.maxOf { it.kwhPer100km } / AXIS_STEP_C) * AXIS_STEP_C),
        )
    }

    /**
     * Median consumption per 5 °C bin. Two bins joined by a line would read as a law where
     * there is only a pair of averages, so the line needs three filled bins and a temperature
     * span wide enough to have a slope at all.
     */
    val trend: List<TrendPoint> =
        if (filledBins < MIN_TREND_BINS || tempSpanC < MIN_SPAN_C) {
            emptyList()
        } else {
            bins.toSortedMap().map { (bin, inBin) ->
                TrendPoint(
                    tempC = bin * BIN_WIDTH_C + BIN_WIDTH_C / 2.0,
                    kwhPer100km = median(inBin.map { it.kwhPer100km })!!,
                )
            }
        }

    val medianCold: Double? = medianOf { it.tempC < 0.0 }
    val medianMild: Double? = medianOf { it.tempC >= 0.0 && it.tempC <= MILD_MAX_C }
    val medianWarm: Double? = medianOf { it.tempC > MILD_MAX_C }

    /** Cold median against the warm one, signed percent (negative = cheaper in the cold); null without both. */
    val winterHigherPercent: Int? =
        if (medianCold != null && medianWarm != null && medianWarm > 0.0) {
            ((medianCold - medianWarm) / medianWarm * 100.0).roundToInt()
        } else null

    val verdict: Verdict = when {
        state == State.NOT_ENOUGH -> Verdict.NONE
        state == State.NARROW_RANGE -> Verdict.NARROW_RANGE
        winterHigherPercent == null -> Verdict.NONE
        winterHigherPercent > 0 -> Verdict.WINTER_HIGHER
        else -> Verdict.WINTER_NOT_HIGHER
    }

    /** Whole-degree bounds of the drawn points, for the «all trips at +A…+B °C» note. */
    val observedRange: Pair<Int, Int>? = points
        .takeIf { it.isNotEmpty() }
        ?.let { pts -> pts.minOf { it.tempC }.roundToInt() to pts.maxOf { it.tempC }.roundToInt() }

    private fun medianOf(inRange: (Point) -> Boolean): Double? {
        val values = points.filter(inRange).map { it.kwhPer100km }
        return if (values.size < MIN_TRIPS_PER_RANGE) null else median(values)
    }

    private fun axis(lo: Double, hi: Double): Range {
        val missing = MIN_TEMP_AXIS_WIDTH_C - (hi - lo)
        if (missing <= 0.0) return Range(lo, hi)
        val pad = ceil(missing / 2.0 / AXIS_STEP_C) * AXIS_STEP_C
        return Range(lo - pad, hi + pad)
    }

    companion object {
        /** Below this a cold start dominates the trip's own average. */
        const val MIN_TRIP_KM = 5.0

        /** Above this the dot is drawn larger: a long trip's consumption is the steadier one. */
        const val BIG_POINT_KM = 20.0

        /** Fewer trips than this and the screen says so instead of drawing a shape. */
        const val MIN_TRIPS = 8

        /** A range median needs this many trips before it is worth showing. */
        const val MIN_TRIPS_PER_RANGE = 3

        /** Narrower than this and there is no temperature effect to see yet. */
        const val MIN_SPAN_C = 15.0

        const val BIN_WIDTH_C = 5.0
        const val MIN_TREND_BINS = 3
        const val AXIS_STEP_C = 5.0
        const val MIN_TEMP_AXIS_WIDTH_C = 20.0
        const val MILD_MAX_C = 15.0
        const val DEFAULT_KWH_MIN = 15.0
        const val DEFAULT_KWH_MAX = 45.0

        /** Plain median; the mean of the two middles on an even count. */
        fun median(values: List<Double>): Double? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
        }
    }
}
