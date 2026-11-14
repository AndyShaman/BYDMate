package com.bydmate.app.ui.automation

import android.content.Context
import com.bydmate.app.data.local.LocalePreferences
import androidx.annotation.StringRes
import com.bydmate.app.R
import com.bydmate.app.util.appLocalizedContext
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.automation.ActionDispatcher

// --- Catalogs ---

data class TriggerParamOption(
    val param: String,
    val chineseName: String,                          // canonical zh id, persisted into TriggerDef.chineseName (not display)
    @StringRes val nameRes: Int,
    @StringRes val categoryRes: Int,
    @StringRes val unitRes: Int? = null,
    val enumValues: List<Pair<String, Int>>? = null   // value -> @StringRes label
)

data class ActionOption(
    val command: String,
    @StringRes val nameRes: Int,
    @StringRes val categoryRes: Int,
    /**
     * Set on the «… переключить» entries that sit next to an on/off pair: picking one stores a
     * `toggle` action on this target instead of the command above, which stays empty for them.
     */
    val toggleTarget: String? = null,
)

internal fun currentLang(context: Context): String =
    LocalePreferences(context).getLanguage() ?: "en"

fun TriggerParamOption.localizedName(context: Context): String =
    context.appLocalizedContext().getString(nameRes)

fun TriggerParamOption.localizedCategory(context: Context): String =
    context.appLocalizedContext().getString(categoryRes)

fun TriggerParamOption.localizedUnit(context: Context): String =
    unitRes?.let { context.appLocalizedContext().getString(it) } ?: ""

fun TriggerParamOption.localizedEnumLabel(value: String, context: Context): String =
    enumValues?.firstOrNull { it.first == value }
        ?.let { context.appLocalizedContext().getString(it.second) } ?: value

fun ActionOption.localizedName(context: Context): String {
    val lc = context.appLocalizedContext()
    // A toggle entry reuses the target's own name and reads as «Багажник: переключить».
    return if (toggleTarget != null) lc.getString(R.string.auto_act_toggle_catalog, lc.getString(nameRes))
    else lc.getString(nameRes)
}

/**
 * The action a catalog pick stores: a toggle entry produces exactly the ActionDef the retired
 * «Переключить» kind picker produced, so an old rule and a new one are the same row.
 */
fun actionDefFor(option: ActionOption, context: Context): ActionDef =
    if (option.toggleTarget != null) {
        ActionDef(
            command = "",
            displayName = toggleDisplayName(context, option.toggleTarget),
            kind = "toggle",
            payload = option.toggleTarget,
        )
    } else {
        ActionDef(option.command, option.localizedName(context))
    }

fun ActionOption.localizedCategory(context: Context): String =
    context.appLocalizedContext().getString(categoryRes)

val TRIGGER_PARAMS = listOf(
        TriggerParamOption("Speed", "车速", R.string.auto_param_speed, R.string.auto_cat_driving, R.string.auto_unit_kmh),
        TriggerParamOption("Gear", "档位", R.string.auto_param_gear, R.string.auto_cat_driving, enumValues = listOf("1" to R.string.auto_enum_code_p, "2" to R.string.auto_enum_code_r, "3" to R.string.auto_enum_code_n, "4" to R.string.auto_enum_code_d)),
        TriggerParamOption("DriveMode", "整车运行模式", R.string.auto_param_drivemode, R.string.auto_cat_driving, enumValues = listOf("1" to R.string.auto_enum_code_eco, "2" to R.string.auto_enum_code_sport, "3" to R.string.auto_enum_code_normal, "4" to R.string.auto_enum_code_snow, "5" to R.string.auto_enum_code_sand, "6" to R.string.auto_enum_code_mud, "7" to R.string.auto_enum_code_mountain, "21" to R.string.auto_enum_code_smart)),
        // Live codes (Leopard 3 2026-07-31): mask of the blinker lines, holds steady while blinking
        TriggerParamOption("TurnSignal", "转向灯", R.string.auto_param_turnsignal, R.string.auto_cat_driving, enumValues = listOf("1" to R.string.auto_enum_turn_off, "2" to R.string.auto_enum_turn_left, "4" to R.string.auto_enum_turn_right, "6" to R.string.auto_enum_turn_hazard)),
        TriggerParamOption("SOC", "电量百分比", R.string.auto_param_soc, R.string.auto_cat_energy, R.string.auto_unit_percent),
        TriggerParamOption("ChargingStatus", "充电状态", R.string.auto_param_chargingstatus, R.string.auto_cat_energy, enumValues = listOf("0" to R.string.auto_enum_none, "1" to R.string.auto_enum_connected, "2" to R.string.auto_enum_charging)),
        TriggerParamOption("Voltage12V", "蓄电池电压", R.string.auto_param_voltage12v, R.string.auto_cat_energy, R.string.auto_unit_volt),
        TriggerParamOption("MinCellVoltage", "单体最低电压", R.string.auto_param_mincellvoltage, R.string.auto_cat_energy, R.string.auto_unit_volt),
        TriggerParamOption("MaxCellVoltage", "单体最高电压", R.string.auto_param_maxcellvoltage, R.string.auto_cat_energy, R.string.auto_unit_volt),
        // The range the Dashboard shows (TrackingService), not a fid of its own
        TriggerParamOption("RangeKm", "续航里程", R.string.auto_param_rangekm, R.string.auto_cat_energy, R.string.auto_unit_km),
        TriggerParamOption("ExtTemp", "车外温度", R.string.auto_param_exttemp, R.string.auto_cat_temperature, R.string.auto_unit_celsius),
        TriggerParamOption("InsideTemp", "车内温度", R.string.auto_param_insidetemp, R.string.auto_cat_temperature, R.string.auto_unit_celsius),
        TriggerParamOption("AvgBatTemp", "平均电池温度", R.string.auto_param_avgbattemp, R.string.auto_cat_temperature, R.string.auto_unit_celsius),
        TriggerParamOption("WindowFL", "主驾车窗打开百分比", R.string.auto_param_windowfl, R.string.auto_cat_body, R.string.auto_unit_percent_open),
        TriggerParamOption("WindowFR", "副驾车窗打开百分比", R.string.auto_param_windowfr, R.string.auto_cat_body, R.string.auto_unit_percent_open),
        TriggerParamOption("WindowRL", "左后车窗打开百分比", R.string.auto_param_windowrl, R.string.auto_cat_body, R.string.auto_unit_percent_open),
        TriggerParamOption("WindowRR", "右后车窗打开百分比", R.string.auto_param_windowrr, R.string.auto_cat_body, R.string.auto_unit_percent_open),
        TriggerParamOption("Sunroof", "天窗打开百分比", R.string.auto_param_sunroof, R.string.auto_cat_body, R.string.auto_unit_percent_open),
        TriggerParamOption("DoorFL", "主驾车门", R.string.auto_param_doorfl, R.string.auto_cat_body, enumValues = listOf("0" to R.string.auto_enum_closed_f, "1" to R.string.auto_enum_open_f)),
        TriggerParamOption("DoorFR", "副驾车门", R.string.auto_param_doorfr, R.string.auto_cat_body, enumValues = listOf("0" to R.string.auto_enum_closed_f, "1" to R.string.auto_enum_open_f)),
        TriggerParamOption("DoorRL", "左后车门", R.string.auto_param_doorrl, R.string.auto_cat_body, enumValues = listOf("0" to R.string.auto_enum_closed_f, "1" to R.string.auto_enum_open_f)),
        TriggerParamOption("DoorRR", "右后车门", R.string.auto_param_doorrr, R.string.auto_cat_body, enumValues = listOf("0" to R.string.auto_enum_closed_f, "1" to R.string.auto_enum_open_f)),
        TriggerParamOption("Hood", "引擎盖", R.string.auto_param_hood, R.string.auto_cat_body, enumValues = listOf("0" to R.string.auto_enum_closed_m, "1" to R.string.auto_enum_open_m)),
        TriggerParamOption("LockFL", "主驾车门锁", R.string.auto_param_lockfl, R.string.auto_cat_body, enumValues = listOf("1" to R.string.auto_enum_unlocked, "2" to R.string.auto_enum_locked)),  // codes match DiParsData.lockFL runtime: 1=unlocked, 2=locked
        // Live codes: 2=closed, 1=open, 3=moving (0 is not a trunk state; old rules migrated)
        TriggerParamOption("Trunk", "后备箱门", R.string.auto_param_trunk, R.string.auto_cat_body, enumValues = listOf("2" to R.string.auto_enum_closed_m, "1" to R.string.auto_enum_open_m, "3" to R.string.auto_enum_moving_m)),
        TriggerParamOption("ACStatus", "空调状态", R.string.auto_param_acstatus, R.string.auto_cat_climate),
        TriggerParamOption("ACCirc", "空调循环方式", R.string.auto_param_accirc, R.string.auto_cat_climate, enumValues = listOf("0" to R.string.auto_enum_fresh_air, "1" to R.string.auto_enum_recirc)),
        TriggerParamOption("ACTemp", "主驾驶空调温度", R.string.auto_param_actemp, R.string.auto_cat_climate, R.string.auto_unit_celsius),
        TriggerParamOption("FanLevel", "风量档位", R.string.auto_param_fanlevel, R.string.auto_cat_climate),
        TriggerParamOption("SeatbeltFL", "主驾驶安全带状态", R.string.auto_param_seatbeltfl, R.string.auto_cat_safety, enumValues = listOf("0" to R.string.auto_enum_unfastened, "1" to R.string.auto_enum_fastened)),
        TriggerParamOption("SeatbeltFR", "副驾安全带状态", R.string.auto_param_seatbeltfr, R.string.auto_cat_safety, enumValues = listOf("0" to R.string.auto_enum_unfastened, "1" to R.string.auto_enum_fastened)),
        TriggerParamOption("SeatbeltRL", "左后安全带状态", R.string.auto_param_seatbeltrl, R.string.auto_cat_safety, enumValues = listOf("0" to R.string.auto_enum_unfastened, "1" to R.string.auto_enum_fastened)),
        TriggerParamOption("SeatbeltRM", "后中安全带状态", R.string.auto_param_seatbeltrm, R.string.auto_cat_safety, enumValues = listOf("0" to R.string.auto_enum_unfastened, "1" to R.string.auto_enum_fastened)),
        TriggerParamOption("SeatbeltRR", "右后安全带状态", R.string.auto_param_seatbeltrr, R.string.auto_cat_safety, enumValues = listOf("0" to R.string.auto_enum_unfastened, "1" to R.string.auto_enum_fastened)),
        // Occupancy codes: 1=free, 2=occupied (validated on-car; NOT 0/1 as the BYD manual claims)
        TriggerParamOption("OccupancyFL", "主驾座椅占用状态", R.string.auto_param_occupancyfl, R.string.auto_cat_safety, enumValues = listOf("1" to R.string.auto_enum_seat_free, "2" to R.string.auto_enum_seat_occupied)),
        TriggerParamOption("OccupancyFR", "副驾座椅占用状态", R.string.auto_param_occupancyfr, R.string.auto_cat_safety, enumValues = listOf("1" to R.string.auto_enum_seat_free, "2" to R.string.auto_enum_seat_occupied)),
        TriggerParamOption("OccupancyRL", "左后座椅占用状态", R.string.auto_param_occupancyrl, R.string.auto_cat_safety, enumValues = listOf("1" to R.string.auto_enum_seat_free, "2" to R.string.auto_enum_seat_occupied)),
        TriggerParamOption("OccupancyRM", "后中座椅占用状态", R.string.auto_param_occupancyrm, R.string.auto_cat_safety, enumValues = listOf("1" to R.string.auto_enum_seat_free, "2" to R.string.auto_enum_seat_occupied)),
        TriggerParamOption("OccupancyRR", "右后座椅占用状态", R.string.auto_param_occupancyrr, R.string.auto_cat_safety, enumValues = listOf("1" to R.string.auto_enum_seat_free, "2" to R.string.auto_enum_seat_occupied)),
        TriggerParamOption("KeyBattery", "钥匙电池状态", R.string.auto_param_keybattery, R.string.auto_cat_safety, R.string.auto_unit_key_ok_hint),
        TriggerParamOption("TirePressFL", "左前轮气压", R.string.auto_param_tirepressfl, R.string.auto_cat_safety, R.string.auto_unit_kpa),
        TriggerParamOption("TirePressFR", "右前轮气压", R.string.auto_param_tirepressfr, R.string.auto_cat_safety, R.string.auto_unit_kpa),
        TriggerParamOption("TirePressRL", "左后轮气压", R.string.auto_param_tirepressrl, R.string.auto_cat_safety, R.string.auto_unit_kpa),
        TriggerParamOption("TirePressRR", "右后轮气压", R.string.auto_param_tirepressrr, R.string.auto_cat_safety, R.string.auto_unit_kpa),
        TriggerParamOption("Rain", "雨量", R.string.auto_param_rain, R.string.auto_cat_safety, R.string.auto_unit_dry_hint),
        TriggerParamOption("LightLow", "近光灯", R.string.auto_param_lightlow, R.string.auto_cat_light, enumValues = listOf("0" to R.string.auto_enum_off, "1" to R.string.auto_enum_on)),
        TriggerParamOption("DRL", "日行灯", R.string.auto_param_drl, R.string.auto_cat_light, enumValues = listOf("0" to R.string.auto_enum_none, "1" to R.string.auto_enum_on, "2" to R.string.auto_enum_off)),
        TriggerParamOption("LightLevel", "光照等级", R.string.auto_param_lightlevel, R.string.auto_cat_light, R.string.auto_unit_light_hint),
)

val ACTION_COMMANDS = listOf(
        ActionOption("车窗通风", R.string.auto_act_vent_windows, R.string.auto_cat_windows),
        ActionOption("车窗关闭", R.string.auto_act_close_all_windows, R.string.auto_cat_windows),
        ActionOption("车窗全开", R.string.auto_act_open_all_windows, R.string.auto_cat_windows),
        ActionOption("车窗半开", R.string.auto_act_all_windows_50, R.string.auto_cat_windows),
        ActionOption("前排车窗关闭", R.string.auto_act_close_front_windows, R.string.auto_cat_windows),
        ActionOption("后排车窗关闭", R.string.auto_act_close_rear_windows, R.string.auto_cat_windows),
        ActionOption("前排车窗全开", R.string.auto_act_open_front_windows, R.string.auto_cat_windows),
        ActionOption("后排车窗全开", R.string.auto_act_open_rear_windows, R.string.auto_cat_windows),
        ActionOption("主驾打开100", R.string.auto_act_open_driver_window, R.string.auto_cat_windows),
        ActionOption("主驾打开0", R.string.auto_act_close_driver_window, R.string.auto_cat_windows),
        ActionOption("副驾打开100", R.string.auto_act_open_passenger_window, R.string.auto_cat_windows),
        ActionOption("副驾打开0", R.string.auto_act_close_passenger_window, R.string.auto_cat_windows),
        ActionOption("后左打开100", R.string.auto_act_open_rear_left_window, R.string.auto_cat_windows),
        ActionOption("后左打开0", R.string.auto_act_close_rear_left_window, R.string.auto_cat_windows),
        ActionOption("后右打开100", R.string.auto_act_open_rear_right_window, R.string.auto_cat_windows),
        ActionOption("后右打开0", R.string.auto_act_close_rear_right_window, R.string.auto_cat_windows),
        ActionOption("主驾通风", R.string.auto_act_vent_driver_window, R.string.auto_cat_windows),
        ActionOption("副驾通风", R.string.auto_act_vent_passenger_window, R.string.auto_cat_windows),
        ActionOption("后左通风", R.string.auto_act_vent_rear_left_window, R.string.auto_cat_windows),
        ActionOption("后右通风", R.string.auto_act_vent_rear_right_window, R.string.auto_cat_windows),
        ActionOption("主驾半开", R.string.auto_act_half_driver_window, R.string.auto_cat_windows),
        ActionOption("副驾半开", R.string.auto_act_half_passenger_window, R.string.auto_cat_windows),
        ActionOption("后左半开", R.string.auto_act_half_rear_left_window, R.string.auto_cat_windows),
        ActionOption("后右半开", R.string.auto_act_half_rear_right_window, R.string.auto_cat_windows),
        ActionOption("前排车窗半开", R.string.auto_act_half_front_windows, R.string.auto_cat_windows),
        ActionOption("前排车窗通风", R.string.auto_act_vent_front_windows, R.string.auto_cat_windows),
        ActionOption("后排车窗半开", R.string.auto_act_half_rear_windows, R.string.auto_cat_windows),
        ActionOption("后排车窗通风", R.string.auto_act_vent_rear_windows, R.string.auto_cat_windows),
        ActionOption("自动空调", R.string.auto_act_auto_ac, R.string.auto_cat_climate),
        ActionOption("打开空调通风", R.string.auto_act_ventilation_no_ac, R.string.auto_cat_climate),
        ActionOption("吹面", R.string.auto_act_wind_face, R.string.auto_cat_climate),
        ActionOption("吹面吹脚", R.string.auto_act_wind_face_feet, R.string.auto_cat_climate),
        ActionOption("吹脚", R.string.auto_act_wind_feet, R.string.auto_cat_climate),
        ActionOption("吹脚除霜", R.string.auto_act_wind_feet_windshield, R.string.auto_cat_climate),
        ActionOption("除霜", R.string.auto_act_wind_windshield, R.string.auto_cat_climate),
        ActionOption("内循环", R.string.auto_act_recirculation, R.string.auto_cat_climate),
        ActionOption("外循环", R.string.auto_act_fresh_air, R.string.auto_cat_climate),
        ActionOption("吹前挡", R.string.auto_act_windshield_defog_on, R.string.auto_cat_climate),
        ActionOption("关闭吹前挡", R.string.auto_act_windshield_defog_off, R.string.auto_cat_climate),
        ActionOption("关闭空调", R.string.auto_act_ac_off, R.string.auto_cat_climate),
        ActionOption("", R.string.toggle_target_climate, R.string.auto_cat_climate, toggleTarget = ActionDispatcher.TOGGLE_CLIMATE),
        ActionOption("空调自动", R.string.auto_act_ac_auto_on, R.string.auto_cat_climate),
        ActionOption("空调手动", R.string.auto_act_ac_auto_off, R.string.auto_cat_climate),
        ActionOption("", R.string.toggle_target_seat_heat_driver, R.string.auto_cat_seats, toggleTarget = ActionDispatcher.TOGGLE_SEAT_HEAT_DRIVER),
        ActionOption("", R.string.toggle_target_seat_heat_passenger, R.string.auto_cat_seats, toggleTarget = ActionDispatcher.TOGGLE_SEAT_HEAT_PASSENGER),
        ActionOption("", R.string.toggle_target_seat_vent_driver, R.string.auto_cat_seats, toggleTarget = ActionDispatcher.TOGGLE_SEAT_VENT_DRIVER),
        ActionOption("", R.string.toggle_target_seat_vent_passenger, R.string.auto_cat_seats, toggleTarget = ActionDispatcher.TOGGLE_SEAT_VENT_PASSENGER),
        ActionOption("后视镜加热", R.string.auto_act_mirror_heat_on, R.string.auto_cat_mirrors),
        ActionOption("关闭后视镜加热", R.string.auto_act_mirror_heat_off, R.string.auto_cat_mirrors),
        ActionOption("方向盘加热", R.string.auto_act_steering_heat_on, R.string.auto_cat_climate),
        ActionOption("关闭方向盘加热", R.string.auto_act_steering_heat_off, R.string.auto_cat_climate),
        ActionOption("", R.string.toggle_target_steering_heat, R.string.auto_cat_climate, toggleTarget = ActionDispatcher.TOGGLE_STEERING_HEAT),
        ActionOption("ECO模式", R.string.auto_act_drive_mode_eco, R.string.auto_cat_drive_mode),
        ActionOption("普通模式", R.string.auto_act_drive_mode_normal, R.string.auto_cat_drive_mode),
        ActionOption("运动模式", R.string.auto_act_drive_mode_sport, R.string.auto_cat_drive_mode),
        ActionOption("雪地模式", R.string.auto_act_drive_mode_snow, R.string.auto_cat_drive_mode),
        ActionOption("沙地模式", R.string.auto_act_drive_mode_sand, R.string.auto_cat_drive_mode),
        ActionOption("泥地模式", R.string.auto_act_drive_mode_mud, R.string.auto_cat_drive_mode),
        ActionOption("山地模式", R.string.auto_act_drive_mode_mountain, R.string.auto_cat_drive_mode),
        ActionOption("岩石模式", R.string.auto_act_drive_mode_rock, R.string.auto_cat_drive_mode),
        ActionOption("智能模式", R.string.auto_act_drive_mode_smart, R.string.auto_cat_drive_mode),
        ActionOption("氛围灯打开", R.string.auto_act_ambient_light_on, R.string.auto_cat_light),
        ActionOption("氛围灯关闭", R.string.auto_act_ambient_light_off, R.string.auto_cat_light),
        ActionOption("打开日行灯", R.string.auto_act_drl_on, R.string.auto_cat_light),
        ActionOption("关闭日行灯", R.string.auto_act_drl_off, R.string.auto_cat_light),
        ActionOption("双闪打开", R.string.auto_act_hazard_on, R.string.auto_cat_light),
        ActionOption("双闪关闭", R.string.auto_act_hazard_off, R.string.auto_cat_light),
        ActionOption("", R.string.toggle_target_hazard, R.string.auto_cat_light, toggleTarget = ActionDispatcher.TOGGLE_HAZARD),
        ActionOption("后车标灯打开", R.string.auto_act_rear_logo_light_on, R.string.auto_cat_light),
        ActionOption("后车标灯关闭", R.string.auto_act_rear_logo_light_off, R.string.auto_cat_light),
        ActionOption("打开车内灯", R.string.auto_act_interior_light_on, R.string.auto_cat_light),
        ActionOption("关闭车内灯", R.string.auto_act_interior_light_off, R.string.auto_cat_light),
        ActionOption("打开抬头显示", R.string.auto_act_hud_on, R.string.auto_cat_light),
        ActionOption("关闭抬头显示", R.string.auto_act_hud_off, R.string.auto_cat_light),
        ActionOption("车门上锁", R.string.auto_act_lock_doors, R.string.auto_cat_locks),
        ActionOption("车门解锁", R.string.auto_act_unlock_doors, R.string.auto_cat_locks),
        ActionOption("", R.string.toggle_target_locks, R.string.auto_cat_locks, toggleTarget = ActionDispatcher.TOGGLE_LOCKS),
        ActionOption("天窗打开100", R.string.auto_act_sunroof_open_100, R.string.auto_cat_sunroof),
        ActionOption("天窗打开50", R.string.auto_act_sunroof_open_50, R.string.auto_cat_sunroof),
        ActionOption("天窗打开0", R.string.auto_act_sunroof_close, R.string.auto_cat_sunroof),
        ActionOption("", R.string.toggle_target_sunroof, R.string.auto_cat_sunroof, toggleTarget = ActionDispatcher.TOGGLE_SUNROOF),
        ActionOption("遮阳帘打开", R.string.auto_act_sunshade_open, R.string.auto_cat_sunroof),
        ActionOption("遮阳帘打开50", R.string.auto_act_sunshade_open_50, R.string.auto_cat_sunroof),
        ActionOption("遮阳帘关闭", R.string.auto_act_sunshade_close, R.string.auto_cat_sunroof),
        ActionOption("遮阳帘停止", R.string.auto_act_sunshade_stop, R.string.auto_cat_sunroof),
        ActionOption("天窗停止", R.string.auto_act_sunroof_stop, R.string.auto_cat_sunroof),
        ActionOption("天窗通风", R.string.auto_act_sunroof_updip, R.string.auto_cat_sunroof),
        ActionOption("天窗舒适打开", R.string.auto_act_sunroof_comfort, R.string.auto_cat_sunroof),
        ActionOption("开后备箱", R.string.auto_act_open_trunk, R.string.auto_cat_body),
        ActionOption("关后备箱", R.string.auto_act_close_trunk, R.string.auto_cat_body),
        ActionOption("", R.string.toggle_target_trunk, R.string.auto_cat_body, toggleTarget = ActionDispatcher.TOGGLE_TRUNK),
        ActionOption("前备箱打开", R.string.auto_act_open_front_trunk, R.string.auto_cat_body),
        ActionOption("前备箱关闭", R.string.auto_act_close_front_trunk, R.string.auto_cat_body),
        ActionOption("", R.string.toggle_target_front_trunk, R.string.auto_cat_body, toggleTarget = ActionDispatcher.TOGGLE_FRONT_TRUNK),
        ActionOption("打开尾翼", R.string.auto_act_spoiler_up, R.string.auto_cat_body),
        ActionOption("关闭尾翼", R.string.auto_act_spoiler_down, R.string.auto_cat_body),
        ActionOption("尾翼自动模式", R.string.auto_act_spoiler_auto, R.string.auto_cat_body),
        ActionOption("冰箱制冷", R.string.auto_act_fridge_cool, R.string.auto_cat_fridge),
        ActionOption("冰箱制热", R.string.auto_act_fridge_heat, R.string.auto_cat_fridge),
        ActionOption("冰箱关闭", R.string.auto_act_fridge_off, R.string.auto_cat_fridge),
        ActionOption("打开电池预热", R.string.auto_act_battery_preheat_on, R.string.auto_cat_energy),
        ActionOption("关闭电池预热", R.string.auto_act_battery_preheat_off, R.string.auto_cat_energy),
)

val OPERATORS = listOf(">", "<", ">=", "<=", "==", "!=")
