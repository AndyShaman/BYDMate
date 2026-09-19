package com.bydmate.app.data.remote

import org.json.JSONObject

object AliceBridgeCommandTranslator {
    data class Resolved(val action: String, val vehicleCommand: String)

    private val fixedCommands = mapOf(
        "climate.on" to "自动空调",
        "climate.off" to "关闭空调",
        "climate.recirculation_inner" to "内循环",
        "climate.recirculation_outer" to "外循环",
        "climate.rear_defrost_on" to "后视镜加热",
        "climate.rear_defrost_off" to "关闭后视镜加热",
        "climate.front_defrost_on" to "吹前挡",
        "climate.front_defrost_off" to "关闭吹前挡",
        "climate.flow_only_on" to "打开空调通风",
        "climate.flow_only_off" to "关闭空调通风",
        "climate.airflow_face" to "吹面",
        "climate.airflow_face_feet" to "吹面吹脚",
        "climate.airflow_feet" to "吹脚",
        "climate.airflow_feet_windshield" to "吹脚除霜",
        "climate.airflow_windshield" to "除霜",
        "climate.airflow_face_feet_windshield" to "吹面吹脚除霜",
        "climate.airflow_face_windshield" to "吹面除霜",
        "light.interior_on" to "打开车内灯",
        "light.interior_off" to "关闭车内灯",
        "light.ambient_on" to "氛围灯打开",
        "light.ambient_off" to "氛围灯关闭",
        "window.driver.open" to "主驾打开100",
        "window.driver.close" to "主驾打开0",
        "window.passenger.open" to "副驾打开100",
        "window.passenger.close" to "副驾打开0",
        "window.rear_left.open" to "后左打开100",
        "window.rear_left.close" to "后左打开0",
        "window.rear_right.open" to "后右打开100",
        "window.rear_right.close" to "后右打开0",
        "window.all.open" to "车窗全开",
        "window.all.close" to "车窗关闭",
        "window.all.half" to "车窗半开",
        "window.all.vent" to "车窗通风",
        "trunk.rear.open" to "开后备箱",
        "trunk.rear.close" to "关后备箱",
        "sunroof.open" to "天窗打开100",
        "sunroof.close" to "天窗打开0",
        "sunroof.tilt" to "天窗打开50",
        "sunroof.vent" to "天窗通风",
        "sunroof.comfort" to "天窗舒适打开",
        "sunshade.open" to "遮阳帘打开",
        "sunshade.close" to "遮阳帘关闭",
    )

    fun resolve(json: JSONObject): Resolved? {
        val action = json.optString("action").trim().lowercase()
        if (action.isBlank()) return null
        val command = fixedCommands[action] ?: dynamicCommand(action, json) ?: return null
        return Resolved(action, command)
    }

    private fun dynamicCommand(action: String, json: JSONObject): String? = when (action) {
        "climate.temperature" -> ranged("设置温度", json, 16..30)
        "climate.fan_level" -> ranged("风量", json, 1..7)
        "seat.driver.heat" -> seat("主驾座椅加热", json)
        "seat.passenger.heat" -> seat("副驾座椅加热", json)
        "seat.driver.vent" -> seat("主驾座椅通风", json)
        "seat.passenger.vent" -> seat("副驾座椅通风", json)
        else -> null
    }

    private fun ranged(prefix: String, json: JSONObject, range: IntRange): String? {
        val value = json.intValue() ?: return null
        return value.takeIf(range::contains)?.let { prefix + it }
    }

    private fun seat(prefix: String, json: JSONObject): String? {
        val level = json.intValue()?.takeIf { it in 0..5 } ?: return null
        return if (level == 0) prefix + "关闭" else prefix + level + "档"
    }

    private fun JSONObject.intValue(): Int? = when (val value = opt("value")) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }
}
