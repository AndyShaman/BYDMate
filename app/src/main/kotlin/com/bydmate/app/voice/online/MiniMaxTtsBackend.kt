package com.bydmate.app.voice.online

import android.util.Log
import com.bydmate.app.data.remote.HttpPrewarm
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.voice.TtsGender
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import org.json.JSONObject
import java.io.IOException

/**
 * MiniMax speech-2.8-turbo TTS, reachable through three interchangeable transports selected by
 * the `minimax_tts_provider` setting: MiniMax's own API ("official"), or the same model proxied
 * through fal.ai / Replicate for accounts without direct MiniMax access. fal and Replicate both
 * hand back a URL to the rendered audio rather than the bytes themselves and share a
 * fetch-and-decode step; only WAV downloads are supported there (mp3 decoding is out of scope).
 */
@Suppress("TooManyFunctions") // three transports plus the official SSE stream and their small helpers
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

    override suspend fun prewarm() {
        if (!configured()) return
        val base = when (settingsRepository.getString(SettingsRepository.KEY_MINIMAX_TTS_PROVIDER, PROVIDER_OFFICIAL)) {
            PROVIDER_FAL -> falBaseUrl
            PROVIDER_REPLICATE -> replicateBaseUrl
            else -> officialBaseUrl
        }
        HttpPrewarm.fire(http, base)
    }

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

    override suspend fun streamSampleRate(): Int? =
        when (settingsRepository.getString(SettingsRepository.KEY_MINIMAX_TTS_PROVIDER, PROVIDER_OFFICIAL)) {
            PROVIDER_FAL, PROVIDER_REPLICATE -> null
            else -> OFFICIAL_SAMPLE_RATE
        }

    /** Official transport with `stream: true`: the response is SSE, one `data: {json}` event per
     *  hex PCM chunk, so the first chunk can play while the rest is still being synthesized. */
    override suspend fun synthesizeStream(text: String, gender: TtsGender, onChunk: (FloatArray) -> Unit) {
        if (streamSampleRate() == null) {
            onChunk(synthesize(text, gender).samples)
            return
        }
        withContext(Dispatchers.IO) {
            val key = settingsRepository.getString(SettingsRepository.KEY_MINIMAX_TTS_KEY, "")
            if (key.isBlank()) throw IOException("MiniMax TTS: connection not configured")
            val voice = if (gender == TtsGender.MALE) MALE_VOICE else FEMALE_VOICE
            val call = http.newCall(officialRequest(key, officialPayload(text, voice, stream = true)))
            callWithCancelWatcher(call) { call.execute().use { resp -> readAudioStream(streamSource(resp), onChunk) } }
        }
    }

    /** A blocked read never observes coroutine cancellation by itself: the sibling watcher
     *  cancels [call] on barge-in or a router timeout, unblocking [block] (same pattern for the
     *  streaming and the whole-sentence official transport). UNDISPATCHED: the watcher enters its
     *  try before [block] starts blocking, so a cancellation that lands before the watcher would
     *  have been dispatched still runs its finally. */
    private suspend fun <T> callWithCancelWatcher(call: Call, block: () -> T): T = coroutineScope {
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            block()
        } catch (e: IOException) {
            // call.cancel() from the watcher's finally surfaces here as a plain IOException; if
            // that's because this coroutine was cancelled, ensureActive() rethrows the real
            // CancellationException so callers see a cancellation, not an ordinary synth failure.
            currentCoroutineContext().ensureActive()
            throw e
        } finally {
            watcher.cancel()
        }
    }

    private fun streamSource(resp: Response): BufferedSource {
        if (!resp.isSuccessful) throw IOException("MiniMax TTS HTTP ${resp.code}")
        return resp.body?.source() ?: throw IOException("MiniMax TTS: empty body")
    }

    private fun readAudioStream(source: BufferedSource, onChunk: (FloatArray) -> Unit) {
        val pcm = Pcm16Chunker(onChunk)
        val plain = StringBuilder()
        var done = false
        var eof = false
        while (!done && !eof) {
            val line = source.readUtf8Line()
            when {
                line == null -> eof = true
                line.startsWith("data:") -> done = onStreamEvent(JSONObject(line.removePrefix("data:").trim()), pcm)
                else -> plain.append(line.trim())
            }
        }
        if (pcm.carried > 0) Log.w(TAG, "stream ended with ${pcm.carried} unpaired PCM byte(s), dropped")
        if (done && pcm.delivered > 0) return
        // Only status 2 confirms the sentence is complete: a connection closed after some audio
        // is a cut-off sentence, a mid-stream failure (never cached, rest of the reply silent).
        if (pcm.delivered > 0) {
            Log.w(TAG, "stream ended without status 2 after ${pcm.delivered} chunk(s)")
            throw IOException("MiniMax TTS: stream ended before synthesis completed")
        }
        // HTTP 200 with a plain JSON body instead of SSE: an API error.
        runCatching { JSONObject(plain.toString()) }.getOrNull()?.optJSONObject("base_resp")?.let(::checkBaseResp)
        throw IOException("MiniMax TTS: stream ended without audio")
    }

    /** Handles one SSE event; true once synthesis completed (status 2). When
     *  exclude_aggregated_audio is not honoured that final event repeats the WHOLE sentence,
     *  so its audio is never played. */
    private fun onStreamEvent(event: JSONObject, pcm: Pcm16Chunker): Boolean {
        event.optJSONObject("base_resp")?.let(::checkBaseResp)
        val data = event.optJSONObject("data") ?: return false
        if (data.optInt("status") == STREAM_STATUS_DONE) return true
        data.optString("audio").takeIf { it.isNotEmpty() }?.let { pcm.feed(hexToBytes(it)) }
        return false
    }

    /** Decodes PCM16 chunk by chunk; a sample straddling two events keeps its first byte here
     *  until the next one arrives. */
    private class Pcm16Chunker(private val onChunk: (FloatArray) -> Unit) {
        private var carry = ByteArray(0)
        var delivered = 0
            private set
        val carried: Int get() = carry.size

        fun feed(bytes: ByteArray) {
            val all = carry + bytes
            val even = all.size - all.size % 2
            carry = all.copyOfRange(even, all.size)
            if (even == 0) return
            onChunk(WavCodec.decodePcm16(all.copyOf(even), OFFICIAL_SAMPLE_RATE).samples)
            delivered++
        }
    }

    private suspend fun synthesizeOfficial(key: String, text: String, voice: String): TtsPcm {
        val call = http.newCall(officialRequest(key, officialPayload(text, voice, stream = false)))
        return callWithCancelWatcher(call) {
            call.execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("MiniMax TTS HTTP ${resp.code}")
                val body = JSONObject(resp.body?.string() ?: throw IOException("MiniMax TTS: empty body"))
                checkBaseResp(body.getJSONObject("base_resp"))
                val bytes = hexToBytes(body.getJSONObject("data").getString("audio"))
                WavCodec.decodePcm16(bytes, OFFICIAL_SAMPLE_RATE)
            }
        }
    }

    private fun officialPayload(text: String, voice: String, stream: Boolean) = JSONObject().apply {
        put("model", MODEL)
        put("text", text)
        put("voice_setting", JSONObject().put("voice_id", voice))
        // Tells the model the text is Russian instead of letting it guess per sentence (short
        // replies are where a guess goes wrong). Non-Cyrillic text, and Cyrillic with letters
        // Russian lacks (Belarusian/Ukrainian), keeps the old request untouched, so replies in
        // other interface languages sound as before.
        if (text.any { it in '\u0400'..'\u04FF' } && text.none { it in NON_RUSSIAN_CYRILLIC }) {
            put("language_boost", "Russian")
        }
        put(
            "audio_setting",
            JSONObject().apply {
                put("format", "pcm")
                put("sample_rate", OFFICIAL_SAMPLE_RATE)
                put("channel", 1)
            },
        )
        if (stream) {
            put("stream", true)
            put("stream_options", JSONObject().put("exclude_aggregated_audio", true))
        }
    }

    private fun officialRequest(key: String, payload: JSONObject): Request = Request.Builder()
        .url("$officialBaseUrl/v1/t2a_v2")
        .addHeader("Authorization", "Bearer $key")
        .post(payload.toString().toRequestBody(JSON_MEDIA))
        .build()

    private fun checkBaseResp(baseResp: JSONObject) {
        val statusCode = baseResp.getInt("status_code")
        if (statusCode != 0) {
            throw IOException("MiniMax TTS error $statusCode: ${baseResp.optString("status_msg")}")
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
        private const val TAG = "MiniMaxTts"
        private val JSON_MEDIA = "application/json".toMediaType()
        private const val OFFICIAL_SAMPLE_RATE = 24_000
        private const val STREAM_STATUS_DONE = 2
        private const val NON_RUSSIAN_CYRILLIC = "іўїєґІЎЇЄҐ"

        private const val PROVIDER_OFFICIAL = "official"
        private const val PROVIDER_FAL = "fal"
        private const val PROVIDER_REPLICATE = "replicate"

        // MiniMax speech-2.8-turbo voice/model ids -- verify against provider docs on first on-car smoke.
        const val MALE_VOICE = "Russian_ReliableMan"
        const val FEMALE_VOICE = "Russian_BrightHeroine"
        const val MODEL = "speech-2.8-turbo"
    }
}
