package com.bydmate.app.agent

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.StringReader
import java.time.Instant

class BetaMapClientTest {

    // Cut from the real beta.by/map answer to `RSC: 1` (28.09): the eight stations nearest the
    // National Library in Minsk, as the server sent them, inside the same RSC row they came in.
    private val fixture = requireNotNull(javaClass.classLoader?.getResource("beta/map-rsc-national-library.txt")).readText()

    private fun parse(text: String) = BetaMapParser.parse(StringReader(text))

    private fun reasonOf(text: String): String = try {
        parse(text)
        fail("expected a failure")
        ""
    } catch (e: MapBadAnswer) {
        e.message.orEmpty()
    }

    // --- parser ---

    @Test fun parses_every_station_of_the_fixture() {
        assertEquals(8, parse(fixture).size)
    }

    @Test fun station_fields_come_through() {
        val s = parse(fixture).first()
        assertEquals("Пр-т Независимости, 116 (TZone)", s.name)
        assertEquals("Минск, Пр-т Независимости, 116", s.address)
        assertEquals("Malanka", s.operator)
        assertEquals(53.930607, s.lat, 1e-9)
        assertEquals(27.647039, s.lon, 1e-9)
        assertEquals(Instant.parse("2026-09-28T07:35:04.885Z").toEpochMilli(), s.statusAtMs)
        val connectors = s.connectors!!
        assertEquals(7, connectors.size)
        val gbt = connectors.single { it.standard == "GBT_DC" }
        assertEquals(50.0, gbt.powerKw!!, 0.0)
        assertEquals("available", gbt.status)
        assertEquals(0.73, gbt.pricePerKwh!!, 1e-9)
        assertEquals("BYN", gbt.currency)
    }

    // The real payload carries escaped quotes inside names: "ООО Волдан" in the fixture.
    @Test fun escaped_quotes_inside_a_name_are_unescaped() {
        assertTrue(parse(fixture).any { it.name == "ул. Навуковая, 2 (\"ООО Волдан\")" })
    }

    // BETA labels one operator's tariffs PER_KW with the same amounts other operators give
    // PER_KWH (0.55 AC, 0.73 DC): read as a price per kWh.
    @Test fun per_kw_tariff_reads_as_price_per_kwh() {
        val s = parse(fixture).first { it.name.startsWith("№46") }
        assertEquals(0.55, s.connectors!!.first().pricePerKwh!!, 1e-9)
    }

    // The same data embedded in the HTML page (a proxy or CDN may answer with it): the row is a
    // JS string there, every quote escaped, "</" written as "<\/".
    @Test fun html_embedded_escaped_form_parses_the_same_stations() {
        val row = fixture.lines().first { it.startsWith("1a:") }
        val html = "<html><script>self.__next_f.push([1,${JSONObject.quote(row + "\n")}])</script></html>"
        assertTrue(html.contains("\\\"stations\\\":["))
        val stations = parse(html)
        assertEquals(parse(fixture), stations)
    }

    @Test fun page_without_stations_is_a_failure_with_a_reason() {
        assertEquals("no stations array", reasonOf("<html><body>maintenance</body></html>"))
    }

    @Test fun broken_json_is_a_failure_with_a_reason() {
        assertEquals("bad JSON", reasonOf("""0:{"stations":[{"id":"a","latitude":53.9,,}]}"""))
        assertEquals("bad JSON", reasonOf("""0:{"stations":[42]}"""))
    }

    @Test fun cut_off_payload_is_a_failure_with_a_reason() {
        assertEquals("truncated", reasonOf(fixture.substring(0, fixture.indexOf("Dana Mall"))))
    }

    @Test fun empty_stations_array_is_a_failure_with_a_reason() {
        assertEquals("empty stations", reasonOf("""0:{"stations":[],"filters":{}}"""))
    }

    @Test fun station_without_coordinates_is_skipped() {
        val stations = parse(
            """0:{"stations":[{"name":"Без координат"},""" +
                """{"name":"Есть","latitude":53.9,"longitude":27.5,"status":"available","connectors":[]}]}""")
        assertEquals(listOf("Есть"), stations.map { it.name })
    }

    // Android's org.json turns a JSON null into the string "null": a null field stays null.
    @Test fun null_fields_stay_null() {
        val s = parse(
            """0:{"stations":[{"name":"X","address":null,"operator":null,"latitude":53.9,"longitude":27.5,""" +
                """"lastImportedAt":null,"connectors":[{"standard":"GBT_DC","powerKw":null,"status":null,"tariff":null}]}]}""")
            .single()
        assertNull(s.address)
        assertNull(s.operator)
        assertNull(s.statusAtMs)
        val c = s.connectors!!.single()
        assertNull(c.powerKw)
        assertNull(c.status)
        assertNull(c.pricePerKwh)
    }

    // A station nested thousands of arrays deep fits in the 64 KB cut and would send the
    // recursive JSON parser into a StackOverflowError, which nothing catches.
    @Test fun deeply_nested_station_is_a_failure_with_a_reason() {
        val deep = "[".repeat(5_000) + "]".repeat(5_000)
        assertEquals("bad JSON", reasonOf("""0:{"stations":[{"latitude":53.9,"longitude":27.5,"x":$deep}]}"""))
    }

    // The station object is level 1; a real one goes 3-4 levels deep.
    @Test fun nesting_is_limited_to_max_depth() {
        fun station(levels: Int) = """0:{"stations":[{"latitude":53.9,"longitude":27.5,"x":""" +
            "[".repeat(levels - 1) + "]".repeat(levels - 1) + "}]}"
        assertEquals(1, parse(station(BetaMapParser.MAX_DEPTH)).size)
        assertEquals("bad JSON", reasonOf(station(BetaMapParser.MAX_DEPTH + 1)))
    }

    @Test fun more_stations_than_the_limit_is_a_failure_with_a_reason() {
        val three = """0:{"stations":[""" + List(3) { """{"latitude":53.9,"longitude":27.5}""" }.joinToString(",") + "]}"
        assertEquals(3, BetaMapParser.parse(StringReader(three), maxStations = 3).size)
        val reason = try {
            BetaMapParser.parse(StringReader(three), maxStations = 2)
            "no failure"
        } catch (e: MapBadAnswer) {
            e.message
        }
        assertEquals("bad JSON", reason)
    }

    // The real map holds 1544 stations (28.09).
    @Test fun production_station_limit_is_10000() {
        assertEquals(10_000, BetaMapParser.MAX_STATIONS)
    }

    // --- client ---

    private lateinit var server: MockWebServer
    private lateinit var client: BetaMapClient
    private var nowMs = 1_000_000L

    @Before fun setUp() {
        server = MockWebServer(); server.start()
        client = BetaMapClient(OkHttpClient()).also {
            it.url = server.url("/map").toString()
            it.clockMs = { nowMs }
        }
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun production_url_is_the_public_map_page_never_the_api() {
        val fresh = BetaMapClient(OkHttpClient())
        assertEquals("https://beta.by/map", fresh.url)
        assertEquals(4_500L, fresh.callTimeoutMs)
        assertEquals(4 * 60_000L, fresh.cacheTtlMs)
    }

    @Test fun request_asks_for_the_rsc_payload_and_names_the_app() = runTest {
        server.enqueue(MockResponse().setBody(fixture))
        assertEquals(8, client.stations().getOrThrow().stations.size)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/map", request.path)
        assertFalse(request.path!!.startsWith("/api/"))
        assertEquals("1", request.getHeader("RSC"))
        assertTrue(request.getHeader("User-Agent")!!.startsWith("BYDMate/"))
    }

    @Test fun server_error_is_a_failure() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        val result = client.stations()
        assertTrue(result.isFailure)
        assertEquals("HTTP 503", result.exceptionOrNull()?.message)
    }

    @Test fun second_call_within_ttl_is_served_from_memory() = runTest {
        server.enqueue(MockResponse().setBody(fixture))
        assertFalse(client.stations().getOrThrow().fromCache)
        nowMs += client.cacheTtlMs - 1
        val second = client.stations().getOrThrow()
        assertTrue(second.fromCache)
        assertEquals(8, second.stations.size)
        assertEquals(1, server.requestCount)
    }

    @Test fun expired_cache_downloads_again() = runTest {
        server.enqueue(MockResponse().setBody(fixture))
        server.enqueue(MockResponse().setBody(fixture))
        client.stations().getOrThrow()
        nowMs += client.cacheTtlMs
        assertFalse(client.stations().getOrThrow().fromCache)
        assertEquals(2, server.requestCount)
    }

    // A stale list is not live occupancy: when the refresh fails, the caller moves on to the
    // next source instead of getting old statuses.
    @Test fun expired_cache_is_not_served_when_the_refresh_fails() = runTest {
        server.enqueue(MockResponse().setBody(fixture))
        server.enqueue(MockResponse().setResponseCode(502))
        client.stations().getOrThrow()
        nowMs += client.cacheTtlMs + 1
        assertTrue(client.stations().isFailure)
    }

    @Test fun failure_is_not_cached() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setBody(fixture))
        assertTrue(client.stations().isFailure)
        assertEquals(8, client.stations().getOrThrow().stations.size)
        assertEquals(2, server.requestCount)
    }
}
