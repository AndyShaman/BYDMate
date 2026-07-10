package com.bydmate.app.agent

import android.content.Context
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.AutomationEngine
import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.automation.ScheduleSpec
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.PlaceEntity
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.TriggerDef
import com.bydmate.app.data.repository.PlaceRepository
import com.bydmate.app.data.remote.InsightsManager
import com.bydmate.app.data.remote.OpenRouterClient
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.domain.battery.BatteryStateRepository
import com.bydmate.app.domain.calculator.RangeCalculator
import com.bydmate.app.voice.VoiceGate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolsAutomationTest {

    private val gate = mockk<VoiceGate>()
    private val battery = mockk<BatteryStateRepository>()
    private val range = mockk<RangeCalculator>()
    private val tripDao = mockk<TripDao>()
    private val chargeDao = mockk<ChargeDao>()
    private val dispatcher = mockk<ActionDispatcher>()
    private val ruleDao = mockk<RuleDao>()
    private val engine = mockk<AutomationEngine>()
    private val places = mockk<PlaceRepository>()
    private val weather = mockk<WeatherClient>()
    private val exa = mockk<ExaSearchClient>()
    private val openRouterClient = mockk<OpenRouterClient>()
    private val settingsRepository = mockk<SettingsRepository>()
    private val contactLookup = mockk<ContactLookup>()

    private val context = mockk<Context>(relaxed = true)

    private fun tools() = AgentTools(
        gate, battery, range, tripDao, chargeDao, dispatcher, ruleDao, engine, places, weather,
        exa, openRouterClient, settingsRepository, contactLookup, context,
        mockk<ClusterVoiceControl>(relaxed = true),
        mockk<ChargerSearchClient>(relaxed = true),
        mockk<InsightsManager>(relaxed = true),
        mockk<ZaiSearchClient>(relaxed = true),
        mockk<LlmConnectionResolver>(relaxed = true),
    )

    private fun call(name: String, args: String) = AgentToolCall("1", name, args)

    private fun rule(
        id: Long,
        name: String,
        enabled: Boolean,
        triggerDisplay: String = "SOC < 20%",
        actionDisplay: String = "Закрыть окна",
    ) = RuleEntity(
        id = id,
        name = name,
        enabled = enabled,
        triggers = TriggerDef.listToJson(listOf(
            TriggerDef(param = "soc", chineseName = "电量", operator = "<", value = "20",
                displayName = triggerDisplay))),
        actions = ActionDef.listToJson(listOf(
            ActionDef(command = "windows_close_all", displayName = actionDisplay))),
    )

    // --- list_automations ---

    @Test fun list_automations_empty() = runTest {
        coEvery { ruleDao.getAllList() } returns emptyList()
        val out = JSONObject(tools().execute(call("list_automations", "{}")))
        assertEquals(0, out.getJSONArray("automations").length())
    }

    @Test fun list_automations_includes_disabled_with_display_names() = runTest {
        coEvery { ruleDao.getAllList() } returns listOf(
            rule(1, "Ночной режим", enabled = true),
            rule(2, "Выключено", enabled = false),
        )
        val out = JSONObject(tools().execute(call("list_automations", "{}")))
        val arr = out.getJSONArray("automations")
        assertEquals(2, arr.length())
        val first = arr.getJSONObject(0)
        assertEquals("Ночной режим", first.getString("name"))
        assertTrue(first.getBoolean("enabled"))
        assertEquals("SOC < 20%", first.getString("triggers"))
        assertEquals("Закрыть окна", first.getString("actions"))
        val second = arr.getJSONObject(1)
        assertEquals("Выключено", second.getString("name"))
        assertTrue(!second.getBoolean("enabled"))
    }

    // --- set_automation_enabled ---

    @Test fun set_automation_enabled_true_matches_case_insensitive() = runTest {
        coEvery { ruleDao.getAllList() } returns listOf(rule(5, "Проветрить", enabled = false))
        coEvery { ruleDao.setEnabled(5, true) } returns Unit
        val out = JSONObject(tools().execute(
            call("set_automation_enabled", """{"name":"проветрить","enabled":true}""")))
        assertTrue(out.getBoolean("ok"))
        assertEquals("проветрить", out.getString("name"))
        assertTrue(out.getBoolean("enabled"))
        coVerify { ruleDao.setEnabled(5, true) }
    }

    @Test fun set_automation_enabled_false() = runTest {
        coEvery { ruleDao.getAllList() } returns listOf(rule(6, "Фрунк", enabled = true))
        coEvery { ruleDao.setEnabled(6, false) } returns Unit
        val out = JSONObject(tools().execute(
            call("set_automation_enabled", """{"name":"Фрунк","enabled":false}""")))
        assertTrue(out.getBoolean("ok"))
        assertTrue(!out.getBoolean("enabled"))
        coVerify { ruleDao.setEnabled(6, false) }
    }

    @Test fun set_automation_enabled_not_found() = runTest {
        coEvery { ruleDao.getAllList() } returns emptyList()
        val out = JSONObject(tools().execute(
            call("set_automation_enabled", """{"name":"нет такой","enabled":true}""")))
        assertTrue(out.has("error"))
        coVerify(exactly = 0) { ruleDao.setEnabled(any(), any()) }
    }

    @Test fun set_automation_enabled_duplicate_name_reports_error() = runTest {
        coEvery { ruleDao.getAllList() } returns listOf(
            rule(1, "Дубль", enabled = true),
            rule(2, "дубль", enabled = false),
        )
        val out = JSONObject(tools().execute(
            call("set_automation_enabled", """{"name":"Дубль","enabled":true}""")))
        assertTrue(out.has("error"))
        coVerify(exactly = 0) { ruleDao.setEnabled(any(), any()) }
    }

    // --- exception safety + strict arg parsing (fix wave) ---

    @Test fun list_automations_dao_failure_returns_error_not_exception() = runTest {
        coEvery { ruleDao.getAllList() } throws RuntimeException("db locked")
        val out = JSONObject(tools().execute(call("list_automations", "{}")))
        assertTrue(out.has("error"))
    }

    @Test fun set_automation_enabled_dao_failure_returns_error_not_exception() = runTest {
        coEvery { ruleDao.getAllList() } throws RuntimeException("db locked")
        val out = JSONObject(tools().execute(
            call("set_automation_enabled", """{"name":"Фрунк","enabled":true}""")))
        assertTrue(out.has("error"))
    }

    @Test fun set_automation_enabled_missing_enabled_key_reports_error() = runTest {
        coEvery { ruleDao.getAllList() } returns listOf(rule(6, "Фрунк", enabled = true))
        val out = JSONObject(tools().execute(
            call("set_automation_enabled", """{"name":"Фрунк"}""")))
        assertEquals("не указано значение enabled", out.getString("error"))
        coVerify(exactly = 0) { ruleDao.setEnabled(any(), any()) }
    }

    @Test fun set_automation_enabled_string_true_works() = runTest {
        coEvery { ruleDao.getAllList() } returns listOf(rule(6, "Фрунк", enabled = false))
        coEvery { ruleDao.setEnabled(6, true) } returns Unit
        val out = JSONObject(tools().execute(
            call("set_automation_enabled", """{"name":"Фрунк","enabled":"true"}""")))
        assertTrue(out.getBoolean("ok"))
        assertTrue(out.getBoolean("enabled"))
        coVerify { ruleDao.setEnabled(6, true) }
    }

    @Test fun set_automation_enabled_empty_name_reports_russian_error() = runTest {
        val out = JSONObject(tools().execute(
            call("set_automation_enabled", """{"name":"","enabled":true}""")))
        assertEquals("не указано имя", out.getString("error"))
        coVerify(exactly = 0) { ruleDao.setEnabled(any(), any()) }
    }

    // --- create_automation ---

    private fun createArgs(
        name: String = "Ночной свет",
        trigger: String = """{"kind":"param","param":"SOC","operator":"<","value":"20"}""",
        actions: String = """[{"kind":"param","command_id":"windows_close_all"}]""",
        cooldown: String = "",
    ) = """{"name":"$name","trigger":$trigger,"actions":$actions$cooldown}"""

    @Test fun create_automation_happy_path_param_trigger_and_action() = runTest {
        coEvery { ruleDao.getAllList() } returns emptyList()
        val slot = slot<RuleEntity>()
        coEvery { ruleDao.insert(capture(slot)) } returns 1L

        val out = JSONObject(tools().execute(call("create_automation", createArgs())))

        assertTrue(out.getBoolean("ok"))
        assertEquals("Ночной свет", out.getString("name"))
        assertTrue(out.getString("hint").contains("Ночной свет"))

        val saved = slot.captured
        assertEquals("Ночной свет", saved.name)
        assertTrue(saved.enabled)
        assertEquals(60, saved.cooldownSeconds)
        val triggers = TriggerDef.listFromJson(saved.triggers)
        assertEquals(1, triggers.size)
        assertEquals("SOC", triggers[0].param)
        assertEquals("<", triggers[0].operator)
        assertEquals("20", triggers[0].value)
        val actions = ActionDef.listFromJson(saved.actions)
        assertEquals(1, actions.size)
        assertEquals("车窗关闭", actions[0].command)
        assertEquals("param", actions[0].kind)
    }

    @Test fun create_automation_place_trigger_found_sets_place_id_and_name() = runTest {
        coEvery { ruleDao.getAllList() } returns emptyList()
        coEvery { places.getAllSnapshot() } returns listOf(PlaceEntity(id = 3, name = "Дом", lat = 55.0, lon = 37.0))
        val slot = slot<RuleEntity>()
        coEvery { ruleDao.insert(capture(slot)) } returns 1L

        val out = JSONObject(tools().execute(call("create_automation", createArgs(
            trigger = """{"kind":"place_enter","place_name":"дом"}"""))))

        assertTrue(out.getBoolean("ok"))
        val trigger = TriggerDef.listFromJson(slot.captured.triggers).first()
        assertEquals(3L, trigger.placeId)
        assertEquals("Дом", trigger.placeName)
        assertEquals("place_enter", trigger.kind)
    }

    @Test fun create_automation_place_trigger_not_found_reports_error() = runTest {
        coEvery { ruleDao.getAllList() } returns emptyList()
        coEvery { places.getAllSnapshot() } returns emptyList()

        val out = JSONObject(tools().execute(call("create_automation", createArgs(
            trigger = """{"kind":"place_exit","place_name":"Дача"}"""))))

        assertEquals("место не найдено: Дача", out.getString("error"))
        coVerify(exactly = 0) { ruleDao.insert(any()) }
    }

    @Test fun create_automation_invalid_trigger_kind_reports_error() = runTest {
        coEvery { ruleDao.getAllList() } returns emptyList()

        // "button_press" became a real kind, so pin the else-branch with a
        // genuinely unknown kind.
        val out = JSONObject(tools().execute(call("create_automation", createArgs(
            trigger = """{"kind":"foo"}"""))))

        assertTrue(out.getString("error").contains("недопустимый тип триггера"))
        coVerify(exactly = 0) { ruleDao.insert(any()) }
    }

    @Test fun create_automation_duplicate_name_reports_error() = runTest {
        coEvery { ruleDao.getAllList() } returns listOf(rule(1, "ночной свет", enabled = true))

        val out = JSONObject(tools().execute(call("create_automation", createArgs(name = "Ночной свет"))))

        assertTrue(out.has("error"))
        coVerify(exactly = 0) { ruleDao.insert(any()) }
    }

    @Test fun create_automation_cap_50_reports_error() = runTest {
        coEvery { ruleDao.getAllList() } returns List(50) { rule(it.toLong(), "r$it", enabled = true) }

        val out = JSONObject(tools().execute(call("create_automation", createArgs())))

        assertTrue(out.has("error"))
        coVerify(exactly = 0) { ruleDao.insert(any()) }
    }

    @Test fun create_automation_invalid_phone_reports_error() = runTest {
        coEvery { ruleDao.getAllList() } returns emptyList()

        val out = JSONObject(tools().execute(call("create_automation", createArgs(
            actions = """[{"kind":"call","phone":"123"}]"""))))

        assertTrue(out.has("error"))
        coVerify(exactly = 0) { ruleDao.insert(any()) }
    }

    @Test fun create_automation_invalid_url_reports_error() = runTest {
        coEvery { ruleDao.getAllList() } returns emptyList()

        val out = JSONObject(tools().execute(call("create_automation", createArgs(
            actions = """[{"kind":"url","url":"example.com"}]"""))))

        assertTrue(out.has("error"))
        coVerify(exactly = 0) { ruleDao.insert(any()) }
    }

    @Test fun create_automation_cooldown_is_coerced_to_minimum_30() = runTest {
        coEvery { ruleDao.getAllList() } returns emptyList()
        val slot = slot<RuleEntity>()
        coEvery { ruleDao.insert(capture(slot)) } returns 1L

        tools().execute(call("create_automation", createArgs(cooldown = ""","cooldown_seconds":10""")))

        assertEquals(30, slot.captured.cooldownSeconds)
    }

    // --- create_automation: payload contract fix wave ---

    @Test fun create_automation_call_action_sets_auto_dial() = runTest {
        coEvery { ruleDao.getAllList() } returns emptyList()
        val slot = slot<RuleEntity>()
        coEvery { ruleDao.insert(capture(slot)) } returns 1L

        tools().execute(call("create_automation", createArgs(
            actions = """[{"kind":"call","phone":"+79161234567"}]""")))

        val action = ActionDef.listFromJson(slot.captured.actions).first()
        assertTrue(JSONObject(action.payload!!).getBoolean("autoDial"))
    }

    @Test fun create_automation_media_volume_mute_is_accepted() = runTest {
        coEvery { ruleDao.getAllList() } returns emptyList()
        coEvery { ruleDao.insert(any()) } returns 1L

        val out = JSONObject(tools().execute(call("create_automation", createArgs(
            actions = """[{"kind":"media_volume","op":"mute"}]"""))))

        assertTrue(out.getBoolean("ok"))
    }

    @Test fun create_automation_media_volume_relative_step_is_accepted() = runTest {
        coEvery { ruleDao.getAllList() } returns emptyList()
        coEvery { ruleDao.insert(any()) } returns 1L

        val out = JSONObject(tools().execute(call("create_automation", createArgs(
            actions = """[{"kind":"media_volume","op":"-2"}]"""))))

        assertTrue(out.getBoolean("ok"))
    }

    @Test fun create_automation_media_volume_garbage_reports_russian_error() = runTest {
        coEvery { ruleDao.getAllList() } returns emptyList()

        val out = JSONObject(tools().execute(call("create_automation", createArgs(
            actions = """[{"kind":"media_volume","op":"loud"}]"""))))

        assertEquals("не указан уровень громкости (действие 1)", out.getString("error"))
        coVerify(exactly = 0) { ruleDao.insert(any()) }
    }

    @Test fun create_automation_delay_without_ms_reports_russian_error() = runTest {
        coEvery { ruleDao.getAllList() } returns emptyList()

        val out = JSONObject(tools().execute(call("create_automation", createArgs(
            actions = """[{"kind":"delay"}]"""))))

        assertEquals("не указана длительность паузы (мс, 0..30000)", out.getString("error"))
        coVerify(exactly = 0) { ruleDao.insert(any()) }
    }

    @Test fun create_automation_navigate_missing_lon_reports_russian_error() = runTest {
        coEvery { ruleDao.getAllList() } returns emptyList()

        val out = JSONObject(tools().execute(call("create_automation", createArgs(
            actions = """[{"kind":"navigate","lat":55.7}]"""))))

        assertEquals("не указаны координаты lat/lon", out.getString("error"))
        coVerify(exactly = 0) { ruleDao.insert(any()) }
    }

    @Test fun create_automation_yandex_music_without_mode_reports_russian_error() = runTest {
        coEvery { ruleDao.getAllList() } returns emptyList()

        val out = JSONObject(tools().execute(call("create_automation", createArgs(
            actions = """[{"kind":"yandex_music"}]"""))))

        assertEquals("не указан режим Я.Музыки", out.getString("error"))
        coVerify(exactly = 0) { ruleDao.insert(any()) }
    }

    @Test fun create_automation_sentry_without_on_reports_russian_error() = runTest {
        coEvery { ruleDao.getAllList() } returns emptyList()

        val out = JSONObject(tools().execute(call("create_automation", createArgs(
            actions = """[{"kind":"sentry"}]"""))))

        assertEquals("не указано состояние охранного режима", out.getString("error"))
        coVerify(exactly = 0) { ruleDao.insert(any()) }
    }

    // --- create_automation: voice and button_press triggers ---

    @Test fun `create with voice trigger saves phrase`() = runTest {
        coEvery { ruleDao.getAllList() } returns emptyList()
        val saved = slot<RuleEntity>()
        coEvery { ruleDao.insert(capture(saved)) } returns 1L
        val out = JSONObject(tools().execute(call("create_automation",
            createArgs(trigger = """{"kind":"voice","phrase":"тёплый приём"}"""))))
        assertTrue(out.getBoolean("ok"))
        val t = TriggerDef.listFromJson(saved.captured.triggers).single()
        assertEquals("voice", t.kind)
        assertEquals("Voice", t.param)
        assertEquals("тёплый приём", t.value)
        assertEquals("тёплый приём", t.displayName)
    }

    @Test fun `voice trigger with empty phrase is rejected`() = runTest {
        coEvery { ruleDao.getAllList() } returns emptyList()
        val out = JSONObject(tools().execute(call("create_automation",
            createArgs(trigger = """{"kind":"voice","phrase":"  "}"""))))
        assertTrue(out.has("error"))
    }

    @Test fun `voice trigger colliding with builtin command is rejected`() = runTest {
        coEvery { ruleDao.getAllList() } returns emptyList()
        val out = JSONObject(tools().execute(call("create_automation",
            createArgs(trigger = """{"kind":"voice","phrase":"открой окна"}"""))))
        assertTrue(out.getString("error").contains("встроенн"))
    }

    @Test fun `voice trigger taken by another rule is rejected`() = runTest {
        val voiceTrigger = TriggerDef(param = "Voice", chineseName = "语音", operator = "==",
            value = "тёплый приём", displayName = "тёплый приём", kind = "voice")
        coEvery { ruleDao.getAllList() } returns listOf(rule(id = 7L, name = "Voice rule", enabled = true).copy(
            triggers = TriggerDef.listToJson(listOf(voiceTrigger))))
        val out = JSONObject(tools().execute(call("create_automation",
            createArgs(trigger = """{"kind":"voice","phrase":"Тёплый приём!"}"""))))
        assertTrue(out.getString("error").contains("уже использ"))
    }

    @Test fun `create with button press trigger saves button number`() = runTest {
        coEvery { ruleDao.getAllList() } returns emptyList()
        val saved = slot<RuleEntity>()
        coEvery { ruleDao.insert(capture(saved)) } returns 1L
        val out = JSONObject(tools().execute(call("create_automation",
            createArgs(trigger = """{"kind":"button_press","button":2}"""))))
        assertTrue(out.getBoolean("ok"))
        val t = TriggerDef.listFromJson(saved.captured.triggers).single()
        assertEquals("button_press", t.kind)
        assertEquals("button", t.param)
        assertEquals("2", t.value)
    }

    @Test fun `button press trigger with invalid number is rejected`() = runTest {
        coEvery { ruleDao.getAllList() } returns emptyList()
        for (arg in listOf("""{"kind":"button_press","button":0}""",
                           """{"kind":"button_press","button":5}""",
                           """{"kind":"button_press"}""")) {
            val out = JSONObject(tools().execute(call("create_automation", createArgs(trigger = arg))))
            assertTrue(out.has("error"))
        }
    }

    // --- create_automation: app_launch action and time_range weekdays ---

    @Test fun `create with app_launch action resolves app by label`() = runTest {
        coEvery { ruleDao.getAllList() } returns emptyList()
        val saved = slot<RuleEntity>()
        coEvery { ruleDao.insert(capture(saved)) } returns 1L
        val t = tools()
        t.launcherAppsProvider = { listOf("Шахматы" to "com.example.chess", "Радио" to "com.example.radio") }
        val out = JSONObject(t.execute(call("create_automation",
            createArgs(actions = """[{"kind":"app_launch","app":"шахматы"}]"""))))
        assertTrue(out.getBoolean("ok"))
        val a = ActionDef.listFromJson(saved.captured.actions).single()
        assertEquals("app_launch", a.kind)
        val payload = JSONObject(a.payload!!)
        assertEquals("com.example.chess", payload.getString("packageName"))
        assertEquals("Шахматы", payload.getString("appLabel"))
    }

    @Test fun `app_launch action with unknown app is rejected`() = runTest {
        coEvery { ruleDao.getAllList() } returns emptyList()
        val t = tools()
        t.launcherAppsProvider = { listOf("Шахматы" to "com.example.chess") }
        val out = JSONObject(t.execute(call("create_automation",
            createArgs(actions = """[{"kind":"app_launch","app":"тетрис"}]"""))))
        assertTrue(out.getString("error").contains("не найдено"))
    }

    @Test fun `app_launch action without app field is rejected`() = runTest {
        coEvery { ruleDao.getAllList() } returns emptyList()
        val out = JSONObject(tools().execute(call("create_automation",
            createArgs(actions = """[{"kind":"app_launch"}]"""))))
        assertTrue(out.has("error"))
    }

    @Test fun `time_range trigger saves weekdays`() = runTest {
        coEvery { ruleDao.getAllList() } returns emptyList()
        val saved = slot<RuleEntity>()
        coEvery { ruleDao.insert(capture(saved)) } returns 1L
        val out = JSONObject(tools().execute(call("create_automation",
            createArgs(trigger = """{"kind":"time_range","value":"08:00-10:00","weekdays":[1,2,3,4,5]}"""))))
        assertTrue(out.getBoolean("ok"))
        val t = TriggerDef.listFromJson(saved.captured.triggers).single()
        val spec = ScheduleSpec.fromJson(t.value)!!
        assertEquals(setOf(1, 2, 3, 4, 5), spec.days)
    }

    @Test fun `time_range trigger without weekdays keeps every-day default`() = runTest {
        coEvery { ruleDao.getAllList() } returns emptyList()
        val saved = slot<RuleEntity>()
        coEvery { ruleDao.insert(capture(saved)) } returns 1L
        val out = JSONObject(tools().execute(call("create_automation",
            createArgs(trigger = """{"kind":"time_range","value":"08:00-10:00"}"""))))
        assertTrue(out.getBoolean("ok"))
        val spec = ScheduleSpec.fromJson(TriggerDef.listFromJson(saved.captured.triggers).single().value)!!
        assertTrue(spec.days.isEmpty())
    }

    @Test fun `time_range trigger with invalid weekday is rejected`() = runTest {
        coEvery { ruleDao.getAllList() } returns emptyList()
        val out = JSONObject(tools().execute(call("create_automation",
            createArgs(trigger = """{"kind":"time_range","value":"08:00-10:00","weekdays":[0,8]}"""))))
        assertTrue(out.has("error"))
    }

    @Test fun create_automation_notification_action_normalizes_legacy_kind_and_play_sound() = runTest {
        coEvery { ruleDao.getAllList() } returns emptyList()
        val saved = slot<RuleEntity>()
        coEvery { ruleDao.insert(capture(saved)) } returns 1L
        val out = JSONObject(tools().execute(AgentToolCall("1", "create_automation",
            """{"name":"тестзвук","trigger":{"kind":"voice","phrase":"тест"},"actions":[{"kind":"notification_sound","title":"Привет","text":"мир"}],"play_sound":true}""")))
        assertTrue(out.optBoolean("ok"))
        val actions = ActionDef.listFromJson(saved.captured.actions)
        assertEquals("notification", actions[0].kind)
        assertEquals("notification", actions[0].command)
        assertTrue(saved.captured.playSound)
    }

    @Test fun create_automation_schema_time_of_day_value_has_enum() = runTest {
        coEvery { ruleDao.getEnabled() } returns emptyList()
        coEvery { settingsRepository.getString(SettingsRepository.KEY_EXA_API_KEY, "") } returns "exa-key"

        val arr = tools().schemas()
        var found = false
        for (i in 0 until arr.length()) {
            val fn = arr.getJSONObject(i).getJSONObject("function")
            if (fn.getString("name") == "create_automation") {
                val valueSchema = fn.getJSONObject("parameters").getJSONObject("properties")
                    .getJSONObject("trigger").getJSONObject("properties").getJSONObject("value")
                // Must be a real JSON "enum" array, not just a text description mentioning
                // the values — walk anyOf branches (or the property itself) looking for one.
                val candidates = if (valueSchema.has("anyOf")) {
                    val anyOf = valueSchema.getJSONArray("anyOf")
                    (0 until anyOf.length()).map { anyOf.getJSONObject(it) }
                } else {
                    listOf(valueSchema)
                }
                val enumValues = candidates.firstOrNull { it.has("enum") }
                    ?.getJSONArray("enum")
                    ?.let { e -> (0 until e.length()).map { e.getString(it) } }
                assertEquals(listOf("dawn", "day", "dusk", "night"), enumValues)
                found = true
            }
        }
        assertTrue(found)
    }
}
