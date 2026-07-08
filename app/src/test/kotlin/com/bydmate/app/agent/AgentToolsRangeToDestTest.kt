package com.bydmate.app.agent

import android.content.Context
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.AutomationEngine
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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolsRangeToDestTest {

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
    ).also { it.nowMs = { 1_000_000_000_000L } }

    private fun call(name: String, args: String) = AgentToolCall("1", name, args)

    @Test fun range_to_dest_enough_when_range_exceeds_distance() = runTest {
        // GPS Минск (53.9, 27.56), назначение geocode -> Орша (54.508, 30.42): ~198 км прямой,
        // ~247 км с коэффициентом 1.25. range 400 км -> enough=true.
        tools.locationProvider = { 53.9 to 27.56 }
        coEvery { places.getAllSnapshot() } returns emptyList()
        coEvery { weather.geocode("Орша") } returns
            Result.success(WeatherClient.GeoPoint(54.508, 30.42, "Орша"))
        every { gate.vehicleSnapshot() } returns AgentToolsReadTest.snapshot(soc = 80, totalElec = 1000.0)
        coEvery { range.estimate(80, 1000.0) } returns 400.0
        val out = JSONObject(tools.execute(call("range_to_destination", """{"destination":"Орша"}""")))
        assertTrue(out.getBoolean("enough"))
        assertEquals(400, out.getInt("range_km"))
        val dist = out.getInt("distance_km")
        assertTrue("~247 km with road factor, got $dist", dist in 235..260)
        assertEquals(400 - dist, out.getInt("reserve_km"))
    }

    @Test fun range_to_dest_not_enough_flags_false() = runTest {
        // Same route (~247 km with road factor), range 200 km -> enough=false, reserve negative.
        tools.locationProvider = { 53.9 to 27.56 }
        coEvery { places.getAllSnapshot() } returns emptyList()
        coEvery { weather.geocode("Орша") } returns
            Result.success(WeatherClient.GeoPoint(54.508, 30.42, "Орша"))
        every { gate.vehicleSnapshot() } returns AgentToolsReadTest.snapshot(soc = 40, totalElec = 1000.0)
        coEvery { range.estimate(40, 1000.0) } returns 200.0
        val out = JSONObject(tools.execute(call("range_to_destination", """{"destination":"Орша"}""")))
        assertTrue(!out.getBoolean("enough"))
        assertEquals(200, out.getInt("range_km"))
        assertTrue(out.getInt("reserve_km") < 0)
    }

    @Test fun range_to_dest_prefers_saved_place_over_geocode() = runTest {
        // Place "Дача" (54.2, 28.3) in the repository -> geocode is never called.
        tools.locationProvider = { 53.9 to 27.56 }
        coEvery { places.getAllSnapshot() } returns listOf(
            PlaceEntity(name = "Дача", lat = 54.2, lon = 28.3, radiusM = 100),
        )
        every { gate.vehicleSnapshot() } returns AgentToolsReadTest.snapshot(soc = 80, totalElec = 1000.0)
        coEvery { range.estimate(80, 1000.0) } returns 400.0
        val out = JSONObject(tools.execute(call("range_to_destination", """{"destination":"дача"}""")))
        assertEquals("Дача", out.getString("destination"))
        coVerify(exactly = 0) { weather.geocode(any()) }
    }

    @Test fun range_to_dest_without_gps_errors() = runTest {
        tools.locationProvider = { null }
        val out = JSONObject(tools.execute(call("range_to_destination", """{"destination":"Орша"}""")))
        assertTrue(out.has("error"))
        coVerify(exactly = 0) { weather.geocode(any()) }
    }

    @Test fun range_to_dest_unknown_destination_errors() = runTest {
        tools.locationProvider = { 53.9 to 27.56 }
        coEvery { places.getAllSnapshot() } returns emptyList()
        coEvery { weather.geocode("Тьмутаракань") } returns
            Result.failure(RuntimeException("город не найден"))
        val out = JSONObject(tools.execute(call("range_to_destination", """{"destination":"Тьмутаракань"}""")))
        assertTrue(out.has("error"))
    }

    @Test fun range_to_dest_without_range_data_errors() = runTest {
        tools.locationProvider = { 53.9 to 27.56 }
        coEvery { places.getAllSnapshot() } returns emptyList()
        coEvery { weather.geocode("Орша") } returns
            Result.success(WeatherClient.GeoPoint(54.508, 30.42, "Орша"))
        every { gate.vehicleSnapshot() } returns AgentToolsReadTest.snapshot(soc = null, totalElec = null)
        coEvery { range.estimate(null, null) } returns null
        val out = JSONObject(tools.execute(call("range_to_destination", """{"destination":"Орша"}""")))
        assertTrue(out.has("error"))
    }
}
