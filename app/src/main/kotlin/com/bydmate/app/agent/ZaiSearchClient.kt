package com.bydmate.app.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * z.ai Web Search API (POST {base}/web_search, engine search-prime) used as the native
 * search fallback when Exa is not configured. Output mirrors ExaSearchClient's JSON
 * shape so the LLM sees one format regardless of the engine.
 */
@Singleton
class ZaiSearchClient @Inject constructor(private val http: OkHttpClient) {

    /** Test seam: overrides the z.ai base URL. Production leaves it null. */
    internal var baseUrlForTest: String? = null

    suspend fun search(apiKey: String, query: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject()
                .put("search_engine", "search-prime")
                .put("search_query", query)
                .put("count", MAX_RESULTS)
            val base = baseUrlForTest ?: LlmConnectionResolver.ZAI_BASE_URL
            val request = Request.Builder()
                .url("$base/web_search")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("z.ai HTTP ${resp.code}")
                val body = resp.body?.string().takeUnless { it.isNullOrBlank() }
                    ?: throw IOException("z.ai: empty body")
                val results = JSONObject(body).optJSONArray("search_result") ?: JSONArray()
                val out = JSONArray()
                for (i in 0 until minOf(results.length(), MAX_RESULTS)) {
                    val r = results.getJSONObject(i)
                    out.put(JSONObject().apply {
                        put("title", r.optString("title"))
                        put("url", r.optString("link"))
                        put("text", r.optString("content").take(TEXT_LIMIT))
                    })
                }
                JSONObject().put("results", out).toString()
            }
        }
    }

    companion object {
        private const val MAX_RESULTS = 5
        private const val TEXT_LIMIT = 1500
        private val JSON_MEDIA = "application/json".toMediaType()
    }
}
