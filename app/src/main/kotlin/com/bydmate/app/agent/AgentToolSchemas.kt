package com.bydmate.app.agent

import com.bydmate.app.data.charging.ChargeConnector
import com.bydmate.app.ui.automation.OPERATORS
import com.bydmate.app.ui.automation.TRIGGER_PARAMS
import org.json.JSONArray
import org.json.JSONObject

internal suspend fun agentToolSchemas(
    includeAutomationTools: Boolean,
    enabledRuleNames: suspend () -> List<String>,
    enumValueGuide: () -> String,
): JSONArray = JSONArray().apply {
    put(tool(
        "get_vehicle_state",
        "Текущее состояние машины: заряд, запас хода, скорость, температуры, климат, окна, двери, шины, свет, подогрев сидений, GPS-позиция и название сохранённого Места, если машина в нём. " +
            "Поле age_s = сколько секунд назад получены данные; если больше 60, предупреди, что данные могли устареть.",
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
        "Выполнить команду управления машиной. У окон ровно три положения: полностью открыть, " +
            "наполовину и проветривание; произвольный процент машина не умеет — если водитель " +
            "назвал процент, выбери ближайшее из трёх и скажи в ответе, какое. " +
            "command — идентификатор из списка:\n" +
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
    put(tool(
        "where_am_i",
        "Где сейчас машина: ближайшие населённые пункты вокруг текущей GPS-позиции (данные " +
            "OpenStreetMap) с расстоянием до центра в км и стороной света от машины, ближайший " +
            "первым, плюс ближайший город. Известны только центры, не границы: ответ говорит, " +
            "насколько машина близко к месту, а не в каком месте она. Вызывай на вопросы \"где я\", " +
            "\"что рядом\", \"какой это населённый пункт\"; не ищи координаты через web_search.",
        JSONObject(), emptyList(),
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
        val ruleNames = enabledRuleNames()
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
    // Memory tools live behind the automation gate: a session started BY an automation
    // talks to a rule, not to the driver, and must not rewrite what we know about them.
    if (includeAutomationTools) {
        put(tool(
            "remember_fact",
            "Запомнить устойчивый факт о водителе на будущее: имя, как обращаться, " +
                "предпочтения (температура, музыка, маршруты), семья. Одна короткая фраза, " +
                "например \"Водителя зовут Андрей\", \"Любит 22 градуса в салоне\". " +
                "Не сохранять разовые команды и состояние машины.",
            JSONObject().put("fact", JSONObject().put("type", "string")
                .put("description", "Факт одной короткой фразой")),
            listOf("fact"),
        ))
        put(tool(
            "forget_fact",
            "Забыть сохранённый факт о водителе. fact = часть текста факта; " +
                "all = true чтобы забыть всё.",
            JSONObject()
                .put("fact", JSONObject().put("type", "string")
                    .put("description", "Часть текста факта, который нужно забыть"))
                .put("all", JSONObject().put("type", "boolean")
                    .put("description", "true = забыть все факты о водителе")),
            emptyList(),
        ))
    }
    put(tool(
        "navigate_to",
        "Построить маршрут от текущей позиции: по умолчанию в приложении, выбранном в " +
            "настройках (Навигатор/2ГИС/Карты), app=\"maps\" - явно в Яндекс Картах, если " +
            "пользователь просит именно их. Команды поехали домой, до дома, " +
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
                .put("description", "Долгота точки"))
            .put("go", JSONObject().put("type", "boolean")
                .put("description", "true, когда водитель сказал поехали/вези/едем/повёз - " +
                    "начать движение по маршруту, кнопку Поехали нажмёт приложение. " +
                    "false (по умолчанию) для построй/покажи/проложи маршрут - только " +
                    "построить, водитель нажмёт Поехали сам"))
            .put("app", JSONObject().put("type", "string")
                .put("enum", JSONArray().put("navigator").put("maps"))
                .put("description", "navigator = приложение из настроек (Навигатор/2ГИС/Карты, по умолчанию), " +
                    "maps = явно Яндекс Карты; maps передавай только когда пользователь явно просит Яндекс Карты")),
        emptyList(),
    ))
    put(tool(
        "search_on_map",
        "Открыть поиск места в приложении, выбранном в настройках (app=\"maps\" - явно в " +
            "Яндекс Картах, если пользователь просит именно их): на карте появится выдача, " +
            "пользователь сам выберет точку. Использовать для команд вроде: поищи кафе, найди заправку, " +
            "где ближайшая аптека. Если пользователь просит ПОЕХАТЬ куда-то, в том числе " +
            "домой или на работу, используй navigate_to.",
        JSONObject().put("query", JSONObject().put("type", "string")
            .put("description", "Что искать: название места или категория"))
            .put("app", JSONObject().put("type", "string")
                .put("enum", JSONArray().put("navigator").put("maps"))
                .put("description", "navigator = приложение из настроек (Навигатор/2ГИС/Карты, по умолчанию), " +
                    "maps = явно Яндекс Карты; maps передавай только когда пользователь явно просит Яндекс Карты")),
        listOf("query"),
    ))
    put(tool(
        "show_point_on_map",
        "Показать точку на карте БЕЗ построения маршрута (по умолчанию приложение из " +
            "настроек, app=\"maps\" - явно Яндекс Карты): команды вроде " +
            "покажи на карте, где находится. destination: имя Места, город или адрес; " +
            "либо lat/lon, если координаты известны (например из find_chargers). " +
            "Для маршрута используй navigate_to, для поиска по категории search_on_map.",
        JSONObject().put("destination", JSONObject().put("type", "string")
            .put("description", "Что показать: имя Места, адрес или название"))
            .put("lat", JSONObject().put("type", "number").put("description", "Широта, если известна"))
            .put("lon", JSONObject().put("type", "number").put("description", "Долгота"))
            .put("app", JSONObject().put("type", "string")
                .put("enum", JSONArray().put("navigator").put("maps"))
                .put("description", "navigator = приложение из настроек (Навигатор/2ГИС/Карты, по умолчанию), " +
                    "maps = явно Яндекс Карты; maps передавай только когда пользователь явно просит Яндекс Карты")),
        emptyList(),
    ))
    put(tool(
        "find_chargers",
        "Найти электрозарядные станции. В Беларуси с живой занятостью разъёмов, мощностью и " +
            "ценой (карта ассоциации BETA), иначе только станции (OpenStreetMap). Без city ищет " +
            "от текущей позиции машины. where: «по пути», «впереди», «по дороге» - ahead (станции " +
            "по ходу движения, а после маршрута navigate_to - с небольшим крюком к его точке); " +
            "«рядом», «вокруг», «ближайшая» - around; без where в движении ищет ahead, на месте around. " +
            "Поля станции: distance_km по прямой; direction_from_car - сторона света; position " +
            "ahead/behind/aside - впереди, позади, в стороне по ходу; detour_km - лишний крюк; status " +
            "free/busy/unavailable и connectors_free из connectors_total - разъёмы типа машины, " +
            "connectors_unknown - по стольким из них нет данных о занятости; status_scope=station - " +
            "занятость всей станции, не разъёма машины; " +
            "max_power_kw; price_per_kwh и currency; status_age_min - сколько минут назад обновлена " +
            "занятость, status_stale=true - устарела; beyond_range=true - дальше запаса хода. " +
            "connector_known=false - разъёмы, мощность и цена неизвестны; status_known=false - занятость " +
            "неизвестна. Когда водитель выберет станцию, вызови navigate_to с lat и lon выбранной станции.",
        JSONObject()
            .put("city", JSONObject().put("type", "string")
                .put("description", "Город/точка поиска, если не вокруг машины"))
            .put("radius_km", JSONObject().put("type", "integer")
                .put("description", "Радиус поиска в км, по умолчанию 30 (ahead: 30-100 по скорости), максимум 100"))
            .put("connector", JSONObject().put("type", "string")
                .put("enum", JSONArray(ChargeConnector.entries.map { it.label }))
                .put("description", "Тип разъёма, только если водитель назвал другой; по умолчанию из настроек"))
            .put("where", JSONObject().put("type", "string")
                .put("enum", JSONArray(ChargerWhere.entries.map { it.code }))
                .put("description", "ahead - по ходу движения, around - вокруг машины")),
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
            "в ответе, этих данных сейчас нет - скажи честно." +
            " Поле energy_estimate: сколько кВтч уйдёт до конца маршрута и сколько процентов батареи останется на финише.",
        JSONObject(), emptyList(),
    ))
    put(tool(
        "go_home",
        "Свернуть все окна и показать домашний экран (рабочий стол) машины. Использовать для " +
            "команд вроде: домой на экран, на рабочий стол, сверни всё.",
        JSONObject(), emptyList(),
    ))
    put(tool(
        "end_conversation",
        "Закрыть голосового помощника (тебя самого). Вызывать ТОЛЬКО когда водитель прямо просит " +
            "закрыть, выключить или остановить помощника или говорит, что разговор окончен: " +
            "\"закрой агента\", \"выключись\", \"отключись\", \"всё, спасибо, хватит\". Никогда не " +
            "вызывать просто после выполненной команды. После вызова ответь очень коротким прощанием.",
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
            "приложений машины: навигатор, яндекс карты, музыка, камера, видеорегистратор, " +
            "браузер, ютуб, настройки машины, файлы, режимы вождения, часовой, АБРП, " +
            "медиацентр, телефон.",
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
    put(tool(
        "set_hotspot",
        "Включить или выключить точку доступа Wi-Fi (раздачу интернета с машины). " +
            "\"включи раздачу интернета\", \"включи точку доступа\" = on; " +
            "\"выключи раздачу\" = off.",
        JSONObject().put("on", JSONObject().put("type", "boolean")
            .put("description", "true = включить точку доступа, false = выключить")),
        listOf("on"),
    ))
    put(tool(
        "set_screen",
        "Погасить или зажечь центральный экран магнитолы (приборку и HUD не трогает). Гаснет только " +
            "подсветка: музыка и приложения продолжают работать, касание экрана зажигает его снова. " +
            "\"выключи экран\", \"погаси экран\" = off; \"включи экран\" = on.",
        JSONObject().put("on", JSONObject().put("type", "boolean")
            .put("description", "true = включить экран, false = выключить")),
        listOf("on"),
    ))
    put(tool(
        "split_screen",
        "Разделённый экран: два приложения рядом (1/3 + 2/3). " +
            "action=start — запустить пару (без narrow_app/wide_app = восстановить последнюю пару); " +
            "mirror — перенести узкую панель на другую сторону; " +
            "swap — поменять приложения местами; " +
            "change — заменить приложение в панели (side = сторона экрана, app = приложение); " +
            "exit — выйти из разделённого режима.",
        JSONObject()
            .put("action", JSONObject().put("type", "string")
                .put("enum", JSONArray(listOf("start", "mirror", "swap", "change", "exit")))
                .put("description", "Операция над разделённым экраном"))
            .put("narrow_app", JSONObject().put("type", "string")
                .put("description", "Для action=start: приложение в узкой панели (1/3)"))
            .put("wide_app", JSONObject().put("type", "string")
                .put("description", "Для action=start: приложение в широкой панели (2/3)"))
            .put("side", JSONObject().put("type", "string")
                .put("enum", JSONArray(listOf("left", "right")))
                .put("description", "Для action=start: сторона узкой панели (по умолчанию right). " +
                    "Для action=change: сторона экрана заменяемой панели"))
            .put("app", JSONObject().put("type", "string")
                .put("description", "Только для action=change: новое приложение")),
        listOf("action"),
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
                                    "yandex_music", "sentry", "hotspot", "screen", "app_launch", "app_close", "media_key",
                                    "cluster_projection", "speak", "agent_query",
                                    "split_screen", "split_screen_close",
                                    "split_screen_toggle", "youtube", "go_home")))
                                .put("description", "Тип действия. split_screen_close, " +
                                    "split_screen_toggle и go_home дополнительных полей не требуют"))
                            // No second enum: the same ~90 ids already ship with
                            // vehicle_control, and repeating them here cost ~2 kB of the
                            // static prefix on every single turn.
                            .put("command_id", JSONObject()
                                .put("type", "string")
                                .put("description", "Только для kind=param: идентификатор команды " +
                                    "из списка инструмента vehicle_control"))
                            .put("value", JSONObject().put("type", "integer")
                                .put("description", "Только для kind=param: значение, если команда его требует"))
                            .put("ms", JSONObject().put("type", "integer")
                                .put("description", "Только для kind=delay: пауза в мс, 0..86400000 (до 24 ч)"))
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
                            .put("go", JSONObject().put("type", "boolean")
                                .put("description", "Только для kind=navigate: true - сразу начать " +
                                    "движение по маршруту (нажать Поехали), false - только построить"))
                            .put("url", JSONObject().put("type", "string")
                                .put("description", "Только для kind=url: ссылка со схемой, например https://"))
                            .put("mode", JSONObject().put("type", "string")
                                .put("description", "Для kind=yandex_music: режим, например mybeat. " +
                                    "Для kind=youtube: play (включить) или search (открыть поиск), по умолчанию play"))
                            .put("query", JSONObject().put("type", "string")
                                .put("description", "Только для kind=youtube: что искать на YouTube"))
                            .put("on", JSONObject().put("type", "boolean")
                                .put("description", "Для kind=sentry: включить/выключить охрану. Для kind=cluster_projection: true = вывести проекцию на приборку, false = убрать. Для kind=hotspot: включить/выключить точку доступа Wi-Fi. Для kind=screen: включить/выключить центральный экран"))
                            .put("app", JSONObject().put("type", "string")
                                .put("description", "Для kind=app_launch и kind=app_close: название приложения, как на домашнем экране. " +
                                    "app_close закрывает приложение полностью"))
                            .put("key", JSONObject().put("type", "string")
                                .put("enum", JSONArray(listOf("play", "pause")))
                                .put("description", "Только для kind=media_key: play - играть, pause - пауза. " +
                                    "Плеер должен быть уже запущен"))
                            .put("narrow_app", JSONObject().put("type", "string")
                                .put("description", "Только для kind=split_screen: приложение в узкой панели (1/3), название как на домашнем экране"))
                            .put("wide_app", JSONObject().put("type", "string")
                                .put("description", "Только для kind=split_screen: приложение в широкой панели (2/3), название как на домашнем экране"))
                            .put("side", JSONObject().put("type", "string")
                                .put("enum", JSONArray(listOf("left", "right")))
                                .put("description", "Только для kind=split_screen: сторона узкой панели, left или right (по умолчанию right)"))
                            .put("prompt", JSONObject().put("type", "string")
                                .put("description", "Только для kind=agent_query: запрос агенту, ответ будет озвучен")))
                        .put("required", JSONArray(listOf("kind")))))
                .put("cooldown_seconds", JSONObject()
                    .put("type", "integer")
                    .put("description", "Минимальный интервал между срабатываниями в секундах, минимум 1, по умолчанию 60"))
                .put("play_sound", JSONObject()
                    .put("type", "boolean")
                    .put("description", "Проиграть звуковой сигнал при срабатывании правила, по умолчанию false")),
            listOf("name", "trigger", "actions"),
        ))
    }
}

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
