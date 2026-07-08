package com.bydmate.app.agent

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ZaiSearchClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: ZaiSearchClient

    @Before fun setUp() {
        server = MockWebServer(); server.start()
        client = ZaiSearchClient(OkHttpClient())
    }

    @After fun tearDown() { server.shutdown() }

    @Test
    fun `search posts search-prime payload and maps results to exa shape`() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"search_result":[{"title":"T","link":"https://a","content":"C"}]}"""
        ))
        client.baseUrlForTest = server.url("/paas/v4").toString().trimEnd('/')
        val out = client.search("zk", "погода минск").getOrThrow()
        val req = server.takeRequest()
        assertEquals("/paas/v4/web_search", req.path)
        assertEquals("Bearer zk", req.getHeader("Authorization"))
        val sent = JSONObject(req.body.readUtf8())
        assertEquals("search-prime", sent.getString("search_engine"))
        assertEquals("погода минск", sent.getString("search_query"))
        val results = JSONObject(out).getJSONArray("results")
        assertEquals("T", results.getJSONObject(0).getString("title"))
        assertEquals("https://a", results.getJSONObject(0).getString("url"))
        assertEquals("C", results.getJSONObject(0).getString("text"))
    }

    @Test
    fun `http error is failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        client.baseUrlForTest = server.url("/paas/v4").toString().trimEnd('/')
        assertTrue(client.search("zk", "q").isFailure)
    }
}
