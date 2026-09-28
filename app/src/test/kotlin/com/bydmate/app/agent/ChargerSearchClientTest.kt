package com.bydmate.app.agent

import com.bydmate.app.data.charging.ChargeConnector
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.QueueDispatcher
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChargerSearchClientTest {

    private lateinit var server: MockWebServer
    private lateinit var beta: MockWebServer
    private lateinit var gateway: MockWebServer
    private lateinit var client: ChargerSearchClient

    @Before fun setUp() {
        server = MockWebServer(); server.start()
        // Nothing queued on the Belarus sources = they are down; chain tests queue answers.
        beta = MockWebServer().apply { dispatcher = failFast(); start() }
        gateway = MockWebServer().apply { dispatcher = failFast(); start() }
        client = ChargerSearchClient(OkHttpClient())
        client.endpoints = listOf(server.url("/api/interpreter").toString())
        client.beta.url = beta.url("/map").toString()
        client.gateway.baseUrl = gateway.url("/central-system/api/v1/").toString()
    }

    @After fun tearDown() {
        server.shutdown()
        beta.shutdown()
        gateway.shutdown()
    }

    private fun failFast() = QueueDispatcher().apply { setFailFast(MockResponse().setResponseCode(503)) }

    // Field 28.09, measured from the head unit: overpass.openstreetmap.fr answered 4 of 4 small
    // queries in 0.5-1.4 s, overpass-api.de gave a 504 after 7.5 s and a 200 after 17.9 s. The
    // maps.mail.ru mirror never worked there (Android 12 lacks its TLS root).
    @Test fun production_servers_are_the_french_mirror_then_overpass_api_de() {
        val fresh = ChargerSearchClient(OkHttpClient())
        assertEquals(
            listOf("https://overpass.openstreetmap.fr/api/interpreter", "https://overpass-api.de/api/interpreter"),
            fresh.endpoints)
        assertTrue(fresh.endpoints.none { it.contains("mail.ru") })
    }

    // One slow Overpass server must leave the second one its turn inside the ~10 s tool budget.
    @Test fun production_timeouts_are_4s_per_overpass_server_and_10s_in_total() {
        val fresh = ChargerSearchClient(OkHttpClient())
        assertEquals(4_000L, fresh.callTimeoutMs)
        assertEquals(10_000L, fresh.totalTimeoutMs)
    }

    // (a) node element parses with name from tags.
    @Test fun node_element_parses_with_tag_name() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"elements":[{"type":"node","lat":54.5,"lon":30.4,"tags":{"name":"ЭЗС Орша"}}]}"""))
        val result = client.search(54.5, 30.4, 5000)
        val chargers = result.getOrThrow()
        assertEquals(1, chargers.size)
        assertEquals("ЭЗС Орша", chargers[0].name)
        assertEquals(54.5, chargers[0].lat, 0.0001)
        assertEquals(30.4, chargers[0].lon, 0.0001)
    }

    // (b) way/relation element parses coordinates from "center".
    @Test fun way_element_parses_center_coordinates() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"elements":[{"type":"way","center":{"lat":55.1,"lon":31.2},"tags":{"name":"Стоянка"}}]}"""))
        val chargers = client.search(55.0, 31.0, 5000).getOrThrow()
        assertEquals(1, chargers.size)
        assertEquals(55.1, chargers[0].lat, 0.0001)
        assertEquals(31.2, chargers[0].lon, 0.0001)
    }

    // (c) element without coordinates (no lat/lon, no center) is skipped.
    @Test fun element_without_coordinates_is_skipped() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"elements":[{"type":"way","tags":{"name":"Без координат"}}]}"""))
        val chargers = client.search(54.5, 30.4, 5000).getOrThrow()
        assertTrue(chargers.isEmpty())
    }

    // (c2) malformed "center" present but missing lat/lon must be skipped, not leak NaN.
    @Test fun malformed_center_without_lat_lon_is_skipped() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"elements":[{"type":"way","center":{},"tags":{"name":"Битый center"}},""" +
                """{"type":"node","lat":54.5,"lon":30.4,"tags":{"name":"Живая"}}]}"""))
        val chargers = client.search(54.5, 30.4, 5000).getOrThrow()
        assertEquals(1, chargers.size)
        assertEquals("Живая", chargers[0].name)
    }

    // (d) name absent -> falls back to operator tag.
    @Test fun missing_name_falls_back_to_operator() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"elements":[{"type":"node","lat":54.5,"lon":30.4,"tags":{"operator":"Россети"}}]}"""))
        val chargers = client.search(54.5, 30.4, 5000).getOrThrow()
        assertEquals("Россети", chargers[0].name)
    }

    // (e) name and operator both absent -> default Russian label.
    @Test fun missing_name_and_operator_falls_back_to_default() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"elements":[{"type":"node","lat":54.5,"lon":30.4,"tags":{}}]}"""))
        val chargers = client.search(54.5, 30.4, 5000).getOrThrow()
        assertEquals("Зарядная станция", chargers[0].name)
    }

    // (f) HTTP 500 -> Result.failure.
    @Test fun http_error_is_failure() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(client.search(54.5, 30.4, 5000).isFailure)
    }

    // (g) POST body carries the amenity filter and the requested radius (URL-encoded).
    @Test fun post_body_contains_amenity_and_radius() = runTest {
        server.enqueue(MockResponse().setBody("""{"elements":[]}"""))
        client.search(54.5, 30.4, 12000)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("amenity%22%3D%22charging_station%22"))
        assertTrue(body.contains("around%3A12000"))
    }

    // (h) POST body must be form-URL-encoded: no raw quotes, %22 present, starts with "data=".
    @Test fun overpass_query_body_is_form_url_encoded() = runTest {
        server.enqueue(MockResponse().setBody("""{"elements":[]}"""))
        client.endpoints = listOf(server.url("/api/interpreter").toString())
        client.search(55.75, 37.62, 3000)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.startsWith("data="))
        assertFalse(body.contains("\""))
        assertTrue(body.contains("%22"))
    }

    // Field log 28.09: overpass-api.de answers 406 to OkHttp's default User-Agent.
    @Test fun request_names_the_app_so_overpass_does_not_answer_406() = runTest {
        server.dispatcher = overpassUserAgentDispatcher(
            """{"elements":[{"type":"node","lat":54.5,"lon":30.4,"tags":{"name":"ЭЗС Орша"}}]}""")
        assertEquals(1, client.search(54.5, 30.4, 5000).getOrThrow().size)
        assertTrue(server.takeRequest().getHeader("User-Agent")!!.startsWith("BYDMate/"))
    }

    @Test fun overpass_remark_error_falls_over_to_the_second_server() = runTest {
        val second = MockWebServer(); second.start()
        try {
            server.enqueue(MockResponse().setBody("""{"remark":"runtime error: Query timed out","elements":[]}"""))
            second.enqueue(MockResponse().setBody(
                """{"elements":[{"type":"node","lat":54.5,"lon":30.4,"tags":{"name":"ЭЗС Орша"}}]}"""))
            client.endpoints = listOf(server.url("/a").toString(), second.url("/b").toString())
            assertEquals("ЭЗС Орша", client.search(54.5, 30.4, 5000).getOrThrow().single().name)
        } finally {
            second.shutdown()
        }
    }

    @Test fun overpass_operator_and_address_tags_are_kept() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"elements":[{"type":"node","lat":54.5,"lon":30.4,"tags":{"name":"ЭЗС","operator":"Малахит",""" +
                """"addr:city":"Орша","addr:street":"улица Мира","addr:housenumber":"4"}}]}"""))
        val s = client.search(54.5, 30.4, 5000).getOrThrow().single()
        assertEquals("Малахит", s.operator)
        assertEquals("Орша, улица Мира, 4", s.address)
        assertEquals(null, s.connectors)
    }

    // --- source chain: BETA -> Malanka gateway -> Overpass inside Belarus, Overpass elsewhere ---

    private val betaFixture = requireNotNull(javaClass.classLoader?.getResource("beta/map-rsc-national-library.txt")).readText()
    private val osmOne = """{"elements":[{"type":"node","lat":54.5,"lon":30.4,"tags":{"name":"ЭЗС"}}]}"""
    private val evika = """[{"id":"NDMw","name":"Минск, ул. Пулихова, 5","address":"Минск","latitude":53.89911,"longitude":27.575947,"status":"FULLY_USED"}]"""

    // National Library, Minsk: the fixture's landmark.
    private val libLat = 53.9313
    private val libLon = 27.6461

    @Test fun inside_belarus_the_beta_map_answers_first() = runTest {
        beta.dispatcher = QueueDispatcher()
        beta.enqueue(MockResponse().setBody(betaFixture))
        val found = client.find(libLat, libLon, 30_000, ChargeConnector.GBT).getOrThrow()
        assertEquals(ChargerSource.BETA, found.source)
        assertEquals(8, found.stations.size)
        assertEquals(0, gateway.requestCount)
        assertEquals(0, server.requestCount)
        val request = beta.takeRequest()
        assertEquals("/map", request.path)
        assertTrue(request.path!!.startsWith("/api/").not())
    }

    @Test fun the_radius_cuts_the_country_wide_list() = runTest {
        beta.dispatcher = QueueDispatcher()
        beta.enqueue(MockResponse().setBody(betaFixture))
        val found = client.find(libLat, libLon, 1_000, ChargeConnector.GBT).getOrThrow()
        assertEquals(7, found.stations.size)
        assertTrue(found.stations.none { it.name.startsWith("ул. Навуковая") })
    }

    @Test fun beta_server_error_falls_back_to_the_gateway() = runTest {
        // The three networks are asked in parallel: answer by path, not by arrival order.
        gateway.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path!!.contains("/evika/")) MockResponse().setBody(evika)
                else MockResponse().setResponseCode(502)
        }
        val found = client.find(libLat, libLon, 30_000, ChargeConnector.GBT).getOrThrow()
        assertEquals(ChargerSource.GATEWAY, found.source)
        assertEquals(listOf("Evika"), found.stations.map { it.operator })
        assertEquals(1, beta.requestCount)
        assertEquals(0, server.requestCount)
    }

    @Test fun gateway_down_too_falls_back_to_overpass() = runTest {
        server.enqueue(MockResponse().setBody(osmOne))
        val found = client.find(libLat, libLon, 30_000, ChargeConnector.GBT).getOrThrow()
        assertEquals(ChargerSource.OSM, found.source)
        assertEquals(1, beta.requestCount)
        assertEquals(3, gateway.requestCount)
    }

    @Test fun outside_belarus_the_beta_map_is_never_asked() = runTest {
        server.enqueue(MockResponse().setBody(osmOne))
        val found = client.find(55.7558, 37.6173, 30_000, ChargeConnector.GBT).getOrThrow()
        assertEquals(ChargerSource.OSM, found.source)
        assertEquals(0, beta.requestCount)
        assertEquals(0, gateway.requestCount)
    }

    // Vilnius lies inside the Belarus bounding box: BETA has nothing near it, so the chain goes on.
    @Test fun beta_with_nothing_in_the_radius_falls_through() = runTest {
        beta.dispatcher = QueueDispatcher()
        beta.enqueue(MockResponse().setBody(betaFixture))
        server.enqueue(MockResponse().setBody(osmOne))
        val found = client.find(54.6872, 25.2797, 10_000, ChargeConnector.GBT).getOrThrow()
        assertEquals(ChargerSource.OSM, found.source)
        assertEquals(1, beta.requestCount)
    }

    // BETA answered "nothing here" and Overpass is down: that is still an answer, not an outage.
    @Test fun an_empty_beta_answer_survives_an_overpass_outage() = runTest {
        beta.dispatcher = QueueDispatcher()
        beta.enqueue(MockResponse().setBody(betaFixture))
        server.enqueue(MockResponse().setResponseCode(504))
        val found = client.find(52.1, 23.7, 5_000, ChargeConnector.GBT).getOrThrow()
        assertEquals(ChargerSource.BETA, found.source)
        assertTrue(found.stations.isEmpty())
    }

    @Test fun every_source_down_is_a_failure() = runTest {
        server.enqueue(MockResponse().setResponseCode(504))
        assertTrue(client.find(libLat, libLon, 30_000, ChargeConnector.GBT).isFailure)
    }

    @Test fun a_hanging_source_cannot_outlast_the_total_budget() = runBlocking {
        client.totalTimeoutMs = 500L
        beta.dispatcher = QueueDispatcher()
        beta.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val t0 = System.nanoTime()
        val result = client.find(libLat, libLon, 30_000, ChargeConnector.GBT)
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue(result.isFailure)
        assertTrue("took ${elapsedMs}ms", elapsedMs < 3_000)
    }

    // A follow-up question and the route request right after it reuse the downloaded map.
    @Test fun a_second_search_within_minutes_reuses_the_beta_map() = runTest {
        beta.dispatcher = QueueDispatcher()
        beta.enqueue(MockResponse().setBody(betaFixture))
        client.find(libLat, libLon, 30_000, ChargeConnector.GBT).getOrThrow()
        val again = client.find(libLat, libLon, 5_000, ChargeConnector.CCS2).getOrThrow()
        assertEquals(ChargerSource.BETA, again.source)
        assertEquals(1, beta.requestCount)
    }
}

/** Overpass as seen on 28.09: 406 for an `okhttp/x.y.z` User-Agent, [body] for any other. */
internal fun overpassUserAgentDispatcher(body: String) = object : Dispatcher() {
    override fun dispatch(request: RecordedRequest): MockResponse =
        if (request.getHeader("User-Agent").orEmpty().startsWith("okhttp")) MockResponse().setResponseCode(406)
        else MockResponse().setBody(body)
}
