package com.bydmate.app.agent

import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.repository.SettingsRepository
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

    private fun realTools() = agentTools(
        ruleDao = mockk<RuleDao>().also {
            coEvery { it.getEnabled() } returns emptyList()
            every { it.getAll() } returns MutableStateFlow(emptyList())
        },
        settingsRepository = mockk<SettingsRepository>().also {
            coEvery { it.getString(SettingsRepository.KEY_EXA_API_KEY, "") } returns "exa-key"
        },
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
