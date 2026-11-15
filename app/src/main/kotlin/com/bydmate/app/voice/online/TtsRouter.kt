package com.bydmate.app.voice.online

import android.util.Log
import com.bydmate.app.diagnostics.Trace
import com.bydmate.app.diagnostics.TraceArea
import com.bydmate.app.voice.TtsEngine
import com.bydmate.app.voice.TtsGender
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue

/** Wraps an offline [TtsEngine] (the Sherpa/Piper voice) and, when an online source is selected,
 *  speaks through a cloud [OnlineTtsBackend]. If the online backend fails or times out, the rest of
 *  the reply is spoken by the offline engine (silent only when it is unavailable, e.g. its model
 *  is not downloaded; the text stays visible in the orb dialog) and [onOnlineFailure] hears about
 *  it once per reply. The
 *  offline engine is always the delegate for stop/speaking/audible/reload, so barge-in and the
 *  mic-mute machinery stay exactly as they are today regardless of which source spoke. */
class TtsRouter @Suppress("LongParameterList") constructor( // DI-provided lambdas plus test seams
    private val delegate: TtsEngine,
    private val backends: List<OnlineTtsBackend> = emptyList(),
    private val selectedSource: () -> String = { OFFLINE },
    private val selectedGender: () -> TtsGender = { TtsGender.MALE },
    /** Short phrases worth synthesizing ahead of use (the persona's confirmation pools). */
    private val precachePhrases: () -> List<String> = { emptyList() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val synthTimeoutMs: Long = SYNTH_TIMEOUT_MS,
    /** App-private directory for the persisted phrase cache; null keeps phrases in memory only. */
    phraseDir: File? = null,
    /** Called once per reply the online voice failed (never for the background precache). */
    private val onOnlineFailure: (OnlineTtsFailure) -> Unit = {},
) : TtsEngine {

    override val speaking: StateFlow<Boolean> = delegate.speaking

    // Every online job still in flight (single speak() calls and agent reply queues), so stop()
    // -- called on barge-in -- can never let a sentence still awaiting synthesis play out after
    // the caller already considers speech stopped. Several speak() calls can overlap (an
    // automation's consecutive "speak" actions), so one slot is not enough. Guarded by [inFlightLock];
    // cancel handles run outside the lock (a job's completion handler removes itself under it).
    private class InFlight(val isQueue: Boolean, val cancel: () -> Unit)
    private val inFlightLock = Any()
    private val inFlight = mutableListOf<InFlight>()

    /** Removes the matching in-flight entries and registers [add] in ONE critical section (so two
     *  racing calls behave as if sequential: the later one always cancels the earlier), then runs
     *  the removed entries' cancel handles outside the lock. */
    private fun replaceInFlight(add: InFlight?, which: (InFlight) -> Boolean) {
        val dropped = synchronized(inFlightLock) {
            inFlight.filter(which).also { inFlight.removeAll(it); if (add != null) inFlight += add }
        }
        dropped.forEach { it.cancel() }
    }

    // Short phrases (persona confirmations) replay from memory instead of paying a network
    // round-trip every time. The key carries source + gender + voice identity + text, so a switch
    // of voice or source never replays the old voice; LRU-bounded so stray short replies cannot
    // grow it. Disk is the second level: a phrase survives restarts and is synthesized once per voice.
    private val phraseDisk = phraseDir?.let(::PhraseDiskCache)
    private val phraseCache = object : LinkedHashMap<String, TtsPcm>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TtsPcm>) =
            size > PHRASE_CACHE_ENTRIES
    }

    /** Online source is ready when its backend is configured. The local model only backs up a
     *  failed online reply, so its presence does not decide readiness; source "offline" still
     *  follows the delegate. */
    override fun isReady(): Boolean {
        val backend = onlineBackend() ?: return delegate.isReady()
        return runCatching { runBlocking { backend.configured() } }.getOrDefault(false)
    }

    override fun speak(text: String): Boolean {
        if (text.isBlank()) return false
        val backend = onlineBackend()
        Log.i(TAG, "route: source=${backend?.id ?: OFFLINE}")
        if (backend == null) return delegate.speak(text)
        // A new reply supersedes an agent reply queue still awaiting synthesis: its sentences must
        // not play after (or between) the new audio. A previous single speak() is left alone, so
        // an automation's consecutive "speak" actions all still play.
        // Lazy: registered before it can run, so a stop() racing this call cancels it too.
        val job = scope.launch(start = CoroutineStart.LAZY) { speakOnline(backend, text) }
        val entry = InFlight(isQueue = false) { job.cancel() }
        replaceInFlight(entry) { it.isQueue }
        job.invokeOnCompletion { synchronized(inFlightLock) { inFlight.remove(entry) } }
        job.start()
        return true
    }

    /** Preview of a LOCAL voice row must bypass the online source and always speak through
     *  the offline delegate, regardless of which source is currently selected. */
    override fun speakOffline(text: String): Boolean = delegate.speak(text)

    private suspend fun speakOnline(backend: OnlineTtsBackend, text: String) {
        val result = synthesizeOnline(backend, text)
        // A cache hit never suspends, so a stop() that already cancelled this job must be
        // observed here or the interrupted phrase would still play.
        currentCoroutineContext().ensureActive()
        val pcm = result.getOrElse { cause ->
            reportOnlineFailure(backend, cause, spokenOffline = delegate.isReady() && delegate.speak(text))
            return
        }
        val played = delegate.playPcm(pcm.samples, pcm.sampleRate)
        Log.i(TAG, "online pcm: samples=${pcm.samples.size} rate=${pcm.sampleRate} played=$played")
        if (!played) Log.w(TAG, "online playback failed; reply stays silent")
    }

    // One line per failed reply, at WARN so a user's log recording keeps the reason. The callback
    // runs on a speech coroutine whose scope has no exception handler: it must never escape.
    private fun reportOnlineFailure(backend: OnlineTtsBackend, cause: Throwable?, spokenOffline: Boolean) {
        Log.w(TAG, "online voice failed: backend=${backend.id} spoken_offline=$spokenOffline cause=$cause")
        runCatching { onOnlineFailure(OnlineTtsFailure(backend.id, cause, spokenOffline)) }
            .onFailure { Log.w(TAG, "online failure callback failed", it) }
    }

    override fun stop() {
        replaceInFlight(add = null) { true }
        delegate.stop()
    }

    override fun reload() = delegate.reload()

    /** Loads the offline model ahead of time only when it is the engine that will speak;
     *  with an online source selected the sherpa model would sit in memory unused, and the
     *  persona's short phrases are synthesized into the cache instead. */
    override fun warmUp() {
        val backend = onlineBackend()
        if (backend == null) delegate.warmUp() else scope.launch { precache(backend) }
    }

    // One request at a time, in the background: a burst would compete with the driver's first
    // turn and risk the provider's rate limit. The first failure (no network yet at ignition,
    // 429, auth, a "success" without audio) stops the pass; the remaining phrases get cached on
    // first use.
    // Phrases already on disk (the usual case after the first start with this voice) cost no
    // network call; only the missing ones are synthesized.
    private suspend fun precache(backend: OnlineTtsBackend) {
        if (!runCatching { backend.configured() }.getOrDefault(false)) return
        val phrases = runCatching { precachePhrases() }.getOrDefault(emptyList())
        val hits = mutableMapOf<String, Int>()
        val done = phrases.indexOfFirst { text ->
            val hit = cachedPcm(phraseKey(backend, selectedGender(), text))
            if (hit != null) hits.merge(hit.tier, 1, Int::plus)
            hit == null && synthesizeOnline(backend, text).getOrNull()?.samples?.isNotEmpty() != true
        }.let { if (it < 0) phrases.size else it }
        val memory = hits[TIER_MEMORY] ?: 0
        val disk = hits[TIER_DISK] ?: 0
        Log.i(
            TAG,
            "phrase precache: backend=${backend.id} phrases=${phrases.size} done=$done memory=$memory disk=$disk " +
                "synthesized=${done - memory - disk} stopped=${done < phrases.size} " +
                "cached=${synchronized(phraseCache) { phraseCache.size }}",
        )
    }

    override fun prewarmNetwork() {
        val backend = onlineBackend() ?: return
        scope.launch { runCatching { backend.prewarm() } }
    }

    override fun audible(): Boolean = delegate.audible()

    override fun playPcm(samples: FloatArray, sampleRate: Int): Boolean = delegate.playPcm(samples, sampleRate)

    override fun playPcmStream(chunks: BlockingQueue<FloatArray>, sampleRate: Int): Boolean =
        delegate.playPcmStream(chunks, sampleRate)

    /** For an online source, synthesis runs one sentence AHEAD of playback (prefetch), so the
     *  network round-trip of sentence N+1 overlaps the playback of sentence N -- this removes
     *  audible inter-sentence pauses. Playback order stays strict. A failed sentence and the rest
     *  of the reply go to the offline voice. */
    override fun startQueue(): TtsEngine.SpeechQueue? {
        val backend = onlineBackend()
        Log.i(TAG, "route: source=${backend?.id ?: OFFLINE}")
        if (backend == null) return delegate.startQueue()
        val queue = OnlineSpeechQueue(backend)
        // A new agent reply drops whatever online speech is still pending.
        replaceInFlight(InFlight(isQueue = true) { queue.cancel() }) { true }
        return queue
    }

    private fun onlineBackend(): OnlineTtsBackend? {
        val source = selectedSource()
        if (source == OFFLINE) return null
        return backends.find { it.id == source }
    }

    // One line per sentence: the network+synthesis leg of the reply latency, per backend.
    private suspend fun synthesizeOnline(backend: OnlineTtsBackend, text: String): Result<TtsPcm> {
        val gender = selectedGender()
        val key = phraseKey(backend, gender, text)
        cachedPcm(key)?.let {
            Log.i(TAG, "online synth: backend=${backend.id} chars=${text.length} cached=${it.tier}")
            return Result.success(it.pcm)
        }
        val startNs = System.nanoTime()
        val result = synthesizeCatching(backend, text, gender)
        val tookMs = elapsedMs(startNs)
        Log.i(TAG, "online synth: backend=${backend.id} chars=${text.length} ms=$tookMs ok=${result.isSuccess}")
        traceTts(backend, "synth", result.isSuccess, tookMs, tookMs)
        result.onSuccess { cacheIfKeyStable(backend, gender, text, key, it) }
        return result
    }

    /** [persist] is false when the backend cannot describe its voice: such a phrase could replay
     *  a stale voice after a settings change, so it stays in memory only. */
    private class PhraseKey(val value: String, val text: String, val persist: Boolean)

    private class CacheHit(val pcm: TtsPcm, val tier: String)

    private suspend fun phraseKey(backend: OnlineTtsBackend, gender: TtsGender, text: String): PhraseKey {
        val identity = runCatching { backend.voiceIdentity(gender) }.getOrNull()
        return PhraseKey("v$PHRASE_FORMAT|${backend.id}|$gender|${identity ?: "-"}|$text", text, identity != null)
    }

    // The voice identity a key carries can drift while synthesis is in flight (the user switches
    // provider or voice mid-request): caching under the pre-synthesis key would then persist audio
    // of the OLD voice under a key that, from now on, names the NEW voice -- permanently, since the
    // disk file survives restarts. Recomputing the key right after synthesis and requiring both to
    // match closes that window; a mismatch only skips caching, the audio just synthesized still plays.
    private suspend fun cacheIfKeyStable(backend: OnlineTtsBackend, gender: TtsGender, text: String, before: PhraseKey, pcm: TtsPcm) {
        val after = phraseKey(backend, gender, text)
        if (after.value != before.value) {
            Log.w(TAG, "voice identity changed mid-synthesis; not caching '${backend.id}' chars=${text.length}")
            return
        }
        cachePhrase(before, pcm)
    }

    // Memory first; a disk hit (one small file read, on the router's IO scope) is promoted to memory.
    private fun cachedPcm(key: PhraseKey): CacheHit? {
        synchronized(phraseCache) { phraseCache[key.value] }?.let { return CacheHit(it, TIER_MEMORY) }
        if (!key.persist || key.text.length > PHRASE_CACHE_MAX_CHARS) return null
        // runCatching: PhraseDiskCache handles IOException itself; anything else (SecurityException
        // etc.) must not escape into the speech coroutines, whose scope has no exception handler.
        val pcm = phraseDisk?.let { d ->
            runCatching { d.read(key.value) }.onFailure { Log.w(TAG, "phrase disk read failed", it) }.getOrNull()
        } ?: return null
        Log.i(TAG, "phrase cache: disk hit chars=${key.text.length} samples=${pcm.samples.size}")
        synchronized(phraseCache) { phraseCache[key.value] = pcm }
        return CacheHit(pcm, TIER_DISK)
    }

    // Empty audio (a provider "success" without samples) is never cached: it would pin silence.
    // The disk write runs detached, so it never delays the playback of the phrase just synthesized.
    private fun cachePhrase(key: PhraseKey, pcm: TtsPcm) {
        if (key.text.length > PHRASE_CACHE_MAX_CHARS || pcm.samples.isEmpty()) return
        synchronized(phraseCache) { phraseCache[key.value] = pcm }
        val disk = phraseDisk ?: return
        // Detached on a scope without an exception handler: an escaping throwable would crash the app.
        if (key.persist) scope.launch {
            runCatching { disk.write(key.value, pcm) }.onFailure { Log.w(TAG, "phrase disk write failed", it) }
        }
    }

    /** Test seam: entry count of the phrase cache. */
    internal fun phraseCacheSizeForTest(): Int = synchronized(phraseCache) { phraseCache.size }

    /** Streams one sentence into [chunks] (the engine plays them as they arrive); the end marker
     *  is put by the caller. A failure before the first chunk is retried as one whole-sentence
     *  synthesis; after audio started there is no retry. Null = the sentence was delivered
     *  completely. */
    private suspend fun streamSentence(
        backend: OnlineTtsBackend,
        text: String,
        gender: TtsGender,
        sampleRate: Int,
        chunks: BlockingQueue<FloatArray>,
    ): SentenceFailure? {
        val startNs = System.nanoTime()
        var firstChunkMs = -1L
        var count = 0
        val kept = if (text.length <= PHRASE_CACHE_MAX_CHARS) mutableListOf<FloatArray>() else null
        // Computed before the stream starts, like synthesizeOnline's key; cacheIfKeyStable
        // recomputes it after and only caches if the two agree (see its comment).
        val keyBefore = kept?.let { phraseKey(backend, gender, text) }
        val error = catchingWithTimeout {
            backend.synthesizeStream(text, gender) { samples ->
                if (samples.isNotEmpty()) {
                    if (count == 0) firstChunkMs = elapsedMs(startNs)
                    count++
                    kept?.add(samples)
                    chunks.put(samples)
                }
            }
        }.exceptionOrNull()
        if (error != null) Log.w(TAG, "online tts stream failed for '${backend.id}'", error)
        val ok = error == null
        val totalMs = elapsedMs(startNs)
        Log.i(
            TAG,
            "online stream: backend=${backend.id} chars=${text.length} first_chunk_ms=$firstChunkMs " +
                "total_ms=$totalMs chunks=$count ok=$ok",
        )
        traceTts(backend, "stream", ok, firstChunkMs, totalMs)
        if (ok && kept != null && keyBefore != null) {
            cacheIfKeyStable(backend, gender, text, keyBefore, TtsPcm(mergeChunks(kept), sampleRate))
        }
        return when {
            error == null -> null
            count > 0 -> SentenceFailure(error, heard = true)
            else -> wholeRetry(backend, text, sampleRate, chunks, error)
        }
    }

    /** Trace: a synthesis that failed, or kept the driver waiting for its first audio longer
     *  than [Trace.SLOW_CALL_MS]. A long sentence streams for long by design, so a stream is
     *  judged by its first chunk. */
    private fun traceTts(backend: OnlineTtsBackend, mode: String, ok: Boolean, firstAudioMs: Long, totalMs: Long) {
        if (ok && firstAudioMs <= Trace.SLOW_CALL_MS) return
        Trace.event(TraceArea.NET, "tts", "src" to backend.id, "mode" to mode, "ok" to ok,
            "first_audio_ms" to firstAudioMs, "ms" to totalMs)
    }

    // A retry that throws reports its own cause; one that returns unusable audio, the stream's.
    private suspend fun wholeRetry(
        backend: OnlineTtsBackend,
        text: String,
        sampleRate: Int,
        chunks: BlockingQueue<FloatArray>,
        streamError: Throwable,
    ): SentenceFailure? {
        val result = synthesizeOnline(backend, text)
        val pcm = result.getOrNull()
        val samples = pcm?.takeIf { it.sampleRate == sampleRate }?.samples?.takeIf { it.isNotEmpty() }
        Log.w(TAG, "online stream failed before first chunk; whole fallback: ok=${pcm != null} rate=${pcm?.sampleRate} usable=${samples != null}")
        samples?.let(chunks::put)
        return if (samples != null) null else SentenceFailure(result.exceptionOrNull() ?: streamError, heard = false)
    }

    private suspend fun synthesizeCatching(backend: OnlineTtsBackend, text: String, gender: TtsGender): Result<TtsPcm> =
        catchingWithTimeout { backend.synthesize(text, gender) }
            .onFailure { Log.w(TAG, "online tts synth failed for '${backend.id}'", it) }

    // Timeouts and ordinary failures come back as a failed Result (the caller switches to the
    // offline voice); a structural cancellation (stop() tearing down this job on barge-in) must
    // propagate instead, or the coroutine would carry on past the cancellation point and speak
    // the interrupted reply anyway. The backend's exception is caught inside withTimeout: rethrown
    // through it, it would arrive as a stack-trace-recovered copy instead of the original.
    private suspend fun <T> catchingWithTimeout(block: suspend () -> T): Result<T> =
        try {
            withTimeout(synthTimeoutMs) {
                try {
                    Result.success(block())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Result.failure(e)
                }
            }
        } catch (e: TimeoutCancellationException) {
            Result.failure(e)
        }

    /** For an online source, synthesis runs ahead of playback (prefetch), so the network
     *  round-trip of sentence N+1 overlaps the playback of sentence N -- this removes the audible
     *  inter-sentence pauses. Playback order stays strict: the single player coroutine consumes
     *  the synthesized sentences in send order. A backend that can stream plays each sentence
     *  from its first network chunk (cache hits stay whole). The first failed/timed-out sentence
     *  (unless part of it was already heard) and every later one go, in order, to one offline
     *  queue; no new online request starts once the failure is known (requests already
     *  prefetched are cancelled). A playback failure silences the rest. */
    private inner class OnlineSpeechQueue(private val backend: OnlineTtsBackend) : TtsEngine.SpeechQueue {
        private val pending = Channel<String>(Channel.UNLIMITED)
        private val synthesized = Channel<QueuedSentence>(capacity = 1)
        // Guarded by this queue's monitor together with every hand-off to [offline], so after
        // cancel() returns no sentence reaches the offline voice.
        @Volatile private var superseded = false
        // Set by a sentence's synthesis the moment it fails: later sentences skip the network.
        @Volatile private var onlineFailed = false
        // Player-confined; [offline] is written under the monitor (cancel() reads it there) and
        // read by awaitPlayed() after joining the player.
        private var fellBack = false
        private var silenced = false
        private var offline: TtsEngine.SpeechQueue? = null

        private val synthJob: Job = scope.launch {
            for (text in pending) {
                val sentence = if (onlineFailed) OfflineSentence(text) else startSynthesis(text)
                synthesized.send(sentence)
            }
            synthesized.close()
        }

        private suspend fun CoroutineScope.startSynthesis(text: String): QueuedSentence {
            val gender = selectedGender()
            val rate = if (cachedPcm(phraseKey(backend, gender, text)) == null) backend.streamSampleRate() else null
            return if (rate == null) {
                WholeSentence(text, async {
                    synthesizeOnline(backend, text).onFailure { onlineFailed = true }
                })
            } else {
                val chunks = LinkedBlockingQueue<FloatArray>()
                val outcome = CompletableDeferred<SentenceFailure?>()
                // The outcome is published BEFORE the end marker, so a player that read the marker
                // always finds it; the marker follows whatever happens.
                StreamedSentence(text, chunks, rate, outcome, launch {
                    try {
                        val failure = streamSentence(backend, text, gender, rate, chunks)
                        if (failure != null) onlineFailed = true
                        outcome.complete(failure)
                    } finally {
                        chunks.put(END_OF_STREAM)
                    }
                })
            }
        }

        private val playJob: Job = scope.launch {
            try {
                for (sentence in synthesized) {
                    if (silenced || fellBack) {
                        sentence.synthesis?.cancel()
                        if (fellBack) speakOffline(sentence.text)
                        continue
                    }
                    val failure = playOnline(sentence) ?: continue
                    fallBack(failure)
                    if (!failure.heard) speakOffline(sentence.text)
                }
            } finally {
                offline?.finish()
            }
        }

        /** Plays [sentence] online; returns its online failure, or null (played, or a playback
         *  failure that set [silenced]). */
        private suspend fun playOnline(sentence: QueuedSentence): SentenceFailure? = when (sentence) {
            is OfflineSentence -> SentenceFailure(null, heard = false)
            is WholeSentence -> playWhole(sentence)
            is StreamedSentence -> playStreamed(sentence)
        }

        private suspend fun playWhole(sentence: WholeSentence): SentenceFailure? {
            val pcm = sentence.pcm.await().getOrElse { return SentenceFailure(it, heard = false) }
            currentCoroutineContext().ensureActive() // see speakOnline
            val played = delegate.playPcm(pcm.samples, pcm.sampleRate)
            Log.i(TAG, "online pcm: samples=${pcm.samples.size} rate=${pcm.sampleRate} played=$played")
            if (!played) silenced = true
            return null
        }

        private suspend fun playStreamed(sentence: StreamedSentence): SentenceFailure? {
            currentCoroutineContext().ensureActive() // see speakOnline
            val played = delegate.playPcmStream(sentence.chunks, sentence.sampleRate)
            // The outcome is published before the end marker, so a player that read the marker
            // finds it; one that gave up earlier (dead route, stalled stream) finds none and
            // cancels the stream still running.
            if (!played && !sentence.outcome.isCompleted) sentence.job.cancel()
            sentence.job.join()
            val failure = if (sentence.outcome.isCompleted) sentence.outcome.await() else null
            Log.i(TAG, "online pcm stream: rate=${sentence.sampleRate} played=$played streamed=${failure == null}")
            // Not played with nothing heard is the empty stream of a failed synthesis; otherwise
            // the audio device refused it: a playback failure, silent as before.
            if (!played && failure?.heard != false) {
                silenced = true
                return null
            }
            return failure
        }

        private fun fallBack(failure: SentenceFailure) {
            fellBack = true
            onlineFailed = true
            val queue = synchronized(this) {
                (if (superseded || !delegate.isReady()) null else delegate.startQueue()).also { offline = it }
            }
            reportOnlineFailure(backend, failure.cause, spokenOffline = queue != null)
        }

        private fun speakOffline(text: String) {
            synchronized(this) { if (!superseded) offline?.enqueue(text) }
        }

        override fun enqueue(text: String): Boolean {
            if (text.isBlank() || superseded) return false
            return pending.trySend(text).isSuccess
        }

        override fun finish() { pending.close() }

        // The player ends once the last sentence was played or handed to the offline queue (or the
        // queue was cancelled); the offline queue then plays its share. Each sentence's
        // speaking=false in the delegate is only the end of that one sentence.
        override suspend fun awaitPlayed() {
            playJob.join()
            offline?.awaitPlayed()
        }

        /** stop() or a newer reply cancels the whole queue: both coroutines are torn down
         *  immediately (even mid-synthesis), nothing more reaches the offline queue and further
         *  enqueue() calls are rejected. Speech already handed to the offline voice is cut too;
         *  a queue that never fell back leaves the delegate alone, as before. */
        fun cancel() {
            val handedOff = synchronized(this) {
                superseded = true
                offline != null
            }
            synthJob.cancel()
            playJob.cancel()
            pending.close()
            synthesized.close()
            if (handedOff) delegate.stop()
        }
    }

    /** Why a sentence did not come out online; [heard] = part of it already played. */
    private class SentenceFailure(val cause: Throwable?, val heard: Boolean)

    /** One reply sentence in playback order: whole PCM, chunks streamed in by
     *  [StreamedSentence.job], or (after an online failure) text for the offline voice. */
    private sealed interface QueuedSentence {
        val text: String
        val synthesis: Job?
    }
    private class WholeSentence(override val text: String, val pcm: Deferred<Result<TtsPcm>>) : QueuedSentence {
        override val synthesis: Job get() = pcm
    }
    private class StreamedSentence(
        override val text: String,
        val chunks: BlockingQueue<FloatArray>,
        val sampleRate: Int,
        val outcome: CompletableDeferred<SentenceFailure?>,
        val job: Job,
    ) : QueuedSentence {
        override val synthesis: Job get() = job
    }
    private class OfflineSentence(override val text: String) : QueuedSentence {
        override val synthesis: Job? get() = null
    }

    companion object {
        private const val TAG = "TtsRouter"
        const val OFFLINE = "offline"

        // TtsEngine.playPcmStream's end-of-stream marker.
        private val END_OF_STREAM = FloatArray(0)

        private fun elapsedMs(startNs: Long): Long = (System.nanoTime() - startNs) / 1_000_000

        private fun mergeChunks(chunks: List<FloatArray>): FloatArray {
            val merged = FloatArray(chunks.sumOf { it.size })
            var offset = 0
            for (chunk in chunks) {
                chunk.copyInto(merged, offset)
                offset += chunk.size
            }
            return merged
        }

        // Safety net over the backends' own network timeouts (OpenRouter callTimeout=15s;
        // MiniMax official has none). Cloud TTS renders a whole sentence before responding,
        // so long sentences legitimately take many seconds -- a tight cap here silences
        // every long reply (field defect APK 346: 2s cap killed all long Gemini answers).
        const val SYNTH_TIMEOUT_MS = 18_000L

        // The longest persona phrase is 37 chars; agent replies are longer (median 59).
        internal const val PHRASE_CACHE_MAX_CHARS = 40
        // Three persona pools of ~13 phrases fit with room for both genders of one pool.
        internal const val PHRASE_CACHE_ENTRIES = 48
        // Part of every phrase key: bump when the stored format or the synthesis request changes
        // so phrases persisted by an older build are never replayed.
        // v2: file header gained a CRC32 check, see PhraseDiskCache.
        // v3: a take is frozen once cached; re-roll every phrase after the filler text change.
        private const val PHRASE_FORMAT = 3
        private const val TIER_MEMORY = "memory"
        private const val TIER_DISK = "disk"
    }
}
