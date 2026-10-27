package com.bydmate.app.voice

import android.content.SharedPreferences
import android.util.Log
import com.bydmate.app.data.vehicle.AutoVoiceReply
import com.bydmate.app.helper.HelperBinderProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Rollback switch of the firmware voice duck (#353): false puts every car back on the plain
 * media duck (AudioCapture.duckMusic). A marker left by an earlier version is still cleared
 * at service start.
 */
internal const val FIRMWARE_VOICE_DUCK_ENABLED = true

/**
 * Firmware voice duck, the stock assistant's way (#353). On cars with an external amplifier the
 * amp gets one volume, that of the active stream, which stays media: AudioCapture.duckMusic()
 * turns the amp itself down to the duck index and our reply on stream 17 is inaudible. The
 * stock assistant's call (TX_SET_AUTO_VOICE) ducks media at track level and makes stream 17
 * active instead; verified on Song L (external amp) and Leopard 3.
 *
 * [activate] takes a [Lease] and owns the media fallback: when the daemon has not applied the
 * firmware duck within the budget it runs the caller's media duck, and when the daemon applies
 * it later the media duck is restored, so a session never has both. Leases nest like the media
 * duck (session, speak): the first one calls the daemon, the last [deactivate] turns it off. An
 * UNSUPPORTED verdict holds for the process lifetime, so a firmware without the method is asked
 * once.
 *
 * Daemon calls run in order on [scope], never on the caller: a call in flight cannot delay the
 * listening start past the budget. A marker persisted before the on call and cleared after a
 * definite answer to an off call keeps the off call coming: retried with [offRetryDelaysMs],
 * again on every daemon arrival and service start ([recoverStuck]), until it lands.
 */
@Suppress("TooManyFunctions") // one state machine under one lock: its transitions stay together
class AutoVoiceDuck(
    private val setActive: suspend (Boolean) -> AutoVoiceReply?,
    private val prefs: SharedPreferences?,
    initialVerdict: Int? = null,
    private val activateTimeoutMs: Long = ACTIVATE_TIMEOUT_MS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val offRetryDelaysMs: List<Long> = OFF_RETRY_DELAYS_MS,
) {
    companion object {
        private const val TAG = "AutoVoiceDuck"
        // Short: the orb, the mic and the media duck wait for this answer at most.
        internal const val ACTIVATE_TIMEOUT_MS = 250L
        // A failed off call is retried after these delays (~1 min in all), then waits for the
        // next daemon arrival or service start.
        internal val OFF_RETRY_DELAYS_MS = listOf(2_000L, 5_000L, 15_000L, 40_000L)
        internal const val KEY_MARKER = "auto_voice_marker"
        internal const val KEY_LAST = "auto_voice_last"

        /** Never asks the daemon: the default for callers built without one (unit tests). */
        val OFF = AutoVoiceDuck({ null }, null, HelperBinderProtocol.AUTO_VOICE_UNSUPPORTED)

        internal fun statusName(reply: AutoVoiceReply?): String = when (reply?.outcome) {
            null -> "NO_ANSWER"
            HelperBinderProtocol.AUTO_VOICE_APPLIED -> "APPLIED"
            HelperBinderProtocol.AUTO_VOICE_UNSUPPORTED -> "UNSUPPORTED"
            else -> "FAILED"
        }
    }

    /** One taken level of the duck; hand it back to [deactivate] exactly once. All fields are
     *  guarded by the duck's state lock. */
    class Lease internal constructor(internal val level: Boolean, private val restoreMedia: (Int?) -> Unit) {
        internal var mediaDucked = false
        internal var mediaSaved: Int? = null
        internal var released = false

        /** Gives the media duck of this lease back, once. */
        internal fun restoreMediaDuck() {
            if (!mediaDucked) return
            mediaDucked = false
            val saved = mediaSaved
            mediaSaved = null
            runCatching { restoreMedia(saved) }
        }
    }

    // Guards every field below; never held across a suspension.
    private val state = Any()
    // UNSUPPORTED once the daemon said so; null = still worth asking.
    @Volatile private var verdict: Int? = initialVerdict
    private var depth = 0
    // Bumped by every first level: tells an answer for an ended session from the live one's.
    private var nesting = 0
    private val leases = mutableListOf<Lease>()
    @Volatile private var applied = false
    // In-memory copy of KEY_MARKER (prefs may be null).
    @Volatile private var marker = prefs?.getBoolean(KEY_MARKER, false) == true
    private var retryJob: Job? = null

    // Daemon calls, strictly in the order they were queued. Every call is queued under [state],
    // with the transition it belongs to, so on/off calls cannot overtake each other.
    private val calls = SerialCalls(scope)

    /** False when the firmware duck is off or this firmware lacks it: duck the media at once. */
    fun mayApply(): Boolean = FIRMWARE_VOICE_DUCK_ENABLED && verdict == null

    /** The firmware owns the duck right now: inner media ducks must stay off. */
    fun isActive(): Boolean = applied

    /**
     * Takes a level and waits at most [activateTimeoutMs] for the daemon (first level only).
     * Unless the firmware duck is applied by then, runs [duckMedia]; its saved volume goes back
     * through [restoreMedia] on [deactivate], or as soon as a late apply lands. Cancelled while
     * waiting, it gives the level back itself and the caller holds nothing.
     */
    suspend fun activate(duckMedia: () -> Int?, restoreMedia: (Int?) -> Unit): Lease {
        var call: Job? = null
        val lease = synchronized(state) {
            if (!mayApply()) return@synchronized Lease(level = false, restoreMedia)
            val lease = Lease(level = true, restoreMedia)
            leases += lease
            depth++
            if (depth == 1) {
                nesting++
                // A new session owns the state again: no off retry may land under it.
                retryJob?.cancel()
                retryJob = null
                val n = nesting
                call = calls.enqueue { onCall(n) }
            }
            lease
        }
        try {
            call?.let { job ->
                if (withTimeoutOrNull(activateTimeoutMs) { job.join() } == null) {
                    Log.i(TAG, "activate: no answer in $activateTimeoutMs ms, media duck; a late apply still counts")
                }
            }
        } catch (e: CancellationException) {
            deactivate(lease)
            throw e
        }
        synchronized(state) {
            if (!lease.released && !(lease.level && applied)) {
                lease.mediaDucked = true
                lease.mediaSaved = runCatching(duckMedia).getOrNull()
            }
        }
        return lease
    }

    /**
     * An inner media duck inside a live nesting (the capture session's own duck), decided under
     * the same lock as a late apply, so that apply undoes it too and both ducks are never on at
     * once. Applied: nothing is ducked. Null when no level is held (firmware duck off, cached
     * UNSUPPORTED, no live session): the caller ducks the media itself. Release with [deactivate].
     */
    fun nest(duckMedia: () -> Int?, restoreMedia: (Int?) -> Unit): Lease? = synchronized(state) {
        if (depth == 0) return null
        val lease = Lease(level = true, restoreMedia)
        leases += lease
        depth++
        if (!applied) {
            lease.mediaDucked = true
            lease.mediaSaved = runCatching(duckMedia).getOrNull()
        }
        lease
    }

    /** Gives [lease] back: restores its media duck, and the last level turns the firmware state
     *  off. A second call with the same lease is a no-op. */
    fun deactivate(lease: Lease): Unit = synchronized(state) {
        if (lease.released) return
        lease.released = true
        lease.restoreMediaDuck()
        if (!lease.level) return
        leases -= lease
        depth--
        if (depth > 0) return
        applied = false
        retryJob?.cancel()
        retryJob = null
        calls.enqueue { offCall("deactivate", 0) }
    }

    /** Service start and every daemon arrival: a marker means the firmware duck may still be on
     *  (a process death, or off calls that never landed). Turns it off unless a live session in
     *  this process owns it. */
    fun recoverStuck(): Unit = synchronized(state) {
        if (depth > 0) {
            Log.i(TAG, "recoverStuck: skipped, owned by a live session in this process")
            return
        }
        if (!marker) return
        retryJob?.cancel()
        retryJob = null
        calls.enqueue { offCall("recover", 0) }
    }

    private suspend fun onCall(n: Int) {
        if (synchronized(state) { nesting != n || depth == 0 }) {
            Log.i(TAG, "activate: session ended before the call, not sent")
            return
        }
        writeMarker(true)
        val reply = ask(true, "activate")
        synchronized(state) {
            val live = nesting == n && depth > 0
            // Late apply: callers that already fell back to the media duck get it restored, the
            // firmware one alone stays for the rest of this nesting.
            val fellBack = leases.filter { it.mediaDucked }
            when (reply?.outcome) {
                HelperBinderProtocol.AUTO_VOICE_APPLIED -> if (live) {
                    applied = true
                    fellBack.forEach { it.restoreMediaDuck() }
                }
                HelperBinderProtocol.AUTO_VOICE_UNSUPPORTED -> {
                    verdict = reply.outcome
                    writeMarker(false)
                }
                // FAILED or no answer: the daemon may still have applied it, so the off call
                // at session end is sent anyway.
                else -> Unit
            }
            Log.i(TAG, "activate: ${statusName(reply)} ${reply?.detail.orEmpty()} -> " + when {
                !live -> "session already ended, the queued off call follows"
                !applied -> "media duck"
                fellBack.isEmpty() -> "firmware duck, media volume left alone"
                else -> "firmware duck applied late, media duck restored for ${fellBack.size} level(s)"
            })
        }
    }

    private suspend fun offCall(what: String, attempt: Int) {
        val proceed = synchronized(state) {
            if (depth > 0) Log.i(TAG, "$what: off call skipped, a new session owns the state")
            depth == 0 && marker
        }
        if (!proceed) return
        val reply = ask(false, what)
        when (reply?.outcome) {
            HelperBinderProtocol.AUTO_VOICE_APPLIED -> writeMarker(false)
            HelperBinderProtocol.AUTO_VOICE_UNSUPPORTED -> {
                verdict = reply.outcome
                writeMarker(false)
            }
            else -> Unit
        }
        Log.i(TAG, "$what: ${statusName(reply)} ${reply?.detail.orEmpty()}" +
            if (marker) " (marker kept)" else "")
        if (marker) scheduleRetry(attempt)
    }

    private fun scheduleRetry(attempt: Int): Unit = synchronized(state) {
        if (depth > 0) return
        if (attempt >= offRetryDelaysMs.size) {
            Log.w(TAG, "off call: gave up after $attempt retries; the next daemon arrival or service start retries")
            return
        }
        val delayMs = offRetryDelaysMs[attempt]
        Log.i(TAG, "off call: retry ${attempt + 1}/${offRetryDelaysMs.size} in $delayMs ms")
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(delayMs)
            synchronized(state) { if (depth == 0) calls.enqueue { offCall("retry ${attempt + 1}", attempt + 1) } }
        }
    }

    /** One daemon call; its status is persisted for the dump. */
    private suspend fun ask(active: Boolean, what: String): AutoVoiceReply? {
        val reply = runCatching { setActive(active) }.getOrNull()
        prefs?.edit()?.putString(KEY_LAST, "$what ${statusName(reply)} ${reply?.detail.orEmpty()}".trim())?.apply()
        return reply
    }

    /** Set before an on call (committed: it must survive a death mid-call), cleared after a
     *  definite answer. */
    private fun writeMarker(set: Boolean) {
        marker = set
        if (set) prefs?.edit()?.putBoolean(KEY_MARKER, true)?.commit() else prefs?.edit()?.remove(KEY_MARKER)?.apply()
    }

    /** Runs the queued tasks one at a time, in order, on [scope]. */
    private class SerialCalls(scope: CoroutineScope) {
        private val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
        private val worker = scope.launch(start = CoroutineStart.LAZY) { for (task in queue) task() }

        /** The returned job completes once [task] ran. */
        fun enqueue(task: suspend () -> Unit): Job {
            val done = Job()
            queue.trySend {
                try {
                    runCatching { task() }.onFailure { Log.w(TAG, "daemon call threw: ${it.message}") }
                } finally {
                    done.complete()
                }
            }
            worker.start()
            return done
        }
    }
}
