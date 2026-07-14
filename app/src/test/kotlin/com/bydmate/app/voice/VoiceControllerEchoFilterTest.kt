package com.bydmate.app.voice

import android.content.Context
import com.bydmate.app.agent.AgentOrchestrator
import com.bydmate.app.agent.AgentResult
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.AutomationEngine
import com.bydmate.app.data.automation.DispatchResult
import com.bydmate.app.data.local.LocalePreferences
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Coverage for SelfEchoFilter wired into VoiceController's continuous-session
 * routeUtterance path (Task 2): a transcript matching recently spoken TTS phrases
 * is filtered and journaled as an echo, never reaching NLU/agent routing.
 */
class VoiceControllerEchoFilterTest {

    private class FakeContinuousAsr : ContinuousAsr {
        val events = MutableSharedFlow<ContinuousAsrEvent>(extraBufferCapacity = 16)
        override fun isReady(): Boolean = true
        override fun transcribe(pcm: Flow<ShortArray>): Flow<ContinuousAsrEvent> =
            flow { emitAll(events) }
    }

    private fun awaitTrue(timeoutMs: Long = 2_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        if (!condition()) fail("condition not met within ${timeoutMs}ms")
    }

    private fun awaitSubscribed(events: MutableSharedFlow<*>) = awaitTrue { events.subscriptionCount.value >= 1 }

    private fun quietTtsEngine(): TtsEngine = mockk<TtsEngine>(relaxed = true) {
        every { speaking } returns MutableStateFlow(false)
        every { audible() } returns false
    }

    private fun makeController(
        continuousAsr: ContinuousAsr,
        dispatcher: ActionDispatcher,
        echoFilter: SelfEchoFilter = SelfEchoFilter(),
        journal: VoiceJournal = VoiceJournal(),
        ttsEngine: TtsEngine = quietTtsEngine(),
        earcon: VoiceEarcon = mockk(relaxed = true),
        ttsEnabled: Boolean = false,
        agentIdentity: () -> AgentIdentity = { AgentIdentity("Джез", AgentPersona.NAVIGATOR) },
    ): VoiceController {
        val gate = mockk<VoiceGate>()
        every { gate.isEnabled() } returns true
        every { gate.vehicleSnapshot() } returns null
        every { gate.preferredLang() } returns null
        every { gate.ttsEnabled() } returns ttsEnabled

        val audioCapture = mockk<AudioCapture>(relaxed = true)
        every { audioCapture.captureSession(any()) } returns flow { }

        val localePrefs = mockk<LocalePreferences>(relaxed = true)
        every { localePrefs.getLanguage() } returns "ru"
        val automationEngine = mockk<AutomationEngine>(relaxed = true)
        val automationResolver = mockk<VoiceAutomationResolver>(relaxed = true)
        coEvery { automationResolver.match(any()) } returns null

        val agentOrchestrator = mockk<AgentOrchestrator>(relaxed = true)
        coEvery { agentOrchestrator.expectsFollowUp() } returns false

        return VoiceController(
            audioCapture, dispatcher, localePrefs, earcon, gate,
            automationEngine, automationResolver, agentOrchestrator,
            mockk<Context>(relaxed = true), ttsEngine, journal,
            continuousAsr,
            agentIdentity = agentIdentity,
            ttsModelManager = mockk(relaxed = true),
            ruStressMarker = RuStressMarker { null },
            selectedTtsVoice = { TtsVoiceCatalog.byId("dmitri") },
            echoFilter = echoFilter
        )
    }

    @Test fun `echo transcript is filtered, journaled as 'Эхо своей речи', not routed to NLU`() {
        val echoFilter = SelfEchoFilter()
        val journal = VoiceJournal()
        val fakeContinuousAsr = FakeContinuousAsr()
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val earcon = mockk<VoiceEarcon>(relaxed = true)
        val ttsEngine = mockk<TtsEngine>(relaxed = true) {
            every { speaking } returns MutableStateFlow(false)
            every { audible() } returns false
        }

        val controller = makeController(
            continuousAsr = fakeContinuousAsr,
            dispatcher = dispatcher,
            echoFilter = echoFilter,
            journal = journal,
            ttsEngine = ttsEngine,
            earcon = earcon,
            ttsEnabled = true,
        )

        // Simulate TTS speaking: note a phrase that will later be echoed.
        echoFilter.noteSpoken("открой окно")
        echoFilter.onPlaybackEnd()

        // Start continuous session (this fires earcon.ok() -- clear it before asserting silence below).
        controller.onPttPressed()
        awaitSubscribed(fakeContinuousAsr.events)
        earcon.let { clearMocks(it, answers = false, recordedCalls = true, childMocks = false) }
        fakeContinuousAsr.events.tryEmit(ContinuousAsrEvent.Utterance("открой окно"))

        // Wait for the echo check to complete.
        awaitTrue { journal.entries.value.isNotEmpty() }

        // The transcript should be journaled as an echo with route=NONE, outcome=NOT_UNDERSTOOD.
        val entries = journal.entries.value
        assertEquals("Journal should have exactly 1 entry", 1, entries.size)
        val entry = entries[0]
        assertEquals("Route should be NONE", VoiceJournalEntry.Route.NONE, entry.route)
        assertEquals("Outcome should be NOT_UNDERSTOOD", VoiceJournalEntry.Outcome.NOT_UNDERSTOOD, entry.outcome)
        assertEquals("Reason should be 'Эхо своей речи'", "Эхо своей речи", entry.reason)
        assertEquals("Transcript should be preserved", "открой окно", entry.transcript)

        // Silent drop: no earcon, no spoken "Не понял", no state flip to NotUnderstood.
        verify(exactly = 0) { earcon.fail() }
        verify(exactly = 0) { ttsEngine.speak(any()) }
        assertEquals("State must not switch to NotUnderstood", VoiceUiState.Listening, controller.state.value)
    }

    @Test fun `non-echo transcript passes through to NLU resolution`() {
        val journal = VoiceJournal()
        val fakeContinuousAsr = FakeContinuousAsr()
        val dispatcher = mockk<ActionDispatcher>()
        coEvery { dispatcher.dispatch(any(), any()) } returns DispatchResult(success = true, reason = null)

        val controller = makeController(
            continuousAsr = fakeContinuousAsr,
            dispatcher = dispatcher,
            journal = journal
        )

        controller.onPttPressed()
        awaitSubscribed(fakeContinuousAsr.events)
        fakeContinuousAsr.events.tryEmit(ContinuousAsrEvent.Utterance("закрой окно"))

        // Wait for NLU routing to complete.
        awaitTrue { journal.entries.value.isNotEmpty() }

        // The transcript should be journaled as a successful NLU route, not an echo.
        val entries = journal.entries.value
        assertEquals("Journal should have exactly 1 entry", 1, entries.size)
        val entry = entries[0]
        assertEquals("Route should be NLU", VoiceJournalEntry.Route.NLU, entry.route)
        assertEquals("Outcome should be OK", VoiceJournalEntry.Outcome.OK, entry.outcome)
    }

    @Test fun `noteSpoken is called when TTS speaks a successful command announce`() {
        val echoFilter = mockk<SelfEchoFilter>(relaxed = true)
        val journal = VoiceJournal()
        val fakeContinuousAsr = FakeContinuousAsr()
        val dispatcher = mockk<ActionDispatcher>()
        coEvery { dispatcher.dispatch(any(), any()) } returns DispatchResult(success = true, reason = null)
        val ttsEngine = mockk<TtsEngine>(relaxed = true) {
            every { speaking } returns MutableStateFlow(false)
            every { audible() } returns false
            every { speak(any()) } returns true
        }

        val controller = makeController(
            continuousAsr = fakeContinuousAsr,
            dispatcher = dispatcher,
            echoFilter = echoFilter,
            journal = journal,
            ttsEngine = ttsEngine,
            ttsEnabled = true,
        )

        controller.onPttPressed()
        awaitSubscribed(fakeContinuousAsr.events)
        fakeContinuousAsr.events.tryEmit(ContinuousAsrEvent.Utterance("закрой окно"))

        awaitTrue { journal.entries.value.isNotEmpty() }

        // announce() speaks the terse outcome phrase via ttsEngine.speak() and must stamp it in the
        // echo filter -- otherwise the agent's own spoken reply would leak back in as a fresh command.
        verify(exactly = 1) { ttsEngine.speak(any()) }
        verify(exactly = 1) { echoFilter.noteSpoken(any()) }
    }

    @Test fun `echo with leading agent name is NOT filtered (name-gated barge-in)`() {
        val echoFilter = SelfEchoFilter()
        val journal = VoiceJournal()
        val fakeContinuousAsr = FakeContinuousAsr()
        val dispatcher = mockk<ActionDispatcher>()
        // Dispatch may succeed or fail, but the key point is it should be called (not filtered as echo).
        coEvery { dispatcher.dispatch(any(), any()) } returns DispatchResult(success = false, reason = "test block")

        val controller = makeController(
            continuousAsr = fakeContinuousAsr,
            dispatcher = dispatcher,
            echoFilter = echoFilter,
            journal = journal,
            agentIdentity = { AgentIdentity("Джез", AgentPersona.NAVIGATOR) }
        )

        // Simulate TTS speaking: note the command phrase (without the name).
        echoFilter.noteSpoken("открой окно")
        echoFilter.onPlaybackEnd()

        controller.onPttPressed()
        awaitSubscribed(fakeContinuousAsr.events)
        // Emit the transcript WITH the agent name prefix.
        fakeContinuousAsr.events.tryEmit(ContinuousAsrEvent.Utterance("Джез, открой окно"))

        // Wait for routing to complete.
        awaitTrue { journal.entries.value.isNotEmpty() }

        // The transcript "Джез, открой окно" should be routed (not filtered as echo)
        // because it has the agent name prefix. Even if dispatch fails, the route should be NLU, not NONE.
        val entries = journal.entries.value
        assertEquals("Journal should have 1 entry", 1, entries.size)
        val entry = entries[0]
        assertEquals("Route should be NLU (not NONE)", VoiceJournalEntry.Route.NLU, entry.route)
    }
}
