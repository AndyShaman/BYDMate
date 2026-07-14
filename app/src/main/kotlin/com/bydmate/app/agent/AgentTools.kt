package com.bydmate.app.agent

import android.content.Context
import android.content.Intent
import com.bydmate.app.cluster.ClusterMode
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.ActionValidationError
import com.bydmate.app.data.automation.AutomationEngine
import com.bydmate.app.data.automation.ConfirmOverlayManager
import com.bydmate.app.data.automation.DispatchResult
import com.bydmate.app.data.automation.PlaceGeometry
import com.bydmate.app.data.automation.RuleDraftValidator
import com.bydmate.app.data.automation.TriggerValidationError
import com.bydmate.app.data.automation.ScheduleSpec
import com.bydmate.app.data.automation.VoiceFireResult
import com.bydmate.app.data.automation.hhmmToMinute
import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.ChargeEntity
import com.bydmate.app.data.local.entity.PlaceEntity
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.TriggerDef
import com.bydmate.app.data.remote.InsightStatsAggregator
import com.bydmate.app.data.remote.InsightsManager
import com.bydmate.app.data.remote.OpenRouterClient
import com.bydmate.app.data.repository.PlaceRepository
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.vehicle.CommandTranslator
import com.bydmate.app.domain.battery.BatteryStateRepository
import com.bydmate.app.domain.calculator.RangeCalculator
import com.bydmate.app.cluster.SteeringWheelKeyService
import com.bydmate.app.data.camera.CameraStateMonitor
import com.bydmate.app.media.NaviRouteHolder
import com.bydmate.app.media.NaviScreenReader
import com.bydmate.app.service.TrackingService
import com.bydmate.app.ui.automation.OPERATORS
import com.bydmate.app.ui.automation.TRIGGER_PARAMS
import com.bydmate.app.ui.automation.TriggerParamOption
import com.bydmate.app.ui.automation.localizedEnumLabel
import com.bydmate.app.voice.VoiceGate
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Tool registry + executor for the voice agent. Tools are the ONLY surface the
 * LLM sees: reads go through existing repositories, writes go exclusively through
 * ActionDispatcher (kind="param"/"media_volume") so every safety gate applies.
 * Executor results are compact JSON strings fed back as tool-role messages.
 */
@Singleton
class AgentTools @Inject constructor(
    private val gate: VoiceGate,
    private val batteryStateRepository: BatteryStateRepository,
    private val rangeCalculator: RangeCalculator,
    private val tripDao: TripDao,
    private val chargeDao: ChargeDao,
    private val actionDispatcher: ActionDispatcher,
    private val ruleDao: RuleDao,
    private val automationEngine: AutomationEngine,
    private val placeRepository: PlaceRepository,
    private val weatherClient: WeatherClient,
    private val exaSearchClient: ExaSearchClient,
    private val openRouterClient: OpenRouterClient,
    private val settingsRepository: SettingsRepository,
    private val contactLookup: ContactLookup,
    @ApplicationContext private val context: Context,
    private val clusterVoiceControl: ClusterVoiceControl,
    private val chargerSearchClient: ChargerSearchClient,
    private val insightsManager: InsightsManager,
    private val zaiSearchClient: ZaiSearchClient,
    private val llmConnections: LlmConnectionResolver,
) {
    /** Test seam — deterministic time for period queries. */
    internal var nowMs: () -> Long = { System.currentTimeMillis() }

    /** Test seam — overlay confirm gate for dangerous agent actions (П7). */
    internal var confirmGate: (Context, String, String, () -> Unit, () -> Unit) -> Boolean =
        { ctx, ruleName, summary, onConfirm, onCancel ->
            ConfirmOverlayManager.show(ctx, ruleName, summary, onConfirm, onCancel)
        }

    /** Test seam — scope that runs a confirmed dangerous dispatch off the UI thread. */
    internal var confirmScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Test seam — poll interval for re-reading the cluster IPC mode after apply(). */
    internal var clusterPollIntervalMs = 500L

    /** Test seam — poll attempts before giving up (30 x 500ms = 15s worst case). */
    internal var clusterPollAttempts = 30

    /** Test seam — live GPS position, default reads TrackingService's last known fix. */
    internal var locationProvider: () -> Pair<Double, Double>? =
        { TrackingService.lastLocation.value?.let { it.latitude to it.longitude } }

    /** Test seam - launchable apps as label to package pairs. */
    internal var launcherAppsProvider: () -> List<Pair<String, String>> = { queryLauncherApps() }

    /** Test seam - a11y read of the Navigator window; null when it is not on screen. */
    internal var naviScreenProvider: () -> NaviScreenReader.ScreenInfo? = {
        NaviScreenReader.read(SteeringWheelKeyService.instance?.rootInActiveWindow)
    }

    /** Test seam - did the Navigator reach the foreground since [sinceMs]? Combines UsageStats
     *  ACTIVITY_RESUMED with the live a11y window package: Android 12 blocks background activity
     *  starts SILENTLY, so a non-throwing startActivity alone is not proof. */
    internal var naviForegroundCheck: (Long) -> Boolean = { sinceMs ->
        val viaEvents = runCatching {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE)
                as android.app.usage.UsageStatsManager
            CameraStateMonitor.latestResumed(usm, sinceMs, System.currentTimeMillis() + 1)?.first
        }.getOrNull() == NaviRouteHolder.NAVI_PACKAGE
        viaEvents || runCatching {
            SteeringWheelKeyService.instance?.rootInActiveWindow?.packageName?.toString()
        }.getOrNull() == NaviRouteHolder.NAVI_PACKAGE
    }

    /** Test seam - poll interval for the navigate foreground verification. */
    internal var naviVerifyIntervalMs = 500L

    /** Test seam - poll attempts before declaring the Navigator missing (10 x 500ms = 5s). */
    internal var naviVerifyAttempts = 10

    // All agent navigation goes through here: startActivity "success" only means "no exception
    // thrown", Android 12 background-start blocks are silent. Verify the Navigator actually
    // surfaced before letting the LLM claim the route was built.
    private suspend fun dispatchNavigate(displayName: String, payload: JSONObject): DispatchResult {
        val since = System.currentTimeMillis() - 1_000L
        val result = actionDispatcher.dispatch(
            ActionDef(command = "", displayName = displayName, kind = "navigate",
                payload = payload.toString()), data = null)
        if (!result.success) return result
        repeat(naviVerifyAttempts) {
            if (runCatching { naviForegroundCheck(since) }.getOrDefault(false)) return result
            delay(naviVerifyIntervalMs)
        }
        return DispatchResult(false,
            "интент отправлен, но Навигатор не вышел на передний план: маршрут скорее всего " +
                "не построен, предложи пользователю открыть Навигатор вручную")
    }

    private fun queryLauncherApps(): List<Pair<String, String>> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0).map {
            it.loadLabel(pm).toString() to it.activityInfo.packageName
        }
    }

    suspend fun schemas(includeAutomationTools: Boolean = true): JSONArray = JSONArray().apply {
        put(tool(
            "get_vehicle_state",
            "Текущее состояние машины: заряд, запас хода, скорость, температуры, климат, окна, двери, шины, свет, подогрев сидений, GPS-позиция и название сохранённого Места, если машина в нём.",
            JSONObject(), emptyList(),
        ))
        put(tool(
            "query_trips",
            "Статистика поездок за период: количество, километраж, расход энергии, средний расход, стоимость.",
            JSONObject().put("period", periodParam())
                .put("from", JSONObject().put("type", "string")
                    .put("description", "Начало диапазона, дата ГГГГ-ММ-ДД (например 2026-06-01)"))
                .put("to", JSONObject().put("type", "string")
                    .put("description", "Конец диапазона включительно, ГГГГ-ММ-ДД; без to = один день from")),
            emptyList(),
        ))
        put(tool(
            "query_charges",
            "Статистика зарядок за период: сессии, кВт·ч, стоимость, разбивка AC (медленная) / DC (быстрая).",
            JSONObject().put("period", periodParam())
                .put("from", JSONObject().put("type", "string")
                    .put("description", "Начало диапазона, дата ГГГГ-ММ-ДД (например 2026-06-01)"))
                .put("to", JSONObject().put("type", "string")
                    .put("description", "Конец диапазона включительно, ГГГГ-ММ-ДД; без to = один день from")),
            emptyList(),
        ))
        put(tool(
            "add_charge",
            "Записать зарядку вручную в журнал (как ручное добавление на экране Зарядки). " +
                "Либо soc_start+soc_end (кВтч посчитается из ёмкости батареи), либо kwh напрямую. " +
                "Тариф по умолчанию берётся из настроек по типу. Прочитай пользователю итог из ответа.",
            JSONObject()
                .put("type", JSONObject().put("type", "string")
                    .put("enum", JSONArray(listOf("AC", "DC")))
                    .put("description", "AC медленная (дом), DC быстрая станция. По умолчанию AC"))
                .put("soc_start", JSONObject().put("type", "integer")
                    .put("description", "Процент заряда до зарядки, 0..100"))
                .put("soc_end", JSONObject().put("type", "integer")
                    .put("description", "Процент заряда после зарядки, 0..100, больше soc_start"))
                .put("kwh", JSONObject().put("type", "number")
                    .put("description", "Сколько кВтч залито, если SOC неизвестен"))
                .put("tariff", JSONObject().put("type", "number")
                    .put("description", "Цена за кВтч, если пользователь назвал; иначе из настроек"))
                .put("date", JSONObject().put("type", "string")
                    .put("description", "Дата зарядки ГГГГ-ММ-ДД, если не сегодня")),
            emptyList(),
        ))
        put(tool(
            "get_stats_summary",
            "Сводка динамики: расход, поездки, короткие поездки, средняя дистанция, расход на " +
                "стоянке. Текущий период против предыдущего. Озвучь коротко главное.",
            JSONObject().put("period", JSONObject().put("type", "string")
                .put("enum", JSONArray(listOf("week", "month")))
                .put("description", "week = 7 дней (по умолчанию), month = 30 дней")),
            emptyList(),
        ))
        put(tool(
            "vehicle_control",
            "Выполнить команду управления машиной. command — идентификатор из списка:\n" +
                AgentCommandCatalog.idsDoc(),
            JSONObject()
                .put("command", JSONObject()
                    .put("type", "string")
                    .put("enum", JSONArray(AgentCommandCatalog.ALL.map { it.id }))
                    .put("description", "Идентификатор команды из списка"))
                .put("value", JSONObject()
                    .put("type", "integer")
                    .put("description", "Значение для команд с value, например температура")),
            listOf("command"),
        ))
        put(tool(
            "media_volume",
            "Громкость медиа: op = число (уровень), \"+N\"/\"-N\" (шаг), \"mute\", \"unmute\".",
            JSONObject().put("op", JSONObject().put("type", "string")),
            listOf("op"),
        ))
        put(tool(
            "get_weather",
            "Погода сейчас и прогноз на 3 дня. city — только если пользователь явно спросил про другое место, " +
                "иначе берётся текущая GPS-позиция машины.",
            JSONObject().put("city", JSONObject()
                .put("type", "string")
                .put("description", "Город, если вопрос не про текущее местоположение машины")),
            emptyList(),
        ))
        put(tool(
            "call_contact",
            "Позвонить контакту из телефонной книги по имени. Если совпадений несколько, " +
                "задай ровно один уточняющий вопрос, какой контакт нужен.",
            JSONObject().put("name", JSONObject()
                .put("type", "string")
                .put("description", "Имя контакта, как записано в телефонной книге")),
            listOf("name"),
        ))
        // Always declared: with no Exa key the execute path below falls back to the primary
        // connection's native search (openrouter:web_search server tool or z.ai web_search).
        put(tool(
            "web_search",
            "Поиск в интернете по актуальным вопросам, которых нет в других инструментах.",
            JSONObject().put("query", JSONObject()
                .put("type", "string")
                .put("description", "Поисковый запрос")),
            listOf("query"),
        ))
        if (includeAutomationTools) {
            val ruleNames = runCatchingCancellable { ruleDao.getEnabled() }.getOrDefault(emptyList()).map { it.name }
            put(tool(
                "run_automation",
                if (ruleNames.isEmpty()) "Запустить сохранённую автоматизацию по имени. Сейчас включённых автоматизаций нет."
                else "Запустить сохранённую автоматизацию пользователя по имени.",
                JSONObject().put("name", JSONObject().apply {
                    put("type", "string")
                    if (ruleNames.isNotEmpty()) put("enum", JSONArray(ruleNames))
                }),
                listOf("name"),
            ))
        }
        put(tool(
            "list_automations",
            "Список всех сохранённых автоматизаций пользователя (включая выключенные) с их триггерами и действиями.",
            JSONObject(), emptyList(),
        ))
        if (includeAutomationTools) {
            put(tool(
                "set_automation_enabled",
                "Включить или выключить сохранённую автоматизацию по имени.",
                JSONObject()
                    .put("name", JSONObject().put("type", "string").put("description", "Имя автоматизации"))
                    .put("enabled", JSONObject().put("type", "boolean").put("description", "true = включить, false = выключить")),
                listOf("name", "enabled"),
            ))
        }
        put(tool(
            "list_places",
            "Список сохранённых Мест (дом, работа и т.п.): имя, координаты, радиус в метрах. " +
                "Места используются в триггерах place_enter/place_exit и в get_vehicle_state.",
            JSONObject(), emptyList(),
        ))
        put(tool(
            "create_place",
            "Сохранить новое Место. Без lat/lon берётся текущая GPS-позиция машины " +
                "(подходит для \"запомни это место как Дом\").",
            JSONObject()
                .put("name", JSONObject().put("type", "string")
                    .put("description", "Имя Места, уникальное, до 40 символов"))
                .put("lat", JSONObject().put("type", "number")
                    .put("description", "Широта; вместе с lon, иначе GPS"))
                .put("lon", JSONObject().put("type", "number")
                    .put("description", "Долгота; вместе с lat, иначе GPS"))
                .put("radius_m", JSONObject().put("type", "integer")
                    .put("description", "Радиус в метрах, 20..500, по умолчанию 100")),
            listOf("name"),
        ))
        put(tool(
            "navigate_to",
            "Построить маршрут в Навигаторе от текущей позиции. Команды поехали домой, до дома, " +
                "на работу - ЭТОТ инструмент: передай destination \"Дом\" или \"Работа\", маршрут " +
                "построится по Месту BYDMate или по адресу, сохранённому в самом Навигаторе. " +
                "destination: имя сохранённого Места, город или населённый пункт (маршрут строится " +
                "сразу) либо точный адрес (откроется поиск на карте, пользователь выберет точку). " +
                "Ответ может содержать проверку запаса хода: если enough=false или есть charge_note, " +
                "предупреди пользователя и предложи find_chargers. Для поиска места без маршрута " +
                "(поищи, найди) используй search_on_map.",
            JSONObject().put("destination", JSONObject().put("type", "string")
                .put("description", "Куда ехать: имя Места, адрес или название"))
                .put("lat", JSONObject().put("type", "number")
                    .put("description", "Широта точки, если известны точные координаты (например из find_chargers)"))
                .put("lon", JSONObject().put("type", "number")
                    .put("description", "Долгота точки")),
            emptyList(),
        ))
        put(tool(
            "search_on_map",
            "Открыть поиск места в Яндекс Навигаторе: на карте появится выдача, пользователь сам " +
                "выберет точку. Использовать для команд вроде: поищи кафе, найди заправку, " +
                "где ближайшая аптека. Если пользователь просит ПОЕХАТЬ куда-то, в том числе " +
                "домой или на работу, используй navigate_to.",
            JSONObject().put("query", JSONObject().put("type", "string")
                .put("description", "Что искать: название места или категория")),
            listOf("query"),
        ))
        put(tool(
            "show_point_on_map",
            "Показать точку на карте Навигатора БЕЗ построения маршрута: команды вроде " +
                "покажи на карте, где находится. destination: имя Места, город или адрес; " +
                "либо lat/lon, если координаты известны (например из find_chargers). " +
                "Для маршрута используй navigate_to, для поиска по категории search_on_map.",
            JSONObject().put("destination", JSONObject().put("type", "string")
                .put("description", "Что показать: имя Места, адрес или название"))
                .put("lat", JSONObject().put("type", "number").put("description", "Широта, если известна"))
                .put("lon", JSONObject().put("type", "number").put("description", "Долгота")),
            emptyList(),
        ))
        put(tool(
            "find_chargers",
            "Найти электрозарядные станции рядом (данные OpenStreetMap). Без city ищет вокруг " +
                "текущей позиции машины. Верни пользователю ближайшие варианты с расстоянием; " +
                "когда он выберет, вызови navigate_to с lat и lon выбранной станции.",
            JSONObject()
                .put("city", JSONObject().put("type", "string")
                    .put("description", "Город/точка поиска, если не вокруг машины"))
                .put("radius_km", JSONObject().put("type", "integer")
                    .put("description", "Радиус поиска в км, по умолчанию 30, максимум 100")),
            emptyList(),
        ))
        put(tool(
            "range_to_destination",
            "Хватит ли текущего заряда доехать до точки: расстояние до неё против запаса хода. " +
                "Расстояние оценивается по прямой с дорожным коэффициентом, поэтому примерное. " +
                "Если reserve_km меньше 20% расстояния, предупреди что впритык и предложи зарядку.",
            JSONObject().put("destination", JSONObject().put("type", "string")
                .put("description", "Куда ехать: сохранённое Место или город/населённый пункт")),
            listOf("destination"),
        ))
        put(tool(
            "get_route_info",
            "Состояние ведения Яндекс Навигатора: следующий манёвр (maneuver, maneuver_distance, " +
                "street), строки уведомления route_lines (обычно остаток пути и время прибытия), " +
                "лимит скорости speed_limit и номер съезда exit_number (только когда Навигатор " +
                "на экране), заряд soc и запас хода range_km. Отвечай по этим полям на вопросы " +
                "сколько ехать, какой поворот, когда приедем, хватит ли заряда. Если поля нет " +
                "в ответе, этих данных сейчас нет - скажи честно.",
            JSONObject(), emptyList(),
        ))
        put(tool(
            "go_home",
            "Свернуть все окна и показать домашний экран (рабочий стол) машины. Использовать для " +
                "команд вроде: домой на экран, на рабочий стол, сверни всё.",
            JSONObject(), emptyList(),
        ))
        put(tool(
            "play_music",
            "Включить музыку в Яндекс Музыке. Без query играет персональная подборка (Моя волна). " +
                "С query ищет и включает трек, альбом или исполнителя.",
            JSONObject().put("query", JSONObject().put("type", "string")
                .put("description", "Трек, альбом или исполнитель; не передавать для Моей волны"))
                .put("mode", JSONObject().put("type", "string")
                    .put("enum", JSONArray(listOf("play", "search")))
                    .put("description", "play = сразу включить лучшее совпадение (\"включи/поставь X\"), " +
                        "search = только открыть поиск (\"найди X\"). По умолчанию play.")),
            emptyList(),
        ))
        put(tool(
            "youtube",
            "YouTube: включить видео по запросу или открыть поиск. \"включи на ютубе X\" = mode " +
                "play (сразу запускает лучшее совпадение), \"найди на ютубе X\" = mode search. " +
                "Для простого запуска приложения без запроса используй launch_app.",
            JSONObject()
                .put("query", JSONObject().put("type", "string")
                    .put("description", "Что искать: название видео, канала или тема"))
                .put("mode", JSONObject().put("type", "string")
                    .put("enum", JSONArray(listOf("play", "search")))
                    .put("description", "play = включить, search = показать результаты. По умолчанию play.")),
            listOf("query"),
        ))
        put(tool(
            "launch_app",
            "Запустить установленное приложение по названию. Понимает русские названия штатных " +
                "приложений машины: навигатор, музыка, камера, видеорегистратор, браузер, ютуб, " +
                "настройки машины, файлы, режимы вождения, часовой, АБРП.",
            JSONObject().put("name", JSONObject().put("type", "string")
                .put("description", "Название приложения, как на домашнем экране")),
            listOf("name"),
        ))
        put(tool(
            "set_cluster_projection",
            "Показать или убрать выбранное приложение (обычно Навигатор) на приборной панели перед " +
                "рулём, как кнопка-звезда на руле. То же самое: \"пусти навигатор на приборку\" = on; " +
                "\"убери с приборки\", \"верни навигатор на основной экран\", \"верни на большой " +
                "экран\" = off (убрать с приборки и вернуть на основной экран - одно действие).",
            JSONObject().put("on", JSONObject().put("type", "boolean")
                .put("description", "true = показать карту на приборке, false = убрать")),
            listOf("on"),
        ))
        put(tool(
            "set_sentry",
            "Включить или выключить охранный режим (Sentry, караульный режим) — камеры следят за " +
                "обстановкой вокруг припаркованной машины. \"поставь на охрану\", \"включи охрану\" = on; " +
                "\"сними с охраны\", \"выключи охрану\" = off.",
            JSONObject().put("on", JSONObject().put("type", "boolean")
                .put("description", "true = включить охранный режим, false = выключить")),
            listOf("on"),
        ))
        if (includeAutomationTools) {
            put(tool(
                "create_automation",
                "Создать новую автоматизацию: \"если <триггер>, то <действия>\". Один триггер, одно или " +
                    "несколько действий. Триггеры: точное время и расписание (kind=time_range, value=\"HH:MM-HH:MM\", " +
                    "одинаковые начало и конец = сработать ровно в это время, weekdays = дни недели; пример: " +
                    "\"каждый будний день в 8:00\" -> value=\"08:00-08:00\", weekdays=[1,2,3,4,5]), " +
                    "голосовая фраза (kind=voice, поле phrase), кнопка виджета (kind=button_press, поле button), " +
                    "параметр машины (kind=param), въезд/выезд из места, время суток (kind=time_of_day).",
                JSONObject()
                    .put("name", JSONObject().put("type", "string")
                        .put("description", "Имя автоматизации, должно быть уникальным"))
                    .put("trigger", JSONObject()
                        .put("type", "object")
                        .put("description", "Условие срабатывания, ровно один триггер")
                        .put("properties", JSONObject()
                            .put("kind", JSONObject()
                                .put("type", "string")
                                .put("enum", JSONArray(listOf(
                                    "param", "place_enter", "place_exit", "time_of_day",
                                    "time_range", "service_start", "network_available",
                                    "voice", "button_press")))
                                .put("description", "Тип триггера"))
                            .put("param", JSONObject()
                                .put("type", "string")
                                .put("enum", JSONArray(TRIGGER_PARAMS.map { it.param }))
                                .put("description", "Только для kind=param: параметр машины"))
                            .put("operator", JSONObject()
                                .put("type", "string")
                                .put("enum", JSONArray(OPERATORS))
                                .put("description", "Только для kind=param: оператор сравнения"))
                            .put("value", JSONObject()
                                .put("description", "Только для kind=param: пороговое значение. Числовые параметры " +
                                    "(скорость, SOC, температуры, давление, проценты) — число строкой. " +
                                    "Параметры-переключатели принимают код ИЛИ название, лучше код: ${enumValueGuide()}. " +
                                    "ACStatus/FanLevel — числом (ACStatus 1=вкл, 0=выкл). " +
                                    "Для kind=time_of_day: dawn/day/dusk/night; для kind=time_range: \"HH:MM-HH:MM\" " +
                                    "(одинаковые начало и конец = сработать ровно в это время)")
                                .put("anyOf", JSONArray(listOf(
                                    JSONObject().put("type", "string"),
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray(listOf("dawn", "day", "dusk", "night")))
                                        .put("description", "Для kind=time_of_day: время суток"),
                                ))))
                            .put("place_name", JSONObject()
                                .put("type", "string")
                                .put("description", "Только для kind=place_enter/place_exit: имя сохранённого места"))
                            .put("phrase", JSONObject().put("type", "string")
                                .put("description", "Только для kind=voice: фраза, после которой сработает правило"))
                            .put("button", JSONObject().put("type", "integer")
                                .put("description", "Только для kind=button_press: номер кнопки виджета, 1-4"))
                            .put("weekdays", JSONObject().put("type", "array")
                                .put("items", JSONObject().put("type", "integer"))
                                .put("description", "Только для kind=time_range: дни недели, 1=понедельник .. 7=воскресенье. Не указано = каждый день")))
                        .put("required", JSONArray(listOf("kind"))))
                    .put("actions", JSONObject()
                        .put("type", "array")
                        .put("description", "Список действий, минимум одно")
                        .put("items", JSONObject()
                            .put("type", "object")
                            .put("properties", JSONObject()
                                .put("kind", JSONObject()
                                    .put("type", "string")
                                    .put("enum", JSONArray(listOf(
                                        "param", "delay", "media_volume", "notification",
                                        "call", "navigate", "url",
                                        "yandex_music", "sentry", "app_launch",
                                        "cluster_projection", "speak", "agent_query")))
                                    .put("description", "Тип действия"))
                                .put("command_id", JSONObject()
                                    .put("type", "string")
                                    .put("enum", JSONArray(AgentCommandCatalog.ALL.map { it.id }))
                                    .put("description", "Только для kind=param: идентификатор команды"))
                                .put("value", JSONObject().put("type", "integer")
                                    .put("description", "Только для kind=param: значение, если команда его требует"))
                                .put("ms", JSONObject().put("type", "integer")
                                    .put("description", "Только для kind=delay: пауза в мс, 0..30000"))
                                .put("op", JSONObject().put("type", "string")
                                    .put("description", "Только для kind=media_volume: число / \"+N\" / \"-N\" / mute / unmute"))
                                .put("title", JSONObject().put("type", "string")
                                    .put("description", "Только для kind=notification: заголовок"))
                                .put("text", JSONObject().put("type", "string")
                                    .put("description", "Для kind=notification: текст уведомления. Для kind=speak: текст, который машина озвучит"))
                                .put("phone", JSONObject().put("type", "string")
                                    .put("description", "Только для kind=call: номер телефона"))
                                .put("lat", JSONObject().put("type", "number")
                                    .put("description", "Только для kind=navigate: широта"))
                                .put("lon", JSONObject().put("type", "number")
                                    .put("description", "Только для kind=navigate: долгота"))
                                .put("url", JSONObject().put("type", "string")
                                    .put("description", "Только для kind=url: ссылка со схемой, например https://"))
                                .put("mode", JSONObject().put("type", "string")
                                    .put("description", "Только для kind=yandex_music: режим, например mybeat"))
                                .put("on", JSONObject().put("type", "boolean")
                                    .put("description", "Для kind=sentry: включить/выключить охрану. Для kind=cluster_projection: true = вывести проекцию на приборку, false = убрать"))
                                .put("app", JSONObject().put("type", "string")
                                    .put("description", "Только для kind=app_launch: название приложения, как на домашнем экране"))
                                .put("prompt", JSONObject().put("type", "string")
                                    .put("description", "Только для kind=agent_query: запрос агенту, ответ будет озвучен")))
                            .put("required", JSONArray(listOf("kind")))))
                    .put("cooldown_seconds", JSONObject()
                        .put("type", "integer")
                        .put("description", "Минимальный интервал между срабатываниями в секундах, минимум 30, по умолчанию 60"))
                    .put("play_sound", JSONObject()
                        .put("type", "boolean")
                        .put("description", "Проиграть звуковой сигнал при срабатывании правила, по умолчанию false")),
                listOf("name", "trigger", "actions"),
            ))
        }
    }

    // runCatching swallows CancellationException (it is an Exception subtype), which would turn
    // a PTT-stop cancellation mid-suspend-call (e.g. ruleDao.getEnabled(), findByName()) into a
    // caught failure instead of letting it unwind to execute()'s top-level rethrow. Use this in
    // place of runCatching everywhere in this file so cancellation still propagates.
    private inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Throwable, not Exception: keeps runCatching's scope so device-only Errors
            // (e.g. NoSuchMethodError absent from JVM tests) still map to the site's
            // Russian error instead of escaping the tool call.
            Result.failure(e)
        }

    suspend fun execute(call: AgentToolCall, allowAutomationTools: Boolean = true): String {
        val args = runCatchingCancellable { JSONObject(call.arguments.ifBlank { "{}" }) }
            .getOrElse { return BAD_ARGS_ERROR }
        // Last-resort net: every branch already has its own runCatching guards for specific
        // Russian messages, but a future/overlooked throw must still never escape as a raw
        // exception. CancellationException must be rethrown, not swallowed here, or a PTT-stop
        // mid-tool-call would silently turn into an error JSON instead of cancelling the session.
        return try {
            if (!allowAutomationTools && call.name in AUTOMATION_TOOLS) {
                return """{"error":"инструмент ${call.name} недоступен в сессии, запущенной автоматизацией"}"""
            }
            when (call.name) {
                "get_vehicle_state" -> vehicleState()
                "get_weather" -> getWeather(args)
                "call_contact" -> callContact(args)
                "web_search" -> webSearch(args)
                "query_trips" -> queryTrips(args)
                "query_charges" -> queryCharges(args)
                "add_charge" -> addCharge(args)
                "get_stats_summary" -> statsSummary(args)
                "vehicle_control" -> vehicleControl(args)
                "media_volume" -> mediaVolume(args)
                "run_automation" -> runAutomation(args)
                "list_automations" -> listAutomations()
                "list_places" -> listPlaces()
                "create_place" -> createPlace(args)
                "navigate_to" -> navigateTo(args)
                "search_on_map" -> searchOnMap(args)
                "show_point_on_map" -> showPointOnMap(args)
                "find_chargers" -> findChargers(args)
                "range_to_destination" -> rangeToDestination(args)
                "get_route_info" -> routeInfo()
                "go_home" -> goHomeScreen()
                "play_music" -> playMusic(args)
                "youtube" -> youtubeTool(args)
                "launch_app" -> launchAppTool(args)
                "set_cluster_projection" -> setClusterProjection(args)
                "set_sentry" -> setSentry(args)
                "set_automation_enabled" -> setAutomationEnabled(args)
                "create_automation" -> createAutomation(args)
                else -> """{"error":"неизвестный инструмент ${call.name}"}"""
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            INTERNAL_ERROR
        }
    }

    // --- get_vehicle_state ---

    private suspend fun vehicleState(): String {
        val d = gate.vehicleSnapshot()
            ?: return """{"error":"нет данных с машины (нет связи или машина спит)"}"""
        val o = JSONObject()
        fun putIf(key: String, v: Any?) { if (v != null) o.put(key, v) }
        putIf("soc_percent", d.soc)
        putIf("speed_kmh", d.speed)
        putIf("power_kw", d.power)
        putIf("gear", when (d.gear) { 1 -> "P"; 2 -> "R"; 3 -> "N"; 4 -> "D"; else -> null })
        putIf("mileage_km", d.mileage)
        putIf("inside_temp_c", d.insideTemp)
        putIf("outside_temp_c", d.exteriorTemp)
        putIf("ac_on", d.acStatus?.let { it == 1 })
        putIf("ac_temp_c", d.acTemp)
        putIf("fan_level", d.fanLevel)
        putIf("recirculation_inner", d.acCirc?.let { it == 1 })
        putIf("defrost_front_on", d.acDefrostFront?.let { it == 1 })
        putIf("ac_auto_mode", d.acCtrlMode?.let { it == 0 })
        putIf("seat_heat_driver_level", d.seatHeatDriver)
        putIf("seat_vent_driver_level", d.seatVentDriver)
        putIf("seat_heat_passenger_level", d.seatHeatPassenger)
        putIf("seat_vent_passenger_level", d.seatVentPassenger)
        putIf("window_front_left_pct", d.windowFL)
        putIf("window_front_right_pct", d.windowFR)
        putIf("window_rear_left_pct", d.windowRL)
        putIf("window_rear_right_pct", d.windowRR)
        putIf("sunroof_pct", d.sunroof)
        putIf("trunk_open", d.trunk?.let { it == 1 })
        putIf("hood_open", d.hood?.let { it == 1 })
        putIf("locked", d.lockFL?.let { it == 2 })
        // Absent door sensors -> omit the key entirely: a hardcoded 0 would read as
        // "all doors closed" while the truth is "unknown" (e.g. reduced D+ payload).
        val doorStates = listOfNotNull(d.doorFL, d.doorFR, d.doorRL, d.doorRR)
        if (doorStates.isNotEmpty()) o.put("doors_open", doorStates.count { it == 1 })
        putIf("tire_press_fl_kpa", d.tirePressFL)
        putIf("tire_press_fr_kpa", d.tirePressFR)
        putIf("tire_press_rl_kpa", d.tirePressRL)
        putIf("tire_press_rr_kpa", d.tirePressRR)
        // Deterministic low-pressure flag so the model reliably warns by voice.
        // 210 kPa is ~16% below the Leopard 3 cold placard (~250 kPa).
        val tirePressures = listOfNotNull(d.tirePressFL, d.tirePressFR, d.tirePressRL, d.tirePressRR)
        if (tirePressures.isNotEmpty() && tirePressures.any { it < TIRE_WARN_MIN_KPA }) {
            o.put("tire_pressure_warning",
                "давление в одном из колёс ниже нормы, предупреди водителя")
        }
        putIf("voltage_12v", d.voltage12v)
        putIf("battery_temp_avg_c", d.avgBatTemp)
        putIf("battery_temp_max_c", d.maxBatTemp)
        putIf("battery_temp_min_c", d.minBatTemp)
        putIf("cell_voltage_min_v", d.minCellVoltage)
        putIf("cell_voltage_max_v", d.maxCellVoltage)
        // Cell delta in millivolts -- the unit battery diagnostics are discussed in.
        if (d.minCellVoltage != null && d.maxCellVoltage != null) {
            o.put("cell_voltage_delta_mv", ((d.maxCellVoltage - d.minCellVoltage) * 1000).roundToInt())
        }
        // Gun fid: 1=NONE, 2=AC, 3=DC, 4=AC_DC, 5=VTOL -- NONE is 1, not 0.
        putIf("charging_gun_connected", d.chargeGunState?.let { it >= 2 })
        putIf("drive_mode", when (d.driveMode) { 1 -> "ECO"; 2 -> "SPORT"; else -> null })
        putIf("power_state", when (d.powerState) { 0 -> "OFF"; 1 -> "ON"; 2 -> "DRIVE"; else -> null })
        putIf("work_mode", when (d.workMode) { 0 -> "STOP"; 1 -> "EV"; 2 -> "FORCED_EV"; 3 -> "HEV"; else -> null })
        putIf("light_low_beam_on", d.lightLow?.let { it == 1 })
        putIf("light_high_beam_on", d.lightHigh?.let { it == 1 })
        putIf("light_side_on", d.lightSide?.let { it == 1 })
        // DRL fid: 1=ON, 2=OFF, 0=invalid -- not a plain boolean.
        putIf("light_drl_on", when (d.drl) { 1 -> true; 2 -> false; else -> null })
        // Cabin sensors (sensors wave). Absent sensor -> omit the key: null must
        // read as "unknown", not as a fabricated false.
        putIf("seatbelt_driver_fastened", d.seatbeltFL?.let { it == 1 })
        putIf("seatbelt_front_passenger_fastened", d.seatbeltFR?.let { it == 1 })
        // Occupancy raw codes: 1=free, 2=occupied.
        putIf("seat_occupied_driver", d.occupancyFL?.let { it == 2 })
        putIf("seat_occupied_front_passenger", d.occupancyFR?.let { it == 2 })
        putIf("seat_occupied_rear_left", d.occupancyRL?.let { it == 2 })
        putIf("seat_occupied_rear_middle", d.occupancyRM?.let { it == 2 })
        putIf("seat_occupied_rear_right", d.occupancyRR?.let { it == 2 })
        putIf("ambient_light_level_1dark_5bright", d.lightLevel)
        putIf("key_fob_battery_ok", d.keyBatteryStatus?.let { it == 0 })
        putIf("rain_detected", d.rain?.let { it == 1 })
        // Deterministic belt flag (same pattern as tire_pressure_warning): moving with
        // the driver unbuckled, or an occupied front passenger seat unbuckled.
        val moving = (d.speed ?: 0) > 0
        val driverUnbuckled = d.seatbeltFL == 0
        val passengerUnbuckled = d.occupancyFR == 2 && d.seatbeltFR == 0
        if (moving && (driverUnbuckled || passengerUnbuckled)) {
            o.put("seatbelt_warning", "кто-то не пристёгнут при движении, предупреди водителя")
        }
        locationProvider()?.let { (lat, lon) ->
            o.put("gps_lat", lat)
            o.put("gps_lon", lon)
            runCatchingCancellable { placeRepository.getAllSnapshot() }.getOrNull()
                ?.firstOrNull { PlaceGeometry.isInside(lat, lon, it.lat, it.lon, it.radiusM) }
                ?.let { p -> o.put("place", p.name) }
        }
        runCatchingCancellable { batteryStateRepository.refresh() }.getOrNull()?.let { b ->
            // Float -> Double: Android org.json has no put(String, float) overload.
            putIf("soh_percent", b.sohPercent?.toDouble())
            putIf("lifetime_km", b.lifetimeKm?.toDouble())
            putIf("lifetime_kwh", b.lifetimeKwh?.toDouble())
        }
        runCatchingCancellable { rangeCalculator.estimate(d.soc, d.totalElecConsumption) }.getOrNull()?.let {
            o.put("range_km", it.roundToInt())
        }
        return o.toString()
    }

    // --- get_weather ---

    private suspend fun getWeather(args: JSONObject): String {
        val city = args.optString("city").trim().ifBlank { null }
        val lat: Double
        val lon: Double
        // Name of the place the forecast is for: the geocoder's name on the city path, the
        // saved Place on the GPS path (Open-Meteo has no reverse geocoding), null if neither.
        val location: String?
        if (city != null) {
            val geo = weatherClient.geocode(city).getOrElse { return weatherErrorJson(it) }
            lat = geo.lat
            lon = geo.lon
            location = geo.name.ifBlank { null }
        } else {
            val gps = locationProvider() ?: return """{"error":"нет GPS и не указан город"}"""
            lat = gps.first
            lon = gps.second
            location = placeNameAt(lat, lon)
        }
        val forecast = weatherClient.forecast(lat, lon).getOrElse { return weatherErrorJson(it) }
        if (location == null) return forecast
        // location is a String, so no put(String, float) hazard here.
        return JSONObject(forecast).put("location", location).toString()
    }

    // Same saved-place lookup as get_vehicle_state: first Place whose radius contains the fix,
    // exception-safe so a DAO failure just omits the name instead of failing the weather call.
    private suspend fun placeNameAt(lat: Double, lon: Double): String? =
        runCatchingCancellable { placeRepository.getAllSnapshot() }.getOrNull()
            ?.firstOrNull { PlaceGeometry.isInside(lat, lon, it.lat, it.lon, it.radiusM) }
            ?.name

    // Raw Throwable messages are English ("timeout", "No value for current") and must not leak
    // into the error JSON the LLM reads back to the driver; only WeatherClient.UserError carries
    // deliberately user-facing Russian text.
    private fun weatherErrorJson(e: Throwable): String {
        val msg = (e as? WeatherClient.UserError)?.message ?: "погода недоступна"
        return JSONObject().put("error", msg).toString()
    }

    // --- call_contact ---

    // Phone numbers never appear in the tool response: the single-match branch reports
    // only the contact's name, and the ambiguous branch reports only candidate names.
    private suspend fun callContact(args: JSONObject): String {
        val name = args.optString("name").trim()
        if (name.isEmpty()) return """{"error":"не указано имя"}"""
        val hasPermission = runCatchingCancellable { contactLookup.hasPermission() }.getOrDefault(false)
        if (!hasPermission) return """{"error":"нет доступа к контактам, включите в Настройках"}"""
        val matches = runCatchingCancellable { contactLookup.findByName(name) }
            .getOrElse { return """{"error":"не удалось прочитать контакты"}""" }
        return when {
            matches.isEmpty() -> """{"error":"контакт не найден"}"""
            matches.size == 1 -> {
                val m = matches.first()
                val action = ActionDef(
                    command = "", displayName = m.name, kind = "call",
                    payload = JSONObject().put("phone", m.phone).put("autoDial", true).toString(),
                )
                return confirmDangerous(action, "Звонок: ${m.name}")
            }
            else -> JSONObject().put("matches", JSONArray(matches.map { it.name })).toString()
        }
    }

    // --- web_search ---

    // Exa failure falls through to the primary connection's native search; only when the
    // whole chain fails does it collapse to the fixed Russian string: raw Throwable
    // messages from search engines are English and must never reach the LLM.
    private suspend fun webSearch(args: JSONObject): String {
        val query = args.optString("query").trim()
        if (query.isEmpty()) return """{"error":"не указан запрос"}"""
        val exaKey = runCatchingCancellable {
            settingsRepository.getString(SettingsRepository.KEY_EXA_API_KEY, "")
        }.getOrDefault("")
        if (exaKey.isNotBlank()) {
            exaSearchClient.search(exaKey, query).onSuccess { return it }
            // Exa failed: fall through to the primary connection's native search.
        }
        return nativeSearch(query)
    }

    /** Native search of the PRIMARY connection; custom connections have none. */
    private suspend fun nativeSearch(query: String): String {
        val conn = runCatchingCancellable { llmConnections.primary() }.getOrNull() ?: return SEARCH_ERROR
        return when (conn.id) {
            LlmConnectionResolver.ID_OPENROUTER -> openRouterWebSearch(conn, query)
            LlmConnectionResolver.ID_ZAI ->
                zaiSearchClient.search(conn.apiKey, query).getOrElse { SEARCH_ERROR }
            else -> SEARCH_ERROR
        }
    }

    /** One-shot completion with the openrouter:web_search server tool doing the search server-side. */
    private suspend fun openRouterWebSearch(conn: LlmConnection, query: String): String {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content",
                "Найди в интернете актуальную информацию по запросу пользователя и изложи факты кратко, по-русски, с датами и источниками."))
            .put(JSONObject().put("role", "user").put("content", query))
        val tools = JSONArray().put(JSONObject().put("type", "openrouter:web_search"))
        val message = openRouterClient.chatRaw(conn.baseUrl, conn.apiKey, conn.model, messages, tools)
            .getOrElse { return SEARCH_ERROR }
        val content = if (message.isNull("content")) null
            else message.optString("content").takeIf { it.isNotBlank() }
        return content ?: SEARCH_ERROR
    }

    // --- query_trips / query_charges ---

    /** Absolute range from "from"/"to" (YYYY-MM-DD, device timezone, "to" inclusive) when given;
     *  otherwise the legacy relative "period" (day/week/month back from now). Null = bad args. */
    private fun periodRange(args: JSONObject): Pair<Long, Long>? {
        val fromStr = args.optString("from").trim()
        if (fromStr.isNotEmpty()) {
            // SimpleDateFormat.parse consumes a prefix and ignores trailing garbage even with
            // isLenient=false ("2026-06-01xyz" parses fine) -- require the exact shape first.
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }
            fun parseDay(s: String): Long? =
                if (!s.matches(Regex("""\d{4}-\d{2}-\d{2}"""))) null
                else runCatching { fmt.parse(s)!!.time }.getOrNull()
            val from = parseDay(fromStr) ?: return null
            val toStr = args.optString("to").trim()
            val toDay = if (toStr.isEmpty()) from else (parseDay(toStr) ?: return null)
            if (toDay < from) return null
            // Half-open day boundary (AC-14): DAO compares start_ts <= :to, so stop
            // 1 ms short of next-day midnight — an exactly-00:00 record belongs to
            // the NEXT day, not this period.
            return from to toDay + DAY_MS - 1
        }
        val now = nowMs()
        val from = when (args.optString("period")) {
            "day" -> now - DAY_MS
            "week" -> now - 7 * DAY_MS
            "month" -> now - 30 * DAY_MS
            else -> return null
        }
        return from to now
    }

    private suspend fun queryTrips(args: JSONObject): String {
        val (from, to) = periodRange(args)
            ?: return PERIOD_ERROR
        val s = tripDao.getPeriodSummary(from, to)
        return JSONObject().apply {
            put("trips", s.tripCount)
            put("distance_km", round1(s.totalKm))
            put("energy_kwh", round1(s.totalKwh))
            if (s.totalKm > 0.5) put("avg_kwh_per_100km", round1(s.totalKwh / s.totalKm * 100))
            put("cost", round1(s.totalCost))
        }.toString()
    }

    private suspend fun queryCharges(args: JSONObject): String {
        val (from, to) = periodRange(args)
            ?: return PERIOD_ERROR
        val summary = chargeDao.getPeriodSummary(from, to)
        val split = InsightStatsAggregator.chargingWeek(
            // <= to matches getPeriodSummary's inclusive upper bound so the AC/DC split always
            // sums to the top-level totals, including a charge starting exactly at the boundary.
            chargeDao.getCompletedSince(from).filter { it.startTs <= to })
        return JSONObject().apply {
            put("sessions", summary.sessionCount)
            put("energy_kwh", round1(summary.totalKwh))
            put("cost", round1(summary.totalCost))
            put("ac_kwh", round1(split.acKwh))
            put("dc_kwh", round1(split.dcKwh))
            put("ac_sessions", split.acSessions)
            put("dc_sessions", split.dcSessions)
        }.toString()
    }

    // Mirrors the manual add path (ChargesViewModel.onCreateNewCharge + ChargeEditDialog):
    // kWh derived from SOC delta x battery capacity when both SOC given, otherwise explicit
    // kwh; cost = kWh x tariff (settings default by type unless spoken). detectionSource
    // stays "manual" so every existing screen/stat treats it like a hand-entered record.
    private suspend fun addCharge(args: JSONObject): String {
        val type = args.optString("type", "AC").uppercase(Locale.US)
        if (type != "AC" && type != "DC") return BAD_ARGS_ERROR
        val socStart = if (args.has("soc_start")) args.optInt("soc_start", -1) else null
        val socEnd = if (args.has("soc_end")) args.optInt("soc_end", -1) else null
        if (socStart != null && socStart !in 0..100) return BAD_ARGS_ERROR
        if (socEnd != null && socEnd !in 0..100) return BAD_ARGS_ERROR
        if (socStart != null && socEnd != null && socEnd <= socStart) {
            return """{"error":"конечный процент должен быть больше начального"}"""
        }

        val kwhFromSoc = if (socStart != null && socEnd != null) {
            val capacity = runCatchingCancellable { settingsRepository.getBatteryCapacity() }
                .getOrDefault(72.9)
            (socEnd - socStart) / 100.0 * capacity
        } else null
        val kwhManual = if (args.has("kwh")) args.optDouble("kwh").takeIf { it > 0.0 } else null
        val kwh = kwhFromSoc ?: kwhManual
            ?: return """{"error":"нужен либо процент заряда с и по, либо количество кВтч"}"""

        val tariff = if (args.has("tariff")) {
            args.optDouble("tariff").takeIf { it >= 0.0 } ?: return BAD_ARGS_ERROR
        } else runCatchingCancellable {
            if (type == "DC") settingsRepository.getDcTariff() else settingsRepository.getHomeTariff()
        }.getOrDefault(0.0)
        val cost = kwh * tariff

        val startTs = args.optString("date").trim().let { dateStr ->
            if (dateStr.isEmpty()) System.currentTimeMillis()
            else parseDayToNoon(dateStr) ?: return """{"error":"дата должна быть в формате ГГГГ-ММ-ДД"}"""
        }

        runCatchingCancellable {
            chargeDao.insert(
                ChargeEntity(
                    id = 0L,
                    startTs = startTs,
                    endTs = startTs + 3_600_000L,
                    type = type,
                    gunState = 2,
                    detectionSource = "manual",
                    socStart = socStart,
                    socEnd = socEnd,
                    kwhCharged = kwh,
                    kwhChargedSoc = kwhFromSoc,
                    cost = cost,
                )
            )
        }.getOrElse { return INTERNAL_ERROR }

        val currency = runCatchingCancellable { settingsRepository.getCurrency() }.getOrNull()
        return JSONObject()
            .put("ok", true)
            .put("type", type)
            .apply {
                if (socStart != null) put("soc_start", socStart)
                if (socEnd != null) put("soc_end", socEnd)
            }
            .put("kwh", Math.round(kwh * 100) / 100.0)
            .put("cost", Math.round(cost * 100) / 100.0)
            .put("tariff", tariff)
            .apply { currency?.let { put("currency", it.symbol) } }
            .toString()
    }

    private suspend fun statsSummary(args: JSONObject): String {
        val periodDays = if (args.optString("period") == "month") 30 else 7
        val metrics = runCatchingCancellable { insightsManager.dynamicsFor(periodDays) }
            .getOrElse { return INTERNAL_ERROR }
        if (metrics.isEmpty()) return """{"error":"недостаточно данных за период"}"""
        val arr = JSONArray()
        for (m in metrics) {
            arr.put(JSONObject().apply {
                put("label", m.label)
                put("current", m.current)
                if (m.previous != null) put("previous", m.previous)
                if (m.changePct != null) put("change_pct", m.changePct)
                put("sentiment", m.sentiment)
            })
        }
        return JSONObject()
            .put("period_days", periodDays)
            .put("metrics", arr)
            .toString()
    }

    // Strict YYYY-MM-DD -> that local day at 12:00 (backdated entries need no exact time;
    // noon keeps the record inside the intended calendar day in any timezone math).
    private fun parseDayToNoon(s: String): Long? {
        if (!s.matches(Regex("""\d{4}-\d{2}-\d{2}"""))) return null
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }
        val day = runCatching { fmt.parse(s)!!.time }.getOrNull() ?: return null
        return day + 12L * 3_600_000L
    }

    // --- control tools ---

    // П7 origin-based defense: a dangerous agent-initiated action never fires
    // directly. It shows a confirmation overlay and dispatches ONLY from the
    // user's "confirm" tap (via confirmScope, off the UI thread). Fail-closed:
    // if the overlay cannot be shown (SYSTEM_ALERT_WINDOW not granted), the
    // action is refused, never executed silently.
    private fun confirmDangerous(action: ActionDef, summary: String): String {
        val shown = confirmGate(
            context,
            "Голосовой агент",
            summary,
            // Re-read live vehicle data at confirm time (mirrors AutomationEngine ~line 573):
            // the overlay can sit open while the car accelerates, so the >30 km/h unlock
            // gate must see current speed, not the stale snapshot captured at tool-call time.
            { confirmScope.launch { actionDispatcher.dispatch(action, TrackingService.lastData.value) } },
            { },
        )
        return if (shown) {
            JSONObject().put("ok", true).put("status", "ожидает подтверждения на экране").toString()
        } else {
            JSONObject().put("ok", false)
                .put("error", "нужно подтверждение на экране, но нет разрешения на оверлей")
                .toString()
        }
    }

    private suspend fun vehicleControl(args: JSONObject): String {
        val input = args.optString("command").trim()
        if (input.isEmpty()) return """{"error":"не указана команда"}"""
        val valueOrNull = if (args.has("value")) args.optInt("value") else null
        // Documented surface is the readable id catalog; raw Chinese strings are
        // still accepted for backward compat (pre-catalog callers, T4 free string).
        val command = AgentCommandCatalog.resolve(input, valueOrNull)
            ?: input.takeIf { CommandTranslator.resolve(it).isNotEmpty() }
            ?: return unknownCommandError(input)
        val snapshot = gate.vehicleSnapshot()
        // Fail-closed, same invariant as VoiceController.execute: getBlockReason()
        // allows window-open when the whole snapshot is missing, so refuse here.
        if (snapshot == null && (ActionDispatcher.isWindowOpenCommand(command) || ActionDispatcher.isSunroofOpenCommand(command))) {
            return """{"error":"скорость неизвестна, окна и люк не открываю"}"""
        }
        val actionDef = ActionDef(command = command, displayName = command, kind = "param")
        if (ActionDispatcher.isDangerousAction(actionDef)) {
            return confirmDangerous(actionDef, command)
        }
        val result = actionDispatcher.dispatch(actionDef, data = snapshot)
        return dispatchJson(result)
    }

    private fun unknownCommandError(input: String): String {
        val sample = AgentCommandCatalog.ALL.take(10).joinToString(", ") { it.id }
        return JSONObject().put("error", "неизвестная команда '$input'. Доступные команды: $sample…").toString()
    }

    private suspend fun mediaVolume(args: JSONObject): String {
        val op = args.optString("op").trim()
        if (op.isEmpty()) return """{"error":"не указана операция"}"""
        val result = actionDispatcher.dispatch(
            ActionDef(command = "media_volume", displayName = "media_volume",
                kind = "media_volume", payload = op),
            data = gate.vehicleSnapshot(),
        )
        return dispatchJson(result)
    }

    private suspend fun runAutomation(args: JSONObject): String {
        val name = args.optString("name").trim()
        if (name.isEmpty()) return """{"error":"не указано имя"}"""
        val rule = ruleDao.getEnabled()
            .firstOrNull { it.name.trim().equals(name, ignoreCase = true) }
            ?: return """{"error":"автоматизация не найдена"}"""
        return when (val r = automationEngine.fireVoiceRule(rule.id, gate.vehicleSnapshot())) {
            is VoiceFireResult.Fired ->
                if (r.success) OK else """{"error":"часть действий не выполнилась"}"""
            VoiceFireResult.Confirming -> """{"ok":true,"status":"ожидает подтверждения на экране"}"""
            VoiceFireResult.ParkRequired -> """{"error":"выполняется только на паркинге"}"""
            VoiceFireResult.SpeedUnknown -> """{"error":"скорость неизвестна"}"""
            VoiceFireResult.NotFound -> """{"error":"автоматизация не найдена"}"""
        }
    }

    private suspend fun listAutomations(): String {
        val rules = runCatchingCancellable { ruleDao.getAllList() }
            .getOrElse { return """{"error":"не удалось прочитать автоматизации"}""" }
        val arr = JSONArray()
        rules.forEach { rule ->
            arr.put(JSONObject().apply {
                put("name", rule.name)
                put("enabled", rule.enabled)
                put("triggers", TriggerDef.listFromJson(rule.triggers).joinToString(", ") { it.displayName })
                put("actions", ActionDef.listFromJson(rule.actions).joinToString(", ") { it.displayName })
            })
        }
        return JSONObject().put("automations", arr).toString()
    }

    // --- places ---

    private suspend fun listPlaces(): String {
        val places = runCatchingCancellable { placeRepository.getAllSnapshot() }
            .getOrElse { return """{"error":"не удалось прочитать места"}""" }
        val arr = JSONArray()
        places.forEach { p ->
            arr.put(JSONObject()
                .put("name", p.name)
                .put("lat", p.lat)
                .put("lon", p.lon)
                .put("radius_m", p.radiusM))
        }
        return JSONObject().put("places", arr).toString()
    }

    private suspend fun createPlace(args: JSONObject): String {
        val name = args.optString("name").trim()
        if (name.isEmpty()) return """{"error":"не указано имя"}"""
        if (name.length > 40) return """{"error":"имя длиннее 40 символов"}"""
        val existing = runCatchingCancellable { placeRepository.getAllSnapshot() }
            .getOrElse { return """{"error":"не удалось создать место"}""" }
        if (existing.any { it.name.trim().equals(name, ignoreCase = true) }) {
            return JSONObject().put("error", "место с именем «$name» уже существует").toString()
        }
        if (existing.size >= MAX_PLACES) return """{"error":"достигнут предел в 50 мест"}"""
        val hasLat = args.has("lat") && !args.isNull("lat")
        val hasLon = args.has("lon") && !args.isNull("lon")
        val coords: Pair<Double, Double> = when {
            hasLat && hasLon -> {
                val la = args.optDouble("lat", Double.NaN)
                val lo = args.optDouble("lon", Double.NaN)
                if (!la.isFinite() || la !in -90.0..90.0 || !lo.isFinite() || lo !in -180.0..180.0) {
                    return """{"error":"некорректные координаты"}"""
                }
                la to lo
            }
            hasLat != hasLon -> return """{"error":"нужны обе координаты lat и lon"}"""
            else -> locationProvider()
                ?: return """{"error":"нет GPS-позиции; назови координаты или создай место на экране Автоматизации"}"""
        }
        // Same radius bounds as PlaceEditDialog (20..500 m).
        val radius = args.optInt("radius_m", 100).coerceIn(20, 500)
        runCatchingCancellable {
            placeRepository.insert(PlaceEntity(name = name, lat = coords.first, lon = coords.second, radiusM = radius))
        }.getOrElse { return """{"error":"не удалось создать место"}""" }
        return JSONObject().put("ok", true).put("name", name).put("radius_m", radius).toString()
    }

    // --- navigation ---

    private suspend fun navigateTo(args: JSONObject): String {
        val destination = args.optString("destination").trim()
        if (args.has("lat") && args.has("lon")) {
            val label = destination.ifEmpty { "точка" }
            return dispatchRoute(args.getDouble("lat"), args.getDouble("lon"), "destination", label)
        }
        if (destination.isEmpty()) return """{"error":"не указано, куда ехать"}"""
        homeWorkTarget(destination)?.let { return navigateHomeWork(it) }
        val placesResult = runCatchingCancellable { placeRepository.getAllSnapshot() }
        val place = placesResult.getOrNull()?.firstOrNull { it.name.equals(destination, ignoreCase = true) }
        if (place != null) return dispatchRoute(place.lat, place.lon, "place", place.name)
        // Free text: geocode (Open-Meteo, city-level) so the Navigator builds a real route from
        // the current position instead of just opening map search (field defect APK 336: «маршрут
        // до Орши» opened a search window). Street-level addresses do not geocode and fall back.
        val geo = runCatchingCancellable { weatherClient.geocode(destination) }
            .getOrNull()?.getOrNull()
        if (geo != null) return dispatchRoute(geo.lat, geo.lon, "destination", geo.name)
        val result = dispatchNavigate("Навигация", JSONObject().put("query", destination))
        if (!result.success) return JSONObject()
            .put("error", result.reason ?: "не получилось открыть Навигатор").toString()
        return JSONObject().put("ok", true).put("mode", "search")
            .put("note", "точку не удалось найти автоматически, открыт поиск на карте").toString()
    }

    private suspend fun searchOnMap(args: JSONObject): String {
        val query = args.optString("query").trim()
        if (query.isEmpty()) return """{"error":"не указано, что искать"}"""
        val result = dispatchNavigate("Поиск на карте", JSONObject().put("query", query))
        if (!result.success) return JSONObject()
            .put("error", result.reason ?: "не получилось открыть Навигатор").toString()
        return JSONObject().put("ok", true).put("mode", "search")
            .put("note", "открыта выдача в Навигаторе, пользователь выберет точку сам").toString()
    }

    private suspend fun showPointOnMap(args: JSONObject): String {
        val destination = args.optString("destination").trim()
        if (destination.isEmpty() && !(args.has("lat") && args.has("lon"))) return BAD_ARGS_ERROR
        // Explicit coordinates take priority; otherwise resolve from saved places or geocode.
        val coords: Triple<Double, Double, String>? = if (args.has("lat") && args.has("lon")) {
            Triple(args.getDouble("lat"), args.getDouble("lon"), destination.ifEmpty { "точка" })
        } else {
            val place = runCatchingCancellable { placeRepository.getAllSnapshot() }
                .getOrNull()?.firstOrNull { it.name.equals(destination, ignoreCase = true) }
            if (place != null) Triple(place.lat, place.lon, place.name)
            else runCatchingCancellable { weatherClient.geocode(destination) }
                .getOrNull()?.getOrNull()?.let { Triple(it.lat, it.lon, it.name) }
        }
        // No coordinates found (street-level address etc.): degrade to map search so the
        // user still sees the point in the Navigator's search results.
        if (coords == null) return searchOnMap(JSONObject().put("query", destination))
        val result = dispatchNavigate("Точка на карте",
            JSONObject().put("show", true).put("lat", coords.first)
                .put("lon", coords.second).put("label", coords.third))
        if (!result.success) return JSONObject()
            .put("error", result.reason ?: "не получилось открыть Навигатор").toString()
        return JSONObject().put("ok", true).put("mode", "show").put("point", coords.third).toString()
    }

    private data class HomeWork(val placeName: String, val shortcut: String)

    private fun homeWorkTarget(destination: String): HomeWork? =
        when (destination.lowercase()) {
            "дом", "домой", "до дома", "мой дом", "home" -> HomeWork("Дом", "home")
            "работа", "работу", "на работу", "моя работа", "work" -> HomeWork("Работа", "work")
            else -> null
        }

    // "домой"/"на работу": the BYDMate Place wins (exact coordinates, range assessment works),
    // then the Navigator's own saved Home/Work via its exported shortcut actions.
    private suspend fun navigateHomeWork(target: HomeWork): String {
        val place = runCatchingCancellable { placeRepository.getAllSnapshot() }
            .getOrNull()?.firstOrNull { it.name.equals(target.placeName, ignoreCase = true) }
        if (place != null) return dispatchRoute(place.lat, place.lon, "place", place.name)
        val result = dispatchNavigate("Навигация", JSONObject().put("shortcut", target.shortcut))
        if (!result.success) return JSONObject().put("error",
            result.reason ?: ("адрес не найден: добавь Место \"${target.placeName}\" в BYDMate " +
                "или сохрани точку \"${target.placeName}\" в Навигаторе")).toString()
        return JSONObject().put("ok", true).put("mode", "route").put("target", target.placeName)
            .put("note", "если адрес \"${target.placeName}\" сохранён в Навигаторе, " +
                "маршрут построится по нему; запас хода не оценивался, координаты неизвестны").toString()
    }

    private suspend fun dispatchRoute(lat: Double, lon: Double, labelKey: String, label: String): String {
        val result = dispatchNavigate("Навигация", JSONObject().put("lat", lat).put("lon", lon))
        if (!result.success) return JSONObject()
            .put("error", result.reason ?: "не получилось открыть Навигатор").toString()
        val json = JSONObject().put("ok", true).put("mode", "route").put(labelKey, label)
        rangeAssessment(lat, lon)?.let { ra ->
            ra.keys().forEach { k -> json.put(k, ra.get(k)) }
        }
        return json.toString()
    }

    // Same distance-vs-range math as rangeToDestination, but advisory: returns null when
    // any input is missing so route dispatch is never blocked by a failed assessment.
    private suspend fun rangeAssessment(lat: Double, lon: Double): JSONObject? {
        val gps = locationProvider() ?: return null
        val d = gate.vehicleSnapshot() ?: return null
        val rangeKm = runCatchingCancellable {
            rangeCalculator.estimate(d.soc, d.totalElecConsumption)
        }.getOrNull() ?: return null
        val straightKm = PlaceGeometry.distanceMeters(gps.first, gps.second, lat, lon) / 1000.0
        val distanceKm = (straightKm * ROAD_FACTOR).roundToInt()
        val range = rangeKm.roundToInt()
        val reserve = range - distanceKm
        val json = JSONObject()
            .put("distance_km", distanceKm)
            .put("range_km", range)
            .put("enough", range >= distanceKm)
            .put("reserve_km", reserve)
            .put("note", "distance_km примерное (по прямой с коэффициентом 1.25), не называй его " +
                "точным остатком пути; точный километраж и время даёт get_route_info во время ведения")
        if (range < distanceKm || reserve < distanceKm * 0.2) {
            json.put("charge_note",
                "заряда впритык или не хватит, предупреди и предложи найти зарядки по пути")
        }
        return json
    }

    private suspend fun findChargers(args: JSONObject): String {
        val city = args.optString("city").trim()
        val (lat, lon) = if (city.isNotEmpty()) {
            runCatchingCancellable { weatherClient.geocode(city) }.getOrNull()?.getOrNull()
                ?.let { it.lat to it.lon }
                ?: return """{"error":"не нашёл такую точку, уточни название"}"""
        } else locationProvider() ?: return """{"error":"нет GPS и не указан город"}"""
        val radiusKm = args.optInt("radius_km", 30).coerceIn(1, 100)
        val chargers = runCatchingCancellable {
            chargerSearchClient.search(lat, lon, radiusKm * 1000)
        }.getOrNull()?.getOrNull()
            ?: return """{"error":"сервис поиска зарядок недоступен, попробуй позже"}"""
        if (chargers.isEmpty()) return JSONObject()
            .put("chargers", JSONArray())
            .put("note", "в радиусе $radiusKm км зарядок в OpenStreetMap не найдено").toString()
        val nearest = chargers
            .sortedBy { PlaceGeometry.distanceMeters(lat, lon, it.lat, it.lon) }
            .take(5)
        return JSONObject().put("chargers", JSONArray().apply {
            nearest.forEach { c ->
                put(JSONObject()
                    .put("name", c.name)
                    .put("distance_km", (PlaceGeometry.distanceMeters(lat, lon, c.lat, c.lon) / 1000.0 * 10).roundToInt() / 10.0)
                    .put("lat", c.lat)
                    .put("lon", c.lon))
            }
        }).put("note", "данные OpenStreetMap, наличие и мощность не гарантированы").toString()
    }

    // Same saved-place-then-geocode lookup as navigateTo, plus a straight-line distance
    // (with a road-factor fudge) against the current range estimate.
    private suspend fun rangeToDestination(args: JSONObject): String {
        val destination = args.optString("destination").trim()
        if (destination.isEmpty()) return BAD_ARGS_ERROR
        val gps = locationProvider()
            ?: return """{"error":"нет GPS-позиции машины"}"""
        val place = runCatchingCancellable { placeRepository.getAllSnapshot() }
            .getOrNull()?.firstOrNull { it.name.equals(destination, ignoreCase = true) }
        val target = if (place != null) Triple(place.lat, place.lon, place.name)
            else runCatchingCancellable { weatherClient.geocode(destination) }
                .getOrNull()?.getOrNull()?.let { Triple(it.lat, it.lon, it.name) }
                ?: return """{"error":"не нашёл такую точку, уточни название"}"""
        val straightKm = PlaceGeometry.distanceMeters(
            gps.first, gps.second, target.first, target.second) / 1000.0
        val distanceKm = (straightKm * ROAD_FACTOR).roundToInt()
        val d = gate.vehicleSnapshot()
            ?: return """{"error":"нет данных с машины (нет связи или машина спит)"}"""
        val rangeKm = runCatchingCancellable {
            rangeCalculator.estimate(d.soc, d.totalElecConsumption)
        }.getOrNull()
            ?: return """{"error":"не хватает данных для оценки запаса хода (нужен пробег с учётом расхода)"}"""
        return JSONObject()
            .put("destination", target.third)
            .put("distance_km", distanceKm)
            .put("range_km", rangeKm.roundToInt())
            .put("enough", rangeKm.roundToInt() >= distanceKm)
            .put("reserve_km", rangeKm.roundToInt() - distanceKm)
            .put("note", "расстояние по прямой с дорожным коэффициентом 1.25, оценка примерная")
            .toString()
    }

    private suspend fun routeInfo(): String {
        val snap = NaviRouteHolder.latest
        // Screen is the fallback source: the 2026 Navigator build posts a static stub
        // notification, all guidance data lives only in the on-screen a11y tree.
        val screen = runCatching { naviScreenProvider() }.getOrNull()
        if (snap == null && screen == null)
            return """{"error":"Навигатор не ведёт маршрут, или данных нет: нет уведомления и Навигатор не на экране"}"""
        return JSONObject().apply {
            snap?.maneuver?.let { put("maneuver", it) }
            if (snap?.maneuver == null) snap?.maneuverIcon?.let { put("maneuver_icon", it) }
            (snap?.maneuverDistance ?: screen?.maneuverDistance)?.let { put("maneuver_distance", it) }
            (snap?.street ?: screen?.street)?.let { put("street", it) }
            if (snap != null && snap.bigTexts.isNotEmpty()) put("route_lines", JSONArray(snap.bigTexts))
            snap?.title?.let { put("raw_title", it) }
            snap?.text?.let { put("raw_text", it) }
            snap?.subText?.let { put("raw_sub_text", it) }
            screen?.remainingDistance?.let { put("remaining_distance", it) }
            screen?.remainingTime?.let { put("remaining_time", it) }
            screen?.arrivalTime?.let { put("arrival_time", it) }
            screen?.speedLimit?.let { put("speed_limit", it) }
            screen?.exitNumber?.let { put("exit_number", it) }
            gate.vehicleSnapshot()?.let { d ->
                put("soc", d.soc)
                runCatchingCancellable { rangeCalculator.estimate(d.soc, d.totalElecConsumption) }
                    .getOrNull()?.let { put("range_km", it.roundToInt()) }
            }
            // Screen values are live; notification age matters only when it is the source.
            put("age_min", if (snap != null) ((nowMs() - snap.postedAtMs) / 60_000L) else 0L)
            put("note", "данные из уведомления Навигатора и его экрана; remaining_* и arrival_time " +
                "видны, только когда Навигатор открыт на экране")
        }.toString()
    }

    private suspend fun goHomeScreen(): String {
        val result = actionDispatcher.dispatch(
            ActionDef(command = "", displayName = "Домой", kind = "go_home"), data = null)
        return if (result.success) """{"ok":true}"""
        else JSONObject().put("error", result.reason ?: "не получилось").toString()
    }

    private suspend fun playMusic(args: JSONObject): String {
        val query = args.optString("query").trim()
        val mode = args.optString("mode").takeIf { it == "search" } ?: "play"
        val payload = if (query.isEmpty()) JSONObject().put("mode", "mybeat")
            else JSONObject().put("mode", mode).put("query", query)
        val result = actionDispatcher.dispatch(
            ActionDef(command = "", displayName = "Музыка", kind = "yandex_music",
                payload = payload.toString()), data = null)
        return if (result.success) JSONObject().put("ok", true)
            .put("playing", if (query.isEmpty()) "Моя волна" else query).toString()
        else JSONObject().put("error", result.reason ?: "не получилось включить музыку").toString()
    }

    private suspend fun youtubeTool(args: JSONObject): String {
        val query = args.optString("query").trim()
        if (query.isEmpty()) return BAD_ARGS_ERROR
        val mode = args.optString("mode").takeIf { it == "search" } ?: "play"
        val result = actionDispatcher.dispatch(
            ActionDef(command = "", displayName = "YouTube", kind = "youtube",
                payload = JSONObject().put("mode", mode).put("query", query).toString()), data = null)
        return if (result.success) JSONObject().put("ok", true).put(
            if (mode == "play") "playing" else "searching", query).toString()
        else JSONObject().put("error", result.reason ?: "не получилось открыть YouTube").toString()
    }

    /** Resolve a human app name to (label, packageName) via aliases + launcher labels. */
    private suspend fun resolveLauncherApp(name: String): Built<Pair<String, String>> {
        val apps = runCatchingCancellable { launcherAppsProvider() }.getOrNull()
            ?: return Built.Error("список приложений недоступен")
        val needle = name.lowercase()
        // Alias hit: resolve to the first candidate package present in the launcher list, so an
        // alias never launches an app this car does not have; miss falls through to label match.
        val aliasMatch = APP_ALIASES[needle]?.firstNotNullOfOrNull { pkg ->
            apps.firstOrNull { it.second == pkg }
        }
        if (aliasMatch != null) return Built.Value(aliasMatch)
        val exact = apps.filter { it.first.lowercase() == needle }
        val matches = exact.ifEmpty { apps.filter { it.first.lowercase().contains(needle) } }
        return when {
            matches.isEmpty() -> Built.Error("приложение не найдено: $name")
            matches.size > 1 -> Built.Error(
                "несколько совпадений: ${matches.take(5).joinToString(", ") { it.first }}. Уточни название")
            else -> Built.Value(matches.single())
        }
    }

    private suspend fun launchAppTool(args: JSONObject): String {
        val name = args.optString("name").trim()
        if (name.isEmpty()) return """{"error":"не указано название приложения"}"""
        val (label, pkg) = when (val r = resolveLauncherApp(name)) {
            is Built.Error -> return JSONObject().put("error", r.message).toString()
            is Built.Value -> r.value
        }
        val result = actionDispatcher.dispatch(
            ActionDef(command = "", displayName = "Запуск $label", kind = "app_launch",
                payload = JSONObject().put("packageName", pkg).toString()), data = null)
        return if (result.success) JSONObject().put("ok", true).put("app", label).toString()
        else JSONObject().put("error", result.reason ?: "не получилось запустить $label").toString()
    }

    // --- cluster projection ---

    // Drive ClusterProjectionManager the same way the steering-wheel star does, then re-read to
    // confirm. The write fid is closed, so projection only takes when the manual preconditions
    // hold — on failure we voice back the honest, non-automatable hint.
    private suspend fun setClusterProjection(args: JSONObject): String {
        if (!args.has("on")) return """{"error":"не указано, включить или выключить проекцию"}"""
        val on = args.optBoolean("on")
        val want = if (on) ClusterMode.FULLSCREEN else ClusterMode.OFF
        // Treat an unreadable state as OFF: for on=true we then still actuate (worst case a
        // no-op inside the manager's mutex), never lie that the projection is already up.
        val before = runCatchingCancellable { clusterVoiceControl.projectionMode() }
            .getOrDefault(ClusterMode.OFF)
        val label = runCatchingCancellable { clusterVoiceControl.projectedAppLabel() }
            .getOrNull() ?: "Навигатор"
        if (before == want) return JSONObject().put("ok", true)
            .put("note", if (on) "$label уже на приборке" else "проекции уже нет на приборке")
            .toString()
        clusterVoiceControl.apply(on)
        // setMode is async (scope.launch + mutex) and the full on-sequence (daemon start,
        // compositor power-up, virtual display, app launch) can take well over 2s on a cold
        // start. Poll until the mode lands instead of a single fixed-delay check — the old
        // 2s check reported a false "не включилась" while the projection was still coming up.
        var after: ClusterMode? = null
        for (attempt in 1..clusterPollAttempts) {
            delay(clusterPollIntervalMs)
            after = runCatchingCancellable { clusterVoiceControl.projectionMode() }.getOrNull()
            if (after == want) break
        }
        val failure = runCatchingCancellable { clusterVoiceControl.lastFailure() }.getOrNull()
        return when {
            after == want -> JSONObject().put("ok", true).put("app", label).toString()
            on && failure == "daemon" -> JSONObject().put("ok", false)
                .put("note", "служебный процесс перезапускается, попробуй ещё раз через минуту").toString()
            on -> JSONObject().put("error",
                "проекция не включилась. Попробуй повторить команду через несколько секунд").toString()
            else -> JSONObject().put("error", "не получилось убрать проекцию с приборки").toString()
        }
    }

    // Immediate sentry toggle: routes through the SAME ActionDispatcher path as a "sentry"
    // automation action (dispatchSentry -> helper.putGlobalSetting), so the write is honest —
    // DispatchResult carries the real success/failure, unlike a fire-and-forget projection.
    private suspend fun setSentry(args: JSONObject): String {
        val on = requireBoolArg(args, "on")
            ?: return """{"error":"не указано, включить или выключить охрану"}"""
        val action = ActionDef(command = "sentry",
            displayName = if (on) "Включить охрану" else "Выключить охрану",
            kind = "sentry", payload = if (on) "1" else "0")
        if (ActionDispatcher.isDangerousAction(action)) {
            return confirmDangerous(action, action.displayName)
        }
        val result = actionDispatcher.dispatch(action, data = null)
        return dispatchJson(result)
    }

    // Accepts a JSON boolean or the strings "true"/"false" (some LLMs emit stringified
    // booleans); anything else (missing key, null, number) is treated as unspecified so a
    // malformed call can never silently disable a rule.
    private fun requireEnabledArg(args: JSONObject): Boolean? {
        if (!args.has("enabled") || args.isNull("enabled")) return null
        return when (val raw = args.get("enabled")) {
            is Boolean -> raw
            is String -> when (raw.trim().lowercase()) {
                "true" -> true
                "false" -> false
                else -> null
            }
            else -> null
        }
    }

    // Same acceptance rule as requireEnabledArg, generalized to an arbitrary key (used by
    // create_automation's sentry action "on" field).
    private fun requireBoolArg(args: JSONObject, key: String): Boolean? {
        if (!args.has(key) || args.isNull(key)) return null
        return when (val raw = args.get(key)) {
            is Boolean -> raw
            is String -> when (raw.trim().lowercase()) {
                "true" -> true
                "false" -> false
                else -> null
            }
            else -> null
        }
    }

    private suspend fun setAutomationEnabled(args: JSONObject): String {
        val name = args.optString("name").trim()
        if (name.isEmpty()) return """{"error":"не указано имя"}"""
        val enabled = requireEnabledArg(args) ?: return """{"error":"не указано значение enabled"}"""
        val matches = runCatchingCancellable { ruleDao.getAllList() }
            .getOrElse { return """{"error":"не удалось изменить автоматизацию"}""" }
            .filter { it.name.trim().equals(name, ignoreCase = true) }
        return when {
            matches.isEmpty() -> JSONObject().put("error", "автоматизация не найдена: $name").toString()
            matches.size > 1 ->
                JSONObject().put("error", "несколько автоматизаций с именем $name, переименуйте").toString()
            else -> {
                runCatchingCancellable { ruleDao.setEnabled(matches.first().id, enabled) }
                    .getOrElse { return """{"error":"не удалось изменить автоматизацию"}""" }
                JSONObject().put("ok", true).put("name", name).put("enabled", enabled).toString()
            }
        }
    }

    // --- create_automation ---

    /** Result of parsing one trigger/action draft: either a built value, or a fixed Russian error. */
    private sealed class Built<out T> {
        data class Value<T>(val value: T) : Built<T>()
        data class Error(val message: String) : Built<Nothing>()
    }

    private suspend fun createAutomation(args: JSONObject): String {
        val name = args.optString("name").trim()
        if (name.isEmpty()) return """{"error":"не указано имя"}"""
        val triggerArg = args.optJSONObject("trigger")
            ?: return """{"error":"не указан триггер"}"""
        val actionsArg = args.optJSONArray("actions")
        if (actionsArg == null || actionsArg.length() == 0) return """{"error":"не указаны действия"}"""

        val existing = runCatchingCancellable { ruleDao.getAllList() }
            .getOrElse { return """{"error":"не удалось создать автоматизацию"}""" }
        if (existing.size >= MAX_AUTOMATIONS) return """{"error":"достигнут предел в 50 автоматизаций"}"""
        if (existing.any { it.name.trim().equals(name, ignoreCase = true) }) {
            return JSONObject().put("error", "автоматизация с именем «$name» уже существует").toString()
        }

        val trigger = when (val r = buildTrigger(triggerArg)) {
            is Built.Error -> return JSONObject().put("error", r.message).toString()
            is Built.Value -> r.value
        }

        // Validate voice-phrase collisions (builtin commands, duplicates across rules).
        // editingId=0L because this is a new rule with no persisted id yet.
        RuleDraftValidator.validateTriggers(listOf(trigger), editingId = 0L, existingRules = existing)?.let {
            val msg = when (it) {
                TriggerValidationError.VoicePhraseEmpty -> "не указана голосовая фраза"
                TriggerValidationError.VoicePhraseBuiltin ->
                    "эта фраза совпадает со встроенной голосовой командой, выбери другую"
                TriggerValidationError.VoicePhraseTaken -> "эта фраза уже используется другой автоматизацией"
            }
            return JSONObject().put("error", msg).toString()
        }

        val actions = mutableListOf<ActionDef>()
        for (i in 0 until actionsArg.length()) {
            val obj = actionsArg.optJSONObject(i)
                ?: return """{"error":"некорректное описание действия"}"""
            when (val r = buildAction(obj)) {
                is Built.Error -> return JSONObject().put("error", r.message).toString()
                is Built.Value -> actions += r.value
            }
        }
        RuleDraftValidator.validateActions(actions)?.let { return actionErrorJson(it) }

        val cooldown = args.optInt("cooldown_seconds", 60).coerceAtLeast(30)
        val playSound = args.optBoolean("play_sound", false)
        val rule = RuleEntity(
            name = name,
            enabled = true,
            triggers = TriggerDef.listToJson(listOf(trigger)),
            actions = ActionDef.listToJson(actions),
            cooldownSeconds = cooldown,
            playSound = playSound,
            confirmBeforeExecute = actions.any { ActionDispatcher.isDangerousAction(it) },
        )
        runCatchingCancellable { ruleDao.insert(rule) }
            .getOrElse { return """{"error":"не удалось создать автоматизацию"}""" }
        return JSONObject()
            .put("ok", true)
            .put("name", name)
            .put("hint", "скажи «выключи автоматизацию $name» чтобы отключить")
            .toString()
    }

    /** Enum trigger params persist a numeric code; the agent may pass the code ("4") or the
     *  human label it saw in get_vehicle_state ("D"/"d"). Returns the code, or null when the
     *  raw value matches neither (caller rejects with [enumValuesHint]). */
    private fun resolveEnumCode(option: TriggerParamOption, raw: String): String? {
        val enums = option.enumValues ?: return raw
        enums.firstOrNull { it.first == raw }?.let { return it.first }
        return enums.firstOrNull {
            option.localizedEnumLabel(it.first, context).equals(raw, ignoreCase = true)
        }?.first
    }

    /** "label (code)" list of one enum param's valid values, for the rejection message. */
    private fun enumValuesHint(option: TriggerParamOption): String =
        option.enumValues.orEmpty().joinToString(", ") {
            "${option.localizedEnumLabel(it.first, context)} (${it.first})"
        }

    /** Compact "Param:label(code)/..." guide across every enum trigger param, injected into
     *  the create_automation value-field description so the LLM emits codes, not raw labels. */
    private fun enumValueGuide(): String =
        TRIGGER_PARAMS.filter { it.enumValues != null }.joinToString("; ") { p ->
            p.param + ":" + p.enumValues!!.joinToString("/") {
                "${p.localizedEnumLabel(it.first, context)}(${it.first})"
            }
        }

    private suspend fun buildTrigger(t: JSONObject): Built<TriggerDef> {
        val kind = t.optString("kind")
        return when (kind) {
            "param" -> {
                val paramArg = t.optString("param").trim()
                val option = TRIGGER_PARAMS.firstOrNull { it.param.equals(paramArg, ignoreCase = true) }
                    ?: return Built.Error("неизвестный параметр триггера: $paramArg")
                val operator = t.optString("operator").trim()
                if (operator !in OPERATORS) return Built.Error("недопустимый оператор: $operator")
                val rawValue = t.optString("value").trim()
                if (rawValue.isEmpty()) return Built.Error("не указано значение триггера")
                // Enum params persist a numeric code ("4"=Drive), but the LLM naturally writes
                // the human label it saw in get_vehicle_state ("D"). The engine parses
                // value.toDoubleOrNull(), so a raw label silently makes the predicate false
                // forever (visible + enabled rule that never fires). Normalize label -> code;
                // reject an unknown enum value loudly instead of persisting a dead rule.
                // Numeric params pass through unchanged.
                val value = if (option.enumValues != null) {
                    resolveEnumCode(option, rawValue)
                        ?: return Built.Error(
                            "недопустимое значение «$rawValue» для «${option.param}»; допустимо: ${enumValuesHint(option)}")
                } else rawValue
                val displayValue = option.localizedEnumLabel(value, context)
                Built.Value(TriggerDef(
                    param = option.param,
                    chineseName = option.chineseName,
                    operator = operator,
                    value = value,
                    displayName = "${option.param} $operator $displayValue",
                ))
            }
            "place_enter", "place_exit" -> {
                val placeName = t.optString("place_name").trim()
                if (placeName.isEmpty()) return Built.Error("не указано имя места")
                val places = runCatchingCancellable { placeRepository.getAllSnapshot() }
                    .getOrElse { return Built.Error("не удалось создать автоматизацию") }
                val matches = places.filter { it.name.trim().equals(placeName, ignoreCase = true) }
                when {
                    matches.isEmpty() -> Built.Error("место не найдено: $placeName")
                    matches.size > 1 -> Built.Error("несколько мест с именем $placeName, переименуйте")
                    else -> {
                        val place = matches.first()
                        val verb = if (kind == "place_enter") "Въезд в" else "Выезд из"
                        Built.Value(TriggerDef(
                            param = "Place", chineseName = "位置", operator = "==", value = "enter",
                            displayName = "$verb «${place.name}»",
                            kind = kind, placeId = place.id, placeName = place.name,
                        ))
                    }
                }
            }
            "time_of_day" -> {
                val value = t.optString("value").trim().lowercase()
                if (value !in setOf("dawn", "day", "dusk", "night")) {
                    return Built.Error("недопустимое время суток: $value")
                }
                Built.Value(TriggerDef(
                    param = "TimeOfDay", chineseName = "时间段", operator = "==",
                    value = value.uppercase(), displayName = value, kind = "time_of_day",
                ))
            }
            "time_range" -> {
                val raw = t.optString("value").trim()
                val parts = raw.split("-")
                val from = parts.getOrNull(0)?.let { hhmmToMinute(it.trim()) }
                val to = parts.getOrNull(1)?.let { hhmmToMinute(it.trim()) }
                if (parts.size != 2 || from == null || to == null) {
                    return Built.Error("неверный формат времени, ожидается HH:MM-HH:MM")
                }
                val days = mutableSetOf<Int>()
                t.optJSONArray("weekdays")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val day = arr.optInt(i, -1)
                        if (day !in 1..7) return Built.Error("день недели должен быть числом 1-7 (1=понедельник, 7=воскресенье)")
                        days += day
                    }
                }
                val spec = ScheduleSpec(from, to, days)
                Built.Value(TriggerDef(
                    param = "Schedule", chineseName = "时间表", operator = "==",
                    value = spec.toJson(), displayName = raw, kind = "time_range",
                ))
            }
            "service_start" -> Built.Value(TriggerDef(
                param = "ServiceStart", chineseName = "服务启动", operator = "==", value = "true",
                displayName = "Запуск приложения", kind = "service_start",
            ))
            "network_available" -> Built.Value(TriggerDef(
                param = "NetworkAvailable", chineseName = "网络可用", operator = "==", value = "true",
                displayName = "Есть сеть", kind = "network_available",
            ))
            // Mirrors newVoiceTrigger in AutomationScreen.kt: phrase is stored as both value
            // and displayName; collision validation (builtin / taken) runs in createAutomation.
            "voice" -> {
                val phrase = t.optString("phrase").trim()
                if (phrase.isEmpty()) return Built.Error("не указана голосовая фраза (поле phrase)")
                Built.Value(TriggerDef(
                    param = "Voice", chineseName = "语音", operator = "==",
                    value = phrase, displayName = phrase, kind = "voice",
                ))
            }
            // Mirrors newButtonPressTrigger in AutomationViewModel.kt: value is button number
            // as string; displayName is "Кнопка N".
            "button_press" -> {
                val button = t.optInt("button", -1)
                if (button !in 1..4) return Built.Error("номер кнопки виджета должен быть от 1 до 4 (поле button)")
                Built.Value(TriggerDef(
                    param = "button", chineseName = "", operator = "==",
                    value = button.toString(), displayName = "Кнопка $button", kind = "button_press",
                ))
            }
            else -> Built.Error("недопустимый тип триггера: $kind")
        }
    }

    private suspend fun buildAction(a: JSONObject): Built<ActionDef> {
        val kind = a.optString("kind")
        return when (kind) {
            "param" -> {
                val id = a.optString("command_id").trim()
                val cmd = AgentCommandCatalog.ALL.firstOrNull { it.id == id }
                    ?: return Built.Error("неизвестная команда: $id")
                val value = if (a.has("value") && !a.isNull("value")) a.optInt("value") else null
                val chinese = AgentCommandCatalog.resolve(id, value)
                    ?: return Built.Error("неверное значение для команды $id")
                Built.Value(ActionDef(command = chinese, displayName = cmd.ru, kind = "param"))
            }
            "delay" -> {
                val hasMs = a.has("ms") && !a.isNull("ms")
                val ms = if (hasMs) a.optInt("ms", -1) else -1
                if (!hasMs || ms !in 0..30000) {
                    return Built.Error("не указана длительность паузы (мс, 0..30000)")
                }
                Built.Value(ActionDef(command = "delay", displayName = "Пауза $ms мс",
                    kind = "delay", payload = ms.toString()))
            }
            "media_volume" -> {
                val op = a.optString("op").trim()
                Built.Value(ActionDef(command = "media_volume", displayName = "Громкость $op",
                    kind = "media_volume", payload = op))
            }
            // Accept legacy kind names and normalize all three to the canonical "notification".
            "notification", "notification_sound", "notification_silent" -> {
                val title = a.optString("title").trim()
                val text = a.optString("text").trim()
                val payload = JSONObject().put("title", title).put("text", text).toString()
                Built.Value(ActionDef(command = "notification", displayName = title.ifBlank { "Уведомление" },
                    kind = "notification", payload = payload))
            }
            "call" -> {
                val phone = a.optString("phone").trim()
                // displayName has no phone number: list_automations serializes it straight back
                // to the LLM (the payload, which does carry the phone for the dispatcher, never is).
                Built.Value(ActionDef(command = "call", displayName = "Звонок", kind = "call",
                    payload = JSONObject().put("phone", phone).put("autoDial", true).toString()))
            }
            "navigate" -> {
                val hasLat = a.has("lat") && !a.isNull("lat")
                val hasLon = a.has("lon") && !a.isNull("lon")
                val lat = if (hasLat) a.optDouble("lat", Double.NaN) else Double.NaN
                val lon = if (hasLon) a.optDouble("lon", Double.NaN) else Double.NaN
                if (!hasLat || !hasLon || !lat.isFinite() || !lon.isFinite()) {
                    return Built.Error("не указаны координаты lat/lon")
                }
                Built.Value(ActionDef(command = "navigate", displayName = "Маршрут", kind = "navigate",
                    payload = JSONObject().put("lat", lat).put("lon", lon).toString()))
            }
            "url" -> {
                val url = a.optString("url").trim()
                Built.Value(ActionDef(command = "url", displayName = url.ifBlank { "Ссылка" }, kind = "url",
                    payload = JSONObject().put("url", url).toString()))
            }
            "yandex_music" -> {
                val mode = a.optString("mode").trim()
                if (mode.isEmpty()) return Built.Error("не указан режим Я.Музыки")
                Built.Value(ActionDef(command = "yandex_music", displayName = "Я.Музыка",
                    kind = "yandex_music", payload = JSONObject().put("mode", mode).toString()))
            }
            "sentry" -> {
                val on = requireBoolArg(a, "on") ?: return Built.Error("не указано состояние охранного режима")
                Built.Value(ActionDef(command = "sentry",
                    displayName = if (on) "Включить охрану" else "Выключить охрану",
                    kind = "sentry", payload = if (on) "1" else "0"))
            }
            "app_launch" -> {
                val name = a.optString("app").trim()
                if (name.isEmpty()) return Built.Error("не указано приложение (поле app)")
                val (label, pkg) = when (val r = resolveLauncherApp(name)) {
                    is Built.Error -> return r
                    is Built.Value -> r.value
                }
                Built.Value(ActionDef(
                    command = "", displayName = "Запуск $label", kind = "app_launch",
                    payload = JSONObject().put("packageName", pkg).put("appLabel", label)
                        .put("minimize", false).toString(),
                ))
            }
            "cluster_projection" -> {
                val on = requireBoolArg(a, "on")
                    ?: return Built.Error("не указано состояние проекции на приборку")
                Built.Value(ActionDef(command = "cluster_projection", displayName = "Вывод на приборку",
                    kind = "cluster_projection", payload = if (on) "1" else "0"))
            }
            "speak" -> {
                val text = a.optString("text").trim()
                if (text.isEmpty()) return Built.Error("не задан текст для озвучки")
                Built.Value(ActionDef(command = "", displayName = "Озвучить текст", kind = "speak",
                    payload = JSONObject().put("text", text).toString()))
            }
            "agent_query" -> {
                val prompt = a.optString("prompt").trim()
                if (prompt.isEmpty()) return Built.Error("не задан запрос агенту")
                Built.Value(ActionDef(command = "", displayName = "Запрос агенту", kind = "agent_query",
                    payload = JSONObject().put("prompt", prompt).toString()))
            }
            else -> Built.Error("недопустимый тип действия: $kind")
        }
    }

    private fun actionErrorJson(err: ActionValidationError): String {
        val msg = when (err) {
            is ActionValidationError.CommandMissing -> "не указана команда (действие ${err.index})"
            is ActionValidationError.NotifTitleEmpty -> "не указан заголовок уведомления (действие ${err.index})"
            is ActionValidationError.AppNotSelected -> "не указано приложение (действие ${err.index})"
            is ActionValidationError.PhoneInvalid -> "некорректный номер телефона (действие ${err.index})"
            is ActionValidationError.NavDestMissing -> "не указан пункт назначения (действие ${err.index})"
            is ActionValidationError.UrlEmpty -> "не указана ссылка (действие ${err.index})"
            is ActionValidationError.UrlNoScheme ->
                "ссылка должна начинаться со схемы, например https:// (действие ${err.index})"
            is ActionValidationError.YandexMusicModeMissing -> "не указан режим Я.Музыки (действие ${err.index})"
            is ActionValidationError.MediaVolumeMissing -> "не указан уровень громкости (действие ${err.index})"
            is ActionValidationError.SentryInvalid ->
                "некорректное состояние охранного режима (действие ${err.index})"
            is ActionValidationError.SpeakTextEmpty ->
                "не задан текст для озвучки (действие ${err.index})"
            is ActionValidationError.AgentQueryPromptEmpty ->
                "не задан запрос агенту (действие ${err.index})"
        }
        return JSONObject().put("error", msg).toString()
    }

    // ActionDispatcher's reason is either a Russian safety-gate message meant to be voiced back
    // (e.g. "скорость выше 80 км/ч") or a raw English exception message from its own catch —
    // only the former may reach the LLM, so a Cyrillic-letter check gates which one this is.
    private fun dispatchJson(result: DispatchResult): String {
        if (result.success) return OK
        val reason = result.reason
        val safeReason = reason?.takeIf { it.any { c -> c in 'Ѐ'..'ӿ' } }
        return JSONObject().put("error", safeReason ?: "не выполнено").toString()
    }

    // --- helpers ---

    private fun periodParam(): JSONObject = JSONObject()
        .put("type", "string")
        .put("enum", JSONArray(listOf("day", "week", "month")))
        .put("description", "Период от текущего момента: day = 24 часа, week = 7 дней, month = 30 дней. " +
            "Для конкретного месяца или дат используй from/to вместо period")

    private fun tool(
        name: String,
        description: String,
        properties: JSONObject,
        required: List<String>,
    ): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", properties)
                put("required", JSONArray(required))
            })
        })
    }

    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0

    companion object {
        /** Tools that manage automations. Excluded for automation-origin sessions: run_automation
         *  reaches fireVoiceRule (which bypasses cooldown by design), so a rule whose agent_query
         *  action calls it could recurse into itself with no engine-level brake. */
        internal val AUTOMATION_TOOLS = setOf("run_automation", "create_automation", "set_automation_enabled")

        private const val DAY_MS = 24L * 3600_000
        private const val PERIOD_ERROR =
            """{"error":"укажи period (day/week/month) или даты from и to в формате ГГГГ-ММ-ДД"}"""
        private const val OK = """{"ok":true}"""
        private const val SEARCH_ERROR = """{"error":"поиск недоступен"}"""
        private const val MAX_AUTOMATIONS = 50
        private const val MAX_PLACES = 50
        private const val BAD_ARGS_ERROR = """{"error":"некорректные аргументы"}"""
        private const val CALL_CONTACT_FAILED = """{"error":"не удалось позвонить"}"""
        private const val INTERNAL_ERROR = """{"error":"внутренняя ошибка инструмента"}"""
        // Straight-line distance underestimates real road distance; this fudge factor
        // brings the estimate closer to typical highway/road routing.
        private const val ROAD_FACTOR = 1.25

        // Any wheel below this is clearly deflated (Leopard 3 cold placard ~250 kPa).
        private const val TIRE_WARN_MIN_KPA = 210

        // RU aliases for stock DiLink apps whose launcher labels are Chinese/English and thus
        // unreachable by label match. Values are candidate packages in priority order; an alias
        // fires only when one of them is actually installed on this car (fleet cars differ).
        // App store and fridge app are excluded deliberately (user decision, 2026-07-05).
        internal val APP_ALIASES: Map<String, List<String>> = mapOf(
            "навигатор" to listOf("ru.yandex.yandexnavi"),
            "яндекс навигатор" to listOf("ru.yandex.yandexnavi"),
            "музыка" to listOf("ru.yandex.music"),
            "яндекс музыка" to listOf("ru.yandex.music"),
            "камера" to listOf("com.byd.avc"),
            "камеры" to listOf("com.byd.avc"),
            "видеорегистратор" to listOf("com.byd.cdr"),
            "регистратор" to listOf("com.byd.cdr"),
            "браузер" to listOf("com.android.chrome"),
            "хром" to listOf("com.android.chrome"),
            "ютуб" to listOf("anddea.youtube", "com.google.android.youtube"),
            "настройки машины" to listOf("com.byd.carsettings"),
            "настройки автомобиля" to listOf("com.byd.carsettings"),
            "файлы" to listOf("com.byd.filemanager"),
            "файловый менеджер" to listOf("com.byd.filemanager"),
            "режимы вождения" to listOf("com.byd.drivemode"),
            "режим вождения" to listOf("com.byd.drivemode"),
            "часовой" to listOf("com.byd.sentrymode"),
            "охрана" to listOf("com.byd.sentrymode"),
            "охранный режим" to listOf("com.byd.sentrymode"),
            "абрп" to listOf("com.iternio.abrpapp"),
            "маршрутный планировщик" to listOf("com.iternio.abrpapp"),
        )
    }
}
