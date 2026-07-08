package com.bydmate.app.agent

import android.content.Context
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.AutomationEngine
import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.remote.InsightsManager
import com.bydmate.app.data.remote.OpenRouterClient
import com.bydmate.app.data.repository.PlaceRepository
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.domain.battery.BatteryStateRepository
import com.bydmate.app.domain.calculator.RangeCalculator
import com.bydmate.app.voice.VoiceGate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolsSearchTest {

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
    private val zaiSearchClient = mockk<ZaiSearchClient>()
    private val llmConnections = mockk<LlmConnectionResolver>()

    private val context = mockk<Context>(relaxed = true)

    private fun tools() = AgentTools(
        gate, battery, range, tripDao, chargeDao, dispatcher, ruleDao, engine, places, weather,
        exa, openRouterClient, settingsRepository, contactLookup, context,
        mockk<ClusterVoiceControl>(relaxed = true),
        mockk<ChargerSearchClient>(relaxed = true),
        mockk<InsightsManager>(relaxed = true),
        zaiSearchClient,
        llmConnections,
    )

    private fun toolNames(arr: JSONArray): List<String> {
        val names = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            names += arr.getJSONObject(i).getJSONObject("function").getString("name")
        }
        return names
    }

    // (a) empty Exa key -> web_search declared as a function tool, no server-tool object.
    @Test fun schemas_without_exa_key_declares_web_search_function_tool() = runTest {
        coEvery { ruleDao.getEnabled() } returns emptyList()
        coEvery { settingsRepository.getString(SettingsRepository.KEY_EXA_API_KEY, "") } returns ""
        val arr = tools().schemas()
        assertTrue(toolNames(arr).contains("web_search"))
        for (i in 0 until arr.length()) {
            assertFalse(arr.getJSONObject(i).optString("type") == "openrouter:web_search")
        }
    }

    // (b) Exa key present -> same unconditional web_search, no server-tool either.
    @Test fun schemas_with_exa_key_declares_same_web_search_function_tool() = runTest {
        coEvery { ruleDao.getEnabled() } returns emptyList()
        coEvery { settingsRepository.getString(SettingsRepository.KEY_EXA_API_KEY, "") } returns "exa-key-123"
        val arr = tools().schemas()
        assertTrue(toolNames(arr).contains("web_search"))
        for (i in 0 until arr.length()) {
            assertFalse(arr.getJSONObject(i).optString("type") == "openrouter:web_search")
        }
    }

    // (1) Exa key present, Exa succeeds -> Exa result, zai/openrouter never called.
    @Test fun execute_web_search_exa_success_short_circuits_native_fallback() = runTest {
        coEvery { settingsRepository.getString(SettingsRepository.KEY_EXA_API_KEY, "") } returns "exa-key-123"
        coEvery { exa.search("exa-key-123", "погода в Москве") } returns Result.success("""{"results":[]}""")
        val out = JSONObject(tools().execute(
            AgentToolCall("1", "web_search", """{"query":"погода в Москве"}""")))
        assertTrue(out.has("results"))
        coVerify(exactly = 1) { exa.search("exa-key-123", "погода в Москве") }
        coVerify(exactly = 0) { zaiSearchClient.search(any(), any()) }
        coVerify(exactly = 0) { openRouterClient.chatRaw(any(), any(), any(), any(), any()) }
    }

    // (2) Exa key present, Exa fails -> falls back to the primary connection's native search,
    // not straight to the fixed error.
    @Test fun execute_web_search_exa_failure_falls_back_to_native_primary() = runTest {
        coEvery { settingsRepository.getString(SettingsRepository.KEY_EXA_API_KEY, "") } returns "exa-key-123"
        coEvery { exa.search(any(), any()) } returns Result.failure(RuntimeException("HTTP 401"))
        val conn = LlmConnection(
            LlmConnectionResolver.ID_ZAI, "z.ai", LlmConnectionResolver.ZAI_BASE_URL, "zk", LlmConnectionResolver.ZAI_MODEL,
        )
        coEvery { llmConnections.primary() } returns conn
        coEvery { zaiSearchClient.search("zk", "x") } returns
            Result.success("""{"results":[{"title":"T","url":"u","text":"t"}]}""")
        val out = JSONObject(tools().execute(AgentToolCall("1", "web_search", """{"query":"x"}""")))
        assertTrue(out.has("results"))
        coVerify(exactly = 1) { zaiSearchClient.search("zk", "x") }
    }

    // (3) No Exa key, primary = zai -> zaiSearchClient.search called with the z.ai key.
    @Test fun execute_web_search_without_exa_primary_zai_calls_zai_client() = runTest {
        coEvery { settingsRepository.getString(SettingsRepository.KEY_EXA_API_KEY, "") } returns ""
        val conn = LlmConnection(
            LlmConnectionResolver.ID_ZAI, "z.ai", LlmConnectionResolver.ZAI_BASE_URL, "zk", LlmConnectionResolver.ZAI_MODEL,
        )
        coEvery { llmConnections.primary() } returns conn
        coEvery { zaiSearchClient.search("zk", "погода в Минске") } returns Result.success("""{"results":[]}""")
        val out = JSONObject(
            tools().execute(AgentToolCall("1", "web_search", """{"query":"погода в Минске"}""")),
        )
        assertTrue(out.has("results"))
        coVerify(exactly = 1) { zaiSearchClient.search("zk", "погода в Минске") }
    }

    // (4) No Exa key, primary = openrouter -> chatRaw called with the openrouter:web_search
    // server tool; result is the response content.
    @Test fun execute_web_search_without_exa_primary_openrouter_uses_server_tool() = runTest {
        coEvery { settingsRepository.getString(SettingsRepository.KEY_EXA_API_KEY, "") } returns ""
        val conn = LlmConnection(
            LlmConnectionResolver.ID_OPENROUTER, "OpenRouter", LlmConnectionResolver.OPENROUTER_BASE_URL, "or-key", "m",
        )
        coEvery { llmConnections.primary() } returns conn
        val message = JSONObject().put("content", "в Москве +20")
        coEvery {
            openRouterClient.chatRaw(
                LlmConnectionResolver.OPENROUTER_BASE_URL, "or-key", "m", any(),
                match<JSONArray> { toolsArg ->
                    toolsArg.length() == 1 && toolsArg.getJSONObject(0).getString("type") == "openrouter:web_search"
                },
            )
        } returns Result.success(message)
        val out = tools().execute(AgentToolCall("1", "web_search", """{"query":"погода в Москве"}"""))
        assertEquals("в Москве +20", out)
    }

    // (5) No Exa key, primary = custom -> fixed error (custom connections have no native search).
    @Test fun execute_web_search_without_exa_primary_custom_reports_fixed_error() = runTest {
        coEvery { settingsRepository.getString(SettingsRepository.KEY_EXA_API_KEY, "") } returns ""
        val conn = LlmConnection(LlmConnectionResolver.ID_CUSTOM, "Своё", "https://example.com/v1", "ck", "custom-model")
        coEvery { llmConnections.primary() } returns conn
        val out = JSONObject(tools().execute(AgentToolCall("1", "web_search", """{"query":"x"}""")))
        assertEquals("поиск недоступен", out.getString("error"))
        coVerify(exactly = 0) { zaiSearchClient.search(any(), any()) }
        coVerify(exactly = 0) { openRouterClient.chatRaw(any(), any(), any(), any(), any()) }
    }

    // (6) No Exa key, primary not configured -> fixed error.
    @Test fun execute_web_search_without_exa_no_primary_reports_fixed_error() = runTest {
        coEvery { settingsRepository.getString(SettingsRepository.KEY_EXA_API_KEY, "") } returns ""
        coEvery { llmConnections.primary() } returns null
        val out = JSONObject(tools().execute(AgentToolCall("1", "web_search", """{"query":"x"}""")))
        assertEquals("поиск недоступен", out.getString("error"))
    }
}
