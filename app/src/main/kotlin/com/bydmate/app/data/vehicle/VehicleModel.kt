package com.bydmate.app.data.vehicle

import android.os.Build

/**
 * Per-model defaults. The settings UI lets the user override the model if autodetect gets it
 * wrong. Adding a new model = adding a row + a knowledge-base fill-in for [KEYCODE_PRESETS].
 *
 * Battery capacities are nominal (manufacturer-published) and only used as a fallback when BMS
 * doesn't report one. SoH / energydata availability gate features that some models don't expose
 * at all; do not assume they exist on every car.
 *
 * Cluster trigger defaults reflect the physical steering-wheel layout for each DiLink 5.0 model.
 * For models we haven't validated, [defaultClusterTriggerKeycode] is [NO_TRIGGER_KEYCODE] and the
 * user is forced through the learn-the-button dialog the first time they enable cluster projection
 * — that way the app works on any DiLink 5.0 car without code changes.
 */
enum class VehicleModel(
    val displayName: String,
    val batteryCapacityKwh: Double,
    val hasEnergydataDb: Boolean,
    val supportsSoh: Boolean,
    val defaultClusterTriggerKeycode: Int,
) {
    LEOPARD_3(
        displayName = "BYD Leopard 3 (Fangchengbao Tai 3)",
        batteryCapacityKwh = 72.9,
        hasEnergydataDb = true,
        supportsSoh = true,
        defaultClusterTriggerKeycode = 351,            // right star — validated
    ),
    TANG_L(
        displayName = "BYD Tang L",
        batteryCapacityKwh = 35.6,                    // TODO confirm from spec sheet
        hasEnergydataDb = true,                       // TODO confirm on hardware
        supportsSoh = true,                           // not exposed on this platform yet
        // Tang L steering-wheel layout differs from Leopard 3; the right star either
        // doesn't exist or has a different keycode. Force the user through learn-mode
        // the first time the master switch is turned on, so we never silently no-op.
        defaultClusterTriggerKeycode = NO_TRIGGER_KEYCODE,
    ),
    OTHER(
        displayName = "Другая (DiLink 5.0)",
        batteryCapacityKwh = 0.0,                      // unknown — fall back to BMS-reported value
        hasEnergydataDb = false,
        supportsSoh = false,
        defaultClusterTriggerKeycode = NO_TRIGGER_KEYCODE,
    );

    companion object {
        /**
         * Best-effort autodetect from `Build.MODEL` / `Build.PRODUCT` strings. Conservative: if we
         * can't tell, return [OTHER] (never silently guess a wrong model). The settings UI exposes
         * an explicit picker so the user can correct us.
         */
        fun autodetect(): VehicleModel {
            val haystack = (listOf(Build.MODEL, Build.PRODUCT, Build.DEVICE, Build.BOARD)
                .joinToString(" ")).lowercase()
            return when {
                "leopard" in haystack || "tai3" in haystack || "fangchengbao" in haystack ->
                    LEOPARD_3
                "tang l" in haystack || "tangl" in haystack || "tang-l" in haystack ->
                    TANG_L
                else -> OTHER
            }
        }

        /** Parse from persisted name; unknown values → [OTHER]. */
        fun fromName(name: String?): VehicleModel =
            entries.firstOrNull { it.name == name } ?: OTHER
    }
}
