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
    /** Cold flow: collecting consumes pcm frames (16kHz ShortArray), cancelling stops. */
    fun transcribe(pcm: Flow<ShortArray>): Flow<ContinuousAsrEvent>
    /** Pre-build the recognizer ahead of the first PTT so its cold model load doesn't
     *  delay transcribe(). Default no-op so fakes/tests don't need to implement it. */
    fun warmUp() {}
}
