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
}
