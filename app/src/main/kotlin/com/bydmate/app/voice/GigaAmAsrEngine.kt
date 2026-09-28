package com.bydmate.app.voice

import android.util.Log
import com.bydmate.app.diagnostics.Trace
import com.bydmate.app.diagnostics.TraceArea
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
import java.util.concurrent.Executor
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

/** GigaAM v3 Russian nemo-CTC recognizer segmented by a silero VAD, for the continuous voice
 *  session. The recognizer is cached across sessions (see cachedRecognizer below) since
 *  constructing it loads the model from disk; the VAD stays per-collection, created and
 *  released in transcribe()'s finally block, so neither cancellation nor a second collect can
 *  leak or clobber a VAD handle. With [warmVad] (Android 10 head units) that per-collection VAD
 *  is built ahead of the session instead of on its path, see spareVad. */
internal class GigaAmAsrEngine(
    private val modelManager: GigaAmModelManager,
    private val recognizerFactory: () -> RecognizerHandle = { RealRecognizerHandle(modelManager) },
    private val vadFactory: () -> VadHandle = { RealVadHandle(modelManager) },
    private val loadGuard: AsrLoadGuard? = null,
    private val warmVad: Boolean = false,
    // Test seam: where the next spare VAD gets built.
    private val prewarmExecutor: Executor = Executor { task ->
        thread(name = "gigaam-vad-prewarm", isDaemon = true) { task.run() }
    },
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

    // warmVad only: one VAD built ahead and not used yet (PR #261, DiLink 3.0 ATTO 3: building it
    // after the start cue swallowed the first words). A session takes it and owns it from then on
    // (closed in its finally), and the next one is built in the background. Written under this
    // engine's monitor; volatile so isWarm() reads it from the main thread without the monitor,
    // which a recognizer load can hold for seconds.
    @Volatile private var spareVad: VadHandle? = null
    private val spareInFlight = AtomicBoolean(false)

    override fun isWarm(): Boolean =
        if (warmVad) isReady() && cachedRecognizer != null && spareVad != null else isReady()

    override fun requiresWarmBeforeListening(): Boolean = warmVad

    /** Drop the cached recognizer so the next session reloads the model from disk. Called when
     *  the model files change (re-download). The old handle is NOT closed here: an in-flight
     *  session may still be decoding with it; the one-handle leak per re-download is bounded
     *  and rare, and beats a use-after-free.
     *
     *  Not wired to a call site yet: GigaAmModelManager (the re-download/delete owner) has no
     *  reference to this engine, and re-download is a rare, manual user action after which the
     *  driver restarts the app anyway -- building that wiring now would be speculative (YAGNI).
     *  The spare VAD, unused by definition, is closed. */
    internal fun invalidateCachedRecognizer() {
        cachedRecognizer = null
        synchronized(this) {
            spareVad?.let { runCatching { it.close() } }
            spareVad = null
        }
    }

    /** Pre-builds the cached recognizer (~1.3 s cold load of the 226 MiB model) so the first
     *  PTT's transcribe() starts the mic immediately instead of after model load (field defect:
     *  first words swallowed on the first session after app start). With [warmVad] also the
     *  spare VAD. */
    @Synchronized
    override fun warmUp() {
        if (!isReady()) return
        val recognizer = runCatching { obtainRecognizer() }
        if (warmVad && recognizer.isSuccess && spareVad == null) runCatching { spareVad = buildVad() }
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

    // Same crash-loop bracket as the recognizer: the silero VAD is a native .onnx load
    // too, and a corrupt file aborts the process the same way. Separate artifact key so
    // a successful recognizer load can't wipe the VAD's crash evidence.
    private fun buildVad(): VadHandle {
        loadGuard?.noteLoadBegin(AsrLoadGuard.ARTIFACT_VAD)
        val vad = vadFactory()
        loadGuard?.noteLoadSuccess(AsrLoadGuard.ARTIFACT_VAD)
        return vad
    }

    /** Hands the spare VAD to the calling session, which owns (and closes) it from now on. */
    @Synchronized
    private fun takeSpareVad(): VadHandle? = spareVad.also { spareVad = null }

    /** Builds the next spare off the session's path. One build at a time; a failure only leaves
     *  the next session building its own VAD, as on other cars. */
    private fun prewarmNextVad() {
        if (!spareInFlight.compareAndSet(false, true)) return
        prewarmExecutor.execute {
            try {
                synchronized(this@GigaAmAsrEngine) {
                    if (spareVad == null && isReady()) {
                        runCatching { spareVad = buildVad() }
                            .onFailure { Log.w(TAG, "spare vad build failed", it) }
                    }
                }
            } finally {
                spareInFlight.set(false)
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
        val vadStartNs = System.nanoTime()
        val spare = if (warmVad) takeSpareVad() else null
        val vad = spare ?: buildVad()   // recognizer is cached -- no paired close needed on this throw path
        try {
            // One line per session on every car: how long the session waited for its VAD, so a
            // normal drive shows whether newer head units need the spare too. Milliseconds only.
            val vadMs = (System.nanoTime() - vadStartNs) / NANOS_PER_MS
            Log.i(TAG, "vad ready: ms=$vadMs prebuilt=${spare != null}")
            Trace.event(TraceArea.VOICE, "vad-ready", "ms" to vadMs, "prebuilt" to (spare != null))
            if (warmVad) prewarmNextVad()
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
        // Recorded by LogRecorder: the VAD line belongs to the voice session's story.
        private const val TAG = "VoiceController"
        private const val NANOS_PER_MS = 1_000_000L

        // Silero VAD tuning against field defect APK 335: default threshold=0.5 clipped soft
        // speech onsets ("лышишь меня"); default minSilenceDuration=0.25s split phrases
        // mid-sentence ("в том это где").
        internal const val VAD_THRESHOLD = 0.4f
        internal const val VAD_MIN_SILENCE_SEC = 0.8f
        internal const val VAD_MIN_SPEECH_SEC = 0.25f
        internal const val VAD_MAX_SPEECH_SEC = 15.0f
    }
}
