package com.bydmate.app.ui.automation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.dao.RuleLogDao
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.PlaceEntity
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.RuleLogEntity
import com.bydmate.app.data.local.entity.TriggerDef
import com.bydmate.app.data.repository.PlaceRepository
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
    val unit: String = "",
    val category: String,
    val enumValues: List<Pair<String, String>>? = null // value to label
)

data class ActionOption(
    val command: String,
    val displayName: String,
    val category: String
)

val TRIGGER_PARAMS = listOf(
    TriggerParamOption("Speed", "车速", "Скорость", "км/ч", "Движение"),
    TriggerParamOption("Gear", "档位", "Передача", "", "Движение",
        enumValues = listOf("1" to "P", "2" to "R", "3" to "N", "4" to "D")),
    TriggerParamOption("DriveMode", "整车运行模式", "Режим вождения", "", "Движение",
        enumValues = listOf("0" to "NORMAL", "1" to "ECO", "2" to "SPORT", "4" to "SNOW")),
    TriggerParamOption("SOC", "电量百分比", "SOC", "%", "Энергия"),
    TriggerParamOption("ChargingStatus", "充电状态", "Статус зарядки", "", "Энергия",
        enumValues = listOf("0" to "Нет", "1" to "Подключён", "2" to "Заряжается")),
    TriggerParamOption("PowerState", "电源状态", "Питание", "", "Энергия",
        enumValues = listOf("0" to "OFF", "1" to "ON", "2" to "DRIVE")),
    TriggerParamOption("Voltage12V", "蓄电池电压", "12V аккумулятор", "В", "Энергия"),
    TriggerParamOption("ExtTemp", "车外温度", "Темп. снаружи", "°C", "Температура"),
    TriggerParamOption("InsideTemp", "车内温度", "Темп. салона", "°C", "Температура"),
    TriggerParamOption("AvgBatTemp", "平均电池温度", "Темп. батареи", "°C", "Температура"),
    TriggerParamOption("WindowFL", "主驾车窗打开百分比", "Окно водителя", "% откр.", "Кузов"),
    TriggerParamOption("WindowFR", "副驾车窗打开百分比", "Окно пассажира", "% откр.", "Кузов"),
    TriggerParamOption("WindowRL", "左后车窗打开百分比", "Окно ЛЗ", "% откр.", "Кузов"),
    TriggerParamOption("WindowRR", "右后车窗打开百分比", "Окно ПЗ", "% откр.", "Кузов"),
    TriggerParamOption("Sunroof", "天窗打开百分比", "Люк", "% откр.", "Кузов"),
    TriggerParamOption("DoorFL", "主驾车门", "Дверь водителя", "", "Кузов",
        enumValues = listOf("0" to "Закрыта", "1" to "Открыта")),
    TriggerParamOption("DoorFR", "副驾车门", "Дверь пассажира", "", "Кузов",
        enumValues = listOf("0" to "Закрыта", "1" to "Открыта")),
    TriggerParamOption("DoorRL", "左后车门", "Дверь ЛЗ", "", "Кузов",
        enumValues = listOf("0" to "Закрыта", "1" to "Открыта")),
    TriggerParamOption("DoorRR", "右后车门", "Дверь ПЗ", "", "Кузов",
        enumValues = listOf("0" to "Закрыта", "1" to "Открыта")),
    TriggerParamOption("Hood", "引擎盖", "Капот", "", "Кузов",
        enumValues = listOf("0" to "Закрыт", "1" to "Открыт")),
    TriggerParamOption("LockFL", "主驾车门锁", "Замок двери водителя", "", "Кузов",
        enumValues = listOf("0" to "Разблокирован", "1" to "Заблокирован")),
    TriggerParamOption("Trunk", "后备箱门", "Багажник", "", "Кузов",
        enumValues = listOf("0" to "Закрыт", "1" to "Открыт")),
    TriggerParamOption("ACStatus", "空调状态", "Кондиционер", "", "Климат",
        enumValues = listOf("0" to "Выкл", "1" to "Вкл")),
    TriggerParamOption("ACCirc", "空调循环方式", "Режим циркуляции", "", "Климат",
        enumValues = listOf("0" to "Внешний воздух", "1" to "Внутренний воздух")),
    TriggerParamOption("ACTemp", "主驾驶空调温度", "Темп. AC", "°C", "Климат"),
    TriggerParamOption("FanLevel", "风量档位", "Вентилятор", "", "Климат"),
    TriggerParamOption("SeatbeltFL", "主驾驶安全带状态", "Ремень водителя", "", "Безопасность",
        enumValues = listOf("0" to "Не пристёгнут", "1" to "Пристёгнут")),
    TriggerParamOption("TirePressFL", "左前轮气压", "Давление ЛП шины", "кПа", "Безопасность"),
    TriggerParamOption("TirePressFR", "右前轮气压", "Давление ПП шины", "кПа", "Безопасность"),
    TriggerParamOption("TirePressRL", "左后轮气压", "Давление ЛЗ шины", "кПа", "Безопасность"),
    TriggerParamOption("TirePressRR", "右后轮气压", "Давление ПЗ шины", "кПа", "Безопасность"),
    TriggerParamOption("Rain", "雨量", "Датчик дождя", "(0=сухо)", "Безопасность"),
    TriggerParamOption("LightLow", "近光灯", "Ближний свет", "", "Свет",
        enumValues = listOf("0" to "Выкл", "1" to "Вкл")),
    TriggerParamOption("DRL", "日行灯", "Дневные ходовые", "", "Свет",
        enumValues = listOf("0" to "Нет", "1" to "Вкл", "2" to "Выкл"))
)

val ACTION_COMMANDS = listOf(
    ActionOption("车窗通风", "Проветривание", "Окна"),
    ActionOption("车窗关闭", "Закрыть все окна", "Окна"),
    ActionOption("车窗全开", "Открыть все окна", "Окна"),
    ActionOption("车窗半开", "Все окна на 50%", "Окна"),
    ActionOption("前排车窗关闭", "Закрыть передние", "Окна"),
    ActionOption("后排车窗关闭", "Закрыть задние", "Окна"),
    ActionOption("前排车窗全开", "Открыть передние", "Окна"),
    ActionOption("后排车窗全开", "Открыть задние", "Окна"),
    ActionOption("自动空调", "Авто AC", "Климат"),
    ActionOption("打开空调通风", "Обдув без AC", "Климат"),
    ActionOption("设置温度18", "Темп. 18°C", "Климат"),
    ActionOption("设置温度20", "Темп. 20°C", "Климат"),
    ActionOption("设置温度22", "Темп. 22°C", "Климат"),
    ActionOption("设置温度25", "Темп. 25°C", "Климат"),
    ActionOption("内循环", "Циркуляция внутр.", "Климат"),
    ActionOption("外循环", "Циркуляция внешн.", "Климат"),
    ActionOption("吹前挡", "Обдув лобового вкл", "Климат"),
    ActionOption("关闭吹前挡", "Обдув лобового выкл", "Климат"),
    ActionOption("主驾座椅加热1档", "Подогрев водителя 1", "Сиденья"),
    ActionOption("主驾座椅加热2档", "Подогрев водителя 2", "Сиденья"),
    ActionOption("主驾座椅加热关闭", "Подогрев водителя выкл", "Сиденья"),
    ActionOption("副驾座椅加热1档", "Подогрев пассажира 1", "Сиденья"),
    ActionOption("副驾座椅加热2档", "Подогрев пассажира 2", "Сиденья"),
    ActionOption("副驾座椅加热关闭", "Подогрев пассажира выкл", "Сиденья"),
    ActionOption("主驾座椅通风1档", "Вентиляция водителя 1", "Сиденья"),
    ActionOption("主驾座椅通风2档", "Вентиляция водителя 2", "Сиденья"),
    ActionOption("主驾座椅通风关闭", "Вентиляция водителя выкл", "Сиденья"),
    ActionOption("副驾座椅通风1档", "Вентиляция пассажира 1", "Сиденья"),
    ActionOption("副驾座椅通风2档", "Вентиляция пассажира 2", "Сиденья"),
    ActionOption("副驾座椅通风关闭", "Вентиляция пассажира выкл", "Сиденья"),
    ActionOption("后视镜加热", "Подогрев зеркал вкл", "Зеркала"),
    ActionOption("关闭后视镜加热", "Подогрев зеркал выкл", "Зеркала"),
    ActionOption("氛围灯打开", "Амбиент вкл", "Свет"),
    ActionOption("氛围灯关闭", "Амбиент выкл", "Свет"),
    ActionOption("打开车内灯", "Салонный свет вкл", "Свет"),
    ActionOption("关闭车内灯", "Салонный свет выкл", "Свет"),
    ActionOption("车门上锁", "Заблокировать", "Замки"),
    ActionOption("车门解锁", "Разблокировать", "Замки"),
    ActionOption("天窗打开100", "Люк открыть 100%", "Люк"),
    ActionOption("天窗打开50", "Люк открыть 50%", "Люк"),
    ActionOption("天窗打开0", "Люк закрыть", "Люк"),
    ActionOption("遮阳帘打开", "Шторка открыть", "Люк"),
    ActionOption("遮阳帘关闭", "Шторка закрыть", "Люк")
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
    val places: List<PlaceEntity> = emptyList()
)

@HiltViewModel
class AutomationViewModel @Inject constructor(
    private val ruleDao: RuleDao,
    private val ruleLogDao: RuleLogDao,
    private val placeRepository: PlaceRepository,
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

    // --- Editor ---

    fun openNewRule() {
        if (_uiState.value.rules.size >= 50) return
        val defaultParam = TRIGGER_PARAMS.first()
        val defaultAction = ACTION_COMMANDS.first()
        _uiState.update {
            it.copy(
                showEditor = true,
                editing = EditingRule(
                    triggers = listOf(
                        TriggerDef(defaultParam.param, defaultParam.chineseName, ">", "0", defaultParam.displayName)
                    ),
                    actions = listOf(
                        ActionDef(defaultAction.command, defaultAction.displayName)
                    )
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
                    isNew = false
                )
            )
        }
    }

    fun closeEditor() {
        _uiState.update { it.copy(showEditor = false) }
    }

    fun updateEditing(transform: EditingRule.() -> EditingRule) {
        _uiState.update { it.copy(editing = it.editing.transform()) }
    }

    fun saveRule() {
        val e = _uiState.value.editing
        if (e.name.isBlank() || e.triggers.isEmpty() || e.actions.isEmpty()) return

        val entity = RuleEntity(
            id = if (e.isNew) 0 else e.id,
            name = e.name.trim(),
            triggerLogic = e.triggerLogic,
            triggers = TriggerDef.listToJson(e.triggers),
            actions = ActionDef.listToJson(e.actions),
            cooldownSeconds = e.cooldownSeconds.coerceAtLeast(30),
            requirePark = e.requirePark,
            confirmBeforeExecute = e.confirmBeforeExecute
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
                    name = "${rule.name} (копия)",
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

        val templates = listOf(
            RuleEntity(
                name = "Закрыть окна на трассе",
                enabled = false,
                triggerLogic = "AND",
                triggers = TriggerDef.listToJson(listOf(
                    TriggerDef("Speed", "车速", ">", "100", "Скорость > 100 км/ч")
                )),
                actions = ActionDef.listToJson(listOf(
                    ActionDef("车窗关闭", "Закрыть все окна")
                )),
                cooldownSeconds = 60
            ),
            RuleEntity(
                name = "Зимний старт",
                enabled = false,
                triggerLogic = "AND",
                triggers = TriggerDef.listToJson(listOf(
                    TriggerDef("ExtTemp", "车外温度", "<", "0", "Темп. снаружи < 0°C"),
                    TriggerDef("PowerState", "电源状态", "==", "2", "Питание = DRIVE")
                )),
                actions = ActionDef.listToJson(listOf(
                    ActionDef("主驾座椅加热2档", "Подогрев водителя 2"),
                    ActionDef("后视镜加热", "Подогрев зеркал вкл")
                )),
                cooldownSeconds = 600
            ),
            RuleEntity(
                name = "Эко при низком заряде",
                enabled = false,
                triggerLogic = "AND",
                triggers = TriggerDef.listToJson(listOf(
                    TriggerDef("SOC", "电量百分比", "<", "15", "SOC < 15%")
                )),
                actions = ActionDef.listToJson(listOf(
                    ActionDef("ECO模式", "ECO режим")
                )),
                cooldownSeconds = 300
            ),
            RuleEntity(
                name = "Летнее охлаждение",
                enabled = false,
                triggerLogic = "AND",
                triggers = TriggerDef.listToJson(listOf(
                    TriggerDef("InsideTemp", "车内温度", ">", "30", "Темп. салона > 30°C"),
                    TriggerDef("PowerState", "电源状态", "==", "2", "Питание = DRIVE")
                )),
                actions = ActionDef.listToJson(listOf(
                    ActionDef("主驾座椅通风1档", "Вентиляция водителя 1"),
                    ActionDef("自动空调", "Авто AC")
                )),
                cooldownSeconds = 600
            ),
            RuleEntity(
                name = "Шторка при движении",
                enabled = false,
                triggerLogic = "AND",
                triggers = TriggerDef.listToJson(listOf(
                    TriggerDef("PowerState", "电源状态", "==", "2", "Питание = DRIVE")
                )),
                actions = ActionDef.listToJson(listOf(
                    ActionDef("遮阳帘打开", "Открыть шторку")
                )),
                cooldownSeconds = 600
            ),
            RuleEntity(
                name = "Климат при зарядке",
                enabled = false,
                triggerLogic = "AND",
                triggers = TriggerDef.listToJson(listOf(
                    TriggerDef("ChargingStatus", "充电状态", "==", "2", "Зарядка = Начата"),
                    TriggerDef("ExtTemp", "车外温度", "<", "5", "Темп. снаружи < 5°C")
                )),
                actions = ActionDef.listToJson(listOf(
                    ActionDef("自动空调", "Авто AC"),
                    ActionDef("主驾座椅加热1档", "Подогрев водителя 1")
                )),
                cooldownSeconds = 600
            )
        )

        templates.forEach { ruleDao.insert(it) }
        prefs.edit().putBoolean("templates_inserted", true).apply()
    }
}

// --- Action kind helpers (v2.3.0) ---

fun newNotificationAction(silent: Boolean): ActionDef = ActionDef(
    command = "",
    displayName = if (silent) "Уведомление (без звука)" else "Уведомление (звук)",
    kind = if (silent) "notification_silent" else "notification_sound",
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

fun newAppLaunchAction(): ActionDef = ActionDef(
    command = "",
    displayName = "Запуск приложения",
    kind = "app_launch",
    payload = """{"packageName":"","appLabel":""}"""
)

fun ActionDef.appLaunchPackageName(): String = try {
    org.json.JSONObject(payload ?: "{}").optString("packageName")
} catch (e: Exception) { "" }

fun ActionDef.appLaunchLabel(): String = try {
    org.json.JSONObject(payload ?: "{}").optString("appLabel")
} catch (e: Exception) { "" }

fun ActionDef.withAppLaunch(packageName: String, appLabel: String): ActionDef = copy(
    payload = org.json.JSONObject().apply {
        put("packageName", packageName)
        put("appLabel", appLabel)
    }.toString()
)

// --- Call helpers (v2.3.0) ---

fun newCallAction(): ActionDef = ActionDef(
    command = "",
    displayName = "Звонок",
    kind = "call",
    payload = """{"phone":""}"""
)

fun ActionDef.callPhone(): String = try {
    org.json.JSONObject(payload ?: "{}").optString("phone")
} catch (e: Exception) { "" }

fun ActionDef.withCall(phone: String): ActionDef = copy(
    payload = org.json.JSONObject().apply { put("phone", phone) }.toString()
)

// --- Navigate helpers (v2.3.0) ---

fun newNavigateAction(): ActionDef = ActionDef(
    command = "",
    displayName = "Маршрут в Я.Навигаторе",
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

fun newUrlAction(): ActionDef = ActionDef(
    command = "",
    displayName = "Открыть URL",
    kind = "url",
    payload = """{"url":""}"""
)

fun ActionDef.urlString(): String = try {
    org.json.JSONObject(payload ?: "{}").optString("url")
} catch (e: Exception) { "" }

fun ActionDef.withUrl(url: String): ActionDef = copy(
    payload = org.json.JSONObject().apply { put("url", url) }.toString()
)
