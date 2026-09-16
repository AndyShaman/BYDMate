package com.bydmate.app.data.automation

import android.content.Context
import androidx.core.content.edit

/**
 * Last seat heating / ventilation step the app itself sent to each seat. A «toggle» that finds
 * the seat off has to pick a step to turn it back on, and the honest choice is the one the
 * driver used last rather than a fixed guess; until they have used one, the middle step 3.
 *
 * Only commands that came through the app are remembered — the head unit's own panel does not
 * report a step change to us, so the memory is «what we last asked for», nothing more.
 */
internal class SeatLevelMemory(private val context: Context) {

    /** Remembers the step of [command] when it is a seat command that sets one. */
    fun remember(command: String) {
        val (target, level) = parseSeatCommand(command) ?: return
        if (level <= 0) return
        prefs().edit { putInt(key(target), level) }
    }

    /** Step to turn [target] back on with. */
    fun lastLevel(target: String): Int = prefs().getInt(key(target), DEFAULT_LEVEL)

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(target: String) = "seat_level_$target"

    companion object {
        private const val PREFS = "seat_level_memory"

        /** Step used until the driver has set one of their own. */
        const val DEFAULT_LEVEL = 3

        /** Command prefix of every seat target, by toggle target id. */
        internal val SEAT_COMMAND_PREFIX = mapOf(
            ActionDispatcher.TOGGLE_SEAT_HEAT_DRIVER to "主驾座椅加热",
            ActionDispatcher.TOGGLE_SEAT_HEAT_PASSENGER to "副驾座椅加热",
            ActionDispatcher.TOGGLE_SEAT_VENT_DRIVER to "主驾座椅通风",
            ActionDispatcher.TOGGLE_SEAT_VENT_PASSENGER to "副驾座椅通风",
        )

        private val LEVEL = Regex("^(\\d+)档$")

        /**
         * Splits a seat command into its target and step: «主驾座椅加热3档» → driver heat, 3;
         * «主驾座椅加热关闭» → driver heat, 0. Null for anything that is not a seat command.
         */
        internal fun parseSeatCommand(command: String): Pair<String, Int>? {
            val (target, prefix) = SEAT_COMMAND_PREFIX.entries
                .firstOrNull { command.startsWith(it.value) }
                ?.let { it.key to it.value }
                ?: return null
            val tail = command.removePrefix(prefix)
            val level = when {
                tail == "关闭" -> 0
                else -> LEVEL.find(tail)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            }
            return target to level
        }
    }
}
