package com.bydmate.app.voice

import com.bydmate.app.data.remote.DiParsData
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A read question of the built-in dictionary (`ASK <id>`), answered from the car's own readings
 * without the agent. [answer] is the short Russian sentence to say, or null when the car does
 * not report the value: the phrase then goes to the agent like an unrecognized one. Pure.
 */
enum class VehicleQuestion(val id: String) {
    OUTSIDE_TEMP("outside_temp"),
    CABIN_TEMP("cabin_temp"),
    SOC("soc"),
    RANGE("range"),
    AC_TEMP("ac_temp");

    /** [rangeKm] is the estimate the dashboard shows: the snapshot itself holds no range. A
     *  charge or a climate setpoint of 0 is a cold-start reading, not a value. */
    fun answer(d: DiParsData?, rangeKm: Double?): String? = when (this) {
        OUTSIDE_TEMP -> d?.exteriorTemp?.let { "Снаружи ${degrees(it)}." }
        CABIN_TEMP -> d?.insideTemp?.let { "В салоне ${degrees(it)}." }
        SOC -> d?.soc?.takeIf { it in 1..100 }?.let { "Заряд $it ${plural(it, "процент", "процента", "процентов")}." }
        RANGE -> rangeKm?.roundToInt()?.let { "Запас хода $it ${plural(it, "километр", "километра", "километров")}." }
        AC_TEMP -> d?.acTemp?.takeIf { it > 0 }?.let { "Климат на ${degrees(it)}." }
    }

    companion object {
        fun of(id: String): VehicleQuestion? = entries.firstOrNull { it.id == id }

        private fun degrees(t: Int): String {
            val n = abs(t)
            val said = "$n ${plural(n, "градус", "градуса", "градусов")}"
            return if (t < 0) "минус $said" else said
        }

        /** The Russian form of a noun after [n] >= 0: 1 градус, 2 градуса, 5 градусов, 11 градусов. */
        internal fun plural(n: Int, one: String, few: String, many: String): String = when {
            n % 100 in 11..14 -> many
            n % 10 == 1 -> one
            n % 10 in 2..4 -> few
            else -> many
        }
    }
}
