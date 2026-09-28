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
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

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

/** Rolling raw PCM context from just before Silero declares speech. */
private class SpeechPreRoll(private val maxSamples: Int) {
    private val chunks = ArrayDeque<FloatArray>()
    private var sampleCount = 0

    fun append(frame: FloatArray) {
        chunks.addLast(frame)
        sampleCount += frame.size
        trim()
    }

    fun take(): FloatArray {
        if (sampleCount == 0) return FloatArray(0)
        val out = FloatArray(sampleCount)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(out, offset)
            offset += chunk.size
        }
        chunks.clear()
        sampleCount = 0
        return out
    }

    private fun trim() {
        while (sampleCount > maxSamples && chunks.isNotEmpty()) {
            val first = chunks.removeFirst()
            val excess = sampleCount - maxSamples
            if (excess < first.size) {
                val kept = first.copyOfRange(excess, first.size)
                chunks.addFirst(kept)
                sampleCount -= excess
                return
            }
            sampleCount -= first.size
        }
    }
}

private fun prependPrefix(prefix: FloatArray, segment: FloatArray): FloatArray {
    if (prefix.isEmpty()) return segment
    return FloatArray(prefix.size + segment.size).also { merged ->
        prefix.copyInto(merged, 0)
        segment.copyInto(merged, prefix.size)
    }
}

/** GigaAM v3 Russian nemo-CTC recognizer segmented by a silero VAD, for the continuous voice
 *  session. The recognizer is cached across sessions (see cachedRecognizer below) since
 *  constructing it loads the model from disk; the VAD stays per-collection, created and
 *  released in transcribe()'s finally block, so neither cancellation nor a second collect can
 *  leak or clobber a VAD handle. */
@Suppress("TooManyFunctions")
internal class GigaAmAsrEngine(
    private val modelManager: GigaAmModelManager,
    private val recognizerFactory: () -> RecognizerHandle = { RealRecognizerHandle(modelManager) },
    private val vadFactory: () -> VadHandle = { RealVadHandle(modelManager) },
    private val loadGuard: AsrLoadGuard? = null,
    private val dilink3Optimizations: Boolean = false,
) : ContinuousAsr {

    // A tripped guard means the last loads aborted the whole process from native code
    // (corrupt .onnx): reporting not-ready blocks every load path (warmUp and transcribe
    // both gate on isReady) until TrackingService quarantines the files and resets it.
    override fun isReady(): Boolean = modelManager.isReady() && loadGuard?.isTripped() != true

    // Cached across sessions: creating the recognizer loads the 226 MiB GigaAM model from disk
    // (~1.3 s on the 780G) — paying that on every PTT press delayed both the music duck and the
    // first listened words (field defect APK 337). The VAD stays per-collection: it is cheap and
    // stateful, so a fresh instance per session is the safe reset.
    @Volatile private var cachedRecognizer: RecognizerHandle? = null
    // Keep one UNUSED VAD hot. A live session takes ownership of the spare and a
    // replacement is prepared in the background for the next PTT.
    @Volatile private var cachedVad: VadHandle? = null
    private val vadWarmupInFlight = AtomicBoolean(false)

    override fun isWarm(): Boolean =
        if (dilink3Optimizations) cachedRecognizer != null && cachedVad != null else isReady()

    override fun requiresWarmBeforeListening(): Boolean = dilink3Optimizations

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
        synchronized(this) {
            runCatching { cachedVad?.close() }
            cachedVad = null
        }
    }

    /** Pre-build both native pieces used before pcm.collect starts. Without the VAD spare the
     *  mic flow is subscribed only after the Silero constructor returns, which can swallow the
     *  first spoken words on DiLink 3. */
    @Synchronized
    override fun warmUp() {
        if (!isReady()) return
        val recognizerReady = runCatching { obtainRecognizer() }.isSuccess
        if (dilink3Optimizations && recognizerReady) runCatching { ensureCachedVad() }
    }

    /** Single synchronized build point for the shared recognizer: warmUp() and transcribe()
     *  both funnel through here, so a background warm-up racing a PTT session can never each
     *  load the 226 MiB model and orphan the loser's handle. */
    @Synchronized
    private fun obtainRecognizer(): RecognizerHandle =
        cachedRecognizer ?: run {
            // Bracket the native load with the crash-loop guard: a corrupt model aborts
            // the process inside recognizerFactory() (SIGABRT from JNI), so only the
            // begin mark survives — that asymmetry is how the next start detects it.
            loadGuard?.noteLoadBegin(AsrLoadGuard.ARTIFACT_RECOGNIZER)
            val handle = recognizerFactory()
            loadGuard?.noteLoadSuccess(AsrLoadGuard.ARTIFACT_RECOGNIZER)
            handle.also { cachedRecognizer = it }
        }

    private fun buildVad(): VadHandle {
        loadGuard?.noteLoadBegin(AsrLoadGuard.ARTIFACT_VAD)
        val handle = vadFactory()
        loadGuard?.noteLoadSuccess(AsrLoadGuard.ARTIFACT_VAD)
        return handle
    }

    @Synchronized
    private fun ensureCachedVad(): VadHandle =
        cachedVad ?: buildVad().also { cachedVad = it }

    @Synchronized
    private fun takeVad(): VadHandle {
        val ready = cachedVad
        if (ready != null) {
            cachedVad = null
            return ready
        }
        return buildVad()
    }

    private fun prewarmNextVad() {
        if (!isReady() || cachedVad != null || !vadWarmupInFlight.compareAndSet(false, true)) return
        thread(name = "gigaam-vad-prewarm", isDaemon = true) {
            try {
                synchronized(this@GigaAmAsrEngine) {
                    if (cachedVad == null && isReady()) cachedVad = buildVad()
                }
            } finally {
                vadWarmupInFlight.set(false)
            }
        }
    }

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
        val vad = if (dilink3Optimizations) {
            // DiLink3: take the already-built fresh VAD so PCM collection starts immediately,
            // then prepare another unused instance while this session is running.
            takeVad().also { prewarmNextVad() }
        } else {
            // Upstream 3.19 behavior for every other platform.
            buildVad()
        }
        try {
            var speaking = false
            var silentMs = 0L
            val preRoll = if (dilink3Optimizations) SpeechPreRoll(VAD_PRE_ROLL_SAMPLES) else null
            var utterancePrefix = FloatArray(0)

            pcm.collect { shorts ->
                val frame = FloatArray(shorts.size) { i -> shorts[i] / 32768f }
                vad.acceptWaveform(frame)
                val speechDetected = vad.isSpeechDetected()

                if (speechDetected) {
                    if (!speaking) {
                        speaking = true
                        silentMs = 0L
                        utterancePrefix = preRoll?.take() ?: FloatArray(0)
                        emit(ContinuousAsrEvent.SpeechStart)
                    }
                } else {
                    if (!speaking) preRoll?.append(frame)
                    silentMs += (shorts.size * 1000L) / SAMPLE_RATE
                    emit(ContinuousAsrEvent.SilenceTick(silentMs))
                }

                while (!vad.empty()) {
                    val rawSegment = vad.front()
                    vad.pop()
                    speaking = false
                    val segment = prependPrefix(utterancePrefix, rawSegment)
                    utterancePrefix = FloatArray(0)
                    val text = recognizer.decode(segment)
                    if (text.isNotBlank()) {
                        emit(ContinuousAsrEvent.Utterance(text, segment.size * 1000L / SAMPLE_RATE))
                    }
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
        // Preserve 400 ms of audio immediately before Silero declares speech. This protects
        // soft initial syllables in a noisy cabin without lowering the VAD threshold globally.
        internal const val VAD_PRE_ROLL_MS = 400
        internal const val VAD_PRE_ROLL_SAMPLES = SAMPLE_RATE * VAD_PRE_ROLL_MS / 1000

        internal const val VAD_THRESHOLD = 0.4f
        internal const val VAD_MIN_SILENCE_SEC = 0.8f
        internal const val VAD_MIN_SPEECH_SEC = 0.25f
        internal const val VAD_MAX_SPEECH_SEC = 15.0f
    }
}
