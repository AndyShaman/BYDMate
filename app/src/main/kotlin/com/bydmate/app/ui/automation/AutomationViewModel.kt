package com.bydmate.app.ui.automation

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bydmate.app.data.local.LocalePreferences
import androidx.annotation.StringRes
import com.bydmate.app.R
import com.bydmate.app.util.appLocalizedContext
import com.bydmate.app.data.automation.ActionValidationError
import com.bydmate.app.data.automation.RuleDraftValidator
import com.bydmate.app.data.automation.TriggerValidationError
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.dao.RuleLogDao
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.PlaceEntity
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.RuleLogEntity
import com.bydmate.app.data.local.entity.TriggerDef
import com.bydmate.app.data.repository.PlaceRepository
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.vehicle.VehicleApi
import com.bydmate.app.service.TrackingService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    @StringRes val categoryRes: Int
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

fun ActionOption.localizedName(context: Context): String =
    context.appLocalizedContext().getString(nameRes)

fun ActionOption.localizedCategory(context: Context): String =
    context.appLocalizedContext().getString(categoryRes)

val TRIGGER_PARAMS = listOf(
        TriggerParamOption("Speed", "车速", R.string.auto_param_speed, R.string.auto_cat_driving, R.string.auto_unit_kmh),
        TriggerParamOption("Gear", "档位", R.string.auto_param_gear, R.string.auto_cat_driving, enumValues = listOf("1" to R.string.auto_enum_code_p, "2" to R.string.auto_enum_code_r, "3" to R.string.auto_enum_code_n, "4" to R.string.auto_enum_code_d)),
        TriggerParamOption("DriveMode", "整车运行模式", R.string.auto_param_drivemode, R.string.auto_cat_driving, enumValues = listOf("0" to R.string.auto_enum_code_normal, "1" to R.string.auto_enum_code_eco, "2" to R.string.auto_enum_code_sport, "4" to R.string.auto_enum_code_snow)),
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
        ActionOption("自动空调", R.string.auto_act_auto_ac, R.string.auto_cat_climate),
        ActionOption("打开空调通风", R.string.auto_act_ventilation_no_ac, R.string.auto_cat_climate),
        ActionOption("设置温度18", R.string.auto_act_temp_18c, R.string.auto_cat_climate),
        ActionOption("设置温度20", R.string.auto_act_temp_20c, R.string.auto_cat_climate),
        ActionOption("设置温度22", R.string.auto_act_temp_22c, R.string.auto_cat_climate),
        ActionOption("设置温度25", R.string.auto_act_temp_25c, R.string.auto_cat_climate),
        ActionOption("内循环", R.string.auto_act_recirculation, R.string.auto_cat_climate),
        ActionOption("外循环", R.string.auto_act_fresh_air, R.string.auto_cat_climate),
        ActionOption("吹前挡", R.string.auto_act_windshield_defog_on, R.string.auto_cat_climate),
        ActionOption("关闭吹前挡", R.string.auto_act_windshield_defog_off, R.string.auto_cat_climate),
        ActionOption("关闭空调", R.string.auto_act_ac_off, R.string.auto_cat_climate),
        ActionOption("空调自动", R.string.auto_act_ac_auto_on, R.string.auto_cat_climate),
        ActionOption("空调手动", R.string.auto_act_ac_auto_off, R.string.auto_cat_climate),
        ActionOption("主驾座椅加热1档", R.string.auto_act_driver_heat_1, R.string.auto_cat_seats),
        ActionOption("主驾座椅加热2档", R.string.auto_act_driver_heat_2, R.string.auto_cat_seats),
        ActionOption("主驾座椅加热3档", R.string.auto_act_driver_heat_3, R.string.auto_cat_seats),
        ActionOption("主驾座椅加热4档", R.string.auto_act_driver_heat_4, R.string.auto_cat_seats),
        ActionOption("主驾座椅加热5档", R.string.auto_act_driver_heat_5, R.string.auto_cat_seats),
        ActionOption("主驾座椅加热关闭", R.string.auto_act_driver_heat_off, R.string.auto_cat_seats),
        ActionOption("副驾座椅加热1档", R.string.auto_act_passenger_heat_1, R.string.auto_cat_seats),
        ActionOption("副驾座椅加热2档", R.string.auto_act_passenger_heat_2, R.string.auto_cat_seats),
        ActionOption("副驾座椅加热3档", R.string.auto_act_passenger_heat_3, R.string.auto_cat_seats),
        ActionOption("副驾座椅加热4档", R.string.auto_act_passenger_heat_4, R.string.auto_cat_seats),
        ActionOption("副驾座椅加热5档", R.string.auto_act_passenger_heat_5, R.string.auto_cat_seats),
        ActionOption("副驾座椅加热关闭", R.string.auto_act_passenger_heat_off, R.string.auto_cat_seats),
        ActionOption("主驾座椅通风1档", R.string.auto_act_driver_vent_1, R.string.auto_cat_seats),
        ActionOption("主驾座椅通风2档", R.string.auto_act_driver_vent_2, R.string.auto_cat_seats),
        ActionOption("主驾座椅通风3档", R.string.auto_act_driver_vent_3, R.string.auto_cat_seats),
        ActionOption("主驾座椅通风4档", R.string.auto_act_driver_vent_4, R.string.auto_cat_seats),
        ActionOption("主驾座椅通风5档", R.string.auto_act_driver_vent_5, R.string.auto_cat_seats),
        ActionOption("主驾座椅通风关闭", R.string.auto_act_driver_vent_off, R.string.auto_cat_seats),
        ActionOption("副驾座椅通风1档", R.string.auto_act_passenger_vent_1, R.string.auto_cat_seats),
        ActionOption("副驾座椅通风2档", R.string.auto_act_passenger_vent_2, R.string.auto_cat_seats),
        ActionOption("副驾座椅通风3档", R.string.auto_act_passenger_vent_3, R.string.auto_cat_seats),
        ActionOption("副驾座椅通风4档", R.string.auto_act_passenger_vent_4, R.string.auto_cat_seats),
        ActionOption("副驾座椅通风5档", R.string.auto_act_passenger_vent_5, R.string.auto_cat_seats),
        ActionOption("副驾座椅通风关闭", R.string.auto_act_passenger_vent_off, R.string.auto_cat_seats),
        ActionOption("后视镜加热", R.string.auto_act_mirror_heat_on, R.string.auto_cat_mirrors),
        ActionOption("关闭后视镜加热", R.string.auto_act_mirror_heat_off, R.string.auto_cat_mirrors),
        ActionOption("氛围灯打开", R.string.auto_act_ambient_light_on, R.string.auto_cat_light),
        ActionOption("氛围灯关闭", R.string.auto_act_ambient_light_off, R.string.auto_cat_light),
        ActionOption("打开日行灯", R.string.auto_act_drl_on, R.string.auto_cat_light),
        ActionOption("关闭日行灯", R.string.auto_act_drl_off, R.string.auto_cat_light),
        ActionOption("打开车内灯", R.string.auto_act_interior_light_on, R.string.auto_cat_light),
        ActionOption("关闭车内灯", R.string.auto_act_interior_light_off, R.string.auto_cat_light),
        ActionOption("车门上锁", R.string.auto_act_lock_doors, R.string.auto_cat_locks),
        ActionOption("车门解锁", R.string.auto_act_unlock_doors, R.string.auto_cat_locks),
        ActionOption("天窗打开100", R.string.auto_act_sunroof_open_100, R.string.auto_cat_sunroof),
        ActionOption("天窗打开50", R.string.auto_act_sunroof_open_50, R.string.auto_cat_sunroof),
        ActionOption("天窗打开0", R.string.auto_act_sunroof_close, R.string.auto_cat_sunroof),
        ActionOption("遮阳帘打开", R.string.auto_act_sunshade_open, R.string.auto_cat_sunroof),
        ActionOption("遮阳帘关闭", R.string.auto_act_sunshade_close, R.string.auto_cat_sunroof),
        ActionOption("天窗停止", R.string.auto_act_sunroof_stop, R.string.auto_cat_sunroof),
        ActionOption("天窗通风", R.string.auto_act_sunroof_updip, R.string.auto_cat_sunroof),
        ActionOption("天窗舒适打开", R.string.auto_act_sunroof_comfort, R.string.auto_cat_sunroof),
        ActionOption("开后备箱", R.string.auto_act_open_trunk, R.string.auto_cat_body),
        ActionOption("关后备箱", R.string.auto_act_close_trunk, R.string.auto_cat_body),
        ActionOption("前备箱打开", R.string.auto_act_open_front_trunk, R.string.auto_cat_body),
        ActionOption("前备箱关闭", R.string.auto_act_close_front_trunk, R.string.auto_cat_body),
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
    val isNew: Boolean = true
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
    val editorError: String? = null
)

@HiltViewModel
class AutomationViewModel @Inject constructor(
    private val ruleDao: RuleDao,
    private val ruleLogDao: RuleLogDao,
    private val placeRepository: PlaceRepository,
    private val vehicleApi: VehicleApi,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(AutomationUiState())
    val uiState: StateFlow<AutomationUiState> = _uiState.asStateFlow()

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
     * "Выполнить сейчас" — dispatch a single vehicle command through the native
     * write channel immediately, for live testing from the rule editor. Result is
     * surfaced via Toast; the raw autoservice status (real action vs no-op) lands
     * in logcat via HelperClient. Bypasses the automation edge/cooldown logic by
     * design — this is a manual, explicit user action.
     */
    fun executeNow(command: String) {
        viewModelScope.launch {
            // Manual test button bypasses ActionDispatcher.dispatch, so apply the
            // same safety gates explicitly (frunk/unlock fail closed on unknown speed).
            val block = ActionDispatcher.safetyBlockReason(command, TrackingService.lastData.value)
            if (block != null) {
                Toast.makeText(context, block, Toast.LENGTH_SHORT).show()
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
        if (_uiState.value.rules.size >= 50) return
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
                    isNew = false
                )
            )
        }
    }

    fun closeEditor() {
        _uiState.update { it.copy(showEditor = false, editorError = null) }
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
            is ActionValidationError.SpeakTextEmpty -> ctx.getString(R.string.auto_msg_speak_text_empty, err.index)
            is ActionValidationError.AgentQueryPromptEmpty -> ctx.getString(R.string.auto_msg_agent_query_prompt_empty, err.index)
            null -> null
        }
    }

    private fun validateTriggers(triggers: List<TriggerDef>, editingId: Long): String? {
        val ctx = context.appLocalizedContext()
        return when (RuleDraftValidator.validateTriggers(triggers, editingId, _uiState.value.rules)) {
            TriggerValidationError.VoicePhraseEmpty -> ctx.getString(R.string.automation_voice_phrase_empty)
            TriggerValidationError.VoicePhraseBuiltin -> ctx.getString(R.string.automation_voice_phrase_builtin)
            TriggerValidationError.VoicePhraseTaken -> ctx.getString(R.string.automation_voice_phrase_taken)
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

        val entity = RuleEntity(
            id = if (e.isNew) 0 else e.id,
            name = e.name.trim(),
            triggerLogic = e.triggerLogic,
            triggers = TriggerDef.listToJson(e.triggers),
            actions = ActionDef.listToJson(e.actions),
            cooldownSeconds = e.cooldownSeconds.coerceAtLeast(30),
            requirePark = e.requirePark,
            confirmBeforeExecute = e.confirmBeforeExecute,
            fireOncePerTrip = e.fireOncePerTrip,
            playSound = e.playSound,
        )

        viewModelScope.launch {
            if (e.isNew) ruleDao.insert(entity)
            else ruleDao.update(entity)
        }
        closeEditor()
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

    // --- Starter templates ---

    private suspend fun insertStarterTemplatesIfNeeded() {
        val prefs = context.getSharedPreferences("automation", Context.MODE_PRIVATE)
        if (prefs.getBoolean("templates_inserted", false)) return

        val lang = currentLang(context)
        fun tName(zh: String, en: String, ru: String): String = when (lang) { "zh" -> zh; "en" -> en; else -> ru }

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

fun ActionDef.withUrl(url: String, minimize: Boolean): ActionDef = copy(
    payload = org.json.JSONObject().apply {
        put("url", url)
        put("minimize", minimize)
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

// --- Cluster projection helpers ---

fun newClusterAction(context: Context): ActionDef = ActionDef(
    command = "cluster_projection",
    displayName = context.getString(R.string.automation_action_cluster_projection),
    kind = "cluster_projection",
    payload = "1"
)

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
