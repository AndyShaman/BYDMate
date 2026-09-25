package com.bydmate.app.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class SettlementSearchClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: SettlementSearchClient

    @Before fun setUp() {
        server = MockWebServer(); server.start()
        client = SettlementSearchClient(OkHttpClient())
        client.endpoints = listOf(server.url("/api/interpreter").toString())
    }

    @After fun tearDown() { server.shutdown() }

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
        val found = client.search(54.03, 27.97, 15_000, 50_000).getOrThrow()
        assertEquals(listOf("Воложин", "Семково"), found.map { it.name })
        assertEquals("town", found[0].place)
        assertEquals(54.087, found[0].lat, 0.0001)
        assertEquals(26.523, found[0].lon, 0.0001)
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
        assertTrue(client.search(54.0, 27.0, 15_000, 50_000).getOrThrow().isEmpty())
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
            assertEquals(2, client.search(54.0, 27.0, 15_000, 50_000).getOrThrow().size)
        } finally {
            second.shutdown()
        }
    }

    @Test fun timeout_fails_instead_of_hanging() = runTest {
        client = SettlementSearchClient(OkHttpClient.Builder().readTimeout(200, TimeUnit.MILLISECONDS).build())
        client.endpoints = listOf(server.url("/api/interpreter").toString())
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
            assertEquals(2, client.search(54.0, 27.0, 15_000, 50_000).getOrThrow().size)
        } finally {
            second.shutdown()
        }
    }

    @Test fun remark_error_on_every_endpoint_fails() = runTest {
        server.enqueue(MockResponse().setBody(remarkError))
        assertTrue(client.search(54.0, 27.0, 15_000, 50_000).isFailure)
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
            assertEquals(2, client.search(54.0, 27.0, 15_000, 50_000).getOrThrow().size)
        } finally {
            second.shutdown()
        }
    }

    // A cancelled voice turn must not wait out the call timeout nor try the next server.
    @Test fun cancellation_drops_the_in_flight_call_and_skips_the_next_endpoint() = runBlocking {
        val okHttp = OkHttpClient()
        client = SettlementSearchClient(okHttp)
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
        } finally {
            second.shutdown()
        }
    }
}
