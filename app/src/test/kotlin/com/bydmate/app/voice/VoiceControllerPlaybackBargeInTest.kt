package com.bydmate.app.voice

import android.content.Context
import com.bydmate.app.R
import com.bydmate.app.agent.AgentOrchestrator
import com.bydmate.app.agent.AgentResult
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.AutomationEngine
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.util.appStringsOver
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Collections

/**
 * Name barge-in while the agent is speaking: mic frames keep reaching the recognizer during
 * playback, an utterance that overlaps it is never routed, and only a leading agent name
 * stops the agent. The fake recognizer records the frames that passed the controller's filter,
 * so a test can wait until the filter has seen a frame before it emits the VAD events.
 */
class VoiceControllerPlaybackBargeInTest {

    private class FakeContinuousAsr : ContinuousAsr {
        val events = MutableSharedFlow<ContinuousAsrEvent>(extraBufferCapacity = 16)
        val recordedFrames: MutableList<ShortArray> = Collections.synchronizedList(mutableListOf())
        override fun isReady(): Boolean = true
        override fun transcribe(pcm: Flow<ShortArray>): Flow<ContinuousAsrEvent> = flow {
            coroutineScope {
                val collectJob: Job = launch { pcm.collect { recordedFrames.add(it) } }
                try {
                    emitAll(events)
                } finally {
                    collectJob.cancel()
                }
            }
        }
    }

    private class Rig(
        val controller: VoiceController,
        val asr: FakeContinuousAsr,
        val tts: TtsEngine,
        val earcon: VoiceEarcon,
        val dispatcher: ActionDispatcher,
        val journal: VoiceJournal,
    )

    // The raw mic and the TTS engine's physical playback signal, shared by every rig of a test.
    private val frames = MutableSharedFlow<ShortArray>(extraBufferCapacity = 8)
    @Volatile private var audible = false

    private fun awaitTrue(timeoutMs: Long = 2_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        if (!condition()) fail("condition not met within ${timeoutMs}ms")
    }

    private fun rig(orchestrator: AgentOrchestrator, ttsEnabled: Boolean = false, name: String = "Лео"): Rig {
        val asr = FakeContinuousAsr()
        val audioCapture = mockk<AudioCapture>(relaxed = true)
        every { audioCapture.captureSession(any()) } returns frames
        val tts = mockk<TtsEngine>(relaxed = true)
        every { tts.speaking } returns MutableStateFlow(false)
        every { tts.audible() } answers { audible }
        every { tts.speak(any()) } returns true
        val gate = mockk<VoiceGate>()
        every { gate.isEnabled() } returns true
        every { gate.vehicleSnapshot() } returns null
        every { gate.ttsEnabled() } returns ttsEnabled
        val resolver = mockk<VoiceAutomationResolver>()
        coEvery { resolver.match(any()) } returns null
        val context = mockk<Context>(relaxed = true).also {
            every { it.getString(R.string.voice_listening) } returns "Слушаю"
            every { it.getString(R.string.voice_thinking) } returns "Думаю"
        }
        val earcon = mockk<VoiceEarcon>(relaxed = true)
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val journal = VoiceJournal()
        val controller = VoiceController(audioCapture, dispatcher, earcon, gate,
            mockk<AutomationEngine>(relaxed = true), resolver, orchestrator, context,
            tts, journal, asr,
            agentIdentity = { AgentIdentity(name, AgentPersona.NAVIGATOR) },
            ttsModelManager = mockk(relaxed = true),
            ruStressMarker = RuStressMarker { null },
            selectedTtsVoice = { TtsVoiceCatalog.byId("dmitri") },
            appStrings = appStringsOver(context))
        controller.updateListeningOverlay = {}
        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitTrue { frames.subscriptionCount.value >= 1 && asr.events.subscriptionCount.value >= 1 }
        clearMocks(tts, earcon, answers = false)
        return Rig(controller, asr, tts, earcon, dispatcher, journal)
    }

    private fun orchestrator(answer: AgentResult = AgentResult.Disabled) = mockk<AgentOrchestrator>().also {
        coEvery { it.ask(any(), any(), any()) } returns answer
        coEvery { it.noteAction(any()) } returns Unit
        coEvery { it.expectsFollowUp() } returns false
    }

    /** One VAD segment heard while the agent's audio is audible: a frame the filter has seen,
     *  then SpeechStart and the decoded utterance. */
    private fun Rig.hearDuringPlayback(text: String) {
        audible = true
        val before = asr.recordedFrames.size
        frames.tryEmit(shortArrayOf(1))
        awaitTrue { asr.recordedFrames.size > before }
        asr.events.tryEmit(ContinuousAsrEvent.SpeechStart)
        asr.events.tryEmit(ContinuousAsrEvent.Utterance(text))
    }

    private fun VoiceJournal.bargeIns() = entries.value.filter { it.refusal == VoiceRefusal.BARGE_IN }

    @Test fun `name while the last sentences play and no ask is in flight barges in without routing`() {
        val orchestrator = orchestrator()
        val r = rig(orchestrator, name = "Лёша")

        r.hearDuringPlayback("леш посмотри") // how GigaAM renders "Лёша, посмотри" in field logs

        awaitTrue { r.journal.bargeIns().size == 1 }
        verify(exactly = 1) { r.tts.stop() }
        verify(exactly = 1) { r.earcon.ok() }
        assertEquals(VoiceUiState.Listening, r.controller.state.value)
        assertEquals(0, r.controller.droppedDuringPlaybackForTest())
        assertEquals(VoiceJournalEntry.Route.AGENT, r.journal.bargeIns().single().route)
        coVerify(exactly = 0) { orchestrator.ask(any(), any(), any()) }
        coVerify(exactly = 0) { r.dispatcher.dispatch(any<ActionDef>(), any()) }
        assertTrue(r.controller.listening.value)
    }

    @Test fun `name during playback cancels the ask in flight`() {
        val askStarted = CompletableDeferred<Unit>()
        val askCancelled = CompletableDeferred<Unit>()
        val orchestrator = orchestrator()
        coEvery { orchestrator.ask(any(), any(), any()) } coAnswers {
            askStarted.complete(Unit)
            try {
                CompletableDeferred<Unit>().await()
                AgentResult.Disabled
            } catch (ce: CancellationException) {
                askCancelled.complete(Unit)
                throw ce
            }
        }
        val r = rig(orchestrator)

        r.asr.events.tryEmit(ContinuousAsrEvent.Utterance("расскажи про заряд")) // before any playback: routed
        awaitTrue { askStarted.isCompleted }
        r.hearDuringPlayback("Лео")

        awaitTrue { askCancelled.isCompleted }
        awaitTrue { r.controller.routingJobForTest() == null }
        verify(atLeast = 1) { r.tts.stop() }
        assertEquals(VoiceUiState.Listening, r.controller.state.value)
        assertTrue(r.journal.bargeIns().any { it.outcome == VoiceJournalEntry.Outcome.OK && it.transcript == "Лео" })
        coVerify(exactly = 1) { orchestrator.ask(any(), any(), any()) }
    }

    @Test fun `a phrase without the name during playback is dropped, not routed`() {
        val orchestrator = orchestrator()
        val r = rig(orchestrator)

        r.hearDuringPlayback("закрой окна")
        // A name later in the phrase does not count: the segment began with the agent's voice.
        r.hearDuringPlayback("погода хорошая Лео")

        awaitTrue { r.controller.droppedDuringPlaybackForTest() == 2 }
        verify(exactly = 0) { r.tts.stop() }
        assertTrue(r.journal.entries.value.isEmpty())
        coVerify(exactly = 0) { r.dispatcher.dispatch(any<ActionDef>(), any()) }
        coVerify(exactly = 0) { orchestrator.ask(any(), any(), any()) }
    }

    @Test fun `the agent speaking its own name does not barge in on itself`() {
        val orchestrator = orchestrator(AgentResult.Answer("Лео на связи."))
        val r = rig(orchestrator, ttsEnabled = true)

        r.asr.events.tryEmit(ContinuousAsrEvent.Utterance("как тебя зовут"))
        awaitTrue { r.controller.lastSpeakingSeenMs > 0L } // the answer went to TTS
        verify { r.tts.speak("Лео на связи.") }
        r.hearDuringPlayback("Лео на связи") // its own voice, echoed back through the mic

        awaitTrue { r.controller.droppedDuringPlaybackForTest() == 1 }
        verify(exactly = 0) { r.tts.stop() }
        assertTrue(r.journal.bargeIns().isEmpty())
        assertFalse(r.journal.entries.value.any { it.transcript == "Лео на связи" })
    }
}
