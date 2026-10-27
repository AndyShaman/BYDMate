package com.bydmate.app.voice

import android.content.Context
import com.bydmate.app.agent.AgentOrchestrator
import com.bydmate.app.agent.AgentResult
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.AutomationEngine
import com.bydmate.app.data.vehicle.AutoVoiceReply
import com.bydmate.app.helper.HelperBinderProtocol
import com.bydmate.app.util.appStringsOver
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Collections

/** Continuous voice session with the firmware voice duck (#353): who ducks, and that deactivate always runs. */
class VoiceControllerFirmwareDuckTest {

    private class FakeAsr : ContinuousAsr {
        val events = MutableSharedFlow<ContinuousAsrEvent>(extraBufferCapacity = 16)
        @Volatile var collecting = false
        override fun isReady(): Boolean = true
        override fun transcribe(pcm: Flow<ShortArray>): Flow<ContinuousAsrEvent> = flow {
            coroutineScope {
                val collectJob: Job = launch {
                    collecting = true
                    try { pcm.collect { } } finally { collecting = false }
                }
                try { emitAll(events) } finally { collectJob.cancel() }
            }
        }
    }

    private class FailingAsr : ContinuousAsr {
        override fun isReady(): Boolean = true
        override fun transcribe(pcm: Flow<ShortArray>): Flow<ContinuousAsrEvent> = flow {
            pcm.collect { error("gigaam decode failed") }
        }
    }

    private fun awaitTrue(timeoutMs: Long = 2_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        if (!condition()) fail("condition not met within ${timeoutMs}ms")
    }

    private val applied = AutoVoiceReply(HelperBinderProtocol.AUTO_VOICE_APPLIED, "ok")
    private val unsupported = AutoVoiceReply(HelperBinderProtocol.AUTO_VOICE_UNSUPPORTED, "no method")
    private val calls: MutableList<Boolean> = Collections.synchronizedList(mutableListOf())

    private fun duck(reply: AutoVoiceReply?) = AutoVoiceDuck({ on -> calls += on; reply }, null)

    private val rawFrames = MutableSharedFlow<ShortArray>(extraBufferCapacity = 8)

    private fun capture() = mockk<AudioCapture>(relaxed = true).also {
        every { it.duckMusic() } returns 20
        it.stubFrames(rawFrames)
    }

    private fun controller(asr: ContinuousAsr, capture: AudioCapture, duck: AutoVoiceDuck): VoiceController {
        val gate = mockk<VoiceGate>()
        every { gate.isEnabled() } returns true
        every { gate.vehicleSnapshot() } returns null
        every { gate.ttsEnabled() } returns false
        every { gate.closeAfterCommand() } returns false
        val resolver = mockk<VoiceAutomationResolver>()
        coEvery { resolver.match(any()) } returns null
        val orchestrator = mockk<AgentOrchestrator>()
        coEvery { orchestrator.ask(any(), any(), any()) } returns AgentResult.Disabled
        coEvery { orchestrator.noteAction(any()) } returns Unit
        coEvery { orchestrator.expectsFollowUp() } returns false
        val context = mockk<Context>(relaxed = true)
        return VoiceController(
            capture, mockk<ActionDispatcher>(relaxed = true), mockk(relaxed = true), gate,
            mockk<AutomationEngine>(relaxed = true), resolver, orchestrator, context,
            mockk<TtsEngine>(relaxed = true) { every { speaking } returns MutableStateFlow(false) },
            VoiceJournal(), asr,
            agentIdentity = { AgentIdentity("", AgentPersona.NAVIGATOR) },
            ttsModelManager = mockk(relaxed = true),
            ruStressMarker = RuStressMarker { null },
            selectedTtsVoice = { TtsVoiceCatalog.byId("dmitri") },
            appStrings = appStringsOver(context),
            autoVoiceDuck = duck,
        ).also { it.hideListeningOverlay = { } }
    }

    private fun VoiceController.pressAndStop(asr: FakeAsr) {
        onPttPressed()
        awaitTrue { listening.value }
        awaitTrue { asr.collecting }
        onPttPressed()
        awaitTrue { !listening.value }
        awaitTrue { !sessionActive() }
    }

    @Test fun `applied - media untouched, firmware state on then off at PTT stop`() {
        val asr = FakeAsr()
        val capture = capture()
        controller(asr, capture, duck(applied)).pressAndStop(asr)

        verify(exactly = 0) { capture.duckMusic() }
        assertEquals(listOf(true, false), calls.toList())
    }

    @Test fun `applied - a failing recogniser still deactivates`() {
        val capture = capture()
        val c = controller(FailingAsr(), capture, duck(applied))
        c.showAnswerHook = { }
        c.onPttPressed()
        awaitTrue { c.listening.value }
        awaitTrue { rawFrames.subscriptionCount.value >= 1 }
        rawFrames.tryEmit(shortArrayOf(1, 2, 3))
        awaitTrue { !c.listening.value }
        awaitTrue { !c.sessionActive() }

        verify(exactly = 0) { capture.duckMusic() }
        assertEquals(listOf(true, false), calls.toList())
    }

    @Test fun `unsupported - media duck before capture and restore at teardown, second session ducks synchronously`() {
        val asr = FakeAsr()
        val capture = capture()
        val c = controller(asr, capture, duck(unsupported))

        c.pressAndStop(asr)
        verifyOrder {
            capture.duckMusic()
            capture.captureSession(any(), any<(ShortArray) -> Any?>())
        }
        verify { capture.restoreMusic(20) }
        assertEquals(listOf(true), calls.toList())

        // Verdict cached: the second press no longer asks the daemon.
        c.pressAndStop(asr)
        verify(exactly = 2) { capture.duckMusic() }
        assertEquals(listOf(true), calls.toList())
    }

    @Test fun `helper down - media duck and restore as today, deactivate still sent`() {
        val asr = FakeAsr()
        val capture = capture()
        controller(asr, capture, duck(null)).pressAndStop(asr)

        verifyOrder {
            capture.duckMusic()
            capture.restoreMusic(20)
        }
        assertEquals(listOf(true, false), calls.toList())
    }

    @Test fun `no firmware duck configured - today's duck, no daemon calls`() {
        val asr = FakeAsr()
        val capture = capture()
        controller(asr, capture, AutoVoiceDuck.OFF).pressAndStop(asr)

        verify(exactly = 1) { capture.duckMusic() }
        verify { capture.restoreMusic(20) }
        assertTrue(calls.isEmpty())
    }
}
