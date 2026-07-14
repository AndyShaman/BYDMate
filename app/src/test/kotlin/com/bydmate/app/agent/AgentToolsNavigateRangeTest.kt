package com.bydmate.app.agent

import android.content.Context
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.AutomationEngine
import com.bydmate.app.data.automation.DispatchResult
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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolsNavigateRangeTest {

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

    @Test fun navigate_to_route_appends_range_assessment() = runTest {
        // car at 55.75,37.62 (Moscow); target ~Tver direction, straight ~150 km
        tools.locationProvider = { 55.75 to 37.62 }
        every { gate.vehicleSnapshot() } returns AgentToolsReadTest.snapshot(soc = 80, totalElec = 1234.5)
        coEvery { range.estimate(80, 1234.5) } returns 320.0
        coEvery { dispatcher.dispatch(any(), any()) } returns DispatchResult(true)

        val out = JSONObject(tools.execute(call("navigate_to",
            """{"destination":"точка","lat":57.0,"lon":36.0}""")))

        assertTrue(out.getBoolean("ok"))
        assertEquals("route", out.getString("mode"))
        assertTrue(out.has("distance_km"))
        assertEquals(320, out.getInt("range_km"))
        assertTrue(out.has("enough"))
        assertTrue(out.has("reserve_km"))
    }

    @Test fun navigate_to_adds_charge_note_when_not_enough() = runTest {
        tools.locationProvider = { 55.75 to 37.62 }
        every { gate.vehicleSnapshot() } returns AgentToolsReadTest.snapshot(soc = 20, totalElec = 1234.5)
        coEvery { range.estimate(20, 1234.5) } returns 60.0
        coEvery { dispatcher.dispatch(any(), any()) } returns DispatchResult(true)

        val out = JSONObject(tools.execute(call("navigate_to",
            """{"destination":"точка","lat":57.0,"lon":36.0}""")))

        assertTrue(out.getBoolean("ok"))          // navigation itself is NOT blocked
        assertFalse(out.getBoolean("enough"))
        assertTrue(out.has("charge_note"))
    }

    @Test fun navigate_to_works_without_gps_or_snapshot() = runTest {
        tools.locationProvider = { null }
        coEvery { dispatcher.dispatch(any(), any()) } returns DispatchResult(true)

        val out = JSONObject(tools.execute(call("navigate_to",
            """{"destination":"точка","lat":57.0,"lon":36.0}""")))

        assertTrue(out.getBoolean("ok"))
        assertFalse(out.has("distance_km"))
        assertFalse(out.has("charge_note"))
    }

    @Test fun `search_on_map dispatches navigate action with query payload`() = runTest {
        coEvery { dispatcher.dispatch(any(), any()) } returns DispatchResult(success = true)
        val out = JSONObject(tools.execute(call("search_on_map",
            """{"query":"кофейня рядом"}""")))
        assertTrue(out.getBoolean("ok"))
        assertEquals("search", out.getString("mode"))
        coVerify { dispatcher.dispatch(match {
            it.kind == "navigate" && JSONObject(it.payload!!).getString("query") == "кофейня рядом"
        }, null) }
    }

    @Test fun `search_on_map without query returns error`() = runTest {
        val out = JSONObject(tools.execute(call("search_on_map", """{}""")))
        assertTrue(out.has("error"))
    }

    @Test fun `tool catalog routes home commands to navigate_to`() = runTest {
        val catalog = tools.schemas()
        val navigateDesc = (0 until catalog.length()).mapNotNull {
            val fn = catalog.getJSONObject(it).optJSONObject("function") ?: return@mapNotNull null
            if (fn.getString("name") == "navigate_to") fn.getString("description") else null
        }.first()
        val searchDesc = (0 until catalog.length()).mapNotNull {
            val fn = catalog.getJSONObject(it).optJSONObject("function") ?: return@mapNotNull null
            if (fn.getString("name") == "search_on_map") fn.getString("description") else null
        }.first()
        // home/work commands belong to navigate_to now; search_on_map must not claim them
        assertTrue(navigateDesc.contains("домой"))
        assertTrue(navigateDesc.contains("на работу"))
        assertFalse(searchDesc.contains("Дом\""))
        assertTrue(searchDesc.contains("navigate_to"))
    }
}
