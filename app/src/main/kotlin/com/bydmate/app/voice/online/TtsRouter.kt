package com.bydmate.app.voice.online

import android.util.Log
import com.bydmate.app.voice.TtsEngine
import com.bydmate.app.voice.TtsGender
import kotlinx.coroutines.CancellationException
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
 *  speaks through a cloud [OnlineTtsBackend]. There is NO offline fallback: if the online backend
 *  fails or times out, the reply stays SILENT (its text remains visible in the orb dialog). The
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
) : TtsEngine {

    override val speaking: StateFlow<Boolean> = delegate.speaking

    // Cancels whatever online work (a single speak() or a queue) is currently in flight, so
    // stop() -- called on barge-in -- can never let a sentence still awaiting synthesis play
    // out after the caller already considers speech stopped.
    @Volatile private var cancelActive: (() -> Unit)? = null

    // Short phrases (persona confirmations) replay from memory instead of paying a network
    // round-trip every time. The key carries source + gender + voice identity + text, so a switch
    // of voice or source never replays the old voice; LRU-bounded so stray short replies cannot
    // grow it. Disk is the second level: a phrase survives restarts and is synthesized once per voice.
    private val phraseDisk = phraseDir?.let(::PhraseDiskCache)
    private val phraseCache = object : LinkedHashMap<String, TtsPcm>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TtsPcm>) =
            size > PHRASE_CACHE_ENTRIES
    }

    /** Online source is ready when its backend is configured. There is no offline fallback
     *  any more (user contract: it either works or it does not), so the local model's
     *  presence is irrelevant; source "offline" still follows the delegate. */
    override fun isReady(): Boolean {
        val backend = onlineBackend() ?: return delegate.isReady()
        return runCatching { runBlocking { backend.configured() } }.getOrDefault(false)
    }

    override fun speak(text: String): Boolean {
        if (text.isBlank()) return false
        val backend = onlineBackend()
        Log.i(TAG, "route: source=${backend?.id ?: OFFLINE}")
        if (backend == null) return delegate.speak(text)
        val job = scope.launch { speakOnline(backend, text) }
        cancelActive = { job.cancel() }
        return true
    }

    /** Preview of a LOCAL voice row must bypass the online source and always speak through
     *  the offline delegate, regardless of which source is currently selected. */
    override fun speakOffline(text: String): Boolean = delegate.speak(text)

    private suspend fun speakOnline(backend: OnlineTtsBackend, text: String) {
        val pcm = synthesizeOrNull(backend, text)
        // A cache hit never suspends, so a stop() that already cancelled this job must be
        // observed here or the interrupted phrase would still play.
        currentCoroutineContext().ensureActive()
        val played = pcm != null && delegate.playPcm(pcm.samples, pcm.sampleRate)
        Log.i(TAG, "online pcm: samples=${pcm?.samples?.size} rate=${pcm?.sampleRate} played=$played")
        if (!played) Log.w(TAG, "online speak failed; reply stays silent (no fallback)")
    }

    override fun stop() {
        cancelActive?.invoke()
        cancelActive = null
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
            hit == null && synthesizeOrNull(backend, text)?.samples?.isNotEmpty() != true
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
     *  audible inter-sentence pauses. Playback order stays strict. There is NO offline fallback:
     *  a failed sentence silences the rest of the reply. */
    override fun startQueue(): TtsEngine.SpeechQueue? {
        val backend = onlineBackend()
        Log.i(TAG, "route: source=${backend?.id ?: OFFLINE}")
        if (backend == null) return delegate.startQueue()
        val queue = OnlineSpeechQueue(backend)
        cancelActive = { queue.cancel() }
        return queue
    }

    private fun onlineBackend(): OnlineTtsBackend? {
        val source = selectedSource()
        if (source == OFFLINE) return null
        return backends.find { it.id == source }
    }

    // One line per sentence: the network+synthesis leg of the reply latency, per backend.
    private suspend fun synthesizeOrNull(backend: OnlineTtsBackend, text: String): TtsPcm? {
        val gender = selectedGender()
        val key = phraseKey(backend, gender, text)
        cachedPcm(key)?.let {
            Log.i(TAG, "online synth: backend=${backend.id} chars=${text.length} cached=${it.tier}")
            return it.pcm
        }
        val startNs = System.nanoTime()
        val pcm = synthesizeCatching(backend, text, gender)
        Log.i(TAG, "online synth: backend=${backend.id} chars=${text.length} ms=${elapsedMs(startNs)} ok=${pcm != null}")
        if (pcm != null) cacheIfKeyStable(backend, gender, text, key, pcm)
        return pcm
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
     *  always follows, whatever happens. A failure before the first chunk falls back to one
     *  whole-sentence synthesis; after audio started there is no fallback. True = the sentence
     *  was delivered completely. */
    private suspend fun streamSentence(
        backend: OnlineTtsBackend,
        text: String,
        gender: TtsGender,
        sampleRate: Int,
        chunks: BlockingQueue<FloatArray>,
    ): Boolean {
        val startNs = System.nanoTime()
        var firstChunkMs = -1L
        var count = 0
        val kept = if (text.length <= PHRASE_CACHE_MAX_CHARS) mutableListOf<FloatArray>() else null
        // Computed before the stream starts, like synthesizeOrNull's key; cacheIfKeyStable
        // recomputes it after and only caches if the two agree (see its comment).
        val keyBefore = kept?.let { phraseKey(backend, gender, text) }
        try {
            val ok = try {
                withTimeout(synthTimeoutMs) {
                    backend.synthesizeStream(text, gender) { samples ->
                        if (samples.isNotEmpty()) {
                            if (count == 0) firstChunkMs = elapsedMs(startNs)
                            count++
                            kept?.add(samples)
                            chunks.put(samples)
                        }
                    }
                }
                true
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "online tts stream failed for '${backend.id}'", e)
                false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "online tts stream failed for '${backend.id}'", e)
                false
            }
            Log.i(
                TAG,
                "online stream: backend=${backend.id} chars=${text.length} first_chunk_ms=$firstChunkMs " +
                    "total_ms=${elapsedMs(startNs)} chunks=$count ok=$ok",
            )
            if (ok && kept != null && keyBefore != null) {
                cacheIfKeyStable(backend, gender, text, keyBefore, TtsPcm(mergeChunks(kept), sampleRate))
            }
            return when {
                ok -> true
                count > 0 -> false
                else -> wholeFallback(backend, text, sampleRate, chunks)
            }
        } finally {
            chunks.put(END_OF_STREAM)
        }
    }

    private suspend fun wholeFallback(
        backend: OnlineTtsBackend,
        text: String,
        sampleRate: Int,
        chunks: BlockingQueue<FloatArray>,
    ): Boolean {
        val pcm = synthesizeOrNull(backend, text)
        val samples = pcm?.takeIf { it.sampleRate == sampleRate }?.samples?.takeIf { it.isNotEmpty() }
        Log.w(TAG, "online stream failed before first chunk; whole fallback: ok=${pcm != null} rate=${pcm?.sampleRate} usable=${samples != null}")
        samples?.let(chunks::put)
        return samples != null
    }

    // Timeouts and ordinary failures return null (reply stays silent); a structural cancellation
    // (stop() tearing down this job on barge-in) must propagate instead, or the coroutine would
    // carry on past the cancellation point and speak the interrupted reply anyway.
    private suspend fun synthesizeCatching(backend: OnlineTtsBackend, text: String, gender: TtsGender): TtsPcm? =
        try {
            withTimeout(synthTimeoutMs) { backend.synthesize(text, gender) }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "online tts synth failed for '${backend.id}'", e)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "online tts synth failed for '${backend.id}'", e)
            null
        }

    /** For an online source, synthesis runs one sentence AHEAD of playback (prefetch), so the
     *  network round-trip of sentence N+1 overlaps the playback of sentence N -- this removes
     *  the audible inter-sentence pauses. Playback order stays strict: the single player
     *  coroutine consumes the synthesized sentences in send order. A backend that can stream
     *  plays each sentence from its first network chunk (cache hits stay whole). There is NO
     *  offline fallback: the first failed/timed-out sentence silences the rest of the reply (its
     *  text is still shown in the orb dialog). */
    private inner class OnlineSpeechQueue(private val backend: OnlineTtsBackend) : TtsEngine.SpeechQueue {
        private val pending = Channel<String>(Channel.UNLIMITED)
        private val synthesized = Channel<QueuedSentence>(capacity = 1)
        @Volatile private var superseded = false

        private val synthJob: Job = scope.launch {
            for (text in pending) {
                val gender = selectedGender()
                val rate = if (cachedPcm(phraseKey(backend, gender, text)) == null) backend.streamSampleRate() else null
                val sentence = if (rate == null) {
                    WholeSentence(async { synthesizeOrNull(backend, text) })
                } else {
                    val chunks = LinkedBlockingQueue<FloatArray>()
                    StreamedSentence(chunks, rate, async { streamSentence(backend, text, gender, rate, chunks) })
                }
                synthesized.send(sentence)
            }
            synthesized.close()
        }

        private val playJob: Job = scope.launch {
            var failed = false
            for (sentence in synthesized) {
                when (sentence) {
                    is WholeSentence -> {
                        val pcm = sentence.pcm.await()
                        if (failed) continue
                        if (pcm == null) {
                            Log.w(TAG, "online synth failed; silencing the rest of the reply (no fallback)")
                            failed = true
                            continue
                        }
                        currentCoroutineContext().ensureActive() // see speakOnline
                        val played = delegate.playPcm(pcm.samples, pcm.sampleRate)
                        Log.i(TAG, "online pcm: samples=${pcm.samples.size} rate=${pcm.sampleRate} played=$played")
                        if (!played) failed = true
                    }
                    is StreamedSentence -> if (failed) sentence.job.cancel() else failed = !playStreamed(sentence)
                }
            }
        }

        private suspend fun playStreamed(sentence: StreamedSentence): Boolean {
            currentCoroutineContext().ensureActive() // see speakOnline
            val played = delegate.playPcmStream(sentence.chunks, sentence.sampleRate)
            if (!played) sentence.job.cancel()
            val streamed = played && sentence.job.await()
            Log.i(TAG, "online pcm stream: rate=${sentence.sampleRate} played=$played streamed=$streamed")
            if (!streamed) Log.w(TAG, "online stream failed; silencing the rest of the reply (no fallback)")
            return streamed
        }

        override fun enqueue(text: String): Boolean {
            if (text.isBlank() || superseded) return false
            return pending.trySend(text).isSuccess
        }

        override fun finish() { pending.close() }

        /** stop() on the router cancels the whole queue: both coroutines are torn down
         *  immediately (even mid-synthesis) and further enqueue() calls are rejected. */
        fun cancel() {
            superseded = true
            synthJob.cancel()
            playJob.cancel()
            pending.close()
            synthesized.close()
        }
    }

    /** One reply sentence in playback order: whole PCM, or chunks streamed in by [StreamedSentence.job]. */
    private sealed interface QueuedSentence
    private class WholeSentence(val pcm: Deferred<TtsPcm?>) : QueuedSentence
    private class StreamedSentence(
        val chunks: BlockingQueue<FloatArray>,
        val sampleRate: Int,
        val job: Deferred<Boolean>,
    ) : QueuedSentence

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
        private const val PHRASE_FORMAT = 2 // v2: file header gained a CRC32 check, see PhraseDiskCache
        private const val TIER_MEMORY = "memory"
        private const val TIER_DISK = "disk"
    }
}
