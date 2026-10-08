package com.bydmate.app.ui.automation

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.AirlineSeatReclineNormal
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.Kitchen
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.Window
import androidx.compose.ui.graphics.vector.ImageVector
import com.bydmate.app.R
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.util.appLocalizedContext
import kotlin.math.roundToInt

private const val CELSIUS = " °C"
private const val PERCENT = " %"
private const val SEAT_OFF = "关闭"

/** A stored command read as its level row: which family and which value. */
data class LevelValue(val family: LevelFamily, val value: Int)

/**
 * A graded car action edited as one row (minus, slider, plus, value) instead of a tile per value.
 * The command strings are the ones the fixed tiles stored, so old rules open as a row unchanged.
 * [pickerBefore] is the catalog entry (command or toggle target) the picker tile goes before;
 * null puts it at the end of its category.
 */
enum class LevelFamily @Suppress("LongParameterList") constructor( // one catalog row per family
    private val prefix: String,
    private val suffix: String,
    val range: IntRange,
    val step: Int,
    val default: Int,
    private val unit: String,
    @StringRes val nameRes: Int,
    @StringRes val tileRes: Int,
    @StringRes val categoryRes: Int,
    val offAtZero: Boolean = false,
    val pickerBefore: String? = null,
) {
    TEMPERATURE("设置温度", "", 16..33, 1, 22, CELSIUS, R.string.auto_level_temp, R.string.auto_level_tile_temp, R.string.auto_cat_climate, pickerBefore = "吹面"),
    FAN("风量", "", 1..7, 1, 3, "", R.string.auto_level_fan, R.string.auto_level_tile_fan, R.string.auto_cat_climate, pickerBefore = "吹面"),
    SEAT_HEAT_DRIVER("主驾座椅加热", "档", 0..5, 1, 3, "", R.string.auto_level_seat_heat_driver, R.string.auto_level_tile_seat_heat_driver, R.string.auto_cat_seats, true, ActionDispatcher.TOGGLE_SEAT_HEAT_DRIVER),
    SEAT_HEAT_PASSENGER("副驾座椅加热", "档", 0..5, 1, 3, "", R.string.auto_level_seat_heat_passenger, R.string.auto_level_tile_seat_heat_passenger, R.string.auto_cat_seats, true, ActionDispatcher.TOGGLE_SEAT_HEAT_PASSENGER),
    SEAT_VENT_DRIVER("主驾座椅通风", "档", 0..5, 1, 3, "", R.string.auto_level_seat_vent_driver, R.string.auto_level_tile_seat_vent_driver, R.string.auto_cat_seats, true, ActionDispatcher.TOGGLE_SEAT_VENT_DRIVER),
    SEAT_VENT_PASSENGER("副驾座椅通风", "档", 0..5, 1, 3, "", R.string.auto_level_seat_vent_passenger, R.string.auto_level_tile_seat_vent_passenger, R.string.auto_cat_seats, true, ActionDispatcher.TOGGLE_SEAT_VENT_PASSENGER),
    WINDOW_DRIVER("主驾打开", "", 0..100, 10, 50, PERCENT, R.string.auto_level_window_driver, R.string.auto_level_tile_window_driver, R.string.auto_cat_windows, pickerBefore = "主驾半开"),
    WINDOW_PASSENGER("副驾打开", "", 0..100, 10, 50, PERCENT, R.string.auto_level_window_passenger, R.string.auto_level_tile_window_passenger, R.string.auto_cat_windows, pickerBefore = "主驾半开"),
    WINDOW_REAR_LEFT("后左打开", "", 0..100, 10, 50, PERCENT, R.string.auto_level_window_rear_left, R.string.auto_level_tile_window_rear_left, R.string.auto_cat_windows, pickerBefore = "主驾半开"),
    WINDOW_REAR_RIGHT("后右打开", "", 0..100, 10, 50, PERCENT, R.string.auto_level_window_rear_right, R.string.auto_level_tile_window_rear_right, R.string.auto_cat_windows, pickerBefore = "主驾半开"),
    FRIDGE_COOL("冰箱制冷", "度", -6..6, 1, 0, CELSIUS, R.string.auto_level_fridge_cool, R.string.auto_level_tile_fridge_cool, R.string.auto_cat_fridge),
    FRIDGE_HEAT("冰箱制热", "度", 35..50, 1, 40, CELSIUS, R.string.auto_level_fridge_heat, R.string.auto_level_tile_fridge_heat, R.string.auto_cat_fridge);

    /** The command for [value]; a seat at 0 is its «关闭» command. */
    fun command(value: Int): String =
        if (offAtZero && value == 0) prefix + SEAT_OFF else "$prefix$value$suffix"

    /** «22 °C», «−3 °C», «30 %», «выкл». */
    fun valueText(value: Int, lc: Context): String =
        if (offAtZero && value == 0) lc.getString(R.string.auto_level_off)
        else (if (value < 0) "−${-value}" else "$value") + unit

    /** «Температура: 22 °C» — the action's saved name. */
    fun displayName(value: Int, lc: Context): String = "${lc.getString(nameRes)}: ${valueText(value, lc)}"

    /** The action a picker tile adds: this family at its default. */
    fun newAction(context: Context): ActionDef =
        ActionDef(command(default), displayName(default, context.appLocalizedContext()))

    val icon: ImageVector
        get() = when (this) {
            TEMPERATURE -> Icons.Outlined.Thermostat
            FAN -> Icons.Outlined.Air
            SEAT_HEAT_DRIVER, SEAT_HEAT_PASSENGER, SEAT_VENT_DRIVER, SEAT_VENT_PASSENGER -> Icons.Outlined.AirlineSeatReclineNormal
            WINDOW_DRIVER, WINDOW_PASSENGER, WINDOW_REAR_LEFT, WINDOW_REAR_RIGHT -> Icons.Outlined.Window
            FRIDGE_COOL -> Icons.Outlined.AcUnit
            FRIDGE_HEAT -> Icons.Outlined.Kitchen
        }

    private fun parse(command: String): LevelValue? {
        if (!command.startsWith(prefix)) return null
        val tail = command.removePrefix(prefix)
        if (offAtZero && tail == SEAT_OFF) return LevelValue(this, 0)
        if (!tail.endsWith(suffix)) return null
        val digits = tail.removeSuffix(suffix)
        if (!NUMBER.matches(digits)) return null
        val value = digits.toIntOrNull() ?: return null
        // A seat's 0 is only ever «关闭»; «0档» is no command of the car.
        if (offAtZero && value == 0) return null
        return if (value in range) LevelValue(this, value) else null
    }

    companion object {
        // Canonical digits only: «03» or «0100» are no command the write path knows.
        private val NUMBER = Regex("-?(0|[1-9]\\d*)")

        /** The family and value of a stored command, or null when it is no level command. */
        fun of(command: String): LevelValue? = entries.firstNotNullOfOrNull { it.parse(command) }
    }
}

/** The saved name of a level command («Температура: 22 °C»), or null for any other command. */
internal fun levelActionName(command: String, lc: Context): String? =
    LevelFamily.of(command)?.let { it.family.displayName(it.value, lc) }

/** The grid point next to [value] in the [up] direction; a value off the grid goes to its neighbour. */
internal fun levelStep(value: Int, range: IntRange, step: Int, up: Boolean): Int {
    val below = range.first + Math.floorDiv(value - range.first, step) * step
    val next = when {
        up -> below + step
        below == value -> value - step
        else -> below
    }
    return next.coerceIn(range)
}

/** The slider position [raw] rounded to the nearest grid point. */
internal fun levelSnap(raw: Float, range: IntRange, step: Int): Int =
    (range.first + ((raw - range.first) / step).roundToInt() * step).coerceIn(range)
