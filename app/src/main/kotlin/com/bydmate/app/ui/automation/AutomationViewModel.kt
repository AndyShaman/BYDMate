package com.bydmate.app.ui.automation

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.dao.RuleLogDao
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.PlaceEntity
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.RuleLogEntity
import com.bydmate.app.data.local.entity.TriggerDef
import com.bydmate.app.data.repository.PlaceRepository
import com.bydmate.app.data.vehicle.VehicleApi
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
    val chineseName: String,
    val displayName: String,
    val englishName: String = displayName,
    val unit: String = "",
    val category: String,
    val chineseCategory: String = category,
    val englishCategory: String = category,
    val enumValues: List<Pair<String, String>>? = null // value to label
)

data class ActionOption(
    val command: String,
    val displayName: String,
    val chineseName: String = displayName,
    val englishName: String = displayName,
    val category: String,
    val chineseCategory: String = category,
    val englishCategory: String = category
)

internal fun currentLang(context: Context): String =
    LocalePreferences(context).getLanguage() ?: "en"

internal fun localized(zh: String, en: String, ru: String, context: Context): String =
    when (currentLang(context)) { "zh" -> zh; "en" -> en; else -> ru }

fun TriggerParamOption.localizedName(context: Context): String =
    when (currentLang(context)) {
        "zh" -> chineseName
        "en" -> englishName
        else -> displayName
    }

fun TriggerParamOption.localizedCategory(context: Context): String =
    when (currentLang(context)) {
        "zh" -> chineseCategory
        "en" -> englishCategory
        else -> category
    }

fun ActionOption.localizedName(context: Context): String =
    when (currentLang(context)) {
        "zh" -> chineseName
        "en" -> englishName
        else -> displayName
    }

fun ActionOption.localizedCategory(context: Context): String =
    when (currentLang(context)) {
        "zh" -> chineseCategory
        "en" -> englishCategory
        else -> category
    }

val TRIGGER_PARAMS = listOf(
    TriggerParamOption("Speed", "车速", "Скорость", "Speed", "км/ч", "Движение", "行驶", "Driving"),
    TriggerParamOption("Gear", "档位", "Передача", "Gear", "", "Движение", "行驶", "Driving",
        enumValues = listOf("1" to "P", "2" to "R", "3" to "N", "4" to "D")),
    TriggerParamOption("DriveMode", "整车运行模式", "Режим вождения", "Drive Mode", "", "Движение", "行驶", "Driving",
        enumValues = listOf("0" to "NORMAL", "1" to "ECO", "2" to "SPORT", "4" to "SNOW")),
    TriggerParamOption("SOC", "电量百分比", "SOC", "SOC", "%", "Энергия", "能源", "Energy"),
    TriggerParamOption("ChargingStatus", "充电状态", "Статус зарядки", "Charging Status", "", "Энергия", "能源", "Energy",
        enumValues = listOf("0" to "Нет", "1" to "Подключён", "2" to "Заряжается")),
    TriggerParamOption("PowerState", "电源状态", "Питание", "Power State", "", "Энергия", "能源", "Energy",
        enumValues = listOf("0" to "OFF", "1" to "ON", "2" to "DRIVE")),
    TriggerParamOption("Voltage12V", "蓄电池电压", "12V аккумулятор", "12V Battery", "В", "Энергия", "能源", "Energy"),
    TriggerParamOption("ExtTemp", "车外温度", "Темп. снаружи", "Outside Temp", "°C", "Температура", "温度", "Temperature"),
    TriggerParamOption("InsideTemp", "车内温度", "Темп. салона", "Cabin Temp", "°C", "Температура", "温度", "Temperature"),
    TriggerParamOption("AvgBatTemp", "平均电池温度", "Темп. батареи", "Battery Temp", "°C", "Температура", "温度", "Temperature"),
    TriggerParamOption("WindowFL", "主驾车窗打开百分比", "Окно водителя", "Driver Window", "% откр.", "Кузов", "车身", "Body"),
    TriggerParamOption("WindowFR", "副驾车窗打开百分比", "Окно пассажира", "Passenger Window", "% откр.", "Кузов", "车身", "Body"),
    TriggerParamOption("WindowRL", "左后车窗打开百分比", "Окно ЛЗ", "Rear Left Window", "% откр.", "Кузов", "车身", "Body"),
    TriggerParamOption("WindowRR", "右后车窗打开百分比", "Окно ПЗ", "Rear Right Window", "% откр.", "Кузов", "车身", "Body"),
    TriggerParamOption("Sunroof", "天窗打开百分比", "Люк", "Sunroof", "% откр.", "Кузов", "车身", "Body"),
    TriggerParamOption("DoorFL", "主驾车门", "Дверь водителя", "Driver Door", "", "Кузов", "车身", "Body",
        enumValues = listOf("0" to "Закрыта", "1" to "Открыта")),
    TriggerParamOption("DoorFR", "副驾车门", "Дверь пассажира", "Passenger Door", "", "Кузов", "车身", "Body",
        enumValues = listOf("0" to "Закрыта", "1" to "Открыта")),
    TriggerParamOption("DoorRL", "左后车门", "Дверь ЛЗ", "Rear Left Door", "", "Кузов", "车身", "Body",
        enumValues = listOf("0" to "Закрыта", "1" to "Открыта")),
    TriggerParamOption("DoorRR", "右后车门", "Дверь ПЗ", "Rear Right Door", "", "Кузов", "车身", "Body",
        enumValues = listOf("0" to "Закрыта", "1" to "Открыта")),
    TriggerParamOption("Hood", "引擎盖", "Капот", "Hood", "", "Кузов", "车身", "Body",
        enumValues = listOf("0" to "Закрыт", "1" to "Открыт")),
    TriggerParamOption("LockFL", "主驾车门锁", "Замок двери водителя", "Driver Door Lock", "", "Кузов", "车身", "Body",
        enumValues = listOf("0" to "Разблокирован", "1" to "Заблокирован")),
    TriggerParamOption("Trunk", "后备箱门", "Багажник", "Trunk", "", "Кузов", "车身", "Body",
        enumValues = listOf("0" to "Закрыт", "1" to "Открыт")),
    TriggerParamOption("ACStatus", "空调状态", "Кондиционер", "AC Status", "", "Климат", "空调", "Climate"),
    TriggerParamOption("ACCirc", "空调循环方式", "Режим циркуляции", "AC Circulation", "", "Климат", "空调", "Climate",
        enumValues = listOf("0" to "Внешний воздух", "1" to "Внутренний воздух")),
    TriggerParamOption("ACTemp", "主驾驶空调温度", "Темп. AC", "AC Temp", "°C", "Климат", "空调", "Climate"),
    TriggerParamOption("FanLevel", "风量档位", "Вентилятор", "Fan Level", "", "Климат", "空调", "Climate"),
    TriggerParamOption("SeatbeltFL", "主驾驶安全带状态", "Ремень водителя", "Driver Seatbelt", "", "Безопасность", "安全", "Safety",
        enumValues = listOf("0" to "Не пристёгнут", "1" to "Пристёгнут")),
    TriggerParamOption("TirePressFL", "左前轮气压", "Давление ЛП шины", "FL Tire Pressure", "кПа", "Безопасность", "安全", "Safety"),
    TriggerParamOption("TirePressFR", "右前轮气压", "Давление ПП шины", "FR Tire Pressure", "кПа", "Безопасность", "安全", "Safety"),
    TriggerParamOption("TirePressRL", "左后轮气压", "Давление ЛЗ шины", "RL Tire Pressure", "кПа", "Безопасность", "安全", "Safety"),
    TriggerParamOption("TirePressRR", "右后轮气压", "Давление ПЗ шины", "RR Tire Pressure", "кПа", "Безопасность", "安全", "Safety"),
    TriggerParamOption("Rain", "雨量", "Датчик дождя", "Rain Sensor", "(0=сухо)", "Безопасность", "安全", "Safety"),
    TriggerParamOption("LightLow", "近光灯", "Ближний свет", "Low Beam", "", "Свет", "灯光", "Light",
        enumValues = listOf("0" to "Выкл", "1" to "Вкл")),
    TriggerParamOption("DRL", "日行灯", "Дневные ходовые", "DRL", "", "Свет", "灯光", "Light",
        enumValues = listOf("0" to "Нет", "1" to "Вкл", "2" to "Выкл"))
)

val ACTION_COMMANDS = listOf(
<<<<<<< HEAD
    ActionOption("车窗通风", "Проветривание", "车窗通风", "Vent Windows", "Окна", "车窗", "Windows"),
    ActionOption("车窗关闭", "Закрыть все окна", "关闭所有车窗", "Close All Windows", "Окна", "车窗", "Windows"),
    ActionOption("车窗全开", "Открыть все окна", "打开所有车窗", "Open All Windows", "Окна", "车窗", "Windows"),
    ActionOption("车窗半开", "Все окна на 50%", "所有车窗半开", "All Windows 50%", "Окна", "车窗", "Windows"),
    ActionOption("前排车窗关闭", "Закрыть передние", "关闭前排车窗", "Close Front Windows", "Окна", "车窗", "Windows"),
    ActionOption("后排车窗关闭", "Закрыть задние", "关闭后排车窗", "Close Rear Windows", "Окна", "车窗", "Windows"),
    ActionOption("前排车窗全开", "Открыть передние", "打开前排车窗", "Open Front Windows", "Окна", "车窗", "Windows"),
    ActionOption("后排车窗全开", "Открыть задние", "打开后排车窗", "Open Rear Windows", "Окна", "车窗", "Windows"),
    ActionOption("主驾打开100", "Открыть окно водителя", "打开主驾车窗", "Open Driver Window", "Окна", "车窗", "Windows"),
    ActionOption("主驾打开0", "Закрыть окно водителя", "关闭主驾车窗", "Close Driver Window", "Окна", "车窗", "Windows"),
    ActionOption("副驾打开100", "Открыть окно пассажира", "打开副驾车窗", "Open Passenger Window", "Окна", "车窗", "Windows"),
    ActionOption("副驾打开0", "Закрыть окно пассажира", "关闭副驾车窗", "Close Passenger Window", "Окна", "车窗", "Windows"),
    ActionOption("自动空调", "Авто AC", "自动空调", "Auto AC", "Климат", "空调", "Climate"),
    ActionOption("打开空调通风", "Обдув без AC", "通风（不开AC）", "Ventilation (no AC)", "Климат", "空调", "Climate"),
    ActionOption("设置温度18", "Темп. 18°C", "温度 18°C", "Temp 18°C", "Климат", "空调", "Climate"),
    ActionOption("设置温度20", "Темп. 20°C", "温度 20°C", "Temp 20°C", "Климат", "空调", "Climate"),
    ActionOption("设置温度22", "Темп. 22°C", "温度 22°C", "Temp 22°C", "Климат", "空调", "Climate"),
    ActionOption("设置温度25", "Темп. 25°C", "温度 25°C", "Temp 25°C", "Климат", "空调", "Climate"),
    ActionOption("内循环", "Циркуляция внутр.", "内循环", "Recirculation", "Климат", "空调", "Climate"),
    ActionOption("外循环", "Циркуляция внешн.", "外循环", "Fresh Air", "Климат", "空调", "Climate"),
    ActionOption("吹前挡", "Обдув лобового вкл", "前挡风除雾开", "Windshield Defog On", "Климат", "空调", "Climate"),
    ActionOption("关闭吹前挡", "Обдув лобового выкл", "前挡风除雾关", "Windshield Defog Off", "Климат", "空调", "Climate"),
    ActionOption("主驾座椅加热1档", "Подогрев водителя 1", "主驾座椅加热1档", "Driver Heat 1", "Сиденья", "座椅", "Seats"),
    ActionOption("主驾座椅加热2档", "Подогрев водителя 2", "主驾座椅加热2档", "Driver Heat 2", "Сиденья", "座椅", "Seats"),
    ActionOption("主驾座椅加热关闭", "Подогрев водителя выкл", "主驾座椅加热关", "Driver Heat Off", "Сиденья", "座椅", "Seats"),
    ActionOption("副驾座椅加热1档", "Подогрев пассажира 1", "副驾座椅加热1档", "Passenger Heat 1", "Сиденья", "座椅", "Seats"),
    ActionOption("副驾座椅加热2档", "Подогрев пассажира 2", "副驾座椅加热2档", "Passenger Heat 2", "Сиденья", "座椅", "Seats"),
    ActionOption("副驾座椅加热关闭", "Подогрев пассажира выкл", "副驾座椅加热关", "Passenger Heat Off", "Сиденья", "座椅", "Seats"),
    ActionOption("主驾座椅通风1档", "Вентиляция водителя 1", "主驾座椅通风1档", "Driver Vent 1", "Сиденья", "座椅", "Seats"),
    ActionOption("主驾座椅通风2档", "Вентиляция водителя 2", "主驾座椅通风2档", "Driver Vent 2", "Сиденья", "座椅", "Seats"),
    ActionOption("主驾座椅通风关闭", "Вентиляция водителя выкл", "主驾座椅通风关", "Driver Vent Off", "Сиденья", "座椅", "Seats"),
    ActionOption("副驾座椅通风1档", "Вентиляция пассажира 1", "副驾座椅通风1档", "Passenger Vent 1", "Сиденья", "座椅", "Seats"),
    ActionOption("副驾座椅通风2档", "Вентиляция пассажира 2", "副驾座椅通风2档", "Passenger Vent 2", "Сиденья", "座椅", "Seats"),
    ActionOption("副驾座椅通风关闭", "Вентиляция пассажира выкл", "副驾座椅通风关", "Passenger Vent Off", "Сиденья", "座椅", "Seats"),
    ActionOption("后视镜加热", "Подогрев зеркал вкл", "后视镜加热开", "Mirror Heat On", "Зеркала", "后视镜", "Mirrors"),
    ActionOption("关闭后视镜加热", "Подогрев зеркал выкл", "后视镜加热关", "Mirror Heat Off", "Зеркала", "后视镜", "Mirrors"),
    ActionOption("氛围灯打开", "Амбиент вкл", "氛围灯开", "Ambient Light On", "Свет", "灯光", "Light"),
    ActionOption("氛围灯关闭", "Амбиент выкл", "氛围灯关", "Ambient Light Off", "Свет", "灯光", "Light"),
    ActionOption("打开日行灯", "ДХО вкл", "日行灯开", "DRL On", "Свет", "灯光", "Light"),
    ActionOption("关闭日行灯", "ДХО выкл", "日行灯关", "DRL Off", "Свет", "灯光", "Light"),
    ActionOption("打开车内灯", "Салонный свет вкл", "车内灯开", "Interior Light On", "Свет", "灯光", "Light"),
    ActionOption("关闭车内灯", "Салонный свет выкл", "车内灯关", "Interior Light Off", "Свет", "灯光", "Light"),
    ActionOption("车门上锁", "Заблокировать", "车门上锁", "Lock Doors", "Замки", "门锁", "Locks"),
    ActionOption("车门解锁", "Разблокировать", "车门解锁", "Unlock Doors", "Замки", "门锁", "Locks"),
    ActionOption("天窗打开100", "Люк открыть 100%", "天窗全开", "Sunroof Open 100%", "Люк", "天窗", "Sunroof"),
    ActionOption("天窗打开50", "Люк открыть 50%", "天窗半开", "Sunroof Open 50%", "Люк", "天窗", "Sunroof"),
    ActionOption("天窗打开0", "Люк закрыть", "天窗关闭", "Sunroof Close", "Люк", "天窗", "Sunroof"),
    ActionOption("遮阳帘打开", "Шторка открыть", "遮阳帘打开", "Sunshade Open", "Люк", "天窗", "Sunroof"),
    ActionOption("遮阳帘关闭", "Шторка закрыть", "遮阳帘关闭", "Sunshade Close", "Люк", "天窗", "Sunroof")
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
            val result = vehicleApi.dispatch(command)
            val msg = if (result.isSuccess) "Отправлено"
                      else "Ошибка: ${result.exceptionOrNull()?.message ?: "недоступно"}"
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
        actions.forEachIndexed { idx, a ->
            val n = idx + 1
            when (a.kind) {
                "param" -> {
                    if (a.command.isBlank()) return localized("动作 #$n：未指定命令", "Action #$n: command not set", "Действие #$n: команда не задана", context)
                }
                "notification_silent", "notification_sound" -> {
                    if (a.notificationTitle().isBlank()) return localized("动作 #$n：通知标题为空", "Action #$n: notification title is empty", "Действие #$n: заголовок уведомления пуст", context)
                }
                "app_launch" -> {
                    if (a.appLaunchPackageName().isBlank()) return localized("动作 #$n：未选择应用", "Action #$n: app not selected", "Действие #$n: приложение не выбрано", context)
                }
                "call" -> {
                    val phone = a.callPhone().trim()
                    if (phone.length !in 5..20) return localized("动作 #$n：电话号码不正确", "Action #$n: phone number is invalid", "Действие #$n: номер телефона некорректен", context)
                }
                "navigate" -> {
                    val lat = a.navigateLat()
                    val lon = a.navigateLon()
                    if (lat == null || lon == null || (lat == 0.0 && lon == 0.0)) {
                        return localized("动作 #$n：未选择导航目的地", "Action #$n: navigation destination not selected", "Действие #$n: место для маршрута не выбрано", context)
                    }
                }
                "url" -> {
                    val u = a.urlString().trim()
                    if (u.isEmpty()) return localized("动作 #$n：URL 为空", "Action #$n: URL is empty", "Действие #$n: URL пустой", context)
                    // Allow any scheme: http(s)://, yandexmusic://, tel:, intent://, geo:, etc.
                    if (!u.matches(Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*:.+"))) {
                        return localized("动作 #$n：URL 必须包含协议头（http://, yandexmusic://, tel:, ...）", "Action #$n: URL must contain a scheme (http://, yandexmusic://, tel:, ...)", "Действие #$n: URL должен содержать схему (http://, yandexmusic://, tel:, ...)", context)
                    }
                }
                "yandex_music" -> {
                    if (a.yandexMusicMode().isBlank()) return localized("动作 #$n：未选择 Yandex 音乐模式", "Action #$n: Yandex Music mode not selected", "Действие #$n: режим Я.Музыки не выбран", context)
                }
            }
        }
        return null
    }

    fun saveRule() {
        val e = _uiState.value.editing
        if (e.name.isBlank() || e.triggers.isEmpty() || e.actions.isEmpty()) {
            _uiState.value = _uiState.value.copy(editorError =
                localized("名称和至少一个条件及动作是必需的", "Name and at least one condition and action are required", "Название и хотя бы одно условие и действие обязательны", context)
            )
            return
        }
        val actionError = validateActions(e.actions)
        if (actionError != null) {
            _uiState.value = _uiState.value.copy(editorError = actionError)
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
                    name = "${rule.name} (${localized("副本", "copy", "копия", context)})",
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

fun newNotificationAction(silent: Boolean, context: Context): ActionDef {
    val name = if (silent)
        localized("通知（静音）", "Notification (silent)", "Уведомление (без звука)", context)
    else
        localized("通知（带声音）", "Notification (sound)", "Уведомление (звук)", context)
    return ActionDef(
        command = "",
        displayName = name,
        kind = if (silent) "notification_silent" else "notification_sound",
        payload = """{"title":"","text":""}"""
    )
}

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
    displayName = localized("启动应用", "Launch app", "Запуск приложения", context),
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
    displayName = localized("拨打电话", "Call", "Звонок", context),
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
    displayName = localized("Yandex 导航路线", "Route in Y.Navigator", "Маршрут в Я.Навигаторе", context),
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
    displayName = localized("打开 URL", "Open URL", "Открыть URL", context),
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
    displayName = localized("Yandex 音乐", "Yandex Music", "Яндекс.Музыка", context),
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
