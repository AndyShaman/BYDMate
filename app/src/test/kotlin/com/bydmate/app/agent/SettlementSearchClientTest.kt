package com.bydmate.app.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import com.bydmate.app.agent.SettlementSearchClient.Surroundings
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.QueueDispatcher
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class SettlementSearchClientTest {

    private lateinit var server: MockWebServer
    private lateinit var nominatim: MockWebServer
    private lateinit var client: SettlementSearchClient

    @Before fun setUp() {
        server = MockWebServer(); server.start()
        // Nothing queued = Nominatim is down, so an Overpass failure fails the search fast.
        nominatim = MockWebServer()
        nominatim.dispatcher = QueueDispatcher().apply { setFailFast(MockResponse().setResponseCode(503)) }
        nominatim.start()
        client = newClient(OkHttpClient())
    }

    @After fun tearDown() {
        server.shutdown()
        nominatim.shutdown()
    }

    /** Both servers on MockWebServer: a test must never reach the real Nominatim. */
    private fun newClient(http: OkHttpClient) = SettlementSearchClient(http).also {
        it.endpoints = listOf(server.url("/api/interpreter").toString())
        it.nominatimUrl = nominatim.url("/reverse").toString()
    }

    private fun Result<Surroundings>.nearby() = (getOrThrow() as Surroundings.Nearby).settlements
    private fun Result<Surroundings>.address() = getOrThrow() as Surroundings.Address

    private val minsk = requireNotNull(javaClass.classLoader?.getResource("nominatim/reverse-minsk.json")).readText()

    // Review: timing tests below override the timeouts, so a change to the production defaults
    // (8 s Overpass, 3 s Nominatim, 10 s in total - the budget a voice turn actually waits)
    // would pass them unnoticed. Pin the defaults on a client nobody has touched.
    @Test fun production_timeout_defaults_are_8s_overpass_3s_nominatim_10s_total() {
        val fresh = SettlementSearchClient(OkHttpClient())
        assertEquals(8_000L, fresh.callTimeoutMs)
        assertEquals(3_000L, fresh.nominatimCallTimeoutMs)
        assertEquals(10_000L, fresh.totalTimeoutMs)
    }

    // Field 28.09: the maps.mail.ru mirror never worked on the head unit (Android 12 lacks its
    // TLS root, and it answered in 9-12 s); the backup is Nominatim reverse geocoding now.
    @Test fun production_servers_are_overpass_api_de_then_nominatim() {
        val fresh = SettlementSearchClient(OkHttpClient())
        assertEquals(listOf("https://overpass-api.de/api/interpreter"), fresh.endpoints)
        assertEquals("https://nominatim.openstreetmap.org/reverse", fresh.nominatimUrl)
    }

    // Trimmed Overpass reply near Воложин: name:ru wins over the Belarusian name, a node
    // without any name and one without coordinates are skipped.
    private val fixture = """
        {"version":0.6,"elements":[
          {"type":"node","id":1,"lat":54.0870,"lon":26.5230,"tags":{"place":"town","name":"Валожын","name:ru":"Воложин"}},
          {"type":"node","id":2,"lat":54.0300,"lon":27.9600,"tags":{"place":"village","name":"Семково"}},
          {"type":"node","id":3,"lat":54.0100,"lon":27.9800,"tags":{"place":"hamlet"}},
          {"type":"node","id":4,"tags":{"place":"hamlet","name":"Без координат"}}
        ]}
    """.trimIndent()

    @Test fun parses_settlements_and_prefers_russian_name() = runTest {
        server.enqueue(MockResponse().setBody(fixture))
        val found = client.search(54.03, 27.97, 15_000, 50_000).nearby()
        assertEquals(listOf("Воложин", "Семково"), found.map { it.name })
        assertEquals("town", found[0].place)
        assertEquals(54.087, found[0].lat, 0.0001)
        assertEquals(26.523, found[0].lon, 0.0001)
    }

    // lastEndpoint backs the where_am_i diagnostic log (no place names, just which server
    // answered), so it must reflect the endpoint that actually returned the result.
    @Test fun last_endpoint_reflects_the_endpoint_that_answered() = runTest {
        server.enqueue(MockResponse().setBody(fixture))
        client.search(54.03, 27.97, 15_000, 50_000)
        assertEquals(client.endpoints.single(), client.lastEndpoint)
    }

    @Test fun query_asks_for_place_nodes_in_both_radii() = runTest {
        server.enqueue(MockResponse().setBody("""{"elements":[]}"""))
        client.search(54.03, 27.97, 15_000, 50_000)
        val body = java.net.URLDecoder.decode(server.takeRequest().body.readUtf8(), "UTF-8")
        assertTrue(body, body.contains("village|hamlet|suburb|isolated_dwelling"))
        assertTrue(body, body.contains("around:15000,54.03,27.97"))
        assertTrue(body, body.contains("city|town"))
        assertTrue(body, body.contains("around:50000,54.03,27.97"))
    }

    @Test fun empty_elements_is_an_empty_list() = runTest {
        server.enqueue(MockResponse().setBody("""{"elements":[]}"""))
        assertTrue(client.search(54.0, 27.0, 15_000, 50_000).nearby().isEmpty())
        assertEquals(0, nominatim.requestCount)
    }

    @Test fun http_error_on_every_endpoint_fails() = runTest {
        server.enqueue(MockResponse().setResponseCode(504))
        assertTrue(client.search(54.0, 27.0, 15_000, 50_000).isFailure)
    }

    @Test fun first_endpoint_failure_falls_over_to_the_second() = runTest {
        val second = MockWebServer(); second.start()
        try {
            server.enqueue(MockResponse().setResponseCode(429))
            second.enqueue(MockResponse().setBody(fixture))
            client.endpoints = listOf(server.url("/a").toString(), second.url("/b").toString())
            assertEquals(2, client.search(54.0, 27.0, 15_000, 50_000).nearby().size)
        } finally {
            second.shutdown()
        }
    }

    @Test fun timeout_fails_instead_of_hanging() = runTest {
        client = newClient(OkHttpClient.Builder().readTimeout(200, TimeUnit.MILLISECONDS).build())
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        assertTrue(client.search(54.0, 27.0, 15_000, 50_000).isFailure)
    }

    // Review of 30f1f01d: Overpass reports runtime errors inside a 200 via "remark", with an
    // empty "elements"; that used to become "nothing around".
    private val remarkError = """{"version":0.6,"elements":[],""" +
        """"remark":"runtime error: Query timed out in \"query\" at line 1 after 10 seconds."}"""

    @Test fun remark_error_falls_back_to_the_second_endpoint() = runTest {
        val second = MockWebServer(); second.start()
        try {
            server.enqueue(MockResponse().setBody(remarkError))
            second.enqueue(MockResponse().setBody(fixture))
            client.endpoints = listOf(server.url("/a").toString(), second.url("/b").toString())
            assertEquals(2, client.search(54.0, 27.0, 15_000, 50_000).nearby().size)
        } finally {
            second.shutdown()
        }
    }

    @Test fun remark_error_on_every_endpoint_fails() = runTest {
        server.enqueue(MockResponse().setBody(remarkError))
        assertTrue(client.search(54.0, 27.0, 15_000, 50_000).isFailure)
    }

    // Field log 28.09: overpass-api.de answers 406 to OkHttp's default User-Agent.
    @Test fun request_names_the_app_so_overpass_does_not_answer_406() = runTest {
        server.dispatcher = overpassUserAgentDispatcher(fixture)
        assertEquals(2, client.search(54.03, 27.97, 15_000, 50_000).nearby().size)
        assertTrue(server.takeRequest().getHeader("User-Agent")!!.startsWith("BYDMate/"))
    }

    @Test fun missing_elements_is_a_failure_not_an_empty_list() = runTest {
        server.enqueue(MockResponse().setBody("""{"version":0.6}"""))
        assertTrue(client.search(54.0, 27.0, 15_000, 50_000).isFailure)
    }

    // A body dripping a byte every 100 ms never trips the read timeout; only the overall
    // deadline stops it (the fixture alone would take ~45 s).
    @Test fun overall_deadline_stops_a_dripping_body() = runBlocking {
        client.callTimeoutMs = 60_000L
        client.totalTimeoutMs = 500L
        server.enqueue(MockResponse().setBody(fixture).throttleBody(1, 100, TimeUnit.MILLISECONDS))
        val t0 = System.nanoTime()
        val result = client.search(54.0, 27.0, 15_000, 50_000)
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue(result.isFailure)
        assertTrue("took ${elapsedMs}ms", elapsedMs < 3_000)
    }

    @Test fun per_call_timeout_leaves_time_for_the_second_endpoint() = runBlocking {
        val second = MockWebServer(); second.start()
        try {
            client.callTimeoutMs = 300L
            client.totalTimeoutMs = 10_000L
            server.enqueue(MockResponse().setBody(fixture).throttleBody(1, 100, TimeUnit.MILLISECONDS))
            second.enqueue(MockResponse().setBody(fixture))
            client.endpoints = listOf(server.url("/a").toString(), second.url("/b").toString())
            assertEquals(2, client.search(54.0, 27.0, 15_000, 50_000).nearby().size)
        } finally {
            second.shutdown()
        }
    }

    // A cancelled voice turn must not wait out the call timeout nor try the next server.
    @Test fun cancellation_drops_the_in_flight_call_and_skips_the_next_endpoint() = runBlocking {
        val okHttp = OkHttpClient()
        client = newClient(okHttp)
        client.callTimeoutMs = 60_000L
        client.totalTimeoutMs = 60_000L
        val second = MockWebServer(); second.start()
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            second.enqueue(MockResponse().setBody(fixture))
            client.endpoints = listOf(server.url("/a").toString(), second.url("/b").toString())
            val job = launch(Dispatchers.IO) { client.search(54.0, 27.0, 15_000, 50_000) }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
            val t0 = System.nanoTime()
            job.cancelAndJoin()
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000
            assertTrue("cancel took ${elapsedMs}ms", elapsedMs < 2_000)
            val deadline = System.currentTimeMillis() + 2_000
            while (okHttp.dispatcher.runningCallsCount() > 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            assertEquals(0, okHttp.dispatcher.runningCallsCount())
            assertEquals(0, second.requestCount)
            assertEquals(0, nominatim.requestCount)
        } finally {
            second.shutdown()
        }
    }

    // --- Overpass down (field 28.09: 504 under load): Nominatim reverse geocoding answers ---

    @Test fun overpass_504_falls_back_to_the_nominatim_address() = runTest {
        server.enqueue(MockResponse().setResponseCode(504))
        nominatim.enqueue(MockResponse().setBody(minsk))
        val address = client.search(53.8946, 27.5474, 15_000, 50_000).address()
        assertEquals("Минск", address.settlement)
        assertEquals("city", address.place)
        assertEquals("проспект Независимости", address.road)
        assertEquals("Ленинский район", address.district)
        assertNull(address.region)
        assertEquals("Беларусь", address.country)
        assertEquals(client.nominatimUrl, client.lastEndpoint)
    }

    @Test fun an_overpass_answer_never_asks_nominatim() = runTest {
        server.enqueue(MockResponse().setBody(fixture))
        assertEquals(2, client.search(54.03, 27.97, 15_000, 50_000).nearby().size)
        assertEquals(0, nominatim.requestCount)
    }

    @Test fun both_servers_down_is_a_failure() = runTest {
        server.enqueue(MockResponse().setResponseCode(504))
        val result = client.search(54.0, 27.0, 15_000, 50_000)
        assertTrue(result.isFailure)
        assertEquals(1, nominatim.requestCount)
    }

    // Nominatim says "nothing here" (open sea) inside a 200.
    @Test fun nominatim_error_answer_is_a_failure() = runTest {
        server.enqueue(MockResponse().setResponseCode(504))
        nominatim.enqueue(MockResponse().setBody("""{"error":"Unable to geocode"}"""))
        assertTrue(client.search(54.0, 27.0, 15_000, 50_000).isFailure)
    }

    // The server's own text can echo the request: it never reaches the error that gets logged.
    @Test fun nominatim_error_text_is_not_carried_into_the_failure() = runTest {
        server.enqueue(MockResponse().setResponseCode(504))
        nominatim.enqueue(MockResponse().setBody("""{"error":"Unable to geocode 54.0,27.0"}"""))
        val message = client.search(54.0, 27.0, 15_000, 50_000).exceptionOrNull()?.message.orEmpty()
        assertTrue(message, message.contains("nominatim error answer"))
        assertTrue(message, !message.contains("54.0"))
    }

    // Nominatim usage policy: an identifying User-Agent; names in Russian for the model.
    @Test fun nominatim_request_names_the_app_and_asks_for_russian_names() = runTest {
        server.enqueue(MockResponse().setResponseCode(504))
        nominatim.enqueue(MockResponse().setBody(minsk))
        client.search(53.8946, 27.5474, 15_000, 50_000).address()
        val request = nominatim.takeRequest()
        assertEquals("GET", request.method)
        assertTrue(request.getHeader("User-Agent")!!.startsWith("BYDMate/"))
        val url = request.requestUrl!!
        assertEquals("ru", url.queryParameter("accept-language"))
        assertEquals("jsonv2", url.queryParameter("format"))
        assertEquals("1", url.queryParameter("addressdetails"))
        assertEquals("17", url.queryParameter("zoom"))
        assertEquals(53.8946, url.queryParameter("lat")!!.toDouble(), 0.000001)
        assertEquals(27.5474, url.queryParameter("lon")!!.toDouble(), 0.000001)
    }

    // Nominatim usage policy: at most one request per second. A second where_am_i inside that
    // second waits its turn instead of failing.
    @Test fun a_second_nominatim_request_within_a_second_waits_its_turn() = runBlocking {
        val arrivals = mutableListOf<Long>()
        nominatim.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(arrivals) { arrivals += System.nanoTime() }
                return MockResponse().setBody(minsk)
            }
        }
        server.enqueue(MockResponse().setResponseCode(504))
        server.enqueue(MockResponse().setResponseCode(504))
        val t0 = System.nanoTime()
        assertEquals("Минск", client.search(53.8946, 27.5474, 15_000, 50_000).address().settlement)
        assertEquals("Минск", client.search(53.8946, 27.5474, 15_000, 50_000).address().settlement)
        val second = synchronized(arrivals) { arrivals.toList() }.also { assertEquals(2, it.size) }[1]
        val afterMs = (second - t0) / 1_000_000
        assertTrue("second Nominatim request ${afterMs}ms after the first search began", afterMs >= 1_000)
    }

    // An Overpass call that hangs out its own timeout still leaves Nominatim time in the budget.
    @Test fun overpass_hanging_out_its_call_timeout_still_leaves_nominatim_its_turn() = runBlocking {
        client.callTimeoutMs = 300L
        client.totalTimeoutMs = 5_000L
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        nominatim.enqueue(MockResponse().setBody(minsk))
        assertEquals("Минск", client.search(53.8946, 27.5474, 15_000, 50_000).address().settlement)
    }
}
