package com.bydmate.app.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the silero VAD tuning constants against the field defect from APK 335: default
 * minSilenceDuration=0.25s split phrases mid-sentence ("в том это где"), and default
 * threshold=0.5 clipped soft speech onsets ("лышишь меня"). Read via GigaAmAsrEngine.Companion
 * only, so the sherpa-onnx JNI classes are never touched.
 */
class GigaAmVadConfigTest {

    @Test
    fun vad_config_pinned() {
        assertEquals(0.4f, GigaAmAsrEngine.VAD_THRESHOLD)
        assertEquals(0.8f, GigaAmAsrEngine.VAD_MIN_SILENCE_SEC)
        assertEquals(0.25f, GigaAmAsrEngine.VAD_MIN_SPEECH_SEC)
        assertEquals(15.0f, GigaAmAsrEngine.VAD_MAX_SPEECH_SEC)
    }
}
