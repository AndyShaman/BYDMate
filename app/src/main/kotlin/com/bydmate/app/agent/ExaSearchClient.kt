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
 * Exa (api.exa.ai) client for the web_search tool, BYOK (bring your own API key).
 */
@Singleton
class ExaSearchClient @Inject constructor(private val http: OkHttpClient) {

    /** Up to [MAX_RESULTS] results, text truncated to keep the tool JSON compact for the LLM. */
    suspend fun search(apiKey: String, query: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject().apply {
                put("query", query)
                put("numResults", MAX_RESULTS)
                put("contents", JSONObject().put("text", true))
            }
            val request = Request.Builder()
                .url(SEARCH_URL)
                .addHeader("x-api-key", apiKey)
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val body = resp.body?.string().takeUnless { it.isNullOrBlank() }
                    ?: throw IOException("empty body")
                val results = JSONObject(body).optJSONArray("results") ?: JSONArray()
                resultsJson(results, urlKey = "url", textKey = "text")
            }
        }
    }

    companion object {
        private const val SEARCH_URL = "https://api.exa.ai/search"
        internal const val MAX_RESULTS = 5
        private const val TEXT_LIMIT = 1500

        /** The web_search tool's JSON: up to [MAX_RESULTS] {title, url, text} entries, text cut to
         *  [TEXT_LIMIT]; [urlKey] and [textKey] name the engine's own fields. Shared with ZaiSearchClient. */
        internal fun resultsJson(results: JSONArray, urlKey: String, textKey: String): String {
            val out = JSONArray()
            for (i in 0 until minOf(results.length(), MAX_RESULTS)) {
                val r = results.getJSONObject(i)
                out.put(JSONObject().apply {
                    put("title", r.optString("title"))
                    put("url", r.optString(urlKey))
                    put("text", r.optString(textKey).take(TEXT_LIMIT))
                })
            }
            return JSONObject().put("results", out).toString()
        }
    }
}
