package com.bydmate.app.voice

import android.content.SharedPreferences
import android.util.Log
import com.bydmate.app.data.vehicle.AutoVoiceReply
import com.bydmate.app.helper.HelperBinderProtocol
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * [activate] answers true only when the daemon applied it; then the caller leaves STREAM_MUSIC
 * alone. Every other answer keeps the caller on duckMusic(). An UNSUPPORTED verdict holds for
 * the process lifetime, so a firmware without the method is asked once. Activations nest like
 * the media duck (session, speak): the first one calls the daemon, the last [deactivate] turns
 * it off. A marker persisted before the call and cleared after a definite answer to the
 * deactivate lets [recoverStuck] turn it off after a process death.
 */
class AutoVoiceDuck(
    private val setActive: suspend (Boolean) -> AutoVoiceReply?,
    private val prefs: SharedPreferences?,
    initialVerdict: Int? = null,
    private val activateTimeoutMs: Long = ACTIVATE_TIMEOUT_MS,
) {
    companion object {
        private const val TAG = "AutoVoiceDuck"
        // Short: the media duck waits for this answer when the daemon does not apply it.
        internal const val ACTIVATE_TIMEOUT_MS = 250L
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

    private val lock = Mutex()
    // UNSUPPORTED once the daemon said so; null = still worth asking.
    @Volatile private var verdict: Int? = initialVerdict
    // Nesting depth of live activations, guarded by lock.
    private var depth = 0
    @Volatile private var applied = false

    /** False when the firmware duck is off or this firmware lacks it: duck the media at once. */
    fun mayApply(): Boolean = FIRMWARE_VOICE_DUCK_ENABLED && verdict == null

    /** The firmware owns the duck right now: inner media ducks must stay off. */
    fun isActive(): Boolean = applied

    /** Asks the daemon to activate (first nesting level only). True = applied, leave media alone.
     *  Every call must be paired with [deactivate]. */
    suspend fun activate(): Boolean = lock.withLock {
        if (!mayApply()) return@withLock false
        depth++
        if (depth > 1) return@withLock applied
        prefs?.edit()?.putBoolean(KEY_MARKER, true)?.commit()
        val reply = withTimeoutOrNull(activateTimeoutMs) { setActive(true) }
        record("activate", reply)
        when (reply?.outcome) {
            HelperBinderProtocol.AUTO_VOICE_APPLIED -> applied = true
            HelperBinderProtocol.AUTO_VOICE_UNSUPPORTED -> {
                verdict = reply.outcome
                depth = 0
                clearMarker()
            }
            // FAILED or no answer in time: the daemon may still have applied it, so the
            // deactivate at session end is sent anyway.
            else -> Unit
        }
        Log.i(TAG, "activate: ${statusName(reply)} ${reply?.detail.orEmpty()} -> " +
            if (applied) "firmware duck, media volume left alone" else "media duck")
        applied
    }

    /** Leaves one nesting level; the last one turns the firmware state off. A no-op when
     *  [activate] did not take a level (switch off, cached verdict). */
    suspend fun deactivate(): Unit = lock.withLock {
        if (depth == 0) return@withLock
        depth--
        if (depth > 0) return@withLock
        applied = false
        // The full helper budget, not the short activate one: this call must land.
        val reply = setActive(false)
        record("deactivate", reply)
        settle(reply, "deactivate")
    }

    /** Service start: a marker left by a process that died mid-session means the firmware duck
     *  may still be on. Turns it off unless a live session in this process owns it. */
    suspend fun recoverStuck(): Unit = lock.withLock {
        if (depth > 0) {
            Log.i(TAG, "recoverStuck: skipped, owned by a live session in this process")
            return@withLock
        }
        if (prefs?.getBoolean(KEY_MARKER, false) != true) return@withLock
        val reply = setActive(false)
        record("recover", reply)
        settle(reply, "recoverStuck")
    }

    private fun settle(reply: AutoVoiceReply?, what: String) {
        when (reply?.outcome) {
            HelperBinderProtocol.AUTO_VOICE_APPLIED -> clearMarker()
            HelperBinderProtocol.AUTO_VOICE_UNSUPPORTED -> {
                verdict = reply.outcome
                clearMarker()
            }
            else -> Unit
        }
        val markerKept = prefs?.getBoolean(KEY_MARKER, false) == true
        Log.i(TAG, "$what: ${statusName(reply)} ${reply?.detail.orEmpty()}" +
            if (markerKept) " (marker kept for the next service start)" else "")
    }

    private fun clearMarker() {
        prefs?.edit()?.remove(KEY_MARKER)?.apply()
    }

    private fun record(what: String, reply: AutoVoiceReply?) {
        prefs?.edit()?.putString(KEY_LAST, "$what ${statusName(reply)} ${reply?.detail.orEmpty()}".trim())?.apply()
    }
}
