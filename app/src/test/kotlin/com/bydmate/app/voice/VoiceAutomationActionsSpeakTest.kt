package com.bydmate.app.voice

import android.content.Context
import com.bydmate.app.agent.AgentOrchestrator
import com.bydmate.app.data.automation.DispatchResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAutomationActionsSpeakTest {

    private fun makeActions(
        ttsEnabled: Boolean = true,
        voiceEnabled: Boolean = true,
        listening: Boolean = false,
        speakOk: Boolean = true,
        ttsEngine: TtsEngine = mockk(relaxed = true) {
            every { speaking } returns MutableStateFlow(false)
            every { speak(any()) } returns speakOk
        },
        audioCapture: AudioCapture = mockk(relaxed = true),
        orchestrator: AgentOrchestrator = mockk(relaxed = true),
    ): Pair<VoiceAutomationActions, TtsEngine> {
        val gate = mockk<VoiceGate>()
        every { gate.isEnabled() } returns voiceEnabled
        every { gate.ttsEnabled() } returns ttsEnabled
        val controller = mockk<VoiceController>()
        every { controller.listening } returns MutableStateFlow(listening)
        every { controller.sessionActive() } returns listening
        val actions = VoiceAutomationActions(
            ttsEngine = ttsEngine,
            audioCapture = audioCapture,
            gate = gate,
            agentOrchestrator = dagger.Lazy { orchestrator },
            voiceController = dagger.Lazy { controller },
            context = mockk<Context>(relaxed = true),
        )
        // Never touch the real static overlay in JVM tests.
        actions.isOrbShowing = { false }
        actions.canShowOrb = { false }
        return actions to ttsEngine
    }

    @Test fun `speaks the raw text and reports success`() = runTest {
        val (actions, tts) = makeActions()
        val r = actions.speak("Позвони жене")
        assertTrue(r.success)
        verify(exactly = 1) { tts.speak("Позвони жене") }  // raw, no persona wrapper
    }

    @Test fun `ducks music and restores it after speech`() = runTest {
        val capture = mockk<AudioCapture>(relaxed = true)
        io.mockk.every { capture.duckMusic() } returns 20
        val (actions, _) = makeActions(audioCapture = capture)
        actions.speak("тест")
        io.mockk.verifyOrder {
            capture.duckMusic()
            capture.restoreMusic(20)
        }
    }

    @Test fun `voice master off - refused with reason`() = runTest {
        val (actions, tts) = makeActions(voiceEnabled = false)
        val r = actions.speak("тест")
        assertFalse(r.success)
        assertEquals("голосовой помощник выключен в настройках", r.reason)
        verify(exactly = 0) { tts.speak(any()) }
    }

    @Test fun `tts toggle off - refused with reason`() = runTest {
        val (actions, tts) = makeActions(ttsEnabled = false)
        val r = actions.speak("тест")
        assertFalse(r.success)
        assertEquals("озвучка ответов выключена в настройках", r.reason)
        verify(exactly = 0) { tts.speak(any()) }
    }

    @Test fun `live voice session - dropped, not queued`() = runTest {
        val (actions, tts) = makeActions(listening = true)
        val r = actions.speak("тест")
        assertFalse(r.success)
        assertEquals("идёт голосовая сессия, действие пропущено", r.reason)
        verify(exactly = 0) { tts.speak(any()) }
    }

    // Fix 3: legacy one-shot startSession() sets only busy, not _listening. sessionActive()
    // must cover that path; the gate must drop the speak with the same reason.
    @Test fun `legacy session active (sessionActive=true, listening=false) - speak dropped`() = runTest {
        val gate = mockk<VoiceGate>()
        every { gate.isEnabled() } returns true
        every { gate.ttsEnabled() } returns true
        val controller = mockk<VoiceController>()
        every { controller.listening } returns MutableStateFlow(false) // continuous session NOT active
        every { controller.sessionActive() } returns true              // legacy busy IS active
        val tts = mockk<TtsEngine>(relaxed = true) {
            every { speaking } returns MutableStateFlow(false)
        }
        val actions = VoiceAutomationActions(
            ttsEngine = tts,
            audioCapture = mockk(relaxed = true),
            gate = gate,
            agentOrchestrator = dagger.Lazy { mockk(relaxed = true) },
            voiceController = dagger.Lazy { controller },
            context = mockk(relaxed = true),
        )
        actions.isOrbShowing = { false }
        actions.canShowOrb = { false }
        val r = actions.speak("тест")
        assertFalse(r.success)
        assertEquals("идёт голосовая сессия, действие пропущено", r.reason)
        verify(exactly = 0) { tts.speak(any()) }
    }

    @Test fun `blank text - refused`() = runTest {
        val (actions, _) = makeActions()
        val r = actions.speak("   ")
        assertFalse(r.success)
        assertEquals("не задан текст", r.reason)
    }

    @Test fun `tts refusing to start reports failure but still restores music`() = runTest {
        val capture = mockk<AudioCapture>(relaxed = true)
        io.mockk.every { capture.duckMusic() } returns 12
        val (actions, _) = makeActions(speakOk = false, audioCapture = capture)
        assertFalse(actions.speak("тест").success)
        verify(exactly = 1) { capture.restoreMusic(12) }
    }

    @Test fun `speak timeout stops tts and returns failure`() = runTest {
        // speaking stuck at true: start-wait (first { it }) resolves immediately;
        // drain (first { !it }) suspends forever. runTest's virtual clock advances
        // SPEAK_TIMEOUT_MS so withTimeoutOrNull fires, stop() must be called and
        // the result must be failure with the expected reason.
        val tts = mockk<TtsEngine>(relaxed = true) {
            every { speaking } returns MutableStateFlow(true)
            every { speak(any()) } returns true
        }
        val (actions, _) = makeActions(ttsEngine = tts)
        val r = actions.speak("тест")
        assertFalse(r.success)
        assertEquals("озвучка прервана по таймауту", r.reason)
        verify(exactly = 1) { tts.stop() }
    }

    @Test fun `music duck is restored on cancellation mid-drain`() = runTest {
        val capture = mockk<AudioCapture>(relaxed = true)
        io.mockk.every { capture.duckMusic() } returns 15
        // speaking = true: start-wait (first { it }) completes immediately;
        // drain (first { !it }) suspends forever -- coroutine hangs there until cancelled.
        val tts = mockk<TtsEngine>(relaxed = true) {
            every { speaking } returns MutableStateFlow(true)
            every { speak(any()) } returns true
        }
        val (actions, _) = makeActions(ttsEngine = tts, audioCapture = capture)
        val job = launch { actions.speak("тест") }
        testScheduler.runCurrent()    // start coroutine, run until suspended in drain
        job.cancelAndJoin()           // cancel while suspended; finally must fire
        io.mockk.verify(exactly = 1) { capture.restoreMusic(15) }
    }
}
