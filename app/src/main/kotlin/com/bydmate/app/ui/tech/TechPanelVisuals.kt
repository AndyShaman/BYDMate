package com.bydmate.app.ui.tech

import kotlin.math.abs

/**
 * Scale and threshold maths behind the small graphics on the «Техника» screen, kept out of the
 * composables so the numbers can be tested without a UI.
 */
internal object TechPanelVisuals {

    /** Overheat zones of a motor / inverter temperature. */
    enum class TempZone { OK, WARM, HOT }

    /** Yellow zone starts here, °C. */
    const val TEMP_WARM_C = 90

    /** Red zone starts here, °C. */
    const val TEMP_HOT_C = 120

    /** Full scale of the temperature bars, °C. */
    const val TEMP_FULL_SCALE_C = 150.0

    /** A tyre is flagged when it is this far from the average of the others. */
    private const val TYRE_DEVIATION = 0.10

    /**
     * Scale of the cell range strip. Defaults to the LFP working window and widens itself when a
     * pack (NMC, or a cell out of the window) sits outside it, so the band always fits.
     */
    fun cellScale(min: Double, max: Double): Pair<Double, Double> =
        minOf(3.25, min - 0.05) to maxOf(3.40, max + 0.05)

    fun tempZone(temp: Int): TempZone = when {
        temp < TEMP_WARM_C -> TempZone.OK
        temp < TEMP_HOT_C -> TempZone.WARM
        else -> TempZone.HOT
    }

    /**
     * Marks the tyres whose pressure is more than 10% off the average of the reported ones.
     * With fewer than two readings there is nothing to compare against, so nothing is flagged.
     */
    fun tyreDeviates(pressures: List<Int?>): List<Boolean> {
        val known = pressures.filterNotNull()
        if (known.size < 2) return List(pressures.size) { false }
        val mean = known.sum().toDouble() / known.size
        return pressures.map { it != null && abs(it - mean) > TYRE_DEVIATION * mean }
    }
}
