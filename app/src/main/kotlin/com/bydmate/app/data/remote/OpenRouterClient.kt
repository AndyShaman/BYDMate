package com.bydmate.app.data.remote

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** HTTP-level failure from an OpenAI-compatible endpoint; [code] lets callers tell auth/limit/server errors apart. */
class LlmHttpException(val code: Int) : IOException("LLM HTTP $code")

@Singleton
class OpenRouterClient @Inject constructor(
    private val httpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "OpenRouterClient"
        private const val BASE_URL = "https://openrouter.ai/api/v1"
        private val JSON_MEDIA = "application/json".toMediaType()
    }

    /**
     * Fetches a model list from any OpenAI-compatible endpoint (not just OpenRouter).
     * Parses {"data":[{"id":"..."},...]} and returns model ids.
     * Throws on network failure so the caller can map the exception to a user-readable message.
     */
    suspend fun fetchModelsFromUrl(baseUrl: String, apiKey: String): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = baseUrl.trimEnd('/') + "/models"
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw LlmHttpException(response.code)
                    val body = response.body?.string().takeUnless { it.isNullOrBlank() }
                        ?: throw IOException("empty body")
                    val data = JSONObject(body).optJSONArray("data")
                        ?: return@use emptyList<String>()
                    (0 until data.length()).mapNotNull { i ->
                        data.getJSONObject(i).optString("id").takeUnless { it.isBlank() }
                    }
                }
            }
        }

    suspend fun fetchModels(apiKey: String): List<OpenRouterModel> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$BASE_URL/models")
                .addHeader("Authorization", "Bearer $apiKey")
                .build()
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext emptyList()

            if (!response.isSuccessful) {
                Log.w(TAG, "fetchModels HTTP ${response.code}")
                return@withContext emptyList()
            }

            val json = JSONObject(body)
            val data = json.optJSONArray("data") ?: return@withContext emptyList()
            val models = mutableListOf<OpenRouterModel>()

            for (i in 0 until data.length()) {
                val m = data.getJSONObject(i)
                val id = m.optString("id", "")
                val name = m.optString("name", id)
                val pricing = m.optJSONObject("pricing")
                val promptPrice = pricing?.optString("prompt", "0")?.toDoubleOrNull() ?: 0.0
                // pricing.prompt is $/token, convert to $/1M tokens
                val pricePerMillion = promptPrice * 1_000_000
                models.add(OpenRouterModel(id = id, name = name, pricingPrompt = pricePerMillion))
            }

            // Sort: free first, then by price
            models.sortWith(compareBy({ it.pricingPrompt }, { it.name }))
            models
        } catch (e: Exception) {
            Log.e(TAG, "fetchModels failed: ${e.message}")
            emptyList()
        }
    }

    suspend fun chat(
        apiKey: String,
        modelId: String,
        systemPrompt: String,
        userMessage: String
    ): String? = withContext(Dispatchers.IO) {
        try {
            val messages = JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemPrompt))
                put(JSONObject().put("role", "user").put("content", userMessage))
            }
            val payload = JSONObject().apply {
                put("model", modelId)
                put("messages", messages)
                put("temperature", 0.7)
                put("max_tokens", 1024)
            }

            val request = Request.Builder()
                .url("$BASE_URL/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("HTTP-Referer", "https://github.com/AndyShaman/BYDMate")
                .addHeader("X-Title", "BYDMate")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string()

            if (!response.isSuccessful) {
                Log.w(TAG, "chat HTTP ${response.code}: $body")
                return@withContext null
            }

            val json = JSONObject(body ?: return@withContext null)
            val choices = json.optJSONArray("choices") ?: return@withContext null
            if (choices.length() == 0) return@withContext null

            choices.getJSONObject(0)
                .optJSONObject("message")
                ?.optString("content")
        } catch (e: Exception) {
            Log.e(TAG, "chat failed: ${e.message}")
            null
        }
    }

    /** Test seam: overrides the OpenRouter base URL. Production leaves it null. */
    internal var baseUrlForTest: String? = null

    /**
     * Raw chat-completions call for the voice agent: full message history plus optional
     * tool schemas, against any OpenAI-compatible [baseUrl] (OpenRouter, z.ai, or a custom
     * endpoint). Returns choices[0].message (content and/or tool_calls). Failures surface
     * as Result.failure — unlike [chat], the agent needs to voice the error.
     */
    suspend fun chatRaw(
        baseUrl: String,
        apiKey: String,
        modelId: String,
        messages: JSONArray,
        tools: JSONArray?,
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject().apply {
                put("model", modelId)
                put("messages", messages)
                if (tools != null && tools.length() > 0) put("tools", tools)
                put("temperature", 0.2)
                put("max_tokens", 1024)
            }
            val base = baseUrlForTest ?: baseUrl
            val request = Request.Builder()
                .url("$base/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("HTTP-Referer", "https://github.com/AndyShaman/BYDMate")
                .addHeader("X-Title", "BYDMate")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw LlmHttpException(resp.code)
                val bodyStr = resp.body?.string().takeUnless { it.isNullOrBlank() }
                    ?: throw IOException("LLM: empty body")
                JSONObject(bodyStr).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            }
        }
    }

    /** Dedicated client for SSE: the shared 15s readTimeout is a between-token timeout and
     *  would cut long generations. Voice-tuned bounds: a between-token stall of 20s means the
     *  car's network degraded (OpenRouter sends keep-alive comments well within that), and
     *  45s is the worst-case hard cap for one voice round — a frozen orb for minutes is worse
     *  than an honest "не получилось". */
    private val streamClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    /** Streaming twin of chatRaw: same request shape plus stream=true, same result shape
     *  (choices[0].message equivalent), with content deltas forwarded to [onDelta] as they
     *  arrive. After the first tool_calls fragment content is no longer forwarded (a tool
     *  round must not be spoken), but it is still accumulated into the returned message. */
    suspend fun chatStream(
        baseUrl: String,
        apiKey: String,
        modelId: String,
        messages: JSONArray,
        tools: JSONArray?,
        onDelta: (String) -> Unit,
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject().apply {
                put("model", modelId)
                put("messages", messages)
                if (tools != null && tools.length() > 0) put("tools", tools)
                put("temperature", 0.2)
                put("max_tokens", 1024)
                put("stream", true)
            }
            val base = baseUrlForTest ?: baseUrl
            val request = Request.Builder()
                .url("$base/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("HTTP-Referer", "https://github.com/AndyShaman/BYDMate")
                .addHeader("X-Title", "BYDMate")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()
            val call = streamClient.newCall(request)
            // A blocked call.execute()/readUtf8Line() never observes coroutine cancellation by itself.
            // A sibling watcher resumes on cancellation (on another thread) and cancels the OkHttp call,
            // unblocking this one. On normal completion the watcher is cancelled and its call.cancel()
            // hits an already-finished call, which is a no-op.
            val watcher = launch {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) throw LlmHttpException(resp.code)
                    val body = resp.body ?: throw IOException("LLM: empty body")
                    val isSse = body.contentType()?.subtype.orEmpty().contains("event-stream")
                    if (isSse) {
                        readSse(body.source(), onDelta)
                    } else {
                        // Provider ignored stream=true and returned a whole completion.
                        val bodyStr = body.string().takeUnless { it.isNullOrBlank() }
                            ?: throw IOException("LLM: empty body")
                        val message = JSONObject(bodyStr)
                            .getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                        if (!message.isNull("content")) {
                            message.optString("content").takeIf { it.isNotEmpty() }?.let(onDelta)
                        }
                        message
                    }
                }
            } finally {
                watcher.cancel()
            }
        }
    }

    private fun readSse(source: BufferedSource, onDelta: (String) -> Unit): JSONObject {
        val content = StringBuilder()
        val toolCalls = LinkedHashMap<Int, JSONObject>()
        var forward = true
        while (true) {
            val line = source.readUtf8Line() ?: break
            if (line.isBlank() || line.startsWith(":")) continue
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data == "[DONE]") break
            val chunkJson = runCatching { JSONObject(data) }.getOrNull() ?: continue
            chunkJson.optJSONObject("error")?.let { err ->
                throw IOException("LLM stream error: ${err.optString("message", "unknown")}")
            }
            val choices = chunkJson.optJSONArray("choices") ?: continue
            if (choices.length() == 0) continue
            val delta = choices.getJSONObject(0).optJSONObject("delta") ?: continue
            delta.optJSONArray("tool_calls")?.let { fragments ->
                forward = false
                for (i in 0 until fragments.length()) {
                    val fragment = fragments.getJSONObject(i)
                    val idx = fragment.optInt("index", i)
                    val slot = toolCalls.getOrPut(idx) {
                        JSONObject()
                            .put("type", "function")
                            .put("function", JSONObject().put("name", "").put("arguments", ""))
                    }
                    fragment.optString("id").takeIf { it.isNotBlank() }?.let { slot.put("id", it) }
                    fragment.optJSONObject("function")?.let { fn ->
                        val slotFn = slot.getJSONObject("function")
                        fn.optString("name").takeIf { it.isNotBlank() }?.let { slotFn.put("name", it) }
                        if (fn.has("arguments")) {
                            slotFn.put("arguments", slotFn.getString("arguments") + fn.optString("arguments"))
                        }
                    }
                }
            }
            if (!delta.isNull("content")) {
                val piece = delta.optString("content")
                if (piece.isNotEmpty()) {
                    content.append(piece)
                    if (forward) onDelta(piece)
                }
            }
        }
        val message = JSONObject()
        if (content.isNotEmpty()) message.put("content", content.toString())
        else message.put("content", JSONObject.NULL)
        if (toolCalls.isNotEmpty()) {
            val arr = JSONArray()
            toolCalls.values.forEach { arr.put(it) }
            message.put("tool_calls", arr)
        }
        return message
    }
}
