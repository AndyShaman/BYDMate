package com.bydmate.app.data.automation

import com.bydmate.app.data.vehicle.DriveMode

/**
 * The value a DriveMode condition compares against. Saved rules keep the dev 1006 encoding the
 * condition always had (1 ECO, 2 SPORT, 3 NORMAL, 4 SNOW); the catalog adds 5 SAND, 6 MUD,
 * 7 MOUNTAIN, 21 SMART in that same encoding.
 *
 * dev 1006 reads 3 on normal AND on every terrain mode (Leopard 3 2026-09-27), so «Режим = Норма»
 * fired on sand. SETTING_TARGET_DRIVING_MODE tells them apart, but only refines that 3: when the
 * legacy fid reads a mode of its own (ECO, SPORT, SNOW) it stays the answer, and a target value
 * that is not a known mode (flotation, a firmware answering garbage) never replaces it. A car
 * where the target fid does not read keeps the legacy value, exactly as before.
 */
internal object DriveModeCondition {

    /** dev 1006 NORMAL: the one legacy value the target fid is allowed to refine. */
    private const val LEGACY_NORMAL = 3

    /** Target mode -> the condition encoding. ROCK is not offered on any car we know. */
    private val TARGET_TO_CONDITION: Map<Int, Int> = mapOf(
        DriveMode.NORMAL.value to LEGACY_NORMAL,
        DriveMode.ECO.value to 1,
        DriveMode.SPORT.value to 2,
        DriveMode.SNOW.value to 4,
        DriveMode.SAND.value to 5,
        DriveMode.MUD.value to 6,
        DriveMode.MOUNTAIN.value to 7,
        DriveMode.SMART.value to 21,
    )

    /** Values that may stand in for a legacy 3: normal itself and the modes 3 hides. */
    private val REFINES_NORMAL = setOf(LEGACY_NORMAL, 5, 6, 7, 21)

    fun value(legacy: Int?, target: Int?): Int? {
        val mapped = target?.let { TARGET_TO_CONDITION[it] } ?: return legacy
        return when {
            legacy == null -> mapped
            legacy == LEGACY_NORMAL && mapped in REFINES_NORMAL -> mapped
            else -> legacy
        }
    }
}
