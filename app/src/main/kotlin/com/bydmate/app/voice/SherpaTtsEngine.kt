package com.bydmate.app.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/** JNI-shape-pinned callback: sherpa-onnx native code looks up
 *  invoke([F)Ljava/lang/Integer; via GetMethodID on this exact class. A Kotlin
 *  lambda gets D8-desugared to a synthetic class WITHOUT the typed bridge in
 *  release builds -> NoSuchMethodError inside JNI -> SIGABRT (field crash
 *  2026-07-03). Do not convert back to a lambda. */
internal class TtsSamplesCallback(
    private val onSamples: (FloatArray) -> Int,
) : Function1<FloatArray, Int> {
    override fun invoke(samples: FloatArray): Int = onSamples(samples)
}

/** sherpa-onnx piper VITS TTS. All JNI access is confined to this class and
 *  runs on a single worker thread; construction touches no native code.
 *  Streaming: generateWithCallback feeds float PCM chunks into an
 *  AudioTrack (MODE_STREAM) so speech starts before synthesis finishes.
 *  Each speak() supersedes queued/playing speech via a generation counter;
 *  stop() invalidates all pending jobs. */
class SherpaTtsEngine(
    private val modelManager: TtsModelManager,
    private val selectedVoice: () -> TtsVoice = { TtsVoiceCatalog.byId(TtsModelManager.DEFAULT_VOICE_ID) },
    private val rate: () -> Float = { 1.0f },
    private val liveliness: () -> Int = { 33 },
    private val marker: RuStressMarker = RuStressMarker { null },
) : TtsEngine {

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "tts-worker") }
    @Volatile private var tts: OfflineTts? = null
    @Volatile private var track: AudioTrack? = null
    private val generation = AtomicInteger(0)

    // Cumulative frames written into [track] over its whole lifetime (worker-thread-confined,
    // reset when a fresh track is created). This -- not "head position at write start + own
    // samples" -- is the frame the head must reach for the track to be truly drained: the
    // parked track is reused across replies with up to 8s of earlier sentences still buffered
    // ahead of the head, so a head-relative target lands short by exactly that backlog. The
    // final drain then returned early and pause() parked the track with seconds of unplayed
    // tail (reply cut mid-word, field defect APK 347), which replayed at the start of the
    // NEXT reply; the same short target also let audible() unmute the mic mid-speech.
    private var trackFramesWritten = 0L

    init {
        // First engine creation preloads the dictionary too, so the very first reply after app
        // start doesn't pay the load latency (reload() covers voice switches afterwards).
        marker.preload()
    }

    // Frame position the currently written audio should reach once fully played, or -1 when
    // nothing is in flight (drained, or never started). audible() compares this against the
    // track's live playbackHeadPosition -- read/written from the tts worker thread and read
    // from any thread (see class doc on playbackHeadPosition's thread-safety).
    /** Playback target tagged with the generation that produced it, published in a single
     *  volatile write. audible() rejects a mismatched generation, so a write callback that was
     *  blocked inside AudioTrack.write while stop() superseded it cannot resurrect a dead
     *  target -- a plain check-then-publish of a bare Long had a TOCTOU window here. */
    private class PendingTarget(val gen: Int, val frames: Long)

    @Volatile private var pendingTarget: PendingTarget? = null

    // Wall-clock floor for audible(): elapsedRealtime timestamp until which written audio cannot
    // have finished sounding, no matter what playbackHeadPosition claims. The DiLink HAL reports
    // garbage head positions on some tracks (field log APK 340: "server read:-1182393"), which
    // made the frame-based check read "drained" mid-sentence, unmuted the mic and let ASR
    // transcribe the agent's own speech back at it. Stamped on every successful write; stop()
    // zeroes it.
    @Volatile private var audibleUntilMs: Long = 0L

    private val _speaking = MutableStateFlow(false)
    override val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    override fun isReady(): Boolean = modelManager.isReady(selectedVoice())

    /** Drops the cached engine/track so the next speak() re-initializes createTts()
     *  against the currently selected voice. Runs on the worker thread since tts/track
     *  are only ever touched there; bumps generation first so any job already queued
     *  against the old engine is dropped instead of speaking mid-switch. */
    override fun reload() {
        generation.incrementAndGet()
        marker.preload()
        worker.execute {
            runCatching { tts?.release() }
            tts = null
            runCatching { track?.release() }
            track = null
        }
    }

    override fun speak(text: String): Boolean {
        // Snapshot the decision inputs so the log reflects the exact values the
        // branch was taken on; blank text short-circuits without calling isReady().
        val blank = text.isBlank()
        val ready = !blank && isReady()
        if (blank || !ready) {
            Log.i(TAG, "speak skipped: blank=$blank ready=$ready voice=${selectedVoice().id}")
            return false
        }
        val myGen = generation.incrementAndGet()
        worker.execute {
            if (generation.get() != myGen) return@execute
            _speaking.value = true
            try {
                runCatching {
                    val engine = tts ?: createTts()?.also {
                        Log.i(TAG, "engine created: voice=${selectedVoice().id} engineRate=${it.sampleRate()}")
                        tts = it
                    } ?: run {
                        Log.w(TAG, "createTts returned null for voice=${selectedVoice().id}")
                        return@execute
                    }
                    val voice = selectedVoice()
                    val out = ensureTrackForRate(engine.sampleRate())
                    val synthesisText = textForSynthesis(voice.engine, text, marker::mark)
                    val samples = accumulateSentence(
                        generate = { onChunk ->
                            engine.generateWithCallback(
                                synthesisText, sid = voice.speakerId, speed = TtsTuning.speed(rate()), TtsSamplesCallback(onChunk),
                            )
                        },
                        stillCurrent = { generation.get() == myGen },
                    )
                    Log.i(TAG, "synth done: samples=${samples?.size} generation ok=${generation.get() == myGen}")
                    if (samples != null && samples.isNotEmpty() && generation.get() == myGen) {
                        // Start playback only now that the sentence is in hand: a track left
                        // ACTIVE and starving through multi-second synthesis gets underrun-
                        // disabled by AudioFlinger (BUFFER TIMEOUT), and this HAL never
                        // recovers from restartIfDisabled -- the server keeps framesReady(0),
                        // kicks the track again and the whole reply plays into the void
                        // (field log APK 344, track 84 / session 401).
                        if (out.playState != AudioTrack.PLAYSTATE_PLAYING) out.play()
                        // Publish target/floor before the write, not after -- see writeSentence's
                        // doc. The track buffer holds only a fraction of a sentence, so the write
                        // blocks for nearly the whole playback duration.
                        val written = writeSentence(
                            samples = samples,
                            write = { out.write(it, 0, it.size, AudioTrack.WRITE_BLOCKING) },
                            publish = {
                                pendingTarget = PendingTarget(myGen, trackFramesWritten + samples.size)
                                stampAudibleClock(samples.size, engine.sampleRate())
                            },
                            stillCurrent = { generation.get() == myGen },
                            retract = { pendingTarget = null; audibleUntilMs = 0L },
                        )
                        if (written > 0) trackFramesWritten += written
                    }
                    // Skip the drain wait if a newer speak()/stop() has already superseded us --
                    // that caller owns (or already reset) the speaking flag now.
                    if (generation.get() == myGen) {
                        // Remaining-frames timeout, not wall-clock: the 8s track buffer swallows
                        // the whole sentence, so the blocking write returns with up to 8s still
                        // buffered -- a duration-capped timeout would expire mid-tail and the
                        // pause() below would cut real speech on a perfectly healthy route.
                        val timeout = queueDrainTimeoutMs(
                            targetFrames = trackFramesWritten,
                            currentFrames = playbackFrames(out),
                            sampleRate = engine.sampleRate(),
                        )
                        awaitPlaybackDrain(out, trackFramesWritten, myGen, timeout)
                        // Park the drained track: left PLAYING on an empty buffer AudioFlinger
                        // underrun-disables it within a second, and the NEXT utterance would
                        // reuse the poisoned track (restartIfDisabled never recovers on this
                        // HAL). pause() -- never flush(), see stop() -- leaves the active list
                        // cleanly; the next write resumes it with play().
                        if (generation.get() == myGen) runCatching { out.pause() }
                    }
                }.onFailure { Log.w(TAG, "tts speak failed", it) }
            } finally {
                if (generation.get() == myGen) {
                    pendingTarget = null
                    _speaking.value = false
                }
            }
        }
        return true
    }

    override fun stop() {
        generation.incrementAndGet()
        pendingTarget = null
        audibleUntilMs = 0L
        _speaking.value = false
        // Never reuse a flushed track: after pause()+flush() the DiLink HAL restarts it with a
        // broken server-side read position (field log APK 340: "prior state:STATE_FLUSHED,
        // server read:-1182393"), so every frame-position comparison against it -- audible(),
        // drain waits -- is garbage. Silence it now, then release it off the caller thread and
        // let the next speak()/enqueue() build a fresh zero-positioned track. The stale local
        // keeps in-flight worker jobs (which hold their own ref) from racing a re-created track.
        val stale = track
        track = null
        runCatching {
            stale?.pause()
            stale?.flush()
        }
        worker.execute { runCatching { stale?.release() } }
    }

    /** Physical signal, not the logical [speaking] flag: false the instant the track has caught
     *  up to everything written so far, even while a queue stays "hot" between sentences during
     *  a silent tool round. */
    override fun audible(): Boolean {
        if (!_speaking.value) return false
        // Wall-clock floor first: written audio cannot have finished sounding yet, whatever the
        // HAL's head position claims. The frame-based check below stays as the second signal --
        // it keeps audible() true when underruns stretch playback PAST the nominal duration.
        if (SystemClock.elapsedRealtime() < audibleUntilMs) return true
        val out = track ?: return false
        val target = pendingTarget ?: return false
        return target.gen == generation.get() && playbackFrames(out) < target.frames
    }

    /** Plays already-synthesized PCM (e.g. from an online TTS client) through the same
     *  generation/worker/track path as speak(), minus the synthesis step: publish-before-write,
     *  the wall-clock floor and the drain wait all behave identically, so audible()/stop() stay
     *  correct for online audio too. The track is recreated when [sampleRate] does not match the
     *  one already in use (a different online voice, or a switch back to the offline model).
     *
     *  Blocks the calling thread until the write to the AudioTrack buffer completes, then returns
     *  the honest outcome: true only when all [samples] were actually written (real sound) or the
     *  write was cut short by a barge-in/stop (user interruption, not a route failure). Returns
     *  false and logs when write produced 0 / short bytes with no interruption -- that is silence
     *  on a dead or wrong output route, and the caller (TtsRouter) drops the rest of the reply.
     *  The drain wait continues on the worker thread after the caller unblocks. */
    override fun playPcm(samples: FloatArray, sampleRate: Int): Boolean {
        if (samples.isEmpty()) return false
        Log.i(TAG, "playPcm: samples=${samples.size} rate=$sampleRate")
        val myGen = generation.incrementAndGet()
        val requestedRate = rate()  // frozen once; both worker and caller use this snapshot, no race
        val writeOutcome = CompletableFuture<Boolean>()
        worker.execute {
            if (generation.get() != myGen) {
                // Superseded before the write even started -- a deliberate interruption, not a
                // silent-route failure; TtsRouter must not fall back.
                writeOutcome.complete(true)
                return@execute
            }
            _speaking.value = true
            try {
                runCatching {
                    val out = ensureTrackForRate(sampleRate)
                    if (out.playState != AudioTrack.PLAYSTATE_PLAYING) out.play()
                    // Apply user speech rate via time-stretch; piper PCM is already rate-adjusted
                    // at synthesis (TtsTuning.speed), so PlaybackParams are online-path only here.
                    // If the HAL rejects the params (exotic firmware), logs a warning and falls
                    // back to 1.0x so playback continues at the wrong speed rather than not at all.
                    // The effective sample rate for wall-clock math follows the actual applied rate.
                    val effectiveSampleRate = run {
                        val requested = effectivePlaybackRate(sampleRate, requestedRate)
                        val speed = requested.toFloat() / sampleRate
                        val ok = runCatching {
                            out.playbackParams = PlaybackParams().setSpeed(speed).setPitch(1f)
                        }.onFailure { Log.w(TAG, "playbackParams rejected by HAL, playing at 1.0x", it) }.isSuccess
                        if (ok) requested else sampleRate
                    }
                    var framesWritten = 0L
                    if (generation.get() == myGen) {
                        // Same publish-before-write ordering as speak()/enqueue() -- see writeSentence's doc.
                        val written = writeSentence(
                            samples = samples,
                            write = { out.write(it, 0, it.size, AudioTrack.WRITE_BLOCKING) },
                            publish = {
                                pendingTarget = PendingTarget(myGen, trackFramesWritten + samples.size)
                                // effectiveSampleRate falls back to sampleRate on HAL rejection,
                                // so stamp accuracy is correct there. If HAL silently plays at a
                                // different speed, stamp may expire early -- accepted trade-off.
                                stampAudibleClock(samples.size, effectiveSampleRate)
                            },
                            stillCurrent = { generation.get() == myGen },
                            retract = { pendingTarget = null; audibleUntilMs = 0L },
                        )
                        if (written > 0) {
                            framesWritten += written
                            trackFramesWritten += written
                        }
                    }
                    val interrupted = generation.get() != myGen
                    val outcome = playbackOutcome(framesWritten.toInt(), samples.size, interrupted)
                    if (!outcome) Log.w(TAG, "playPcm failed: short write $framesWritten of ${samples.size}")
                    // Signal the outcome to the calling thread BEFORE the drain wait so TtsRouter
                    // can decide immediately whether to fall back; drain continues here in background.
                    writeOutcome.complete(outcome)
                    if (generation.get() == myGen) {
                        // Remaining-frames timeout -- see the speak() drain comment.
                        val timeout = queueDrainTimeoutMs(
                            targetFrames = trackFramesWritten,
                            currentFrames = playbackFrames(out),
                            sampleRate = effectiveSampleRate,
                        )
                        awaitPlaybackDrain(out, trackFramesWritten, myGen, timeout)
                        // Park the drained track (never flush) -- see the speak() drain comment.
                        if (generation.get() == myGen) runCatching { out.pause() }
                    }
                }.onFailure { e ->
                    Log.w(TAG, "tts playPcm failed", e)
                    if (!writeOutcome.isDone) writeOutcome.complete(false)
                }
            } finally {
                if (!writeOutcome.isDone) writeOutcome.complete(false) // safety net for unhandled paths
                if (generation.get() == myGen) {
                    pendingTarget = null
                    _speaking.value = false
                }
            }
        }
        // writeWaitBoundMs is pessimistic: speedup (e.g. 2.0x) does NOT shorten the bound;
        // slowdown (0.5x) lengthens it proportionally. If PlaybackParams are rejected by the
        // HAL, actual playback runs at 1.0x and the bound must cover that full duration.
        val timeoutMs = writeWaitBoundMs(samples.size, sampleRate, requestedRate)
        return try {
            writeOutcome.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            Log.w(TAG, "audio write stalled after ${timeoutMs}ms, forcing track stop")
            val stale = track
            track = null
            runCatching { stale?.stop() }
            worker.execute { runCatching { stale?.release() } }
            false
        }
    }

    /** Reuses the current worker-thread-confined [track] when its sample rate already matches
     *  [sampleRate]; otherwise releases it and creates a fresh one. Shared by speak(), playPcm()
     *  and the offline queue's enqueue() so a mid-reply fallback (online PCM at one rate, then a
     *  live speak()/enqueue() call at another -- e.g. an online TTS answer followed by an
     *  offline announce()) recreates the track instead of silently reusing it at the wrong rate
     *  -- speak() and enqueue() used to skip this guard entirely and always reuse a non-null
     *  track. Not private: exercised directly by SherpaTtsEngineTest, since driving it through
     *  speak()/enqueue() would require a real OfflineTts, which cannot be constructed or mocked
     *  on the JVM unit test stub jar (its native library load always throws in <clinit>). */
    internal fun ensureTrackForRate(sampleRate: Int): AudioTrack {
        val existing = track
        return if (existing != null && !shouldRecreateTrack(existing.sampleRate, sampleRate)) {
            Log.i(TAG, "track reused: rate=$sampleRate")
            existing
        } else {
            Log.i(TAG, "track recreated: rate=$sampleRate existing=${existing?.sampleRate}")
            runCatching { existing?.release() }
            createTrack(sampleRate).also {
                track = it
                trackFramesWritten = 0L // fresh track, head restarts at 0
            }
        }
    }

    /** Advances the wall-clock audible floor by the nominal duration of [writtenFrames]. Called
     *  right before the write that will produce them, so "now" is close to the actual playback
     *  start -- except for queued sentences, where this call can land while the track still has
     *  an earlier sentence buffered ahead of the play head, so extend from the later of "now"
     *  and the floor already promised, never sum onto the past. */
    private fun stampAudibleClock(writtenFrames: Int, sampleRate: Int) {
        if (sampleRate <= 0) return
        val now = SystemClock.elapsedRealtime()
        audibleUntilMs = maxOf(audibleUntilMs, now) + writtenFrames * 1000L / sampleRate
    }

    /** Test seam: queue tests pin that enqueue() must not bump the generation. */
    internal fun generationForTest(): Int = generation.get()

    /** Test seam: drain/audible target of the in-flight write, or null when drained. */
    internal fun pendingTargetFramesForTest(): Long? = pendingTarget?.frames

    /** Test seam: speak()/enqueue() never reach real synthesis on the JVM (createTts() fails
     *  without the native sherpa-onnx lib), so audible()'s track/pendingTargetFrames state can
     *  never be driven through the real speak() path in unit tests. Primes that worker-confined
     *  state directly instead. */
    internal fun primeAudibleStateForTest(
        track: AudioTrack?,
        speaking: Boolean,
        pendingTargetFrames: Long,
        audibleUntilMs: Long = 0L,
    ) {
        this.track = track
        _speaking.value = speaking
        this.audibleUntilMs = audibleUntilMs
        pendingTarget =
            if (pendingTargetFrames >= 0) PendingTarget(generation.get(), pendingTargetFrames) else null
    }

    override fun startQueue(): TtsEngine.SpeechQueue? {
        if (!isReady()) return null
        val myGen = generation.incrementAndGet()
        return QueuedSpeech(myGen)
    }

    /** One streamed reply = one queue = one generation bump (at startQueue). Sentences are
     *  synthesized sequentially on the single worker into the shared MODE_STREAM track;
     *  WRITE_BLOCKING gives natural backpressure, so sentence N+1 synthesizes while N's tail
     *  is still playing. speaking stays true across sentence boundaries (mic-mute in the
     *  continuous session reads it every frame; a false dip would let ASR hear the reply). */
    private inner class QueuedSpeech(private val myGen: Int) : TtsEngine.SpeechQueue {
        // Worker-thread-confined (single executor), like tts/track. Frames THIS queue wrote --
        // drain/audible targets use the engine-level trackFramesWritten (cumulative over the
        // track's lifetime), this local only gates the drain wait in finish().
        private var totalFramesWritten = 0L

        override fun enqueue(text: String): Boolean {
            if (text.isBlank() || generation.get() != myGen) return false
            worker.execute {
                if (generation.get() != myGen) return@execute
                runCatching {
                    val engine = tts ?: createTts()?.also {
                        Log.i(TAG, "engine created: voice=${selectedVoice().id} engineRate=${it.sampleRate()}")
                        tts = it
                    } ?: run {
                        Log.w(TAG, "createTts returned null for voice=${selectedVoice().id}")
                        return@execute
                    }
                    Log.i(TAG, "enqueue: len=${text.length} engineRate=${engine.sampleRate()}")
                    val voice = selectedVoice()
                    val out = ensureTrackForRate(engine.sampleRate())
                    // Set-then-recheck: a stop() landing during the slow createTts/createTrack
                    // above bumps generation before we get here, so undo our own speaking=true
                    // write instead of leaving it stuck (stop() cannot see this write to undo it).
                    _speaking.value = true
                    if (generation.get() != myGen) { _speaking.value = false; return@execute }
                    val synthesisText = textForSynthesis(voice.engine, text, marker::mark)
                    val samples = accumulateSentence(
                        generate = { onChunk ->
                            engine.generateWithCallback(
                                synthesisText, sid = voice.speakerId, speed = TtsTuning.speed(rate()), TtsSamplesCallback(onChunk),
                            )
                        },
                        stillCurrent = { generation.get() == myGen },
                    )
                    Log.i(TAG, "synth done (queued): samples=${samples?.size} generation ok=${generation.get() == myGen}")
                    if (samples != null && samples.isNotEmpty() && generation.get() == myGen) {
                        // Same underrun-disable guard as speak(): start the track only with the
                        // sentence in hand. The first sentence of a queue synthesizes for seconds
                        // while an already-started track would starve ACTIVE and get disabled;
                        // for later sentences the track is still playing and play() is skipped.
                        if (out.playState != AudioTrack.PLAYSTATE_PLAYING) out.play()
                        // See the speak() comment: publish before the blocking write, same
                        // ordering fix for queued sentences.
                        val written = writeSentence(
                            samples = samples,
                            write = { out.write(it, 0, it.size, AudioTrack.WRITE_BLOCKING) },
                            publish = {
                                pendingTarget =
                                    PendingTarget(myGen, trackFramesWritten + samples.size)
                                stampAudibleClock(samples.size, engine.sampleRate())
                            },
                            stillCurrent = { generation.get() == myGen },
                            retract = { pendingTarget = null; audibleUntilMs = 0L },
                        )
                        if (written > 0) {
                            totalFramesWritten += written
                            trackFramesWritten += written
                        }
                    }
                    // No drain and no speaking=false here: the queue stays hot for the next sentence.
                }.onFailure { Log.w(TAG, "tts enqueue failed", it) }
            }
            return true
        }

        override fun finish() {
            worker.execute {
                try {
                    if (generation.get() != myGen) return@execute
                    val out = track ?: return@execute
                    val engine = tts ?: return@execute
                    if (totalFramesWritten > 0) {
                        val targetFrames = trackFramesWritten
                        val timeout = queueDrainTimeoutMs(
                            targetFrames = targetFrames,
                            currentFrames = playbackFrames(out),
                            sampleRate = engine.sampleRate(),
                        )
                        awaitPlaybackDrain(out, targetFrames, myGen, timeout)
                    }
                    // Park the drained track (never flush) -- see the speak() drain comment.
                    // Outside the frames-written check on purpose: a zero-length write still
                    // leaves the track play()ed and starving, and pause() on a never-started
                    // track is a harmless no-op.
                    if (generation.get() == myGen) runCatching { out.pause() }
                } finally {
                    if (generation.get() == myGen) {
                        pendingTarget = null
                        _speaking.value = false
                    }
                }
            }
        }
    }

    private fun playbackFrames(track: AudioTrack): Long = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL

    /** Blocks (on the tts worker thread only) until the track has actually played
     *  [targetFrames], so `speaking` flips false once audio has truly drained -- not merely
     *  once the last chunk was handed to the OS mixer. Bails out immediately once [myGen] is
     *  superseded (stop()/a newer speak() bumped the generation): stop()/flush() resets
     *  AudioTrack.playbackHeadPosition, so without this check a barge-in mid-drain would spin
     *  the full timeout with the played-frame target never reachable, wedging the worker thread
     *  behind the next queued speak(). Otherwise bounded so a stuck output route can never wedge
     *  the continuous-session mic mute forever. [timeoutMs] bounds the wait to the remaining
     *  buffered audio (see [queueDrainTimeoutMs]) instead of always spinning the full cap. */
    private fun awaitPlaybackDrain(track: AudioTrack, targetFrames: Long, myGen: Int, timeoutMs: Long) {
        awaitDrain(
            targetFrames = targetFrames,
            timeoutMs = timeoutMs,
            pollMs = DRAIN_POLL_MS,
            currentFrames = { playbackFrames(track) },
            stillCurrent = { generation.get() == myGen },
        )
    }

    private fun createTts(): OfflineTts? = runCatching {
        val voice = selectedVoice()
        val dir = modelManager.modelDirPath(voice.modelDirId)
        val modelConfig = if (voice.engine == TtsVoiceEngine.SUPERTONIC) {
            // Supertonic: 4-model flow-matching pipeline, char-based (no tokens.txt, no stress
            // dictionary); speaker is picked at synthesis time via sid, exactly like VITS_MULTI.
            OfflineTtsModelConfig(
                supertonic = OfflineTtsSupertonicModelConfig(
                    durationPredictor = "$dir/duration_predictor.int8.onnx",
                    textEncoder = "$dir/text_encoder.int8.onnx",
                    vectorEstimator = "$dir/vector_estimator.int8.onnx",
                    vocoder = "$dir/vocoder.int8.onnx",
                    ttsJson = "$dir/tts.json",
                    unicodeIndexer = "$dir/unicode_indexer.bin",
                    voiceStyle = "$dir/voice.bin",
                ),
                numThreads = 2,
                // NEVER "nnapi" — crashes on non-standard firmwares (sherpa-onnx#3611)
                provider = "cpu",
            )
        } else {
            val onnx = modelManager.onnxFile(voice.modelDirId)?.absolutePath ?: return null
            val livelinessValue = liveliness()
            OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = onnx,
                    tokens = "$dir/tokens.txt",
                    dataDir = dataDirFor(voice.engine, dir),
                    noiseScale = TtsTuning.noiseScale(livelinessValue),
                    noiseScaleW = TtsTuning.noiseScaleW(livelinessValue),
                ),
                numThreads = 2,
                // NEVER "nnapi" — crashes on non-standard firmwares (sherpa-onnx#3611)
                provider = "cpu",
            )
        }
        OfflineTts(config = OfflineTtsConfig(model = modelConfig))
    }.onFailure { Log.w(TAG, "tts init failed", it) }.getOrNull()

    private fun createTrack(sampleRate: Int): AudioTrack {
        val minBufBytes =
            AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        // Below API 31 the start threshold cannot be lowered, so the lookahead buffer must stay
        // small -- see trackBufferBytes.
        val thresholdAdjustable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val bufLen = trackBufferBytes(sampleRate, minBufBytes, thresholdAdjustable)
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setSampleRate(sampleRate)
            .build()
        // BYD DiLink routes STREAM_BTTS(17) to the UI "Voice" volume slider (live-validated on
        // Leopard 3, 2026-07-05). setLegacyStreamType is the only public way to target a custom
        // stream; if this firmware rejects it (exception or uninitialized track), fall back to
        // the previous accessibility route, which has an independent volume.
        var viaFallback = false
        val result = createTrackWithFallback(
            primary = { newTrack(bydVoiceAttributes(), format, bufLen).takeIfInitialized() },
            fallback = { viaFallback = true; newTrack(accessibilityAttributes(), format, bufLen) },
        )
        if (result.state != AudioTrack.STATE_INITIALIZED) {
            Log.w(TAG, "audio track bad state")
            runCatching { result.release() }
            throw IllegalStateException("audio track bad state")
        }
        // A streaming track starts playing only once startThresholdInFrames frames are buffered,
        // and the AOSP default is the FULL buffer capacity. With the 8s lookahead buffer no
        // sub-8s utterance ever reaches it: the track sits in FS_FILLING until AudioFlinger
        // kicks it (BUFFER TIMEOUT) and every voice is silent (field defect APK 344/345,
        // live-proven 2026-07-08: sound cut in exactly when cumulative writes crossed the
        // buffer size). Lower it to one min-buffer -- see startThresholdFrames.
        if (thresholdAdjustable) {
            val requested = startThresholdFrames(minBufBytes, FLOAT_FRAME_BYTES)
            val applied = runCatching { result.setStartThresholdInFrames(requested) }.getOrNull()
            Log.i(TAG, "start threshold: requested=$requested applied=$applied")
        }
        Log.i(TAG, "track created: rate=$sampleRate state=${result.state} viaFallback=$viaFallback")
        return result
    }

    private fun newTrack(attrs: AudioAttributes, format: AudioFormat, bufLen: Int) = AudioTrack(
        attrs, format, bufLen, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE,
    )

    private fun AudioTrack.takeIfInitialized(): AudioTrack? =
        if (state == AudioTrack.STATE_INITIALIZED) this
        else { runCatching { release() }; null }

    private fun bydVoiceAttributes(): AudioAttributes =
        AudioAttributes.Builder().setLegacyStreamType(BYD_STREAM_BTTS).build()

    private fun accessibilityAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .setUsage(TTS_USAGE)
        .build()

    // Not private: awaitDrain is a pure poll loop exercised directly by SherpaTtsEngineTest
    // (no real AudioTrack/JNI needed) to pin the barge-in-frees-the-worker-promptly behaviour.
    companion object {
        private const val TAG = "SherpaTtsEngine"
        // Fallback TTS output stream if the BYD Voice stream is rejected. Accessibility has an
        // independent volume (public API since 26). Single constant so a firmware that aliases
        // ACCESSIBILITY back onto MUSIC can be moved to a fallback (USAGE_NOTIFICATION_EVENT /
        // USAGE_ALARM) in one edit.
        internal val TTS_USAGE = AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
        // BYD custom stream behind the DiLink UI "Voice" volume slider.
        internal const val BYD_STREAM_BTTS = 17

        // The track buffer holds this many seconds of audio so a whole sentence's blocking write
        // returns while the sentence is still playing -- the worker then synthesizes the NEXT
        // sentence in parallel with playback, which removes the inter-sentence pauses (Wave P).
        internal const val TRACK_LOOKAHEAD_SECONDS = 8

        // Bytes per frame of the track's PCM_FLOAT mono format.
        internal const val FLOAT_FRAME_BYTES = 4

        /** Bytes for the AudioTrack buffer: [TRACK_LOOKAHEAD_SECONDS] of float mono audio,
         *  floored at 4x the device minimum so tiny sample rates keep the old headroom.
         *  When the start threshold cannot be lowered (API < 31, [thresholdAdjustable] false)
         *  the lookahead buffer would never fill from a short utterance and playback would
         *  never start (see createTrack) -- keep the old small buffer there instead. */
        internal fun trackBufferBytes(sampleRate: Int, minBufBytes: Int, thresholdAdjustable: Boolean = true): Int =
            if (thresholdAdjustable) maxOf(4 * minBufBytes, sampleRate * 4 * TRACK_LOOKAHEAD_SECONDS)
            else 4 * minBufBytes

        /** Start-playback threshold in frames: one device-minimum buffer (~80 ms at 22050 Hz).
         *  A sub-threshold utterance never starts playing at all (MODE_STREAM has no
         *  end-of-data signal short of stop(), which this engine avoids), so the threshold must
         *  sit below the shortest real utterance -- every synthesized word and online PCM clip
         *  is far longer than one min-buffer. Not lower still: one min-buffer of priming guards
         *  the freshly started track against an instant underrun before the rest of the
         *  blocking write lands. Pure so it is unit-testable. */
        internal fun startThresholdFrames(minBufBytes: Int, bytesPerFrame: Int): Int =
            (minBufBytes / bytesPerFrame.coerceAtLeast(1)).coerceAtLeast(1)

        /** Try the BYD Voice-stream track first; any exception or null (uninitialized track)
         *  falls back to the accessibility route. Pure so it is unit-testable without JNI. */
        internal fun <T : Any> createTrackWithFallback(primary: () -> T?, fallback: () -> T): T =
            runCatching { primary() }.getOrNull() ?: fallback()

        /** Pure decision seam for playPcm's track reuse: true when the currently held track's
         *  sample rate does not match the requested one, meaning it must be released and
         *  recreated. Kept out of playPcm itself because the real recreation path touches
         *  AudioTrack/AudioFormat.Builder, which is not constructible on the JVM unit test stub
         *  jar (same seam pattern as accumulateSentence/writeSentence). */
        internal fun shouldRecreateTrack(currentSampleRate: Int, requestedSampleRate: Int): Boolean =
            currentSampleRate != requestedSampleRate

        /** Pure outcome decision for [playPcm]: a write covering all expected frames is real
         *  sound; a short write (dead route, wrong stream, HAL refusing) is silence and must
         *  surface as failure so [com.bydmate.app.voice.online.TtsRouter] can drop the rest of
         *  the reply (online failures stay silent, never swapped to the offline voice). A
         *  generation bump mid-write is a deliberate user interruption -- the user heard speech
         *  and barged in -- so it is never treated as failure. */
        internal fun playbackOutcome(written: Int, expected: Int, interrupted: Boolean): Boolean =
            interrupted || written >= expected

        /** PIPER archives need espeak-ng-data/ for phonemization; the VITS_MULTI archive ships
         *  no espeak data and errors if pointed at a non-existent dir, so pass "" for it. */
        internal fun dataDirFor(engine: TtsVoiceEngine, modelDir: String): String =
            if (engine == TtsVoiceEngine.PIPER) "$modelDir/espeak-ng-data" else ""

        /** Dictionary stress marking is engine-specific: VITS_MULTI needs '+', Supertonic needs
         *  uppercase stressed vowels because its char model speaks '+' / combining accents
         *  literally, and PIPER must stay untouched because espeak-ng predicts stress itself.
         *  Pure so the routing seam is unit-testable without JNI (same pattern as
         *  accumulateSentence/writeSentence). */
        internal fun textForSynthesis(
            engine: TtsVoiceEngine,
            text: String,
            mark: (String, RuStressMarker.Style) -> String,
        ): String = when (engine) {
            TtsVoiceEngine.VITS_MULTI -> mark(text, RuStressMarker.Style.PLUS)
            TtsVoiceEngine.SUPERTONIC -> mark(text, RuStressMarker.Style.UPPERCASE)
            TtsVoiceEngine.PIPER -> text
        }

        /** Runs synthesis via [generate] (stands in for OfflineTts.generateWithCallback in
         *  production), buffering every chunk instead of writing it to the track as it streams
         *  in, then returns the whole sentence as one merged buffer -- or null if [stillCurrent]
         *  ever went false, meaning stop()/a newer speak() superseded this job and nothing should
         *  be written at all. Pure so the accumulate-then-write ordering and the mid-synthesis
         *  stop case are unit-testable without a real AudioTrack/JNI (same seam pattern as
         *  [awaitDrain]). */
        internal fun accumulateSentence(
            generate: (onChunk: (FloatArray) -> Int) -> Unit,
            stillCurrent: () -> Boolean,
        ): FloatArray? {
            val chunks = mutableListOf<FloatArray>()
            generate { samples ->
                if (!stillCurrent()) 0
                else { chunks.add(samples); 1 }
            }
            if (!stillCurrent()) return null
            val merged = FloatArray(chunks.sumOf { it.size })
            var offset = 0
            for (chunk in chunks) {
                chunk.copyInto(merged, offset)
                offset += chunk.size
            }
            return merged
        }

        /** Publishes the playback target/wall-clock floor via [publish] BEFORE calling [write],
         *  then retracts via [retract] if [stillCurrent] went false while the write was blocked
         *  -- a stop() landing mid-write already zeroes both fields itself, so retract() is a
         *  no-op belt-and-suspenders there, but it also catches a same-thread reload() that
         *  bumped the generation without touching either field. Pure so the publish-before-write
         *  ordering and the retract-on-supersession behaviour are unit-testable without a real
         *  AudioTrack/JNI (same seam pattern as [accumulateSentence]/[awaitDrain]). */
        internal fun writeSentence(
            samples: FloatArray,
            write: (FloatArray) -> Int,
            publish: () -> Unit,
            stillCurrent: () -> Boolean,
            retract: () -> Unit,
        ): Int {
            publish()
            val written = write(samples)
            if (!stillCurrent()) retract()
            return written
        }

        private const val DRAIN_POLL_MS = 20L

        // Safety margin on top of the computed remaining-audio duration: covers buffer priming
        // and route-switch latency without re-introducing multi-second deafness.
        internal const val DRAIN_MARGIN_MS = 700L

        // Backstop for the drain wait. Must cover the FULL track buffer at the SLOWEST playback
        // speed: at the 0.5x floor of effectivePlaybackRate a buffer of TRACK_LOOKAHEAD_SECONDS
        // source frames sounds for twice that wall-clock, and the remaining-frames math below
        // can never legitimately exceed that plus the margin -- anything above this cap is a
        // math error, not audio. The old 5s cap predated the 8s buffer: a healthy >4.3s
        // utterance timed out mid-tail and the post-drain pause() would have cut real speech.
        internal const val DRAIN_TIMEOUT_MS = TRACK_LOOKAHEAD_SECONDS * 2 * 1_000L + DRAIN_MARGIN_MS

        /** Drain timeout for all three playback paths, derived from the audio actually remaining
         *  in the track buffer ([targetFrames] - [currentFrames]) instead of wall-clock elapsed
         *  time. Wall-clock lies twice here: LLM gaps between queued sentences would collapse an
         *  elapsed-based timeout to 0 while the tail still plays, and the 8s buffer lets the
         *  blocking write return seconds before the audio has sounded. playbackHeadPosition does
         *  not advance on some DiLink routes (field defect APK 336), so the wait stays bounded by
         *  how long the buffered audio can actually take to play, plus a margin. */
        internal fun queueDrainTimeoutMs(targetFrames: Long, currentFrames: Long, sampleRate: Int): Long {
            val remainingMs = (targetFrames - currentFrames).coerceAtLeast(0L) * 1000L / sampleRate.coerceAtLeast(1)
            return (remainingMs + DRAIN_MARGIN_MS).coerceAtMost(DRAIN_TIMEOUT_MS)
        }

        /** Write-wait timeout for [playPcm]: AudioTrack.write(WRITE_BLOCKING) proceeds in real
         *  time, so duration + 2 s headroom is generous. Floor of 3 s covers route-switch latency
         *  and very short clips. Pure so it is unit-testable. */
        internal fun pcmWriteTimeoutMs(expectedFrames: Int, sampleRate: Int): Long {
            val durationMs = expectedFrames.toLong() * 1_000L / sampleRate.coerceAtLeast(1)
            return maxOf(3_000L, durationMs + 2_000L)
        }

        /** Pessimistic write-wait rescue bound for [playPcm]: the bound is a hang-rescue, not a
         *  precision timer. Speedup (e.g. 2.0x) does NOT shorten the bound -- if the HAL rejects
         *  PlaybackParams, the write falls back to 1.0x speed and the bound must cover that.
         *  Slowdown (0.5x) is accepted as given and lengthens the bound. Pure so it is unit-testable. */
        internal fun writeWaitBoundMs(sampleCount: Int, sampleRate: Int, requestedRate: Float): Long {
            // Clamp speedup to 1.0x for the bound: if HAL rejects 2.0x, write takes the full
            // 1.0x duration. Slowdown passes through: at 0.5x the write takes ~2x nominal.
            val boundRate = requestedRate.coerceAtMost(1.0f)
            return pcmWriteTimeoutMs(sampleCount, effectivePlaybackRate(sampleRate, boundRate))
        }

        /** Pure seam for the effective sample rate after time-stretch: at speed 1.5, 24000 Hz
         *  audio plays in 2/3 of the real time, so the wall-clock floor and drain timeout must
         *  shrink proportionally. Non-finite and zero inputs (corrupt settings store) fall back
         *  to 1.0 so the math reduces identically to the no-rate case. */
        internal fun effectivePlaybackRate(base: Int, speed: Float): Int {
            val clamped = if (speed.isFinite() && speed > 0f) speed.coerceIn(0.5f, 2.0f) else 1.0f
            return (base * clamped).toInt()
        }

        /** Pure poll loop: waits for [currentFrames] to reach [targetFrames], bailing out
         *  immediately once [stillCurrent] goes false (a newer speak()/stop() already owns the
         *  speaking flag) rather than spinning until [timeoutMs]. [sleep]/[now] are seams so
         *  tests can prove the bail-out without a real clock or AudioTrack. */
        internal fun awaitDrain(
            targetFrames: Long,
            timeoutMs: Long,
            pollMs: Long,
            currentFrames: () -> Long,
            stillCurrent: () -> Boolean,
            sleep: (Long) -> Unit = Thread::sleep,
            now: () -> Long = System::currentTimeMillis,
        ) {
            val deadline = now() + timeoutMs
            while (now() < deadline) {
                if (!stillCurrent()) return
                if (currentFrames() >= targetFrames) return
                sleep(pollMs)
            }
        }
    }
}
