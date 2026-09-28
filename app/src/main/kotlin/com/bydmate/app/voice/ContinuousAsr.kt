package com.bydmate.app.voice

import kotlinx.coroutines.flow.Flow

/** Segment-level ASR for the continuous session. Emits one Utterance per
 *  VAD-detected phrase; SilenceTick lets the session loop track auto-stop. */
sealed interface ContinuousAsrEvent {
    /** [audioMs]: length of the VAD segment the text was decoded from, 0 when unknown. */
    data class Utterance(val text: String, val audioMs: Long = 0L) : ContinuousAsrEvent
    object SpeechStart : ContinuousAsrEvent
    data class SilenceTick(val silentMs: Long) : ContinuousAsrEvent
}

interface ContinuousAsr {
    fun isReady(): Boolean
    /** True only when native pieces needed before PCM collection are already loaded. */
    fun isWarm(): Boolean = isReady()
    /** DiLink3 needs native ASR pieces hot before the listening state opens; upstream platforms
     *  keep the author's immediate-start behavior. */
    fun requiresWarmBeforeListening(): Boolean = false
    /** Cold flow: collecting consumes pcm frames (16kHz ShortArray), cancelling stops. */
    fun transcribe(pcm: Flow<ShortArray>): Flow<ContinuousAsrEvent>
    /** Pre-build the recognizer ahead of the first PTT so its cold model load doesn't
     *  delay transcribe(). Default no-op so fakes/tests don't need to implement it. */
    fun warmUp() {}
}
