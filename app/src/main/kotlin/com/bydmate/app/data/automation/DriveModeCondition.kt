package com.bydmate.app.data.automation

import com.bydmate.app.data.vehicle.DriveMode

/**
 * The value a DriveMode condition compares against. Saved rules keep the dev 1006 encoding the
 * condition always had (1 ECO, 2 SPORT, 3 NORMAL, 4 SNOW); the catalog adds 5 SAND, 6 MUD,
 * 7 MOUNTAIN, 21 SMART in that same encoding.
 *
 * dev 1006 reads 3 on normal AND on every terrain mode (Leopard 3 2026-09-27), so «Режим = Норма»
 * fired on sand. SETTING_TARGET_DRIVING_MODE tells them apart, but it is only believed once it has
 * proven itself on this car: the first sample where it maps to the same value the legacy fid
 * reads (any mode) makes it trusted for the rest of the process. From then on a known target mode
 * is the answer, so SAND -> ECO reads ECO at once instead of waiting on the legacy fid. Before
 * that, and whenever the target reads no known mode (flotation, a sentinel, garbage), the legacy
 * value stands. A car where the target fid never agrees keeps the legacy value, exactly as before.
 */
internal class DriveModeCondition(private val log: (String) -> Unit) {

    @Volatile private var trusted = false

    fun value(legacy: Int?, target: Int?): Int? {
        val mapped = target?.let { TARGET_TO_CONDITION[it] }
        if (!trusted && mapped != null && mapped == legacy) {
            trusted = true
            log("DriveMode: target fid trusted (legacy=$legacy target=$target)")
        }
        return if (trusted && mapped != null) mapped else legacy
    }

    private companion object {
        /** Target mode -> the condition encoding. ROCK is not offered on any car we know. */
        val TARGET_TO_CONDITION: Map<Int, Int> = mapOf(
            DriveMode.NORMAL.value to 3,
            DriveMode.ECO.value to 1,
            DriveMode.SPORT.value to 2,
            DriveMode.SNOW.value to 4,
            DriveMode.SAND.value to 5,
            DriveMode.MUD.value to 6,
            DriveMode.MOUNTAIN.value to 7,
            DriveMode.SMART.value to 21,
        )
    }
}
