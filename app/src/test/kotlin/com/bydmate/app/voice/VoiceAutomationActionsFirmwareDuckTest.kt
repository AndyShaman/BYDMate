package com.bydmate.app.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.agent.AgentOrchestrator
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.vehicle.AutoVoiceReply
import com.bydmate.app.helper.HelperBinderProtocol
import com.bydmate.app.util.AppStrings
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The speak action with the firmware voice duck (#353): who ducks, and that deactivate always runs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class VoiceAutomationActionsFirmwareDuckTest {

    private val strings = AppStrings(ApplicationProvider.getApplicationContext())

    init {
        LocalePreferences(ApplicationProvider.getApplicationContext()).setLanguage("ru")
    }

    private val calls = mutableListOf<Boolean>()

    // Daemon calls run on the test scheduler: advanceUntilIdle() settles them without real time.
    private fun TestScope.duck(onOn: suspend () -> AutoVoiceReply?) =
        AutoVoiceDuck({ on -> calls += on; if (on) onOn() else applied }, null, scope = duckScope())

    private fun TestScope.duck(reply: AutoVoiceReply?) = duck { reply }

    private val applied = AutoVoiceReply(HelperBinderProtocol.AUTO_VOICE_APPLIED, "ok")

    private fun makeActions(
        duck: AutoVoiceDuck,
        capture: AudioCapture,
        tts: TtsEngine = mockk(relaxed = true) {
            every { speaking } returns MutableStateFlow(false)
            every { speak(any()) } returns true
        },
    ): VoiceAutomationActions {
        val gate = mockk<VoiceGate>()
        every { gate.isEnabled() } returns true
        every { gate.ttsEnabled() } returns true
        val controller = mockk<VoiceController>()
        every { controller.listening } returns MutableStateFlow(false)
        every { controller.sessionActive() } returns false
        return VoiceAutomationActions(
            ttsEngine = tts,
            audioCapture = capture,
            gate = gate,
            agentOrchestrator = dagger.Lazy { mockk<AgentOrchestrator>(relaxed = true) },
            voiceController = dagger.Lazy { controller },
            context = mockk<Context>(relaxed = true),
            strings = strings,
            autoVoiceDuck = duck,
        ).also {
            it.isOrbShowing = { false }
            it.canShowOrb = { false }
        }
    }

    private fun capture() = mockk<AudioCapture>(relaxed = true).also { every { it.duckMusic() } returns 20 }

    @Test fun `applied - media is not ducked and the firmware state goes on and off`() = runTest {
        val capture = capture()
        val r = makeActions(duck(applied), capture).speak("тест")
        advanceUntilIdle()

        assertTrue(r.success)
        verify(exactly = 0) { capture.duckMusic() }
        assertEquals(listOf(true, false), calls)
    }

    @Test fun `applied - tts refusing to start still deactivates`() = runTest {
        val capture = capture()
        val tts = mockk<TtsEngine>(relaxed = true) {
            every { speaking } returns MutableStateFlow(false)
            every { speak(any()) } returns false
        }
        val r = makeActions(duck(applied), capture, tts).speak("тест")
        advanceUntilIdle()

        assertFalse(r.success)
        verify(exactly = 0) { capture.duckMusic() }
        assertEquals(listOf(true, false), calls)
    }

    @Test fun `applied - cancellation mid-drain still deactivates`() = runTest {
        val capture = capture()
        val tts = mockk<TtsEngine>(relaxed = true) {
            every { speaking } returns MutableStateFlow(true)
            every { speak(any()) } returns true
        }
        val actions = makeActions(duck(applied), capture, tts)
        val job = launch { actions.speak("тест") }
        testScheduler.runCurrent()
        job.cancelAndJoin()
        advanceUntilIdle()

        verify(exactly = 0) { capture.duckMusic() }
        assertEquals(listOf(true, false), calls)
    }

    @Test fun `unsupported - plain media duck and restore, daemon asked once`() = runTest {
        val capture = capture()
        val d = duck(AutoVoiceReply(HelperBinderProtocol.AUTO_VOICE_UNSUPPORTED, "no method"))
        val actions = makeActions(d, capture)
        actions.speak("раз")
        actions.speak("два")
        advanceUntilIdle()

        verify(exactly = 2) { capture.duckMusic() }
        verify(exactly = 2) { capture.restoreMusic(20) }
        assertEquals(listOf(true), calls)
    }

    @Test fun `helper down - media duck and restore as today, deactivate still sent`() = runTest {
        val capture = capture()
        makeActions(duck(null), capture).speak("тест")
        advanceUntilIdle()

        io.mockk.verifyOrder {
            capture.duckMusic()
            capture.restoreMusic(20)
        }
        assertEquals(listOf(true, false), calls)
    }

    @Test fun `no firmware duck configured - today's behaviour, no daemon calls`() = runTest {
        val capture = capture()
        makeActions(AutoVoiceDuck.OFF, capture).speak("тест")

        io.mockk.verifyOrder {
            capture.duckMusic()
            capture.restoreMusic(20)
        }
        assertTrue(calls.isEmpty())
    }

    @Test fun `cancelled speak does not drop a session lease held at the same time`() = runTest {
        val gate = CompletableDeferred<AutoVoiceReply?>()
        val d = duck { gate.await() }
        val tts = mockk<TtsEngine>(relaxed = true) {
            every { speaking } returns MutableStateFlow(true)
            every { speak(any()) } returns true
        }
        val session = async { d.activate({ 20 }, { }) }
        testScheduler.runCurrent()
        val speakJob = launch { makeActions(d, capture(), tts).speak("тест") }
        testScheduler.runCurrent()
        speakJob.cancelAndJoin()
        gate.complete(applied)
        advanceUntilIdle()

        assertTrue(d.isActive())
        assertEquals(listOf(true), calls)
        d.deactivate(session.await())
        advanceUntilIdle()
        assertEquals(listOf(true, false), calls)
    }
}
