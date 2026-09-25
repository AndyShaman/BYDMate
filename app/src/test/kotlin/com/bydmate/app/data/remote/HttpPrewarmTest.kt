package com.bydmate.app.data.remote

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class HttpPrewarmTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }

    @After fun tearDown() { server.shutdown() }

    @Test
    fun `prewarm sends one HEAD and the next request reuses its connection`() {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setBody("{}"))
        val http = OkHttpClient()
        // A client derived with newBuilder() (like the LLM stream client) shares the pool.
        val derived = http.newBuilder().callTimeout(45, TimeUnit.SECONDS).build()

        HttpPrewarm.fire(http, server.url("/api/v1").toString())
        val head = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("HEAD", head.method)
        assertEquals("/api/v1", head.path)

        // The prewarm response is closed on the dispatcher thread: wait until the connection is
        // back in the pool, otherwise the real request would open a second one.
        val deadline = System.currentTimeMillis() + 5_000
        while (http.connectionPool.idleConnectionCount() < 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertEquals(1, http.connectionPool.idleConnectionCount())
        val post = Request.Builder().url(server.url("/api/v1/chat/completions"))
            .post("{}".toRequestBody("application/json".toMediaType())).build()
        derived.newCall(post).execute().use { assertEquals(200, it.code) }
        val real = server.takeRequest(5, TimeUnit.SECONDS)!!
        // sequenceNumber counts requests on one connection: 1 = second request on the warmed one.
        assertEquals(1, real.sequenceNumber)
    }

    @Test
    fun `unparseable url sends nothing`() {
        HttpPrewarm.fire(OkHttpClient(), "not a url")
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    }
}
