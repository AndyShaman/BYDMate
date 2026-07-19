package com.bydmate.app.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AsrLoadGuardTest {

    private lateinit var guard: AsrLoadGuard

    @Before fun setUp() {
        guard = AsrLoadGuard(ApplicationProvider.getApplicationContext<Context>())
        guard.reset()
    }

    @Test fun `fresh guard is not tripped`() {
        assertFalse(guard.isTripped())
    }

    @Test fun `one interrupted load does not trip - could be an unrelated process kill`() {
        guard.noteLoadBegin(AsrLoadGuard.ARTIFACT_RECOGNIZER)
        assertFalse(guard.isTripped())
    }

    @Test fun `two interrupted recognizer loads trip the guard`() {
        guard.noteLoadBegin(AsrLoadGuard.ARTIFACT_RECOGNIZER)
        guard.noteLoadBegin(AsrLoadGuard.ARTIFACT_RECOGNIZER)
        assertTrue(guard.isTripped())
    }

    @Test fun `two interrupted vad loads trip the guard`() {
        guard.noteLoadBegin(AsrLoadGuard.ARTIFACT_VAD)
        guard.noteLoadBegin(AsrLoadGuard.ARTIFACT_VAD)
        assertTrue(guard.isTripped())
    }

    @Test fun `one interruption per artifact does not trip - counters are independent`() {
        guard.noteLoadBegin(AsrLoadGuard.ARTIFACT_RECOGNIZER)
        guard.noteLoadBegin(AsrLoadGuard.ARTIFACT_VAD)
        assertFalse(guard.isTripped())
    }

    @Test fun `successful load clears its own counter`() {
        guard.noteLoadBegin(AsrLoadGuard.ARTIFACT_RECOGNIZER)
        guard.noteLoadSuccess(AsrLoadGuard.ARTIFACT_RECOGNIZER)
        guard.noteLoadBegin(AsrLoadGuard.ARTIFACT_RECOGNIZER)
        assertFalse(guard.isTripped())
    }

    @Test fun `recognizer success does not wipe vad crash evidence`() {
        guard.noteLoadBegin(AsrLoadGuard.ARTIFACT_VAD)
        guard.noteLoadBegin(AsrLoadGuard.ARTIFACT_VAD)
        guard.noteLoadBegin(AsrLoadGuard.ARTIFACT_RECOGNIZER)
        guard.noteLoadSuccess(AsrLoadGuard.ARTIFACT_RECOGNIZER)
        assertTrue(guard.isTripped())
    }

    @Test fun `reset untips the guard`() {
        guard.noteLoadBegin(AsrLoadGuard.ARTIFACT_RECOGNIZER)
        guard.noteLoadBegin(AsrLoadGuard.ARTIFACT_RECOGNIZER)
        guard.reset()
        assertFalse(guard.isTripped())
        // And the counter really is zero, not one-below-threshold.
        guard.noteLoadBegin(AsrLoadGuard.ARTIFACT_RECOGNIZER)
        assertFalse(guard.isTripped())
    }

    @Test fun `state survives a new instance over the same prefs - the whole point`() {
        guard.noteLoadBegin(AsrLoadGuard.ARTIFACT_RECOGNIZER)
        guard.noteLoadBegin(AsrLoadGuard.ARTIFACT_RECOGNIZER)
        val reborn = AsrLoadGuard(ApplicationProvider.getApplicationContext<Context>())
        assertTrue(reborn.isTripped())
    }

    // --- Task 2: TTS guard extension ---

    @Test fun `two interrupted TTS loads trip the guard`() {
        guard.noteLoadBegin(AsrLoadGuard.ARTIFACT_TTS)
        guard.noteLoadBegin(AsrLoadGuard.ARTIFACT_TTS)
        assertTrue(guard.isTripped())
    }

    @Test fun `TTS guard on separate prefs file does not affect ASR instance`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ttsPrefs = context.getSharedPreferences("tts_load_guard", Context.MODE_PRIVATE)
        ttsPrefs.edit().clear().commit()
        val ttsGuard = AsrLoadGuard(ttsPrefs)
        // Trip the TTS guard
        ttsGuard.noteLoadBegin(AsrLoadGuard.ARTIFACT_TTS)
        ttsGuard.noteLoadBegin(AsrLoadGuard.ARTIFACT_TTS)
        assertTrue(ttsGuard.isTripped())
        // ASR guard (uses "asr_load_guard") is unaffected
        assertFalse(guard.isTripped())
    }
}
