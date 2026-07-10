package com.bydmate.app.voice

import com.bydmate.app.voice.ActionSlot.*
import com.bydmate.app.voice.DeviceSlot.*

/** One supported voice command: a (action, device, value?) key and the
 *  dispatchable Chinese command string it produces. The string MUST exist in
 *  CommandTranslator (validated by VoiceCommandSpecTest.every_catalog_command_is_dispatchable). */
data class VoiceCommandSpec(
    val action: ActionSlot,
    val device: DeviceSlot,
    val value: ValueSpec? = null,
    val command: (Int?) -> String,
)

object VoiceCatalog {
    val ALL: List<VoiceCommandSpec> = listOf(
        // Windows — composites
        VoiceCommandSpec(OPEN, WINDOW_ALL) { "车窗全开" },
        VoiceCommandSpec(CLOSE, WINDOW_ALL) { "车窗关闭" },
        VoiceCommandSpec(VENT, WINDOW_ALL) { "车窗通风" },
        VoiceCommandSpec(HALF, WINDOW_ALL) { "车窗半开" },
        VoiceCommandSpec(OPEN, WINDOW_FRONT) { "前排车窗全开" },
        VoiceCommandSpec(CLOSE, WINDOW_FRONT) { "前排车窗关闭" },
        VoiceCommandSpec(OPEN, WINDOW_REAR) { "后排车窗全开" },
        VoiceCommandSpec(CLOSE, WINDOW_REAR) { "后排车窗关闭" },
        // Windows — individual (open=100 / close=0)
        VoiceCommandSpec(OPEN, WINDOW_DRIVER) { "主驾打开100" },
        VoiceCommandSpec(CLOSE, WINDOW_DRIVER) { "主驾打开0" },
        VoiceCommandSpec(OPEN, WINDOW_PASSENGER) { "副驾打开100" },
        VoiceCommandSpec(CLOSE, WINDOW_PASSENGER) { "副驾打开0" },
        VoiceCommandSpec(OPEN, WINDOW_REAR_LEFT) { "后左打开100" },
        VoiceCommandSpec(CLOSE, WINDOW_REAR_LEFT) { "后左打开0" },
        VoiceCommandSpec(OPEN, WINDOW_REAR_RIGHT) { "后右打开100" },
        VoiceCommandSpec(CLOSE, WINDOW_REAR_RIGHT) { "后右打开0" },
        // Windows — individual vent (crack one window for fresh air)
        VoiceCommandSpec(VENT, WINDOW_DRIVER) { "主驾通风" },
        VoiceCommandSpec(VENT, WINDOW_PASSENGER) { "副驾通风" },
        VoiceCommandSpec(VENT, WINDOW_REAR_LEFT) { "后左通风" },
        VoiceCommandSpec(VENT, WINDOW_REAR_RIGHT) { "后右通风" },
        // Climate
        VoiceCommandSpec(ON, AC_AUTO) { "自动空调" },
        VoiceCommandSpec(OFF, AC_AUTO) { "关闭空调" },
        VoiceCommandSpec(ON, AC_FLOW) { "打开空调通风" },
        VoiceCommandSpec(SET, AC_TEMP, ValueSpec(16, 30)) { n -> "设置温度${n}" },
        VoiceCommandSpec(ON, AC_RECIRC_INNER) { "内循环" },
        VoiceCommandSpec(ON, AC_RECIRC_OUTER) { "外循环" },
        VoiceCommandSpec(ON, DEFROST_FRONT) { "吹前挡" },
        VoiceCommandSpec(OFF, DEFROST_FRONT) { "关闭吹前挡" },
        // Seats — heat
        VoiceCommandSpec(HEAT_1, SEAT_DRIVER_HEAT) { "主驾座椅加热1档" },
        VoiceCommandSpec(HEAT_2, SEAT_DRIVER_HEAT) { "主驾座椅加热2档" },
        VoiceCommandSpec(HEAT_3, SEAT_DRIVER_HEAT) { "主驾座椅加热3档" },
        VoiceCommandSpec(OFF, SEAT_DRIVER_HEAT) { "主驾座椅加热关闭" },
        VoiceCommandSpec(HEAT_1, SEAT_PASSENGER_HEAT) { "副驾座椅加热1档" },
        VoiceCommandSpec(HEAT_2, SEAT_PASSENGER_HEAT) { "副驾座椅加热2档" },
        VoiceCommandSpec(HEAT_3, SEAT_PASSENGER_HEAT) { "副驾座椅加热3档" },
        VoiceCommandSpec(OFF, SEAT_PASSENGER_HEAT) { "副驾座椅加热关闭" },
        // Seats — vent
        VoiceCommandSpec(VENT_1, SEAT_DRIVER_VENT) { "主驾座椅通风1档" },
        VoiceCommandSpec(VENT_2, SEAT_DRIVER_VENT) { "主驾座椅通风2档" },
        VoiceCommandSpec(VENT_3, SEAT_DRIVER_VENT) { "主驾座椅通风3档" },
        VoiceCommandSpec(OFF, SEAT_DRIVER_VENT) { "主驾座椅通风关闭" },
        VoiceCommandSpec(VENT_1, SEAT_PASSENGER_VENT) { "副驾座椅通风1档" },
        VoiceCommandSpec(VENT_2, SEAT_PASSENGER_VENT) { "副驾座椅通风2档" },
        VoiceCommandSpec(VENT_3, SEAT_PASSENGER_VENT) { "副驾座椅通风3档" },
        VoiceCommandSpec(OFF, SEAT_PASSENGER_VENT) { "副驾座椅通风关闭" },
        // Mirrors
        VoiceCommandSpec(ON, MIRROR_HEAT) { "后视镜加热" },
        VoiceCommandSpec(OFF, MIRROR_HEAT) { "关闭后视镜加热" },
        // Lights
        VoiceCommandSpec(ON, LIGHT_AMBIENT) { "氛围灯打开" },
        VoiceCommandSpec(OFF, LIGHT_AMBIENT) { "氛围灯关闭" },
        VoiceCommandSpec(ON, LIGHT_DRL) { "打开日行灯" },
        VoiceCommandSpec(OFF, LIGHT_DRL) { "关闭日行灯" },
        VoiceCommandSpec(ON, LIGHT_INTERIOR) { "打开车内灯" },
        VoiceCommandSpec(OFF, LIGHT_INTERIOR) { "关闭车内灯" },
        // Locks — "запри"/"отопри" via the door/lock synonyms ("замок", "двери").
        VoiceCommandSpec(ON, LOCK) { "车门上锁" },
        VoiceCommandSpec(OFF, LOCK) { "车门解锁" },
        // Car — open/close verbs are deliberately NOT wired to the door/lock
        // synonyms: "закрой дверь" from cabin chatter must fall through to the
        // agent. Only the explicit "машина" surface locks/unlocks via fast-path.
        VoiceCommandSpec(ON, CAR) { "车门上锁" },
        VoiceCommandSpec(OFF, CAR) { "车门解锁" },
        VoiceCommandSpec(CLOSE, CAR) { "车门上锁" },
        VoiceCommandSpec(OPEN, CAR) { "车门解锁" },
        // Sunroof / sunshade
        VoiceCommandSpec(OPEN, SUNROOF) { "天窗打开100" },
        VoiceCommandSpec(HALF, SUNROOF) { "天窗打开50" },
        VoiceCommandSpec(CLOSE, SUNROOF) { "天窗打开0" },
        VoiceCommandSpec(OPEN, SUNSHADE) { "遮阳帘打开" },
        VoiceCommandSpec(CLOSE, SUNSHADE) { "遮阳帘关闭" },
        // Trunk
        VoiceCommandSpec(OPEN, TRUNK) { "开后备箱" },
        VoiceCommandSpec(CLOSE, TRUNK) { "关后备箱" },
    )

    private val byKey: Map<Pair<ActionSlot, DeviceSlot>, VoiceCommandSpec> =
        ALL.associateBy { it.action to it.device }

    /** Resolve a filled slot set to a dispatchable command string, or null if
     *  the combination is unsupported / the value is out of range. */
    fun resolve(action: ActionSlot, device: DeviceSlot, value: Int?): String? {
        val spec = byKey[action to device] ?: return null
        val vs = spec.value
        if (vs != null) {
            val v = value ?: return null
            if (v < vs.min || v > vs.max) return null
            return spec.command(v)
        }
        return spec.command(null)
    }
}
