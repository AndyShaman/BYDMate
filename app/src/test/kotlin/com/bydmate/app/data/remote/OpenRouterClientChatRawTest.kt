package com.bydmate.app.data.remote

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenRouterClientChatRawTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OpenRouterClient

    @Before fun setUp() {
        server = MockWebServer(); server.start()
        client = OpenRouterClient(OkHttpClient())
        client.baseUrlForTest = server.url("/api/v1").toString().trimEnd('/')
    }

    @After fun tearDown() { server.shutdown() }

    private fun messages() = JSONArray().put(JSONObject().put("role", "user").put("content", "привет"))

    @Test fun success_returns_choice_message() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"choices":[{"message":{"role":"assistant","content":"Привет!"}}]}"""))
        val result = client.chatRaw("https://openrouter.ai/api/v1", "key", "test/model", messages(), null)
        assertEquals("Привет!", result.getOrThrow().getString("content"))
        val req = server.takeRequest()
        val body = JSONObject(req.body.readUtf8())
        assertEquals("test/model", body.getString("model"))
        assertEquals("Bearer key", req.getHeader("Authorization"))
        assertTrue(!body.has("tools"))
    }

    @Test fun tools_are_sent_when_present() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"choices":[{"message":{"role":"assistant","content":"ok"}}]}"""))
        val tools = JSONArray().put(JSONObject().put("type", "function"))
        client.chatRaw("https://openrouter.ai/api/v1", "key", "m", messages(), tools).getOrThrow()
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(1, body.getJSONArray("tools").length())
    }

    @Test fun http_error_returns_failure() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(client.chatRaw("https://openrouter.ai/api/v1", "key", "m", messages(), null).isFailure)
    }

    @Test fun malformed_body_returns_failure() = runTest {
        server.enqueue(MockResponse().setBody("not json"))
        assertTrue(client.chatRaw("https://openrouter.ai/api/v1", "key", "m", messages(), null).isFailure)
    }

    @Test
    fun `chatRaw hits custom base url`() = runTest {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"ok"}}]}"""))
        client.baseUrlForTest = null
        val base = server.url("/custom/v1").toString().trimEnd('/')
        val result = client.chatRaw(base, "k", "m", JSONArray(), null)
        assertTrue(result.isSuccess)
        assertEquals("/custom/v1/chat/completions", server.takeRequest().path)
    }

    @Test
    fun `http error surfaces as LlmHttpException with code`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("{}"))
        client.baseUrlForTest = null
        val result = client.chatRaw(server.url("/api/v1").toString().trimEnd('/'), "k", "m", JSONArray(), null)
        val e = result.exceptionOrNull()
        assertTrue(e is LlmHttpException)
        assertEquals(429, (e as LlmHttpException).code)
    }
}
