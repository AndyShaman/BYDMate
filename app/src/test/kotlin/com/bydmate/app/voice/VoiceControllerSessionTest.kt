package com.bydmate.app.voice

import android.content.Context
import com.bydmate.app.agent.AgentOrchestrator
import com.bydmate.app.agent.AgentResult
import com.bydmate.app.agent.AgentToolOutcome
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.AutomationEngine
import com.bydmate.app.data.automation.DispatchResult
import com.bydmate.app.data.automation.VoiceFireResult
import com.bydmate.app.R
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.local.entity.ActionDef
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Wave B: PTT-toggled continuous session (onPttPressed / startContinuousSession /
 * stopContinuousSession) in VoiceController. A plain Kotlin fake stands in for
 * ContinuousAsr — its transcribe() replays whatever the test pushes into [events], while
 * ALSO actually collecting the incoming pcm flow (recordedFrames / collecting), which is
 * what lets tests prove the mute filter and cancellation wiring, not just the routing.
 */
class VoiceControllerSessionTest {

    private class FakeContinuousAsr(var ready: Boolean) : ContinuousAsr {
        val events = MutableSharedFlow<ContinuousAsrEvent>(extraBufferCapacity = 16)
        // Frames that actually survived the controller's mute filter and reached transcribe().
        val recordedFrames: MutableList<ShortArray> = Collections.synchronizedList(mutableListOf())
        @Volatile var collecting = false
        override fun isReady(): Boolean = ready
        override fun transcribe(pcm: Flow<ShortArray>): Flow<ContinuousAsrEvent> = flow {
            coroutineScope {
                val collectJob: Job = launch {
                    collecting = true
                    try {
                        pcm.collect { recordedFrames.add(it) }
                    } finally {
                        collecting = false
                    }
                }
                try {
                    emitAll(events)
                } finally {
                    collectJob.cancel()
                }
            }
        }
    }

    /** GigaAM downloaded but transcribe() itself throws (e.g. a corrupted model file after a
     *  partial delete) the instant it sees the first pcm frame -- simulates degradation matrix
     *  scenario (a). No events are ever emitted; the collect chain fails synchronously instead. */
    private class FailingContinuousAsr : ContinuousAsr {
        override fun isReady(): Boolean = true
        override fun transcribe(pcm: Flow<ShortArray>): Flow<ContinuousAsrEvent> = flow {
            pcm.collect { throw RuntimeException("gigaam decode failed") }
        }
    }

    /** Polls [condition] instead of a blind Thread.sleep -- deterministic-ish and fails fast in
     *  the common case. VoiceController's coroutine scope is a real, non-injected
     *  CoroutineScope(SupervisorJob()) with no test-dispatcher seam, so true virtual-time control
     *  (runTest) is not available without changing the production constructor; this is the closest
     *  practical alternative. */
    private fun awaitTrue(timeoutMs: Long = 2_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        if (!condition()) fail("condition not met within ${timeoutMs}ms")
    }

    /** `listening` flips true synchronously inside onPttPressed(), before the session coroutine
     *  has actually started running on its dispatcher -- so waiting on `listening` alone races
     *  with FakeContinuousAsr's emitAll(events) subscribing. subscriptionCount is the real
     *  barrier: a tryEmit before any collector attaches (replay = 0) is simply lost. */
    private fun awaitSubscribed(events: MutableSharedFlow<*>) = awaitTrue { events.subscriptionCount.value >= 1 }

    /** Same idea as [awaitTrue] but for a block of mockk verifications, which throw
     *  AssertionError on mismatch rather than returning a Boolean. */
    private fun awaitVerify(timeoutMs: Long = 2_000L, block: () -> Unit) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastError: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                block()
                return
            } catch (t: Throwable) {
                lastError = t
                Thread.sleep(20)
            }
        }
        block() // let the final failure surface with mockk's own message
        lastError?.let { throw it }
    }

    /** A relaxed TtsEngine mock's `speaking` StateFlow<Boolean> property, being a mocked
     *  interface itself, has no real backing state and its collect() never suspends the way a
     *  real StateFlow does -- every test that runs a continuous session needs a stable, real
     *  StateFlow backing `speaking` for the session's internal speakingWatcher to collect
     *  safely. Tests that care about mute behaviour stub `speaking` explicitly instead. */
    private fun quietTtsEngine(): TtsEngine = mockk<TtsEngine>(relaxed = true) {
        every { speaking } returns MutableStateFlow(false)
    }

    private fun makeController(
        continuousAsr: ContinuousAsr,
        dispatcher: ActionDispatcher,
        agentOrchestrator: AgentOrchestrator = mockk<AgentOrchestrator>().also {
            coEvery { it.ask(any(), any()) } returns AgentResult.Disabled
            coEvery { it.noteAction(any()) } returns Unit
            // Default: no pending agent question (individual tests override AFTER construction).
            coEvery { it.expectsFollowUp() } returns false
        },
        ttsEngine: TtsEngine = quietTtsEngine(),
        audioCapture: AudioCapture = mockk<AudioCapture>(relaxed = true).also {
            every { it.captureSession(any()) } returns flow { /* fake ignores pcm content */ }
        },
        journal: VoiceJournal = VoiceJournal(),
        earcon: VoiceEarcon = mockk(relaxed = true),
        context: Context = mockk<Context>(relaxed = true),
        agentIdentity: () -> AgentIdentity = { AgentIdentity("", AgentPersona.NAVIGATOR) },
    ): VoiceController {
        val gate = mockk<VoiceGate>()
        every { gate.isEnabled() } returns true
        every { gate.vehicleSnapshot() } returns null
        every { gate.preferredLang() } returns null
        every { gate.ttsEnabled() } returns false

        val localePrefs = mockk<LocalePreferences>(relaxed = true)
        every { localePrefs.getLanguage() } returns "ru"
        val automationEngine = mockk<AutomationEngine>(relaxed = true)
        val automationResolver = mockk<VoiceAutomationResolver>()
        coEvery { automationResolver.match(any()) } returns null

        return VoiceController(audioCapture, dispatcher, localePrefs, earcon, gate,
            automationEngine, automationResolver, agentOrchestrator, context,
            ttsEngine, journal, continuousAsr, agentIdentity = agentIdentity,
            ttsModelManager = mockk(relaxed = true),
            ruStressMarker = RuStressMarker { null },
            selectedTtsVoice = { TtsVoiceCatalog.byId("dmitri") })
    }

    // (a) PTT with isReady=true routes each Utterance into the NLU dispatch path, and the
    // session keeps listening for a second Utterance without a second PTT press.
    @Test fun `ptt press starts continuous session and routes each utterance to NLU dispatch`() {
        val fakeAsr = FakeContinuousAsr(ready = true)
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        coEvery { dispatcher.dispatch(any<ActionDef>(), any()) } returns DispatchResult(true)
        val journal = VoiceJournal()
        val controller = makeController(fakeAsr, dispatcher, journal = journal)

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)

        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("закрой окна"))
        awaitVerify { coVerify(exactly = 1) { dispatcher.dispatch(match { it.command == "车窗关闭" }, any()) } }
        awaitTrue { journal.entries.value.size == 1 && controller.routingJobForTest() == null }
        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("закрой окна"))
        awaitVerify { coVerify(exactly = 2) { dispatcher.dispatch(match { it.command == "车窗关闭" }, any()) } }

        assertTrue(controller.listening.value)
    }

    @Test fun `utterance while previous is routing is dropped without journal or earcon`() {
        val fakeAsr = FakeContinuousAsr(ready = true)
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val dispatchStarted = AtomicBoolean(false)
        val releaseDispatch = AtomicBoolean(false)
        coEvery { dispatcher.dispatch(any<ActionDef>(), any()) } coAnswers {
            dispatchStarted.set(true)
            while (!releaseDispatch.get()) delay(10)
            DispatchResult(true)
        }
        val journal = VoiceJournal()
        val earcon = mockk<VoiceEarcon>(relaxed = true)
        val controller = makeController(fakeAsr, dispatcher, journal = journal, earcon = earcon)

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)
        clearMocks(earcon)

        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("закрой окна"))
        awaitTrue { dispatchStarted.get() }
        // The real GigaAM engine emits SpeechStart before every Utterance; while busy it must
        // not clobber the Thinking state either. droppedWhileBusyForTest() is the deterministic
        // signal that the sequential collect has consumed both events (no journal/earcon/state
        // side effects exist to await on a busy drop).
        fakeAsr.events.tryEmit(ContinuousAsrEvent.SpeechStart)
        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("закрой окна"))
        awaitTrue { controller.droppedWhileBusyForTest() == 1 }

        assertEquals(VoiceUiState.Thinking, controller.state.value)
        assertTrue(journal.entries.value.isEmpty())
        verify(exactly = 0) { earcon.ok() }
        verify(exactly = 0) { earcon.fail() }

        releaseDispatch.set(true)
        awaitTrue { journal.entries.value.size == 1 }
        awaitTrue { controller.routingJobForTest() == null }

        coVerify(exactly = 1) { dispatcher.dispatch(match { it.command == "车窗关闭" }, any()) }
        assertEquals(1, journal.entries.value.size)
    }

    @Test fun `next utterance after routing completes is processed normally`() {
        val fakeAsr = FakeContinuousAsr(ready = true)
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val dispatchCalls = AtomicInteger(0)
        val releaseFirst = AtomicBoolean(false)
        coEvery { dispatcher.dispatch(any<ActionDef>(), any()) } coAnswers {
            val call = dispatchCalls.incrementAndGet()
            if (call == 1) {
                while (!releaseFirst.get()) delay(10)
            }
            DispatchResult(true)
        }
        val journal = VoiceJournal()
        val controller = makeController(fakeAsr, dispatcher, journal = journal)

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)

        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("закрой окна"))
        awaitTrue { dispatchCalls.get() == 1 }
        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("закрой окна"))

        releaseFirst.set(true)
        awaitTrue { journal.entries.value.size == 1 }
        awaitTrue { controller.routingJobForTest() == null }

        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("закрой окна"))
        awaitTrue { journal.entries.value.size == 2 }

        coVerify(exactly = 2) { dispatcher.dispatch(match { it.command == "车窗关闭" }, any()) }
    }

    @Test fun `agent name utterance while ask is running cancels ask and keeps listening`() {
        val fakeAsr = FakeContinuousAsr(ready = true)
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        coEvery { dispatcher.dispatch(any<ActionDef>(), any()) } returns DispatchResult(true)
        val askStarted = CompletableDeferred<Unit>()
        val askCancelled = CompletableDeferred<Unit>()
        val releaseAsk = CompletableDeferred<Unit>()
        val agentOrchestrator = mockk<AgentOrchestrator>()
        coEvery { agentOrchestrator.ask(any(), any()) } coAnswers {
            askStarted.complete(Unit)
            try {
                releaseAsk.await()
                AgentResult.Answer("Поздний ответ")
            } catch (ce: CancellationException) {
                askCancelled.complete(Unit)
                throw ce
            }
        }
        coEvery { agentOrchestrator.noteAction(any()) } returns Unit
        coEvery { agentOrchestrator.expectsFollowUp() } returns false
        val journal = VoiceJournal()
        val earcon = mockk<VoiceEarcon>(relaxed = true)
        val ttsEngine = quietTtsEngine()
        val controller = makeController(
            fakeAsr,
            dispatcher,
            agentOrchestrator = agentOrchestrator,
            journal = journal,
            earcon = earcon,
            ttsEngine = ttsEngine,
            context = stubbedContext(),
            agentIdentity = { AgentIdentity("Лео", AgentPersona.NAVIGATOR) },
        )
        val overlayUpdates = Collections.synchronizedList(mutableListOf<String>())
        controller.updateListeningOverlay = { text -> overlayUpdates.add(text) }

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)
        clearMocks(ttsEngine, earcon, answers = false)

        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("расскажи про заряд"))
        awaitTrue { askStarted.isCompleted }
        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("Лео"))

        awaitTrue { askCancelled.isCompleted }
        awaitTrue { controller.routingJobForTest() == null }
        assertEquals(VoiceUiState.Listening, controller.state.value)
        assertTrue(controller.listening.value)
        assertTrue(overlayUpdates.contains("Слушаю"))
        verify(exactly = 1) { ttsEngine.stop() }
        verify(atLeast = 1) { earcon.ok() }
        assertTrue(journal.entries.value.any {
            it.route == VoiceJournalEntry.Route.NONE &&
                it.outcome == VoiceJournalEntry.Outcome.OK &&
                it.detail == "Прерван по имени"
        })

        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("закрой окна"))
        awaitVerify { coVerify(exactly = 1) { dispatcher.dispatch(match { it.command == "车窗关闭" }, any()) } }
    }

    @Test fun `non-name utterance while agent ask is running is still dropped as busy`() {
        val fakeAsr = FakeContinuousAsr(ready = true)
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val askStarted = CompletableDeferred<Unit>()
        val askCancelled = CompletableDeferred<Unit>()
        val releaseAsk = CompletableDeferred<Unit>()
        val agentOrchestrator = mockk<AgentOrchestrator>()
        coEvery { agentOrchestrator.ask(any(), any()) } coAnswers {
            askStarted.complete(Unit)
            try {
                releaseAsk.await()
                AgentResult.Answer("Готово")
            } catch (ce: CancellationException) {
                askCancelled.complete(Unit)
                throw ce
            }
        }
        coEvery { agentOrchestrator.noteAction(any()) } returns Unit
        coEvery { agentOrchestrator.expectsFollowUp() } returns false
        val journal = VoiceJournal()
        val controller = makeController(
            fakeAsr,
            dispatcher,
            agentOrchestrator = agentOrchestrator,
            journal = journal,
            agentIdentity = { AgentIdentity("Лео", AgentPersona.NAVIGATOR) },
        )

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)

        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("расскажи про заряд"))
        awaitTrue { askStarted.isCompleted }
        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("открой дверь"))

        awaitTrue { controller.droppedWhileBusyForTest() == 1 }
        assertFalse(askCancelled.isCompleted)
        releaseAsk.complete(Unit)
        awaitTrue { journal.entries.value.any { it.route == VoiceJournalEntry.Route.AGENT } }
    }

    @Test fun `blank agent name disables name barge-in and drops the utterance as busy`() {
        val fakeAsr = FakeContinuousAsr(ready = true)
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val askStarted = CompletableDeferred<Unit>()
        val askCancelled = CompletableDeferred<Unit>()
        val releaseAsk = CompletableDeferred<Unit>()
        val agentOrchestrator = mockk<AgentOrchestrator>()
        coEvery { agentOrchestrator.ask(any(), any()) } coAnswers {
            askStarted.complete(Unit)
            try {
                releaseAsk.await()
                AgentResult.Answer("Готово")
            } catch (ce: CancellationException) {
                askCancelled.complete(Unit)
                throw ce
            }
        }
        coEvery { agentOrchestrator.noteAction(any()) } returns Unit
        coEvery { agentOrchestrator.expectsFollowUp() } returns false
        val ttsEngine = quietTtsEngine()
        val journal = VoiceJournal()
        val controller = makeController(
            fakeAsr,
            dispatcher,
            agentOrchestrator = agentOrchestrator,
            journal = journal,
            ttsEngine = ttsEngine,
            agentIdentity = { AgentIdentity("", AgentPersona.NAVIGATOR) },
        )

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)
        clearMocks(ttsEngine, answers = false)

        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("расскажи про заряд"))
        awaitTrue { askStarted.isCompleted }
        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("Лео"))

        awaitTrue { controller.droppedWhileBusyForTest() == 1 }
        assertFalse(askCancelled.isCompleted)
        verify(exactly = 0) { ttsEngine.stop() }
        releaseAsk.complete(Unit)
        awaitTrue { journal.entries.value.any { it.route == VoiceJournalEntry.Route.AGENT } }
    }

    @Test fun `silence autostop is suppressed while an utterance is being routed`() {
        val fakeAsr = FakeContinuousAsr(ready = true)
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val dispatchStarted = AtomicBoolean(false)
        val releaseDispatch = AtomicBoolean(false)
        coEvery { dispatcher.dispatch(any<ActionDef>(), any()) } coAnswers {
            dispatchStarted.set(true)
            while (!releaseDispatch.get()) delay(10)
            DispatchResult(true)
        }
        val journal = VoiceJournal()
        val controller = makeController(fakeAsr, dispatcher, journal = journal)

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)

        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("закрой окна"))
        awaitTrue { dispatchStarted.get() }
        fakeAsr.events.tryEmit(ContinuousAsrEvent.SilenceTick(60_000L))
        // Marker utterance: when its busy drop is observed, the sequential collect has
        // definitely consumed the preceding SilenceTick without killing the session.
        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("маркер"))
        awaitTrue { controller.droppedWhileBusyForTest() == 1 }
        assertTrue(controller.listening.value)

        releaseDispatch.set(true)
        awaitTrue { journal.entries.value.size == 1 }
        awaitTrue { controller.routingJobForTest() == null }

        assertTrue(controller.listening.value)
    }

    @Test fun `routing job is set while routing and cleared after completion`() {
        val fakeAsr = FakeContinuousAsr(ready = true)
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val dispatchStarted = AtomicBoolean(false)
        val releaseDispatch = AtomicBoolean(false)
        coEvery { dispatcher.dispatch(any<ActionDef>(), any()) } coAnswers {
            dispatchStarted.set(true)
            while (!releaseDispatch.get()) delay(10)
            DispatchResult(true)
        }
        val controller = makeController(fakeAsr, dispatcher)

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)

        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("закрой окна"))
        awaitTrue { dispatchStarted.get() }
        assertTrue(controller.routingJobForTest() is Job)

        releaseDispatch.set(true)
        awaitTrue { controller.routingJobForTest() == null }
    }

    // (b) A second PTT press while listening stops the session immediately (barge-in stop TTS);
    // an utterance pushed after the stop is no longer routed (the collector was cancelled).
    @Test fun `second ptt press stops the session and further utterances are not routed`() {
        val fakeAsr = FakeContinuousAsr(ready = true)
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val ttsEngine = quietTtsEngine()
        // makeController's default audioCapture is an empty flow{} that completes on first
        // collection -- unsuitable here since we need `collecting` to stay true until we
        // deliberately stop the session, not flip false on its own the instant it's collected.
        val rawFrames = MutableSharedFlow<ShortArray>()
        val audioCapture = mockk<AudioCapture>(relaxed = true)
        every { audioCapture.captureSession(any()) } returns rawFrames
        val controller = makeController(fakeAsr, dispatcher, ttsEngine = ttsEngine, audioCapture = audioCapture)

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitTrue { fakeAsr.collecting }

        controller.onPttPressed()
        awaitTrue { !controller.listening.value }
        // Poll until the pcm collector has actually torn down (not just the listening flag) --
        // once true, the utterance loop is provably no longer running, so the negative assertion
        // below needs no arbitrary wait to be non-racy.
        awaitTrue { !fakeAsr.collecting }
        verify(atLeast = 1) { ttsEngine.stop() }

        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("закрой окна"))
        coVerify(exactly = 0) { dispatcher.dispatch(any(), any()) }
    }

    // (c) SilenceTick reaching the 30s auto-stop threshold ends the session on its own. The tick
    // is exactly 30_000 so this test pins the Wave P threshold: it fails if anyone reverts to 60s.
    @Test fun `silence tick at 30s auto-stops the session`() {
        val fakeAsr = FakeContinuousAsr(ready = true)
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val controller = makeController(fakeAsr, dispatcher)

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)

        fakeAsr.events.tryEmit(ContinuousAsrEvent.SilenceTick(30_000L))

        awaitTrue { !controller.listening.value }
    }

    // (e) A follow-up question from the agent (AgentAnswer) does not end the session — the loop
    // keeps listening for the driver's reply without a second PTT press.
    @Test fun `agent answer to an utterance leaves the session listening`() {
        val fakeAsr = FakeContinuousAsr(ready = true)
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val agentOrchestrator = mockk<AgentOrchestrator>()
        coEvery { agentOrchestrator.ask(any(), any()) } returns AgentResult.Answer("Уточните, пожалуйста")
        coEvery { agentOrchestrator.noteAction(any()) } returns Unit
        coEvery { agentOrchestrator.expectsFollowUp() } returns false
        val controller = makeController(fakeAsr, dispatcher, agentOrchestrator = agentOrchestrator)

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)

        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("навигатор"))

        awaitTrue { controller.state.value == VoiceUiState.AgentAnswer("Уточните, пожалуйста") }
        assertTrue(controller.listening.value)
    }

    // (unrelated automation route): a rule match still dispatches through fireAutomation while
    // the continuous session stays open, mirroring the legacy VoiceFireResult.Fired coverage.
    @Test fun `utterance matched by automation resolver fires the rule, session stays open`() {
        val fakeAsr = FakeContinuousAsr(ready = true)
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val automationEngine = mockk<AutomationEngine>(relaxed = true)
        coEvery { automationEngine.fireVoiceRule(any(), any()) } returns VoiceFireResult.Fired(true)

        val gate = mockk<VoiceGate>()
        every { gate.isEnabled() } returns true
        every { gate.vehicleSnapshot() } returns null
        every { gate.preferredLang() } returns null
        every { gate.ttsEnabled() } returns false

        val audioCapture = mockk<AudioCapture>(relaxed = true)
        every { audioCapture.captureSession(any()) } returns flow { }

        val localePrefs = mockk<LocalePreferences>(relaxed = true)
        every { localePrefs.getLanguage() } returns "ru"
        val earcon = mockk<VoiceEarcon>(relaxed = true)

        val automationResolver = mockk<VoiceAutomationResolver>()
        coEvery { automationResolver.match(any()) } returns 42L

        val agentOrchestrator = mockk<AgentOrchestrator>()
        coEvery { agentOrchestrator.noteAction(any()) } returns Unit
        coEvery { agentOrchestrator.expectsFollowUp() } returns false

        val controller = VoiceController(audioCapture, dispatcher, localePrefs, earcon, gate,
            automationEngine, automationResolver, agentOrchestrator, mockk<Context>(relaxed = true),
            quietTtsEngine(), VoiceJournal(), fakeAsr,
            agentIdentity = { AgentIdentity("", AgentPersona.NAVIGATOR) },
            ttsModelManager = mockk(relaxed = true),
            ruStressMarker = RuStressMarker { null },
            selectedTtsVoice = { TtsVoiceCatalog.byId("dmitri") })

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)
        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("навигатор"))

        awaitVerify { coVerify(exactly = 1) { automationEngine.fireVoiceRule(42L, null) } }
        assertTrue(controller.listening.value)
    }

    // --- Finding 4: captureSession contract + cancellation + mute filter ---

    @Test fun `captureSession is invoked exactly once with no session cap (Long MAX_VALUE)`() {
        val fakeAsr = FakeContinuousAsr(ready = true)
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val audioCapture = mockk<AudioCapture>(relaxed = true)
        every { audioCapture.captureSession(any()) } returns flow { }
        val controller = makeController(fakeAsr, dispatcher, audioCapture = audioCapture)

        controller.onPttPressed()
        // `listening` flips synchronously in startContinuousSession(), before the launched
        // session coroutine has necessarily reached captureSession() -- poll the verify itself
        // (like awaitVerify elsewhere) instead of asserting once right after the flag.
        // Wave P: no hard session cap; Long.MAX_VALUE means "run until silence or user stops".
        awaitVerify { verify(exactly = 1) { audioCapture.captureSession(Long.MAX_VALUE) } }
        assertTrue(controller.listening.value)
    }

    @Test fun `stopping the session cancels pcm collection`() {
        val fakeAsr = FakeContinuousAsr(ready = true)
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val rawFrames = MutableSharedFlow<ShortArray>(extraBufferCapacity = 8)
        val audioCapture = mockk<AudioCapture>(relaxed = true)
        every { audioCapture.captureSession(any()) } returns rawFrames
        val controller = makeController(fakeAsr, dispatcher, audioCapture = audioCapture)

        controller.onPttPressed()
        awaitTrue { fakeAsr.collecting }

        controller.onPttPressed() // second press = stop
        awaitTrue { !controller.listening.value }
        awaitTrue { !fakeAsr.collecting }

        // Not just the flag: prove no frame emitted after the confirmed teardown is ever
        // recorded by the fake recognizer -- the collector job has already finished at this
        // point, so this is a deterministic check, not a timing-dependent one.
        val framesBefore = fakeAsr.recordedFrames.size
        rawFrames.tryEmit(shortArrayOf(9, 9, 9))
        assertEquals(framesBefore, fakeAsr.recordedFrames.size)
    }

    @Test fun `frames arriving while tts is speaking never reach the recognizer`() {
        val fakeAsr = FakeContinuousAsr(ready = true)
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val speaking = MutableStateFlow(false)
        val ttsEngine = mockk<TtsEngine>(relaxed = true)
        every { ttsEngine.speaking } returns speaking
        // The filter reads audible(), not speaking.value directly -- this test isn't about the
        // tool-round split, so mirror the two together like a real engine with nothing queued.
        every { ttsEngine.audible() } answers { speaking.value }
        val rawFrames = MutableSharedFlow<ShortArray>(extraBufferCapacity = 8)
        val audioCapture = mockk<AudioCapture>(relaxed = true)
        every { audioCapture.captureSession(any()) } returns rawFrames
        val controller = makeController(fakeAsr, dispatcher, ttsEngine = ttsEngine, audioCapture = audioCapture)

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(rawFrames)

        // Simulate TTS actively speaking: a frame emitted now must be dropped before it ever
        // reaches ContinuousAsr.transcribe().
        speaking.value = true
        rawFrames.tryEmit(shortArrayOf(1, 2, 3))
        Thread.sleep(150)
        assertTrue(fakeAsr.recordedFrames.isEmpty())

        // TTS finishes; past the 300ms post-speech grace window a frame reaches the recognizer.
        speaking.value = false
        Thread.sleep(350)
        rawFrames.tryEmit(shortArrayOf(4, 5, 6))
        awaitTrue { fakeAsr.recordedFrames.isNotEmpty() }
        assertEquals(1, fakeAsr.recordedFrames.size)
    }

    // --- Fix wave 2, finding 3: per-frame grace boundary (no collect()-based watcher) ---

    @Test fun `a frame just inside the post-speech grace window is muted, one after it passes`() {
        val fakeAsr = FakeContinuousAsr(ready = true)
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val speaking = MutableStateFlow(false)
        val ttsEngine = mockk<TtsEngine>(relaxed = true)
        every { ttsEngine.speaking } returns speaking
        every { ttsEngine.audible() } answers { speaking.value }
        val rawFrames = MutableSharedFlow<ShortArray>(extraBufferCapacity = 8)
        val audioCapture = mockk<AudioCapture>(relaxed = true)
        every { audioCapture.captureSession(any()) } returns rawFrames
        val controller = makeController(fakeAsr, dispatcher, ttsEngine = ttsEngine, audioCapture = audioCapture)

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(rawFrames)

        // TTS speaks; a frame arriving during that window is dropped and, as a side effect of
        // the filter itself (there's no background watcher), stamps the grace window's start --
        // lastSpeakingSeenMs is only ever updated when a frame is actually filtered while
        // speaking is true. Poll the stamp itself instead of a fixed sleep: it only flips away
        // from its initial 0 once the filter has actually read speaking=true for this frame, so
        // this is proof the frame was processed before we flip speaking back to false below.
        speaking.value = true
        rawFrames.tryEmit(shortArrayOf(0))
        awaitTrue { controller.lastSpeakingSeenMs > 0L }
        speaking.value = false

        // Still inside the grace window: dropped.
        rawFrames.tryEmit(shortArrayOf(1))
        Thread.sleep(50)
        assertTrue(fakeAsr.recordedFrames.isEmpty())

        // Past the grace window: passes through.
        // Grace was increased to 500ms in Task 2, so wait more than that.
        Thread.sleep(550)
        rawFrames.tryEmit(shortArrayOf(2))
        awaitTrue { fakeAsr.recordedFrames.isNotEmpty() }
    }

    // --- Task 6 (wave M): mic mute keys off physical playback (audible()), not the logical
    // `speaking` flag -- speaking is deliberately held true across a whole streamed reply,
    // including silent tool rounds where the sentence queue stays hot waiting on the LLM. ---

    @Test fun `frames pass the filter during a tool round -- speaking held true but audible false`() {
        val fakeAsr = FakeContinuousAsr(ready = true)
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        // Emulates the queue mid tool-round: logically still "speaking" (queue hot), but nothing
        // is physically playing right now.
        val speaking = MutableStateFlow(true)
        val ttsEngine = mockk<TtsEngine>(relaxed = true)
        every { ttsEngine.speaking } returns speaking
        every { ttsEngine.audible() } returns false
        val rawFrames = MutableSharedFlow<ShortArray>(extraBufferCapacity = 8)
        val audioCapture = mockk<AudioCapture>(relaxed = true)
        every { audioCapture.captureSession(any()) } returns rawFrames
        val controller = makeController(fakeAsr, dispatcher, ttsEngine = ttsEngine, audioCapture = audioCapture)

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(rawFrames)

        rawFrames.tryEmit(shortArrayOf(1, 2, 3))
        awaitTrue { fakeAsr.recordedFrames.isNotEmpty() }
    }

    @Test fun `frames are muted while audible is true even though the logical speaking flag is false`() {
        val fakeAsr = FakeContinuousAsr(ready = true)
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val speaking = MutableStateFlow(false)
        val ttsEngine = mockk<TtsEngine>(relaxed = true)
        every { ttsEngine.speaking } returns speaking
        // Physical playback is actually happening right now -- the filter must key off this,
        // not the (stale/mocked) logical flag.
        every { ttsEngine.audible() } returns true
        val rawFrames = MutableSharedFlow<ShortArray>(extraBufferCapacity = 8)
        val audioCapture = mockk<AudioCapture>(relaxed = true)
        every { audioCapture.captureSession(any()) } returns rawFrames
        val controller = makeController(fakeAsr, dispatcher, ttsEngine = ttsEngine, audioCapture = audioCapture)

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(rawFrames)

        rawFrames.tryEmit(shortArrayOf(1, 2, 3))
        Thread.sleep(150)
        assertTrue(fakeAsr.recordedFrames.isEmpty())
    }

    // --- Fix wave 3, finding 1: call-time stamp covers a TTS burst so short it starts AND ends
    // entirely between two mic frames, so the per-frame filter (Fix wave 2) never samples
    // speaking=true for it. Only stamping lastSpeakingSeenMs when we ourselves call
    // ttsEngine.speak() (announce()/agent answer) can mute the frame that follows.

    @Test fun `a frame arriving right after an agent answer is muted even though speaking never read true on any frame`() {
        val fakeAsr = FakeContinuousAsr(ready = true)
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        // speaking never flips true: simulates a TTS burst so short the per-frame filter never
        // observes it, so only the call-time stamp in agentFallback()'s Answer branch can mute.
        val speaking = MutableStateFlow(false)
        val ttsEngine = mockk<TtsEngine>(relaxed = true)
        every { ttsEngine.speaking } returns speaking
        // A relaxed mock's unstubbed Boolean-returning speak() returns false by default -- must
        // be stubbed true here since only that lets the Fix wave 4 conditional stamp fire.
        every { ttsEngine.speak(any()) } returns true
        val rawFrames = MutableSharedFlow<ShortArray>(extraBufferCapacity = 8)
        val audioCapture = mockk<AudioCapture>(relaxed = true)
        every { audioCapture.captureSession(any()) } returns rawFrames

        val agentOrchestrator = mockk<AgentOrchestrator>()
        coEvery { agentOrchestrator.ask(any(), any()) } returns AgentResult.Answer("Готово")
        coEvery { agentOrchestrator.noteAction(any()) } returns Unit
        coEvery { agentOrchestrator.expectsFollowUp() } returns false

        val gate = mockk<VoiceGate>()
        every { gate.isEnabled() } returns true
        every { gate.vehicleSnapshot() } returns null
        every { gate.preferredLang() } returns null
        every { gate.ttsEnabled() } returns true // must be true so agentFallback() actually speaks

        val localePrefs = mockk<LocalePreferences>(relaxed = true)
        every { localePrefs.getLanguage() } returns "ru"
        val earcon = mockk<VoiceEarcon>(relaxed = true)
        val automationEngine = mockk<AutomationEngine>(relaxed = true)
        val automationResolver = mockk<VoiceAutomationResolver>()
        coEvery { automationResolver.match(any()) } returns null

        val controller = VoiceController(audioCapture, dispatcher, localePrefs, earcon, gate,
            automationEngine, automationResolver, agentOrchestrator, mockk<Context>(relaxed = true),
            ttsEngine, VoiceJournal(), fakeAsr,
            agentIdentity = { AgentIdentity("", AgentPersona.NAVIGATOR) },
            ttsModelManager = mockk(relaxed = true),
            ruStressMarker = RuStressMarker { null },
            selectedTtsVoice = { TtsVoiceCatalog.byId("dmitri") })

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(rawFrames)
        awaitSubscribed(fakeAsr.events)

        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("навигатор"))
        // Wait for agentFallback()'s Answer branch to actually run and stamp lastSpeakingSeenMs
        // at call time -- speaking.value stays false throughout, so this can only be the
        // call-time stamp, never the per-frame one.
        awaitTrue { controller.lastSpeakingSeenMs > 0L }
        assertFalse(speaking.value)

        // Well within TTS_MUTE_GRACE_MS(300ms) of the stamp: must still be muted.
        rawFrames.tryEmit(shortArrayOf(7))
        Thread.sleep(50)
        assertTrue(fakeAsr.recordedFrames.isEmpty())
    }

    // --- Fix wave 4, finding 1: speak() returning false (blank text, or engine not ready -- e.g.
    // voice not downloaded while the settings toggle is on) means no audio was ever enqueued, so
    // the call-time stamp must NOT fire and the following frame must NOT be muted. ---

    @Test fun `agent answer with speak() returning false does not stamp the mute window, next frame is not muted`() {
        val fakeAsr = FakeContinuousAsr(ready = true)
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val speaking = MutableStateFlow(false)
        val ttsEngine = mockk<TtsEngine>(relaxed = true)
        every { ttsEngine.speaking } returns speaking
        every { ttsEngine.speak(any()) } returns false // e.g. voice not downloaded
        val rawFrames = MutableSharedFlow<ShortArray>(extraBufferCapacity = 8)
        val audioCapture = mockk<AudioCapture>(relaxed = true)
        every { audioCapture.captureSession(any()) } returns rawFrames

        val agentOrchestrator = mockk<AgentOrchestrator>()
        coEvery { agentOrchestrator.ask(any(), any()) } returns AgentResult.Answer("Готово")
        coEvery { agentOrchestrator.noteAction(any()) } returns Unit
        coEvery { agentOrchestrator.expectsFollowUp() } returns false

        val gate = mockk<VoiceGate>()
        every { gate.isEnabled() } returns true
        every { gate.vehicleSnapshot() } returns null
        every { gate.preferredLang() } returns null
        every { gate.ttsEnabled() } returns true

        val localePrefs = mockk<LocalePreferences>(relaxed = true)
        every { localePrefs.getLanguage() } returns "ru"
        val earcon = mockk<VoiceEarcon>(relaxed = true)
        val automationEngine = mockk<AutomationEngine>(relaxed = true)
        val automationResolver = mockk<VoiceAutomationResolver>()
        coEvery { automationResolver.match(any()) } returns null

        val controller = VoiceController(audioCapture, dispatcher, localePrefs, earcon, gate,
            automationEngine, automationResolver, agentOrchestrator, mockk<Context>(relaxed = true),
            ttsEngine, VoiceJournal(), fakeAsr,
            agentIdentity = { AgentIdentity("", AgentPersona.NAVIGATOR) },
            ttsModelManager = mockk(relaxed = true),
            ruStressMarker = RuStressMarker { null },
            selectedTtsVoice = { TtsVoiceCatalog.byId("dmitri") })

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(rawFrames)
        awaitSubscribed(fakeAsr.events)

        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("навигатор"))
        // Once speak() (which returned false) has been invoked, the surrounding conditional has
        // already been evaluated synchronously in the same statement -- no separate wait needed
        // for the stamp decision itself.
        awaitVerify { verify { ttsEngine.speak("Готово") } }
        assertEquals(0L, controller.lastSpeakingSeenMs)

        rawFrames.tryEmit(shortArrayOf(7))
        awaitTrue { fakeAsr.recordedFrames.isNotEmpty() }
    }

    // --- Fix wave 2, finding 3: shouldMute pins the exact grace-window boundary ---

    @Test fun `shouldMute pins the grace-window boundary precisely`() {
        // lastSpeakingSeenMs + TTS_MUTE_GRACE_MS(300) = muteUntilMs, computed at the call site.
        assertTrue(VoiceController.shouldMute(nowMs = 999L, muteUntilMs = 1000L, speaking = false))
        assertFalse(VoiceController.shouldMute(nowMs = 1000L, muteUntilMs = 1000L, speaking = false))
    }

    // --- Finding 3: a single utterance's routing crash is journaled, session stays open ---

    @Test fun `routing exception for one utterance is journaled as an error but the session stays open`() {
        val fakeAsr = FakeContinuousAsr(ready = true)
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        coEvery { dispatcher.dispatch(any<ActionDef>(), any()) } throws RuntimeException("boom") andThen DispatchResult(true)
        val journal = VoiceJournal()
        val controller = makeController(fakeAsr, dispatcher, journal = journal)

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)

        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("закрой окна"))
        awaitTrue { journal.entries.value.isNotEmpty() }
        assertEquals(VoiceJournalEntry.Outcome.ERROR, journal.entries.value.first().outcome)
        assertTrue(controller.listening.value) // crash did not tear down the session
        awaitTrue { controller.routingJobForTest() == null }

        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("закрой окна"))
        awaitVerify { coVerify(exactly = 2) { dispatcher.dispatch(match { it.command == "车窗关闭" }, any()) } }
    }

    // --- Fix wave 2, finding 2: decodeMs also lands in the ERROR entry's detail, not just logMsg ---

    @Test fun `routing exception journal entry also includes decodeMs in its detail`() {
        val fakeAsr = FakeContinuousAsr(ready = true)
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        coEvery { dispatcher.dispatch(any<ActionDef>(), any()) } throws RuntimeException("boom")
        val journal = VoiceJournal()
        val controller = makeController(fakeAsr, dispatcher, journal = journal)

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)

        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("закрой окна"))
        awaitTrue { journal.entries.value.isNotEmpty() }

        val entry = journal.entries.value.first()
        assertEquals(VoiceJournalEntry.Outcome.ERROR, entry.outcome)
        assertTrue(entry.detail.contains("decodeMs="))
    }

    // --- Finding 2: decodeMs lands in the VoiceJournal detail, not just logcat ---

    @Test fun `utterance journal detail includes decodeMs`() {
        val fakeAsr = FakeContinuousAsr(ready = true)
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        coEvery { dispatcher.dispatch(any<ActionDef>(), any()) } returns DispatchResult(true)
        val journal = VoiceJournal()
        val controller = makeController(fakeAsr, dispatcher, journal = journal)

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)
        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("закрой окна"))
        awaitTrue { journal.entries.value.isNotEmpty() }

        assertTrue(journal.entries.value.first().detail.contains("decodeMs="))
    }

    // --- Finding 6: sessionJob is released so a subsequent PTT can start a fresh session ---

    @Test fun `session can be restarted after a stop`() {
        val fakeAsr = FakeContinuousAsr(ready = true)
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val controller = makeController(fakeAsr, dispatcher)

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        controller.onPttPressed()
        awaitTrue { !controller.listening.value }

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
    }

    // --- shouldMute: pure function, no session/clock needed ---

    @Test fun `shouldMute is true strictly before the mute deadline`() {
        assertTrue(VoiceController.shouldMute(nowMs = 100L, muteUntilMs = 200L, speaking = false))
    }

    @Test fun `shouldMute is false at or after the mute deadline`() {
        assertFalse(VoiceController.shouldMute(nowMs = 200L, muteUntilMs = 200L, speaking = false))
        assertFalse(VoiceController.shouldMute(nowMs = 300L, muteUntilMs = 200L, speaking = false))
    }

    @Test fun `shouldMute is false when no mute window is set and tts is not speaking`() {
        assertFalse(VoiceController.shouldMute(nowMs = System.currentTimeMillis(), muteUntilMs = 0L, speaking = false))
    }

    @Test fun `shouldMute is true whenever tts is actively speaking regardless of the mute window`() {
        assertTrue(VoiceController.shouldMute(nowMs = System.currentTimeMillis(), muteUntilMs = 0L, speaking = true))
    }

    // --- Task 4: listening-overlay wiring (show/update/hide test seams) ---

    private fun stubbedContext(): Context = mockk<Context>(relaxed = true).also {
        every { it.getString(R.string.voice_listening) } returns "Слушаю"
        every { it.getString(R.string.voice_thinking) } returns "Думаю"
    }

    @Test fun `starting a continuous session shows the listening overlay`() {
        val fakeAsr = FakeContinuousAsr(ready = true)
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val controller = makeController(fakeAsr, dispatcher, context = stubbedContext())
        val shown = Collections.synchronizedList(mutableListOf<String>())
        controller.showListeningOverlay = { text -> shown.add(text) }

        controller.onPttPressed()
        awaitTrue { controller.listening.value }

        awaitTrue { shown.isNotEmpty() }
        assertEquals(listOf("Слушаю"), shown)
    }

    @Test fun `an utterance flips the overlay to thinking then back to listening`() {
        val fakeAsr = FakeContinuousAsr(ready = true)
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        coEvery { dispatcher.dispatch(any<ActionDef>(), any()) } returns DispatchResult(true)
        val controller = makeController(fakeAsr, dispatcher, context = stubbedContext())
        val updates = Collections.synchronizedList(mutableListOf<String>())
        controller.updateListeningOverlay = { text -> updates.add(text) }

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)

        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("закрой окна"))
        awaitTrue { updates.size >= 2 }
        assertEquals(listOf("Думаю", "Слушаю"), updates)
    }

    @Test fun `stopping the session hides the listening overlay`() {
        val fakeAsr = FakeContinuousAsr(ready = true)
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val controller = makeController(fakeAsr, dispatcher, context = stubbedContext())
        var hidden = false
        controller.hideListeningOverlay = { hidden = true }

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        controller.onPttPressed()
        awaitTrue { !controller.listening.value }

        awaitTrue { hidden }
    }

    // --- Wave B degradation matrix (Task 5) ---

    // (a) transcribe() throws on the first frame -- a capture/ASR failure, not a per-utterance
    // routing crash: the whole session ends with an error announce, busy is released (a fresh PTT
    // press can start a new session), and the overlay hides.
    @Test fun `transcribe throwing on the first frame ends the session with an error announce`() {
        val failingAsr = FailingContinuousAsr()
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val rawFrames = MutableSharedFlow<ShortArray>(extraBufferCapacity = 8)
        val audioCapture = mockk<AudioCapture>(relaxed = true)
        every { audioCapture.captureSession(any()) } returns rawFrames
        val controller = makeController(failingAsr, dispatcher, audioCapture = audioCapture, context = stubbedContext())
        val answers = Collections.synchronizedList(mutableListOf<String>())
        controller.showAnswerHook = { text -> answers.add(text) }
        var hidden = false
        controller.hideListeningOverlay = { hidden = true }

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(rawFrames)

        rawFrames.tryEmit(shortArrayOf(1, 2, 3)) // "first frame" -- triggers the throw in transcribe()

        awaitTrue { !controller.listening.value }
        awaitTrue { hidden }
        awaitTrue { answers.isNotEmpty() }
        // announce()'s orb-dialog text is "Отказ: <message>" ("Ошибка" is the spoken phrase, not
        // shown here since ttsEnabled() defaults to false in makeController).
        assertTrue(answers.first().contains("Отказ"))

        // busy released: a fresh PTT press can start a new session, proving no stuck state.
        controller.onPttPressed()
        awaitTrue { controller.listening.value }
    }

    // (b) GigaAM model not ready (not downloaded, or deleted) while no session is active -- PTT
    // reports the degraded "model not ready" outcome without starting a session: state goes to
    // NotUnderstood, the journal records an ERROR entry, and the overlay/spoken announce is the
    // same "Голосовая модель не загружена" phrase the old legacy path used to produce.
    @Test fun `ptt with continuous engine not ready reports model-not-ready without starting a session`() {
        val fakeAsr = FakeContinuousAsr(ready = false) // simulates the model not being downloaded/deleted
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val journal = VoiceJournal()
        val controller = makeController(fakeAsr, dispatcher, journal = journal)
        val answers = Collections.synchronizedList(mutableListOf<String>())
        controller.showAnswerHook = { text -> answers.add(text) }

        controller.onPttPressed()

        assertEquals(VoiceUiState.NotUnderstood(""), controller.state.value)
        assertEquals(1, journal.entries.value.size)
        assertEquals(VoiceJournalEntry.Outcome.ERROR, journal.entries.value.first().outcome)
        assertFalse(controller.listening.value)
        awaitTrue { answers.contains("Голосовая модель не загружена") }
    }

    // (в) TTS voice not downloaded (gate.ttsEnabled() == false, the makeController default) --
    // the continuous session runs entirely silently (ttsEngine.speak() never called) while the
    // listening/thinking overlay still updates normally.
    @Test fun `continuous session runs silently with overlay when tts is not downloaded`() {
        val fakeAsr = FakeContinuousAsr(ready = true)
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        coEvery { dispatcher.dispatch(any<ActionDef>(), any()) } returns DispatchResult(true)
        val ttsEngine = quietTtsEngine()
        val controller = makeController(fakeAsr, dispatcher, ttsEngine = ttsEngine, context = stubbedContext())
        val updates = Collections.synchronizedList(mutableListOf<String>())
        controller.updateListeningOverlay = { text -> updates.add(text) }

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)

        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("закрой окна"))
        awaitTrue { updates.size >= 2 }

        assertEquals(listOf("Думаю", "Слушаю"), updates)
        verify(exactly = 0) { ttsEngine.speak(any()) }
        assertTrue(controller.listening.value)
    }

    // (г) A PTT-stop pressed while an utterance is mid-dispatch (Thinking) immediately cancels
    // the routing job — the orb goes idle without waiting for dispatch to complete. Frames
    // arriving after the stop never reach the recognizer (stopRequested blocks them, unchanged).
    @Test fun `ptt stop during routing immediately cancels dispatch and tears down`() {
        val fakeAsr = FakeContinuousAsr(ready = true)
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val dispatchStarted = AtomicBoolean(false)
        val releaseDispatch = AtomicBoolean(false)
        coEvery { dispatcher.dispatch(any<ActionDef>(), any()) } coAnswers {
            dispatchStarted.set(true)
            while (!releaseDispatch.get()) delay(10)
            DispatchResult(true)
        }
        val rawFrames = MutableSharedFlow<ShortArray>(extraBufferCapacity = 8)
        val audioCapture = mockk<AudioCapture>(relaxed = true)
        every { audioCapture.captureSession(any()) } returns rawFrames
        val journal = VoiceJournal()
        var hidden = false
        val controller = makeController(fakeAsr, dispatcher, audioCapture = audioCapture, journal = journal, context = stubbedContext())
        controller.hideListeningOverlay = { hidden = true }

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        // Proves the mic collector is genuinely attached to rawFrames (not just to
        // fakeAsr.events) before the post-stop frame is emitted below -- otherwise, with no
        // subscriber and replay=0, the emission could be silently lost by the fixture itself and
        // the "never reaches the recognizer" assertion would pass vacuously.
        awaitSubscribed(rawFrames)
        awaitSubscribed(fakeAsr.events)

        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("закрой окна"))
        awaitTrue { dispatchStarted.get() } // dispatch() is now in flight, suspended in the delay loop

        controller.onPttPressed() // PTT-stop while Thinking

        // Frames emitted after stop must never reach the recognizer (stopRequested blocks them).
        val framesBefore = fakeAsr.recordedFrames.size
        rawFrames.tryEmit(shortArrayOf(1, 2, 3))
        Thread.sleep(100)
        assertEquals(framesBefore, fakeAsr.recordedFrames.size)

        // Hard stop: routing is cancelled immediately; session tears down without calling
        // releaseDispatch. Journal stays empty — CancellationException, no journal write.
        awaitTrue { !controller.listening.value }
        awaitTrue { hidden }
        assertTrue(journal.entries.value.isEmpty())
        coVerify(exactly = 1) { dispatcher.dispatch(any(), any()) } // called once, then cancelled
    }

    // --- Task 6: transcript in the orb dialog (heard phrase / agent answer / auto-clear) ---

    @Test fun `an utterance shows the heard transcript in the orb`() {
        val fakeAsr = FakeContinuousAsr(ready = true)
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        coEvery { dispatcher.dispatch(any<ActionDef>(), any()) } returns DispatchResult(true)
        val controller = makeController(fakeAsr, dispatcher, context = stubbedContext())
        val heard = Collections.synchronizedList(mutableListOf<String>())
        controller.showHeardHook = { text -> heard.add(text) }

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)

        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("закрой окна"))
        awaitTrue { heard.isNotEmpty() }
        assertEquals("закрой окна", heard.first())
    }

    @Test fun `a terminal answer shows the agent answer in the orb`() {
        val fakeAsr = FakeContinuousAsr(ready = true)
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        coEvery { dispatcher.dispatch(any<ActionDef>(), any()) } returns DispatchResult(true)
        val controller = makeController(fakeAsr, dispatcher, context = stubbedContext())
        val answers = Collections.synchronizedList(mutableListOf<String>())
        controller.showAnswerHook = { text -> answers.add(text) }

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)

        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("закрой окна"))
        awaitTrue { answers.isNotEmpty() }
        assertTrue(answers.first().contains("Выполнено"))
    }

    @Test fun `the orb dialog clears after the answer once tts is not speaking`() {
        val fakeAsr = FakeContinuousAsr(ready = true)
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        coEvery { dispatcher.dispatch(any<ActionDef>(), any()) } returns DispatchResult(true)
        val controller = makeController(fakeAsr, dispatcher, context = stubbedContext())
        // Shrink the 6s dwell so the test need not wait it out (mirrors idleResetDelayMs); the fake
        // TtsEngine's speaking flow is already false, so first{!it} returns immediately.
        controller.dialogClearDelayMs = 50L
        var cleared = false
        controller.clearDialogHook = { cleared = true }

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)

        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("закрой окна"))
        awaitTrue { cleared }
    }

    @Test fun `a new utterance cancels the pending orb clear`() {
        val fakeAsr = FakeContinuousAsr(ready = true)
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        // Second utterance's dispatch is held suspended so it never arms a replacement clear during
        // the observation window below -- so a fired clearDialogHook could only be the FIRST timer.
        val secondStarted = AtomicBoolean(false)
        val releaseSecond = AtomicBoolean(false)
        var calls = 0
        coEvery { dispatcher.dispatch(any<ActionDef>(), any()) } coAnswers {
            calls++
            if (calls >= 2) {
                secondStarted.set(true)
                while (!releaseSecond.get()) delay(10)
            }
            DispatchResult(true)
        }
        val controller = makeController(fakeAsr, dispatcher, context = stubbedContext())
        controller.dialogClearDelayMs = 500L
        var cleared = false
        controller.clearDialogHook = { cleared = true }
        val answers = Collections.synchronizedList(mutableListOf<String>())
        controller.showAnswerHook = { text -> answers.add(text) }

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)

        // First utterance completes and arms a 500ms clear.
        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("закрой окна"))
        awaitTrue { answers.isNotEmpty() }
        awaitTrue { controller.routingJobForTest() == null }

        // Second utterance arrives well within 500ms; routeUtterance() cancels the pending clear
        // before its own (held) dispatch, so no replacement clear is armed.
        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("закрой окна"))
        awaitTrue { secondStarted.get() }

        // Past the first timer's 500ms: had it not been cancelled, clearDialogHook would have fired.
        Thread.sleep(800)
        assertFalse(cleared)

        releaseSecond.set(true)
    }

    // --- Wave G Task 1: instant duck on PTT, before the recognizer is even constructed ---

    @Test fun `continuous session ducks before asr and restores on teardown`() {
        val fakeAsr = FakeContinuousAsr(ready = true)
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val audioCapture = mockk<AudioCapture>(relaxed = true)
        every { audioCapture.duckMusic() } returns 20
        // A never-completing frame source: the session must still be alive when the second press
        // arrives, and the collecting barrier below must not miss its window (an empty flow{}
        // flips collecting true->false before awaitTrue can observe it).
        val rawFrames = MutableSharedFlow<ShortArray>()
        every { audioCapture.captureSession(any()) } returns rawFrames
        val controller = makeController(fakeAsr, dispatcher, audioCapture = audioCapture)

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        // Barrier: listening flips true synchronously in startContinuousSession, BEFORE the
        // launched coroutine reaches captureSession(). Without waiting for the collector the
        // stop below could cancel the job before captureSession() is ever invoked.
        awaitTrue { fakeAsr.collecting }

        controller.onPttPressed() // second press stops the session -- runs its finally block
        awaitTrue { !controller.listening.value }

        verifyOrder {
            audioCapture.duckMusic()       // early duck, before captureSession() is even called
            audioCapture.captureSession(any())
        }
        verify { audioCapture.restoreMusic(20) }   // finally restored the early-duck volume
    }

    // --- Wave O Task 8: orb button is a hard OFF switch ---

    /** Hard stop (a): orb pressed while the agent ask is in-flight. The ask job must be
     *  cancelled and listening must go false immediately — without waiting for the LLM. */
    @Test fun `orb stop while agent ask is in flight cancels ask and goes idle immediately`() {
        val fakeAsr = FakeContinuousAsr(ready = true)
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val askStarted = CompletableDeferred<Unit>()
        val askCancelled = CompletableDeferred<Unit>()
        val agentOrchestrator = mockk<AgentOrchestrator>()
        // Ask hangs forever — simulates a slow or stuck LLM SSE stream.
        coEvery { agentOrchestrator.ask(any(), any()) } coAnswers {
            askStarted.complete(Unit)
            try {
                CompletableDeferred<AgentResult>().await() // blocked until cancelled
            } catch (ce: CancellationException) {
                askCancelled.complete(Unit)
                throw ce
            }
        }
        coEvery { agentOrchestrator.noteAction(any()) } returns Unit
        coEvery { agentOrchestrator.expectsFollowUp() } returns false
        val rawFrames = MutableSharedFlow<ShortArray>()
        val audioCapture = mockk<AudioCapture>(relaxed = true)
        every { audioCapture.captureSession(any()) } returns rawFrames
        val ttsEngine = quietTtsEngine()
        val controller = makeController(
            fakeAsr, dispatcher,
            agentOrchestrator = agentOrchestrator,
            audioCapture = audioCapture,
            ttsEngine = ttsEngine,
            agentIdentity = { AgentIdentity("", AgentPersona.NAVIGATOR) },
        )

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)

        // Utterance reaches agent path (no NLU match) and blocks forever.
        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("расскажи про заряд"))
        awaitTrue { askStarted.isCompleted }

        // Hard stop: orb press while ask is suspended in the LLM call.
        controller.onPttPressed()

        // Ask must be cancelled and orb must go idle immediately — no releaseAsk needed.
        awaitTrue { askCancelled.isCompleted }
        awaitTrue { !controller.listening.value }
    }

    /** Hard stop (b): orb pressed while NLU dispatch is in-flight. The routing job must be
     *  cancelled and the orb must go idle immediately, without waiting for dispatch to return. */
    @Test fun `orb stop while routing is in flight cancels routing and goes idle immediately`() {
        val fakeAsr = FakeContinuousAsr(ready = true)
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val dispatchStarted = AtomicBoolean(false)
        // Dispatch hangs indefinitely — cancelled at the delay suspension point.
        coEvery { dispatcher.dispatch(any<ActionDef>(), any()) } coAnswers {
            dispatchStarted.set(true)
            delay(Long.MAX_VALUE)
            DispatchResult(true)
        }
        val rawFrames = MutableSharedFlow<ShortArray>()
        val audioCapture = mockk<AudioCapture>(relaxed = true)
        every { audioCapture.captureSession(any()) } returns rawFrames
        val controller = makeController(fakeAsr, dispatcher, audioCapture = audioCapture)

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)

        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("закрой окна"))
        awaitTrue { dispatchStarted.get() }

        // Hard stop: orb press while dispatch is suspended.
        controller.onPttPressed()

        // Orb must go idle immediately — dispatch will never return on its own.
        awaitTrue { !controller.listening.value }
    }

    /** Hard stop (c): orb pressed while TTS is speaking and dispatch is in-flight.
     *  tts.stop() must be called even when processingUtterance=true. */
    @Test fun `orb stop while speaking and processing calls tts stop immediately`() {
        val fakeAsr = FakeContinuousAsr(ready = true)
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val dispatchStarted = AtomicBoolean(false)
        coEvery { dispatcher.dispatch(any<ActionDef>(), any()) } coAnswers {
            dispatchStarted.set(true)
            delay(Long.MAX_VALUE)
            DispatchResult(true)
        }
        val rawFrames = MutableSharedFlow<ShortArray>()
        val audioCapture = mockk<AudioCapture>(relaxed = true)
        every { audioCapture.captureSession(any()) } returns rawFrames
        val ttsEngine = mockk<TtsEngine>(relaxed = true)
        every { ttsEngine.speaking } returns MutableStateFlow(true)
        val controller = makeController(fakeAsr, dispatcher, audioCapture = audioCapture, ttsEngine = ttsEngine)

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)

        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("закрой окна"))
        awaitTrue { dispatchStarted.get() }

        controller.onPttPressed() // hard stop while TTS speaking + dispatch in flight

        verify(atLeast = 1) { ttsEngine.stop() }
        awaitTrue { !controller.listening.value }
    }

    /** Hard stop (d): a late streaming sentence lands AFTER the orb press. The SSE loop can
     *  emit one more sentence between the ask job's cancellation and its next suspension
     *  point; the callback itself is non-suspend, so without the stopRequested gate it would
     *  repaint the orb dialog after the hard stop. The mock swallows the cancellation and
     *  fires the callback once more to model exactly that window. */
    @Test fun `orb stop suppresses streaming sentence that lands after the stop`() {
        val fakeAsr = FakeContinuousAsr(ready = true)
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val askStarted = CompletableDeferred<Unit>()
        val releaseAsk = CompletableDeferred<Unit>()
        val lateSentenceFired = CompletableDeferred<Unit>()
        val agentOrchestrator = mockk<AgentOrchestrator>()
        coEvery { agentOrchestrator.ask(any(), any()) } coAnswers {
            val onSentence = secondArg<(String) -> Unit>()
            askStarted.complete(Unit)
            // Swallow the hard stop's cancellation, then emit one more sentence — models the
            // real SSE loop running past the cancellation until its next suspension point.
            try { releaseAsk.await() } catch (_: CancellationException) {}
            onSentence("поздний ответ")
            lateSentenceFired.complete(Unit)
            throw CancellationException("ask cancelled")
        }
        coEvery { agentOrchestrator.noteAction(any()) } returns Unit
        coEvery { agentOrchestrator.expectsFollowUp() } returns false
        val rawFrames = MutableSharedFlow<ShortArray>()
        val audioCapture = mockk<AudioCapture>(relaxed = true)
        every { audioCapture.captureSession(any()) } returns rawFrames
        val ttsEngine = quietTtsEngine()
        val controller = makeController(
            fakeAsr, dispatcher,
            agentOrchestrator = agentOrchestrator,
            audioCapture = audioCapture,
            ttsEngine = ttsEngine,
            agentIdentity = { AgentIdentity("", AgentPersona.NAVIGATOR) },
        )
        val answers = mutableListOf<String>()
        controller.showAnswerHook = { text -> answers.add(text) }

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)

        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("расскажи про заряд"))
        awaitTrue { askStarted.isCompleted }

        controller.onPttPressed() // hard stop while the ask is still suspended
        awaitTrue { !controller.listening.value }

        releaseAsk.complete(Unit) // the SSE tail sentence lands after the stop
        awaitTrue { lateSentenceFired.isCompleted }

        assertTrue("late sentence must not reach the orb dialog", answers.none { "поздний ответ" in it })
        assertTrue("late sentence must not resurrect AgentAnswer state",
            controller.state.value !is VoiceUiState.AgentAnswer)
    }

    /** Hard stop (e): stopRequested must not bleed into the NEXT session. A continuous hard
     *  stop leaves the flag set; only startContinuousSession() clears it -- a later session that
     *  forgot to reset it would have every announce() silently suppressed by the entry gate. */
    @Test fun `session after a hard stop is not muted by the stale stop flag`() {
        val fakeAsr = FakeContinuousAsr(ready = true)
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        coEvery { dispatcher.dispatch(any<ActionDef>(), any()) } returns DispatchResult(true)
        val rawFrames = MutableSharedFlow<ShortArray>()
        val audioCapture = mockk<AudioCapture>(relaxed = true)
        every { audioCapture.captureSession(any()) } returns rawFrames
        val controller = makeController(fakeAsr, dispatcher, audioCapture = audioCapture)
        val answers = mutableListOf<String>()
        controller.showAnswerHook = { text -> answers.add(text) }

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)
        controller.onPttPressed() // hard stop: sets stopRequested and tears the session down
        awaitTrue { !controller.listening.value }

        // A fresh continuous session starts: startContinuousSession() must clear the stale flag.
        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)
        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("закрой окна"))

        // The new session's outcome announcement must reach the orb dialog.
        awaitTrue { answers.any { "Выполнено" in it } }
    }

    /** Hard stop (f): a late streaming sentence from the OLD hard-stopped ask must stay muted
     *  even after a NEW session has started and reset the global stopRequested flag — the
     *  per-turn askJob.isCancelled mark, not the global flag, is what keeps it silent. */
    @Test fun `late sentence from a stopped ask stays muted after a new session starts`() {
        val fakeAsr = FakeContinuousAsr(ready = true)
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        coEvery { dispatcher.dispatch(any<ActionDef>(), any()) } returns DispatchResult(true)
        val askStarted = CompletableDeferred<Unit>()
        val releaseAsk = CompletableDeferred<Unit>()
        val lateSentenceFired = CompletableDeferred<Unit>()
        val agentOrchestrator = mockk<AgentOrchestrator>()
        coEvery { agentOrchestrator.ask(any(), any()) } coAnswers {
            val onSentence = secondArg<(String) -> Unit>()
            askStarted.complete(Unit)
            // Survives the hard stop's cancellation: models the real SSE loop still running
            // on the HTTP thread and delivering one more sentence long after the stop.
            withContext(NonCancellable) { releaseAsk.await() }
            onSentence("поздний ответ")
            lateSentenceFired.complete(Unit)
            throw CancellationException("ask cancelled")
        }
        coEvery { agentOrchestrator.noteAction(any()) } returns Unit
        coEvery { agentOrchestrator.expectsFollowUp() } returns false
        val rawFrames = MutableSharedFlow<ShortArray>()
        val audioCapture = mockk<AudioCapture>(relaxed = true)
        every { audioCapture.captureSession(any()) } returns rawFrames
        val controller = makeController(
            fakeAsr, dispatcher,
            agentOrchestrator = agentOrchestrator,
            audioCapture = audioCapture,
            ttsEngine = quietTtsEngine(),
            agentIdentity = { AgentIdentity("", AgentPersona.NAVIGATOR) },
        )
        val answers = mutableListOf<String>()
        controller.showAnswerHook = { text -> answers.add(text) }

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)
        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("расскажи про заряд"))
        awaitTrue { askStarted.isCompleted }

        controller.onPttPressed() // hard stop while the ask is suspended
        awaitTrue { !controller.listening.value }

        // A NEW continuous session starts and resets the global stopRequested flag.
        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)
        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("закрой окна"))
        awaitTrue { answers.any { "Выполнено" in it } } // the new session's round completed

        releaseAsk.complete(Unit) // the OLD ask's tail sentence lands only now
        awaitTrue { lateSentenceFired.isCompleted }

        assertTrue("old ask's late sentence must stay muted after the new session reset the flag",
            answers.none { "поздний ответ" in it })
    }

    // --- Wave P Task 8: orb auto-closes after successful play_music ---

    // Wave P: a successful play_music tool call auto-closes the continuous session once the
    // reply has been spoken, so the orb stops ducking the music it just started. The session
    // must stay open for the whole spoken reply and close only when playback ends.
    @Test fun `successful play_music closes the continuous session after the answer`() {
        val fakeAsr = FakeContinuousAsr(ready = true)
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val agent = mockk<AgentOrchestrator>()
        coEvery { agent.ask(any(), any()) } returns AgentResult.Answer(
            "Включаю", listOf(AgentToolOutcome("play_music", true)))
        coEvery { agent.noteAction(any()) } returns Unit
        coEvery { agent.expectsFollowUp() } returns false
        val tts = mockk<TtsEngine>(relaxed = true)
        val speaking = MutableStateFlow(true)   // the reply is already streaming out loud
        every { tts.speaking } returns speaking
        val controller = makeController(fakeAsr, dispatcher, agentOrchestrator = agent, ttsEngine = tts)

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)
        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("расскажи анекдот"))

        // While the reply is still being spoken the session must stay open...
        Thread.sleep(500)
        assertTrue(controller.listening.value)
        // ...and closes once playback ends.
        speaking.value = false
        awaitTrue { !controller.listening.value }   // the session closed itself
    }

    // Pin for the enqueue-vs-playback race: speak() returns before the TTS worker flips
    // speaking=true, so a close that waits only for !speaking fires immediately and cuts the
    // reply before it starts. The close must wait for playback to begin, then to end.
    @Test fun `play_music close waits for a reply whose playback has not started yet`() {
        val fakeAsr = FakeContinuousAsr(ready = true)
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val agent = mockk<AgentOrchestrator>()
        coEvery { agent.ask(any(), any()) } returns AgentResult.Answer(
            "Включаю", listOf(AgentToolOutcome("play_music", true)))
        coEvery { agent.noteAction(any()) } returns Unit
        coEvery { agent.expectsFollowUp() } returns false
        val tts = mockk<TtsEngine>(relaxed = true)
        val speaking = MutableStateFlow(false)  // enqueue() returned, worker not playing yet
        every { tts.speaking } returns speaking
        val controller = makeController(fakeAsr, dispatcher, agentOrchestrator = agent, ttsEngine = tts)

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)
        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("включи музыку"))

        // The answer landed but playback has not begun: the session must NOT close.
        Thread.sleep(500)
        assertTrue(controller.listening.value)
        speaking.value = true    // playback finally starts
        Thread.sleep(300)
        assertTrue(controller.listening.value)   // still open while the reply plays
        speaking.value = false   // playback ends
        awaitTrue { !controller.listening.value }
    }

    @Test fun `non-music tools keep the continuous session listening`() {
        val fakeAsr = FakeContinuousAsr(ready = true)
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val agent = mockk<AgentOrchestrator>()
        coEvery { agent.ask(any(), any()) } returns AgentResult.Answer(
            "Заряд 80%", listOf(AgentToolOutcome("get_battery", true)))
        coEvery { agent.noteAction(any()) } returns Unit
        coEvery { agent.expectsFollowUp() } returns false
        val controller = makeController(fakeAsr, dispatcher, agentOrchestrator = agent)

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)
        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("расскажи анекдот"))

        Thread.sleep(700)
        assertTrue(controller.listening.value)   // still listening
    }

    // Guard against the stale deferred close: PTT for a NEW session stops TTS, which is the very
    // signal the parked close-coroutine waits for -- without the job-identity check it would set
    // stopRequested and kill the session the user just started.
    @Test fun `play_music auto-close never kills a newer session started while tts still spoke`() {
        val fakeAsr = FakeContinuousAsr(ready = true)
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val agent = mockk<AgentOrchestrator>()
        coEvery { agent.ask(any(), any()) } returns AgentResult.Answer(
            "Включаю", listOf(AgentToolOutcome("play_music", true)))
        coEvery { agent.noteAction(any()) } returns Unit
        coEvery { agent.expectsFollowUp() } returns false
        val tts = mockk<TtsEngine>(relaxed = true)
        val speaking = MutableStateFlow(true)   // the reply keeps playing until flipped below
        every { tts.speaking } returns speaking
        val controller = makeController(fakeAsr, dispatcher, agentOrchestrator = agent, ttsEngine = tts)

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)
        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("включи музыку"))
        // The deferred close is now parked on speaking.first { !it }. End the first session via
        // silence auto-stop; ticks arriving while the utterance is still routing are dropped by
        // the busy guard, so keep re-emitting until the session actually stops.
        awaitTrue {
            fakeAsr.events.tryEmit(ContinuousAsrEvent.SilenceTick(30_000L))
            !controller.listening.value
        }

        controller.onPttPressed()               // user starts a fresh session...
        awaitTrue { controller.listening.value }
        speaking.value = false                  // ...and the old reply finally ends

        Thread.sleep(700)
        assertTrue(controller.listening.value)  // the new session must survive the stale close
    }

    // --- I-1 fix: the else-branch (model not ready / non-RU) must also reset stopRequested ---

    /** A continuous-session hard stop leaves stopRequested set; only startContinuousSession()
     *  used to clear it. If the model then becomes not ready (or the language switches to
     *  non-RU) before the next PTT press, onPttPressed() falls into the else-branch instead --
     *  which, before the fix, never reset the flag, so announce()'s stopRequested gate silently
     *  swallowed the "model not loaded" overlay+speech forever (this user can never start a
     *  continuous session again to reset it). */
    @Test fun `ptt reports model-not-ready after a hard stop, not muted by the stale stop flag`() {
        val fakeAsr = FakeContinuousAsr(ready = true)
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val journal = VoiceJournal()
        val controller = makeController(fakeAsr, dispatcher, journal = journal)
        val answers = Collections.synchronizedList(mutableListOf<String>())
        controller.showAnswerHook = { text -> answers.add(text) }

        controller.onPttPressed() // starts a continuous session
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)
        controller.onPttPressed() // hard stop: sets stopRequested, session tears down
        awaitTrue { !controller.listening.value }

        fakeAsr.ready = false // model becomes not ready (or user switched to a non-RU language)
        controller.onPttPressed() // else-branch: model-not-ready path

        assertEquals(VoiceUiState.NotUnderstood(""), controller.state.value)
        assertEquals(1, journal.entries.value.size)
        assertEquals(VoiceJournalEntry.Outcome.ERROR, journal.entries.value.first().outcome)
        awaitTrue { answers.contains("Голосовая модель не загружена") }
    }
}
