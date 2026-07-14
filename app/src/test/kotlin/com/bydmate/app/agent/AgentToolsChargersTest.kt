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
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolsChargersTest {

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
    private val chargerSearchClient = mockk<ChargerSearchClient>(relaxed = true)

    private val tools = AgentTools(
        gate, battery, range, tripDao, chargeDao, dispatcher, ruleDao, engine, places, weather,
        exa, openRouterClient, settingsRepository, contactLookup, context,
        mockk<ClusterVoiceControl>(relaxed = true),
        chargerSearchClient,
        mockk<InsightsManager>(relaxed = true),
        mockk<ZaiSearchClient>(relaxed = true),
        mockk<LlmConnectionResolver>(relaxed = true),
    ).also {
        it.naviForegroundCheck = { true }
        it.naviVerifyAttempts = 1
        it.naviVerifyIntervalMs = 1L
    }

    private fun call(name: String, args: String) = AgentToolCall("1", name, args)

    // (a) city given -> geocodes the city, does not use the GPS position.
    @Test fun find_chargers_with_city_uses_geocode_not_gps() = runTest {
        tools.locationProvider = { 10.0 to 20.0 }
        coEvery { weather.geocode("Орша") } returns
            Result.success(WeatherClient.GeoPoint(54.508, 30.42, "Орша"))
        coEvery { chargerSearchClient.search(54.508, 30.42, any()) } returns
            Result.success(listOf(ChargerSearchClient.Charger("ЭЗС", 54.51, 30.43)))
        val out = JSONObject(tools.execute(call("find_chargers", """{"city":"Орша"}""")))
        assertEquals(1, out.getJSONArray("chargers").length())
        coVerify(exactly = 0) { chargerSearchClient.search(10.0, 20.0, any()) }
    }

    // (b) no city -> falls back to current GPS position.
    @Test fun find_chargers_without_city_uses_gps() = runTest {
        tools.locationProvider = { 53.9 to 27.56 }
        coEvery { chargerSearchClient.search(53.9, 27.56, 30_000) } returns
            Result.success(listOf(ChargerSearchClient.Charger("ЭЗС", 53.91, 27.57)))
        val out = JSONObject(tools.execute(call("find_chargers", "{}")))
        assertEquals(1, out.getJSONArray("chargers").length())
        coVerify(exactly = 0) { weather.geocode(any()) }
    }

    // (c) results are sorted by distance ascending and capped at 5.
    @Test fun find_chargers_sorts_by_distance_and_caps_at_five() = runTest {
        tools.locationProvider = { 0.0 to 0.0 }
        // Deliberately shuffled input: the expected output order must come from sortedBy,
        // not from the fixture happening to be pre-sorted.
        val chargers = listOf(4, 1, 7, 3, 6, 2, 5).map { i ->
            ChargerSearchClient.Charger("C$i", 0.01 * i, 0.0)
        }
        coEvery { chargerSearchClient.search(0.0, 0.0, any()) } returns Result.success(chargers)
        val out = JSONObject(tools.execute(call("find_chargers", "{}")))
        val arr = out.getJSONArray("chargers")
        assertEquals(5, arr.length())
        val names = (0 until arr.length()).map { arr.getJSONObject(it).getString("name") }
        assertEquals(listOf("C1", "C2", "C3", "C4", "C5"), names)
    }

    // (d) empty result set -> chargers array empty, with an explanatory note.
    @Test fun find_chargers_empty_result_returns_note() = runTest {
        tools.locationProvider = { 53.9 to 27.56 }
        coEvery { chargerSearchClient.search(53.9, 27.56, any()) } returns Result.success(emptyList())
        val out = JSONObject(tools.execute(call("find_chargers", "{}")))
        assertEquals(0, out.getJSONArray("chargers").length())
        assertTrue(out.has("note"))
    }

    // (e) search failure -> Russian error, not the raw exception.
    @Test fun find_chargers_search_failure_returns_russian_error() = runTest {
        tools.locationProvider = { 53.9 to 27.56 }
        coEvery { chargerSearchClient.search(53.9, 27.56, any()) } returns
            Result.failure(RuntimeException("boom"))
        val out = JSONObject(tools.execute(call("find_chargers", "{}")))
        assertTrue(out.has("error"))
    }

    // (f) radius_km is capped at 100 even if the LLM asks for more.
    @Test fun find_chargers_radius_km_capped_at_100() = runTest {
        tools.locationProvider = { 53.9 to 27.56 }
        coEvery { chargerSearchClient.search(53.9, 27.56, any()) } returns Result.success(emptyList())
        tools.execute(call("find_chargers", """{"radius_km":500}"""))
        coVerify { chargerSearchClient.search(53.9, 27.56, 100_000) }
    }

    // (g) navigate_to with lat/lon dispatches straight to the coordinate route, skipping geocode entirely.
    @Test fun navigate_to_with_coordinates_dispatches_without_geocode() = runTest {
        val captured = slot<com.bydmate.app.data.local.entity.ActionDef>()
        coEvery { dispatcher.dispatch(capture(captured), any()) } returns DispatchResult(true)
        val out = JSONObject(tools.execute(
            call("navigate_to", """{"lat":54.51,"lon":30.43}""")))
        assertTrue(out.getBoolean("ok"))
        assertEquals("navigate", captured.captured.kind)
        val payload = JSONObject(captured.captured.payload!!)
        assertEquals(54.51, payload.getDouble("lat"), 0.0001)
        assertEquals(30.43, payload.getDouble("lon"), 0.0001)
        coVerify(exactly = 0) { weather.geocode(any()) }
    }

    // (h) neither destination nor coordinates -> unchanged Russian error.
    @Test fun navigate_to_without_destination_or_coordinates_reports_error() = runTest {
        val out = JSONObject(tools.execute(call("navigate_to", "{}")))
        assertFalse(out.has("ok"))
        assertEquals("не указано, куда ехать", out.getString("error"))
        coVerify(exactly = 0) { dispatcher.dispatch(any(), any()) }
    }
}
