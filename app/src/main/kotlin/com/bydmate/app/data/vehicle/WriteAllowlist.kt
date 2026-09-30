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

        /**
         * Per-(dev,fid) carve-outs from [BANNED_DEVS]. dev=1023 (global powerState)
         * and dev=1004 (light enums) are blanket-banned, but BYD's own system app
         * (BYDAutoSettingDevice, byddiagnosetool decompile) drives the cabin/ambient
         * lights and the DRL through these specific comfort fids via autoservice
         * setInt — the same channel D+ used. Only these exact pairs are exempt; the
         * rest of dev=1023/1004 stays banned. All three live-validated on Leopard 3
         * 2026-05-29 (write+readback snap).
         */
        val BANNED_DEV_FID_EXCEPTIONS: Set<Pair<Int, Int>> = setOf(
            1023 to 1330643002,  // SET_INSIDE_LIGHT_STATE_SET (interior light on/off)
            1023 to 1069547536,  // SET_INTERIOR_ATMOSPHERE_LAMP_BRIGHTNESS_SET (ambient)
            1004 to 1125122118,  // DRL (daytime running lights) on/off
            1004 to 871366669,   // hazard lights — live-validated on Leopard 3 2026-07-31
            1023 to 850427920,  // fridge WORKING_STATUS_SET (cool/heat/off) — com.byd.car.icebox
            1023 to 850427928,  // fridge TEMP_REGULATION_SET — com.byd.car.icebox
            1023 to 944767029,  // SET_STEERING_WHEEL_HEAT_STATE_SET — BYDAutoSettingDevice, unvalidated
            // SETTING_PRESELECTED_DRIVING_MODE_SET — the BYD voice assistant's drive mode write.
            // Only the values of [RESTRICTED_VALUES] pass, see there.
            DRIVE_MODE_DEV to DRIVE_MODE_WRITE_FID,
        )

        /** Drive mode: written and read back on the Setting device (dev=1023). */
        const val DRIVE_MODE_DEV = 1023
        const val DRIVE_MODE_WRITE_FID = 1276260400
        /** SETTING_TARGET_DRIVING_MODE (tx=5): the mode the car is in, same 1..21 encoding as the
         *  write; 10 = emergency flotation. The dev=1006 driveMode fid does not follow the terrain
         *  modes (live Leopard 3 2026-09-27), so only this fid confirms a write. */
        const val DRIVE_MODE_TARGET_FID = 255852712

        /**
         * Carve-outs that open only some values of their fid: an entry on such a fid must be a
         * single value from the set, anything else is dropped at load like a banned dev. The drive
         * mode fid also takes 19 CUSTOM, 23 TRACTION and 255 EXIT, which stay closed.
         */
        val RESTRICTED_VALUES: Map<Pair<Int, Int>, Set<Int>> = mapOf(
            (DRIVE_MODE_DEV to DRIVE_MODE_WRITE_FID) to DriveMode.entries.map { it.value }.toSet(),
        )

        /** False when [entry] sits on a [RESTRICTED_VALUES] fid with a value outside its set. */
        internal fun valuesAllowed(entry: WriteEntry): Boolean {
            val allowed = RESTRICTED_VALUES[entry.dev to entry.writeFid] ?: return true
            return entry.valueMin == entry.valueMax && entry.valueMin in allowed
        }

        /**
         * "No request" on a window target-position fid. The fid holds its last value as a live
         * request, so BYD's own apps write the percent, wait 300 ms and write 255 to the same
         * fid (autovoice CarWindowApiImpl.setLeftFrontWindowPercent → setLeftFrontWindowPercentInvalid,
         * BydMyCar carlib vz). Never a user target: the percent entries stop at 100.
         */
        const val PERCENT_RESET_VALUE = 255

        /** The percent window actions whose fid takes [PERCENT_RESET_VALUE]. */
        private val PERCENT_RESET_ACTIONS = setOf(
            "window_driver_pos", "window_passenger_pos", "window_rear_left_pos", "window_rear_right_pos",
        )

        /**
         * The reset write of a percent window [entry] (same dev and fid, value 255 only), or null
         * for every other entry. Not in the action map: [find] never returns it, so no command,
         * automation or agent tool can send 255.
         */
        fun percentResetFor(entry: WriteEntry): WriteEntry? {
            if (entry.actionName.lowercase() !in PERCENT_RESET_ACTIONS) return null
            return entry.copy(
                actionName = "${entry.actionName}_reset",
                valueMin = PERCENT_RESET_VALUE,
                valueMax = PERCENT_RESET_VALUE,
                validated = false,
                source = "byd-stock CarWindowApiImpl",
            )
        }

        /** An entry may load: not on a banned dev (unless carved out) and within its value set. */
        private fun admitted(entry: WriteEntry): Boolean =
            !isBanned(entry.dev, entry.writeFid) && valuesAllowed(entry)

        /** A (dev,fid) is banned unless explicitly carved out. */
        internal fun isBanned(dev: Int, fid: Int): Boolean =
            dev in BANNED_DEVS && (dev to fid) !in BANNED_DEV_FID_EXCEPTIONS

        // 44 native write entries on Leopard 3 via HelperDaemon. 22 live-validated
        // 2026-05-28 (climate/windows/sunroof/sunshade/locks) + 8 live-validated
        // 2026-05-29 (interior/ambient light, DRL, rear-window-defrost = mirror heat) +
        // 8 live-validated 2026-06-29 (seat heat/vent switch+level, dev=1000) +
        // 2 live-validated 2026-06-29 (front trunk open/close, dev=1001) +
        // 3 live-validated 2026-06-29 (fridge mode/temp cool/temp heat, dev=1023 carve-out).
        // ac_cycle_outer corrected 2026-06-28 from live autoservice reads: external
        // circulation reads 0 (internal reads 1), so the prior value 2 is not a valid
        // enum and autoservice rejected the setInt (the "helper.write returned false"
        // the user hit).
        // fid 501219364 = climate POWER toggle: 0=off, 1=on. BOTH directions physically
        // validated in-car 2026-07-03 (shell uid write, user confirmed AC state change).
        // Old entries were wrong: ac_off val=1 actually turned AC ON (status=1, no effect
        // visible as "success"). fid 501219352 was tried earlier as a POWER toggle and
        // rejected on Leopard 3; it is in fact the AUTO MODE toggle (acCtrlMode,
        // FidMap read fid 1077936146): 0=enable auto, 1=disable (switch to manual).
        // Both directions physically validated in-car 2026-07-07 (status=1, user confirmed).
        val LIVE_VALIDATED: List<WriteEntry> = listOf(
            // climate (dev=1000)
            WriteEntry("ac_on",          1000, 501219364, null, 1, 1,   "climate",  true, "live-leopard3-2026-07-03"),
            WriteEntry("ac_off",         1000, 501219364, null, 0, 0,   "climate",  true, "live-leopard3-2026-07-03"),
            // ac auto mode toggle — fid 501219352 (acCtrlMode write, READ via 1077936146).
            // INVERTED vs ac_on: 0=enable auto, 1=disable (switch to manual).
            // Both values physically validated in-car 2026-07-07.
            WriteEntry("ac_auto_on",     1000, 501219352, null, 0, 0,   "climate",  true, "live-leopard3-2026-07-07"),
            WriteEntry("ac_auto_off",    1000, 501219352, null, 1, 1,   "climate",  true, "live-leopard3-2026-07-07"),
            WriteEntry("ac_temp_main",   1000, 501219368, null, 16, 30, "climate",  true, "live-leopard3-2026-05-28"),
            WriteEntry("ac_cycle_inner", 1000, 501219355, null, 1, 1,   "climate",  true, "live-leopard3-2026-05-28"),
            WriteEntry("ac_cycle_outer", 1000, 501219355, null, 0, 0,   "climate",  true, "live-leopard3-2026-06-28"),

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

            // locks — no readback: 1081081864 is the lock state-of-truth on dev=1032
            // (banned dev, see FidMap lockFL), not on dev=1001 where the write lands.
            // Reading it back on dev=1001 returned a stale/other value, producing a
            // false ReadbackMismatch even though the write physically actuates the
            // lock (field report 2026-06-25). Lock state stays visible via lockFL.
            WriteEntry("doors_unlock", 1001, 1276141590, null, 1, 1, "locks", true, "live-leopard3-2026-05-28"),
            WriteEntry("doors_lock",   1001, 1276141590, null, 2, 2, "locks", true, "live-leopard3-2026-05-28"),

            // interior/cabin light — SET_INSIDE_LIGHT_STATE_SET (1=off, 2=on), dev=1023 carve-out
            WriteEntry("interior_light_on",  1023, 1330643002, null, 2, 2, "lights", true, "live-leopard3-2026-05-29"),
            WriteEntry("interior_light_off", 1023, 1330643002, null, 1, 1, "lights", true, "live-leopard3-2026-05-29"),
            // ambient (IAL) via brightness — raw is +1 shifted: raw 1=off(lvl0), 2..5=lvl1..4, dev=1023 carve-out
            WriteEntry("ambient_light_on",   1023, 1069547536, null, 5, 5, "lights", true, "live-leopard3-2026-05-29"),
            WriteEntry("ambient_light_off",  1023, 1069547536, null, 1, 1, "lights", true, "live-leopard3-2026-05-29"),
            // DRL (daytime running lights) — 1=on, 2=off (0 invalid), dev=1004 carve-out
            WriteEntry("drl_on",  1004, 1125122118, null, 1, 1, "lights", true, "live-leopard3-2026-05-29"),
            WriteEntry("drl_off", 1004, 1125122118, null, 2, 2, "lights", true, "live-leopard3-2026-05-29"),
            // hazard lights — 0=off, 1=hazard (2/3 = turn signals, deliberately not exposed),
            // dev=1004 carve-out. No readback: the read fid 950009900 is a different mask
            // (6 while hazard is on), and reading 871366669 itself returns -10011 (write-only).
            WriteEntry("hazard_on",  1004, 871366669, null, 1, 1, "lights", true, "live-leopard3-2026-07-31"),
            WriteEntry("hazard_off", 1004, 871366669, null, 0, 0, "lights", true, "live-leopard3-2026-07-31"),
            // mirror heat = rear-window defrost (single button on Leopard 3) — 1=on, 0=off, dev=1000
            WriteEntry("defrost_rear_on",  1000, 501219357, null, 1, 1, "climate", true, "live-leopard3-2026-05-29"),
            WriteEntry("defrost_rear_off", 1000, 501219357, null, 0, 0, "climate", true, "live-leopard3-2026-05-29"),
            // seat heat/vent — dev=1000 switch (1=on / 2=off) + level (1..5), validated 2026-06-29,
            // off code corrected 2026-07-01. "On at level N" = two writes (switch=1 + level=N);
            // off = switch=2 (NOT 0 — switch=0 is a silent no-op: autoservice returns status=1 but
            // the seat stays on; 2 matches the read status enum 1=on/2=off). heat and vent on a
            // seat are mutually exclusive (climate module cancels the other).
            WriteEntry("driver_seat_heat_switch",    1000, 1276248084, null, 0, 2, "seats", true, "live-leopard3-2026-07-01"),
            WriteEntry("driver_seat_heat_level",     1000, 1276252180, null, 1, 5, "seats", true, "live-leopard3-2026-06-29"),
            WriteEntry("passenger_seat_heat_switch", 1000, 1276248092, null, 0, 2, "seats", true, "live-leopard3-2026-07-01"),
            WriteEntry("passenger_seat_heat_level",  1000, 1276252188, null, 1, 5, "seats", true, "live-leopard3-2026-06-29"),
            WriteEntry("driver_seat_vent_switch",    1000, 1276248080, null, 0, 2, "seats", true, "live-leopard3-2026-07-01"),
            WriteEntry("driver_seat_vent_level",     1000, 1276252176, null, 1, 5, "seats", true, "live-leopard3-2026-06-29"),
            WriteEntry("passenger_seat_vent_switch", 1000, 1276248088, null, 0, 2, "seats", true, "live-leopard3-2026-07-01"),
            WriteEntry("passenger_seat_vent_level",  1000, 1276252184, null, 1, 5, "seats", true, "live-leopard3-2026-06-29"),
            // front trunk (frunk) — dev=1001 SETTING_ELECTRIC_FORECABIN_SWITCH_SET, 1=open 3=close.
            // Powered external panel; open is speed-0 gated in ActionDispatcher.
            WriteEntry("front_trunk_open",  1001, 1276182560, null, 1, 1, "trunk", true, "live-leopard3-2026-06-29"),
            WriteEntry("front_trunk_close", 1001, 1276182560, null, 3, 3, "trunk", true, "live-leopard3-2026-06-29"),
            // fridge (com.byd.car.icebox) — dev=1023 carve-out. mode 1=cool/2=heat/3=off;
            // temp on one fid, mode-dependent raw: cool=°C+19 (-6..+6→13..25), heat=°C (35..50).
            WriteEntry("fridge_mode",      1023, 850427920, null, 1, 3,  "fridge", true, "live-leopard3-2026-06-29"),
            WriteEntry("fridge_temp_cool", 1023, 850427928, null, 13, 25, "fridge", true, "live-leopard3-2026-06-29"),
            WriteEntry("fridge_temp_heat", 1023, 850427928, null, 35, 50, "fridge", true, "live-leopard3-2026-06-29"),
            // drive mode — dev=1023 carve-out, one entry per value (1=normal 2=eco 3=sport 4=snow
            // 5=sand 6=mud 7=mountain 21=smart). Each read back on DRIVE_MODE_TARGET_FID within
            // 2 s, parked, 2026-09-27. DriveModeChannel checks the support flag, the flotation
            // state and reads the result back; 8=rock is in CANDIDATE_UNVALIDATED.
            WriteEntry("drive_mode_normal",   1023, 1276260400, null, 1, 1,   "drive_mode", true, "live-leopard3-2026-09-27"),
            WriteEntry("drive_mode_eco",      1023, 1276260400, null, 2, 2,   "drive_mode", true, "live-leopard3-2026-09-27"),
            WriteEntry("drive_mode_sport",    1023, 1276260400, null, 3, 3,   "drive_mode", true, "live-leopard3-2026-09-27"),
            WriteEntry("drive_mode_snow",     1023, 1276260400, null, 4, 4,   "drive_mode", true, "live-leopard3-2026-09-27"),
            WriteEntry("drive_mode_sand",     1023, 1276260400, null, 5, 5,   "drive_mode", true, "live-leopard3-2026-09-27"),
            WriteEntry("drive_mode_mud",      1023, 1276260400, null, 6, 6,   "drive_mode", true, "live-leopard3-2026-09-27"),
            WriteEntry("drive_mode_mountain", 1023, 1276260400, null, 7, 7,   "drive_mode", true, "live-leopard3-2026-09-27"),
            WriteEntry("drive_mode_smart",    1023, 1276260400, null, 21, 21, "drive_mode", true, "live-leopard3-2026-09-27"),
        )

        /** dev of the seat status fids below (same namespace as the primary write channel). */
        const val SEAT_STATUS_DEV = 1000

        /**
         * READ-side status fid per seat group (tx=5), the counterpart of the dev=1000
         * switch entries above: 1=on, 2=off on Leopard 3. [AdaptiveSeatChannel] reads it
         * back after a switch write, because Song L / Han EV answer status=1 to that write
         * and actuate nothing (#74/#98/#109) — there the fid stays 0 and the readback is
         * the only signal that the primary channel is dead on this model.
         *
         * DRIVER ONLY. The passenger counterparts in the catalog (711983128 / 711983132)
         * are AC_PASSENGER_SEAT_VENTILATING_LEVEL / _HEATING_LEVEL — LEVELS (0=off, 1..5),
         * not this 1=on/2=off status enum, so verifying a level-2 command against "expect 1"
         * would manufacture a contradiction on a healthy Leopard 3. The passenger status
         * fids (711983112 / 711983116) are unvalidated on a live car and stay out until a
         * field dump shows them alive (they are sampled by SeatsDiagnostics). A group with
         * no entry here reads back as null = inconclusive, which never moves the winner —
         * the driver vertical alone decides the channel, and the winner is channel-wide.
         */
        val SEAT_STATUS_FIDS: Map<SeatGroup, Int> = mapOf(
            SeatGroup.DRIVER_VENT to 702545928,
            SeatGroup.DRIVER_HEAT to 702545932,
        )

        /**
         * READ-side state of the steering wheel heater (SET_STEERING_WHEEL_HEAT_STATE, tx=5),
         * the counterpart of the steering_heat_on/off writes: 2=on, 1=off, 0=function absent,
         * 65535=no CAN link. BYD reads it on the Setting device (dev=1023). Not validated on a
         * car with a heated wheel: Leopard 3 has none and reads 0.
         */
        const val STEERING_HEAT_STATE_DEV = 1023
        const val STEERING_HEAT_STATE_FID = 1116733454

        /**
         * Candidate (unvalidated) native channels staged for an in-vehicle snap.
         * Holds the seat heat/vent dev=1001 fallback channel (competitor-v80 fids)
         * used by AdaptiveSeatChannel when the primary dev=1000 path returns a
         * permanent error on non-Leopard-3 models, plus the window CTRL channel used
         * by [WindowChannelRouter] on DiLink 3.0. The merge order in [loadProduction]
         * folds these in after competitor JSON; LIVE_VALIDATED wins on collision.
         */
        val CANDIDATE_UNVALIDATED: List<WriteEntry> = listOf(
            // Seat heat/vent fallback channel — competitor dev=1001, single write.
            // value range 1..6: 1=off, 2=lvl1 ... 6=lvl5. Used by AdaptiveSeatChannel
            // when dev=1000 primary returns NOOP/PERMANENT (e.g. Song Plus / DiLink 3).
            // Not live-validated on those models yet — confirmed via competitor-v80.
            // Window CTRL channel (1=open, 2=close, 3=stop, 4=half, 5=vent) — the only
            // window family present in BOTH DiLink 3.0 and 5.0 catalogs (#79), used by
            // [WindowChannelRouter]. Unvalidated as a family: the front fids are
            // live-validated on Leopard 3 only for values 1/2 (see the short-form
            // open/close entries), the rear short-form fids are no-ops there. The
            // DiLink 3.0 field report will confirm the full 1..5 range.
            WriteEntry("window_driver_ctrl",     1001, 1125122104, null, 1, 5, "windows", false, "dilink3-catalog-2026-07-30"),
            WriteEntry("window_passenger_ctrl",  1001, 1125122107, null, 1, 5, "windows", false, "dilink3-catalog-2026-07-30"),
            WriteEntry("window_rear_left_ctrl",  1001, 1125122112, null, 1, 5, "windows", false, "dilink3-catalog-2026-07-30"),
            WriteEntry("window_rear_right_ctrl", 1001, 1125122115, null, 1, 5, "windows", false, "dilink3-catalog-2026-07-30"),
            // Rear open/close on the same CTRL fids (1=open, 2=close) — the twins of the
            // live-validated front short-form entries, used by the translator for the
            // "open"/"close" commands. Live-validated on Leopard 3 2026-09-10 (4/4 cycles;
            // the very first write after a long idle was accepted but moved nothing).
            WriteEntry("window_rear_left_open",   1001, 1125122112, null, 1, 1, "windows", true,  "live-leopard3-2026-09-10"),
            WriteEntry("window_rear_left_close",  1001, 1125122112, null, 2, 2, "windows", true,  "live-leopard3-2026-09-10"),
            WriteEntry("window_rear_right_open",  1001, 1125122115, null, 1, 1, "windows", true,  "live-leopard3-2026-09-10"),
            WriteEntry("window_rear_right_close", 1001, 1125122115, null, 2, 2, "windows", true,  "live-leopard3-2026-09-10"),
            // Fan speed (AC_WIND_LEVEL_SET, dev=1000, 1..7). The competitor JSON entry
            // is value-less (valueMin=valueMax=0), which blocks a ranged write — this
            // entry restores the 1..7 range. No readbackFid: AC_WIND_LEVEL sits on a
            // different fid per model (1077936156 on Leopard 3 / DiLink 4, 330301468 on
            // Song Plus), so a hardcoded readback would report a healthy write as
            // unsupported off Leopard 3. Not live-validated yet (#201).
            WriteEntry("ac_wind_level", 1000, 501219340, null, 1, 7, "climate", false, "competitor-actions ac_wind_level; #201"),
            // Blow direction (AC_WIND_MODE_SET, dev=1000): 1=face, 2=face+feet, 3=feet,
            // 4=feet+windshield, 5=windshield. 6/7 exist on some models and stay out.
            // Same value-less competitor entry problem and same per-model readback fid
            // divergence as ac_wind_level above, so no readbackFid here either (#201).
            WriteEntry("ac_wind_mode", 1000, 501219336, null, 1, 5, "climate", false, "competitor-actions ac_wind_mode; AirConditioningService decompiled; #201"),
            // Steering wheel heat — SET_STEERING_WHEEL_HEAT_STATE_SET on the Setting device
            // (dev=1023 carve-out), 2=on, 1=off; the set accepts nothing else (levels live on
            // separate fids and stay out). Override the competitor's same-named dev=1000
            // entries; its wheel_heat_on/off (dev=1000) stay as the fallback channel of
            // [SteeringHeatChannel]. No readbackFid: the state fid lags the write on CAN, so
            // the channel reads it back itself after a delay.
            WriteEntry("steering_heat_on",  1023, 944767029, null, 2, 2, "climate", false, "byd-sdk BYDAutoSettingDevice + autovoice"),
            WriteEntry("steering_heat_off", 1023, 944767029, null, 1, 1, "climate", false, "byd-sdk BYDAutoSettingDevice + autovoice"),
            // Drive mode rock (8) — BYD voice assistant value, not tried: the Leopard 3 support
            // flag reports it absent. Same dev=1023 carve-out as the validated modes.
            WriteEntry("drive_mode_rock", 1023, 1276260400, null, 8, 8, "drive_mode", false, "byd-sdk autovoice DrivingPatternApiImpl"),
            WriteEntry("driver_seat_heat_fallback",    1001, 1125122068, null, 1, 6, "seats", false, "competitor-v80"),
            WriteEntry("driver_seat_vent_fallback",    1001, 1125122064, null, 1, 6, "seats", false, "competitor-v80"),
            WriteEntry("passenger_seat_heat_fallback", 1001, 1125122076, null, 1, 6, "seats", false, "competitor-v80"),
            WriteEntry("passenger_seat_vent_fallback", 1001, 1125122072, null, 1, 6, "seats", false, "competitor-v80"),
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
                    val entry = WriteEntry(
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
                    if (!admitted(entry)) {
                        Log.w(TAG, "Dropping banned-dev action=$name dev=$dev fid=$fid value=$value")
                        continue
                    }
                    merged[name.lowercase()] = entry
                } catch (e: Exception) {
                    Log.w(TAG, "skip malformed competitor entry $name: ${e.message}")
                    continue
                }
            }

            // Candidate (unvalidated) native entries merged after competitor.
            // Carve-out check still applies (only exempt dev=1023 light fids pass).
            for (entry in CANDIDATE_UNVALIDATED) {
                if (!admitted(entry)) {
                    Log.w(TAG, "Dropping banned-dev candidate=${entry.actionName} dev=${entry.dev}")
                    continue
                }
                merged[entry.actionName.lowercase()] = entry
            }

            // LIVE_VALIDATED wins on collision (match by lowercase key, store original entry)
            for (entry in LIVE_VALIDATED) {
                if (!admitted(entry)) {
                    Log.w(TAG, "Dropping banned-dev live entry=${entry.actionName} dev=${entry.dev}")
                    continue
                }
                merged[entry.actionName.lowercase()] = entry
            }

            return WriteAllowlist(merged)
        }
    }
}
