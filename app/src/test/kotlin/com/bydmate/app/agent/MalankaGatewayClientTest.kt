package com.bydmate.app.agent

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MalankaGatewayClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: MalankaGatewayClient

    // Trimmed from the real answers of 28.09: Malanka has no status, Evika and Battery Fly
    // report the whole station as AVAILABLE / FULLY_USED / UNAVAILABLE.
    private val malanka = """[
        {"id":"3f0b","name":"Ул. Академика Федорова, 5 (TZone / Vityaz)","address":"Минск, Ул. Академика Фёдорова, 5","latitude":53.883999,"longitude":27.426775,"hasWorkingHoursLimit":false}
    ]"""
    private val evika = """[
        {"id":"NDMw","name":"Минск, ул. Пулихова, 5","address":"Минск, ул. Пулихова, 5","latitude":53.89911,"longitude":27.575947,"status":"FULLY_USED"},
        {"id":"NDMx","name":"Без координат","address":"-","status":"AVAILABLE"}
    ]"""
    private val batteryFly = """[
        {"id":"MTU4","name":"БЦ \"Capital Palace\" - Минск","address":"г. Минск, ул. Интернациональная, 38","latitude":53.903926,"longitude":27.562358,"status":"AVAILABLE"},
        {"id":"MTU5","name":"АЗС","address":"г. Минск","latitude":53.95,"longitude":27.6,"status":"UNAVAILABLE"}
    ]"""

    private fun dispatch(answers: Map<String, MockResponse>) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
            answers[request.requestUrl!!.encodedPath] ?: MockResponse().setResponseCode(404)
    }

    @Before fun setUp() {
        server = MockWebServer(); server.start()
        client = MalankaGatewayClient(OkHttpClient()).also { it.baseUrl = server.url("/central-system/api/v1/").toString() }
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun production_base_url_and_timeout() {
        val fresh = MalankaGatewayClient(OkHttpClient())
        assertEquals("https://apigateway.malankabn.by/central-system/api/v1/", fresh.baseUrl)
        assertEquals(2_500L, fresh.callTimeoutMs)
    }

    @Test fun three_networks_are_merged_with_operator_and_status() = runTest {
        server.dispatcher = dispatch(mapOf(
            "/central-system/api/v1/locations/map" to MockResponse().setBody(malanka),
            "/central-system/api/v1/evika/locations/map" to MockResponse().setBody(evika),
            "/central-system/api/v1/battery-fly/locations/map" to MockResponse().setBody(batteryFly),
        ))
        val stations = client.stations().getOrThrow()
        assertEquals(4, stations.size)
        val tzone = stations.single { it.operator == "Malanka" }
        assertNull(tzone.status)
        assertNull(tzone.connectors)
        assertEquals("Минск, Ул. Академика Фёдорова, 5", tzone.address)
        assertEquals(StationStatus.BUSY, stations.single { it.operator == "Evika" }.status)
        val fly = stations.filter { it.operator == "Battery Fly" }
        assertEquals("БЦ \"Capital Palace\" - Минск", fly[0].name)
        assertEquals(StationStatus.FREE, fly[0].status)
        assertEquals(StationStatus.DOWN, fly[1].status)
        assertEquals(3, server.requestCount)
    }

    @Test fun every_request_names_the_app_and_asks_for_all_connector_types() = runTest {
        server.dispatcher = dispatch(emptyMap())
        client.stations()
        repeat(3) {
            val request = server.takeRequest()
            assertTrue(request.getHeader("User-Agent")!!.startsWith("BYDMate/"))
            assertEquals("", request.requestUrl!!.queryParameter("connectorTypes"))
        }
    }

    @Test fun one_network_down_still_answers_with_the_others() = runTest {
        server.dispatcher = dispatch(mapOf(
            "/central-system/api/v1/locations/map" to MockResponse().setResponseCode(502),
            "/central-system/api/v1/evika/locations/map" to MockResponse().setBody(evika),
            "/central-system/api/v1/battery-fly/locations/map" to MockResponse().setBody("not json"),
        ))
        assertEquals(listOf("Evika"), client.stations().getOrThrow().map { it.operator })
    }

    @Test fun all_networks_down_is_a_failure() = runTest {
        server.dispatcher = dispatch(emptyMap())
        assertTrue(client.stations().isFailure)
    }
}
