package com.bydmate.app.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
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

    /** A new process: the guard is built again over the same stored counters. */
    private fun reborn() = AsrLoadGuard(ApplicationProvider.getApplicationContext<Context>())

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
        assertTrue(reborn().isTripped())
    }

    @Test fun `two interrupted vad loads trip the guard`() {
        guard.noteLoadBegin(AsrLoadGuard.ARTIFACT_VAD)
        guard.noteLoadBegin(AsrLoadGuard.ARTIFACT_VAD)
        assertTrue(reborn().isTripped())
    }

    // DiLink 3 field log 2026-11: the previous process died mid-load (1), this process is loading again
    // (2 stored). A PTT now must not hear "model missing": the running load proves nothing yet.
    @Test fun `a load running in this process does not trip it`() {
        guard.noteLoadBegin(AsrLoadGuard.ARTIFACT_RECOGNIZER)
        val next = reborn()
        next.noteLoadBegin(AsrLoadGuard.ARTIFACT_RECOGNIZER)
        assertFalse(next.isTripped())
        assertEquals(2, next.storedCounts()[AsrLoadGuard.ARTIFACT_RECOGNIZER])
        assertEquals(1, next.inheritedCounts()[AsrLoadGuard.ARTIFACT_RECOGNIZER])
        // The process it would have killed sees the evidence.
        assertTrue(reborn().isTripped())
    }

    @Test fun `withdrawn in-flight loads do not count towards the trip`() {
        guard.noteLoadBegin(AsrLoadGuard.ARTIFACT_RECOGNIZER)
        guard.noteLoadBegin(AsrLoadGuard.ARTIFACT_VAD)
        assertEquals(2, guard.withdrawInFlight())
        // The planned kill happens now; two more starts killed the same way never trip.
        val second = reborn()
        second.noteLoadBegin(AsrLoadGuard.ARTIFACT_RECOGNIZER)
        second.withdrawInFlight()
        assertFalse(reborn().isTripped())
        assertEquals(0, reborn().storedCounts()[AsrLoadGuard.ARTIFACT_RECOGNIZER])
    }

    @Test fun `withdraw keeps the evidence of earlier processes`() {
        guard.noteLoadBegin(AsrLoadGuard.ARTIFACT_RECOGNIZER)        // died mid-load
        val second = reborn()
        second.noteLoadBegin(AsrLoadGuard.ARTIFACT_RECOGNIZER)       // planned kill
        second.withdrawInFlight()
        assertEquals(1, reborn().storedCounts()[AsrLoadGuard.ARTIFACT_RECOGNIZER])
    }

    @Test fun `withdraw with nothing in flight changes nothing`() {
        guard.noteLoadBegin(AsrLoadGuard.ARTIFACT_RECOGNIZER)
        guard.noteLoadSuccess(AsrLoadGuard.ARTIFACT_RECOGNIZER)
        assertEquals(0, guard.withdrawInFlight())
        assertEquals(0, guard.storedCounts()[AsrLoadGuard.ARTIFACT_RECOGNIZER])
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
        assertTrue(reborn().isTripped())
    }

    @Test fun `reset untips the guard`() {
        guard.noteLoadBegin(AsrLoadGuard.ARTIFACT_RECOGNIZER)
        guard.noteLoadBegin(AsrLoadGuard.ARTIFACT_RECOGNIZER)
        val tripped = reborn()
        assertTrue(tripped.isTripped())
        tripped.reset()
        assertFalse(tripped.isTripped())
        // And the counter really is zero, not one-below-threshold.
        tripped.noteLoadBegin(AsrLoadGuard.ARTIFACT_RECOGNIZER)
        assertFalse(reborn().isTripped())
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
        assertTrue(reborn().isTripped())
    }

    @Test fun `TTS guard on separate prefs file does not affect ASR instance`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ttsPrefs = context.getSharedPreferences("tts_load_guard", Context.MODE_PRIVATE)
        ttsPrefs.edit().clear().commit()
        val ttsGuard = AsrLoadGuard(ttsPrefs)
        // Trip the TTS guard
        ttsGuard.noteLoadBegin(AsrLoadGuard.ARTIFACT_TTS)
        ttsGuard.noteLoadBegin(AsrLoadGuard.ARTIFACT_TTS)
        assertTrue(AsrLoadGuard(ttsPrefs).isTripped())
        // ASR guard (uses "asr_load_guard") is unaffected
        assertFalse(reborn().isTripped())
    }
}
