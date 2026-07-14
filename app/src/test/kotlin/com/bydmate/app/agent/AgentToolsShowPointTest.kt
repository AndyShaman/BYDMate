package com.bydmate.app.agent

import android.content.Context
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.AutomationEngine
import com.bydmate.app.data.automation.DispatchResult
import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.entity.PlaceEntity
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

class AgentToolsShowPointTest {

    private val gate = mockk<VoiceGate>(relaxed = true)
    private val battery = mockk<BatteryStateRepository>(relaxed = true)
    private val range = mockk<RangeCalculator>(relaxed = true)
    private val tripDao = mockk<TripDao>(relaxed = true)
    private val chargeDao = mockk<ChargeDao>(relaxed = true)
    private val dispatcher = mockk<ActionDispatcher>(relaxed = true)
    private val ruleDao = mockk<RuleDao>(relaxed = true)
    private val engine = mockk<AutomationEngine>(relaxed = true)
    private val places = mockk<PlaceRepository>(relaxed = true)
    private val weather = mockk<WeatherClient>(relaxed = true)
    private val exa = mockk<ExaSearchClient>(relaxed = true)
    private val openRouterClient = mockk<OpenRouterClient>(relaxed = true)
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)
    private val contactLookup = mockk<ContactLookup>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)

    private val tools = AgentTools(
        gate, battery, range, tripDao, chargeDao, dispatcher, ruleDao, engine, places, weather,
        exa, openRouterClient, settingsRepository, contactLookup, context,
        mockk<ClusterVoiceControl>(relaxed = true),
        mockk<ChargerSearchClient>(relaxed = true),
        mockk<InsightsManager>(relaxed = true),
        mockk<ZaiSearchClient>(relaxed = true),
        mockk<LlmConnectionResolver>(relaxed = true),
    ).also {
        it.nowMs = { 1_000_000_000_000L }
        it.naviForegroundCheck = { true }
        it.naviVerifyAttempts = 1
        it.naviVerifyIntervalMs = 1L
    }

    private fun call(name: String, args: String) = AgentToolCall("1", name, args)

    @Test fun `show point by saved place`() = runTest {
        coEvery { places.getAllSnapshot() } returns listOf(
            PlaceEntity(name = "Дача", lat = 56.1, lon = 38.2, radiusM = 100))
        coEvery { dispatcher.dispatch(any(), any()) } returns DispatchResult(true)

        val out = JSONObject(tools.execute(call("show_point_on_map", """{"destination":"Дача"}""")))

        assertEquals("show", out.getString("mode"))
        coVerify { dispatcher.dispatch(match {
            val p = JSONObject(it.payload!!)
            p.getBoolean("show") && p.getDouble("lat") == 56.1 && p.getString("label") == "Дача"
        }, null) }
    }

    @Test fun `show point by explicit coordinates`() = runTest {
        coEvery { dispatcher.dispatch(any(), any()) } returns DispatchResult(true)

        val out = JSONObject(tools.execute(call("show_point_on_map",
            """{"destination":"зарядка","lat":55.5,"lon":37.5}""")))

        assertTrue(out.getBoolean("ok"))
        coVerify { dispatcher.dispatch(match {
            JSONObject(it.payload!!).getBoolean("show")
        }, null) }
    }

    @Test fun `unresolvable destination degrades to map search`() = runTest {
        coEvery { places.getAllSnapshot() } returns emptyList()
        // geocode returns Result.failure so getOrNull()?.getOrNull() == null
        coEvery { weather.geocode(any()) } returns Result.failure(Exception("not found"))
        coEvery { dispatcher.dispatch(any(), any()) } returns DispatchResult(true)

        val out = JSONObject(tools.execute(call("show_point_on_map",
            """{"destination":"улица Ленина 5"}""")))

        assertEquals("search", out.getString("mode"))
        coVerify { dispatcher.dispatch(match {
            JSONObject(it.payload!!).optString("query") == "улица Ленина 5"
        }, null) }
    }

    @Test fun `empty args returns error`() = runTest {
        val out = JSONObject(tools.execute(call("show_point_on_map", """{}""")))
        assertTrue(out.has("error"))
    }
}
