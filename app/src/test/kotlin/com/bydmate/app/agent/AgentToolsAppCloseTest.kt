package com.bydmate.app.agent

import android.content.Context
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.AutomationEngine
import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.remote.InsightsManager
import com.bydmate.app.data.remote.OpenRouterClient
import com.bydmate.app.data.repository.PlaceRepository
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.domain.battery.BatteryStateRepository
import com.bydmate.app.domain.calculator.RangeCalculator
import com.bydmate.app.voice.VoiceGate
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** create_automation with the steps added in 3.20: «Закрыть приложение» (#280), «Медиа: играть / пауза» (#212, #275). */
class AgentToolsAppCloseTest {

    private val ruleDao = mockk<RuleDao> { coEvery { getCount() } returns 0 }

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

    private suspend fun create(t: AgentTools, actions: String) = JSONObject(t.execute(AgentToolCall("1", "create_automation",
        """{"name":"Ночной свет","trigger":{"kind":"param","param":"SOC","operator":"<","value":"20"},"actions":$actions}""")))

    @Test fun `create with app_close action resolves app by label`() = runTest {
        coEvery { ruleDao.getAllList() } returns emptyList()
        val saved = slot<RuleEntity>()
        coEvery { ruleDao.insert(capture(saved)) } returns 1L
        val t = tools()
        t.launcherAppsProvider = { listOf("Шахматы" to "com.example.chess", "Радио" to "com.example.radio") }
        val out = create(t, """[{"kind":"app_close","app":"радио"}]""")
        assertTrue(out.getBoolean("ok"))
        val a = ActionDef.listFromJson(saved.captured.actions).single()
        assertEquals("app_close", a.kind)
        val payload = JSONObject(a.payload!!)
        assertEquals("com.example.radio", payload.getString("packageName"))
        assertEquals("Радио", payload.getString("appLabel"))
    }

    @Test fun `app_close action with unknown app is rejected`() = runTest {
        coEvery { ruleDao.getAllList() } returns emptyList()
        val t = tools()
        t.launcherAppsProvider = { listOf("Шахматы" to "com.example.chess") }
        val out = create(t, """[{"kind":"app_close","app":"тетрис"}]""")
        assertTrue(out.getString("error").contains("не найдено"))
    }

    @Test fun `create with media_key play and pause`() = runTest {
        coEvery { ruleDao.getAllList() } returns emptyList()
        val saved = slot<RuleEntity>()
        coEvery { ruleDao.insert(capture(saved)) } returns 1L
        val out = create(tools(), """[{"kind":"media_key","key":"pause"},{"kind":"media_key","key":"play"}]""")
        assertTrue(out.getBoolean("ok"))
        val a = ActionDef.listFromJson(saved.captured.actions)
        assertEquals(listOf("media_key" to "pause", "media_key" to "play"), a.map { it.kind to it.payload })
    }

    @Test fun `media_key without play or pause is rejected`() = runTest {
        coEvery { ruleDao.getAllList() } returns emptyList()
        assertTrue(create(tools(), """[{"kind":"media_key","key":"next"}]""").has("error"))
        assertTrue(create(tools(), """[{"kind":"media_key"}]""").has("error"))
    }
}
