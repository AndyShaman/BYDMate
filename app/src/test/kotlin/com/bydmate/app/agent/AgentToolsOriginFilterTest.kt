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
    fun `schemas without automation tools exclude exactly the three`() = runTest {
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
}
