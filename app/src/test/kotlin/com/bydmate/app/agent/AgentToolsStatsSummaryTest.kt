package com.bydmate.app.agent

import android.content.Context
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.AutomationEngine
import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.remote.DynamicMetric
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

class AgentToolsStatsSummaryTest {

    private val gate = mockk<VoiceGate>()
    private val battery = mockk<BatteryStateRepository>()
    private val range = mockk<RangeCalculator>()
    private val tripDao = mockk<TripDao>()
    private val chargeDao = mockk<ChargeDao>()
    private val dispatcher = mockk<ActionDispatcher>()
    private val ruleDao = mockk<RuleDao>()
    private val engine = mockk<AutomationEngine>()
    private val places = mockk<PlaceRepository>()
    private val weather = mockk<WeatherClient>()
    private val exa = mockk<ExaSearchClient>()
    private val openRouterClient = mockk<OpenRouterClient>()
    private val settingsRepository = mockk<SettingsRepository>()
    private val contactLookup = mockk<ContactLookup>()
    private val insightsManager = mockk<InsightsManager>()

    private val context = mockk<Context>(relaxed = true)

    private fun tools() = AgentTools(
        gate, battery, range, tripDao, chargeDao, dispatcher, ruleDao, engine, places, weather,
        exa, openRouterClient, settingsRepository, contactLookup, context,
        mockk<ClusterVoiceControl>(relaxed = true),
        mockk<ChargerSearchClient>(relaxed = true),
        insightsManager,
        mockk<ZaiSearchClient>(relaxed = true),
        mockk<LlmConnectionResolver>(relaxed = true),
    )

    private fun call(name: String, args: String) = AgentToolCall("1", name, args)

    @Test fun stats_summary_week_serializes_metrics() = runTest {
        coEvery { insightsManager.dynamicsFor(7) } returns listOf(
            DynamicMetric(label = "Расход", current = "18.2 кВтч/100", previous = "19.0",
                changePct = -4.2, sentiment = "good", section = null, kind = "consumption"))
        val out = JSONObject(tools().execute(call("get_stats_summary", "{}")))
        val arr = out.getJSONArray("metrics")
        assertEquals(1, arr.length())
        assertEquals("Расход", arr.getJSONObject(0).getString("label"))
        assertEquals("18.2 кВтч/100", arr.getJSONObject(0).getString("current"))
        assertEquals(-4.2, arr.getJSONObject(0).getDouble("change_pct"), 0.01)
    }

    @Test fun stats_summary_month_uses_30_days() = runTest {
        coEvery { insightsManager.dynamicsFor(30) } returns emptyList()
        val out = JSONObject(tools().execute(call("get_stats_summary", """{"period":"month"}""")))
        assertTrue(out.has("error"))   // empty -> honest "no data" error
        coVerify { insightsManager.dynamicsFor(30) }
    }
}
