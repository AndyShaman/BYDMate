package com.bydmate.app.voice.online

import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.voice.TtsGender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * MiniMax speech-2.8-turbo TTS, reachable through three interchangeable transports selected by
 * the `minimax_tts_provider` setting: MiniMax's own API ("official"), or the same model proxied
 * through fal.ai / Replicate for accounts without direct MiniMax access. fal and Replicate both
 * hand back a URL to the rendered audio rather than the bytes themselves and share a
 * fetch-and-decode step; only WAV downloads are supported there (mp3 decoding is out of scope).
 */
class MiniMaxTtsBackend(
    private val http: OkHttpClient,
    private val settingsRepository: SettingsRepository,
    private val officialBaseUrl: String = "https://api.minimax.io",
    private val falBaseUrl: String = "https://fal.run",
    private val replicateBaseUrl: String = "https://api.replicate.com",
) : OnlineTtsBackend {

    override val id: String = "minimax"

    override suspend fun configured(): Boolean =
        settingsRepository.getString(SettingsRepository.KEY_MINIMAX_TTS_KEY, "").isNotBlank()

    override suspend fun synthesize(text: String, gender: TtsGender): TtsPcm =
        withContext(Dispatchers.IO) {
            val key = settingsRepository.getString(SettingsRepository.KEY_MINIMAX_TTS_KEY, "")
            if (key.isBlank()) throw IOException("MiniMax TTS: connection not configured")
            val voice = if (gender == TtsGender.MALE) MALE_VOICE else FEMALE_VOICE
            val provider = settingsRepository.getString(SettingsRepository.KEY_MINIMAX_TTS_PROVIDER, PROVIDER_OFFICIAL)
            when (provider) {
                PROVIDER_FAL -> synthesizeFal(key, text, voice)
                PROVIDER_REPLICATE -> synthesizeReplicate(key, text, voice)
                else -> synthesizeOfficial(key, text, voice)
            }
        }

    private fun synthesizeOfficial(key: String, text: String, voice: String): TtsPcm {
        val payload = JSONObject().apply {
            put("model", MODEL)
            put("text", text)
            put("voice_setting", JSONObject().put("voice_id", voice))
            put(
                "audio_setting",
                JSONObject().apply {
                    put("format", "pcm")
                    put("sample_rate", OFFICIAL_SAMPLE_RATE)
                    put("channel", 1)
                },
            )
        }
        val request = Request.Builder()
            .url("$officialBaseUrl/v1/t2a_v2")
            .addHeader("Authorization", "Bearer $key")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        return http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("MiniMax TTS HTTP ${resp.code}")
            val body = JSONObject(resp.body?.string() ?: throw IOException("MiniMax TTS: empty body"))
            val baseResp = body.getJSONObject("base_resp")
            val statusCode = baseResp.getInt("status_code")
            if (statusCode != 0) {
                throw IOException("MiniMax TTS error $statusCode: ${baseResp.optString("status_msg")}")
            }
            val bytes = hexToBytes(body.getJSONObject("data").getString("audio"))
            WavCodec.decodePcm16(bytes, OFFICIAL_SAMPLE_RATE)
        }
    }

    private fun synthesizeFal(key: String, text: String, voice: String): TtsPcm {
        val payload = JSONObject().apply {
            put("text", text)
            put("voice_setting", JSONObject().put("voice_id", voice))
            put("output_format", "wav")
        }
        val request = Request.Builder()
            .url("$falBaseUrl/fal-ai/minimax/speech-2.8-turbo")
            .addHeader("Authorization", "Key $key")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        val audioUrl = postForAudioUrl(request) { JSONObject(it).getJSONObject("audio").getString("url") }
        return downloadWav(audioUrl)
    }

    private fun synthesizeReplicate(key: String, text: String, voice: String): TtsPcm {
        val payload = JSONObject().apply {
            put(
                "input",
                JSONObject().apply {
                    put("text", text)
                    put("voice_id", voice)
                },
            )
        }
        val request = Request.Builder()
            .url("$replicateBaseUrl/v1/models/minimax/speech-2.8-turbo/predictions")
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Prefer", "wait")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        val audioUrl = postForAudioUrl(request) { JSONObject(it).getString("output") }
        return downloadWav(audioUrl)
    }

    private fun postForAudioUrl(request: Request, audioUrlOf: (String) -> String): String =
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("MiniMax TTS HTTP ${resp.code}")
            val body = resp.body?.string() ?: throw IOException("MiniMax TTS: empty body")
            audioUrlOf(body)
        }

    private fun downloadWav(url: String): TtsPcm {
        val request = Request.Builder().url(url).get().build()
        return http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("MiniMax TTS HTTP ${resp.code}")
            val bytes = resp.body?.bytes() ?: throw IOException("MiniMax TTS: empty body")
            if (bytes.size < 4 || String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF") {
                throw IOException("MiniMax TTS: unsupported format (expected WAV)")
            }
            WavCodec.decodeWav(bytes)
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        if (hex.length % 2 != 0) {
            throw IOException("MiniMax TTS: audio hex has odd length (${hex.length})")
        }
        return ByteArray(hex.length / 2) { i ->
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi == -1 || lo == -1) {
                throw IOException("MiniMax TTS: audio hex has invalid character at index ${i * 2}")
            }
            ((hi shl 4) + lo).toByte()
        }
    }

    companion object {
        private val JSON_MEDIA = "application/json".toMediaType()
        private const val OFFICIAL_SAMPLE_RATE = 24_000

        private const val PROVIDER_OFFICIAL = "official"
        private const val PROVIDER_FAL = "fal"
        private const val PROVIDER_REPLICATE = "replicate"

        // MiniMax speech-2.8-turbo voice/model ids -- verify against provider docs on first on-car smoke.
        const val MALE_VOICE = "Russian_ReliableMan"
        const val FEMALE_VOICE = "Russian_BrightHeroine"
        const val MODEL = "speech-2.8-turbo"
    }
}
