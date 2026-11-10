package com.bydmate.app.agent

import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.AutomationEngine
import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.dao.TripDao
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

    private val ruleDao = mockk<RuleDao> { coEvery { getCount() } returns 0 }

    private fun tools() = agentTools(
        gate = mockk<VoiceGate>(),
        batteryStateRepository = mockk<BatteryStateRepository>(),
        rangeCalculator = mockk<RangeCalculator>(),
        tripDao = mockk<TripDao>(),
        chargeDao = mockk<ChargeDao>(),
        actionDispatcher = mockk<ActionDispatcher>(),
        ruleDao = ruleDao,
        automationEngine = mockk<AutomationEngine>(),
        placeRepository = mockk<PlaceRepository>(),
        weatherClient = mockk<WeatherClient>(),
        exaSearchClient = mockk<ExaSearchClient>(),
        openRouterClient = mockk<OpenRouterClient>(),
        settingsRepository = mockk<SettingsRepository>(),
        contactLookup = mockk<ContactLookup>(),
    )

    private suspend fun create(name: String, trigger: String, actions: String): JSONObject =
        JSONObject(tools().execute(AgentToolCall("1", "create_automation",
            """{"name":"$name","trigger":$trigger,"actions":$actions}""")))

    private val socTrigger = """{"kind":"param","param":"SOC","operator":"<","value":"20"}"""

    // Item 9: the editor offers pauses up to 24 h, the agent may too.
    @Test fun `a pause of up to 24 h is accepted`() = runTest {
        coEvery { ruleDao.getAllList() } returns emptyList()
        coEvery { ruleDao.insert(any()) } returns 1L

        val ok = create("Пауза", socTrigger, """[{"kind":"delay","ms":86400000}]""")
        val over = create("Длинная", socTrigger, """[{"kind":"delay","ms":86400001}]""")

        assertTrue(ok.toString(), ok.getBoolean("ok"))
        assertEquals("не указана длительность паузы (мс, 0..86400000)", over.getString("error"))
        coVerify(exactly = 1) { ruleDao.insert(any()) }
    }

    // Review fix 4: the list said 49, but the 50th slot went to another path before the insert.
    @Test fun `a rule that lost the last slot is refused at the insert`() = runTest {
        coEvery { ruleDao.getAllList() } returns emptyList()
        coEvery { ruleDao.getCount() } returns 50

        val out = create("Поздно", socTrigger, """[{"kind":"param","command_id":"windows_close_all"}]""")

        assertEquals("достигнут предел в 50 автоматизаций", out.getString("error"))
        coVerify(exactly = 0) { ruleDao.insert(any()) }
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
