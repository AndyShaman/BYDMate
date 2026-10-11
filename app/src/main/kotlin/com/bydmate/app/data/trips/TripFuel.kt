package com.bydmate.app.data.trips

import kotlin.math.roundToLong

/**
 * DM-i fuel columns of one trip (#184), shared by TripRecorder and HistoryImporter so a live
 * trip and an imported one follow the same rules. All null on an EV trip.
 */
data class TripFuel(
    val fuelLiters: Double? = null,
    val hevKm: Double? = null,
    val fuelTotalStart: Double? = null,
    val fuelTotalEnd: Double? = null,
    val mileageHevStart: Double? = null,
    val mileageHevEnd: Double? = null,
) {
    companion object {
        /** Upper bound of the fuel one trip can burn; a larger delta is a counter glitch. */
        const val MAX_TRIP_LITERS = 60.0

        /** Engine km may exceed the trip odometer delta by this much (rounding of two counters). */
        const val HEV_KM_SLACK = 1.0

        /** Fuel counter delta rounded to 0.1 L, or null without both edges or outside [0, 60]. */
        fun liters(start: Double?, end: Double?): Double? {
            if (start == null || end == null) return null
            val delta = end - start
            if (delta < 0.0 || delta > MAX_TRIP_LITERS) return null
            return (delta * 10.0).roundToLong() / 10.0
        }

        /** Engine odometer delta, or null without both edges, without a distance, or outside [0, distance + 1]. */
        fun hevKm(start: Double?, end: Double?, distanceKm: Double?): Double? {
            if (start == null || end == null || distanceKm == null) return null
            val delta = end - start
            if (delta < 0.0 || delta > distanceKm + HEV_KM_SLACK) return null
            return delta
        }
    }
}
