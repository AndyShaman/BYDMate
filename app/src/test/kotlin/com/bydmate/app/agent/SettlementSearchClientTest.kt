package com.bydmate.app.agent

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
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
}
