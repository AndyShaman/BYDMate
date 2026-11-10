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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wave 6, offline half: the eval set itself has to be valid before it is worth spending tokens
 * on a live model. Every expected tool must exist in the schema the model is shown, every
 * command id must exist in the catalog. The live half lives in scripts/agent-eval.sh.
 */
class AgentEvalOfflineTest {

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

    @Test fun the_set_has_no_duplicate_utterances_and_keeps_its_traps() {
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
}
