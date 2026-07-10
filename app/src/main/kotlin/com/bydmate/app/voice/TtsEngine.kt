package com.bydmate.app.voice

import kotlinx.coroutines.flow.StateFlow

/** Offline text-to-speech. Implementations must be safe to call from any
 *  thread; speak() is fire-and-forget and must never throw. */
interface TtsEngine {
    /** True when a voice model is downloaded and usable. */
    fun isReady(): Boolean

    /** Synthesize and play [text]. No-op when blank or not ready.
     *  @return true if playback was actually enqueued, false on a no-op -- callers use this to
     *  know whether a mute window stamped around the call corresponds to real audio in flight. */
    fun speak(text: String): Boolean

    /** Synthesize and play [text] through the LOCAL (offline) engine, bypassing any online source
     *  that may be selected. Intended for previews that must always demonstrate the local voice.
     *  Default delegates to [speak] so plain engines are unaffected. */
    fun speakOffline(text: String): Boolean = speak(text)

    /** Stop current playback immediately (barge-in). */
    fun stop()

    /** Re-initialize against the currently selected voice/model on the next speak().
     *  No-op for engines with nothing to reload. */
    fun reload() {}

    /** True from the moment an utterance actually starts playing until its audio has fully
     *  drained from the output device (or stop() cuts it short). The continuous voice
     *  session mutes its mic off this real signal instead of guessing speech duration. */
    val speaking: StateFlow<Boolean>

    /** True right now, physically: sound is actually coming out of the speaker. Unlike
     *  [speaking] (held true across a whole streamed reply, including silent tool rounds
     *  where the sentence queue stays hot waiting for the next chunk), this reflects only
     *  written-but-not-yet-played audio -- so the mic mute can release during a tool round
     *  and hear the driver, while still muting real playback. Default falls back to the
     *  logical flag for engines that never go physically silent mid-utterance. */
    fun audible(): Boolean = speaking.value

    /** Plays raw mono PCM float samples through the same track/mute machinery as speak().
     *  Returns false when not ready / superseded. */
    fun playPcm(samples: FloatArray, sampleRate: Int): Boolean = false

    /** A speech queue for streamed replies: sentences enqueued one by one play back-to-back
     *  on the same audio track, and [speaking] stays true from the first played sample until
     *  finish() drains the tail. stop() on the engine cancels the whole queue. */
    interface SpeechQueue {
        /** Returns false when the queue is already superseded (stop()/newer speech) or text is blank. */
        fun enqueue(text: String): Boolean
        /** Signals no more sentences: waits (async, on the tts worker) for playback to drain,
         *  then releases the speaking flag. Must be called exactly once. */
        fun finish()
    }

    /** Starts a speech queue, superseding any current speech. Null when the engine is not
     *  ready (model missing) or the implementation does not support queued speech. */
    fun startQueue(): SpeechQueue? = null
}
