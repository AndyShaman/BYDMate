package com.bydmate.app.data.remote

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OpenRouterClient @Inject constructor(
    private val httpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "OpenRouterClient"
        private const val BASE_URL = "https://openrouter.ai/api/v1"
        private val JSON_MEDIA = "application/json".toMediaType()
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
}
