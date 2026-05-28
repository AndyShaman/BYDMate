package com.bydmate.app.data.vehicle

import android.util.Log
import org.json.JSONObject

/**
 * Hardcoded action_name → write address map. Task C.1 populates PRODUCTION
 * from competitor JSON (123 entries, source="competitor-v80") merged with
 * LIVE_VALIDATED (23 entries verified on Leopard 3 2026-05-28). Live entries
 * win on name collision.
 *
 * Competitor JSON shape (competitor-actions.json asset, from pushFidConfig.json):
 *   { "<actionName>": { "featureId": Int, "deviceType": Int, "value"?: Int }, ... }
 * Entries without "value" are range/variable params; stored with valueMin=valueMax=0.
 *
 * Every entry MUST sit on a comfort-tier dev namespace. The invariant test
 * (WriteAllowlistTest) refuses to compile a release against any entry
 * targeting dev ∈ {1004, 1006, 1007, 1009, 1011, 1012, 1013, 1014, 1016, 1023, 1032}.
 *
 * Banned namespace reference:
 * - 1004 = light enums (firmware-protected)
 * - 1006 = drive mode
 * - 1007 = seatbelt sensor
 * - 1009 = charging gun state
 * - 1011 = gear
 * - 1012 = engine power
 * - 1013 = vehicle speed
 * - 1014 = BMS statistics
 * - 1016 = tire pressure
 * - 1023 = global powerState
 * - 1032 = door lock state-of-truth
 */
data class WriteEntry(
    val actionName: String,
    val dev: Int,
    val writeFid: Int,
    val readbackFid: Int?,
    val valueMin: Int,
    val valueMax: Int,
    val category: String,
    val validated: Boolean,
    val source: String,
)

class WriteAllowlist(private val map: Map<String, WriteEntry>) {
    val size: Int get() = map.size
    val validatedCount: Int get() = map.values.count { it.validated }
    val entries: Collection<WriteEntry> get() = map.values
    fun find(actionName: String): WriteEntry? = map[actionName.lowercase()]
    fun allEntries(): Collection<WriteEntry> = map.values
    fun entriesByCategory(category: String): Collection<WriteEntry> =
        map.values.filter { it.category == category }

    companion object {
        private const val TAG = "WriteAllowlist"

        val EMPTY: WriteAllowlist = WriteAllowlist(emptyMap())

        val BANNED_DEVS: Set<Int> = setOf(
            1004, 1006, 1007, 1009, 1011, 1012, 1013, 1014, 1016, 1023, 1032
        )

        // 23 entries live-validated on Leopard 3 2026-05-28 via HelperDaemon.
        val LIVE_VALIDATED: List<WriteEntry> = listOf(
            // climate (dev=1000)
            WriteEntry("ac_on",          1000, 501219364, null, 2, 2,   "climate",  true, "live-leopard3-2026-05-28"),
            WriteEntry("ac_off",         1000, 501219364, null, 1, 1,   "climate",  true, "live-leopard3-2026-05-28"),
            WriteEntry("ac_temp_main",   1000, 501219368, null, 16, 30, "climate",  true, "live-leopard3-2026-05-28"),
            WriteEntry("ac_cycle_inner", 1000, 501219355, null, 1, 1,   "climate",  true, "live-leopard3-2026-05-28"),
            WriteEntry("ac_cycle_outer", 1000, 501219355, null, 2, 2,   "climate",  true, "live-leopard3-2026-05-28"),

            // windows competitor short-form (front only)
            WriteEntry("window_driver_open",     1001, 1125122104, null, 1, 1, "windows", true, "live-leopard3-2026-05-28"),
            WriteEntry("window_driver_close",    1001, 1125122104, null, 2, 2, "windows", true, "live-leopard3-2026-05-28"),
            WriteEntry("window_passenger_open",  1001, 1125122107, null, 1, 1, "windows", true, "live-leopard3-2026-05-28"),
            WriteEntry("window_passenger_close", 1001, 1125122107, null, 2, 2, "windows", true, "live-leopard3-2026-05-28"),

            // windows Leopard 3 % path (preferred — all 4 doors, 0..100%)
            WriteEntry("window_driver_pos",      1001, 1276219408, null, 0, 100, "windows", true, "live-leopard3-2026-05-28"),
            WriteEntry("window_passenger_pos",   1001, 1276219424, null, 0, 100, "windows", true, "live-leopard3-2026-05-28"),
            WriteEntry("window_rear_left_pos",   1001, 1276219416, null, 0, 100, "windows", true, "live-leopard3-2026-05-28"),
            WriteEntry("window_rear_right_pos",  1001, 1276219432, null, 0, 100, "windows", true, "live-leopard3-2026-05-28"),

            // sunroof
            WriteEntry("sunroof_open",    1001, 1125122056, null, 1, 1, "sunroof", true, "live-leopard3-2026-05-28"),
            WriteEntry("sunroof_close",   1001, 1125122056, null, 2, 2, "sunroof", true, "live-leopard3-2026-05-28"),
            WriteEntry("sunroof_tilt",    1001, 1125122056, null, 3, 3, "sunroof", true, "live-leopard3-2026-05-28"),
            WriteEntry("sunroof_stop",    1001, 1125122056, null, 4, 4, "sunroof", true, "live-leopard3-2026-05-28"),
            WriteEntry("sunroof_updip",   1001, 1125122056, null, 5, 5, "sunroof", true, "live-leopard3-2026-05-28"),
            WriteEntry("sunroof_comfort", 1001, 1125122056, null, 6, 6, "sunroof", true, "live-leopard3-2026-05-28"),

            // sunshade
            WriteEntry("sunshade_open",  1001, 1125122060, null, 1, 1, "sunshade", true, "live-leopard3-2026-05-28"),
            WriteEntry("sunshade_close", 1001, 1125122060, null, 2, 2, "sunshade", true, "live-leopard3-2026-05-28"),

            // locks (readback fid 1081081864 mirrors write value)
            WriteEntry("doors_unlock", 1001, 1276141590, 1081081864, 1, 1, "locks", true, "live-leopard3-2026-05-28"),
            WriteEntry("doors_lock",   1001, 1276141590, 1081081864, 2, 2, "locks", true, "live-leopard3-2026-05-28"),
        )

        /**
         * Category inference from action name prefix.
         * Order matters — check most-specific prefixes first.
         */
        private fun inferCategory(name: String): String = when {
            name.startsWith("ac_") || name.startsWith("set_driver_temp") ||
                name.startsWith("set_passenger_temp") || name.contains("defrost") -> "climate"
            name.startsWith("window_") || name.startsWith("windows_") ||
                name.startsWith("close_windows") || name.startsWith("open_windows") -> "windows"
            name.contains("lock") || name.startsWith("door") ||
                name.startsWith("unlock") -> "locks"
            name.startsWith("sunroof_") || name.startsWith("open_sunroof") ||
                name.startsWith("close_sunroof") || name.startsWith("tilt_sunroof") ||
                name.startsWith("comfort_sunroof") -> "sunroof"
            name.startsWith("sunshade_") || name.startsWith("open_sunshade") ||
                name.startsWith("close_sunshade") || name.startsWith("set_sunshade") -> "sunshade"
            name.startsWith("trunk_") || name.startsWith("open_trunk") ||
                name.startsWith("close_trunk") -> "trunk"
            name.startsWith("drl_") || name.contains("light") -> "lights"
            name.contains("seat") && (name.contains("heat") || name.contains("vent")) ||
                name.contains("massage") -> "seats"
            name.contains("mirror") -> "mirrors"
            name.contains("steering") || name.contains("wheel_heat") -> "climate"
            else -> "other"
        }

        /**
         * Parse competitor-actions.json, merge with LIVE_VALIDATED (live wins),
         * filter banned devs (logged as W "WriteAllowlist").
         *
         * [assetReader] returns the raw JSON text of competitor-actions.json.
         */
        fun loadProduction(assetReader: () -> String): WriteAllowlist {
            // Fail-fast: LIVE_VALIDATED must have no duplicate actionName (case-insensitive).
            val liveKeys = LIVE_VALIDATED.map { it.actionName.lowercase() }
            require(liveKeys.size == liveKeys.distinct().size) {
                val dupes = liveKeys.groupBy { it }.filter { it.value.size > 1 }.keys
                "LIVE_VALIDATED has duplicate actionName(s) (case-insensitive): $dupes"
            }

            val json = JSONObject(assetReader())
            // Key = lowercase actionName for collision detection; value preserves original casing.
            val merged = mutableMapOf<String, WriteEntry>()

            // Parse competitor entries first (keyed by lowercase, entry stores original name)
            val keys = json.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                try {
                    val obj = json.getJSONObject(name)
                    val dev = obj.getInt("deviceType")
                    val fid = obj.getInt("featureId")
                    val hasValue = obj.has("value")
                    val value = if (hasValue) obj.getInt("value") else 0
                    if (dev in BANNED_DEVS) {
                        Log.w(TAG, "Dropping banned-dev action=$name dev=$dev")
                        continue
                    }
                    merged[name.lowercase()] = WriteEntry(
                        actionName = name,
                        dev = dev,
                        writeFid = fid,
                        readbackFid = null,
                        valueMin = value,
                        valueMax = value,
                        category = inferCategory(name),
                        validated = false,
                        source = "competitor-v80",
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "skip malformed competitor entry $name: ${e.message}")
                    continue
                }
            }

            // LIVE_VALIDATED wins on collision (match by lowercase key, store original entry)
            for (entry in LIVE_VALIDATED) {
                if (entry.dev in BANNED_DEVS) {
                    Log.w(TAG, "Dropping banned-dev live entry=${entry.actionName} dev=${entry.dev}")
                    continue
                }
                merged[entry.actionName.lowercase()] = entry
            }

            return WriteAllowlist(merged)
        }
    }
}
