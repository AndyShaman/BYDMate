package com.bydmate.app.ui.automation

import android.content.ActivityNotFoundException
import android.content.Context
import android.database.sqlite.SQLiteException
import android.content.Intent
import android.os.Environment
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bydmate.app.data.local.LocalePreferences
import androidx.annotation.StringRes
import com.bydmate.app.BuildConfig
import com.bydmate.app.R
import com.bydmate.app.util.appLocalizedContext
import com.bydmate.app.data.automation.ActionValidationError
import com.bydmate.app.data.automation.AutomationEngine
import com.bydmate.app.data.automation.RuleDraftValidator
import com.bydmate.app.data.automation.RuleParseResult
import com.bydmate.app.data.automation.RuleShare
import com.bydmate.app.data.automation.RuleShareFiles
import com.bydmate.app.data.automation.RuleShareUrl
import com.bydmate.app.data.automation.SharedRule
import com.bydmate.app.data.automation.TriggerValidationError
import com.bydmate.app.voice.VoiceUserPhrases
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.dao.RuleLogDao
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.PlaceEntity
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.RuleLogEntity
import com.bydmate.app.data.local.entity.TriggerDef
import com.bydmate.app.data.repository.PlaceRepository
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.loop.TimedSnapshot
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.vehicle.VehicleApi
import com.bydmate.app.service.TrackingService
import com.bydmate.app.ui.overlay.OverlayNotificationManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject

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
        TriggerParamOption("DriveMode", "整车运行模式", R.string.auto_param_drivemode, R.string.auto_cat_driving, enumValues = listOf("1" to R.string.auto_enum_code_eco, "2" to R.string.auto_enum_code_sport, "3" to R.string.auto_enum_code_normal, "4" to R.string.auto_enum_code_offroad)),
        // Live codes (Leopard 3 2026-07-31): mask of the blinker lines, holds steady while blinking
        TriggerParamOption("TurnSignal", "转向灯", R.string.auto_param_turnsignal, R.string.auto_cat_driving, enumValues = listOf("1" to R.string.auto_enum_turn_off, "2" to R.string.auto_enum_turn_left, "4" to R.string.auto_enum_turn_right, "6" to R.string.auto_enum_turn_hazard)),
        TriggerParamOption("SOC", "电量百分比", R.string.auto_param_soc, R.string.auto_cat_energy, R.string.auto_unit_percent),
        TriggerParamOption("ChargingStatus", "充电状态", R.string.auto_param_chargingstatus, R.string.auto_cat_energy, enumValues = listOf("0" to R.string.auto_enum_none, "1" to R.string.auto_enum_connected, "2" to R.string.auto_enum_charging)),
        TriggerParamOption("PowerState", "电源状态", R.string.auto_param_powerstate, R.string.auto_cat_energy, enumValues = listOf("0" to R.string.auto_enum_code_off, "1" to R.string.auto_enum_code_on, "2" to R.string.auto_enum_code_drive)),
        TriggerParamOption("Voltage12V", "蓄电池电压", R.string.auto_param_voltage12v, R.string.auto_cat_energy, R.string.auto_unit_volt),
        TriggerParamOption("MinCellVoltage", "单体最低电压", R.string.auto_param_mincellvoltage, R.string.auto_cat_energy, R.string.auto_unit_volt),
        TriggerParamOption("MaxCellVoltage", "单体最高电压", R.string.auto_param_maxcellvoltage, R.string.auto_cat_energy, R.string.auto_unit_volt),
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
        TriggerParamOption("Trunk", "后备箱门", R.string.auto_param_trunk, R.string.auto_cat_body, enumValues = listOf("0" to R.string.auto_enum_closed_m, "1" to R.string.auto_enum_open_m)),
        TriggerParamOption("ACStatus", "空调状态", R.string.auto_param_acstatus, R.string.auto_cat_climate),
        TriggerParamOption("ACCirc", "空调循环方式", R.string.auto_param_accirc, R.string.auto_cat_climate, enumValues = listOf("0" to R.string.auto_enum_fresh_air, "1" to R.string.auto_enum_recirc)),
        TriggerParamOption("ACTemp", "主驾驶空调温度", R.string.auto_param_actemp, R.string.auto_cat_climate, R.string.auto_unit_celsius),
        TriggerParamOption("FanLevel", "风量档位", R.string.auto_param_fanlevel, R.string.auto_cat_climate),
        TriggerParamOption("SeatbeltFL", "主驾驶安全带状态", R.string.auto_param_seatbeltfl, R.string.auto_cat_safety, enumValues = listOf("0" to R.string.auto_enum_unfastened, "1" to R.string.auto_enum_fastened)),
        TriggerParamOption("SeatbeltFR", "副驾安全带状态", R.string.auto_param_seatbeltfr, R.string.auto_cat_safety, enumValues = listOf("0" to R.string.auto_enum_unfastened, "1" to R.string.auto_enum_fastened)),
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
        ActionOption("设置温度18", R.string.auto_act_temp_18c, R.string.auto_cat_climate),
        ActionOption("设置温度20", R.string.auto_act_temp_20c, R.string.auto_cat_climate),
        ActionOption("设置温度22", R.string.auto_act_temp_22c, R.string.auto_cat_climate),
        ActionOption("设置温度25", R.string.auto_act_temp_25c, R.string.auto_cat_climate),
        ActionOption("风量1", R.string.auto_act_fan_1, R.string.auto_cat_climate),
        ActionOption("风量2", R.string.auto_act_fan_2, R.string.auto_cat_climate),
        ActionOption("风量3", R.string.auto_act_fan_3, R.string.auto_cat_climate),
        ActionOption("风量4", R.string.auto_act_fan_4, R.string.auto_cat_climate),
        ActionOption("风量5", R.string.auto_act_fan_5, R.string.auto_cat_climate),
        ActionOption("风量6", R.string.auto_act_fan_6, R.string.auto_cat_climate),
        ActionOption("风量7", R.string.auto_act_fan_7, R.string.auto_cat_climate),
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
        ActionOption("主驾座椅加热1档", R.string.auto_act_driver_heat_1, R.string.auto_cat_seats),
        ActionOption("主驾座椅加热2档", R.string.auto_act_driver_heat_2, R.string.auto_cat_seats),
        ActionOption("主驾座椅加热3档", R.string.auto_act_driver_heat_3, R.string.auto_cat_seats),
        ActionOption("主驾座椅加热4档", R.string.auto_act_driver_heat_4, R.string.auto_cat_seats),
        ActionOption("主驾座椅加热5档", R.string.auto_act_driver_heat_5, R.string.auto_cat_seats),
        ActionOption("主驾座椅加热关闭", R.string.auto_act_driver_heat_off, R.string.auto_cat_seats),
        ActionOption("", R.string.toggle_target_seat_heat_driver, R.string.auto_cat_seats, toggleTarget = ActionDispatcher.TOGGLE_SEAT_HEAT_DRIVER),
        ActionOption("副驾座椅加热1档", R.string.auto_act_passenger_heat_1, R.string.auto_cat_seats),
        ActionOption("副驾座椅加热2档", R.string.auto_act_passenger_heat_2, R.string.auto_cat_seats),
        ActionOption("副驾座椅加热3档", R.string.auto_act_passenger_heat_3, R.string.auto_cat_seats),
        ActionOption("副驾座椅加热4档", R.string.auto_act_passenger_heat_4, R.string.auto_cat_seats),
        ActionOption("副驾座椅加热5档", R.string.auto_act_passenger_heat_5, R.string.auto_cat_seats),
        ActionOption("副驾座椅加热关闭", R.string.auto_act_passenger_heat_off, R.string.auto_cat_seats),
        ActionOption("", R.string.toggle_target_seat_heat_passenger, R.string.auto_cat_seats, toggleTarget = ActionDispatcher.TOGGLE_SEAT_HEAT_PASSENGER),
        ActionOption("主驾座椅通风1档", R.string.auto_act_driver_vent_1, R.string.auto_cat_seats),
        ActionOption("主驾座椅通风2档", R.string.auto_act_driver_vent_2, R.string.auto_cat_seats),
        ActionOption("主驾座椅通风3档", R.string.auto_act_driver_vent_3, R.string.auto_cat_seats),
        ActionOption("主驾座椅通风4档", R.string.auto_act_driver_vent_4, R.string.auto_cat_seats),
        ActionOption("主驾座椅通风5档", R.string.auto_act_driver_vent_5, R.string.auto_cat_seats),
        ActionOption("主驾座椅通风关闭", R.string.auto_act_driver_vent_off, R.string.auto_cat_seats),
        ActionOption("", R.string.toggle_target_seat_vent_driver, R.string.auto_cat_seats, toggleTarget = ActionDispatcher.TOGGLE_SEAT_VENT_DRIVER),
        ActionOption("副驾座椅通风1档", R.string.auto_act_passenger_vent_1, R.string.auto_cat_seats),
        ActionOption("副驾座椅通风2档", R.string.auto_act_passenger_vent_2, R.string.auto_cat_seats),
        ActionOption("副驾座椅通风3档", R.string.auto_act_passenger_vent_3, R.string.auto_cat_seats),
        ActionOption("副驾座椅通风4档", R.string.auto_act_passenger_vent_4, R.string.auto_cat_seats),
        ActionOption("副驾座椅通风5档", R.string.auto_act_passenger_vent_5, R.string.auto_cat_seats),
        ActionOption("副驾座椅通风关闭", R.string.auto_act_passenger_vent_off, R.string.auto_cat_seats),
        ActionOption("", R.string.toggle_target_seat_vent_passenger, R.string.auto_cat_seats, toggleTarget = ActionDispatcher.TOGGLE_SEAT_VENT_PASSENGER),
        ActionOption("后视镜加热", R.string.auto_act_mirror_heat_on, R.string.auto_cat_mirrors),
        ActionOption("关闭后视镜加热", R.string.auto_act_mirror_heat_off, R.string.auto_cat_mirrors),
        ActionOption("方向盘加热", R.string.auto_act_steering_heat_on, R.string.auto_cat_climate),
        ActionOption("关闭方向盘加热", R.string.auto_act_steering_heat_off, R.string.auto_cat_climate),
        ActionOption("", R.string.toggle_target_steering_heat, R.string.auto_cat_climate, toggleTarget = ActionDispatcher.TOGGLE_STEERING_HEAT),
        ActionOption("氛围灯打开", R.string.auto_act_ambient_light_on, R.string.auto_cat_light),
        ActionOption("氛围灯关闭", R.string.auto_act_ambient_light_off, R.string.auto_cat_light),
        ActionOption("打开日行灯", R.string.auto_act_drl_on, R.string.auto_cat_light),
        ActionOption("关闭日行灯", R.string.auto_act_drl_off, R.string.auto_cat_light),
        ActionOption("双闪打开", R.string.auto_act_hazard_on, R.string.auto_cat_light),
        ActionOption("双闪关闭", R.string.auto_act_hazard_off, R.string.auto_cat_light),
        ActionOption("", R.string.toggle_target_hazard, R.string.auto_cat_light, toggleTarget = ActionDispatcher.TOGGLE_HAZARD),
        ActionOption("打开车内灯", R.string.auto_act_interior_light_on, R.string.auto_cat_light),
        ActionOption("关闭车内灯", R.string.auto_act_interior_light_off, R.string.auto_cat_light),
        ActionOption("车门上锁", R.string.auto_act_lock_doors, R.string.auto_cat_locks),
        ActionOption("车门解锁", R.string.auto_act_unlock_doors, R.string.auto_cat_locks),
        ActionOption("", R.string.toggle_target_locks, R.string.auto_cat_locks, toggleTarget = ActionDispatcher.TOGGLE_LOCKS),
        ActionOption("天窗打开100", R.string.auto_act_sunroof_open_100, R.string.auto_cat_sunroof),
        ActionOption("天窗打开50", R.string.auto_act_sunroof_open_50, R.string.auto_cat_sunroof),
        ActionOption("天窗打开0", R.string.auto_act_sunroof_close, R.string.auto_cat_sunroof),
        ActionOption("", R.string.toggle_target_sunroof, R.string.auto_cat_sunroof, toggleTarget = ActionDispatcher.TOGGLE_SUNROOF),
        ActionOption("遮阳帘打开", R.string.auto_act_sunshade_open, R.string.auto_cat_sunroof),
        ActionOption("遮阳帘关闭", R.string.auto_act_sunshade_close, R.string.auto_cat_sunroof),
        ActionOption("天窗停止", R.string.auto_act_sunroof_stop, R.string.auto_cat_sunroof),
        ActionOption("天窗通风", R.string.auto_act_sunroof_updip, R.string.auto_cat_sunroof),
        ActionOption("天窗舒适打开", R.string.auto_act_sunroof_comfort, R.string.auto_cat_sunroof),
        ActionOption("开后备箱", R.string.auto_act_open_trunk, R.string.auto_cat_body),
        ActionOption("关后备箱", R.string.auto_act_close_trunk, R.string.auto_cat_body),
        ActionOption("", R.string.toggle_target_trunk, R.string.auto_cat_body, toggleTarget = ActionDispatcher.TOGGLE_TRUNK),
        ActionOption("前备箱打开", R.string.auto_act_open_front_trunk, R.string.auto_cat_body),
        ActionOption("前备箱关闭", R.string.auto_act_close_front_trunk, R.string.auto_cat_body),
        ActionOption("", R.string.toggle_target_front_trunk, R.string.auto_cat_body, toggleTarget = ActionDispatcher.TOGGLE_FRONT_TRUNK),
        ActionOption("冰箱制冷", R.string.auto_act_fridge_cool, R.string.auto_cat_fridge),
        ActionOption("冰箱制热", R.string.auto_act_fridge_heat, R.string.auto_cat_fridge),
        ActionOption("冰箱关闭", R.string.auto_act_fridge_off, R.string.auto_cat_fridge),
        ActionOption("冰箱制冷-6度", R.string.auto_act_fridge_cool_minus6c, R.string.auto_cat_fridge),
        ActionOption("冰箱制冷-3度", R.string.auto_act_fridge_cool_minus3c, R.string.auto_cat_fridge),
        ActionOption("冰箱制冷0度", R.string.auto_act_fridge_cool_0c, R.string.auto_cat_fridge),
        ActionOption("冰箱制冷3度", R.string.auto_act_fridge_cool_plus_3c, R.string.auto_cat_fridge),
        ActionOption("冰箱制冷6度", R.string.auto_act_fridge_cool_plus_6c, R.string.auto_cat_fridge),
        ActionOption("冰箱制热35度", R.string.auto_act_fridge_heat_35c, R.string.auto_cat_fridge),
        ActionOption("冰箱制热40度", R.string.auto_act_fridge_heat_40c, R.string.auto_cat_fridge),
        ActionOption("冰箱制热45度", R.string.auto_act_fridge_heat_45c, R.string.auto_cat_fridge),
        ActionOption("冰箱制热50度", R.string.auto_act_fridge_heat_50c, R.string.auto_cat_fridge),
)

val OPERATORS = listOf(">", "<", ">=", "<=", "==", "!=")

enum class RuleFilter { ALL, ENABLED, DISABLED }

// --- ViewModel ---

data class EditingRule(
    val id: Long = 0,
    val name: String = "",
    val triggerLogic: String = "AND",
    val triggers: List<TriggerDef> = emptyList(),
    val actions: List<ActionDef> = emptyList(),
    val cooldownSeconds: Int = 60,
    val requirePark: Boolean = false,
    val confirmBeforeExecute: Boolean = false,
    val fireOncePerTrip: Boolean = false,
    val playSound: Boolean = false,
    val isNew: Boolean = true,
    /** The rule's switch when the editor opened: «Сохранить как новое» keeps it. */
    val enabled: Boolean = true,
) {
    fun toShared() = SharedRule(
        name = name.trim(),
        triggerLogic = triggerLogic,
        triggers = triggers,
        actions = actions,
        cooldownSeconds = cooldownSeconds,
        requirePark = requirePark,
        confirmBeforeExecute = confirmBeforeExecute,
        fireOncePerTrip = fireOncePerTrip,
        playSound = playSound,
    )

    /** [base] with what the editor edits; enabled, counters and creation time stay as in [base]. */
    fun applyTo(base: RuleEntity) = base.copy(
        name = name.trim(),
        triggerLogic = triggerLogic,
        triggers = TriggerDef.listToJson(triggers),
        actions = ActionDef.listToJson(actions),
        cooldownSeconds = cooldownSeconds.coerceAtLeast(1),
        requirePark = requirePark,
        confirmBeforeExecute = confirmBeforeExecute,
        fireOncePerTrip = fireOncePerTrip,
        playSound = playSound,
    )
}

/**
 * Step 2 of the import dialog: the parsed rule as it will be added, its [preview] lines, plus
 * the user's choices. [token] tells one parsed file from the next: a place or contact picked
 * for an older draft is dropped instead of landing on the same index of a new one. While
 * [saving] the insert runs and the draft is frozen: no edits, no «Отмена».
 */
data class RuleImportDraft(
    val rule: SharedRule,
    val preview: RuleImportPreview,
    val enableNow: Boolean = false,
    val error: String? = null,
    val token: Long = 0,
    val saving: Boolean = false,
)

data class AutomationUiState(
    val rules: List<RuleEntity> = emptyList(),
    val filter: RuleFilter = RuleFilter.ALL,
    val logs: List<RuleLogEntity> = emptyList(),
    val showEditor: Boolean = false,
    val showJournal: Boolean = false,
    val editing: EditingRule = EditingRule(),
    val showDeleteConfirm: Long? = null,
    val places: List<PlaceEntity> = emptyList(),
    val editorError: String? = null,
    /** The rule being edited was deleted meanwhile: the editor says so and closes on «Закрыть». */
    val editorRuleDeleted: Boolean = false,
    /** «Поделиться» tapped: non-null shows what the file keeps, before anything is written. */
    val pendingShare: SharedRule? = null,
    /** Import step 1: non-null shows the list of share files in Download. */
    val importFiles: List<File>? = null,
    /** Why the picked file could not be imported, shown under the step 1 list. */
    val importError: String? = null,
    /** Import step 2: non-null shows the preview. */
    val importDraft: RuleImportDraft? = null,
    val testRunning: Boolean = false,
    /** A share file is being written: the «Поделиться» buttons wait for it. */
    val shareInProgress: Boolean = false,
)

@HiltViewModel
class AutomationViewModel @Inject constructor(
    private val ruleDao: RuleDao,
    private val ruleLogDao: RuleLogDao,
    private val placeRepository: PlaceRepository,
    private val vehicleApi: VehicleApi,
    private val actionDispatcher: ActionDispatcher,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(AutomationUiState())
    val uiState: StateFlow<AutomationUiState> = _uiState.asStateFlow()

    // Test seams: tests point these at a temp dir, a test dispatcher, the telemetry and a
    // recorded share sheet (FileProvider caches its roots per process, so a real one leaks
    // between Robolectric tests).
    internal var downloadsDir: () -> File = {
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    }
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    internal var liveSnapshot: () -> DiParsData? = { TrackingService.lastData.value }
    internal var liveSample: () -> TimedSnapshot? = { TrackingService.lastSample }
    // The user's own phrases for built-in commands (normalized phrase -> command name): a voice
    // trigger may not reuse one. Read fresh from SharedPreferences on every save.
    internal var userCommandPhrases: () -> Map<String, String> = { VoiceUserPhrases(context).owners() }
    internal var serviceRunning: () -> Boolean = { TrackingService.isRunning.value }
    internal var elapsedNow: () -> Long = { SystemClock.elapsedRealtime() }
    internal var shareSheet: (File) -> Unit = { startShareSheet(it) }

    private var testRunJob: Job? = null
    private var importJob: Job? = null
    private var draftToken = 0L

    // Bumped on every editor open and close: a save that returns late only touches its own editor.
    private var editorSession = 0L
    private val shareMutex = Mutex()

    init {
        viewModelScope.launch { insertStarterTemplatesIfNeeded() }
        viewModelScope.launch {
            ruleDao.getAll().collect { rules ->
                _uiState.update { it.copy(rules = rules) }
            }
        }
        viewModelScope.launch {
            ruleLogDao.getRecent(100).collect { logs ->
                _uiState.update { it.copy(logs = logs) }
            }
        }
        viewModelScope.launch {
            placeRepository.getAll().collect { places ->
                _uiState.update { it.copy(places = places) }
            }
        }
    }

    fun setFilter(filter: RuleFilter) {
        _uiState.update { it.copy(filter = filter) }
    }

    fun toggleEnabled(rule: RuleEntity) {
        viewModelScope.launch { ruleDao.setEnabled(rule.id, !rule.enabled) }
    }

    /**
     * "Выполнить сейчас" — run a single action immediately, for live testing from the
     * rule editor. A "toggle" action goes through [ActionDispatcher] (it needs the live
     * state to pick the direction, and the dispatcher already applies every safety gate);
     * a param action takes the direct write path below. Result is surfaced via Toast.
     * Bypasses the automation edge/cooldown logic by design — this is a manual action.
     */
    fun executeNow(action: ActionDef) {
        if (action.kind == "toggle") {
            viewModelScope.launch {
                val result = actionDispatcher.dispatch(action, TrackingService.lastData.value)
                val lc = context.appLocalizedContext()
                val msg = if (result.success) lc.getString(R.string.auto_msg_dispatch_sent)
                          else result.reason ?: lc.getString(R.string.auto_msg_unavailable)
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
            return
        }
        executeCommandNow(action.command)
    }

    /**
     * Dispatches a raw vehicle command through the native write channel. The raw
     * autoservice status (real action vs no-op) lands in logcat via HelperClient.
     */
    private fun executeCommandNow(command: String) {
        viewModelScope.launch {
            // Manual test button bypasses ActionDispatcher.dispatch, so apply the
            // same safety gates explicitly (frunk/unlock fail closed on unknown speed).
            val block = ActionDispatcher.safetyBlockReason(command, TrackingService.lastData.value)
            if (block != null) {
                Toast.makeText(context, block.toText(context), Toast.LENGTH_SHORT).show()
                return@launch
            }
            val result = vehicleApi.dispatch(command)
            val lc = context.appLocalizedContext()
            val msg = if (result.isSuccess) lc.getString(R.string.auto_msg_dispatch_sent)
                      else lc.getString(
                          R.string.auto_msg_dispatch_error,
                          result.exceptionOrNull()?.message ?: lc.getString(R.string.auto_msg_unavailable),
                      )
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // --- Editor ---

    fun openNewRule() {
        if (_uiState.value.rules.size >= MAX_RULES) return
        editorSession++
        _uiState.update {
            it.copy(
                showEditor = true,
                editing = EditingRule(
                    triggers = emptyList(),
                    actions = emptyList()
                )
            )
        }
    }

    fun openEditRule(rule: RuleEntity) {
        editorSession++
        _uiState.update {
            it.copy(
                showEditor = true,
                editing = EditingRule(
                    id = rule.id,
                    name = rule.name,
                    triggerLogic = rule.triggerLogic,
                    triggers = TriggerDef.listFromJson(rule.triggers),
                    actions = ActionDef.listFromJson(rule.actions),
                    cooldownSeconds = rule.cooldownSeconds,
                    requirePark = rule.requirePark,
                    confirmBeforeExecute = rule.confirmBeforeExecute,
                    fireOncePerTrip = rule.fireOncePerTrip,
                    playSound = rule.playSound,
                    isNew = false,
                    enabled = rule.enabled,
                )
            )
        }
    }

    fun closeEditor() {
        // «Отмена» also stops a test run: nothing is left on screen to watch or stop it.
        testRunJob?.cancel()
        editorSession++
        _uiState.update { it.copy(showEditor = false, editorError = null, editorRuleDeleted = false) }
    }

    /**
     * «Правило уже удалено» → «Сохранить как новое»: the draft becomes a new rule, switch as it
     * was. The editor and the draft stay open until the insert returns: it closes only on
     * success, bound to this save's editor session, so a DB failure keeps the draft on screen
     * with a message instead of losing it.
     */
    fun saveDeletedRuleAsNew() {
        val e = _uiState.value.editing
        if (!_uiState.value.editorRuleDeleted) return
        val session = editorSession
        // Dismisses the confirmation dialog right away, so a second tap before the insert
        // returns is a no-op (the guard above), without touching the rest of the editor.
        _uiState.update { it.copy(editorRuleDeleted = false) }
        viewModelScope.launch {
            try {
                ruleDao.insert(e.applyTo(RuleEntity(name = "", triggers = "", actions = "", enabled = e.enabled)))
                if (session == editorSession) closeEditor()
            } catch (ex: SQLiteException) {
                Log.w("AutomationViewModel", "saveDeletedRuleAsNew: insert failed", ex)
                if (session == editorSession) {
                    _uiState.update {
                        it.copy(
                            editorError = context.appLocalizedContext()
                                .getString(R.string.automation_import_save_failed, ex.message ?: "?"),
                        )
                    }
                }
            }
        }
    }

    /** «Правило уже удалено» dismissed by Back or outside: the editor stays with the draft. */
    fun dismissRuleDeleted() {
        _uiState.update { it.copy(editorRuleDeleted = false) }
    }

    fun updateEditing(transform: EditingRule.() -> EditingRule) {
        _uiState.update { it.copy(editing = it.editing.transform()) }
    }

    private fun validateActions(actions: List<ActionDef>): String? {
        val ctx = context.appLocalizedContext()
        return when (val err = RuleDraftValidator.validateActions(actions)) {
            is ActionValidationError.CommandMissing -> ctx.getString(R.string.auto_msg_command_missing, err.index)
            is ActionValidationError.NotifTitleEmpty -> ctx.getString(R.string.auto_msg_notif_title_empty, err.index)
            is ActionValidationError.AppNotSelected -> ctx.getString(R.string.auto_msg_app_not_selected, err.index)
            is ActionValidationError.PhoneInvalid -> ctx.getString(R.string.auto_msg_phone_invalid, err.index)
            is ActionValidationError.NavDestMissing -> ctx.getString(R.string.auto_msg_nav_dest_missing, err.index)
            is ActionValidationError.UrlEmpty -> ctx.getString(R.string.auto_msg_url_empty, err.index)
            is ActionValidationError.UrlNoScheme -> ctx.getString(R.string.auto_msg_url_no_scheme, err.index)
            is ActionValidationError.YandexMusicModeMissing -> ctx.getString(R.string.auto_msg_ymusic_mode_missing, err.index)
            is ActionValidationError.MediaVolumeMissing -> ctx.getString(R.string.auto_msg_media_volume_missing, err.index)
            is ActionValidationError.SentryInvalid -> ctx.getString(R.string.auto_msg_sentry_invalid, err.index)
            is ActionValidationError.HotspotInvalid -> ctx.getString(R.string.auto_msg_hotspot_invalid, err.index)
            is ActionValidationError.SpeakTextEmpty -> ctx.getString(R.string.auto_msg_speak_text_empty, err.index)
            is ActionValidationError.AgentQueryPromptEmpty -> ctx.getString(R.string.auto_msg_agent_query_prompt_empty, err.index)
            is ActionValidationError.SplitScreenNarrowEmpty -> ctx.getString(R.string.auto_msg_split_narrow_empty, err.index)
            is ActionValidationError.SplitScreenWideEmpty -> ctx.getString(R.string.auto_msg_split_wide_empty, err.index)
            is ActionValidationError.SplitScreenSamePackage -> ctx.getString(R.string.auto_msg_split_same_package, err.index)
            is ActionValidationError.SplitScreenInvalidSide -> ctx.getString(R.string.auto_msg_split_invalid_side, err.index)
            null -> null
        }
    }

    private fun validateTriggers(triggers: List<TriggerDef>, editingId: Long): String? {
        val ctx = context.appLocalizedContext()
        val userPhrases = runCatching { userCommandPhrases() }.getOrDefault(emptyMap())
        return when (val err = RuleDraftValidator.validateTriggers(triggers, editingId, _uiState.value.rules, userPhrases)) {
            TriggerValidationError.VoicePhraseEmpty -> ctx.getString(R.string.automation_voice_phrase_empty)
            is TriggerValidationError.VoicePhraseBuiltin -> ctx.getString(R.string.automation_voice_phrase_taken, err.command)
            is TriggerValidationError.VoicePhraseTaken -> ctx.getString(R.string.automation_voice_phrase_taken, err.rule)
            null -> null
        }
    }

    fun saveRule() {
        val e = _uiState.value.editing
        if (e.name.isBlank() || e.triggers.isEmpty() || e.actions.isEmpty()) {
            _uiState.value = _uiState.value.copy(editorError =
                context.appLocalizedContext().getString(R.string.auto_msg_name_cond_action_required)
            )
            return
        }
        val actionError = validateActions(e.actions)
        if (actionError != null) {
            _uiState.value = _uiState.value.copy(editorError = actionError)
            return
        }
        val triggerError = validateTriggers(e.triggers, if (e.isNew) -1L else e.id)
        if (triggerError != null) {
            _uiState.value = _uiState.value.copy(editorError = triggerError)
            return
        }
        // Clear previous error on success path
        _uiState.value = _uiState.value.copy(editorError = null)

        if (e.isNew) {
            viewModelScope.launch { ruleDao.insert(e.applyTo(RuleEntity(name = "", triggers = "", actions = ""))) }
            closeEditor()
            return
        }
        val session = editorSession
        viewModelScope.launch {
            // The stored row keeps enabled, the counters and createdAt: the editor shows none of them.
            // A rule deleted meanwhile is not brought back: the editor stays open and says so.
            // Only this save's editor reacts: another one may be open by the time the DAO returns.
            val stored = ruleDao.getById(e.id)
            if (stored == null) {
                if (session == editorSession) _uiState.update { it.copy(editorRuleDeleted = true) }
            } else {
                ruleDao.update(e.applyTo(stored))
                if (session == editorSession) closeEditor()
            }
        }
    }

    // --- Duplicate / Delete ---

    fun duplicateRule(rule: RuleEntity) {
        viewModelScope.launch {
            ruleDao.insert(
                rule.copy(
                    id = 0,
                    name = "${rule.name} (${context.appLocalizedContext().getString(R.string.auto_rule_copy_suffix)})",
                    enabled = false,
                    lastTriggeredAt = null,
                    triggerCount = 0,
                    createdAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun requestDelete(ruleId: Long) {
        _uiState.update { it.copy(showDeleteConfirm = ruleId) }
    }

    fun cancelDelete() {
        _uiState.update { it.copy(showDeleteConfirm = null) }
    }

    fun confirmDelete() {
        val ruleId = _uiState.value.showDeleteConfirm ?: return
        viewModelScope.launch {
            ruleDao.getById(ruleId)?.let { ruleDao.delete(it) }
        }
        _uiState.update { it.copy(showDeleteConfirm = null) }
    }

    // --- Journal ---

    fun showJournal() { _uiState.update { it.copy(showJournal = true) } }
    fun hideJournal() { _uiState.update { it.copy(showJournal = false) } }

    // --- Test run ---

    /**
     * «Тестовый запуск»: runs every action of the rule being edited, in order, right now.
     * Each action goes through [ActionDispatcher.dispatch] against the live snapshot, so the
     * speed gates apply exactly as when the rule fires; the chime follows «Выполнять со звуком».
     * A speed-gated action ([isSpeedGatedAction]) also needs a running service and a polled
     * snapshot measured less than [TEST_RUN_MAX_SNAPSHOT_AGE_MS] ago ([isSampleFresh]): the
     * dispatcher lets a window or the sunroof open with no snapshot at all, and a stale speed 0
     * passes every gate. Without one the action is skipped and counted as not done; so is an
     * action the pre-check cannot classify. Closing the editor stops the run.
     * The trigger conditions, «Только на паркинге», cooldown, «Раз за поездку» and «Спрашивать
     * подтверждение» are skipped: the button press is the confirmation. Nothing is written:
     * no lastTriggeredAt / triggerCount update and no journal entry.
     */
    fun testRun() {
        val e = _uiState.value.editing
        if (_uiState.value.testRunning || e.actions.isEmpty()) return
        val actionError = validateActions(e.actions)
        if (actionError != null) {
            _uiState.update { it.copy(editorError = actionError) }
            return
        }
        _uiState.update { it.copy(testRunning = true, editorError = null) }
        testRunJob = viewModelScope.launch {
            val lc = context.appLocalizedContext()
            try {
                if (e.playSound) OverlayNotificationManager.playNotificationSound(context)
                var failed = 0
                var firstReason: String? = null
                for (action in e.actions) {
                    val reason = runTestAction(action)
                    if (reason != null) {
                        failed++
                        if (firstReason == null) firstReason = reason
                    }
                }
                val msg = if (failed == 0) {
                    lc.getString(R.string.automation_test_run_done)
                } else {
                    lc.getString(R.string.automation_test_run_failed, failed, e.actions.size, firstReason)
                }
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            } catch (c: CancellationException) {
                Toast.makeText(context, lc.getString(R.string.automation_test_run_stopped), Toast.LENGTH_SHORT).show()
                throw c
            } finally {
                _uiState.update { it.copy(testRunning = false) }
            }
        }
    }

    /**
     * One test-run step: null when it was done, else why not. A speed-gated action is sent with
     * the very snapshot whose age was checked.
     */
    private suspend fun runTestAction(action: ActionDef): String? {
        val lc = context.appLocalizedContext()
        val gated = runCatching { isSpeedGatedAction(action) }.getOrElse {
            Log.w("AutomationViewModel", "test run: ${action.kind} ${action.command} skipped, not recognised", it)
            return lc.getString(R.string.automation_test_run_unrecognized)
        }
        val snapshot = if (gated) {
            val sample = liveSample()?.takeIf { isSampleFresh(it, serviceRunning(), elapsedNow()) }
            if (sample == null) {
                Log.w("AutomationViewModel", "test run: ${action.kind} ${action.command} skipped, no fresh speed")
                return lc.getString(R.string.automation_test_run_no_speed)
            }
            sample.data
        } else {
            liveSnapshot()
        }
        val result = actionDispatcher.dispatch(action, snapshot)
        return if (result.success) null else result.reason ?: lc.getString(R.string.auto_msg_unavailable)
    }

    // --- Share ---

    /** «Поделиться» on a rule card: first the note on what the file keeps. */
    fun shareRule(rule: RuleEntity) = requestShare(SharedRule.fromEntity(rule))

    /** «Поделиться» in the editor: shares the draft as it is on screen, saved or not. */
    fun shareEditing() {
        val e = _uiState.value.editing
        if (e.name.isBlank() || e.triggers.isEmpty() || e.actions.isEmpty()) return
        requestShare(e.toShared())
    }

    private fun requestShare(rule: SharedRule) {
        if (_uiState.value.shareInProgress) return
        _uiState.update { it.copy(pendingShare = rule) }
    }

    /** «Продолжить» under the note: writes the file and opens the system share sheet. */
    fun confirmShare() {
        val rule = _uiState.value.pendingShare ?: return
        _uiState.update { it.copy(pendingShare = null) }
        writeShareFile(rule)
    }

    /** «Отмена» under the note: nothing is written. */
    fun cancelShare() {
        _uiState.update { it.copy(pendingShare = null) }
    }

    /**
     * Writes the share file, opens the system share sheet and names the file in a Toast. One
     * export at a time: the mutex serialises the writes, [AutomationUiState.shareInProgress]
     * greys out the buttons meanwhile.
     */
    private fun writeShareFile(rule: SharedRule) {
        if (_uiState.value.shareInProgress) return
        _uiState.update { it.copy(shareInProgress = true) }
        viewModelScope.launch {
            try {
                val file = shareMutex.withLock {
                    withContext(ioDispatcher) {
                        RuleShareFiles.writeTo(downloadsDir(), rule, BuildConfig.VERSION_NAME)
                    }
                }
                shareSheet(file)
                val msg = context.appLocalizedContext().getString(R.string.automation_share_saved_toast, file.name)
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            } catch (e: IOException) {
                showShareError(e)
            } catch (e: SecurityException) {
                showShareError(e)
            } finally {
                _uiState.update { it.copy(shareInProgress = false) }
            }
        }
    }

    private fun showShareError(e: Exception) {
        val msg = context.appLocalizedContext().getString(R.string.automation_share_error, e.message ?: "?")
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    }

    private fun startShareSheet(file: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(shareIntent, null).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: IllegalArgumentException) {
            showShareError(e)
        } catch (e: ActivityNotFoundException) {
            showShareError(e)
        }
    }

    // --- Import ---

    /** «Импорт»: lists the share files in Download, newest first. */
    fun openImport() {
        importJob?.cancel()
        importJob = viewModelScope.launch {
            val files = withContext(ioDispatcher) { RuleShareFiles.listRuleFiles(downloadsDir()) }
            _uiState.update { it.copy(importFiles = files, importError = null, importDraft = null) }
        }
    }

    /**
     * Closes both import steps; a listing or a read still running is dropped with them. Ignored
     * while the insert runs: the preview closes by itself once the rule is in.
     */
    fun closeImport() {
        if (_uiState.value.importDraft?.saving == true) return
        importJob?.cancel()
        _uiState.update { it.copy(importFiles = null, importError = null, importDraft = null) }
    }

    /**
     * A file tapped in step 1: read it (at most [RuleShareFiles.MAX_FILE_BYTES]), parse it, link
     * its places and build the preview lines, all off the main thread; then open the preview or
     * say why it cannot be imported. A newer tap or [closeImport] cancels a read still running,
     * so only the last pick can open a preview.
     */
    fun pickImportFile(file: File) {
        importJob?.cancel()
        val places = _uiState.value.places
        importJob = viewModelScope.launch {
            val lc = context.appLocalizedContext()
            val callLabel = lc.getString(R.string.auto_act_call)
            val parsed = try {
                withContext(ioDispatcher) {
                    RuleShareFiles.readLimited(file)?.let { RuleShare.parse(it, callLabel) } ?: RuleParseResult.Invalid
                }
            } catch (e: IOException) {
                Log.w("AutomationViewModel", "import: cannot read ${file.name}", e)
                RuleParseResult.Invalid
            }
            when (parsed) {
                is RuleParseResult.Ok -> {
                    val draft = withContext(ioDispatcher) { importDraft(parsed.rule, places, lc) }.copy(token = ++draftToken)
                    _uiState.update { it.copy(importFiles = null, importError = null, importDraft = draft) }
                }
                RuleParseResult.NewerVersion ->
                    _uiState.update { it.copy(importError = lc.getString(R.string.automation_import_newer_version)) }
                RuleParseResult.Invalid ->
                    _uiState.update { it.copy(importError = lc.getString(R.string.automation_import_invalid)) }
            }
        }
    }

    /** [rule] linked to [places] by name, with its preview lines. */
    private fun importDraft(rule: SharedRule, places: List<PlaceEntity>, lc: Context): RuleImportDraft {
        val linked = RuleShare.resolvePlaces(
            rule, places,
            lc.getString(R.string.automation_trigger_place_enter_prefix),
            lc.getString(R.string.automation_trigger_place_exit_prefix),
        )
        return RuleImportDraft(linked, RuleImportSummary.preview(linked, context, actionDispatcher::autoGoWillRun))
    }

    /** «Выбрать место» for the place trigger at [index] of the draft [token]. */
    fun resolveImportPlace(token: Long, index: Int, place: PlaceEntity) {
        val lc = context.appLocalizedContext()
        updateImportRule(token) { rule ->
            val t = rule.triggers.getOrNull(index) ?: return@updateImportRule rule
            val resolved = RuleShare.withPlace(
                t, place,
                lc.getString(R.string.automation_trigger_place_enter_prefix),
                lc.getString(R.string.automation_trigger_place_exit_prefix),
            )
            rule.copy(triggers = rule.triggers.toMutableList().apply { set(index, resolved) })
        }
    }

    /**
     * «Выбрать контакт» for the action at [index] of the draft [token]: a call gets the contact,
     * a `tel:` / `sms:` link gets the number back into its address.
     */
    fun resolveImportContact(token: Long, index: Int, phone: String, name: String, autoDial: Boolean) {
        updateImportRule(token) { rule ->
            val a = rule.actions.getOrNull(index) ?: return@updateImportRule rule
            val resolved = if (a.kind == "url") {
                val url = RuleShareUrl.withNumber(a.urlString(), phone)
                a.withUrl(url, a.urlMinimize()).copy(displayName = url)
            } else {
                a.withCall(phone, name, autoDial)
            }
            rule.copy(actions = rule.actions.toMutableList().apply { set(index, resolved) })
        }
    }

    // The rule is already checked against the file limits, so its preview is cheap to rebuild here.
    private fun updateImportRule(token: Long, transform: (SharedRule) -> SharedRule) {
        _uiState.update { s ->
            val draft = s.importDraft?.takeIf { it.token == token && !it.saving } ?: return@update s
            val rule = transform(draft.rule)
            s.copy(importDraft = draft.copy(rule = rule, preview = RuleImportSummary.preview(rule, context, actionDispatcher::autoGoWillRun), error = null))
        }
    }

    fun setImportEnableNow(enable: Boolean) {
        _uiState.update { s ->
            val draft = s.importDraft?.takeIf { !it.saving } ?: return@update s
            s.copy(importDraft = draft.copy(enableNow = enable && !draft.rule.hasUnresolved()))
        }
    }

    /**
     * «Добавить»: validates like the editor, renames on a clash, checks the [MAX_RULES] limit
     * and inserts the draft as it is on screen at the tap. Until the insert returns the draft is
     * [RuleImportDraft.saving]: edits and «Отмена» are ignored. The preview closes only once the
     * row is in; a refusal or a database error stays in the preview with the draft intact, so
     * «Добавить» can be pressed again.
     */
    fun confirmImport() {
        val draft = _uiState.value.importDraft ?: return
        if (draft.saving) return
        val rule = draft.rule
        // An unresolved number or address is missing by design: validate the rest of the rule around it.
        val unresolvedCalls = rule.unresolvedCallIndexes().toSet()
        val unresolvedUrls = rule.unresolvedUrlIndexes().toSet()
        val forValidation = rule.actions.mapIndexed { i, a ->
            when {
                i in unresolvedUrls -> a.withUrl(VALIDATION_URL, false)
                i !in unresolvedCalls -> a
                a.kind == "url" -> a.withUrl(RuleShareUrl.withNumber(a.urlString(), VALIDATION_PHONE), false)
                else -> a.withCall(VALIDATION_PHONE, "", false)
            }
        }
        val error = validateActions(forValidation) ?: validateTriggers(rule.triggers, -1L)
        if (error != null) {
            _uiState.update { it.copy(importDraft = draft.copy(error = error)) }
            return
        }
        val lc = context.appLocalizedContext()
        val name = RuleShare.uniqueName(
            rule.name,
            _uiState.value.rules.map { it.name },
            lc.getString(R.string.automation_import_name_suffix),
        )
        val entity = RuleShare.toEntity(rule, name, draft.enableNow)
        _uiState.update { it.copy(importDraft = draft.copy(saving = true, error = null)) }
        viewModelScope.launch {
            val failure = try {
                if (ruleDao.getCount() >= MAX_RULES) {
                    lc.getString(R.string.automation_rule_limit, MAX_RULES)
                } else {
                    ruleDao.insert(entity)
                    null
                }
            } catch (e: SQLiteException) {
                Log.w("AutomationViewModel", "import: insert failed", e)
                lc.getString(R.string.automation_import_save_failed, e.message ?: "?")
            }
            _uiState.update { s ->
                val current = s.importDraft?.takeIf { it.token == draft.token } ?: return@update s
                if (failure == null) s.copy(importDraft = null) else s.copy(importDraft = current.copy(saving = false, error = failure))
            }
        }
    }

    // --- Starter templates ---

    private suspend fun insertStarterTemplatesIfNeeded() {
        val prefs = context.getSharedPreferences("automation", Context.MODE_PRIVATE)
        if (prefs.getBoolean("templates_inserted", false)) return

        val lang = currentLang(context)
        fun tName(zh: String, en: String, ru: String): String = when (lang) { "zh" -> zh; "ru", "be" -> ru; else -> en }

        val templates = listOf(
            RuleEntity(
                name = tName("高速关窗", "Close windows on highway", "Закрыть окна на трассе"),
                enabled = false,
                triggerLogic = "AND",
                triggers = TriggerDef.listToJson(listOf(
                    TriggerDef("Speed", "车速", ">", "100",
                        tName("车速 > 100 km/h", "Speed > 100 km/h", "Скорость > 100 км/ч"))
                )),
                actions = ActionDef.listToJson(listOf(
                    ActionDef("车窗关闭",
                        tName("关闭所有车窗", "Close All Windows", "Закрыть все окна"))
                )),
                cooldownSeconds = 60
            ),
            RuleEntity(
                name = tName("冬季启动", "Winter start", "Зимний старт"),
                enabled = false,
                triggerLogic = "AND",
                triggers = TriggerDef.listToJson(listOf(
                    TriggerDef("ExtTemp", "车外温度", "<", "0",
                        tName("车外温度 < 0°C", "Outside Temp < 0°C", "Темп. снаружи < 0°C")),
                    TriggerDef("PowerState", "电源状态", "==", "2",
                        tName("电源状态 = DRIVE", "Power State = DRIVE", "Питание = DRIVE"))
                )),
                actions = ActionDef.listToJson(listOf(
                    ActionDef("主驾座椅加热2档",
                        tName("主驾座椅加热2档", "Driver Heat 2", "Подогрев водителя 2")),
                    ActionDef("后视镜加热",
                        tName("后视镜加热开", "Mirror Heat On", "Подогрев зеркал вкл"))
                )),
                cooldownSeconds = 600
            ),
            RuleEntity(
                name = tName("低电量ECO", "ECO at low SOC", "Эко при низком заряде"),
                enabled = false,
                triggerLogic = "AND",
                triggers = TriggerDef.listToJson(listOf(
                    TriggerDef("SOC", "电量百分比", "<", "15", "SOC < 15%")
                )),
                actions = ActionDef.listToJson(listOf(
                    ActionDef("ECO模式", tName("ECO 模式", "ECO Mode", "ECO режим"))
                )),
                cooldownSeconds = 300
            ),
            RuleEntity(
                name = tName("夏季制冷", "Summer cooling", "Летнее охлаждение"),
                enabled = false,
                triggerLogic = "AND",
                triggers = TriggerDef.listToJson(listOf(
                    TriggerDef("InsideTemp", "车内温度", ">", "30",
                        tName("车内温度 > 30°C", "Cabin Temp > 30°C", "Темп. салона > 30°C")),
                    TriggerDef("PowerState", "电源状态", "==", "2",
                        tName("电源状态 = DRIVE", "Power State = DRIVE", "Питание = DRIVE"))
                )),
                actions = ActionDef.listToJson(listOf(
                    ActionDef("主驾座椅通风1档",
                        tName("主驾座椅通风1档", "Driver Vent 1", "Вентиляция водителя 1")),
                    ActionDef("自动空调",
                        tName("自动空调", "Auto AC", "Авто AC"))
                )),
                cooldownSeconds = 600
            ),
            RuleEntity(
                name = tName("行驶开遮阳帘", "Sunshade while driving", "Шторка при движении"),
                enabled = false,
                triggerLogic = "AND",
                triggers = TriggerDef.listToJson(listOf(
                    TriggerDef("PowerState", "电源状态", "==", "2",
                        tName("电源状态 = DRIVE", "Power State = DRIVE", "Питание = DRIVE"))
                )),
                actions = ActionDef.listToJson(listOf(
                    ActionDef("遮阳帘打开",
                        tName("遮阳帘打开", "Sunshade Open", "Открыть шторку"))
                )),
                cooldownSeconds = 600
            ),
            RuleEntity(
                name = tName("充电时空调", "Climate while charging", "Климат при зарядке"),
                enabled = false,
                triggerLogic = "AND",
                triggers = TriggerDef.listToJson(listOf(
                    TriggerDef("ChargingStatus", "充电状态", "==", "2",
                        tName("充电状态 = 充电中", "Charging Status = Charging", "Зарядка = Начата")),
                    TriggerDef("ExtTemp", "车外温度", "<", "5",
                        tName("车外温度 < 5°C", "Outside Temp < 5°C", "Темп. снаружи < 5°C"))
                )),
                actions = ActionDef.listToJson(listOf(
                    ActionDef("自动空调",
                        tName("自动空调", "Auto AC", "Авто AC")),
                    ActionDef("主驾座椅加热1档",
                        tName("主驾座椅加热1档", "Driver Heat 1", "Подогрев водителя 1"))
                )),
                cooldownSeconds = 600
            )
        )

        templates.forEach { ruleDao.insert(it) }
        prefs.edit().putBoolean("templates_inserted", true).apply()
    }
}

/** Stand-in phone for validating an imported rule whose call contact is not picked yet. */
private const val VALIDATION_PHONE = "00000"

/** Stand-in address for validating an imported rule whose link has to be entered again. */
private const val VALIDATION_URL = "https://localhost"

/** Rules the user can have: the editor, the import and the voice agent stop at this many. */
internal const val MAX_RULES = 50

/** How old the telemetry snapshot may be for a test run to send a speed-gated action. */
internal const val TEST_RUN_MAX_SNAPSHOT_AGE_MS = 10_000L

/** Toggle targets that can resolve to a speed-gated command: sunroof open, frunk open, unlock. */
private val SPEED_GATED_TOGGLES = setOf(
    ActionDispatcher.TOGGLE_SUNROOF, ActionDispatcher.TOGGLE_FRONT_TRUNK, ActionDispatcher.TOGGLE_LOCKS,
)

/**
 * True when [action] can open a window, the sunroof or the frunk, or unlock the doors: the
 * commands ActionDispatcher gates by speed, detected with its own predicates. A toggle counts
 * by target, since which way it flips is only known against the live state.
 */
internal fun isSpeedGatedAction(action: ActionDef): Boolean = when (action.kind) {
    "param" -> ActionDispatcher.isWindowOpenCommand(action.command) ||
        ActionDispatcher.isSunroofOpenCommand(action.command) ||
        ActionDispatcher.isFrontTrunkOpenCommand(action.command) ||
        ActionDispatcher.isDoorUnlockCommand(action.command)
    "toggle" -> action.payload in SPEED_GATED_TOGGLES
    else -> false
}

/**
 * The service polls, and [sample] was measured less than [TEST_RUN_MAX_SNAPSHOT_AGE_MS] before
 * [nowElapsedMs], both on the monotonic clock: a wall-clock change cannot make it fresh, and a
 * replay to a restarted service keeps its original measurement time.
 */
internal fun isSampleFresh(sample: TimedSnapshot, serviceRunning: Boolean, nowElapsedMs: Long): Boolean =
    serviceRunning && sample.measuredAtElapsedMs > 0 &&
        nowElapsedMs - sample.measuredAtElapsedMs in 0 until TEST_RUN_MAX_SNAPSHOT_AGE_MS

// --- Action kind helpers (v2.3.0) ---

fun newNotificationAction(context: Context): ActionDef = ActionDef(
    command = "",
    displayName = context.getString(R.string.auto_act_notification),
    kind = "notification",
    payload = """{"title":"","text":""}"""
)

fun ActionDef.notificationTitle(): String = try {
    org.json.JSONObject(payload ?: "{}").optString("title")
} catch (e: Exception) { "" }

fun ActionDef.notificationText(): String = try {
    org.json.JSONObject(payload ?: "{}").optString("text")
} catch (e: Exception) { "" }

fun ActionDef.withNotification(title: String, text: String): ActionDef = copy(
    payload = org.json.JSONObject().apply {
        put("title", title)
        put("text", text)
    }.toString()
)

// --- App launch helpers (v2.3.0) ---

fun newAppLaunchAction(context: Context): ActionDef = ActionDef(
    command = "",
    displayName = context.getString(R.string.auto_act_launch_app),
    kind = "app_launch",
    payload = """{"packageName":"","appLabel":""}"""
)

fun ActionDef.appLaunchPackageName(): String = try {
    org.json.JSONObject(payload ?: "{}").optString("packageName")
} catch (e: Exception) { "" }

fun ActionDef.appLaunchLabel(): String = try {
    org.json.JSONObject(payload ?: "{}").optString("appLabel")
} catch (e: Exception) { "" }

fun ActionDef.appLaunchMinimize(): Boolean = try {
    org.json.JSONObject(payload ?: "{}").optBoolean("minimize", false)
} catch (e: Exception) { false }

fun ActionDef.withAppLaunch(packageName: String, appLabel: String, minimize: Boolean): ActionDef = copy(
    payload = org.json.JSONObject().apply {
        put("packageName", packageName)
        put("appLabel", appLabel)
        put("minimize", minimize)
    }.toString()
)

// --- Call helpers (v2.3.0) ---

fun newCallAction(context: Context): ActionDef = ActionDef(
    command = "",
    displayName = context.getString(R.string.auto_act_call),
    kind = "call",
    payload = """{"phone":""}"""
)

fun ActionDef.callPhone(): String = try {
    org.json.JSONObject(payload ?: "{}").optString("phone")
} catch (e: Exception) { "" }

fun ActionDef.callName(): String = try {
    org.json.JSONObject(payload ?: "{}").optString("name")
} catch (e: Exception) { "" }

fun ActionDef.callAutoDial(): Boolean = try {
    org.json.JSONObject(payload ?: "{}").optBoolean("autoDial", false)
} catch (e: Exception) { false }

fun ActionDef.withCall(phone: String, name: String, autoDial: Boolean): ActionDef = copy(
    payload = org.json.JSONObject().apply {
        put("phone", phone)
        put("name", name)
        put("autoDial", autoDial)
    }.toString()
)

// --- Navigate helpers (v2.3.0) ---

fun newNavigateAction(context: Context): ActionDef = ActionDef(
    command = "",
    displayName = context.getString(R.string.auto_act_navigate),
    kind = "navigate",
    payload = """{"lat":0,"lon":0,"name":""}"""
)

fun ActionDef.navigateLat(): Double? = try {
    val d = org.json.JSONObject(payload ?: "{}").optDouble("lat", Double.NaN)
    if (d.isNaN()) null else d
} catch (e: Exception) { null }

fun ActionDef.navigateLon(): Double? = try {
    val d = org.json.JSONObject(payload ?: "{}").optDouble("lon", Double.NaN)
    if (d.isNaN()) null else d
} catch (e: Exception) { null }

fun ActionDef.navigateName(): String = try {
    org.json.JSONObject(payload ?: "{}").optString("name")
} catch (e: Exception) { "" }

fun ActionDef.withNavigate(lat: Double, lon: Double, name: String): ActionDef = copy(
    payload = org.json.JSONObject().apply {
        put("lat", lat)
        put("lon", lon)
        put("name", name)
    }.toString()
)

// --- URL helpers (v2.3.0) ---

fun newUrlAction(context: Context): ActionDef = ActionDef(
    command = "",
    displayName = context.getString(R.string.auto_act_open_url),
    kind = "url",
    payload = """{"url":""}"""
)

fun ActionDef.urlString(): String = try {
    org.json.JSONObject(payload ?: "{}").optString("url")
} catch (e: Exception) { "" }

fun ActionDef.urlMinimize(): Boolean = try {
    org.json.JSONObject(payload ?: "{}").optBoolean("minimize", false)
} catch (e: Exception) { false }

/**
 * The url action with a new address and minimize flag. An unchanged address keeps a
 * `paramsStripped` marker (an import may have carried one); a changed address drops it, since a
 * freshly typed one was never stripped by this build.
 */
fun ActionDef.withUrl(url: String, minimize: Boolean): ActionDef = copy(
    payload = org.json.JSONObject().apply {
        put("url", url)
        put("minimize", minimize)
        if (url == urlString()) {
            val stripped = try { org.json.JSONObject(payload ?: "{}").optBoolean(RuleShare.PARAMS_STRIPPED, false) } catch (e: Exception) { false }
            if (stripped) put(RuleShare.PARAMS_STRIPPED, true)
        }
    }.toString()
)

// --- Yandex Music helpers (v2.3.0) ---

fun newYandexMusicAction(context: Context): ActionDef = ActionDef(
    command = "",
    displayName = context.getString(R.string.auto_act_yandex_music),
    kind = "yandex_music",
    payload = """{"mode":"mybeat","minimize":true}"""
)

fun ActionDef.yandexMusicMode(): String = try {
    org.json.JSONObject(payload ?: "{}").optString("mode")
} catch (e: Exception) { "" }

fun ActionDef.yandexMusicMinimize(): Boolean = try {
    org.json.JSONObject(payload ?: "{}").optBoolean("minimize", true)
} catch (e: Exception) { true }

fun ActionDef.withYandexMusic(mode: String, minimize: Boolean): ActionDef = copy(
    payload = org.json.JSONObject().apply {
        put("mode", mode)
        put("minimize", minimize)
    }.toString()
)

// --- Sentry helpers ---

fun newSentryAction(context: Context): ActionDef = ActionDef(
    command = "sentry",
    displayName = context.getString(R.string.automation_action_sentry),
    kind = "sentry",
    payload = "1"
)

// --- Hotspot helpers ---

fun newHotspotAction(context: Context): ActionDef = ActionDef(
    command = "hotspot",
    displayName = context.getString(R.string.automation_action_hotspot),
    kind = "hotspot",
    payload = "1"
)

// --- Cluster projection helpers ---

fun newClusterAction(context: Context): ActionDef = ActionDef(
    command = "cluster_projection",
    displayName = context.getString(R.string.automation_action_cluster_projection),
    kind = "cluster_projection",
    payload = "1"
)

/**
 * A kind that has its own control row AND a toggle target: sentry and the cluster projection.
 * Their row shows three states, and «Переключить» stores a `toggle` action on [toggleTarget]
 * instead of the row's own [kind], so the same target has one storage shape everywhere.
 */
data class ToggleRowSpec(val kind: String, val command: String, val toggleTarget: String)

val SENTRY_ROW = ToggleRowSpec("sentry", "sentry", ActionDispatcher.TOGGLE_SENTRY)
val CLUSTER_ROW = ToggleRowSpec("cluster_projection", "cluster_projection", ActionDispatcher.TOGGLE_CLUSTER)

/**
 * The three-state row that edits [action], or null when it belongs to another control. A stored
 * toggle on one of these targets comes back to its own row, so «Вкл» and «Выкл» stay reachable.
 */
fun toggleRowSpecFor(action: ActionDef): ToggleRowSpec? = listOf(SENTRY_ROW, CLUSTER_ROW).firstOrNull {
    action.kind == it.kind || (action.kind == "toggle" && action.payload == it.toggleTarget)
}

/** What the «Вкл» / «Выкл» chips store: the row's own kind again, even after a «Переключить». */
fun rowStateAction(spec: ToggleRowSpec, payload: String, displayName: String): ActionDef =
    ActionDef(command = spec.command, displayName = displayName, kind = spec.kind, payload = payload)

/**
 * "toggle": one action that flips a panel (rear/front trunk, sunroof), the door
 * locks or the cluster projection from whatever state the car reports. Default
 * target is the rear trunk — the case the action was asked for.
 */
fun newToggleAction(context: Context): ActionDef = ActionDef(
    command = "",
    displayName = toggleDisplayName(context, ActionDispatcher.TOGGLE_TRUNK),
    kind = "toggle",
    payload = ActionDispatcher.TOGGLE_TRUNK
)

/** "Переключить: <цель>" — the saved display name for a toggle action. */
fun toggleDisplayName(context: Context, target: String): String {
    val lc = context.appLocalizedContext()
    val nameRes = ActionDispatcher.toggleTargetNameRes(target) ?: return lc.getString(R.string.automation_action_toggle)
    return lc.getString(R.string.automation_action_toggle_display_name, lc.getString(nameRes))
}

// --- Speak helpers (v3.6) ---

fun newSpeakAction(context: Context): ActionDef = ActionDef(
    command = "",
    displayName = context.getString(R.string.auto_act_speak),
    kind = "speak",
    payload = """{"text":""}"""
)

fun ActionDef.speakText(): String = try {
    org.json.JSONObject(payload ?: "{}").optString("text")
} catch (e: Exception) { "" }

fun ActionDef.withSpeakText(text: String): ActionDef = copy(
    payload = org.json.JSONObject().apply { put("text", text) }.toString()
)

// --- Agent query helpers (v3.6) ---

fun newAgentQueryAction(context: Context): ActionDef = ActionDef(
    command = "",
    displayName = context.getString(R.string.auto_act_agent_query),
    kind = "agent_query",
    payload = """{"prompt":""}"""
)

fun ActionDef.agentPrompt(): String = try {
    org.json.JSONObject(payload ?: "{}").optString("prompt")
} catch (e: Exception) { "" }

fun ActionDef.withAgentPrompt(prompt: String): ActionDef = copy(
    payload = org.json.JSONObject().apply { put("prompt", prompt) }.toString()
)

// --- Split screen helpers ---

fun newSplitScreenAction(context: Context): ActionDef = ActionDef(
    command = "",
    displayName = context.getString(R.string.auto_act_split_screen),
    kind = "split_screen",
    payload = """{"narrow":"","wide":"","narrowLabel":"","wideLabel":"","side":"right"}"""
)

/** "Close split screen" — no payload: the action always targets the running session. */
fun newSplitScreenCloseAction(context: Context): ActionDef = ActionDef(
    command = "",
    displayName = context.getString(R.string.auto_act_split_screen_close),
    kind = "split_screen_close",
    payload = null
)

/** "Toggle split screen" — no payload: exits a running session, else restores the last pair. */
fun newSplitScreenToggleAction(context: Context): ActionDef = ActionDef(
    command = "",
    displayName = context.getString(R.string.auto_act_split_screen_toggle),
    kind = "split_screen_toggle",
    payload = null
)

fun ActionDef.splitNarrowPkg(): String = try {
    org.json.JSONObject(payload ?: "{}").optString("narrow")
} catch (e: Exception) { "" }

fun ActionDef.splitWidePkg(): String = try {
    org.json.JSONObject(payload ?: "{}").optString("wide")
} catch (e: Exception) { "" }

fun ActionDef.splitNarrowLabel(): String = try {
    org.json.JSONObject(payload ?: "{}").optString("narrowLabel")
} catch (e: Exception) { "" }

fun ActionDef.splitWideLabel(): String = try {
    org.json.JSONObject(payload ?: "{}").optString("wideLabel")
} catch (e: Exception) { "" }

/** Returns the narrow-pane side: "left" or "right". Defaults to "right" when missing. */
fun ActionDef.splitSide(): String = try {
    org.json.JSONObject(payload ?: "{}").optString("side", "right").let {
        if (it in listOf("left", "right")) it else "right"
    }
} catch (e: Exception) { "right" }

fun ActionDef.withSplitScreen(
    narrowPkg: String,
    narrowLabel: String,
    widePkg: String,
    wideLabel: String,
    side: String,
): ActionDef {
    val displayName = if (narrowLabel.isNotBlank() && wideLabel.isNotBlank()) {
        "$narrowLabel / $wideLabel"
    } else {
        // Caller will resolve the localized default from context; use stable English here.
        "Split Screen"
    }
    return copy(
        displayName = displayName,
        payload = org.json.JSONObject().apply {
            put("narrow", narrowPkg)
            put("wide", widePkg)
            put("narrowLabel", narrowLabel)
            put("wideLabel", wideLabel)
            put("side", side)
        }.toString()
    )
}

/**
 * Pure list reorder used by the automation editor's up/down arrows: returns a new
 * list with the item at [index] swapped with its neighbour. No-op (returns the same
 * order) when [index] is at the boundary or out of range.
 */
internal fun <T> List<T>.moveItem(index: Int, up: Boolean): List<T> {
    val target = if (up) index - 1 else index + 1
    if (index !in indices || target !in indices) return this
    return toMutableList().apply {
        val tmp = this[index]; this[index] = this[target]; this[target] = tmp
    }
}

/**
 * Builds the "button press N" trigger (widget button N). displayName carries a
 * stable internal label used only for logs/snapshot; the UI renders the
 * localized "Кнопка N" label via stringResource. value holds the button id as a
 * string, matching how every other TriggerDef stores its value. The remaining
 * fields are neutral placeholders so the trigger round-trips through
 * TriggerDef.toJson/fromJson with no schema change.
 */
fun newButtonPressTrigger(buttonId: Int): TriggerDef = TriggerDef(
    param = "button",
    chineseName = "",
    operator = "==",
    value = buttonId.toString(),
    displayName = "Кнопка $buttonId",
    kind = "button_press",
)

/**
 * Builds the "steering-wheel key" trigger. Shaped like [newButtonPressTrigger]: value holds the
 * Android keycode as a string, displayName is the internal log label (the UI renders the localized
 * button name). keyCode 0 means "not assigned yet" — the engine's keycode cache skips it, so a
 * freshly added trigger claims no key until the user learns one.
 */
fun newSteeringKeyTrigger(keyCode: Int): TriggerDef = TriggerDef(
    param = AutomationEngine.TRIGGER_PARAM_STEERING_KEY,
    chineseName = "",
    operator = "==",
    value = keyCode.toString(),
    displayName = "Клавиша $keyCode",
    kind = AutomationEngine.TRIGGER_KIND_STEERING_KEY,
)
