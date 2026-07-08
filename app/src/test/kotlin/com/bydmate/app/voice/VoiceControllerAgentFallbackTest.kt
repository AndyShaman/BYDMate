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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Coverage for the LLM-agent fallback wired into VoiceController's FINAL-transcript path
 * (Task 6): a transcript the local NLU/automation resolvers cannot match is handed to
 * AgentOrchestrator.ask(). The early-fire (partial) path never reaches this fallback —
 * it only fires from the Vosk final, mirrored here by a single AsrEvent.Final.
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

    private fun makeController(
        recognizedPhrase: String,
        agentOrchestrator: AgentOrchestrator,
        dispatcher: ActionDispatcher = mockk(relaxed = true),
        ttsEnabled: Boolean = false,
        ttsEngine: TtsEngine = quietTtsEngine(),
        journal: VoiceJournal = VoiceJournal(),
        agentIdentity: () -> AgentIdentity = { AgentIdentity("", AgentPersona.NAVIGATOR) },
    ): VoiceController {
        val gate = mockk<VoiceGate>()
        every { gate.isEnabled() } returns true
        every { gate.vehicleSnapshot() } returns null
        every { gate.preferredLang() } returns null
        every { gate.ttsEnabled() } returns ttsEnabled

        val audioCapture = mockk<AudioCapture>(relaxed = true)
        every { audioCapture.captureSession(any()) } returns flow { /* empty — completes immediately */ }

        val asrEngine = mockk<AsrEngine>(relaxed = true)
        every { asrEngine.isModelReady(any()) } returns true
        every { asrEngine.recognize(any(), any(), any()) } returns flowOf(AsrEvent.Final(recognizedPhrase))

        val modelManager = mockk<VoiceModelManager>(relaxed = true)

        val localePrefs = mockk<LocalePreferences>(relaxed = true)
        every { localePrefs.getLanguage() } returns "ru"

        val earcon = mockk<VoiceEarcon>(relaxed = true)

        val automationEngine = mockk<AutomationEngine>(relaxed = true)
        val automationResolver = mockk<VoiceAutomationResolver>()
        coEvery { automationResolver.phrases() } returns emptyList()
        coEvery { automationResolver.match(any()) } returns null

        // Default: no pending agent question (individual tests override AFTER construction).
        coEvery { agentOrchestrator.expectsFollowUp() } returns false

        return VoiceController(audioCapture, asrEngine, modelManager, dispatcher, localePrefs, earcon, gate,
            automationEngine, automationResolver, agentOrchestrator, mockk<Context>(relaxed = true), ttsEngine, journal, mockk<ContinuousAsr>(relaxed = true),
            agentIdentity = agentIdentity,
            ttsModelManager = mockk(relaxed = true),
            ruStressMarker = RuStressMarker { null },
            selectedTtsVoice = { TtsVoiceCatalog.byId("dmitri") })
    }

    @Test fun `agent Answer becomes AgentAnswer state, earcon ok, orchestrator called once`() {
        val agentOrchestrator = mockk<AgentOrchestrator>()
        coEvery { agentOrchestrator.ask(any(), any()) } returns AgentResult.Answer("Заряд 80%")

        val controller = makeController(recognizedPhrase = "навигатор", agentOrchestrator = agentOrchestrator)
        val presentedCount = AtomicInteger(0)
        val presentedText = AtomicReference<String?>(null)
        controller.showAnswerHook = { text -> presentedCount.incrementAndGet(); presentedText.set(text) }

        controller.startSession()
        Thread.sleep(500)

        assertEquals(VoiceUiState.AgentAnswer("Заряд 80%"), controller.state.value)
        coVerify(exactly = 1) { agentOrchestrator.ask("навигатор", any()) }
        assertEquals(1, presentedCount.get())
        assertEquals("Заряд 80%", presentedText.get())
    }

    @Test fun `agent Disabled degrades to pre-agent NotUnderstood`() {
        val agentOrchestrator = mockk<AgentOrchestrator>()
        coEvery { agentOrchestrator.ask(any(), any()) } returns AgentResult.Disabled

        val controller = makeController(recognizedPhrase = "навигатор", agentOrchestrator = agentOrchestrator)
        controller.startSession()
        Thread.sleep(500)

        assertEquals(VoiceUiState.NotUnderstood("навигатор"), controller.state.value)
    }

    @Test fun `agent Error maps to Blocked with the agent message`() {
        val agentOrchestrator = mockk<AgentOrchestrator>()
        coEvery { agentOrchestrator.ask(any(), any()) } returns AgentResult.Error("нет сети")

        val controller = makeController(recognizedPhrase = "навигатор", agentOrchestrator = agentOrchestrator)

        controller.startSession()
        Thread.sleep(500)

        assertEquals(VoiceUiState.Blocked("нет сети"), controller.state.value)
    }

    @Test fun `recognized final never reaches the agent fallback`() {
        val agentOrchestrator = mockk<AgentOrchestrator>()
        coEvery { agentOrchestrator.ask(any(), any()) } returns AgentResult.Answer("не должно вызваться")

        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        coEvery { dispatcher.dispatch(any<ActionDef>(), any()) } returns DispatchResult(true)

        // "закрой окна" resolves to a built-in command ("车窗关闭"), so the fast-path handles it.
        val controller = makeController(
            recognizedPhrase = "закрой окна", agentOrchestrator = agentOrchestrator, dispatcher = dispatcher,
        )
        controller.startSession()
        Thread.sleep(500)

        coVerify(exactly = 0) { agentOrchestrator.ask(any(), any()) }
    }

    @Test fun `blank final transcript skips the agent, stays NotUnderstood`() {
        val agentOrchestrator = mockk<AgentOrchestrator>()
        coEvery { agentOrchestrator.ask(any(), any()) } returns AgentResult.Answer("не должно вызваться")

        val controller = makeController(recognizedPhrase = "", agentOrchestrator = agentOrchestrator)
        controller.startSession()
        Thread.sleep(500)

        coVerify(exactly = 0) { agentOrchestrator.ask(any(), any()) }
        assertEquals(VoiceUiState.NotUnderstood(""), controller.state.value)
    }

    @Test fun `agent answer is spoken when tts enabled`() {
        val agentOrchestrator = mockk<AgentOrchestrator>()
        coEvery { agentOrchestrator.ask(any(), any()) } returns AgentResult.Answer("ответ")
        val ttsEngine = quietTtsEngine()

        val controller = makeController(
            recognizedPhrase = "навигатор", agentOrchestrator = agentOrchestrator,
            ttsEnabled = true, ttsEngine = ttsEngine,
        )
        val presentedCount = AtomicInteger(0)
        controller.showAnswerHook = { presentedCount.incrementAndGet() }

        controller.startSession()
        Thread.sleep(500)

        verify { ttsEngine.speak("ответ") }
        assertEquals(1, presentedCount.get())
    }

    @Test fun `agent answer is not spoken when tts disabled`() {
        val agentOrchestrator = mockk<AgentOrchestrator>()
        coEvery { agentOrchestrator.ask(any(), any()) } returns AgentResult.Answer("ответ")
        val ttsEngine = quietTtsEngine()

        val controller = makeController(
            recognizedPhrase = "навигатор", agentOrchestrator = agentOrchestrator,
            ttsEnabled = false, ttsEngine = ttsEngine,
        )
        val presentedCount = AtomicInteger(0)
        controller.showAnswerHook = { presentedCount.incrementAndGet() }

        controller.startSession()
        Thread.sleep(500)

        verify(exactly = 0) { ttsEngine.speak(any()) }
        assertEquals(1, presentedCount.get())
    }

    // --- Task 2: VoiceJournal wiring on the agent path ---

    @Test fun `agent Answer records an AGENT-OK journal entry, orb dialog shown exactly once`() {
        val agentOrchestrator = mockk<AgentOrchestrator>()
        coEvery { agentOrchestrator.ask(any(), any()) } returns AgentResult.Answer("Заряд 80%")
        val journal = VoiceJournal()

        val controller = makeController(
            recognizedPhrase = "навигатор", agentOrchestrator = agentOrchestrator, journal = journal,
        )
        val answerCount = AtomicInteger(0)
        controller.showAnswerHook = { answerCount.incrementAndGet() }

        controller.startSession()
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

        val controller = makeController(
            recognizedPhrase = "навигатор", agentOrchestrator = agentOrchestrator, journal = journal,
        )
        controller.startSession()
        Thread.sleep(500)

        val entry = journal.entries.value.first()
        assertEquals(VoiceJournalEntry.Route.AGENT, entry.route)
        assertEquals(VoiceJournalEntry.Outcome.ERROR, entry.outcome)
        assertEquals("нет сети", entry.reason)
    }

    @Test fun `agent Disabled shows the not-understood orb dialog exactly once`() {
        val agentOrchestrator = mockk<AgentOrchestrator>()
        coEvery { agentOrchestrator.ask(any(), any()) } returns AgentResult.Disabled

        val controller = makeController(recognizedPhrase = "навигатор", agentOrchestrator = agentOrchestrator)
        val answerCount = AtomicInteger(0)
        val answerText = AtomicReference<String?>(null)
        controller.showAnswerHook = { text -> answerCount.incrementAndGet(); answerText.set(text) }

        controller.startSession()
        Thread.sleep(500)

        assertEquals(1, answerCount.get())
        assertEquals("Не понял: «навигатор»", answerText.get())
    }

    @Test fun `agent Error shows the block orb dialog with the agent message exactly once`() {
        val agentOrchestrator = mockk<AgentOrchestrator>()
        coEvery { agentOrchestrator.ask(any(), any()) } returns AgentResult.Error("нет сети")

        val controller = makeController(recognizedPhrase = "навигатор", agentOrchestrator = agentOrchestrator)
        val answerCount = AtomicInteger(0)
        val answerText = AtomicReference<String?>(null)
        controller.showAnswerHook = { text -> answerCount.incrementAndGet(); answerText.set(text) }

        controller.startSession()
        Thread.sleep(500)

        assertEquals(1, answerCount.get())
        assertEquals("Услышал: «навигатор». Отказ: нет сети", answerText.get())
    }

    @Test fun `blank final transcript shows the not-understood orb dialog exactly once`() {
        val agentOrchestrator = mockk<AgentOrchestrator>()
        coEvery { agentOrchestrator.ask(any(), any()) } returns AgentResult.Answer("не должно вызваться")

        val controller = makeController(recognizedPhrase = "", agentOrchestrator = agentOrchestrator)
        val answerCount = AtomicInteger(0)
        val answerText = AtomicReference<String?>(null)
        controller.showAnswerHook = { text -> answerCount.incrementAndGet(); answerText.set(text) }

        controller.startSession()
        Thread.sleep(500)

        coVerify(exactly = 0) { agentOrchestrator.ask(any(), any()) }
        assertEquals(1, answerCount.get())
        assertEquals("Не понял", answerText.get())
    }

    // --- Task 4: every terminal outcome is also spoken, not only shown as an overlay ---

    @Test fun `blank final transcript speaks Не понял when tts enabled`() {
        val agentOrchestrator = mockk<AgentOrchestrator>()
        coEvery { agentOrchestrator.ask(any(), any()) } returns AgentResult.Answer("не должно вызваться")
        val ttsEngine = quietTtsEngine()

        val controller = makeController(
            recognizedPhrase = "", agentOrchestrator = agentOrchestrator,
            ttsEnabled = true, ttsEngine = ttsEngine,
        )
        controller.startSession()
        Thread.sleep(500)

        val navigatorNotUnderstoodPool = listOf("Не расслышал. Повтори, пожалуйста.", "Не понял, скажи иначе.")
        verify { ttsEngine.speak(match { it in navigatorNotUnderstoodPool }) }
    }

    @Test fun `startSession stops ongoing tts`() {
        val agentOrchestrator = mockk<AgentOrchestrator>()
        coEvery { agentOrchestrator.ask(any(), any()) } returns AgentResult.Disabled
        val ttsEngine = quietTtsEngine()

        val controller = makeController(
            recognizedPhrase = "навигатор", agentOrchestrator = agentOrchestrator, ttsEngine = ttsEngine,
        )
        controller.startSession()
        Thread.sleep(200)

        verify { ttsEngine.stop() }
    }

    // --- Task 5: pending agent question outranks NLU ---

    @Test fun `follow-up answer bypasses NLU and reaches the agent verbatim`() {
        val agentOrchestrator = mockk<AgentOrchestrator>()
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        // "закрой все окна" is NLU-resolvable — with a pending agent question it must
        // NOT dispatch; it is the answer to the question.
        val controller = makeController("закрой все окна", agentOrchestrator, dispatcher)
        coEvery { agentOrchestrator.expectsFollowUp() } returns true
        coEvery { agentOrchestrator.ask(any(), any()) } returns AgentResult.Answer("Готово")

        controller.startSession()
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

        val controller = makeController(
            recognizedPhrase = "навигатор", agentOrchestrator = agentOrchestrator,
            ttsEnabled = true, ttsEngine = ttsEngine,
        )
        controller.startSession()
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

        val controller = makeController(
            recognizedPhrase = "навигатор", agentOrchestrator = agentOrchestrator,
            ttsEnabled = true, ttsEngine = ttsEngine,
        )
        controller.startSession()
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

        val controller = makeController(
            recognizedPhrase = "навигатор", agentOrchestrator = agentOrchestrator,
            ttsEnabled = true, ttsEngine = ttsEngine,
        )
        controller.startSession()
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

        val controller = makeController(
            recognizedPhrase = "навигатор", agentOrchestrator = agentOrchestrator,
            ttsEnabled = false, ttsEngine = ttsEngine,
        )
        val answerHookCalls = mutableListOf<String>()
        controller.showAnswerHook = { text -> answerHookCalls.add(text) }

        controller.startSession()
        Thread.sleep(500)

        assertEquals(listOf("Первое.", "Первое. Второе."), answerHookCalls)
    }
}
