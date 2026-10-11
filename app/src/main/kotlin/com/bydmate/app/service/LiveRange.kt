package com.bydmate.app.service

import com.bydmate.app.data.nativestack.VehicleKind
import com.bydmate.app.data.remote.DiParsData

/**
 * Range of one poll tick. A DM-i reports its own electric range (null when it reports none) and
 * never gets [estimate]: SOC x capacity is not a range on a car whose engine refills the pack.
 */
internal suspend fun liveRangeKm(kind: VehicleKind, data: DiParsData, estimate: suspend () -> Double?): Double? =
    if (kind == VehicleKind.HYBRID) data.evRangeKm?.toDouble() else estimate()
