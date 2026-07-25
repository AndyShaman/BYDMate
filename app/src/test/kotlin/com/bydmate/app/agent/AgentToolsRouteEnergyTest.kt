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
import com.bydmate.app.domain.battery.BatteryState
import com.bydmate.app.domain.battery.BatteryStateRepository
import com.bydmate.app.domain.calculator.RangeCalculator
import com.bydmate.app.domain.calculator.RangeEstimate
import com.bydmate.app.media.NaviRouteHolder
import com.bydmate.app.media.NaviScreenReader
import com.bydmate.app.navdata.NavGuidance
import com.bydmate.app.navdata.NavGuidanceHub
import com.bydmate.app.voice.VoiceGate
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AgentToolsRouteEnergyTest {

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

    private fun tools() = AgentTools(
        gate, battery, range, tripDao, chargeDao, dispatcher, ruleDao, engine, places, weather,
        exa, openRouterClient, settingsRepository, contactLookup, context,
        mockk<ClusterVoiceControl>(relaxed = true),
        mockk<ChargerSearchClient>(relaxed = true),
        mockk<InsightsManager>(relaxed = true),
        mockk<ZaiSearchClient>(relaxed = true),
        mockk<LlmConnectionResolver>(relaxed = true),
    ).also { it.nowMs = { 1_000_000_000_000L } }

    @Before
    @After
    fun resetState() {
        NaviRouteHolder.clear(NaviRouteHolder.NAVI_PACKAGE)
        NavGuidanceHub.reset()
    }

    // --- routeInfo energy_estimate tests ---

    @Test fun `energy_estimate present when hub active with soc=50`() = runTest {
        NavGuidanceHub.update(
            NavGuidance(
                maneuverGaode = 2, distanceMeters = 300, road = "тест",
                etaSeconds = 1800, totalDistMeters = 50_000,
            ),
            NavGuidanceHub.Source.A11Y,
            nowMs = 1_000_000_000_000L,
        )
        val t = tools()
        every { gate.vehicleSnapshot() } returns AgentToolsReadTest.snapshot(soc = 50, totalElec = 1234.5)
        coEvery { range.estimateDetailed(50, 1234.5) } returns RangeEstimate(
            rangeKm = 202.5, avgKwhPer100 = 18.0, capacityKwh = 72.9, remainingKwh = 36.45,
        )
        val out = JSONObject(t.execute(AgentToolCall("1", "get_route_info", "{}")))
        assertTrue(out.has("energy_estimate"))
        val ee = out.getJSONObject("energy_estimate")
        // remaining_km = hub.totalDistMeters / 1000.0 = 50000 / 1000 = 50.0
        assertEquals(50.0, ee.getDouble("remaining_km"), 0.01)
        // avg from estimateDetailed
        assertEquals(18.0, ee.getDouble("avg_consumption_kwh_100km"), 0.01)
        // needed = 50.0 * 18.0 / 100.0 = 9.0
        assertEquals(9.0, ee.getDouble("energy_needed_kwh"), 0.01)
        // battery_now_kwh = (36.45 * 10).roundToInt() / 10.0 = 36.5
        assertEquals(36.5, ee.getDouble("battery_now_kwh"), 0.01)
        // soc_at_arrival = (36.45 - 9.0) / 72.9 * 100 = 37.65 -> 38
        assertEquals(38, ee.getInt("soc_at_arrival_percent"))
        assertTrue(ee.getBoolean("enough"))
        assertFalse(ee.has("warning"))
    }

    @Test fun `energy_estimate warning when soc=14 arrival below 10pct`() = runTest {
        NavGuidanceHub.update(
            NavGuidance(
                maneuverGaode = 2, distanceMeters = 300, road = "тест",
                etaSeconds = 600, totalDistMeters = 50_000,
            ),
            NavGuidanceHub.Source.A11Y,
            nowMs = 1_000_000_000_000L,
        )
        val t = tools()
        every { gate.vehicleSnapshot() } returns AgentToolsReadTest.snapshot(soc = 14, totalElec = 1234.5)
        // remainingKwh = 14/100 * 72.9 = 10.206; needed = 9.0; soc_at_arrival = 1.655 -> 2
        coEvery { range.estimateDetailed(14, 1234.5) } returns RangeEstimate(
            rangeKm = 56.7, avgKwhPer100 = 18.0, capacityKwh = 72.9, remainingKwh = 10.206,
        )
        val out = JSONObject(t.execute(AgentToolCall("1", "get_route_info", "{}")))
        val ee = out.getJSONObject("energy_estimate")
        assertEquals(2, ee.getInt("soc_at_arrival_percent"))
        // 10.206 >= 9.0 -> still enough, but warn about low arrival
        assertTrue(ee.getBoolean("enough"))
        assertTrue(ee.has("warning"))
    }

    @Test fun `no energy_estimate when hub inactive and no screen distance`() = runTest {
        // Give routeInfo something to work with (notification only, no hub, no screen)
        NaviRouteHolder.update(
            NaviRouteHolder.NAVI_PACKAGE, "5 км", null, null, 1_000_000_000_000L,
        )
        val t = tools()
        t.naviScreenProvider = { null }
        every { gate.vehicleSnapshot() } returns AgentToolsReadTest.snapshot(soc = 60, totalElec = 1234.5)
        coEvery { range.estimateDetailed(any(), any()) } returns RangeEstimate(
            rangeKm = 200.0, avgKwhPer100 = 18.0, capacityKwh = 72.9, remainingKwh = 36.0,
        )
        val out = JSONObject(t.execute(AgentToolCall("1", "get_route_info", "{}")))
        assertFalse(out.has("error"))
        // No remainingKm source -> energy_estimate must be absent
        assertFalse(out.has("energy_estimate"))
    }

    // --- vehicleState battery capacity / effective capacity tests ---

    @Test fun `vehicle_state includes battery capacity and avg consumption fields`() = runTest {
        every { gate.vehicleSnapshot() } returns AgentToolsReadTest.snapshot(soc = 50, totalElec = 1234.5)
        coEvery { battery.refresh() } throws RuntimeException("n/a")
        coEvery { range.estimateDetailed(50, 1234.5) } returns RangeEstimate(
            rangeKm = 202.5, avgKwhPer100 = 18.0, capacityKwh = 72.9, remainingKwh = 36.45,
        )
        val out = JSONObject(tools().execute(AgentToolCall("1", "get_vehicle_state", "{}")))
        assertEquals(72.9, out.getDouble("battery_capacity_kwh"), 0.001)
        assertEquals(18.0, out.getDouble("avg_consumption_kwh_100km"), 0.01)
        // (36.45 * 10).roundToInt() / 10.0 = 36.5
        assertEquals(36.5, out.getDouble("battery_remaining_kwh"), 0.01)
        // soh absent (refresh threw) -> no effective capacity
        assertFalse(out.has("battery_effective_capacity_kwh"))
    }

    @Test fun `vehicle_state includes battery_effective_capacity when soh in sanity range`() = runTest {
        every { gate.vehicleSnapshot() } returns AgentToolsReadTest.snapshot(soc = 50, totalElec = 1234.5)
        coEvery { battery.refresh() } returns BatteryState(
            socNow = 50f, voltage12v = null, sohPercent = 85f,
            lifetimeKm = null, lifetimeKwh = null, autoserviceAvailable = true,
        )
        coEvery { range.estimateDetailed(50, 1234.5) } returns RangeEstimate(
            rangeKm = 202.5, avgKwhPer100 = 18.0, capacityKwh = 72.9, remainingKwh = 36.45,
        )
        val out = JSONObject(tools().execute(AgentToolCall("1", "get_vehicle_state", "{}")))
        // 72.9 * 85.0 / 100.0 = 61.965; (61.965*10).roundToInt()/10.0 = 62.0
        assertEquals(62.0, out.getDouble("battery_effective_capacity_kwh"), 0.01)
    }

    // --- parseDistanceKm strict full-string tests (MAJOR 2) ---

    private fun screenWith(dist: String?) = NaviScreenReader.ScreenInfo(
        speedLimit = null, exitNumber = null, maneuverDistance = null,
        remainingDistance = dist,
        remainingTime = null, arrivalTime = null, street = null,
    )

    private fun stubRange(t: AgentTools) {
        every { gate.vehicleSnapshot() } returns AgentToolsReadTest.snapshot(soc = 50, totalElec = 1234.5)
        coEvery { range.estimateDetailed(50, 1234.5) } returns RangeEstimate(
            rangeKm = 202.5, avgKwhPer100 = 18.0, capacityKwh = 72.9, remainingKwh = 36.45,
        )
    }

    @Test fun `thousands separator space 1 234 km gives 1234`() = runTest {
        val t = tools()
        t.naviScreenProvider = { screenWith("1 234 км") }
        stubRange(t)
        val out = JSONObject(t.execute(AgentToolCall("1", "get_route_info", "{}")))
        assertTrue(out.has("energy_estimate"))
        assertEquals(1234.0, out.getJSONObject("energy_estimate").getDouble("remaining_km"), 0.01)
    }

    @Test fun `parse 800 m gives 0 8 km`() = runTest {
        val t = tools()
        t.naviScreenProvider = { screenWith("800 м") }
        stubRange(t)
        val out = JSONObject(t.execute(AgentToolCall("1", "get_route_info", "{}")))
        assertTrue(out.has("energy_estimate"))
        assertEquals(0.8, out.getJSONObject("energy_estimate").getDouble("remaining_km"), 0.001)
    }

    @Test fun `parse 1 comma 5 km gives 1 5`() = runTest {
        val t = tools()
        t.naviScreenProvider = { screenWith("1,5 км") }
        stubRange(t)
        val out = JSONObject(t.execute(AgentToolCall("1", "get_route_info", "{}")))
        assertTrue(out.has("energy_estimate"))
        assertEquals(1.5, out.getJSONObject("energy_estimate").getDouble("remaining_km"), 0.001)
    }

    @Test fun `negative distance string gives no energy_estimate`() = runTest {
        val t = tools()
        t.naviScreenProvider = { screenWith("-5 км") }
        stubRange(t)
        val out = JSONObject(t.execute(AgentToolCall("1", "get_route_info", "{}")))
        assertFalse(out.has("energy_estimate"))
    }

    @Test fun `text prefix gives no energy_estimate`() = runTest {
        val t = tools()
        t.naviScreenProvider = { screenWith("Осталось 124 км") }
        stubRange(t)
        val out = JSONObject(t.execute(AgentToolCall("1", "get_route_info", "{}")))
        assertFalse(out.has("energy_estimate"))
    }

    @Test fun `empty distance string gives no energy_estimate`() = runTest {
        val t = tools()
        t.naviScreenProvider = { screenWith("") }
        stubRange(t)
        val out = JSONObject(t.execute(AgentToolCall("1", "get_route_info", "{}")))
        assertFalse(out.has("energy_estimate"))
    }
}
