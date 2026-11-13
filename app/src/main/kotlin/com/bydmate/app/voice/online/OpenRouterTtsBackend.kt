package com.bydmate.app.voice.online

import com.bydmate.app.agent.LlmConnectionResolver
import com.bydmate.app.data.remote.HttpPrewarm
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

    // The LLM prewarm covers this host only when the agent also runs on OpenRouter.
    override suspend fun prewarm() {
        connections.get(LlmConnectionResolver.ID_OPENROUTER)?.let { HttpPrewarm.fire(ttsHttp, it.baseUrl) }
    }

    override suspend fun voiceIdentity(gender: TtsGender): String =
        "$model|${if (gender == TtsGender.MALE) maleVoice else femaleVoice}|pcm"

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
                // "pcm" normally comes back headerless, but the provider may wrap it in a WAV
                // container; a RIFF header played as samples is a click at the wrong rate.
                if (isWav(bytes)) return@use WavCodec.decodeWav(bytes)
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

        // Successor of gemini-3.1-flash-tts-preview, which Google retires on 2026-11-17 (#423).
        // Same endpoint and voices; listed at openrouter.ai/api/v1/models?output_modalities=speech.
        const val GEMINI_MODEL = "google/gemini-3.8-flash-tts"

        private fun isWav(bytes: ByteArray): Boolean =
            bytes.size >= 12 &&
                String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE"
    }
}
