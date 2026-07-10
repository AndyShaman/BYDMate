package com.bydmate.app.voice.online

import com.bydmate.app.agent.LlmConnectionResolver
import com.bydmate.app.voice.TtsGender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OpenRouter TTS (POST {baseUrl}/audio/speech), BYOK via the user's OpenRouter connection.
 * Authenticated with the user's OpenRouter key.
 */
class OpenRouterTtsBackend(
    override val id: String,
    private val model: String,
    private val maleVoice: String,
    private val femaleVoice: String,
    private val http: OkHttpClient,
    private val connections: LlmConnectionResolver,
) : OnlineTtsBackend {

    // Dedicated client with a hard wall-clock timeout -- withContext(IO) + coroutine timeout
    // only interrupts suspending calls; OkHttp's blocking execute() needs its own cut-off.
    // 15s: cloud TTS returns the whole rendered sentence in one body, so a long sentence
    // takes well over the old 10s on slow synthesis without being an error.
    private val ttsHttp = http.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()

    override suspend fun configured(): Boolean =
        connections.get(LlmConnectionResolver.ID_OPENROUTER) != null

    override suspend fun synthesize(text: String, gender: TtsGender): TtsPcm =
        withContext(Dispatchers.IO) {
            val connection = connections.get(LlmConnectionResolver.ID_OPENROUTER)
                ?: throw IOException("OpenRouter TTS: connection not configured")
            val voice = if (gender == TtsGender.MALE) maleVoice else femaleVoice
            val payload = JSONObject().apply {
                put("model", model)
                put("input", text)
                put("voice", voice)
                put("response_format", "pcm")
            }
            val request = Request.Builder()
                .url("${connection.baseUrl}/audio/speech")
                .addHeader("Authorization", "Bearer ${connection.apiKey}")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()
            ttsHttp.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errBody = runCatching { resp.body?.string() }.getOrNull()?.take(200)?.trim().orEmpty()
                    throw IOException(
                        if (errBody.isEmpty()) "OpenRouter TTS HTTP ${resp.code}"
                        else "OpenRouter TTS HTTP ${resp.code}: $errBody"
                    )
                }
                val bytes = resp.body?.bytes()
                if (bytes == null || bytes.size < MIN_AUDIO_BYTES) {
                    throw IOException("OpenRouter TTS: empty audio (${bytes?.size ?: 0} bytes)")
                }
                val rate = RATE_REGEX.find(resp.header("Content-Type") ?: "")
                    ?.groupValues?.get(1)?.toIntOrNull() ?: DEFAULT_PCM_RATE
                WavCodec.decodePcm16(bytes, rate)
            }
        }

    companion object {
        private val JSON_MEDIA = "application/json".toMediaType()
        private val RATE_REGEX = Regex("""\brate\s*=\s*(\d+)""", RegexOption.IGNORE_CASE)
        private const val MIN_AUDIO_BYTES = 256
        private const val DEFAULT_PCM_RATE = 24_000

        // Preview/beta OpenRouter model slug -- verify against openrouter.ai/models on first on-car smoke.
        const val GEMINI_MODEL = "google/gemini-3.1-flash-tts-preview"
    }
}
