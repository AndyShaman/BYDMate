package com.bydmate.app.voice

import io.mockk.every
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Stubs [AudioCapture.captureSession] with [frames], each marked when the consumer reaches it:
 *  these tests do not model frames waiting in the capture channel (VoiceControllerPlaybackGateTest does). */
internal fun AudioCapture.stubFrames(frames: Flow<ShortArray>) {
    every { captureSession(any(), any<() -> Any?>()) } answers {
        val mark = secondArg<() -> Any?>()
        frames.map { MicFrame(it, mark()) }
    }
}
