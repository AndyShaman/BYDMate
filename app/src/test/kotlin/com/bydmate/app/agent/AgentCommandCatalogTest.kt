package com.bydmate.app.agent

import com.bydmate.app.voice.DeviceSlot
import com.bydmate.app.voice.VoiceCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentCommandCatalogTest {

    @Test fun `covers every fixed VoiceCatalog command string`() {
        val voiceFixed = VoiceCatalog.ALL.filter { it.value == null }.map { it.command(null) }.toSet()
        val catalogFixed = AgentCommandCatalog.ALL.filter { it.value == null }.map { it.chinese(null) }.toSet()
        // One-directional: the agent catalog must cover everything the legacy Vosk
        // NLU fast-path can do, but it is allowed to know MORE (fridge, front trunk,
        // etc.) — commands with no VoiceCatalog/NLU surface at all. Asserting exact
        // equality here would break every time the agent catalog grows past NLU.
        assertEquals(emptySet<String>(), voiceFixed - catalogFixed)
    }

    @Test fun `ac_set_temp range and template match VoiceCatalog AC_TEMP`() {
        val voiceSpec = VoiceCatalog.ALL.first { it.device == DeviceSlot.AC_TEMP }
        val catalogCmd = AgentCommandCatalog.ALL.first { it.id == "ac_set_temp" }
        assertEquals(voiceSpec.value!!.min, catalogCmd.value!!.first)
        assertEquals(voiceSpec.value!!.max, catalogCmd.value!!.last)
        assertEquals(voiceSpec.command(22), catalogCmd.chinese(22))
    }

    // The id is what the model picks, the string is what goes to the car: a pair swapped in the
    // catalog (open for close, driver for passenger) still "resolves", so each pair is pinned.
    @Test fun `each id resolves to its own car command`() {
        for ((id, value, command) in idToCommand) {
            assertEquals("case: $id value=$value", command, AgentCommandCatalog.resolve(id, value))
        }
    }

    @Test fun `resolve rejects out-of-range value`() {
        assertNull(AgentCommandCatalog.resolve("ac_set_temp", 40))
    }

    @Test fun `resolve rejects unknown id`() {
        assertNull(AgentCommandCatalog.resolve("nonsense", null))
    }

    @Test fun `idsDoc has no Chinese and lists all ids`() {
        val doc = AgentCommandCatalog.idsDoc()
        assertEquals(AgentCommandCatalog.ALL.size, doc.lines().size)
        assertEquals(false, doc.any { it.code > 0x2E80 })
    }

    private val idToCommand by lazy {
        listOf(
            Triple("windows_close_all", null, "车窗关闭"),
            Triple("window_driver_half", null, "主驾半开"),
            Triple("window_passenger_half", null, "副驾半开"),
            Triple("window_rear_left_half", null, "后左半开"),
            Triple("window_rear_right_half", null, "后右半开"),
            Triple("windows_half_front", null, "前排车窗半开"),
            Triple("windows_vent_front", null, "前排车窗通风"),
            Triple("windows_half_rear", null, "后排车窗半开"),
            Triple("windows_vent_rear", null, "后排车窗通风"),
            Triple("hazard_on", null, "双闪打开"),
            Triple("hazard_off", null, "双闪关闭"),
            Triple("steering_heat_on", null, "方向盘加热"),
            Triple("steering_heat_off", null, "关闭方向盘加热"),
            Triple("hud_on", null, "打开抬头显示"),
            Triple("hud_off", null, "关闭抬头显示"),
            Triple("battery_preheat_on", null, "打开电池预热"),
            Triple("battery_preheat_off", null, "关闭电池预热"),
            Triple("ac_set_temp", 22, "设置温度22"),
            Triple("fridge_cool", -4, "冰箱制冷-4度"),
            Triple("fridge_cool", 6, "冰箱制冷6度"),
            Triple("fridge_heat", 40, "冰箱制热40度"),
            Triple("fridge_off", null, "冰箱关闭"),
            Triple("front_trunk_open", null, "前备箱打开"),
            Triple("front_trunk_close", null, "前备箱关闭"),
            Triple("seat_heat_driver_4", null, "主驾座椅加热4档"),
            Triple("seat_heat_driver_5", null, "主驾座椅加热5档"),
            Triple("seat_heat_passenger_4", null, "副驾座椅加热4档"),
            Triple("seat_vent_driver_5", null, "主驾座椅通风5档"),
            Triple("seat_vent_passenger_4", null, "副驾座椅通风4档"),
            Triple("sunroof_stop", null, "天窗停止"),
            Triple("sunroof_updip", null, "天窗通风"),
            Triple("sunroof_comfort", null, "天窗舒适打开"),
        )
    }
}
