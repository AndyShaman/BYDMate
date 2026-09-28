package com.bydmate.app.agent

import android.content.Context
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.AutomationEngine
import com.bydmate.app.data.automation.DispatchResult
import com.bydmate.app.data.charging.ChargeConnector
import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.remote.InsightsManager
import com.bydmate.app.data.remote.OpenRouterClient
import com.bydmate.app.data.repository.PlaceRepository
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.domain.battery.BatteryStateRepository
import com.bydmate.app.domain.calculator.RangeCalculator
import com.bydmate.app.domain.tracker.TrackPoint
import com.bydmate.app.voice.VoiceGate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.StringReader
import java.time.Instant
import kotlin.math.cos

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

    private fun newTools(charger: ChargerSearchClient = chargerSearchClient) = AgentTools(
        gate, battery, range, tripDao, chargeDao, dispatcher, ruleDao, engine, places, weather,
        exa, openRouterClient, settingsRepository, contactLookup, context,
        mockk<ClusterVoiceControl>(relaxed = true),
        charger,
        mockk<InsightsManager>(relaxed = true),
        mockk<ZaiSearchClient>(relaxed = true),
        mockk<LlmConnectionResolver>(relaxed = true),
    ).also {
        it.naviForegroundCheck = { true }
        it.naviVerifyAttempts = 1
        it.naviVerifyIntervalMs = 1L
        it.recentTrackProvider = { track }
        it.sessionProvider = { 42L }
        it.nowMs = { nowMs }
    }

    private val tools = newTools()

    private var track: List<TrackPoint> = emptyList()
    private var nowMs = Instant.parse("2026-09-28T07:45:04.885Z").toEpochMilli()

    @Before fun setUp() {
        every { gate.vehicleSnapshot() } returns null
        coEvery { range.estimate(any(), any(), any()) } returns null
        coEvery { settingsRepository.getChargeConnector() } returns ChargeConnector.GBT
    }

    private fun call(name: String, args: String) = AgentToolCall("1", name, args)

    private fun station(name: String, lat: Double, lon: Double) =
        ChargerStation(name, address = null, operator = null, lat = lat, lon = lon)

    private fun osm(vararg stations: ChargerStation) =
        Result.success(ChargerSearchClient.Found(ChargerSource.OSM, stations.toList()))

    private suspend fun findChargers(args: String = "{}") = JSONObject(tools.execute(call("find_chargers", args)))

    private fun JSONObject.chargerNames(): List<String> {
        val arr = getJSONArray("chargers")
        return (0 until arr.length()).map { arr.getJSONObject(it).getString("name") }
    }

    // (a) city given -> geocodes the city, does not use the GPS position.
    @Test fun find_chargers_with_city_uses_geocode_not_gps() = runTest {
        tools.locationProvider = { 10.0 to 20.0 }
        coEvery { weather.geocode("Орша") } returns
            Result.success(WeatherClient.GeoPoint(54.508, 30.42, "Орша"))
        coEvery { chargerSearchClient.find(54.508, 30.42, any(), any()) } returns osm(station("ЭЗС", 54.51, 30.43))
        val out = findChargers("""{"city":"Орша"}""")
        assertEquals(1, out.getJSONArray("chargers").length())
        coVerify(exactly = 0) { chargerSearchClient.find(10.0, 20.0, any(), any()) }
    }

    // (b) no city -> falls back to current GPS position, 30 km around by default.
    @Test fun find_chargers_without_city_uses_gps() = runTest {
        tools.locationProvider = { 53.9 to 27.56 }
        coEvery { chargerSearchClient.find(53.9, 27.56, 30_000, ChargeConnector.GBT) } returns
            osm(station("ЭЗС", 53.91, 27.57))
        val out = findChargers()
        assertEquals(1, out.getJSONArray("chargers").length())
        assertEquals("around", out.getString("where"))
        coVerify(exactly = 0) { weather.geocode(any()) }
    }

    // (c) results are sorted by distance ascending and capped.
    @Test fun find_chargers_sorts_by_distance_and_caps_the_list() = runTest {
        tools.locationProvider = { 0.0 to 0.0 }
        // Deliberately shuffled input: the expected output order must come from sorting,
        // not from the fixture happening to be pre-sorted.
        val stations = listOf(4, 1, 9, 7, 3, 10, 6, 2, 8, 5).map { i -> station("C$i", 0.01 * i, 0.0) }
        coEvery { chargerSearchClient.find(0.0, 0.0, any(), any()) } returns osm(*stations.toTypedArray())
        val out = findChargers()
        assertEquals((1..8).map { "C$it" }, out.chargerNames())
    }

    // (d) empty result set -> chargers array empty, with an explanatory note.
    @Test fun find_chargers_empty_result_returns_note() = runTest {
        tools.locationProvider = { 53.9 to 27.56 }
        coEvery { chargerSearchClient.find(53.9, 27.56, any(), any()) } returns osm()
        val out = findChargers()
        assertEquals(0, out.getJSONArray("chargers").length())
        assertTrue(out.has("note"))
    }

    // (e) search failure -> Russian error, not the raw exception.
    @Test fun find_chargers_search_failure_returns_russian_error() = runTest {
        tools.locationProvider = { 53.9 to 27.56 }
        coEvery { chargerSearchClient.find(53.9, 27.56, any(), any()) } returns
            Result.failure(RuntimeException("boom"))
        val out = findChargers()
        assertEquals("сервис поиска зарядок недоступен, попробуй позже", out.getString("error"))
    }

    // (f) radius_km is capped at 100 even if the LLM asks for more.
    @Test fun find_chargers_radius_km_capped_at_100() = runTest {
        tools.locationProvider = { 53.9 to 27.56 }
        coEvery { chargerSearchClient.find(53.9, 27.56, any(), any()) } returns osm()
        findChargers("""{"radius_km":500}""")
        coVerify { chargerSearchClient.find(53.9, 27.56, 100_000, any()) }
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

    // --- the answer the model reads, from the real BETA fixture (National Library, Minsk) ---

    private val betaFixture = requireNotNull(javaClass.classLoader?.getResource("beta/map-rsc-national-library.txt")).readText()
    private val betaStations = BetaMapParser.parse(StringReader(betaFixture))

    private suspend fun betaAnswer(args: String = "{}"): JSONObject {
        tools.locationProvider = { 53.9313 to 27.6461 }
        coEvery { chargerSearchClient.find(any(), any(), any(), any()) } returns
            Result.success(ChargerSearchClient.Found(ChargerSource.BETA, betaStations))
        return findChargers(args)
    }

    @Test fun beta_answer_contract_for_the_model() = runTest {
        val out = betaAnswer()
        assertEquals("beta.by", out.getString("status_source"))
        assertEquals("GB/T", out.getString("connector"))
        assertTrue(out.getBoolean("connector_known"))
        assertTrue(out.getBoolean("status_known"))
        assertEquals("around", out.getString("where"))
        assertEquals(30, out.getInt("radius_km"))
        val first = out.getJSONArray("chargers").getJSONObject(0)
        assertEquals("Пр-т Независимости, 116 (TZone)", first.getString("name"))
        assertEquals("Минск, Пр-т Независимости, 116", first.getString("address"))
        assertEquals("Malanka", first.getString("operator"))
        assertEquals(0.1, first.getDouble("distance_km"), 0.0)
        assertEquals("юго-восток", first.getString("direction_from_car"))
        assertEquals(53.930607, first.getDouble("lat"), 1e-9)
        assertEquals(27.647039, first.getDouble("lon"), 1e-9)
        assertEquals("free", first.getString("status"))
        assertEquals(1, first.getInt("connectors_total"))
        assertEquals(1, first.getInt("connectors_free"))
        assertEquals(50, first.getInt("max_power_kw"))
        assertEquals(0.73, first.getDouble("price_per_kwh"), 1e-9)
        assertEquals("BYN", first.getString("currency"))
        assertEquals(10, first.getInt("status_age_min"))
        assertFalse(first.has("status_stale"))
        assertFalse(first.has("connectors_unknown"))
        assertEquals("connector", out.getString("status_scope"))
        val note = out.getString("note")
        assertTrue(note, note.contains("по прямой"))
        assertTrue(note, note.contains("navigate_to"))
    }

    // Type2-only №46 is left out for a GB/T car; busy and broken GB/T stations stay, marked.
    @Test fun beta_answer_keeps_busy_and_broken_gbt_stations_and_drops_other_types() = runTest {
        val out = betaAnswer()
        val names = out.chargerNames()
        assertEquals(7, names.size)
        assertTrue(names.none { it.startsWith("№46") })
        val chargers = out.getJSONArray("chargers")
        val byName = (0 until chargers.length()).map { chargers.getJSONObject(it) }.associateBy { it.getString("name") }
        val busy = byName.getValue("Петра Мстиславца 22а, Минск")
        assertEquals("busy", busy.getString("status"))
        assertEquals(0, busy.getInt("connectors_free"))
        assertEquals(4, busy.getInt("connectors_total"))
        assertEquals("unavailable", byName.getValue("ТД Азербайджан (forEVo,7)").getString("status"))
    }

    // Occupancy imported hours or months ago is not "free now".
    @Test fun old_status_is_flagged_stale() = runTest {
        val chargers = betaAnswer().getJSONArray("chargers")
        val stale = (0 until chargers.length()).map { chargers.getJSONObject(it) }
            .single { it.getString("name").startsWith("№48") }
        assertTrue(stale.getBoolean("status_stale"))
        assertTrue(stale.getInt("status_age_min") > 60 * 24 * 40)
    }

    // «Разъём для зарядки» in Settings reaches the search and the answer.
    @Test fun connector_setting_reaches_the_tool() = runTest {
        coEvery { settingsRepository.getChargeConnector() } returns ChargeConnector.CCS2
        tools.locationProvider = { 53.9313 to 27.6461 }
        coEvery { chargerSearchClient.find(any(), any(), any(), ChargeConnector.CCS2) } returns
            Result.success(ChargerSearchClient.Found(ChargerSource.BETA, betaStations))
        val out = findChargers()
        assertEquals("CCS2", out.getString("connector"))
        assertTrue(out.chargerNames().contains("№48 - 40 кВт - Мстиславца 6"))
        assertTrue(out.chargerNames().none { it.startsWith("Петра Мстиславца") })
    }

    @Test fun connector_asked_by_voice_overrides_the_setting() = runTest {
        val out = betaAnswer("""{"connector":"Type2"}""")
        assertEquals("Type2", out.getString("connector"))
        assertTrue(out.chargerNames().any { it.startsWith("№46") })
        coVerify { chargerSearchClient.find(any(), any(), any(), ChargeConnector.TYPE2) }
    }

    @Test fun no_station_with_the_cars_connector_says_so() = runTest {
        tools.locationProvider = { 53.9313 to 27.6461 }
        coEvery { chargerSearchClient.find(any(), any(), any(), any()) } returns Result.success(
            ChargerSearchClient.Found(ChargerSource.BETA, betaStations.filter { it.name.startsWith("№46") }))
        val out = findChargers()
        assertEquals(listOf("№46 - 2×22 кВт (Type 2-Type 2) - Мстиславца 6 Минск"), out.chargerNames())
        val station = out.getJSONArray("chargers").getJSONObject(0)
        assertEquals(JSONArray().put("Type2").toString(), station.getJSONArray("connector_types").toString())
        assertFalse(station.has("connectors_total"))
        assertTrue(out.getString("note").contains("GB/T"))
    }

    // Gateway: stations and a whole-station status for some networks, no connectors.
    @Test fun gateway_answer_says_connectors_are_unknown() = runTest {
        tools.locationProvider = { 53.9 to 27.56 }
        coEvery { chargerSearchClient.find(any(), any(), any(), any()) } returns Result.success(
            ChargerSearchClient.Found(ChargerSource.GATEWAY, listOf(
                station("Пулихова", 53.899, 27.576).copy(operator = "Evika", status = StationStatus.BUSY),
                station("TZone", 53.884, 27.427).copy(operator = "Malanka"),
            )))
        val out = findChargers()
        assertEquals("malanka_gateway", out.getString("status_source"))
        assertFalse(out.getBoolean("connector_known"))
        assertTrue(out.getBoolean("status_known"))
        // The status is the whole station's, not the car's connector's.
        assertEquals("station", out.getString("status_scope"))
        assertTrue(out.getString("note").contains("занятость по станции целиком, про разъём машины неизвестно"))
        val first = out.getJSONArray("chargers").getJSONObject(0)
        assertEquals("busy", first.getString("status"))
        assertFalse(first.has("connectors_total"))
        assertFalse(out.getJSONArray("chargers").getJSONObject(1).has("status"))
    }

    // A connector BETA sends without a status is unknown, not busy: the station gets no status.
    @Test fun unknown_connector_status_is_not_reported_as_busy() = runTest {
        tools.locationProvider = { 53.9 to 27.56 }
        fun gbt(status: String?) = StationConnector("GBT_DC", 60.0, status, 0.73, "BYN")
        coEvery { chargerSearchClient.find(any(), any(), any(), any()) } returns Result.success(
            ChargerSearchClient.Found(ChargerSource.BETA, listOf(
                station("Один неизвестен", 53.91, 27.56).copy(connectors = listOf(gbt(null), gbt("charging"))),
                station("Оба неизвестны", 53.92, 27.56).copy(connectors = listOf(gbt(null), gbt(null))),
            )))
        val out = findChargers()
        val chargers = out.getJSONArray("chargers")
        for ((i, unknown) in listOf(1, 2).withIndex()) {
            val s = chargers.getJSONObject(i)
            assertFalse(s.toString(), s.has("status"))
            assertEquals(0, s.getInt("connectors_free"))
            assertEquals(unknown, s.getInt("connectors_unknown"))
        }
        assertTrue(out.getString("note").contains("нет данных о занятости"))
    }

    @Test fun openstreetmap_answer_says_connectors_and_status_are_unknown() = runTest {
        tools.locationProvider = { 55.75 to 37.62 }
        coEvery { chargerSearchClient.find(any(), any(), any(), any()) } returns osm(station("ЭЗС", 55.76, 37.63))
        val out = findChargers()
        assertEquals("openstreetmap", out.getString("status_source"))
        assertFalse(out.getBoolean("connector_known"))
        assertFalse(out.getBoolean("status_known"))
        assertFalse(out.has("status_scope"))
        assertFalse(out.getJSONArray("chargers").getJSONObject(0).has("status"))
    }

    // End to end with the real clients: BETA answers 503, the gateway takes over.
    @Test fun beta_down_answer_comes_from_the_gateway_with_connectors_unknown() = runTest {
        val beta = MockWebServer().apply { start() }
        val gateway = MockWebServer().apply { start() }
        try {
            beta.enqueue(MockResponse().setResponseCode(503))
            gateway.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.path!!.contains("/evika/")) MockResponse().setBody(
                        """[{"id":"1","name":"Минск, ул. Пулихова, 5","address":"Минск","latitude":53.89911,"longitude":27.575947,"status":"AVAILABLE"}]""")
                    else MockResponse().setResponseCode(502)
            }
            val real = ChargerSearchClient(OkHttpClient()).also {
                it.beta.url = beta.url("/map").toString()
                it.gateway.baseUrl = gateway.url("/central-system/api/v1/").toString()
                it.endpoints = listOf(beta.url("/overpass-never").toString())
            }
            val t = newTools(real)
            t.locationProvider = { 53.9 to 27.56 }
            val out = JSONObject(t.execute(call("find_chargers", "{}")))
            assertEquals("malanka_gateway", out.getString("status_source"))
            assertFalse(out.getBoolean("connector_known"))
            assertEquals("free", out.getJSONArray("chargers").getJSONObject(0).getString("status"))
            assertEquals("/map", beta.takeRequest().path)
        } finally {
            beta.shutdown()
            gateway.shutdown()
        }
    }

    // --- direction of travel ---

    private val kmPerDegLat = 111.195
    private val kmPerDegLon = kmPerDegLat * cos(Math.toRadians(53.9))
    private fun north(km: Double, eastKm: Double = 0.0) = (53.9 + km / kmPerDegLat) to (27.56 + eastKm / kmPerDegLon)

    /** Two kilometres north at 60 km/h ending at the car (53.9, 27.56), fixes every 50 m. */
    private fun northboundTrack(): List<TrackPoint> = (0..40).map { i ->
        val (lat, lon) = north(-2.0 + i * 0.05)
        TrackPoint(atMs = i * 3_000L, lat = lat, lon = lon, speedKmh = 60.0)
    }

    private fun moving(speed: Int) {
        track = northboundTrack()
        tools.locationProvider = { 53.9 to 27.56 }
        val data = mockk<DiParsData>(relaxed = true)
        every { data.speed } returns speed
        every { gate.vehicleSnapshot() } returns data
    }

    private fun stationAt(name: String, northKm: Double, eastKm: Double = 0.0): ChargerStation {
        val (lat, lon) = north(northKm, eastKm)
        return station(name, lat, lon)
    }

    @Test fun moving_with_a_known_course_searches_ahead_by_default() = runTest {
        moving(speed = 75)
        coEvery { chargerSearchClient.find(any(), any(), any(), any()) } returns osm(
            stationAt("Позади", -3.0), stationAt("Впереди", 20.0, 2.0))
        val out = findChargers()
        assertEquals("ahead", out.getString("where"))
        assertEquals("север", out.getString("course"))
        assertEquals(listOf("Впереди"), out.chargerNames())
        assertEquals("ahead", out.getJSONArray("chargers").getJSONObject(0).getString("position"))
        // 75 km/h: a 60 km radius ahead.
        coVerify { chargerSearchClient.find(53.9, 27.56, 60_000, any()) }
    }

    @Test fun around_asked_by_voice_while_moving_lists_all_sides_marked() = runTest {
        moving(speed = 75)
        coEvery { chargerSearchClient.find(any(), any(), any(), any()) } returns osm(
            stationAt("Позади", -3.0), stationAt("Впереди", 20.0))
        val out = findChargers("""{"where":"around"}""")
        assertEquals("around", out.getString("where"))
        assertEquals(listOf("Позади", "Впереди"), out.chargerNames())
        assertEquals("behind", out.getJSONArray("chargers").getJSONObject(0).getString("position"))
    }

    @Test fun nothing_ahead_answers_with_the_nearest_around_and_says_so() = runTest {
        moving(speed = 75)
        coEvery { chargerSearchClient.find(any(), any(), any(), any()) } returns osm(stationAt("Позади", -3.0))
        val out = findChargers()
        assertEquals(listOf("Позади"), out.chargerNames())
        assertEquals("behind", out.getJSONArray("chargers").getJSONObject(0).getString("position"))
        assertTrue(out.getString("note").contains("впереди"))
    }

    @Test fun ahead_asked_while_parked_says_the_direction_is_unknown() = runTest {
        tools.locationProvider = { 53.9 to 27.56 }
        coEvery { chargerSearchClient.find(any(), any(), any(), any()) } returns osm(stationAt("Рядом", 1.0))
        val out = findChargers("""{"where":"ahead"}""")
        assertEquals("around", out.getString("where"))
        assertTrue(out.getString("note").contains("направление движения неизвестно"))
        assertFalse(out.getJSONArray("chargers").getJSONObject(0).has("position"))
    }

    // The route the agent built in this trip: stations with a small detour, detour_km reported.
    @Test fun route_built_by_navigate_to_ranks_stations_by_detour() = runTest {
        moving(speed = 90)
        coEvery { dispatcher.dispatch(any(), any()) } returns DispatchResult(true)
        val (dLat, dLon) = north(80.0)
        tools.execute(call("navigate_to", """{"lat":$dLat,"lon":$dLon,"destination":"Логойск"}"""))
        coEvery { chargerSearchClient.find(any(), any(), any(), any()) } returns osm(
            stationAt("Крюк", 30.0, 25.0), stationAt("По пути", 40.0, 1.0))
        val out = findChargers()
        assertEquals("ahead", out.getString("where"))
        assertEquals(listOf("По пути"), out.chargerNames())
        assertTrue(out.getJSONArray("chargers").getJSONObject(0).getDouble("detour_km") < 1.0)
    }

    // A route from an earlier trip (another ignition cycle) is not "the route".
    @Test fun route_from_another_trip_is_ignored() = runTest {
        moving(speed = 90)
        coEvery { dispatcher.dispatch(any(), any()) } returns DispatchResult(true)
        val (dLat, dLon) = north(80.0)
        tools.execute(call("navigate_to", """{"lat":$dLat,"lon":$dLon}"""))
        tools.sessionProvider = { 43L }
        coEvery { chargerSearchClient.find(any(), any(), any(), any()) } returns osm(stationAt("Крюк", 30.0, 25.0))
        val out = findChargers()
        assertFalse(out.getJSONArray("chargers").getJSONObject(0).has("detour_km"))
    }

    @Test fun stations_beyond_the_range_are_marked_not_dropped() = runTest {
        moving(speed = 90)
        coEvery { range.estimate(any(), any(), any()) } returns 30.0
        coEvery { chargerSearchClient.find(any(), any(), any(), any()) } returns osm(
            stationAt("10 км", 10.0), stationAt("50 км", 50.0))
        val chargers = findChargers().getJSONArray("chargers")
        assertFalse(chargers.getJSONObject(0).has("beyond_range"))
        assertTrue(chargers.getJSONObject(1).getBoolean("beyond_range"))
    }

    @Test fun tool_schema_offers_connector_and_where() = runTest {
        val schema = (0 until tools.schemas().length()).map { tools.schemas().getJSONObject(it) }
            .single { it.getJSONObject("function").getString("name") == "find_chargers" }
            .getJSONObject("function")
        val props = schema.getJSONObject("parameters").getJSONObject("properties")
        assertEquals(listOf("GB/T", "CCS2", "Type2", "CHAdeMO"),
            props.getJSONObject("connector").getJSONArray("enum").let { a -> (0 until a.length()).map { a.getString(it) } })
        assertEquals(listOf("around", "ahead"),
            props.getJSONObject("where").getJSONArray("enum").let { a -> (0 until a.length()).map { a.getString(it) } })
        val description = schema.getString("description")
        assertTrue(description, description.contains("по пути"))
        assertTrue(description, description.contains("navigate_to"))
    }
}
