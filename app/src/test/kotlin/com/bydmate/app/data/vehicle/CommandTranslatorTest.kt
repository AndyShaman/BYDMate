package com.bydmate.app.data.vehicle

import com.bydmate.app.data.automation.ActionDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CommandTranslatorTest {

    // resolve() now returns a List<Resolved>: empty = unknown, 1 element = single
    // write, N elements = composite fan-out (e.g. window aggregates). Helper for
    // the single-command tests.
    private fun one(cmd: String): CommandTranslator.Resolved? =
        CommandTranslator.resolve(cmd).singleOrNull()

    private fun pairs(cmd: String): Set<Pair<String, Int>> =
        CommandTranslator.resolve(cmd).map { it.actionName to it.value }.toSet()

    // ── Test 2: unknown command returns empty list ────────────────────────────
    @Test fun `unknown command returns empty list`() {
        assertTrue(CommandTranslator.resolve("不存在的命令").isEmpty())
    }

    // ── Test 3: every translator action_name resolves in production allowlist ─
    // CI gate: translator + allowlist must stay in sync.
    @Test fun `every translator action_name resolves in production allowlist`() {
        val projectRoot = generateSequence(File(".").canonicalFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/assets/competitor-actions.json").exists() }
            ?: error("Cannot find app/src/main/assets/competitor-actions.json from ${File(".").canonicalPath}")
        val jsonText = File(projectRoot, "app/src/main/assets/competitor-actions.json").readText()
        val allowlist = WriteAllowlist.loadProduction { jsonText }
        val unresolved = CommandTranslator.allActions().filter { allowlist.find(it) == null }
        assertTrue(
            "Translator references action_names not in allowlist: $unresolved",
            unresolved.isEmpty(),
        )
    }

    // ── Test 3b: every translator value falls within its allowlist range ──────
    // Regression for the 外循环 bug: the allowlist range was tightened to [0..0]
    // but the translator kept emitting value 2, so the dispatch was silently
    // rejected ("value=2 out of range [0..0]"). Existence (Test 3) didn't catch
    // it — only a value-vs-range check does.
    @Test fun `every translator value falls within its allowlist range`() {
        val projectRoot = generateSequence(File(".").canonicalFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/assets/competitor-actions.json").exists() }
            ?: error("Cannot find app/src/main/assets/competitor-actions.json from ${File(".").canonicalPath}")
        val jsonText = File(projectRoot, "app/src/main/assets/competitor-actions.json").readText()
        val allowlist = WriteAllowlist.loadProduction { jsonText }
        val violations = CommandTranslator.allResolved().mapNotNull { r ->
            val entry = allowlist.find(r.actionName) ?: return@mapNotNull null // existence is Test 3's job
            if (r.value < entry.valueMin || r.value > entry.valueMax)
                "${r.actionName}=${r.value} outside [${entry.valueMin}..${entry.valueMax}]"
            else null
        }
        assertTrue(
            "Translator values outside their allowlist range: $violations",
            violations.isEmpty(),
        )
    }

    // ── Single commands: one row per string users' saved automations store ──
    // (command, expected action, expected value; null = only the action is pinned).
    // One command must resolve to exactly one write.
    private val singleCommands: List<Triple<String, String, Int?>> = listOf(
        Triple("迪加车门上锁", "doors_lock", 2), // prefix stripped before lookup
        Triple("车门解锁", "doors_unlock", 1), // no prefix resolves directly
        Triple("方向盘加热", "steering_heat_on", 2), // dev 1023 pair
        Triple("关闭方向盘加热", "steering_heat_off", 1),
        Triple("打开抬头显示", "hud_on", 1),
        Triple("关闭抬头显示", "hud_off", 2),
        Triple("打开尾翼", "spoiler_up", 1),
        Triple("关闭尾翼", "spoiler_down", 2),
        Triple("打开电池预热", "battery_preheat_on", 1), // stock toggle values
        Triple("关闭电池预热", "battery_preheat_off", 2),
        Triple("ECO模式", "drive_mode_eco", 2), // the old D+ ECO string, with or without its prefix
        Triple("迪加ECO模式", "drive_mode_eco", 2),
        Triple("天窗打开50", "sunroof_tilt", 3),
        Triple("内循环", "ac_cycle_inner", 1),
        // Windows: open / close use the dedicated fids, not 100% / 0%
        Triple("主驾打开100", "window_driver_open", 1),
        Triple("主驾打开0", "window_driver_close", 2),
        Triple("副驾打开100", "window_passenger_open", 1),
        Triple("后左打开100", "window_rear_left_open", 1),
        Triple("后右打开0", "window_rear_right_close", 2),
        // Windows: an aperture that is not full open / full close stays on the % path
        Triple("主驾通风", "window_driver_pos", 10),
        Triple("主驾半开", "window_driver_pos", 50),
        // Window position: any per-door percentage (level row, step 10); 0 and 100 stay
        // on the dedicated close and open fids
        Triple("主驾打开30", "window_driver_pos", 30),
        Triple("后右打开70", "window_rear_right_pos", 70),
        Triple("副驾打开10", "window_passenger_pos", 10),
        Triple("后左打开90", "window_rear_left_pos", 90),
        Triple("主驾打开0", "window_driver_close", 2),
        Triple("主驾打开100", "window_driver_open", 1),
        // Interior / ambient light (candidate, dev=1023 carve-out); ambient off must
        // write raw 1 (level 0), not 0 (level -1, no effect)
        Triple("打开车内灯", "interior_light_on", 2),
        Triple("关闭车内灯", "interior_light_off", 1),
        Triple("氛围灯打开", "ambient_light_on", null),
        Triple("氛围灯关闭", "ambient_light_off", 1),
        // DRL (ДХО), dev=1004 carve-out, validated 2026-05-29
        Triple("打开日行灯", "drl_on", 1),
        Triple("关闭日行灯", "drl_off", 2),
        // Rear logo light, dev=1004 carve-out (#383)
        Triple("后车标灯打开", "rear_logo_light_on", 1),
        Triple("后车标灯关闭", "rear_logo_light_off", 2),
        // Mirror heat = rear defrost (one button on Leopard 3), validated 2026-05-29
        Triple("后视镜加热", "defrost_rear_on", 1),
        Triple("关闭后视镜加热", "defrost_rear_off", 0),
        // ac_power fid 501219364: 0=off, 1=on (live-validated 2026-07-03)
        Triple("自动空调", "ac_on", 1),
        // Temperature: dynamic parse over full 16..33 range
        Triple("设置温度22", "ac_temp_main", 22),
        Triple("设置温度24", "ac_temp_main", 24),
        Triple("设置温度16", "ac_temp_main", 16),
        Triple("设置温度33", "ac_temp_main", 33),
        // Fan speed: dynamic 风量<N> → ac_wind_level (#201)
        Triple("风量4", "ac_wind_level", 4),
        // Blow direction: static 吹面/吹脚/除霜 family → ac_wind_mode values 1 to 5 (#201)
        Triple("吹面", "ac_wind_mode", 1),
        Triple("吹面吹脚", "ac_wind_mode", 2),
        Triple("吹脚", "ac_wind_mode", 3),
        Triple("吹脚除霜", "ac_wind_mode", 4),
        Triple("除霜", "ac_wind_mode", 5),
        // 吹前挡 drives defrost_front_on on a different fid: the 除霜 entry must not shadow it
        Triple("吹前挡", "defrost_front_on", null),
        // Trunk and front trunk: dev=1001, open=1 close=3
        Triple("开后备箱", "open_trunk", 1),
        Triple("关后备箱", "close_trunk", 3),
        Triple("前备箱打开", "front_trunk_open", 1),
        Triple("前备箱关闭", "front_trunk_close", 3),
        // Fridge: dev=1023 carve-out; mode 1/2/3
        Triple("冰箱制冷", "fridge_mode", 1),
        Triple("冰箱关闭", "fridge_mode", 3),
    )

    @Test fun `single commands map to their action and value`() {
        for ((cmd, action, value) in singleCommands) {
            val r = one(cmd)
            assertEquals("action for $cmd", action, r?.actionName)
            if (value != null) assertEquals("value for $cmd", value, r?.value)
        }
    }

    @Test fun `drive mode commands map to their dev 1023 values`() {
        val expected = mapOf(
            "ECO模式" to ("drive_mode_eco" to 2),
            "普通模式" to ("drive_mode_normal" to 1),
            "运动模式" to ("drive_mode_sport" to 3),
            "雪地模式" to ("drive_mode_snow" to 4),
            "沙地模式" to ("drive_mode_sand" to 5),
            "泥地模式" to ("drive_mode_mud" to 6),
            "山地模式" to ("drive_mode_mountain" to 7),
            "岩石模式" to ("drive_mode_rock" to 8),
            "智能模式" to ("drive_mode_smart" to 21),
        )
        for ((cmd, pair) in expected) {
            assertEquals(cmd, CommandTranslator.Resolved(pair.first, pair.second), one(cmd))
            assertEquals(cmd, pair.second, DriveMode.ofAction(pair.first)?.value)
        }
    }

    // ── Rear windows (aggregate) — fan-out to both open/close fids ────────────
    @Test fun `rear windows open fans out to both rear open fids`() {
        assertEquals(
            setOf("window_rear_left_open" to 1, "window_rear_right_open" to 1),
            pairs("后排车窗全开"),
        )
    }

    @Test fun `rear windows close fans out to both rear close fids`() {
        assertEquals(
            setOf("window_rear_left_close" to 2, "window_rear_right_close" to 2),
            pairs("后排车窗关闭"),
        )
    }

    // ── Front windows (aggregate) — fan-out to driver + passenger ─────────────
    @Test fun `front windows open fans out to driver and passenger open fids`() {
        assertEquals(
            setOf("window_driver_open" to 1, "window_passenger_open" to 1),
            pairs("前排车窗全开"),
        )
    }

    @Test fun `front windows close fans out to driver and passenger close fids`() {
        assertEquals(
            setOf("window_driver_close" to 2, "window_passenger_close" to 2),
            pairs("前排车窗关闭"),
        )
    }

    // ── All windows (aggregate) — fan-out to all four open/close fids ─────────
    @Test fun `all windows open fans out to all four open fids`() {
        assertEquals(
            setOf(
                "window_driver_open" to 1,
                "window_passenger_open" to 1,
                "window_rear_left_open" to 1,
                "window_rear_right_open" to 1,
            ),
            pairs("车窗全开"),
        )
    }

    @Test fun `all windows close fans out to all four close fids`() {
        assertEquals(
            setOf(
                "window_driver_close" to 2,
                "window_passenger_close" to 2,
                "window_rear_left_close" to 2,
                "window_rear_right_close" to 2,
            ),
            pairs("车窗关闭"),
        )
    }

    // ── Half stays on the % path (no "half" command on the open/close fids) ───
    @Test fun `all windows half fans out to all four pos fids at 50`() {
        assertEquals(
            setOf(
                "window_driver_pos" to 50,
                "window_passenger_pos" to 50,
                "window_rear_left_pos" to 50,
                "window_rear_right_pos" to 50,
            ),
            pairs("车窗半开"),
        )
    }

    // ── All windows vent — fan-out to all four % fids at VENT_PCT (was driver-only fid) ──
    @Test fun `all windows vent fans out to all four pos fids at 10`() {
        assertEquals(
            setOf(
                "window_driver_pos" to 10,
                "window_passenger_pos" to 10,
                "window_rear_left_pos" to 10,
                "window_rear_right_pos" to 10,
            ),
            pairs("车窗通风"),
        )
    }

    @Test fun `front pair half and vent fan out to the two front pos fids`() {
        assertEquals(
            setOf("window_driver_pos" to 50, "window_passenger_pos" to 50),
            pairs("前排车窗半开"),
        )
        assertEquals(
            setOf("window_driver_pos" to 10, "window_passenger_pos" to 10),
            pairs("前排车窗通风"),
        )
    }

    @Test fun `rear pair half and vent fan out to the two rear pos fids`() {
        assertEquals(
            setOf("window_rear_left_pos" to 50, "window_rear_right_pos" to 50),
            pairs("后排车窗半开"),
        )
        assertEquals(
            setOf("window_rear_left_pos" to 10, "window_rear_right_pos" to 10),
            pairs("后排车窗通风"),
        )
    }

    @Test fun `ac power semantics - off sends 0, on sends 1 on fid 501219364`() {
        val off = one("关闭空调")
        assertEquals("ac_off", off?.actionName)
        assertEquals(0, off?.value)
        val on = one("自动空调")
        assertEquals("ac_on", on?.actionName)
        assertEquals(1, on?.value)

        val allowlist = WriteAllowlist.loadProduction { "{}" }
        val offEntry = allowlist.find("ac_off")!!
        assertEquals(501219364, offEntry.writeFid)
        assertEquals(0, offEntry.valueMin)
        assertEquals(0, offEntry.valueMax)
        val onEntry = allowlist.find("ac_on")!!
        assertEquals(501219364, onEntry.writeFid)
        assertEquals(1, onEntry.valueMin)
        assertEquals(1, onEntry.valueMax)
        assertTrue(offEntry.validated)
        assertTrue(onEntry.validated)
    }

    // Out-of-range request clamps into the validated 16..33 window.
    @Test fun `set temperature 35 clamps to ac_temp_main val 33`() {
        val r = one("设置温度35")
        assertEquals("ac_temp_main", r?.actionName)
        assertEquals(33, r?.value)
    }

    // Only the canonical 1..99 forms resolve dynamically: anything else is an unknown command,
    // never a clamped full open the speed gate does not recognise as an open.
    @Test fun `a non-canonical window percentage resolves to nothing`() {
        for (cmd in listOf("主驾打开150", "主驾打开0100", "主驾打开00", "主驾打开05", "后右打开101")) {
            assertTrue(cmd, CommandTranslator.resolve(cmd).isEmpty())
        }
    }

    @Test fun `the speed gate and the translator agree on which window forms exist`() {
        assertFalse(ActionDispatcher.isWindowOpenCommand("主驾打开150"))
        assertTrue(CommandTranslator.resolve("主驾打开150").isEmpty())
        for (pct in 1..99) {
            val cmd = "主驾打开$pct"
            assertTrue(cmd, ActionDispatcher.isWindowOpenCommand(cmd))
            assertEquals(setOf("window_driver_pos" to pct), pairs(cmd))
        }
    }

    @Test fun `set temperature 5 clamps to ac_temp_main val 16`() {
        val r = one("设置温度5")
        assertEquals("ac_temp_main", r?.actionName)
        assertEquals(16, r?.value)
    }

    @Test fun `fan level 9 clamps to ac_wind_level val 7`() {
        val r = one("风量9")
        assertEquals("ac_wind_level", r?.actionName)
        assertEquals(7, r?.value)
    }

    @Test fun `fan level 0 clamps to ac_wind_level val 1`() {
        val r = one("风量0")
        assertEquals("ac_wind_level", r?.actionName)
        assertEquals(1, r?.value)
    }

    @Test fun `fridge cool 0C fans out to mode 1 plus temp raw 19`() {
        assertEquals(setOf("fridge_mode" to 1, "fridge_temp_cool" to 19), pairs("冰箱制冷0度"))
    }

    @Test fun `fridge cool minus 6C maps to temp raw 13`() {
        assertEquals(setOf("fridge_mode" to 1, "fridge_temp_cool" to 13), pairs("冰箱制冷-6度"))
    }

    @Test fun `fridge heat 40C fans out to mode 2 plus temp raw 40`() {
        assertEquals(setOf("fridge_mode" to 2, "fridge_temp_heat" to 40), pairs("冰箱制热40度"))
    }

    // ── Test 12: allActions returns non-empty set of unique names ─────────────
    @Test fun `allActions returns non-empty set`() {
        val actions = CommandTranslator.allActions()
        assertTrue("allActions must be non-empty", actions.isNotEmpty())
    }

    // ── resolveSeat: dedicated seat parser, out of composite fan-out ──────────
    @Test fun `resolveSeat parses driver vent level`() {
        assertEquals(SeatCommand(SeatGroup.DRIVER_VENT, 1), CommandTranslator.resolveSeat("主驾座椅通风1档"))
        assertEquals(SeatCommand(SeatGroup.DRIVER_VENT, 5), CommandTranslator.resolveSeat("主驾座椅通风5档"))
    }
    @Test fun `resolveSeat parses off`() {
        assertEquals(SeatCommand(SeatGroup.PASSENGER_HEAT, 0), CommandTranslator.resolveSeat("副驾座椅加热关闭"))
    }
    @Test fun `resolveSeat strips prefix`() {
        assertEquals(SeatCommand(SeatGroup.DRIVER_HEAT, 2), CommandTranslator.resolveSeat("迪加主驾座椅加热2档"))
    }
    @Test fun `resolveSeat returns null for non-seat`() {
        assertEquals(null, CommandTranslator.resolveSeat("车窗全开"))
    }
    @Test fun `seat commands no longer in composite fan-out`() {
        assertEquals(emptyList<CommandTranslator.Resolved>(), CommandTranslator.resolve("主驾座椅通风1档"))
    }

    // ── Rear seat heat (#327) ─────────────────────────────────────────────────
    @Test fun `rear seat heat commands parse to seat and level`() {
        assertEquals(RearSeatCommand(RearSeat.LEFT, 1), CommandTranslator.resolveRearSeatHeat("后左座椅加热1档"))
        assertEquals(RearSeatCommand(RearSeat.RIGHT, 2), CommandTranslator.resolveRearSeatHeat("后右座椅加热2档"))
        assertEquals(RearSeatCommand(RearSeat.LEFT, 0), CommandTranslator.resolveRearSeatHeat("后左座椅加热关闭"))
        assertEquals(RearSeatCommand(RearSeat.RIGHT, 0), CommandTranslator.resolveRearSeatHeat("迪加后右座椅加热关闭"))
    }

    @Test fun `rear seat heat rejects what is not a rear stage`() {
        for (cmd in listOf("后左座椅加热3档", "后左座椅加热0档", "后左座椅加热", "后左打开30", "主驾座椅加热1档")) {
            assertNull(cmd, CommandTranslator.resolveRearSeatHeat(cmd))
        }
    }

    @Test fun `rear seat heat is no front seat and no table command`() {
        assertNull(CommandTranslator.resolveSeat("后左座椅加热1档"))
        assertTrue(CommandTranslator.resolve("后左座椅加热1档").isEmpty())
    }
}
