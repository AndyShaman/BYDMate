package com.bydmate.app.agent

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExaSearchClientTest {

    private fun clientFor(status: Int = 200, body: String? = null): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                Response.Builder()
                    .request(req)
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(status).message(if (status == 200) "OK" else "Error")
                    .body((body ?: "").toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

    // (a) parses fixture into compact JSON, truncating text to ~1500 chars.
    @Test fun search_parses_fixture_and_truncates_text() = runTest {
        val longText = "a".repeat(2000)
        val fixture = """
            {"results": [
              {"title": "Result A", "url": "https://a.example", "text": "$longText"},
              {"title": "Result B", "url": "https://b.example", "text": "short"}
            ]}
        """.trimIndent()
        val client = ExaSearchClient(clientFor(body = fixture))
        val result = client.search("key123", "query text")
        assertTrue(result.isSuccess)
        val json = JSONObject(result.getOrThrow())
        val results = json.getJSONArray("results")
        assertEquals(2, results.length())
        val r0 = results.getJSONObject(0)
        assertEquals("Result A", r0.getString("title"))
        assertEquals("https://a.example", r0.getString("url"))
        assertEquals(1500, r0.getString("text").length)
        assertEquals("short", results.getJSONObject(1).getString("text"))
    }

    // (b) HTTP 401 -> Result.failure.
    @Test fun search_http_401_is_failure() = runTest {
        val client = ExaSearchClient(clientFor(status = 401, body = "unauthorized"))
        val result = client.search("bad-key", "query")
        assertTrue(result.isFailure)
    }
}
