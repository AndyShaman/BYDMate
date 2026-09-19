package com.bydmate.app.data.remote

import org.json.JSONObject

object AliceBridgeCommandTranslator {
    data class Resolved(val action: String, val vehicleCommand: String)

    fun resolve(json: JSONObject): Resolved? {
        val action = json.optString("action").trim().lowercase()
        if (action.isBlank()) return null

        val command = when (action) {
            "climate.on" -> "自动空调"
            "climate.off" -> "关闭空调"
            "climate.auto_on" -> "空调自动"
            "climate.auto_off" -> "空调手动"
            "climate.recirculation_inner" -> "内循环"
            "climate.recirculation_outer" -> "外循环"
            "climate.rear_defrost_on" -> "后视镜加热"
            "climate.rear_defrost_off" -> "关闭后视镜加热"
            "climate.front_defrost_on" -> "吹前挡"
            "climate.front_defrost_off" -> "关闭吹前挡"
            "climate.flow_only_on" -> "打开空调通风"
            "climate.flow_only_off" -> "关闭空调通风"
            "climate.temperature" -> ranged("设置温度", json, 16..30) ?: return null
            "climate.fan_level" -> ranged("风量", json, 1..7) ?: return null
            "climate.airflow_face" -> "吹面"
            "climate.airflow_face_feet" -> "吹面吹脚"
            "climate.airflow_feet" -> "吹脚"
            "climate.airflow_feet_windshield" -> "吹脚除霜"
            "climate.airflow_windshield" -> "除霜"
            "climate.airflow_face_feet_windshield" -> "吹面吹脚除霜"
            "climate.airflow_face_windshield" -> "吹面除霜"

            "seat.driver.heat" -> seat("主驾座椅加热", json) ?: return null
            "seat.passenger.heat" -> seat("副驾座椅加热", json) ?: return null
            "seat.driver.vent" -> seat("主驾座椅通风", json) ?: return null
            "seat.passenger.vent" -> seat("副驾座椅通风", json) ?: return null

            "light.interior_on" -> "打开车内灯"
            "light.interior_off" -> "关闭车内灯"
            "light.ambient_on" -> "氛围灯打开"
            "light.ambient_off" -> "氛围灯关闭"
            "light.drl_on" -> "打开日行灯"
            "light.drl_off" -> "关闭日行灯"
            "light.hazard_on" -> "双闪打开"
            "light.hazard_off" -> "双闪关闭"

            "window.driver.open" -> "主驾打开100"
            "window.driver.close" -> "主驾打开0"
            "window.driver.vent" -> "主驾通风"
            "window.driver.half" -> "主驾半开"
            "window.passenger.open" -> "副驾打开100"
            "window.passenger.close" -> "副驾打开0"
            "window.passenger.vent" -> "副驾通风"
            "window.passenger.half" -> "副驾半开"
            "window.rear_left.open" -> "后左打开100"
            "window.rear_left.close" -> "后左打开0"
            "window.rear_left.vent" -> "后左通风"
            "window.rear_left.half" -> "后左半开"
            "window.rear_right.open" -> "后右打开100"
            "window.rear_right.close" -> "后右打开0"
            "window.rear_right.vent" -> "后右通风"
            "window.rear_right.half" -> "后右半开"
            "window.all.open" -> "车窗全开"
            "window.all.close" -> "车窗关闭"
            "window.all.half" -> "车窗半开"
            "window.all.vent" -> "车窗通风"
            "window.front.open" -> "前排车窗全开"
            "window.front.close" -> "前排车窗关闭"
            "window.front.half" -> "前排车窗半开"
            "window.front.vent" -> "前排车窗通风"
            "window.rear.open" -> "后排车窗全开"
            "window.rear.close" -> "后排车窗关闭"
            "window.rear.half" -> "后排车窗半开"
            "window.rear.vent" -> "后排车窗通风"

            "doors.lock" -> "车门上锁"
            "doors.unlock" -> "车门解锁"
            "trunk.rear.open" -> "开后备箱"
            "trunk.rear.close" -> "关后备箱"
            "trunk.front.open" -> "前备箱打开"
            "trunk.front.close" -> "前备箱关闭"

            "sunroof.open" -> "天窗打开100"
            "sunroof.close" -> "天窗打开0"
            "sunroof.tilt" -> "天窗打开50"
            "sunroof.vent" -> "天窗通风"
            "sunroof.comfort" -> "天窗舒适打开"
            "sunroof.stop" -> "天窗停止"
            "sunshade.open" -> "遮阳帘打开"
            "sunshade.close" -> "遮阳帘关闭"

            "fridge.cool" -> "冰箱制冷"
            "fridge.heat" -> "冰箱制热"
            "fridge.off" -> "冰箱关闭"
            "fridge.cool_temperature" -> ranged("冰箱制冷", json, -6..6, "度") ?: return null
            "fridge.heat_temperature" -> ranged("冰箱制热", json, 35..50, "度") ?: return null

            else -> return null
        }
        return Resolved(action, command)
    }

    private fun ranged(prefix: String, json: JSONObject, range: IntRange, suffix: String = ""): String? {
        val value = json.intValue() ?: return null
        if (value !in range) return null
        return "${prefix}${value}${suffix}"
    }

    private fun seat(prefix: String, json: JSONObject): String? {
        val level = json.intValue() ?: return null
        if (level !in 0..5) return null
        return if (level == 0) "${prefix}关闭" else "${prefix}${level}档"
    }

    private fun JSONObject.intValue(): Int? = when (val value = opt("value")) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }
}
