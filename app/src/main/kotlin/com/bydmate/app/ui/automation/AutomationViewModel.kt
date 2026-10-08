package com.bydmate.app.ui.automation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.SharedPreferences
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
import com.bydmate.app.util.AppStrings
import com.bydmate.app.util.appLocalizedContext
import com.bydmate.app.data.automation.ActionValidationError
import com.bydmate.app.data.automation.AutomationEngine
import com.bydmate.app.data.automation.PowerStateRuleMigration
import com.bydmate.app.data.automation.RuleDraftValidator
import com.bydmate.app.data.automation.RuleInserts
import com.bydmate.app.data.automation.RuleJournal
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
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.loop.TimedSnapshot
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.telegram.ReportField
import com.bydmate.app.data.telegram.TELEGRAM_REPORT_KIND
import com.bydmate.app.data.telegram.withReportRuleName
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
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

/** A trigger value as the rule card shows it: an enum code by its localized name, anything else as stored. */
fun triggerValueLabel(trigger: TriggerDef, context: Context): String =
    TRIGGER_PARAMS.firstOrNull { it.param == trigger.param }?.localizedEnumLabel(trigger.value, context) ?: trigger.value

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
)

val OPERATORS = listOf(">", "<", ">=", "<=", "==", "!=")

enum class RuleFilter { ALL, ENABLED, DISABLED }

/** How the rule list is drawn: one compact row per rule, or a grid of cards. */
enum class RuleViewMode {
    LIST, GRID;

    companion object {
        /** Key in the «automation» prefs; the choice survives app restarts. */
        internal const val KEY = "rule_view_mode"

        fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences(AutomationEngine.PREFS_NAME, Context.MODE_PRIVATE)

        /** A missing, unknown or wrongly typed value falls back to [LIST]. */
        fun read(prefs: SharedPreferences): RuleViewMode {
            val stored = try {
                prefs.getString(KEY, null)
            } catch (_: ClassCastException) {
                null
            }
            return entries.firstOrNull { it.name == stored } ?: LIST
        }
    }
}

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
    /** A save is in flight: the draft is frozen, [AutomationViewModel.updateEditing] ignores edits. */
    val saving: Boolean = false,
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
    val viewMode: RuleViewMode = RuleViewMode.LIST,
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
    /** The Telegram bot is connected in Settings: the report dialog warns when it is not. */
    val tgBotConnected: Boolean = false,
    /** The newest journal entry of each rule, by rule id: the status line on its card. */
    val lastLogs: Map<Long, RuleLogEntity> = emptyMap(),
    /** The journal shows only this rule's entries: opened from the rule's status line. */
    val journalRuleId: Long? = null,
    val ruleLogs: List<RuleLogEntity> = emptyList(),
    /** A short note at the bottom of the tab (rule limit, test run refused); the screen hides it. */
    val message: String? = null,
)

@HiltViewModel
@Suppress("LargeClass") // the whole Automation tab: rules, editor, import/share, journal
class AutomationViewModel @Inject @Suppress("LongParameterList") constructor( // Hilt-injected dependencies
    private val ruleDao: RuleDao,
    private val ruleLogDao: RuleLogDao,
    private val placeRepository: PlaceRepository,
    private val vehicleApi: VehicleApi,
    private val actionDispatcher: ActionDispatcher,
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(AutomationUiState(viewMode = RuleViewMode.read(RuleViewMode.prefs(context))))
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
    private var ruleJournalJob: Job? = null
    private var importJob: Job? = null
    private var draftToken = 0L

    // Bumped on every editor open and close: a save that returns late only touches its own editor.
    private var editorSession = 0L
    private val shareMutex = Mutex()

    /** Rule ids in the driver's own order, as last saved; empty = newest first. */
    private var ruleOrder: List<Long> = emptyList()

    init {
        viewModelScope.launch { insertStarterTemplatesIfNeeded() }
        viewModelScope.launch {
            ruleOrder = RuleOrder.parse(settingsRepository.getAutomationRuleOrder())
            ruleDao.getAll().collect { rules ->
                _uiState.update { it.copy(rules = RuleOrder.apply(ruleOrder, rules)) }
            }
        }
        viewModelScope.launch {
            combine(
                settingsRepository.observeString(SettingsRepository.KEY_TG_BACKUP_TOKEN),
                settingsRepository.observeString(SettingsRepository.KEY_TG_BACKUP_CHAT_ID),
            ) { token, chat -> !token.isNullOrBlank() && chat?.toLongOrNull() != null }
                .collect { connected -> _uiState.update { it.copy(tgBotConnected = connected) } }
        }
        viewModelScope.launch {
            ruleLogDao.getRecent(100).collect { logs ->
                _uiState.update { it.copy(logs = logs) }
            }
        }
        viewModelScope.launch {
            ruleLogDao.getLastPerRule().collect { last ->
                _uiState.update { it.copy(lastLogs = last.associateBy { l -> l.ruleId }) }
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

    fun setViewMode(mode: RuleViewMode) {
        _uiState.update { it.copy(viewMode = mode) }
        RuleViewMode.prefs(context).edit().putString(RuleViewMode.KEY, mode.name).apply()
    }

    /**
     * A rule dropped into [target]'s place (#249): the new order shows at once and is saved once,
     * as the whole list of ids, so the rules a filter hides keep their places too.
     */
    fun moveRule(moved: Long, target: Long) {
        val rules = _uiState.value.rules
        val ids = rules.map { it.id }
        val next = RuleOrder.move(ids, moved, target)
        if (next == ids) return
        ruleOrder = next
        _uiState.update { it.copy(rules = RuleOrder.apply(next, it.rules)) }
        Log.i("AutomationViewModel", "rule order: moved id=$moved from=${ids.indexOf(moved)} to=${next.indexOf(moved)}")
        viewModelScope.launch { settingsRepository.setAutomationRuleOrder(RuleOrder.serialize(next)) }
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
                          result.exceptionOrNull()?.let { actionDispatcher.vehicleFailureReason(it) }
                              ?: lc.getString(R.string.auto_msg_unavailable),
                      )
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // --- Editor ---

    fun openNewRule() {
        if (_uiState.value.rules.size >= MAX_RULES) {
            showLimitMessage()
            return
        }
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

    /** The editor says the rule limit is reached; [what] names the save path in the log. */
    private fun refuseAtRuleLimit(what: String) {
        Log.i("AutomationViewModel", "$what not saved: limit of $MAX_RULES rules reached")
        _uiState.update {
            it.copy(editorError = context.appLocalizedContext().getString(R.string.automation_rule_limit, MAX_RULES))
        }
    }

    /**
     * «Правило уже удалено» → «Сохранить как новое»: the draft becomes a new rule, switch as it
     * was. The editor and the draft stay open until the insert returns: it closes only on
     * success, bound to this save's editor session, so a DB failure keeps the draft on screen
     * with a message instead of losing it. The draft is frozen ([EditingRule.saving]) for the
     * same stretch: an edit made while the insert awaits the DB would otherwise be neither saved
     * (a snapshot taken before the edit is what gets inserted) nor kept (the editor closes on
     * success and drops it).
     */
    @Suppress("TooGenericExceptionCaught")
    fun saveDeletedRuleAsNew() {
        val e = _uiState.value.editing
        if (!_uiState.value.editorRuleDeleted || e.saving) return
        val session = editorSession
        // Dismisses the confirmation dialog and freezes the draft right away, so a second tap or
        // an edit before the insert returns is a no-op.
        _uiState.update { it.copy(editorRuleDeleted = false, editing = it.editing.copy(saving = true)) }
        viewModelScope.launch {
            try {
                val id = RuleInserts.insertWithinLimit(
                    ruleDao, e.applyTo(RuleEntity(name = "", triggers = "", actions = "", enabled = e.enabled)), MAX_RULES,
                )
                if (session == editorSession) {
                    if (id != null) closeEditor() else refuseAtRuleLimit("saveDeletedRuleAsNew")
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                Log.w("AutomationViewModel", "saveDeletedRuleAsNew: insert failed", e)
                val msg = context.appLocalizedContext().getString(R.string.automation_import_save_failed, e.message ?: "?")
                if (session == editorSession) _uiState.update { it.copy(editorError = msg) }
            } finally {
                // Unfreezes the draft on any failure; a successful close already dropped this session.
                if (session == editorSession) _uiState.update { it.copy(editing = it.editing.copy(saving = false)) }
            }
        }
    }

    /** «Правило уже удалено» dismissed by Back or outside: the editor stays with the draft. */
    fun dismissRuleDeleted() {
        _uiState.update { it.copy(editorRuleDeleted = false) }
    }

    /** A no-op while the draft is [EditingRule.saving]: nothing edits a rule mid-insert. */
    fun updateEditing(transform: EditingRule.() -> EditingRule) {
        _uiState.update { if (it.editing.saving) it else it.copy(editing = it.editing.transform()) }
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
            is ActionValidationError.MediaKeyInvalid -> ctx.getString(R.string.auto_msg_media_key_invalid, err.index)
            is ActionValidationError.SpeakTextEmpty -> ctx.getString(R.string.auto_msg_speak_text_empty, err.index)
            is ActionValidationError.AgentQueryPromptEmpty -> ctx.getString(R.string.auto_msg_agent_query_prompt_empty, err.index)
            is ActionValidationError.SplitScreenNarrowEmpty -> ctx.getString(R.string.auto_msg_split_narrow_empty, err.index)
            is ActionValidationError.SplitScreenWideEmpty -> ctx.getString(R.string.auto_msg_split_wide_empty, err.index)
            is ActionValidationError.SplitScreenSamePackage -> ctx.getString(R.string.auto_msg_split_same_package, err.index)
            is ActionValidationError.SplitScreenInvalidSide -> ctx.getString(R.string.auto_msg_split_invalid_side, err.index)
            is ActionValidationError.TelegramReportEmpty -> ctx.getString(R.string.auto_msg_tg_report_empty, err.index)
            null -> null
        }
    }

    private fun validateTriggers(
        triggers: List<TriggerDef>,
        editingId: Long,
        triggerLogic: String,
        keyPickedLater: Boolean = false,
    ): String? {
        val ctx = context.appLocalizedContext()
        val userPhrases = runCatching { userCommandPhrases() }.getOrDefault(emptyMap())
        val err = RuleDraftValidator.validateTriggers(triggers, editingId, _uiState.value.rules, userPhrases, triggerLogic)
            ?.takeUnless { keyPickedLater && it == TriggerValidationError.SteeringKeyUnassigned }
        if (err != null) Log.i("AutomationViewModel", "rule not saved: $err")
        return when (err) {
            TriggerValidationError.SteeringKeyUnassigned -> ctx.getString(R.string.automation_trigger_steering_key_unassigned)
            is TriggerValidationError.ValueNotNumber ->
                ctx.getString(R.string.auto_ui_miss_number, triggers.indexOfFirst { it.kind == "param" && it.param == err.param } + 1)
            TriggerValidationError.OneShotInvalid -> ctx.getString(R.string.auto_ui_err_one_shot_invalid)
            TriggerValidationError.OneShotWithOr -> ctx.getString(R.string.auto_ui_err_one_shot_or)
            TriggerValidationError.OneShotWithEvent -> ctx.getString(R.string.auto_ui_err_one_shot_event)
            TriggerValidationError.OneShotTwice -> ctx.getString(R.string.auto_ui_err_one_shot_twice)
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
        // Every save path, «Сохранить изменения?» included: a list condition keeps no hidden operator.
        val noOperator = e.triggers.indexOfFirst { hasListOperatorMissing(it) }
        if (noOperator >= 0) {
            Log.i("AutomationViewModel", "rule not saved: list condition ${noOperator + 1} has operator ${e.triggers[noOperator].operator}")
            _uiState.value = _uiState.value.copy(
                editorError = context.appLocalizedContext().getString(R.string.auto_ui_miss_operator, noOperator + 1)
            )
            return
        }
        val triggerError = validateTriggers(e.triggers, if (e.isNew) -1L else e.id, e.triggerLogic)
        if (triggerError != null) {
            _uiState.value = _uiState.value.copy(editorError = triggerError)
            return
        }
        // Clear previous error on success path
        _uiState.value = _uiState.value.copy(editorError = null)
        // Names follow the catalog as saved: a value or param changed after the pick keeps no
        // stale text in the card, the confirm window or the journal.
        val named = e.copy(
            triggers = e.triggers.map { withCatalogName(it, context) },
            actions = e.actions.map { withCatalogName(it, context) },
        )

        if (e.isNew) {
            insertNewRule(named)
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
                ruleDao.update(named.applyTo(stored))
                if (session == editorSession) closeEditor()
            }
        }
    }

    /**
     * The editor closes once the insert got in: a save racing another one at the limit stays
     * open with the refusal instead of losing the draft, frozen while the insert runs.
     */
    private fun insertNewRule(named: EditingRule) {
        if (named.saving) return
        if (_uiState.value.rules.size >= MAX_RULES) {
            refuseAtRuleLimit("new rule")
            return
        }
        val session = editorSession
        _uiState.update { it.copy(editing = it.editing.copy(saving = true)) }
        viewModelScope.launch {
            try {
                val id = RuleInserts.insertWithinLimit(
                    ruleDao, named.applyTo(RuleEntity(name = "", triggers = "", actions = "")), MAX_RULES,
                )
                if (session == editorSession) {
                    if (id != null) closeEditor() else refuseAtRuleLimit("new rule")
                }
            } finally {
                if (session == editorSession) _uiState.update { it.copy(editing = it.editing.copy(saving = false)) }
            }
        }
    }

    // --- Duplicate / Delete ---

    fun duplicateRule(rule: RuleEntity) {
        viewModelScope.launch {
            val copy = rule.copy(
                id = 0,
                name = "${rule.name} (${context.appLocalizedContext().getString(R.string.auto_rule_copy_suffix)})",
                enabled = false,
                lastTriggeredAt = null,
                triggerCount = 0,
                createdAt = System.currentTimeMillis()
            )
            if (RuleInserts.insertWithinLimit(ruleDao, copy, MAX_RULES) == null) {
                Log.i("AutomationViewModel", "copy of rule ${rule.id} refused: limit of $MAX_RULES rules reached")
                showLimitMessage()
            }
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

    fun showJournal() {
        ruleJournalJob?.cancel()
        _uiState.update { it.copy(showJournal = true, journalRuleId = null, ruleLogs = emptyList()) }
    }

    /** The status line on a card: the journal of this rule only, all its entries. */
    fun showRuleJournal(ruleId: Long) {
        ruleJournalJob?.cancel()
        _uiState.update { it.copy(showJournal = true, journalRuleId = ruleId, ruleLogs = emptyList()) }
        ruleJournalJob = viewModelScope.launch {
            ruleLogDao.getByRule(ruleId).collect { logs -> _uiState.update { it.copy(ruleLogs = logs) } }
        }
    }

    fun hideJournal() {
        ruleJournalJob?.cancel()
        _uiState.update { it.copy(showJournal = false, journalRuleId = null, ruleLogs = emptyList()) }
    }

    // --- Note at the bottom of the tab ---

    fun dismissMessage() { _uiState.update { it.copy(message = null) } }

    private fun showLimitMessage() {
        _uiState.update { it.copy(message = context.appLocalizedContext().getString(R.string.auto_ui_limit, MAX_RULES)) }
    }

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
     * «Только на парковке» holds as when the rule fires: off P, or with no fresh P, the run is refused with a note,
     * and a saved rule gets the refusal in its journal. The trigger conditions, cooldown, «Раз за
     * поездку» and «Спрашивать подтверждение» are skipped: the button press is the confirmation.
     * Otherwise a run writes no lastTriggeredAt / triggerCount update and no journal entry.
     */
    fun testRun() {
        val e = _uiState.value.editing
        if (_uiState.value.testRunning || e.actions.isEmpty()) return
        val actionError = validateActions(e.actions)
        if (actionError != null) {
            _uiState.update { it.copy(editorError = actionError) }
            return
        }
        val gear = liveSnapshot()?.gear
        if (e.requirePark && !inFreshPark(gear)) {
            _uiState.update { it.copy(message = context.appLocalizedContext().getString(R.string.auto_ui_test_park)) }
            if (!e.isNew) {
                viewModelScope.launch {
                    val rule = ruleDao.getById(e.id) ?: return@launch
                    RuleJournal(ruleLogDao, AppStrings(context))
                        .parkRequired(rule, JSONObject().put(TEST_RUN_KEY, true).toString(), gear)
                }
            }
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
     * P from a polled snapshot younger than [TEST_RUN_MAX_SNAPSHOT_AGE_MS] of a running service,
     * and still P in the live snapshot [gear]: the live snapshot outlives the service and never
     * says how old it is.
     */
    private fun inFreshPark(gear: Int?): Boolean {
        val freshGear = liveSample()?.takeIf { isSampleFresh(it, serviceRunning(), elapsedNow()) }?.data?.gear
        if (freshGear == 1 && gear == 1) return true
        Log.i("AutomationViewModel", "test run refused: park only, gear=$gear fresh=$freshGear")
        return false
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
        val result = actionDispatcher.dispatch(action.withReportRuleName(_uiState.value.editing.name), snapshot)
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
                    // A file from before the PowerState condition was removed: converted like a saved rule.
                    val triggers = PowerStateRuleMigration.convert(
                        parsed.rule.triggerLogic, parsed.rule.triggers, PowerStateRuleMigration.labels(context),
                        driveIsGear = PowerStateRuleMigration.isSunshadeTemplate(parsed.rule.name),
                    )
                    if (triggers == null) {
                        _uiState.update { it.copy(importError = lc.getString(R.string.automation_import_power_off_only)) }
                        return@launch
                    }
                    val rule = parsed.rule.copy(triggers = triggers)
                    val draft = withContext(ioDispatcher) { importDraft(rule, places, lc) }.copy(token = ++draftToken)
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
        val error = validateActions(forValidation) ?: validateTriggers(rule.triggers, -1L, rule.triggerLogic, keyPickedLater = true)
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
                if (RuleInserts.insertWithinLimit(ruleDao, entity, MAX_RULES) == null) {
                    Log.i("AutomationViewModel", "import refused: limit of $MAX_RULES rules reached")
                    lc.getString(R.string.automation_rule_limit, MAX_RULES)
                } else {
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
        if (!prefs.getBoolean("templates_inserted", false)) {
            if (RuleInserts.insertAllWithinLimit(ruleDao, starterTemplates(currentLang(context)), MAX_RULES) == null) {
                Log.i("AutomationViewModel", "starter templates refused: limit of $MAX_RULES rules reached")
            }
            prefs.edit().putBoolean("templates_inserted", true).putBoolean(TG_TEMPLATES_KEY, true).apply()
            return
        }
        // Installs from before 3.19 got their templates already: add only the Telegram report ones,
        // once, and only where both fit under the rule limit.
        if (prefs.getBoolean(TG_TEMPLATES_KEY, false)) return
        val ids = RuleInserts.insertAllWithinLimit(ruleDao, telegramReportTemplates(currentLang(context)), MAX_RULES)
        Log.i("AutomationViewModel", "telegram report templates: added=${ids != null} (limit $MAX_RULES rules)")
        prefs.edit().putBoolean(TG_TEMPLATES_KEY, true).apply()
    }
}

/** Disabled starter rules a fresh install gets once, named in [lang]. */
@Suppress("LongMethod") // a data table: one entry per template
internal fun starterTemplates(lang: String): List<RuleEntity> {
    fun tName(zh: String, en: String, ru: String): String = when (lang) { "zh" -> zh; "ru", "be" -> ru; else -> en }

    return listOf(
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
                TriggerDef("ServiceStart", "服务启动", "==", "true",
                    tName("BYDMate 启动", "BYDMate startup", "Запуск BYDMate"), kind = "service_start")
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
                TriggerDef("ServiceStart", "服务启动", "==", "true",
                    tName("BYDMate 启动", "BYDMate startup", "Запуск BYDMate"), kind = "service_start")
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
                TriggerDef("Gear", "档位", "==", "4",
                    tName("档位 = D", "Gear = D", "Передача = D"))
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
        ),
    ) + telegramReportTemplates(lang)
}

/** The two Telegram report rules of 3.19, disabled: also added once to installs made before it. */
internal fun telegramReportTemplates(lang: String): List<RuleEntity> {
    // A local six-language helper: the shared tName above only knows zh/en/ru and falls back to
    // ru for be, en for pl/pt, which ends up in the sent report header (finding #4, 2026-09-27).
    fun tName(zh: String, en: String, ru: String, be: String, pl: String, pt: String): String = when (lang) {
        "zh" -> zh
        "ru" -> ru
        "be" -> be
        "pl" -> pl
        "pt" -> pt
        else -> en
    }
    val reportName = tName(
        "Telegram 报告", "Telegram report", "Отчёт в Telegram",
        "Справаздача ў Telegram", "Raport w Telegramie", "Relatório no Telegram",
    )
    fun report(vararg fields: ReportField) = ActionDef(
        command = "",
        displayName = reportName,
        kind = TELEGRAM_REPORT_KIND,
        payload = telegramReportPayload(fields.toSet(), ""),
    )
    return listOf(
        RuleEntity(
            name = tName(
                "车在哪里", "Where the car is", "Где машина",
                "Дзе машына", "Gdzie jest samochód", "Onde está o carro",
            ),
            enabled = false,
            triggerLogic = "AND",
            triggers = TriggerDef.listToJson(listOf(
                TriggerDef("Gear", "档位", "==", "1", tName(
                    "档位 = P", "Gear = P", "Передача = P", "Перадача = P", "Bieg = P", "Marcha = P",
                ))
            )),
            actions = ActionDef.listToJson(listOf(report(ReportField.LOCATION, ReportField.SOC, ReportField.RANGE))),
            cooldownSeconds = 60
        ),
        RuleEntity(
            name = tName(
                "启动时状态", "Status at start", "Статус при запуске",
                "Стан пры запуску", "Stan przy uruchomieniu", "Status ao iniciar",
            ),
            enabled = false,
            triggerLogic = "AND",
            triggers = TriggerDef.listToJson(listOf(
                TriggerDef("ServiceStart", "服务启动", "==", "true", tName(
                    "BYDMate 启动", "BYDMate startup", "Запуск BYDMate",
                    "Запуск BYDMate", "Uruchomienie BYDMate", "Inicialização do BYDMate",
                ), kind = "service_start")
            )),
            actions = ActionDef.listToJson(listOf(report(ReportField.SOC, ReportField.RANGE, ReportField.TRIP))),
            cooldownSeconds = 60
        ),
    )
}

/** Stand-in phone for validating an imported rule whose call contact is not picked yet. */
private const val VALIDATION_PHONE = "00000"

/** Stand-in address for validating an imported rule whose link has to be entered again. */
private const val VALIDATION_URL = "https://localhost"

/** Rules the user can have: the editor, the import and the voice agent stop at this many. */
internal const val MAX_RULES = 50

/** Actions one rule can hold: the editor stops offering «add» at this many. */
internal const val MAX_RULE_ACTIONS = 20

/** Set once the Telegram report templates were offered (3.19), on fresh and older installs alike. */
private const val TG_TEMPLATES_KEY = "templates_tg_report_inserted"

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

// --- App close helpers (#280): same payload as app launch, without minimize ---

fun newAppCloseAction(context: Context): ActionDef = ActionDef(
    command = "",
    displayName = context.getString(R.string.automation_action_app_close),
    kind = "app_close",
    payload = """{"packageName":"","appLabel":""}"""
)

fun ActionDef.withAppClose(packageName: String, appLabel: String): ActionDef = copy(
    payload = org.json.JSONObject().put("packageName", packageName).put("appLabel", appLabel).toString()
)

// --- Media key helpers (#212, #275): payload "play" or "pause" ---

fun newMediaKeyAction(context: Context, key: String): ActionDef = ActionDef(
    command = "",
    displayName = context.getString(
        if (key == "play") R.string.automation_action_media_play else R.string.automation_action_media_pause
    ),
    kind = "media_key",
    payload = key
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
/** [trigger] with the catalog's name of its param and value; anything off the catalog as it is. */
internal fun withCatalogName(trigger: TriggerDef, context: Context): TriggerDef {
    if (trigger.kind != "param") return trigger
    val option = TRIGGER_PARAMS.firstOrNull { it.param == trigger.param } ?: return trigger
    val value = option.localizedEnumLabel(trigger.value, context)
    return trigger.copy(displayName = "${option.localizedName(context)} ${trigger.operator} $value")
}

/** [action] with the catalog's name of its command or toggle target; anything else as it is. */
internal fun withCatalogName(action: ActionDef, context: Context): ActionDef = when (action.kind) {
    "toggle" -> action.payload?.let { action.copy(displayName = toggleDisplayName(context, it)) } ?: action
    "param" -> ACTION_COMMANDS.firstOrNull { it.toggleTarget == null && it.command == action.command }
        ?.let { action.copy(displayName = it.localizedName(context)) }
        ?: levelActionName(action.command, context.appLocalizedContext())?.let { action.copy(displayName = it) }
        ?: action
    else -> action
}

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

// --- Telegram report helpers (3.19) ---

fun newTelegramReportAction(context: Context): ActionDef = ActionDef(
    command = "",
    displayName = context.appLocalizedContext().getString(R.string.automation_action_tg_report),
    kind = TELEGRAM_REPORT_KIND,
    payload = telegramReportPayload(ReportField.DEFAULT, ""),
)

internal fun telegramReportPayload(fields: Set<ReportField>, text: String): String =
    org.json.JSONObject().put("fields", ReportField.toJson(fields)).put("text", text).toString()

fun ActionDef.reportFields(): Set<ReportField> = try {
    ReportField.fromJson(org.json.JSONObject(payload ?: "{}").optJSONArray("fields"))
} catch (e: Exception) { emptySet() }

fun ActionDef.reportText(): String = try {
    org.json.JSONObject(payload ?: "{}").optString("text")
} catch (e: Exception) { "" }

fun ActionDef.withTelegramReport(fields: Set<ReportField>, text: String): ActionDef =
    copy(payload = telegramReportPayload(fields, text))

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
