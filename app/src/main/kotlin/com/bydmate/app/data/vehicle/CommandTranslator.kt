package com.bydmate.app.data.vehicle

/**
 * Maps legacy D+ Chinese command strings to (action_name, value) for VehicleApi.dispatch.
 *
 * Source: AutomationViewModel.ACTION_COMMANDS (49 entries) + Smart Home VPS catalog
 * (same vocabulary). Both callers historically stripped the "迪加" prefix before sending
 * over HTTP; the prefix is now removed here if present.
 *
 * Crowd validation strategy: actions not present here OR not in WriteAllowlist will
 * fail-soft at dispatch(). User files issue → we add the mapping in a follow-up.
 *
 * Window aggregates (车窗全开/关闭/半开, 前排/后排车窗全开/关闭) and individual rear
 * windows (后左/后右打开{n}) fan out to the validated per-door % fids — see the
 * [composite] map. The competitor "aggregate" fids all target the driver
 * short-form fid (1125122104) and the rear short-form fids are no-ops on
 * Leopard 3, so the % path is the only reliable channel.
 *
 * Interior/cabin light (打开/关闭车内灯), ambient light (氛围灯打开/关闭), DRL
 * (打开/关闭日行灯) and mirror heat = rear-window defrost (后视镜加热/关闭后视镜加热)
 * route to LIVE_VALIDATED entries on dev=1023/1004/1000 — the channel D+ used via
 * BYDAutoSettingDevice. dev=1023/1004 are carved out per-fid in WriteAllowlist.
 * All four groups validated on Leopard 3 2026-05-29 (write+readback snap).
 *
 * DROPPED: commands whose action_name either has no allowlist entry or targets
 * a banned dev namespace —
 *   ECO模式                                — drive mode write targets dev=1006 (BANNED).
 *                                            Default rule "Эко при низком заряде" is
 *                                            shipped disabled; if user enables it the
 *                                            dispatch fails-soft via AllowlistMiss.
 *
 * Values for competitor-sourced entries verified against competitor-actions.json
 * (app/src/main/assets/competitor-actions.json). See value comments below.
 */
object CommandTranslator {
    data class Resolved(val actionName: String, val value: Int)

    private val table: Map<String, Resolved> = mapOf(
        // ── Windows (vent) ── competitor windows_vent (driver-only fid). Aggregate
        // open/close/half are handled by the composite fan-out below ──────────
        "车窗通风"   to Resolved("windows_vent",  5),   // competitor val=5 (vent mode)

        // ── Windows (individual %, 0..100) ── LIVE_VALIDATED ──────────────────
        "主驾打开100" to Resolved("window_driver_pos",      100),
        "主驾打开0"   to Resolved("window_driver_pos",        0),
        "副驾打开100" to Resolved("window_passenger_pos",   100),
        "副驾打开0"   to Resolved("window_passenger_pos",     0),
        "后左打开100" to Resolved("window_rear_left_pos",   100),
        "后左打开0"   to Resolved("window_rear_left_pos",     0),
        "后右打开100" to Resolved("window_rear_right_pos",  100),
        "后右打开0"   to Resolved("window_rear_right_pos",    0),

        // ── Climate ── LIVE_VALIDATED (ac_on/ac_off/ac_temp_main/ac_cycle_*) ──
        "自动空调"    to Resolved("ac_on",         2),   // LIVE val=2 (on/auto)
        "内循环"      to Resolved("ac_cycle_inner", 1),  // LIVE val=1
        "外循环"      to Resolved("ac_cycle_outer", 2),  // LIVE val=2
        "设置温度18"  to Resolved("ac_temp_main",  18),
        "设置温度20"  to Resolved("ac_temp_main",  20),
        "设置温度22"  to Resolved("ac_temp_main",  22),
        "设置温度25"  to Resolved("ac_temp_main",  25),

        // ── Climate ── competitor-actions.json ────────────────────────────────
        "打开空调通风" to Resolved("ac_flow_only_on",   1),  // competitor val=1
        "吹前挡"      to Resolved("defrost_front_on",  1),  // competitor val=1
        "关闭吹前挡"  to Resolved("defrost_front_off", 0),  // competitor val=0

        // ── Driver seat heat ── competitor-actions.json (dev=1001, safe) ──────
        // Naming: off=1, on(lvl1)=2, lvl2=3, lvl3=4, lvl4=5, lvl5=6
        "主驾座椅加热1档"  to Resolved("driver_seat_heat_on",   2),
        "主驾座椅加热2档"  to Resolved("driver_seat_heat_lvl2", 3),
        "主驾座椅加热关闭" to Resolved("driver_seat_heat_off",  1),

        // ── Passenger seat heat ── competitor-actions.json ────────────────────
        "副驾座椅加热1档"  to Resolved("passenger_seat_heat_on",   2),
        "副驾座椅加热2档"  to Resolved("passenger_seat_heat_lvl2", 3),
        "副驾座椅加热关闭" to Resolved("passenger_seat_heat_off",  1),

        // ── Driver seat vent ── competitor-actions.json ───────────────────────
        "主驾座椅通风1档"  to Resolved("driver_seat_vent_on",   2),
        "主驾座椅通风2档"  to Resolved("driver_seat_vent_lvl2", 3),
        "主驾座椅通风关闭" to Resolved("driver_seat_vent_off",  1),

        // ── Passenger seat vent ── competitor-actions.json ────────────────────
        "副驾座椅通风1档"  to Resolved("passenger_seat_vent_on",   2),
        "副驾座椅通风2档"  to Resolved("passenger_seat_vent_lvl2", 3),
        "副驾座椅通风关闭" to Resolved("passenger_seat_vent_off",  1),

        // ── Locks ── LIVE_VALIDATED ───────────────────────────────────────────
        "车门上锁"  to Resolved("doors_lock",   2),
        "车门解锁"  to Resolved("doors_unlock", 1),

        // ── Sunroof ── LIVE_VALIDATED ─────────────────────────────────────────
        "天窗打开100" to Resolved("sunroof_open",  1),  // full open
        "天窗打开50"  to Resolved("sunroof_tilt",  3),  // tilt/half — LIVE val=3
        "天窗打开0"   to Resolved("sunroof_close", 2),

        // ── Sunshade ── LIVE_VALIDATED ────────────────────────────────────────
        "遮阳帘打开" to Resolved("sunshade_open",  1),
        "遮阳帘关闭" to Resolved("sunshade_close", 2),

        // ── Interior / ambient light ── LIVE_VALIDATED (dev=1023 carve-out) ──────
        "打开车内灯" to Resolved("interior_light_on",  2),
        "关闭车内灯" to Resolved("interior_light_off", 1),
        "氛围灯打开" to Resolved("ambient_light_on",   5),
        "氛围灯关闭" to Resolved("ambient_light_off",  1),  // raw +1 shift: 1 = off (lvl0)

        // ── DRL (ДХО) ── LIVE_VALIDATED (dev=1004 carve-out) ──────────────────
        "打开日行灯" to Resolved("drl_on",  1),
        "关闭日行灯" to Resolved("drl_off", 2),

        // ── Mirror heat = rear-window defrost ── LIVE_VALIDATED (dev=1000) ────
        "后视镜加热"   to Resolved("defrost_rear_on",  1),
        "关闭后视镜加热" to Resolved("defrost_rear_off", 0),
    )

    /**
     * Composite commands fan out to several validated per-door % writes. All four
     * window fids (driver/passenger/rear-left/rear-right *_pos) are LIVE_VALIDATED.
     */
    private fun allWindows(pct: Int): List<Resolved> = listOf(
        Resolved("window_driver_pos", pct),
        Resolved("window_passenger_pos", pct),
        Resolved("window_rear_left_pos", pct),
        Resolved("window_rear_right_pos", pct),
    )

    private val composite: Map<String, List<Resolved>> = mapOf(
        "车窗全开"     to allWindows(100),
        "车窗关闭"     to allWindows(0),
        "车窗半开"     to allWindows(50),
        "前排车窗全开" to listOf(Resolved("window_driver_pos", 100), Resolved("window_passenger_pos", 100)),
        "前排车窗关闭" to listOf(Resolved("window_driver_pos", 0),   Resolved("window_passenger_pos", 0)),
        "后排车窗全开" to listOf(Resolved("window_rear_left_pos", 100), Resolved("window_rear_right_pos", 100)),
        "后排车窗关闭" to listOf(Resolved("window_rear_left_pos", 0),   Resolved("window_rear_right_pos", 0)),
    )

    /**
     * Resolve a D+ command string. Strips leading "迪加" prefix if present.
     * Returns an empty list when the command is unknown — caller treats this as a
     * soft failure. A composite command returns several writes; the caller
     * dispatches each (fan-out).
     */
    fun resolve(commandString: String): List<Resolved> {
        val stripped = commandString.removePrefix("迪加")
        composite[stripped]?.let { return it }
        table[stripped]?.let { return listOf(it) }
        return emptyList()
    }

    /** Set of all action_names referenced by this translator. Used by invariant test. */
    fun allActions(): Set<String> =
        (table.values.map { it.actionName } + composite.values.flatten().map { it.actionName })
            .toMutableSet()
}
