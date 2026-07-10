package com.bydmate.app.agent

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChargerSearchClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: ChargerSearchClient

    @Before fun setUp() {
        server = MockWebServer(); server.start()
        client = ChargerSearchClient(OkHttpClient())
        client.endpoints = listOf(server.url("/api/interpreter").toString())
    }

    @After fun tearDown() { server.shutdown() }

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
}
