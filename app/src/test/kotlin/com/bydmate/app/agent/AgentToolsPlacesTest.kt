package com.bydmate.app.agent

import android.content.Context
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.AutomationEngine
import com.bydmate.app.data.automation.DispatchResult
import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.PlaceEntity
import com.bydmate.app.data.repository.PlaceRepository
import com.bydmate.app.data.remote.InsightsManager
import com.bydmate.app.data.remote.OpenRouterClient
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
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolsPlacesTest {

    private val gate = mockk<VoiceGate>(relaxed = true)
    private val battery = mockk<BatteryStateRepository>(relaxed = true)
    private val range = mockk<RangeCalculator>(relaxed = true)
    private val tripDao = mockk<TripDao>(relaxed = true)
    private val chargeDao = mockk<ChargeDao>(relaxed = true)
    private val dispatcher = mockk<ActionDispatcher>(relaxed = true)
    private val ruleDao = mockk<RuleDao>(relaxed = true)
    private val engine = mockk<AutomationEngine>(relaxed = true)
    private val places = mockk<PlaceRepository>()
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

    @Test fun list_places_returns_names_coords_radius() = runTest {
        coEvery { places.getAllSnapshot() } returns listOf(
            PlaceEntity(id = 1, name = "Дом", lat = 55.75, lon = 37.61, radiusM = 100),
        )
        val out = JSONObject(tools().execute(AgentToolCall("1", "list_places", "{}")))
        val arr = out.getJSONArray("places")
        assertEquals(1, arr.length())
        assertEquals("Дом", arr.getJSONObject(0).getString("name"))
        assertEquals(100, arr.getJSONObject(0).getInt("radius_m"))
        assertEquals(55.75, arr.getJSONObject(0).getDouble("lat"), 1e-6)
    }

    @Test fun list_places_empty() = runTest {
        coEvery { places.getAllSnapshot() } returns emptyList()
        val out = JSONObject(tools().execute(AgentToolCall("1", "list_places", "{}")))
        assertEquals(0, out.getJSONArray("places").length())
    }

    @Test fun create_place_uses_gps_when_no_coords() = runTest {
        coEvery { places.getAllSnapshot() } returns emptyList()
        val saved = slot<PlaceEntity>()
        coEvery { places.insert(capture(saved)) } returns 1L
        val t = tools()
        t.locationProvider = { 55.75 to 37.61 }
        val out = JSONObject(t.execute(AgentToolCall("1", "create_place", """{"name":"Работа"}""")))
        assertTrue(out.getBoolean("ok"))
        assertEquals("Работа", saved.captured.name)
        assertEquals(55.75, saved.captured.lat, 1e-6)
        assertEquals(37.61, saved.captured.lon, 1e-6)
        assertEquals(100, saved.captured.radiusM)
    }

    @Test fun create_place_explicit_coords_and_radius_clamp() = runTest {
        coEvery { places.getAllSnapshot() } returns emptyList()
        val saved = slot<PlaceEntity>()
        coEvery { places.insert(capture(saved)) } returns 1L
        val t = tools()
        t.locationProvider = { null }
        val out = JSONObject(t.execute(AgentToolCall("1", "create_place",
            """{"name":"Дача","lat":56.1,"lon":38.2,"radius_m":5000}""")))
        assertTrue(out.getBoolean("ok"))
        assertEquals(56.1, saved.captured.lat, 1e-6)
        assertEquals(500, saved.captured.radiusM)
    }

    @Test fun create_place_without_gps_and_coords_errors() = runTest {
        coEvery { places.getAllSnapshot() } returns emptyList()
        val t = tools()
        t.locationProvider = { null }
        val out = JSONObject(t.execute(AgentToolCall("1", "create_place", """{"name":"Дом"}""")))
        assertTrue(out.has("error"))
    }

    @Test fun create_place_duplicate_name_errors() = runTest {
        coEvery { places.getAllSnapshot() } returns listOf(
            PlaceEntity(id = 1, name = "Дом", lat = 1.0, lon = 2.0, radiusM = 50),
        )
        val t = tools()
        t.locationProvider = { 55.75 to 37.61 }
        val out = JSONObject(t.execute(AgentToolCall("1", "create_place", """{"name":"дом"}""")))
        assertEquals("место с именем «дом» уже существует", out.getString("error"))
    }

    @Test fun create_place_radius_below_minimum_is_clamped() = runTest {
        coEvery { places.getAllSnapshot() } returns emptyList()
        val saved = slot<PlaceEntity>()
        coEvery { places.insert(capture(saved)) } returns 1L
        val t = tools()
        t.locationProvider = { null }
        val out = JSONObject(t.execute(AgentToolCall("1", "create_place",
            """{"name":"Дача","lat":56.1,"lon":38.2,"radius_m":5}""")))
        assertTrue(out.getBoolean("ok"))
        assertEquals(20, saved.captured.radiusM)
    }

    @Test fun create_place_at_limit_errors() = runTest {
        val existing = (1..50).map {
            PlaceEntity(id = it.toLong(), name = "Место $it", lat = 0.0, lon = 0.0, radiusM = 100)
        }
        coEvery { places.getAllSnapshot() } returns existing
        val t = tools()
        t.locationProvider = { 55.75 to 37.61 }
        val out = JSONObject(t.execute(AgentToolCall("1", "create_place", """{"name":"Новое"}""")))
        assertEquals("достигнут предел в 50 мест", out.getString("error"))
    }

    @Test fun create_place_only_one_coord_errors() = runTest {
        coEvery { places.getAllSnapshot() } returns emptyList()
        val t = tools()
        t.locationProvider = { 55.75 to 37.61 }
        val out = JSONObject(t.execute(AgentToolCall("1", "create_place", """{"name":"Дом","lat":56.1}""")))
        assertTrue(out.has("error"))
    }

    // --- navigate_to: geocoding of free-text destinations ---

    // (a) destination is not a saved Place; geocode succeeds -> route by the geocoded coordinates.
    @Test fun navigate_to_geocodes_free_text_destination() = runTest {
        val captured = slot<ActionDef>()
        coEvery { dispatcher.dispatch(capture(captured), any()) } returns DispatchResult(true)
        coEvery { places.getAllSnapshot() } returns emptyList()
        coEvery { weather.geocode("Орша") } returns
            Result.success(WeatherClient.GeoPoint(54.5, 30.4, "Орша"))
        val out = JSONObject(tools().execute(
            AgentToolCall("1", "navigate_to", """{"destination":"Орша"}""")))
        assertTrue(out.getBoolean("ok"))
        assertEquals("route", out.getString("mode"))
        assertEquals("Орша", out.getString("destination"))
        assertEquals("navigate", captured.captured.kind)
        val payload = JSONObject(captured.captured.payload!!)
        assertEquals(54.5, payload.getDouble("lat"), 1e-6)
        assertEquals(30.4, payload.getDouble("lon"), 1e-6)
    }

    // (b) geocode returns a failure Result -> falls back to the query search payload.
    @Test fun navigate_to_geocode_failure_falls_back_to_search() = runTest {
        val captured = slot<ActionDef>()
        coEvery { dispatcher.dispatch(capture(captured), any()) } returns DispatchResult(true)
        coEvery { places.getAllSnapshot() } returns emptyList()
        coEvery { weather.geocode("Тьмутаракань") } returns
            Result.failure(RuntimeException("город не найден"))
        val out = JSONObject(tools().execute(
            AgentToolCall("1", "navigate_to", """{"destination":"Тьмутаракань"}""")))
        assertTrue(out.getBoolean("ok"))
        assertEquals("search", out.getString("mode"))
        val payload = JSONObject(captured.captured.payload!!)
        assertEquals("Тьмутаракань", payload.getString("query"))
    }

    // (c) geocode throws instead of returning a Result -> not propagated, falls back to search.
    @Test fun navigate_to_survives_geocode_exception() = runTest {
        val captured = slot<ActionDef>()
        coEvery { dispatcher.dispatch(capture(captured), any()) } returns DispatchResult(true)
        coEvery { places.getAllSnapshot() } returns emptyList()
        coEvery { weather.geocode(any()) } throws RuntimeException("network down")
        val out = JSONObject(tools().execute(
            AgentToolCall("1", "navigate_to", """{"destination":"Тьмутаракань"}""")))
        assertTrue(out.getBoolean("ok"))
        assertEquals("search", out.getString("mode"))
        val payload = JSONObject(captured.captured.payload!!)
        assertEquals("Тьмутаракань", payload.getString("query"))
    }

    // (d) regression: a saved Place still routes by its own lat/lon; geocode is never called.
    @Test fun navigate_to_saved_place_skips_geocode() = runTest {
        val captured = slot<ActionDef>()
        coEvery { dispatcher.dispatch(capture(captured), any()) } returns DispatchResult(true)
        coEvery { places.getAllSnapshot() } returns listOf(
            PlaceEntity(name = "Дом", lat = 55.75, lon = 37.61, radiusM = 100),
        )
        val out = JSONObject(tools().execute(
            AgentToolCall("1", "navigate_to", """{"destination":"дом"}""")))
        assertTrue(out.getBoolean("ok"))
        assertEquals("route", out.getString("mode"))
        assertEquals("Дом", out.getString("place"))
        val payload = JSONObject(captured.captured.payload!!)
        assertEquals(55.75, payload.getDouble("lat"), 1e-6)
        assertEquals(37.61, payload.getDouble("lon"), 1e-6)
        coVerify(exactly = 0) { weather.geocode(any()) }
    }

    // (e) regression: empty destination -> error, neither geocode nor the dispatcher is called.
    @Test fun navigate_to_empty_destination_reports_error() = runTest {
        val out = JSONObject(tools().execute(
            AgentToolCall("1", "navigate_to", """{"destination":"  "}""")))
        assertTrue(out.has("error"))
        coVerify(exactly = 0) { weather.geocode(any()) }
        coVerify(exactly = 0) { dispatcher.dispatch(any(), any()) }
    }

    @Test fun schemas_expose_places_tools() = runTest {
        coEvery { ruleDao.getEnabled() } returns emptyList()
        coEvery { settingsRepository.getString(SettingsRepository.KEY_EXA_API_KEY, "") } returns ""
        val arr = tools().schemas()
        val names = (0 until arr.length()).mapNotNull {
            arr.getJSONObject(it).optJSONObject("function")?.getString("name")
        }
        assertTrue(names.contains("list_places"))
        assertTrue(names.contains("create_place"))
    }
}
