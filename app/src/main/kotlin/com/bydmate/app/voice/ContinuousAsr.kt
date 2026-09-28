package com.bydmate.app.voice

import kotlinx.coroutines.flow.Flow

/** Segment-level ASR for the continuous session. Emits one Utterance per
 *  VAD-detected phrase; SilenceTick lets the session loop track auto-stop. */
sealed interface ContinuousAsrEvent {
    data class Utterance(val text: String) : ContinuousAsrEvent
    object SpeechStart : ContinuousAsrEvent
    data class SilenceTick(val silentMs: Long) : ContinuousAsrEvent
}

interface ContinuousAsr {
    fun isReady(): Boolean
    /** True once everything transcribe() builds before it reads the first frame is built. */
    fun isWarm(): Boolean = isReady()
    /** True when listening must not open before [isWarm] (Android 10 head units): there the
     *  build runs after the start cue and swallows the driver's first words. */
    fun requiresWarmBeforeListening(): Boolean = false
    /** Cold flow: collecting consumes pcm frames (16kHz ShortArray), cancelling stops. */
    fun transcribe(pcm: Flow<ShortArray>): Flow<ContinuousAsrEvent>
    /** Pre-build the recognizer ahead of the first PTT so its cold model load doesn't
     *  delay transcribe(). Default no-op so fakes/tests don't need to implement it. */
    fun warmUp() {}
}
