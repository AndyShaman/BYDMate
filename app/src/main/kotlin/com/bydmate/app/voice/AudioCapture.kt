package com.bydmate.app.voice

import android.content.SharedPreferences
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.concurrent.thread

class AudioCapture(private val audioManager: AudioManager, private val prefs: SharedPreferences) {

    companion object {
        private const val SAMPLE_RATE = 16000
        // 100 ms of mono PCM16 @ 16 kHz. Reading in small fixed chunks (not minBuf/2 ~ 500 ms) gives
        // the recognizer ~10 partials/sec, which is what makes early-fire feel instant.
        private const val READ_CHUNK = 1600
        // VOICE_RECOGNITION, VOICE_COMMUNICATION, MIC, UNPROCESSED, DEFAULT
        private val SOURCES = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,    // 6
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,  // 7
            MediaRecorder.AudioSource.MIC,                  // 1
            MediaRecorder.AudioSource.UNPROCESSED,          // 9
            MediaRecorder.AudioSource.DEFAULT,              // 0
        )
        internal const val DUCK_VOLUME_INDEX = 1
        private const val TAG = "AudioCapture"
        // Pre-duck media volume survives process death here; restoreStuckDuck() reads it
        // at service start (stuck-quiet media after a crash / APK update mid session).
        internal const val KEY_PRE_DUCK_VOLUME = "pre_duck_volume"
    }

    // True while a duck belongs to a live session in THIS process (set by duckMusic, cleared
    // by restoreMusic). Resets to false on process death — which is exactly the only case
    // where the persisted marker should be acted on by restoreStuckDuck().
    @Volatile private var duckOwnedByLiveSession = false

    // No caller-side endpointing: end-of-speech is decided by the recognizer's VAD (AsrEngine), not
    // an energy/silence heuristic here. The old RMS gate closed the window ~800 ms in if the user
    // hadn't started talking loudly yet, cutting them off mid-phrase. [maxMs] is only a safety
    // backstop so the mic can't stay open forever (e.g. continuous noise with no VAD endpoint).
    fun captureSession(maxMs: Long = 8000): Flow<ShortArray> = callbackFlow {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(SAMPLE_RATE) // ~0.5s

        // Duck background media FIRST so it drops the instant the user triggers voice. DiLink's
        // media player ignores audio-focus ducking, so the MAY_DUCK request below is not enough —
        // we lower STREAM_MUSIC directly and restore it on every exit path. duckedFrom holds the
        // pre-duck volume to restore, or null if nothing was ducked (no music / already low).
        // Ducking must precede openRecord(): that iterates up to 5 mic sources and would otherwise
        // delay the volume drop by a perceptible beat.
        val duckedFrom = duckMusic()

        // If the mic cannot open, restore the volume we just ducked before bailing — otherwise
        // media would stay stuck at the duck level with no listen window to justify it.
        val record = openRecord(minBuf) ?: run {
            restoreMusic(duckedFrom)
            close(IllegalStateException("mic unavailable"))
            return@callbackFlow
        }

        // Fix C — release the already-initialized AudioRecord and abandon focus if
        // requestAudioFocus or startRecording throw (otherwise both resources leak
        // because awaitClose has not been registered yet at this point).
        try {
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            record.startRecording()
        } catch (t: Throwable) {
            runCatching { record.release() }
            runCatching { audioManager.abandonAudioFocus(null) }
            restoreMusic(duckedFrom)
            close(t)
            return@callbackFlow
        }

        val worker = thread(name = "voice-capture") {
            val buf = ShortArray(READ_CHUNK)
            val start = System.nanoTime()
            try {
                while (!isClosedForSend) {
                    val n = record.read(buf, 0, buf.size)
                    if (n > 0) trySend(buf.copyOf(n))
                    if ((System.nanoTime() - start) / 1_000_000 >= maxMs) break
                }
            } finally {
                close()
            }
        }

        awaitClose {
            runCatching { record.stop() }
            runCatching { record.release() }
            // Each cleanup step is independent: a throw in one must not skip the
            // volume restore below (otherwise media stays ducked at 15%).
            runCatching { audioManager.abandonAudioFocus(null) }
            restoreMusic(duckedFrom)
            worker.interrupt()
        }
    }

    /** Lower background media so it doesn't drown out the speaker / force the user to
     *  shout into the mic. Only acts when music is actually playing and louder than the
     *  duck target. Returns the pre-duck volume to restore later, or null if nothing
     *  was changed (so restoreMusic is a no-op and never bumps volume up unexpectedly). */
    // internal for direct unit tests
    internal fun duckMusic(): Int? {
        if (!audioManager.isMusicActive) return null
        val saved = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        // Near-zero, not 15%: DiLink's MUSIC scale is 0..39, so 15% (~index 5) is still clearly
        // audible over the mic and over the agent's own replies (field defect APK 336). Index 1
        // keeps playback alive (position keeps advancing) while being effectively silent.
        val target = DUCK_VOLUME_INDEX
        if (saved <= target) return null
        val applied = runCatching {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        }.isSuccess
        if (!applied) return null
        duckOwnedByLiveSession = true
        prefs.edit().putInt(KEY_PRE_DUCK_VOLUME, saved).apply()
        Log.i(TAG, "duckMusic: $saved -> $target")
        return saved
    }

    /** Restore the media volume captured by duckMusic(). No-op if nothing was ducked. */
    // internal for direct unit tests
    internal fun restoreMusic(saved: Int?) {
        saved ?: return
        duckOwnedByLiveSession = false
        runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0) }
        prefs.edit().remove(KEY_PRE_DUCK_VOLUME).apply()
        Log.i(TAG, "restoreMusic: -> $saved")
    }

    /**
     * One-shot recovery at service start: if the process died between duckMusic() and
     * restoreMusic(), media volume is stuck near zero and no later session can raise it
     * back (duckMusic() no-ops at or below the duck level). Restores the persisted
     * pre-duck volume, but only while the current volume is still at/below the duck
     * level — a volume the user already raised by hand always wins.
     */
    fun restoreStuckDuck() {
        // The persisted marker is only trustworthy after a real process death. When the
        // service is recreated inside a living process (task swiped from recents ->
        // WorkManager restart) an active voice session may still own the duck — restoring
        // here would blast music mid-reply; the session's own restoreMusic() handles it.
        if (duckOwnedByLiveSession) {
            Log.i(TAG, "restoreStuckDuck: skipped, duck owned by a live session in this process")
            return
        }
        val saved = prefs.getInt(KEY_PRE_DUCK_VOLUME, -1)
        if (saved < 0) return
        val cur = runCatching { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull()
        if (cur != null && cur <= DUCK_VOLUME_INDEX) {
            runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0) }
            Log.i(TAG, "restoreStuckDuck: volume stuck at $cur, restored to $saved")
        } else {
            Log.i(TAG, "restoreStuckDuck: pending $saved dropped (current volume $cur)")
        }
        prefs.edit().remove(KEY_PRE_DUCK_VOLUME).apply()
    }

    private fun openRecord(minBuf: Int): AudioRecord? {
        for (src in SOURCES) {
            val r = runCatching {
                AudioRecord(src, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf)
            }.getOrNull()
            if (r != null && r.state == AudioRecord.STATE_INITIALIZED) return r
            r?.release()
        }
        return null
    }
}
