package com.bydmate.app.voice

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Isolates all sherpa-onnx JNI recognizer access behind primitive-only signatures so unit
 *  tests never load com.k2fsa.sherpa.onnx.* (its companion init calls System.loadLibrary and
 *  crashes the JVM, same reason SherpaTtsEngine isolates OfflineTts). */
internal interface RecognizerHandle {
    /** Runs one CTC decode pass over a full utterance and returns the transcript ("" if none). */
    fun decode(samples: FloatArray): String
    fun close()
}

/** Isolates all sherpa-onnx JNI VAD access behind primitive-only signatures. */
internal interface VadHandle {
    fun acceptWaveform(samples: FloatArray)
    fun isSpeechDetected(): Boolean
    fun empty(): Boolean
    /** Oldest queued speech segment's samples; caller must pop() after consuming it. */
    fun front(): FloatArray
    fun pop()
    fun close()
}

private class RealRecognizerHandle(modelManager: GigaAmModelManager) : RecognizerHandle {
    private val recognizer = OfflineRecognizer(
        config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = GigaAmAsrEngine.SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(model = modelManager.modelPath()),
                tokens = modelManager.tokensPath(),
                numThreads = 4,
                provider = "cpu",
            ),
            decodingMethod = "greedy_search",
        ),
    )

    override fun decode(samples: FloatArray): String {
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, GigaAmAsrEngine.SAMPLE_RATE)
            recognizer.decode(stream)
            recognizer.getResult(stream).text
        } finally {
            stream.release()
        }
    }

    override fun close() = recognizer.release()
}

private class RealVadHandle(modelManager: GigaAmModelManager) : VadHandle {
    // Trade-off: minSilenceDuration=0.8s finalizes an utterance ~0.55s later than the sherpa-onnx
    // default (0.25s), a deliberate cost to stop mid-sentence splits (field defect APK 335).
    private val vad = Vad(
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = modelManager.vadPath(),
                threshold = GigaAmAsrEngine.VAD_THRESHOLD,
                minSilenceDuration = GigaAmAsrEngine.VAD_MIN_SILENCE_SEC,
                minSpeechDuration = GigaAmAsrEngine.VAD_MIN_SPEECH_SEC,
                maxSpeechDuration = GigaAmAsrEngine.VAD_MAX_SPEECH_SEC,
            ),
            sampleRate = GigaAmAsrEngine.SAMPLE_RATE,
        ),
    )

    override fun acceptWaveform(samples: FloatArray) = vad.acceptWaveform(samples)
    override fun isSpeechDetected(): Boolean = vad.isSpeechDetected()
    override fun empty(): Boolean = vad.empty()
    override fun front(): FloatArray = vad.front().samples
    override fun pop() = vad.pop()
    override fun close() = vad.release()
}

/** GigaAM v3 Russian nemo-CTC recognizer segmented by a silero VAD, for the continuous voice
 *  session. The recognizer is cached across sessions (see cachedRecognizer below) since
 *  constructing it loads the model from disk; the VAD stays per-collection, created and
 *  released in transcribe()'s finally block, so neither cancellation nor a second collect can
 *  leak or clobber a VAD handle. */
internal class GigaAmAsrEngine(
    private val modelManager: GigaAmModelManager,
    private val recognizerFactory: () -> RecognizerHandle = { RealRecognizerHandle(modelManager) },
    private val vadFactory: () -> VadHandle = { RealVadHandle(modelManager) },
) : ContinuousAsr {

    override fun isReady(): Boolean = modelManager.isReady()

    // Cached across sessions: creating the recognizer loads the 226 MiB GigaAM model from disk
    // (~1.3 s on the 780G) — paying that on every PTT press delayed both the music duck and the
    // first listened words (field defect APK 337). The VAD stays per-collection: it is cheap and
    // stateful, so a fresh instance per session is the safe reset.
    @Volatile private var cachedRecognizer: RecognizerHandle? = null

    /** Drop the cached recognizer so the next session reloads the model from disk. Called when
     *  the model files change (re-download). The old handle is NOT closed here: an in-flight
     *  session may still be decoding with it; the one-handle leak per re-download is bounded
     *  and rare, and beats a use-after-free.
     *
     *  Not wired to a call site yet: GigaAmModelManager (the re-download/delete owner) has no
     *  reference to this engine, and re-download is a rare, manual user action after which the
     *  driver restarts the app anyway -- building that wiring now would be speculative (YAGNI). */
    internal fun invalidateCachedRecognizer() {
        cachedRecognizer = null
    }

    /** Pre-builds the cached recognizer (~1.3 s cold load of the 226 MiB model) so the first
     *  PTT's transcribe() starts the mic immediately instead of after model load (field defect:
     *  first words swallowed on the first session after app start). */
    @Synchronized
    override fun warmUp() {
        if (isReady()) runCatching { obtainRecognizer() }
    }

    /** Single synchronized build point for the shared recognizer: warmUp() and transcribe()
     *  both funnel through here, so a background warm-up racing a PTT session can never each
     *  load the 226 MiB model and orphan the loser's handle. */
    @Synchronized
    private fun obtainRecognizer(): RecognizerHandle =
        cachedRecognizer ?: recognizerFactory().also { cachedRecognizer = it }

    // VAD is a local of the flow builder, so each collection owns its own instance: a second
    // (even concurrent) collect can never clobber or double-release another collection's VAD.
    // The recognizer is shared (cachedRecognizer above) and deliberately outlives every
    // collection's finally block.
    override fun transcribe(pcm: Flow<ShortArray>): Flow<ContinuousAsrEvent> = flow {
        if (!isReady()) return@flow
        // All builds go through the synchronized obtainRecognizer(): warmUp() (fired from
        // TrackingService/SettingsViewModel outside VoiceController's busy gate) is a second
        // call site into this engine, so the old unsynchronized check-then-act could have
        // double-loaded the model and orphaned one handle.
        val recognizer = obtainRecognizer()
        val vad = vadFactory()   // recognizer is cached -- no paired close needed on this throw path
        try {
            var speaking = false
            var silentMs = 0L
            pcm.collect { shorts ->
                vad.acceptWaveform(FloatArray(shorts.size) { i -> shorts[i] / 32768f })
                if (vad.isSpeechDetected()) {
                    if (!speaking) {
                        speaking = true
                        silentMs = 0L
                        emit(ContinuousAsrEvent.SpeechStart)
                    }
                } else {
                    silentMs += (shorts.size * 1000L) / SAMPLE_RATE
                    emit(ContinuousAsrEvent.SilenceTick(silentMs))
                }
                while (!vad.empty()) {
                    val segment = vad.front()
                    vad.pop()
                    speaking = false
                    val text = recognizer.decode(segment)
                    if (text.isNotBlank()) emit(ContinuousAsrEvent.Utterance(text))
                }
            }
        } finally {
            vad.close()   // recognizer intentionally NOT closed: cached for the next session
        }
    }

    companion object {
        const val SAMPLE_RATE = 16000

        // Silero VAD tuning against field defect APK 335: default threshold=0.5 clipped soft
        // speech onsets ("лышишь меня"); default minSilenceDuration=0.25s split phrases
        // mid-sentence ("в том это где").
        internal const val VAD_THRESHOLD = 0.4f
        internal const val VAD_MIN_SILENCE_SEC = 0.8f
        internal const val VAD_MIN_SPEECH_SEC = 0.25f
        internal const val VAD_MAX_SPEECH_SEC = 15.0f
    }
}
