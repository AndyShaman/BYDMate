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
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Origin filtering: an agent session started FROM an automation must not see or execute
 * automation-management tools (run_automation reaches fireVoiceRule which bypasses cooldown —
 * a rule with an agent_query action could otherwise recurse into itself forever).
 */
class AgentToolsOriginFilterTest {

    private fun makeTools() = AgentTools(
        mockk<VoiceGate>(),
        mockk<BatteryStateRepository>(),
        mockk<RangeCalculator>(),
        mockk<TripDao>(),
        mockk<ChargeDao>(),
        mockk<ActionDispatcher>(),
        mockk<RuleDao>(relaxed = true),
        mockk<AutomationEngine>(),
        mockk<PlaceRepository>(),
        mockk<WeatherClient>(),
        mockk<ExaSearchClient>(),
        mockk<OpenRouterClient>(),
        mockk<SettingsRepository>(relaxed = true),
        mockk<ContactLookup>(),
        mockk<Context>(relaxed = true),
        mockk<ClusterVoiceControl>(relaxed = true),
        mockk<ChargerSearchClient>(relaxed = true),
        mockk<InsightsManager>(relaxed = true),
        mockk<ZaiSearchClient>(relaxed = true),
        mockk<LlmConnectionResolver>(relaxed = true),
    )

    private fun toolNames(schemas: JSONArray): Set<String> =
        (0 until schemas.length()).map {
            schemas.getJSONObject(it).getJSONObject("function").getString("name")
        }.toSet()

    @Test
    fun `default schemas include automation management tools`() = runTest {
        val names = toolNames(makeTools().schemas())
        assertTrue(names.containsAll(AgentTools.AUTOMATION_TOOLS))
    }

    @Test
    fun `schemas without automation tools exclude exactly the gated set`() = runTest {
        val tools = makeTools()
        val full = toolNames(tools.schemas())
        val filtered = toolNames(tools.schemas(includeAutomationTools = false))
        assertTrue(filtered.intersect(AgentTools.AUTOMATION_TOOLS).isEmpty())
        // nothing else disappears
        assertTrue(filtered == full - AgentTools.AUTOMATION_TOOLS)
    }

    @Test
    fun `execute refuses automation tools when disallowed`() = runTest {
        val tools = makeTools()
        for (name in AgentTools.AUTOMATION_TOOLS) {
            val res = tools.execute(AgentToolCall(id = "1", name = name, arguments = "{}"),
                allowAutomationTools = false)
            // Pin on the guard's own message, not just any "error" JSON — a missing guard
            // would still yield generic errors for {} args and the test must not pass then.
            assertTrue("$name must be refused by the origin guard", res.contains("недоступен"))
        }
    }

    @Test
    fun `execute still allows other tools when automation tools are disallowed`() = runTest {
        val res = makeTools().execute(AgentToolCall(id = "1", name = "list_places", arguments = "{}"),
            allowAutomationTools = false)
        assertFalse(res.contains("недоступен"))
    }

    // --- driver memory tools ---

    private fun toolsWithMemory(memory: DriverMemory) = makeTools().also { it.injectDriverMemory(memory) }

    @Test
    fun `remember_fact stores the fact and reports it back`() = runTest {
        val memory = DriverMemory(prefs = null)
        val res = toolsWithMemory(memory).execute(AgentToolCall(id = "1", name = "remember_fact",
            arguments = """{"fact":"Водителя зовут Андрей"}"""))
        val json = JSONObject(res)
        assertTrue(json.getBoolean("ok"))
        assertEquals("Водителя зовут Андрей", json.getString("fact"))
        assertEquals(listOf("Водителя зовут Андрей"), memory.facts())
    }

    @Test
    fun `remember_fact without a fact returns an error`() = runTest {
        val res = toolsWithMemory(DriverMemory(prefs = null))
            .execute(AgentToolCall(id = "1", name = "remember_fact", arguments = "{}"))
        assertTrue(JSONObject(res).has("error"))
    }

    @Test
    fun `forget_fact with all wipes the memory`() = runTest {
        val memory = DriverMemory(prefs = null)
        memory.remember("Водителя зовут Андрей")
        memory.remember("Любит 22 градуса в салоне")
        val res = toolsWithMemory(memory).execute(AgentToolCall(id = "1", name = "forget_fact",
            arguments = """{"all":true}"""))
        assertEquals(2, JSONObject(res).getInt("forgotten_all"))
        assertTrue(memory.facts().isEmpty())
    }

    @Test
    fun `forget_fact without arguments returns an error`() = runTest {
        val res = toolsWithMemory(DriverMemory(prefs = null))
            .execute(AgentToolCall(id = "1", name = "forget_fact", arguments = "{}"))
        assertTrue(JSONObject(res).getString("error").contains("не указано"))
    }

    // An automation-origin session talks to a rule, not to the driver: it must not rewrite facts.
    @Test
    fun `memory tools are hidden and refused for automation-origin sessions`() = runTest {
        val tools = toolsWithMemory(DriverMemory(prefs = null))
        val filtered = toolNames(tools.schemas(includeAutomationTools = false))
        assertFalse(filtered.contains("remember_fact"))
        assertFalse(filtered.contains("forget_fact"))
        assertTrue(toolNames(tools.schemas()).contains("remember_fact"))
        val res = tools.execute(AgentToolCall(id = "1", name = "remember_fact",
            arguments = """{"fact":"Водителя зовут Андрей"}"""), allowAutomationTools = false)
        assertTrue(res.contains("недоступен в сессии, запущенной автоматизацией"))
    }
}
