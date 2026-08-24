package com.bydmate.app.data.remote

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WebhookTelemetryClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: WebhookTelemetryClient

    @Before fun setUp() {
        server = MockWebServer(); server.start()
        client = WebhookTelemetryClient(OkHttpClient())
    }

    @After fun tearDown() { server.shutdown() }

    private fun telemetry(): JSONObject = JSONObject().apply {
        put("utc", 1_700_000_000L)
        put("soc", 73)
        put("power", 12.5)
    }

    @Test fun `posts telemetry json to the configured url`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        val payload = telemetry()

        val result = client.send(server.url("/tlm").toString(), null, payload)

        assertTrue(result.isSuccess)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/tlm", recorded.path)
        assertEquals("application/json; charset=utf-8", recorded.getHeader("Content-Type"))
        assertEquals(payload.toString(), recorded.body.readUtf8())
    }

    @Test fun `secret goes out as Authorization Bearer header`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = client.send(server.url("/tlm").toString(), "s3cret", telemetry())

        assertTrue(result.isSuccess)
        assertEquals("Bearer s3cret", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun `blank secret sends no Authorization header`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        client.send(server.url("/tlm").toString(), "   ", telemetry())

        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test fun `non-2xx response returns failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("upstream down"))

        val result = client.send(server.url("/tlm").toString(), null, telemetry())

        assertTrue(result.isFailure)
        assertEquals("HTTP 503", result.exceptionOrNull()?.message)
    }

    @Test fun `invalid url fails without a network call`() = runTest {
        val result = client.send("ftp://example.com/tlm", null, telemetry())

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertEquals("no request must reach the network", 0, server.requestCount)
    }

    @Test fun `plain http to a non-loopback host fails without a network call`() = runTest {
        // network_security_config.xml allows cleartext only on localhost/127.0.0.1;
        // any other http host would be silently blocked by the platform, so reject it up front.
        val result = client.send("http://192.168.1.10:8123/api/webhook/x", null, telemetry())

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertEquals("no request must reach the network", 0, server.requestCount)
    }

    @Test fun `garbage url fails without a network call`() = runTest {
        val result = client.send("not a url", null, telemetry())

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertEquals("no request must reach the network", 0, server.requestCount)
    }
}
