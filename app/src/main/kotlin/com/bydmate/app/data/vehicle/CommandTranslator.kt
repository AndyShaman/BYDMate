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
 * DROPPED (12): commands whose action_name either has no allowlist entry or targets
 * a banned dev namespace —
 *   前排车窗关闭/全开, 后排车窗关闭/全开 — no aggregate window write (out of scope)
 *   后视镜加热/关闭后视镜加热              — no mirror-heat entry in any source
 *   氛围灯打开/关闭                        — no ambient-light entry in any source
 *   打开日行灯/关闭日行灯                  — drl_on/drl_off target dev=1004 (BANNED)
 *   打开车内灯/关闭车内灯                  — no interior-light entry in any source
 *
 * Values for competitor-sourced entries verified against competitor-actions.json
 * (app/src/main/assets/competitor-actions.json). See value comments below.
 */
object CommandTranslator {
    data class Resolved(val actionName: String, val value: Int)

    private val table: Map<String, Resolved> = mapOf(
        // ── Windows (aggregate) ── competitor-actions.json ────────────────────
        "车窗通风"   to Resolved("windows_vent",  5),   // competitor val=5 (vent mode)
        "车窗关闭"   to Resolved("close_windows", 2),   // competitor val=2
        "车窗全开"   to Resolved("open_windows",  1),   // competitor val=1
        "车窗半开"   to Resolved("windows_half",  4),   // competitor val=4

        // ── Windows (individual %, 0..100) ── LIVE_VALIDATED ──────────────────
        "主驾打开100" to Resolved("window_driver_pos",    100),
        "主驾打开0"   to Resolved("window_driver_pos",      0),
        "副驾打开100" to Resolved("window_passenger_pos", 100),
        "副驾打开0"   to Resolved("window_passenger_pos",   0),

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
    )

    /**
     * Resolve a D+ command string. Strips leading "迪加" prefix if present.
     * Returns null when the command is unknown — caller treats this as a soft failure.
     */
    fun resolve(commandString: String): Resolved? {
        val stripped = commandString.removePrefix("迪加")
        return table[stripped]
    }

    /** Set of all action_names referenced by this translator. Used by invariant test. */
    fun allActions(): Set<String> = table.values.mapTo(mutableSetOf()) { it.actionName }
}
