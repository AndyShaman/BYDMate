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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wave 6, offline half: the eval set itself has to be valid before it is worth spending tokens
 * on a live model. Every expected tool must exist in the schema the model is shown, every
 * command id must exist in the catalog, and the loop must carry each expectation end to end
 * when a fake model produces it. The live half lives in scripts/agent-eval.sh.
 */
class AgentEvalOfflineTest {

    private class FakeBackend(private val scripted: List<Result<AgentReply>>) : AgentBackend {
        private var index = 0
        override suspend fun isConfigured() = true
        override suspend fun chat(
            messages: List<AgentMessage>,
            tools: JSONArray?,
            onDelta: ((String) -> Unit)?,
        ): Result<AgentReply> = scripted[index++]
    }

    private val repo = mockk<SettingsRepository>()

    private fun realTools() = AgentTools(
        mockk<VoiceGate>(relaxed = true), mockk<BatteryStateRepository>(relaxed = true),
        mockk<RangeCalculator>(relaxed = true), mockk<TripDao>(relaxed = true),
        mockk<ChargeDao>(relaxed = true), mockk<ActionDispatcher>(relaxed = true),
        mockk<RuleDao>().also {
            coEvery { it.getEnabled() } returns emptyList()
            every { it.getAll() } returns MutableStateFlow(emptyList())
        },
        mockk<AutomationEngine>(relaxed = true), mockk<PlaceRepository>(relaxed = true),
        mockk<WeatherClient>(relaxed = true), mockk<ExaSearchClient>(relaxed = true),
        mockk<OpenRouterClient>(relaxed = true),
        mockk<SettingsRepository>().also {
            coEvery { it.getString(SettingsRepository.KEY_EXA_API_KEY, "") } returns "exa-key"
        },
        mockk<ContactLookup>(relaxed = true), mockk<Context>(relaxed = true),
        mockk<ClusterVoiceControl>(relaxed = true), mockk<ChargerSearchClient>(relaxed = true),
        mockk<InsightsManager>(relaxed = true), mockk<ZaiSearchClient>(relaxed = true),
        mockk<LlmConnectionResolver>(relaxed = true),
    ).also { it.ruleWatchScope = CoroutineScope(Dispatchers.Unconfined) }

    @Test fun the_set_covers_all_three_classes() {
        assertEquals(30, AgentEvalCases.ALL.size)
        assertEquals(15, AgentEvalCases.ACT.size)
        assertEquals(8, AgentEvalCases.NO_ACT.size)
        assertEquals(7, AgentEvalCases.AUTOMATION.size)
        val duplicates = AgentEvalCases.ALL.groupBy { it.utterance }.filterValues { it.size > 1 }
        assertTrue("duplicate utterances: ${duplicates.keys}", duplicates.isEmpty())
        // Traps are the point of the automation class: at least two must expect a question.
        assertTrue(AgentEvalCases.AUTOMATION.count { it.tool == null } >= 2)
    }

    @Test fun every_expected_tool_exists_in_the_schema() = runTest {
        val schemas = realTools().schemas()
        val names = (0 until schemas.length())
            .map { schemas.getJSONObject(it).getJSONObject("function").getString("name") }
            .toSet()
        val missing = AgentEvalCases.ALL.mapNotNull { it.tool }.distinct().filterNot { it in names }
        assertTrue("eval expects tools the model is never shown: $missing", missing.isEmpty())
    }

    @Test fun every_expected_command_id_exists_in_the_catalog() {
        val ids = AgentCommandCatalog.ALL.map { it.id }.toSet()
        val missing = AgentEvalCases.ALL
            .filter { it.tool == "vehicle_control" }
            .flatMap { it.args }
            .filterNot { it in ids }
        assertTrue("eval expects command ids that do not exist: $missing", missing.isEmpty())
    }

    // The harness, not the model: a scripted tool call must actually reach AgentTools and come
    // back as an answer, for every acting case in the set.
    @Test fun an_acting_case_runs_through_the_loop() = runTest {
        coEvery { repo.isAgentEnabled() } returns true
        val tools = mockk<AgentTools>()
        coEvery { tools.schemas() } returns JSONArray()
        val calls = mutableListOf<String>()
        coEvery { tools.execute(any(), any()) } coAnswers {
            calls += (firstArg<AgentToolCall>()).name
            """{"ok":true}"""
        }
        for (case in AgentEvalCases.ACT) {
            val backend = FakeBackend(listOf(
                Result.success(AgentReply(null, listOf(
                    AgentToolCall("c1", case.tool!!, """{"command":"${case.args.firstOrNull() ?: ""}"}""")))),
                Result.success(AgentReply("Готово", emptyList())),
            ))
            val result = AgentOrchestrator(backend, tools, repo).ask(case.utterance)
            assertTrue(case.utterance, result is AgentResult.Answer)
        }
        assertEquals(AgentEvalCases.ACT.map { it.tool }, calls)
    }

    // A "don't act" case that the model answers in words must leave the car alone.
    @Test fun a_no_act_case_touches_nothing() = runTest {
        coEvery { repo.isAgentEnabled() } returns true
        val tools = mockk<AgentTools>()
        coEvery { tools.schemas() } returns JSONArray()
        for (case in AgentEvalCases.NO_ACT) {
            val backend = FakeBackend(listOf(Result.success(AgentReply("Понял", emptyList()))))
            val result = AgentOrchestrator(backend, tools, repo).ask(case.utterance)
            assertEquals(case.utterance, "Понял", (result as AgentResult.Answer).text)
        }
        // tools.execute is not stubbed at all: any call would fail the test with a mockk error.
    }
}
