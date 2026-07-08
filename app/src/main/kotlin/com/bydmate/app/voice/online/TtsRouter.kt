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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Wraps an offline [TtsEngine] (the Sherpa/Piper voice) and, when an online source is selected,
 *  speaks through a cloud [OnlineTtsBackend]. There is NO offline fallback: if the online backend
 *  fails or times out, the reply stays SILENT (its text remains visible in the orb dialog). The
 *  offline engine is always the delegate for stop/speaking/audible/reload, so barge-in and the
 *  mic-mute machinery stay exactly as they are today regardless of which source spoke. */
class TtsRouter(
    private val delegate: TtsEngine,
    private val backends: List<OnlineTtsBackend> = emptyList(),
    private val selectedSource: () -> String = { OFFLINE },
    private val selectedGender: () -> TtsGender = { TtsGender.MALE },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val synthTimeoutMs: Long = SYNTH_TIMEOUT_MS,
) : TtsEngine {

    override val speaking: StateFlow<Boolean> = delegate.speaking

    // Cancels whatever online work (a single speak() or a queue) is currently in flight, so
    // stop() -- called on barge-in -- can never let a sentence still awaiting synthesis play
    // out after the caller already considers speech stopped.
    @Volatile private var cancelActive: (() -> Unit)? = null

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

    override fun audible(): Boolean = delegate.audible()

    override fun playPcm(samples: FloatArray, sampleRate: Int): Boolean = delegate.playPcm(samples, sampleRate)

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

    // Timeouts and ordinary failures return null (reply stays silent); a structural cancellation
    // (stop() tearing down this job on barge-in) must propagate instead, or the coroutine would
    // carry on past the cancellation point and speak the interrupted reply anyway.
    private suspend fun synthesizeOrNull(backend: OnlineTtsBackend, text: String): TtsPcm? =
        try {
            withTimeout(synthTimeoutMs) { backend.synthesize(text, selectedGender()) }
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
     *  coroutine consumes the synthesized deferreds in send order. There is NO offline
     *  fallback: the first failed/timed-out sentence silences the rest of the reply (its text
     *  is still shown in the orb dialog). */
    private inner class OnlineSpeechQueue(private val backend: OnlineTtsBackend) : TtsEngine.SpeechQueue {
        private val pending = Channel<String>(Channel.UNLIMITED)
        private val synthesized = Channel<Deferred<TtsPcm?>>(capacity = 1)
        @Volatile private var superseded = false

        private val synthJob: Job = scope.launch {
            for (text in pending) synthesized.send(async { synthesizeOrNull(backend, text) })
            synthesized.close()
        }

        private val playJob: Job = scope.launch {
            var failed = false
            for (d in synthesized) {
                val pcm = d.await()
                if (failed) continue
                if (pcm == null) {
                    Log.w(TAG, "online synth failed; silencing the rest of the reply (no fallback)")
                    failed = true
                    continue
                }
                val played = delegate.playPcm(pcm.samples, pcm.sampleRate)
                Log.i(TAG, "online pcm: samples=${pcm.samples.size} rate=${pcm.sampleRate} played=$played")
                if (!played) failed = true
            }
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

    companion object {
        private const val TAG = "TtsRouter"
        const val OFFLINE = "offline"

        // Safety net over the backends' own network timeouts (OpenRouter callTimeout=15s;
        // MiniMax official has none). Cloud TTS renders a whole sentence before responding,
        // so long sentences legitimately take many seconds -- a tight cap here silences
        // every long reply (field defect APK 346: 2s cap killed all long Gemini answers).
        const val SYNTH_TIMEOUT_MS = 18_000L
    }
}
