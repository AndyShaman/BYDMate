package com.bydmate.app.voice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Streaming recognition events.
 *  - [Partial]: in-progress hypothesis, emitted every read so the caller can stability-count it and
 *    fire a confident command early (before end-of-phrase silence).
 *  - [Final]: Vosk's VAD declared the utterance complete (the speaker paused), or the capture cap
 *    was reached and the buffer was flushed.
 */
sealed interface AsrEvent {
    data class Partial(val text: String) : AsrEvent
    data class Final(val text: String) : AsrEvent
}

interface AsrEngine {
    fun isModelReady(lang: VoiceLang): Boolean

    /**
     * Consume PCM16 frames and emit a stream of recognition events. Endpointing is delegated to
     * Vosk's VAD (`acceptWaveForm == true` → [AsrEvent.Final]), NOT a caller-side silence timer —
     * so the stream never ends before the speaker has begun talking (the old early-cutoff bug).
     * Non-empty partials are emitted on every read (no dedup) so the caller can detect a stable
     * hypothesis. When the PCM flow ends (capture cap) without a VAD endpoint, a final flush is
     * emitted. Cold flow — collecting it drives capture; cancelling the collection stops the mic.
     */
    fun recognize(pcm: Flow<ShortArray>, lang: VoiceLang, vocabularyJson: String): Flow<AsrEvent>
}

class VoskAsrEngine(
    private val modelManager: VoiceModelManager,
) : AsrEngine {

    override fun isModelReady(lang: VoiceLang): Boolean = modelManager.isReady(lang)

    override fun recognize(pcm: Flow<ShortArray>, lang: VoiceLang, vocabularyJson: String): Flow<AsrEvent> = flow {
        val model = Model(modelManager.modelPath(lang))
        try {
            val recognizer = Recognizer(model, 16000.0f, vocabularyJson)
            try {
                pcm.collect { frame ->
                    val bytes = shortArrayToBytes(frame)
                    if (recognizer.acceptWaveForm(bytes, bytes.size)) {
                        // VAD endpoint: the speaker paused → this segment is final.
                        emit(AsrEvent.Final(JSONObject(recognizer.result).optString("text", "").lowercase().trim()))
                    } else {
                        val partial = JSONObject(recognizer.partialResult).optString("partial", "").lowercase().trim()
                        if (partial.isNotEmpty()) emit(AsrEvent.Partial(partial))
                    }
                }
                // Capture ended (hard cap) before any VAD endpoint — flush whatever is buffered.
                emit(AsrEvent.Final(JSONObject(recognizer.finalResult).optString("text", "").lowercase().trim()))
            } finally {
                recognizer.close()
            }
        } finally {
            model.close()
        }
    }

    private fun shortArrayToBytes(s: ShortArray): ByteArray {
        val out = ByteArray(s.size * 2)
        for (i in s.indices) {
            out[i * 2] = (s[i].toInt() and 0xff).toByte()
            out[i * 2 + 1] = ((s[i].toInt() shr 8) and 0xff).toByte()
        }
        return out
    }
}
