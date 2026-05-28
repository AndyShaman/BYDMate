package com.bydmate.app.data.trips

/**
 * Stable wire values for [TripEntity.source]. Magic strings replaced here.
 */
object TripSource {
    const val LIVE = "live"
    const val ENERGYDATA = "energydata"
    const val NATIVE_POLLING = "native_polling"
    val all = listOf(LIVE, ENERGYDATA, NATIVE_POLLING)
}
