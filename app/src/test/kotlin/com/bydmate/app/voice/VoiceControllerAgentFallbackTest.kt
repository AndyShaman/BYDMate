package com.bydmate.app.voice

import android.content.Context
import com.bydmate.app.agent.AgentOrchestrator
import com.bydmate.app.agent.AgentResult
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.AutomationEngine
import com.bydmate.app.data.automation.DispatchResult
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.local.entity.ActionDef
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Coverage for the LLM-agent fallback wired into VoiceController's continuous-session
 * transcript path (Task 6): an utterance the local NLU/automation resolvers cannot match is
 * handed to AgentOrchestrator.ask(). Each test drives the continuous session with a single
 * scripted ContinuousAsrEvent.Utterance.
 */
class VoiceControllerAgentFallbackTest {

    /** A relaxed TtsEngine mock's `speaking` StateFlow<Boolean> property, being a mocked
     *  interface itself, has no real backing state -- its collect() completes without ever
     *  emitting. VoiceController's scheduleClear() awaits `speaking.first { !it }` after every
     *  terminal outcome, so an unstubbed `speaking` throws NoSuchElementException on that
     *  background job. A real StateFlow backing it (already false) lets first{} match immediately. */
    private fun quietTtsEngine(): TtsEngine = mockk<TtsEngine>(relaxed = true) {
        every { speaking } returns MutableStateFlow(false)
    }

    private class FakeContinuousAsr : ContinuousAsr {
        val events = MutableSharedFlow<ContinuousAsrEvent>(extraBufferCapacity = 16)
        override fun isReady(): Boolean = true
        override fun transcribe(pcm: Flow<ShortArray>): Flow<ContinuousAsrEvent> = events
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
     *  with FakeContinuousAsr's collector subscribing. subscriptionCount is the real barrier: a
     *  tryEmit before any collector attaches (replay = 0) is simply lost. */
    private fun awaitSubscribed(events: MutableSharedFlow<*>) = awaitTrue { events.subscriptionCount.value >= 1 }

    private fun makeController(
        agentOrchestrator: AgentOrchestrator,
        dispatcher: ActionDispatcher = mockk(relaxed = true),
        ttsEnabled: Boolean = false,
        ttsEngine: TtsEngine = quietTtsEngine(),
        journal: VoiceJournal = VoiceJournal(),
        agentIdentity: () -> AgentIdentity = { AgentIdentity("", AgentPersona.NAVIGATOR) },
        continuousAsr: ContinuousAsr = FakeContinuousAsr(),
    ): VoiceController {
        val gate = mockk<VoiceGate>()
        every { gate.isEnabled() } returns true
        every { gate.vehicleSnapshot() } returns null
        every { gate.preferredLang() } returns null
        every { gate.ttsEnabled() } returns ttsEnabled

        val audioCapture = mockk<AudioCapture>(relaxed = true)
        every { audioCapture.captureSession(any()) } returns flow { /* empty — completes immediately */ }

        val localePrefs = mockk<LocalePreferences>(relaxed = true)
        every { localePrefs.getLanguage() } returns "ru"

        val earcon = mockk<VoiceEarcon>(relaxed = true)

        val automationEngine = mockk<AutomationEngine>(relaxed = true)
        val automationResolver = mockk<VoiceAutomationResolver>()
        coEvery { automationResolver.match(any()) } returns null

        // Default: no pending agent question (individual tests override AFTER construction).
        coEvery { agentOrchestrator.expectsFollowUp() } returns false

        return VoiceController(audioCapture, dispatcher, localePrefs, earcon, gate,
            automationEngine, automationResolver, agentOrchestrator, mockk<Context>(relaxed = true), ttsEngine, journal, continuousAsr,
            agentIdentity = agentIdentity,
            ttsModelManager = mockk(relaxed = true),
            ruStressMarker = RuStressMarker { null },
            selectedTtsVoice = { TtsVoiceCatalog.byId("dmitri") })
    }

    @Test fun `agent Answer becomes AgentAnswer state, earcon ok, orchestrator called once`() {
        val agentOrchestrator = mockk<AgentOrchestrator>()
        coEvery { agentOrchestrator.ask(any(), any()) } returns AgentResult.Answer("Заряд 80%")

        val fakeAsr = FakeContinuousAsr()
        val controller = makeController(agentOrchestrator = agentOrchestrator, continuousAsr = fakeAsr)
        val presentedCount = AtomicInteger(0)
        val presentedText = AtomicReference<String?>(null)
        controller.showAnswerHook = { text -> presentedCount.incrementAndGet(); presentedText.set(text) }

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)
        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("навигатор"))
        Thread.sleep(500)

        assertEquals(VoiceUiState.AgentAnswer("Заряд 80%"), controller.state.value)
        coVerify(exactly = 1) { agentOrchestrator.ask("навигатор", any()) }
        assertEquals(1, presentedCount.get())
        assertEquals("Заряд 80%", presentedText.get())
    }

    @Test fun `agent Disabled degrades to pre-agent NotUnderstood`() {
        val agentOrchestrator = mockk<AgentOrchestrator>()
        coEvery { agentOrchestrator.ask(any(), any()) } returns AgentResult.Disabled

        val fakeAsr = FakeContinuousAsr()
        val controller = makeController(agentOrchestrator = agentOrchestrator, continuousAsr = fakeAsr)
        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)
        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("навигатор"))
        Thread.sleep(500)

        assertEquals(VoiceUiState.NotUnderstood("навигатор"), controller.state.value)
    }

    @Test fun `agent Error maps to Blocked with the agent message`() {
        val agentOrchestrator = mockk<AgentOrchestrator>()
        coEvery { agentOrchestrator.ask(any(), any()) } returns AgentResult.Error("нет сети")

        val fakeAsr = FakeContinuousAsr()
        val controller = makeController(agentOrchestrator = agentOrchestrator, continuousAsr = fakeAsr)

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)
        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("навигатор"))
        Thread.sleep(500)

        assertEquals(VoiceUiState.Blocked("нет сети"), controller.state.value)
    }

    @Test fun `recognized final never reaches the agent fallback`() {
        val agentOrchestrator = mockk<AgentOrchestrator>()
        coEvery { agentOrchestrator.ask(any(), any()) } returns AgentResult.Answer("не должно вызваться")

        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        coEvery { dispatcher.dispatch(any<ActionDef>(), any()) } returns DispatchResult(true)

        // "закрой окна" resolves to a built-in command ("车窗关闭"), so the fast-path handles it.
        val fakeAsr = FakeContinuousAsr()
        val controller = makeController(
            agentOrchestrator = agentOrchestrator, dispatcher = dispatcher, continuousAsr = fakeAsr,
        )
        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)
        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("закрой окна"))
        Thread.sleep(500)

        coVerify(exactly = 0) { agentOrchestrator.ask(any(), any()) }
    }

    // NOTE: a "blank final transcript reaches agentFallback and gets a spoken/orb Не понял"
    // test previously lived here. On the continuous path SelfEchoFilter.isEcho() unconditionally
    // treats an empty-after-normalization transcript as garbage echo (see SelfEchoFilter.kt) and
    // routeUtterance() returns before agentFallback() is ever reached, and AgentNameMatcher.
    // stripLeadingName() deliberately leaves a name-only utterance unchanged (never reduces it to
    // blank) -- so agentFallback's own isBlank() branch (VoiceController.kt:624) is structurally
    // unreachable from the continuous session. No continuous equivalent exists; removed rather
    // than re-plumbed. See vosk-task-2-report.md for the full trace.

    @Test fun `agent answer is spoken when tts enabled`() {
        val agentOrchestrator = mockk<AgentOrchestrator>()
        coEvery { agentOrchestrator.ask(any(), any()) } returns AgentResult.Answer("ответ")
        val ttsEngine = quietTtsEngine()

        val fakeAsr = FakeContinuousAsr()
        val controller = makeController(
            agentOrchestrator = agentOrchestrator,
            ttsEnabled = true, ttsEngine = ttsEngine, continuousAsr = fakeAsr,
        )
        val presentedCount = AtomicInteger(0)
        controller.showAnswerHook = { presentedCount.incrementAndGet() }

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)
        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("навигатор"))
        Thread.sleep(500)

        verify { ttsEngine.speak("ответ") }
        assertEquals(1, presentedCount.get())
    }

    @Test fun `agent answer is not spoken when tts disabled`() {
        val agentOrchestrator = mockk<AgentOrchestrator>()
        coEvery { agentOrchestrator.ask(any(), any()) } returns AgentResult.Answer("ответ")
        val ttsEngine = quietTtsEngine()

        val fakeAsr = FakeContinuousAsr()
        val controller = makeController(
            agentOrchestrator = agentOrchestrator,
            ttsEnabled = false, ttsEngine = ttsEngine, continuousAsr = fakeAsr,
        )
        val presentedCount = AtomicInteger(0)
        controller.showAnswerHook = { presentedCount.incrementAndGet() }

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)
        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("навигатор"))
        Thread.sleep(500)

        verify(exactly = 0) { ttsEngine.speak(any()) }
        assertEquals(1, presentedCount.get())
    }

    // --- Task 2: VoiceJournal wiring on the agent path ---

    @Test fun `agent Answer records an AGENT-OK journal entry, orb dialog shown exactly once`() {
        val agentOrchestrator = mockk<AgentOrchestrator>()
        coEvery { agentOrchestrator.ask(any(), any()) } returns AgentResult.Answer("Заряд 80%")
        val journal = VoiceJournal()

        val fakeAsr = FakeContinuousAsr()
        val controller = makeController(
            agentOrchestrator = agentOrchestrator, journal = journal, continuousAsr = fakeAsr,
        )
        val answerCount = AtomicInteger(0)
        controller.showAnswerHook = { answerCount.incrementAndGet() }

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)
        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("навигатор"))
        Thread.sleep(500)

        val entry = journal.entries.value.first()
        assertEquals(VoiceJournalEntry.Route.AGENT, entry.route)
        assertEquals(VoiceJournalEntry.Outcome.OK, entry.outcome)
        // The Answer branch feeds the orb directly (not through announce()) — must not double-fire.
        assertEquals(1, answerCount.get())
    }

    @Test fun `agent Error records an AGENT-ERROR journal entry with the agent message as reason`() {
        val agentOrchestrator = mockk<AgentOrchestrator>()
        coEvery { agentOrchestrator.ask(any(), any()) } returns AgentResult.Error("нет сети")
        val journal = VoiceJournal()

        val fakeAsr = FakeContinuousAsr()
        val controller = makeController(
            agentOrchestrator = agentOrchestrator, journal = journal, continuousAsr = fakeAsr,
        )
        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)
        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("навигатор"))
        Thread.sleep(500)

        val entry = journal.entries.value.first()
        assertEquals(VoiceJournalEntry.Route.AGENT, entry.route)
        assertEquals(VoiceJournalEntry.Outcome.ERROR, entry.outcome)
        assertEquals("нет сети", entry.reason)
    }

    @Test fun `agent Disabled shows the not-understood orb dialog exactly once`() {
        val agentOrchestrator = mockk<AgentOrchestrator>()
        coEvery { agentOrchestrator.ask(any(), any()) } returns AgentResult.Disabled

        val fakeAsr = FakeContinuousAsr()
        val controller = makeController(agentOrchestrator = agentOrchestrator, continuousAsr = fakeAsr)
        val answerCount = AtomicInteger(0)
        val answerText = AtomicReference<String?>(null)
        controller.showAnswerHook = { text -> answerCount.incrementAndGet(); answerText.set(text) }

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)
        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("навигатор"))
        Thread.sleep(500)

        assertEquals(1, answerCount.get())
        assertEquals("Не понял: «навигатор»", answerText.get())
    }

    @Test fun `agent Error shows the block orb dialog with the agent message exactly once`() {
        val agentOrchestrator = mockk<AgentOrchestrator>()
        coEvery { agentOrchestrator.ask(any(), any()) } returns AgentResult.Error("нет сети")

        val fakeAsr = FakeContinuousAsr()
        val controller = makeController(agentOrchestrator = agentOrchestrator, continuousAsr = fakeAsr)
        val answerCount = AtomicInteger(0)
        val answerText = AtomicReference<String?>(null)
        controller.showAnswerHook = { text -> answerCount.incrementAndGet(); answerText.set(text) }

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)
        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("навигатор"))
        Thread.sleep(500)

        assertEquals(1, answerCount.get())
        assertEquals("Услышал: «навигатор». Отказ: нет сети", answerText.get())
    }

    // NOTE: "blank final transcript shows the not-understood orb dialog exactly once" and
    // "blank final transcript speaks Не понял when tts enabled" previously lived here (Task 4:
    // every terminal outcome is also spoken, not only shown as an overlay). Removed for the same
    // reason as the third blank-transcript test above -- agentFallback's isBlank() branch is
    // unreachable from the continuous session, so there is nothing left for these to exercise.

    @Test fun `starting a session stops ongoing tts`() {
        val agentOrchestrator = mockk<AgentOrchestrator>()
        coEvery { agentOrchestrator.ask(any(), any()) } returns AgentResult.Disabled
        val ttsEngine = quietTtsEngine()

        val controller = makeController(
            agentOrchestrator = agentOrchestrator, ttsEngine = ttsEngine,
        )
        controller.onPttPressed()
        Thread.sleep(200)

        verify { ttsEngine.stop() }
    }

    // --- Task 5: pending agent question outranks NLU ---

    @Test fun `follow-up answer bypasses NLU and reaches the agent verbatim`() {
        val agentOrchestrator = mockk<AgentOrchestrator>()
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val fakeAsr = FakeContinuousAsr()
        // "закрой все окна" is NLU-resolvable — with a pending agent question it must
        // NOT dispatch; it is the answer to the question.
        val controller = makeController(agentOrchestrator = agentOrchestrator, dispatcher = dispatcher, continuousAsr = fakeAsr)
        coEvery { agentOrchestrator.expectsFollowUp() } returns true
        coEvery { agentOrchestrator.ask(any(), any()) } returns AgentResult.Answer("Готово")

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)
        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("закрой все окна"))
        Thread.sleep(500)

        coVerify(exactly = 1) { agentOrchestrator.ask("закрой все окна", any()) }
        coVerify(exactly = 0) { dispatcher.dispatch(any(), any()) }
    }

    // --- Wave K, Task 6: streaming reply sentences into the TTS queue and orb ---

    @Test fun `streamed sentences go to queue and final speak is skipped`() {
        val agentOrchestrator = mockk<AgentOrchestrator>()
        val ttsEngine = quietTtsEngine()
        val queue = mockk<TtsEngine.SpeechQueue>(relaxed = true)
        every { ttsEngine.startQueue() } returns queue
        every { queue.enqueue(any()) } returns true
        coEvery { agentOrchestrator.ask(any(), any()) } coAnswers {
            val onSentence = secondArg<((String) -> Unit)?>()
            onSentence?.invoke("Первое.")
            onSentence?.invoke("Второе.")
            AgentResult.Answer("Первое. Второе.", emptyList())
        }

        val fakeAsr = FakeContinuousAsr()
        val controller = makeController(
            agentOrchestrator = agentOrchestrator,
            ttsEnabled = true, ttsEngine = ttsEngine, continuousAsr = fakeAsr,
        )
        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)
        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("навигатор"))
        Thread.sleep(500)

        verify { queue.enqueue("Первое.") }
        verify { queue.enqueue("Второе.") }
        verify { queue.finish() }
        verify(exactly = 0) { ttsEngine.speak(any()) }
    }

    @Test fun `no streamed sentences falls back to legacy speak`() {
        val agentOrchestrator = mockk<AgentOrchestrator>()
        val ttsEngine = quietTtsEngine()
        every { ttsEngine.startQueue() } returns null
        coEvery { agentOrchestrator.ask(any(), any()) } returns AgentResult.Answer("Ответ.")

        val fakeAsr = FakeContinuousAsr()
        val controller = makeController(
            agentOrchestrator = agentOrchestrator,
            ttsEnabled = true, ttsEngine = ttsEngine, continuousAsr = fakeAsr,
        )
        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)
        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("навигатор"))
        Thread.sleep(500)

        verify { ttsEngine.speak("Ответ.") }
    }

    @Test fun `queue failing to start falls back to legacy speak even with streamed sentences`() {
        val agentOrchestrator = mockk<AgentOrchestrator>()
        val ttsEngine = quietTtsEngine()
        every { ttsEngine.startQueue() } returns null
        coEvery { agentOrchestrator.ask(any(), any()) } coAnswers {
            val onSentence = secondArg<((String) -> Unit)?>()
            onSentence?.invoke("Первое.")
            onSentence?.invoke("Второе.")
            AgentResult.Answer("Первое. Второе.", emptyList())
        }

        val fakeAsr = FakeContinuousAsr()
        val controller = makeController(
            agentOrchestrator = agentOrchestrator,
            ttsEnabled = true, ttsEngine = ttsEngine, continuousAsr = fakeAsr,
        )
        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)
        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("навигатор"))
        Thread.sleep(500)

        // The queue never started, so the turn must not go silent: legacy speak() takes over.
        verify { ttsEngine.speak("Первое. Второе.") }
    }

    @Test fun `overlay is updated incrementally then with final text`() {
        val agentOrchestrator = mockk<AgentOrchestrator>()
        val ttsEngine = quietTtsEngine()
        every { ttsEngine.startQueue() } returns null
        coEvery { agentOrchestrator.ask(any(), any()) } coAnswers {
            val onSentence = secondArg<((String) -> Unit)?>()
            onSentence?.invoke("Первое.")
            AgentResult.Answer("Первое. Второе.", emptyList())
        }

        val fakeAsr = FakeContinuousAsr()
        val controller = makeController(
            agentOrchestrator = agentOrchestrator,
            ttsEnabled = false, ttsEngine = ttsEngine, continuousAsr = fakeAsr,
        )
        val answerHookCalls = mutableListOf<String>()
        controller.showAnswerHook = { text -> answerHookCalls.add(text) }

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)
        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("навигатор"))
        Thread.sleep(500)

        assertEquals(listOf("Первое.", "Первое. Второе."), answerHookCalls)
    }
}
