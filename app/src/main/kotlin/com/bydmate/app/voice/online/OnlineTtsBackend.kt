package com.bydmate.app.voice.online

import com.bydmate.app.voice.TtsGender

/** Result of one online synthesis: mono PCM float samples and their sample rate. */
data class TtsPcm(val samples: FloatArray, val sampleRate: Int)

/** A cloud TTS provider (Gemini / MiniMax). [TtsRouter] synthesizes through this; on any
 *  failure or timeout the rest of the reply stays silent -- the voice is never swapped to
 *  the offline engine. */
interface OnlineTtsBackend {
    /** Stable id matched against the `tts_source` preference ("gemini" | "minimax"). */
    val id: String

    /** Synthesizes [text] respecting [gender]. Throws on any failure (network, API error, etc). */
    suspend fun synthesize(text: String, gender: TtsGender): TtsPcm

    /** True when this backend's API key/config is present and usable. */
    suspend fun configured(): Boolean

    /** Everything besides the text that decides the audio for [gender] (transport, model, voice
     *  id, output format), or null when this backend cannot describe it -- its phrases are then
     *  cached in memory only, never persisted across restarts. */
    suspend fun voiceIdentity(gender: TtsGender): String? = null

    /** Fire-and-forget: opens the connection to the synthesis host ahead of the first sentence. */
    suspend fun prewarm() {}

    /** Sample rate of [synthesizeStream]'s chunks, or null when this backend cannot stream with
     *  the current settings -- the router then synthesizes whole sentences. */
    suspend fun streamSampleRate(): Int? = null

    /** Synthesizes [text] delivering mono PCM chunks to [onChunk] as they arrive (never an empty
     *  one). Throws on any failure, like [synthesize]. Default: the whole sentence as one chunk. */
    suspend fun synthesizeStream(text: String, gender: TtsGender, onChunk: (FloatArray) -> Unit) {
        onChunk(synthesize(text, gender).samples)
    }
}
