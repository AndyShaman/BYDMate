package com.bydmate.app.data.automation

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure geometric helpers for geofenced place triggers.
 *
 * Distances use the Haversine formula — sub-meter accurate for distances under a few kilometres,
 * which is all we need for 20–500 m place radii.
 */
object PlaceGeometry {
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Great-circle distance in metres between two (lat, lon) pairs in degrees. */
    fun distanceMeters(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2.0).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2.0).pow(2)
        val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
        return EARTH_RADIUS_M * c
    }

    /** True when (lat, lon) lies within `radiusM` metres of (centerLat, centerLon). */
    fun isInside(
        lat: Double, lon: Double,
        centerLat: Double, centerLon: Double,
        radiusM: Int
    ): Boolean {
        return distanceMeters(lat, lon, centerLat, centerLon) <= radiusM
    }
}
