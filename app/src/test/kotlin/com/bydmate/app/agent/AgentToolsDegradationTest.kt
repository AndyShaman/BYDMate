package com.bydmate.app.agent

import android.content.Context
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.AutomationEngine
import com.bydmate.app.data.automation.DispatchResult
import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.repository.PlaceRepository
import com.bydmate.app.data.remote.InsightsManager
import com.bydmate.app.data.remote.OpenRouterClient
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.domain.battery.BatteryStateRepository
import com.bydmate.app.domain.calculator.RangeCalculator
import com.bydmate.app.voice.VoiceGate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wave C Task 7: degradation matrix for the 5 new tool groups (get_vehicle_state GPS,
 * get_weather, web_search, list/set/create automations, call_contact). Covers failure
 * branches not already pinned by the per-tool test files, plus schemas()/create_automation
 * against malformed LLM input.
 */
class AgentToolsDegradationTest {

    private val gate = mockk<VoiceGate>()
    private val battery = mockk<BatteryStateRepository>()
    private val range = mockk<RangeCalculator>()
    private val tripDao = mockk<TripDao>()
    private val chargeDao = mockk<ChargeDao>()
    private val dispatcher = mockk<ActionDispatcher>(relaxed = true)
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

    // --- (a) get_weather: generic (non-UserError) geocode failure ---

    // Pin: a plain Throwable from geocode() (HTTP error, parse error) must collapse to the
    // same fixed Russian string as forecast() failures, never leak its English message.
    @Test fun get_weather_geocode_generic_failure_reports_fixed_russian_error() = runTest {
        val t = tools()
        t.locationProvider = { 55.0 to 37.0 }
        coEvery { weather.geocode("Атлантида") } returns Result.failure(RuntimeException("HTTP 500"))
        val out = JSONObject(t.execute(call("get_weather", """{"city":"Атлантида"}""")))
        assertEquals("погода недоступна", out.getString("error"))
    }

    // --- (a) web_search: SettingsRepository throwing must not escape execute() ---

    @Test fun web_search_settings_failure_reports_fixed_russian_error() = runTest {
        coEvery { settingsRepository.getString(SettingsRepository.KEY_EXA_API_KEY, "") } throws
            RuntimeException("prefs corrupt")
        val out = JSONObject(tools().execute(call("web_search", """{"query":"x"}""")))
        assertEquals("поиск недоступен", out.getString("error"))
    }

    // --- (a) create_automation: RuleDao throwing on both read paths ---

    @Test fun create_automation_getAllList_failure_reports_error_and_inserts_nothing() = runTest {
        coEvery { ruleDao.getAllList() } throws RuntimeException("db locked")
        val out = JSONObject(tools().execute(call("create_automation", CREATE_ARGS)))
        assertTrue(out.has("error"))
        coVerify(exactly = 0) { ruleDao.insert(any()) }
    }

    @Test fun create_automation_insert_failure_reports_error() = runTest {
        coEvery { ruleDao.getAllList() } returns emptyList()
        coEvery { ruleDao.insert(any()) } throws RuntimeException("db full")
        val out = JSONObject(tools().execute(call("create_automation", CREATE_ARGS)))
        assertTrue(out.has("error"))
    }

    // --- (a)/(b) schemas(): RuleDao / SettingsRepository throwing must not crash the whole
    // tool-schema round-trip (AgentOrchestrator.ask() calls schemas() unconditionally, so a
    // throw here would fail the entire turn, not just one tool result). ---

    @Test fun schemas_survives_ruleDao_getEnabled_failure() = runTest {
        coEvery { ruleDao.getEnabled() } throws RuntimeException("db locked")
        coEvery { settingsRepository.getString(SettingsRepository.KEY_EXA_API_KEY, "") } returns ""
        val arr = tools().schemas()
        assertTrue(arr.length() > 0)
        assertToolNamesParseable(arr)
    }

    @Test fun schemas_survives_settingsRepository_getString_failure() = runTest {
        coEvery { ruleDao.getEnabled() } returns emptyList()
        coEvery { settingsRepository.getString(SettingsRepository.KEY_EXA_API_KEY, "") } throws
            RuntimeException("prefs corrupt")
        val arr = tools().schemas()
        assertTrue(arr.length() > 0)
        assertToolNamesParseable(arr)
    }

    // --- (b) schemas() at fully empty state: valid, parseable, every entry recognizable ---

    @Test fun schemas_empty_state_is_valid_and_every_entry_parseable() = runTest {
        coEvery { ruleDao.getEnabled() } returns emptyList()
        coEvery { settingsRepository.getString(SettingsRepository.KEY_EXA_API_KEY, "") } returns ""
        val arr = tools().schemas()
        assertTrue(arr.length() > 0)
        assertToolNamesParseable(arr)
    }

    /** Every schemas() entry is either {"type":"function","function":{...}} or a server-tool
     *  object whose "type" field names the tool (e.g. "openrouter:web_search"); asserting this
     *  for every entry is what proves the array end-to-end parseable, not just non-empty. */
    private fun assertToolNamesParseable(arr: org.json.JSONArray) {
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.has("function")) {
                assertTrue(obj.getJSONObject("function").getString("name").isNotBlank())
            } else {
                assertTrue(obj.getString("type").isNotBlank())
            }
        }
    }

    // --- (c) create_automation: garbage/malformed LLM JSON, wrong types ---

    @Test fun create_automation_trigger_is_array_not_object_reports_error() = runTest {
        coEvery { ruleDao.getAllList() } returns emptyList()
        val out = JSONObject(tools().execute(call("create_automation",
            """{"name":"Х","trigger":[1,2,3],"actions":[{"kind":"param","command_id":"windows_close_all"}]}""")))
        assertEquals("не указан триггер", out.getString("error"))
        coVerify(exactly = 0) { ruleDao.insert(any()) }
    }

    @Test fun create_automation_actions_is_object_not_array_reports_error() = runTest {
        coEvery { ruleDao.getAllList() } returns emptyList()
        val out = JSONObject(tools().execute(call("create_automation",
            """{"name":"Х","trigger":{"kind":"service_start"},"actions":{"kind":"param"}}""")))
        assertEquals("не указаны действия", out.getString("error"))
        coVerify(exactly = 0) { ruleDao.insert(any()) }
    }

    @Test fun create_automation_action_item_is_string_not_object_reports_error() = runTest {
        coEvery { ruleDao.getAllList() } returns emptyList()
        val out = JSONObject(tools().execute(call("create_automation",
            """{"name":"Х","trigger":{"kind":"service_start"},"actions":["не объект"]}""")))
        assertEquals("некорректное описание действия", out.getString("error"))
        coVerify(exactly = 0) { ruleDao.insert(any()) }
    }

    @Test fun create_automation_empty_trigger_object_reports_error() = runTest {
        coEvery { ruleDao.getAllList() } returns emptyList()
        val out = JSONObject(tools().execute(call("create_automation",
            """{"name":"Х","trigger":{},"actions":[{"kind":"param","command_id":"windows_close_all"}]}""")))
        assertTrue(out.has("error"))
        coVerify(exactly = 0) { ruleDao.insert(any()) }
    }

    @Test fun create_automation_empty_action_object_reports_error() = runTest {
        coEvery { ruleDao.getAllList() } returns emptyList()
        val out = JSONObject(tools().execute(call("create_automation",
            """{"name":"Х","trigger":{"kind":"service_start"},"actions":[{}]}""")))
        assertTrue(out.has("error"))
        coVerify(exactly = 0) { ruleDao.insert(any()) }
    }

    @Test fun create_automation_nested_object_action_reports_error() = runTest {
        coEvery { ruleDao.getAllList() } returns emptyList()
        val out = JSONObject(tools().execute(call("create_automation",
            """{"name":"Х","trigger":{"kind":"service_start"},
                "actions":[{"kind":{"nested":"nonsense"}}]}""")))
        assertTrue(out.has("error"))
        coVerify(exactly = 0) { ruleDao.insert(any()) }
    }

    // --- FIX 1: execute() top-level guard ---

    // Pin: an unexpected throw from any branch (here: gate.vehicleSnapshot() itself, which has
    // no runCatching of its own) must collapse to the fixed internal-error JSON, not propagate.
    @Test fun execute_unexpected_exception_reports_internal_error() = runTest {
        every { gate.vehicleSnapshot() } throws RuntimeException("boom")
        val out = JSONObject(tools().execute(call("get_vehicle_state", "{}")))
        assertEquals("внутренняя ошибка инструмента", out.getString("error"))
    }

    // Pin: CancellationException must be rethrown, never swallowed into the internal-error
    // JSON — otherwise a PTT-stop mid-tool-call would silently eat the session cancellation.
    @Test(expected = CancellationException::class)
    fun execute_rethrows_cancellation_exception() = runTest {
        every { gate.vehicleSnapshot() } throws CancellationException("session stopped")
        tools().execute(call("get_vehicle_state", "{}"))
    }

    // --- runCatchingCancellable: cancellation inside a wrapped suspend call site must still
    // propagate out of execute(), not collapse into that branch's own error JSON. findByName()
    // is one of ~10 call sites in this file wrapped for a specific Russian message; a plain
    // runCatching there would swallow the CancellationException itself, before it ever reaches
    // execute()'s top-level rethrow. ---
    @Test(expected = CancellationException::class)
    fun call_contact_findByName_cancellation_propagates_not_swallowed() = runTest {
        coEvery { contactLookup.hasPermission() } returns true
        coEvery { contactLookup.findByName(any()) } throws CancellationException("session stopped")
        tools().execute(call("call_contact", """{"name":"Мама"}"""))
    }

    // Pin: runCatchingCancellable must keep runCatching's Throwable scope. A device-only Error
    // (NoSuchMethodError passes JVM tests but throws at runtime — see JSONObject.put(Float)
    // precedent) must map to the call site's Russian error, not escape the tool call.
    @Test fun call_contact_findByName_error_throwable_maps_to_site_error() = runTest {
        coEvery { contactLookup.hasPermission() } returns true
        coEvery { contactLookup.findByName(any()) } throws NoSuchMethodError("device-only")
        val raw = tools().execute(call("call_contact", """{"name":"Мама"}"""))
        assertEquals("не удалось прочитать контакты", JSONObject(raw).getString("error"))
    }

    // --- FIX 2: call action displayName must never carry the phone number ---

    // Pin: an automation created with a "call" action, then read back via list_automations
    // (which serializes ActionDef.displayName straight to the LLM), must not leak any digit
    // of the phone number — only the payload (never sent to the LLM) carries it.
    @Test fun create_automation_call_action_then_list_automations_hides_phone() = runTest {
        coEvery { ruleDao.getAllList() } returns emptyList()
        val slot = slot<RuleEntity>()
        coEvery { ruleDao.insert(capture(slot)) } returns 1L
        tools().execute(call("create_automation",
            """{"name":"Позвони маме","trigger":{"kind":"service_start"},
                "actions":[{"kind":"call","phone":"+79161234567"}]}"""))

        coEvery { ruleDao.getAllList() } returns listOf(slot.captured)
        val raw = tools().execute(call("list_automations", "{}"))
        assertFalse(raw.contains("79161234567"))
        assertFalse(Regex("[0-9]{4,}").containsMatchIn(raw))
    }

    // --- FIX 3: dispatchJson() Cyrillic whitelist ---

    @Test fun vehicle_control_dispatch_failure_english_reason_collapses_to_fixed_error() = runTest {
        every { gate.vehicleSnapshot() } returns null
        coEvery { dispatcher.dispatch(any(), any()) } returns
            DispatchResult(false, "Activity not found: tel:12345")
        val out = JSONObject(tools().execute(call("vehicle_control", """{"command":"关闭空调"}""")))
        assertEquals("не выполнено", out.getString("error"))
    }

    @Test fun vehicle_control_dispatch_failure_russian_reason_passes_through() = runTest {
        every { gate.vehicleSnapshot() } returns null
        coEvery { dispatcher.dispatch(any(), any()) } returns
            DispatchResult(false, "Заблокировано скоростью")
        val out = JSONObject(tools().execute(call("vehicle_control", """{"command":"关闭空调"}""")))
        assertEquals("Заблокировано скоростью", out.getString("error"))
    }

    private companion object {
        const val CREATE_ARGS = """{"name":"Ночной свет","trigger":{"kind":"param","param":"SOC",
            "operator":"<","value":"20"},"actions":[{"kind":"param","command_id":"windows_close_all"}]}"""
    }
}
