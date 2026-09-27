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
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Automation audit 3.19, create_automation: the 60 s pause and thresholds that are numbers. */
class AgentToolsAutomationAuditTest {

    private val ruleDao = mockk<RuleDao>()

    private fun tools() = AgentTools(
        mockk<VoiceGate>(), mockk<BatteryStateRepository>(), mockk<RangeCalculator>(), mockk<TripDao>(),
        mockk<ChargeDao>(), mockk<ActionDispatcher>(), ruleDao, mockk<AutomationEngine>(),
        mockk<PlaceRepository>(), mockk<WeatherClient>(), mockk<ExaSearchClient>(), mockk<OpenRouterClient>(),
        mockk<SettingsRepository>(), mockk<ContactLookup>(), mockk<Context>(relaxed = true),
        mockk<ClusterVoiceControl>(relaxed = true),
        mockk<ChargerSearchClient>(relaxed = true),
        mockk<InsightsManager>(relaxed = true),
        mockk<ZaiSearchClient>(relaxed = true),
        mockk<LlmConnectionResolver>(relaxed = true),
    )

    private suspend fun create(name: String, trigger: String, actions: String): JSONObject =
        JSONObject(tools().execute(AgentToolCall("1", "create_automation",
            """{"name":"$name","trigger":$trigger,"actions":$actions}""")))

    private val socTrigger = """{"kind":"param","param":"SOC","operator":"<","value":"20"}"""

    // Item 9: the editor offers a 60 s pause, the agent may too.
    @Test fun `a pause of up to 60 s is accepted`() = runTest {
        coEvery { ruleDao.getAllList() } returns emptyList()
        coEvery { ruleDao.insert(any()) } returns 1L

        val ok = create("Пауза", socTrigger, """[{"kind":"delay","ms":60000}]""")
        val over = create("Длинная", socTrigger, """[{"kind":"delay","ms":60001}]""")

        assertTrue(ok.toString(), ok.getBoolean("ok"))
        assertEquals("не указана длительность паузы (мс, 0..60000)", over.getString("error"))
        coVerify(exactly = 1) { ruleDao.insert(any()) }
    }

    // Item 3: a threshold that is not a number would make a rule that never fires.
    @Test fun `a threshold that is not a number is refused`() = runTest {
        coEvery { ruleDao.getAllList() } returns emptyList()

        val out = create("Мало", """{"kind":"param","param":"SOC","operator":"<","value":"мало"}""",
            """[{"kind":"param","command_id":"windows_close_all"}]""")

        assertEquals("значение для «SOC» должно быть числом", out.getString("error"))
        coVerify(exactly = 0) { ruleDao.insert(any()) }
    }
}
