package com.bydmate.app.agent

import android.content.Context
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.AutomationEngine
import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.remote.InsightsManager
import com.bydmate.app.data.remote.OpenRouterClient
import com.bydmate.app.data.repository.PlaceRepository
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.domain.battery.BatteryStateRepository
import com.bydmate.app.domain.calculator.RangeCalculator
import com.bydmate.app.voice.VoiceGate
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wave 2: the static prefix the model re-reads on every turn. The command catalog must be
 * serialized once, and the only data-driven part (the run_automation enum) must be stable
 * between rule edits — both are paid for on every single voice command.
 */
class AgentSchemaPrefixTest {

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
    ).also { it.ruleWatchScope = CoroutineScope(Dispatchers.Unconfined) }

    private fun function(schemas: JSONArray, name: String): JSONObject? =
        (0 until schemas.length()).map { schemas.getJSONObject(it).getJSONObject("function") }
            .firstOrNull { it.getString("name") == name }

    @Test fun command_catalog_is_serialized_once() = runTest {
        coEvery { ruleDao.getEnabled() } returns emptyList()
        every { ruleDao.getAll() } returns MutableStateFlow(emptyList())
        coEvery { settingsRepository.getString(SettingsRepository.KEY_EXA_API_KEY, "") } returns "exa-key"
        val schemas = tools().schemas()

        // The full id enum belongs to vehicle_control and to nothing else.
        val enum = function(schemas, "vehicle_control")!!
            .getJSONObject("parameters").getJSONObject("properties")
            .getJSONObject("command").getJSONArray("enum")
        assertEquals(AgentCommandCatalog.ALL.size, enum.length())

        val commandId = function(schemas, "create_automation")!!
            .getJSONObject("parameters").getJSONObject("properties")
            .getJSONObject("actions").getJSONObject("items").getJSONObject("properties")
            .getJSONObject("command_id")
        assertFalse("command_id must reference the vehicle_control list, not repeat it",
            commandId.has("enum"))
        assertTrue(commandId.getString("description").contains("vehicle_control"))
    }

    @Test fun schemas_are_byte_stable_until_a_rule_changes() = runTest {
        val rules = MutableStateFlow(listOf(
            RuleEntity(id = 1, name = "Ночной режим", triggers = "[]", actions = "[]"),
        ))
        coEvery { ruleDao.getEnabled() } returns rules.value
        every { ruleDao.getAll() } returns rules
        coEvery { settingsRepository.getString(SettingsRepository.KEY_EXA_API_KEY, "") } returns "exa-key"

        val t = tools()
        val first = t.schemas().toString()
        yield()
        val second = t.schemas().toString()
        assertEquals("two turns without a rule edit must send the same prefix", first, second)

        rules.value = rules.value + RuleEntity(id = 2, name = "Прогрев", triggers = "[]", actions = "[]")
        yield()
        val third = t.schemas().toString()
        assertTrue("a new rule must reach the enum", third.contains("Прогрев"))
    }

    @Test fun disabled_rules_stay_out_of_the_enum() = runTest {
        val rules = MutableStateFlow(listOf(
            RuleEntity(id = 1, name = "Включено", triggers = "[]", actions = "[]", enabled = true),
            RuleEntity(id = 2, name = "Выключено", triggers = "[]", actions = "[]", enabled = false),
        ))
        coEvery { ruleDao.getEnabled() } returns rules.value.filter { it.enabled }
        every { ruleDao.getAll() } returns rules
        coEvery { settingsRepository.getString(SettingsRepository.KEY_EXA_API_KEY, "") } returns "exa-key"

        val t = tools()
        t.schemas()
        yield()
        val enum = function(t.schemas(), "run_automation")!!
            .getJSONObject("parameters").getJSONObject("properties")
            .getJSONObject("name").getJSONArray("enum")
        assertEquals(1, enum.length())
        assertEquals("Включено", enum.getString(0))
    }
}
