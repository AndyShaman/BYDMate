package com.bydmate.app.ui.trips

import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.nativestack.VehicleKind

/** Text of the «/100» cell; [colored] = paint it with the consumption scale. */
internal data class Per100Cell(val text: String, val colored: Boolean)

/**
 * The «/100» cell of a trip row (DM-i, #184). A trip without fuel keeps today's kWh/100 with the
 * consumption colour; a trip that burned fuel shows litres per 100 km of the whole trip with
 * [litersUnit], uncoloured: one column, no split between battery and engine km.
 */
internal fun per100Cell(
    kwhPer100: Double?,
    fuelLiters: Double?,
    distanceKm: Double?,
    litersUnit: String,
): Per100Cell {
    val km = distanceKm?.takeIf { it > 0.0 }
    val litersPer100 = fuelLiters?.takeIf { it > 0.0 }?.let { liters -> km?.let { liters / it * 100.0 } }
    return if (litersPer100 != null) {
        Per100Cell("%.1f %s".format(litersPer100, litersUnit), colored = false)
    } else {
        Per100Cell(kwhPer100?.let { "%.1f".format(it) } ?: "—", colored = kwhPer100 != null)
    }
}

/**
 * The "/100" cell of a period header (day or month, #458). When both electric consumption and
 * positive fuel consumption per 100 km are present, both show side by side uncoloured; otherwise
 * falls back to [per100Cell].
 */
internal fun periodPer100Cell(
    kwhPer100: Double?,
    fuelLiters: Double?,
    litersKm: Double?,
    litersUnit: String,
): Per100Cell {
    val km = litersKm?.takeIf { it > 0.0 }
    val litersPer100 = fuelLiters?.takeIf { it > 0.0 }?.let { liters -> km?.let { liters / it * 100.0 } }
    return if (kwhPer100 != null && litersPer100 != null) {
        Per100Cell("%.1f + %.1f %s".format(kwhPer100, litersPer100, litersUnit), colored = false)
    } else {
        per100Cell(kwhPer100, fuelLiters, litersKm, litersUnit)
    }
}

/** The «л» cell: litres of the trip, «—» when either counter edge was missing. */
internal fun litersCell(fuelLiters: Double?): String = fuelLiters?.let { "%.1f".format(it) } ?: "—"

/**
 * Weights of the trip table on Главная: время | длит. | км | кВт·ч | (л) | /100 | валюта.
 * The «л» column exists only on a hybrid; EV and UNKNOWN keep today's six columns.
 */
internal fun columnWeights(kind: VehicleKind): List<Float> =
    if (kind == VehicleKind.HYBRID) listOf(2.5f, 1f, 1f, 1f, 1f, 1f, 1f)
    else listOf(2.5f, 1f, 1f, 1f, 1f, 1f)

/**
 * Widths (dp) of the Поездки columns after the time cell: длит. | км | SOC | кВт·ч | (л) | /100 | валюта.
 * A hybrid gets the «л» column and a wider «/100» that holds «4.4 л».
 */
internal fun tripsColumnWidthsDp(kind: VehicleKind): List<Int> =
    if (kind == VehicleKind.HYBRID) listOf(44, 48, 58, 44, 44, 56, 56)
    else listOf(44, 48, 58, 44, 44, 56)

/** Sum of the trips' litres, null when none of them carries a litre figure. */
internal fun sumLiters(trips: List<TripEntity>): Double? =
    trips.mapNotNull { it.fuelLiters }.takeIf { it.isNotEmpty() }?.sum()

/**
 * Km of the trips that know their litres (a zero-litre trip counts), the denominator of
 * [sumLiters] per 100 km; null when none does. A trip with unknown litres would dilute it.
 */
internal fun litersKm(trips: List<TripEntity>): Double? =
    trips.filter { it.fuelLiters != null }.takeIf { it.isNotEmpty() }?.sumOf { it.distanceKm ?: 0.0 }

/**
 * Electric consumption per 100 km of a period (#458): null when no trip has a kWh reading or the
 * summed distance is not positive, so a hybrid header without kWh keeps the litres-only cell.
 */
internal fun periodKwhPer100(trips: List<TripEntity>): Double? {
    if (trips.none { it.kwhConsumed != null }) return null
    val km = trips.sumOf { it.distanceKm ?: 0.0 }
    if (km <= 0.0) return null
    return trips.sumOf { it.kwhConsumed ?: 0.0 } / km * 100.0
}
