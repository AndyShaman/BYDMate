package com.bydmate.app.agent

import android.content.Context
import android.location.Location
import com.bydmate.app.agent.SettlementSearchClient.Surroundings
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
import com.bydmate.app.domain.battery.BatteryStateRepository
import com.bydmate.app.domain.calculator.RangeCalculator
import com.bydmate.app.voice.VoiceGate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** where_am_i: on-car log 25.09 had the model web-search raw coordinates and name wrong villages. */
class AgentToolsWhereAmITest {

    // relaxed: whereAmI() now also reads lastEndpoint for the diagnostic log line, which most
    // tests below have no reason to stub.
    private val settlements = mockk<SettlementSearchClient>(relaxed = true)

    private val tools = AgentTools(
        mockk<VoiceGate>(relaxed = true), mockk<BatteryStateRepository>(relaxed = true),
        mockk<RangeCalculator>(relaxed = true), mockk<TripDao>(relaxed = true),
        mockk<ChargeDao>(relaxed = true), mockk<ActionDispatcher>(relaxed = true),
        mockk<RuleDao>(relaxed = true), mockk<AutomationEngine>(relaxed = true),
        mockk<PlaceRepository>(relaxed = true), mockk<WeatherClient>(relaxed = true),
        mockk<ExaSearchClient>(relaxed = true), mockk<OpenRouterClient>(relaxed = true),
        mockk<SettingsRepository>(relaxed = true), mockk<ContactLookup>(relaxed = true),
        mockk<Context>(relaxed = true), mockk<ClusterVoiceControl>(relaxed = true),
        mockk<ChargerSearchClient>(relaxed = true), mockk<InsightsManager>(relaxed = true),
        mockk<ZaiSearchClient>(relaxed = true), mockk<LlmConnectionResolver>(relaxed = true),
    ).also {
        it.injectSettlementSearch(settlements)
        it.gpsFixProvider = { AgentTools.GpsFix(54.0, 27.0, ageMs = 5_000L, live = true) }
    }

    private suspend fun whereAmI() = JSONObject(tools.execute(AgentToolCall("1", "where_am_i", "{}")))

    private fun s(name: String, place: String, lat: Double, lon: Double) =
        SettlementSearchClient.Settlement(name, place, lat, lon)

    private fun nearby(vararg found: SettlementSearchClient.Settlement) = Result.success(Surroundings.Nearby(found.toList()))

    @Test fun nearest_first_with_distance_direction_and_nearest_town() = runTest {
        coEvery { settlements.search(54.0, 27.0, 15_000, 50_000) } returns nearby(
            s("Город", "town", 53.9, 27.1),
            s("Западная", "hamlet", 54.0, 26.97),
            s("Северная", "village", 54.01, 27.0),
            s("Хутор", "isolated_dwelling", 54.001, 27.0),
        )
        val out = whereAmI()
        val list = out.getJSONArray("settlements")
        assertEquals(3, list.length())
        val first = list.getJSONObject(0)
        assertEquals("Северная", first.getString("name"))
        assertEquals(1.1, first.getDouble("distance_km"), 0.001)
        assertEquals("север", first.getString("direction_from_car"))
        assertEquals("деревня или посёлок", first.getString("type"))
        assertEquals("Западная", list.getJSONObject(1).getString("name"))
        assertEquals("запад", list.getJSONObject(1).getString("direction_from_car"))
        assertEquals("юго-восток", list.getJSONObject(2).getString("direction_from_car"))
        val town = out.getJSONObject("nearest_town")
        assertEquals("Город", town.getString("name"))
        assertEquals("город", town.getString("type"))
        assertFalse(out.toString().contains("Хутор"))
    }

    @Test fun isolated_dwelling_is_used_when_nothing_else_is_around() = runTest {
        coEvery { settlements.search(any(), any(), any(), any()) } returns
            nearby(s("Хутор", "isolated_dwelling", 54.001, 27.0))
        val list = whereAmI().getJSONArray("settlements")
        assertEquals("Хутор", list.getJSONObject(0).getString("name"))
        assertEquals("хутор", list.getJSONObject(0).getString("type"))
    }

    @Test fun caps_the_list_at_five() = runTest {
        coEvery { settlements.search(any(), any(), any(), any()) } returns
            Result.success(Surroundings.Nearby((1..9).map { s("Д$it", "hamlet", 54.0 + it / 100.0, 27.0) }))
        val list = whereAmI().getJSONArray("settlements")
        assertEquals(5, list.length())
        assertEquals("Д1", list.getJSONObject(0).getString("name"))
    }

    @Test fun empty_result_tells_the_model_not_to_guess() = runTest {
        coEvery { settlements.search(any(), any(), any(), any()) } returns nearby()
        val out = whereAmI()
        assertEquals(0, out.getJSONArray("settlements").length())
        assertTrue(out.getString("note").contains("не называй место наугад"))
    }

    @Test fun search_failure_is_an_error() = runTest {
        coEvery { settlements.search(any(), any(), any(), any()) } returns Result.failure(IOException("timeout"))
        assertTrue(whereAmI().getString("error").contains("не могу определить, где мы"))
    }

    // --- Overpass down (field 28.09: 504 under load): the answer comes from Nominatim ---

    private val minsk = requireNotNull(javaClass.classLoader?.getResource("nominatim/reverse-minsk.json")).readText()

    /** where_am_i through a real client on two MockWebServers: Overpass, then Nominatim. */
    private suspend fun whereAmIOver(overpass: MockResponse, nominatim: MockResponse): JSONObject {
        val overpassServer = MockWebServer()
        val nominatimServer = MockWebServer()
        try {
            overpassServer.enqueue(overpass)
            nominatimServer.enqueue(nominatim)
            tools.injectSettlementSearch(SettlementSearchClient(OkHttpClient()).also {
                it.endpoints = listOf(overpassServer.url("/api/interpreter").toString())
                it.nominatimUrl = nominatimServer.url("/reverse").toString()
            })
            return whereAmI()
        } finally {
            overpassServer.shutdown()
            nominatimServer.shutdown()
        }
    }

    @Test fun overpass_down_the_answer_names_the_city_from_nominatim_in_russian() = runTest {
        val out = whereAmIOver(MockResponse().setResponseCode(504), MockResponse().setBody(minsk))
        val place = out.getJSONArray("settlements").getJSONObject(0)
        assertEquals("Минск", place.getString("name"))
        assertEquals("город", place.getString("type"))
        // A reverse geocode knows the point's own address, not the centre: no distance, no side.
        assertFalse(place.has("distance_km"))
        assertFalse(place.has("direction_from_car"))
        assertFalse(out.has("nearest_town"))
        val address = out.getJSONObject("address")
        assertEquals("проспект Независимости", address.getString("street"))
        assertEquals("Ленинский район", address.getString("district"))
        assertEquals("Беларусь", address.getString("country"))
        assertFalse(address.has("region"))
        val note = out.getString("note")
        assertTrue(note, note.contains("расстояний"))
        assertTrue(note, note.contains("Называй только эти места"))
    }

    @Test fun both_servers_down_is_the_fixed_error() = runTest {
        val out = whereAmIOver(MockResponse().setResponseCode(504), MockResponse().setResponseCode(503))
        assertEquals("сервис карт недоступен, не могу определить, где мы", out.getString("error"))
    }

    // Out of town Nominatim may name only the road and the region.
    @Test fun an_address_without_a_settlement_has_no_settlements_list() = runTest {
        coEvery { settlements.search(any(), any(), any(), any()) } returns Result.success(
            Surroundings.Address(null, null, "М-1", null, "Минская область", "Беларусь"))
        val out = whereAmI()
        assertFalse(out.has("settlements"))
        assertEquals("М-1", out.getJSONObject("address").getString("street"))
        assertEquals("Минская область", out.getJSONObject("address").getString("region"))
    }

    @Test fun no_gps_fix_is_an_error_without_a_network_call() = runTest {
        tools.gpsFixProvider = { null }
        assertTrue(whereAmI().getString("error").contains("нет GPS-позиции"))
        coVerify(exactly = 0) { settlements.search(any(), any(), any(), any()) }
    }

    // Review of 30f1f01d: only centre points are known, a car 1 km from a compact village is
    // not in it; the old note told the model "меньше 1-2 км значит машина в нём".
    @Test fun note_never_claims_the_car_is_inside_a_settlement() = runTest {
        coEvery { settlements.search(any(), any(), any(), any()) } returns
            nearby(s("Северная", "village", 54.005, 27.0))
        val note = whereAmI().getString("note")
        assertFalse(note, note.contains("значит машина в нём"))
        assertTrue(note, note.contains("не говори, что машина в нём"))
        assertTrue(note, note.contains("примерно N км от X"))
    }

    @Test fun fresh_fix_reports_its_age_without_a_hedge() = runTest {
        coEvery { settlements.search(any(), any(), any(), any()) } returns
            nearby(s("Северная", "village", 54.01, 27.0))
        val out = whereAmI()
        assertEquals(0L, out.getLong("fix_age_min"))
        assertFalse(out.has("fix_note"))
    }

    // Parked car: the 8 m GPS filter stops updates, the fix is old but still right. Review of
    // 2c994429: the hedge must not depend on current speed - zero speed now does not prove the
    // car has been parked since the fix (it could have moved after the signal dropped).
    @Test fun old_live_fix_always_hedges_regardless_of_current_speed() = runTest {
        tools.gpsFixProvider = { AgentTools.GpsFix(54.0, 27.0, ageMs = 45 * 60_000L, live = true) }
        coEvery { settlements.search(any(), any(), any(), any()) } returns
            nearby(s("Северная", "village", 54.01, 27.0))
        val out = whereAmI()
        assertEquals(45L, out.getLong("fix_age_min"))
        assertEquals("Северная", out.getJSONArray("settlements").getJSONObject(0).getString("name"))
        val fixNote = out.getString("fix_note")
        assertTrue(fixNote, fixNote.contains("машина могла стоять"))
        assertTrue(fixNote, fixNote.contains("по последним данным"))
    }

    // Start without a fresh fix: only the last-known seed, possibly from before the drive.
    @Test fun start_time_seed_is_reported_as_last_known_data() = runTest {
        tools.gpsFixProvider = { AgentTools.GpsFix(54.0, 27.0, ageMs = 600 * 60_000L, live = false) }
        coEvery { settlements.search(any(), any(), any(), any()) } returns nearby()
        val out = whereAmI()
        assertEquals(600L, out.getLong("fix_age_min"))
        val fixNote = out.getString("fix_note")
        assertTrue(fixNote, fixNote.contains("свежего GPS-сигнала после запуска не было"))
        assertTrue(fixNote, fixNote.contains("по последним данным"))
    }

    @Test fun tool_is_declared_and_steers_away_from_web_search() = runTest {
        val schemas = tools.schemas(includeAutomationTools = false)
        val fn = (0 until schemas.length()).map { schemas.getJSONObject(it).getJSONObject("function") }
            .single { it.getString("name") == "where_am_i" }
        assertTrue(fn.getString("description").contains("где я"))
        assertTrue(fn.getString("description").contains("не ищи координаты через web_search"))
        assertTrue(fn.getString("description").contains("а не в каком месте она"))
    }

    // Review: age used to be System.currentTimeMillis() - Location.time, so a wall-clock change
    // (manual or NTP) turned a stale fix fresh or a fresh one stale. gpsFixAgeMs must depend only
    // on the monotonic elapsedRealtimeNanos seam, never on nowMs (the wall clock).
    @Test fun gps_fix_age_ignores_the_wall_clock() {
        val loc = mockk<Location>()
        every { loc.elapsedRealtimeNanos } returns 100_000_000_000L // 100 s since boot
        tools.elapsedRealtimeNanos = { 130_000_000_000L } // 130 s since boot -> 30 s old
        tools.nowMs = { 1_000L }
        val ageBefore = tools.gpsFixAgeMs(loc)
        tools.nowMs = { 999_999_999_999L } // wall clock jumps far forward
        val ageAfter = tools.gpsFixAgeMs(loc)
        assertEquals(30_000L, ageBefore)
        assertEquals(ageBefore, ageAfter)
    }

    // A provider without the monotonic stamp must not make every fix look hours old.
    @Test fun gps_fix_age_falls_back_to_wall_clock_without_monotonic_stamp() {
        val loc = mockk<Location>()
        every { loc.elapsedRealtimeNanos } returns 0L
        every { loc.time } returns 1_000_000L
        tools.elapsedRealtimeNanos = { 9_000_000_000_000L } // 2.5 h of uptime
        tools.nowMs = { 1_060_000L }
        assertEquals(60_000L, tools.gpsFixAgeMs(loc))
    }
}
