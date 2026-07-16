package com.bydmate.app.voice

import android.content.Context
import com.bydmate.app.agent.AgentOrchestrator
import com.bydmate.app.agent.AgentResult
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.AutomationEngine
import com.bydmate.app.data.automation.DispatchResult
import com.bydmate.app.data.automation.VoiceFireResult
import com.bydmate.app.R
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.remote.DiParsData
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Safety regression tests for VoiceGate integration in VoiceController.
 *
 * Tests two invariants:
 * 1. execute() passes the live vehicle snapshot to dispatch (parity with AutomationEngine).
 * 2. Disabled VoiceGate prevents dispatch from ever being called.
 */
class VoiceControllerSafetyTest {

    // Russian phrase recognized by NluParser as a window CLOSE command.
    // NluParser maps this to the DiPlus command "车窗关闭".
    private val windowClosePhrase = "закрой окна"

    // #87: voice_error_model_missing resource string (the "model not ready" cause of
    // onPttPressed's else-branch; voice_error_lang_not_ru is the other, untested in this file).
    private val modelMissingMsg = "Голосовая модель не загружена. Скачайте её в Настройках, раздел Голос-агент."

    /** A relaxed TtsEngine mock's `speaking` StateFlow<Boolean> property, being a mocked
     *  interface itself, has no real backing state -- its collect() completes without ever
     *  emitting. VoiceController's scheduleClear() awaits `speaking.first { !it }` after every
     *  terminal outcome, so an unstubbed `speaking` throws NoSuchElementException on that
     *  background job. A real StateFlow backing it (already false) lets first{} match immediately. */
    private fun quietTtsEngine(): TtsEngine = mockk<TtsEngine>(relaxed = true) {
        every { speaking } returns MutableStateFlow(false)
    }

    /** Drivable double for the continuous session: `ready` gates onPttPressed()'s branch, and
     *  tests push one scripted utterance at a time onto [events] once the controller has
     *  subscribed. pcm is intentionally ignored -- no test in this file exercises frame muting. */
    private class FakeContinuousAsr(var ready: Boolean = true) : ContinuousAsr {
        val events = MutableSharedFlow<ContinuousAsrEvent>(extraBufferCapacity = 16)
        override fun isReady(): Boolean = ready
        override fun transcribe(pcm: Flow<ShortArray>): Flow<ContinuousAsrEvent> = events
    }

    /** transcribe() throws the instant it is collected -- reproduces the top-level
     *  catch(t: Throwable) path in startContinuousSession() (a broken recognizer stream). */
    private class FailingContinuousAsr : ContinuousAsr {
        override fun isReady(): Boolean = true
        override fun transcribe(pcm: Flow<ShortArray>): Flow<ContinuousAsrEvent> = flow {
            throw RuntimeException("boom")
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
     *  with FakeContinuousAsr's collector subscribing. subscriptionCount is the real barrier: a
     *  tryEmit before any collector attaches (replay = 0) is simply lost. */
    private fun awaitSubscribed(events: MutableSharedFlow<*>) = awaitTrue { events.subscriptionCount.value >= 1 }

    private fun makeController(
        gateEnabled: Boolean,
        snapshot: DiParsData?,
        dispatcher: ActionDispatcher,
        journal: VoiceJournal = VoiceJournal(),
        ttsEnabled: Boolean = false,
        ttsEngine: TtsEngine = quietTtsEngine(),
        agentIdentity: () -> AgentIdentity = { AgentIdentity("", AgentPersona.NAVIGATOR) },
        continuousAsr: ContinuousAsr = FakeContinuousAsr(ready = true),
    ): VoiceController {
        val gate = mockk<VoiceGate>()
        every { gate.isEnabled() } returns gateEnabled
        every { gate.vehicleSnapshot() } returns snapshot
        // Fix D: gate.preferredLang() must be stubbed so existing tests continue to compile.
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

        val agentOrchestrator = mockk<AgentOrchestrator>()
        coEvery { agentOrchestrator.ask(any(), any()) } returns AgentResult.Disabled
        // Default: no pending agent question (individual tests override AFTER construction).
        coEvery { agentOrchestrator.expectsFollowUp() } returns false

        return VoiceController(audioCapture, dispatcher, localePrefs, earcon, gate,
            automationEngine, automationResolver, agentOrchestrator, mockk<Context>(relaxed = true),
            ttsEngine, journal, continuousAsr, agentIdentity = agentIdentity,
            ttsModelManager = mockk(relaxed = true),
            ruStressMarker = RuStressMarker { null },
            selectedTtsVoice = { TtsVoiceCatalog.byId("dmitri") })
    }

    private fun makeControllerWithAutomation(
        dispatcher: ActionDispatcher,
        matchedRuleId: Long,
        journal: VoiceJournal = VoiceJournal(),
        continuousAsr: ContinuousAsr = FakeContinuousAsr(ready = true),
    ): VoiceController {
        val gate = mockk<VoiceGate>()
        every { gate.isEnabled() } returns true
        every { gate.vehicleSnapshot() } returns null
        every { gate.preferredLang() } returns null
        every { gate.ttsEnabled() } returns false

        val audioCapture = mockk<AudioCapture>(relaxed = true)
        every { audioCapture.captureSession(any()) } returns flow { /* empty — completes immediately */ }

        val localePrefs = mockk<LocalePreferences>(relaxed = true)
        every { localePrefs.getLanguage() } returns "ru"

        val earcon = mockk<VoiceEarcon>(relaxed = true)

        val automationEngine = mockk<AutomationEngine>(relaxed = true)
        coEvery { automationEngine.fireVoiceRule(any(), any()) } returns VoiceFireResult.Fired(true)

        val automationResolver = mockk<VoiceAutomationResolver>()
        coEvery { automationResolver.match(any()) } returns matchedRuleId

        val agentOrchestrator = mockk<AgentOrchestrator>()
        coEvery { agentOrchestrator.ask(any(), any()) } returns AgentResult.Disabled
        // Default: no pending agent question (individual tests override AFTER construction).
        coEvery { agentOrchestrator.expectsFollowUp() } returns false

        return VoiceController(audioCapture, dispatcher, localePrefs, earcon, gate,
            automationEngine, automationResolver, agentOrchestrator, mockk<Context>(relaxed = true),
            quietTtsEngine(), journal, continuousAsr,
            agentIdentity = { AgentIdentity("", AgentPersona.NAVIGATOR) },
            ttsModelManager = mockk(relaxed = true),
            ruStressMarker = RuStressMarker { null },
            selectedTtsVoice = { TtsVoiceCatalog.byId("dmitri") })
    }

    /**
     * Assertion 1: execute() passes the live snapshot (not null) to dispatch.
     * Mocks gate.vehicleSnapshot() = DiParsData(speed=120), starts a continuous session and feeds
     * it one utterance, waits for the internal coroutine (VoiceController uses Dispatchers.Default),
     * then asserts the captured 'data' arg has speed=120.
     * Uses a CLOSE command so the null-snapshot fail-closed guard (Fix A) does not fire.
     */
    @Test
    fun `execute passes live vehicle snapshot to dispatch`() {
        val snapshot = DiParsData(
            speed = 120, soc = null, mileage = null, power = null,
            chargeGunState = null, maxBatTemp = null, avgBatTemp = null,
            minBatTemp = null, chargingStatus = null, batteryCapacityKwh = null,
            totalElecConsumption = null, voltage12v = null, maxCellVoltage = null,
            minCellVoltage = null, exteriorTemp = null, gear = null, powerState = null,
            insideTemp = null, acStatus = null, acTemp = null, fanLevel = null,
            acCirc = null, doorFL = null, doorFR = null, doorRL = null, doorRR = null,
            windowFL = null, windowFR = null, windowRL = null, windowRR = null,
            sunroof = null, trunk = null, hood = null, seatbeltFL = null,
            lockFL = null, tirePressFL = null, tirePressFR = null,
            tirePressRL = null, tirePressRR = null, driveMode = null,
            workMode = null, autoPark = null, rain = null, lightLow = null, drl = null,
        )

        // Use an AtomicReference to capture the nullable data argument from the dispatch call.
        val capturedData = AtomicReference<DiParsData?>(null)
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        coEvery { dispatcher.dispatch(any<ActionDef>(), any()) } coAnswers {
            capturedData.set(secondArg<DiParsData?>())
            DispatchResult(false, "blocked")
        }

        // windowClosePhrase ("закрой окна") → "车窗关闭" → isWindowOpenCommand = false,
        // so the fail-closed guard is not activated and dispatch is reached.
        val fakeAsr = FakeContinuousAsr(ready = true)
        val controller = makeController(gateEnabled = true, snapshot = snapshot, dispatcher = dispatcher, continuousAsr = fakeAsr)
        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)
        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance(windowClosePhrase))

        // Give the internal SupervisorJob coroutine time to execute.
        Thread.sleep(500)

        coVerify(atLeast = 1) { dispatcher.dispatch(any(), any()) }
        assertNotNull("dispatch must receive a non-null snapshot", capturedData.get())
        assertEquals(120, capturedData.get()?.speed)
    }

    /**
     * Assertion 2: Disabled VoiceGate prevents dispatch from being called at all.
     * gate.isEnabled() = false → onPttPressed() returns immediately, no session is ever started.
     */
    @Test
    fun `disabled gate blocks session — dispatch is never called`() {
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val controller = makeController(gateEnabled = false, snapshot = null, dispatcher = dispatcher)

        controller.onPttPressed()

        // No session was started; nothing to wait for.
        Thread.sleep(200)

        coVerify(exactly = 0) { dispatcher.dispatch(any(), any()) }
    }

    /**
     * Fix (Finding 1): a window-OPEN command with an unknown (null) vehicle snapshot must fail
     * CLOSED — ActionDispatcher.getBlockReason() returns null (no block) when data == null, which
     * would otherwise let a voice "открой окна" through unchecked at unknown speed. VoiceController
     * must intercept this itself, before ever calling dispatch.
     * "открой окна" resolves to "车窗全开" (isWindowOpenCommand == true).
     */
    @Test fun `null snapshot fail-closed blocks window-open command, no dispatch`() {
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val fakeAsr = FakeContinuousAsr(ready = true)
        val controller = makeController(
            gateEnabled = true, snapshot = null, dispatcher = dispatcher, continuousAsr = fakeAsr
        )
        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)
        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("открой окна"))
        Thread.sleep(500)

        coVerify(exactly = 0) { dispatcher.dispatch(any(), any()) }
        assertEquals(VoiceUiState.Blocked("Скорость неизвестна"), controller.state.value)
    }

    /**
     * After the T12 predicate split, sunroof (天窗) is no longer part of isWindowOpenCommand —
     * the fail-closed guard must cover isSunroofOpenCommand too, or "открой люк" at unknown
     * speed would dispatch unchecked (the sunroof gate is >80 km/h, stricter than windows).
     */
    @Test fun `null snapshot fail-closed blocks sunroof-open command, no dispatch`() {
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val fakeAsr = FakeContinuousAsr(ready = true)
        val controller = makeController(
            gateEnabled = true, snapshot = null, dispatcher = dispatcher, continuousAsr = fakeAsr
        )
        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)
        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("открой люк"))
        Thread.sleep(500)

        coVerify(exactly = 0) { dispatcher.dispatch(any(), any()) }
        assertEquals(VoiceUiState.Blocked("Скорость неизвестна"), controller.state.value)
    }

    /**
     * Mirror of the above: a NON-window command (climate ON) with a null snapshot must still
     * dispatch (fail-soft preserved) — the fail-closed guard (Finding 1) is scoped to window-open
     * commands only. "включи кондиционер" resolves to "自动空调" (ON + AC_AUTO), not a window command.
     */
    @Test fun `null snapshot still dispatches non-window command`() {
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        coEvery { dispatcher.dispatch(any<ActionDef>(), any()) } returns DispatchResult(true)
        val fakeAsr = FakeContinuousAsr(ready = true)
        val controller = makeController(
            gateEnabled = true, snapshot = null, dispatcher = dispatcher, continuousAsr = fakeAsr
        )
        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)
        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("включи кондиционер"))
        Thread.sleep(500)

        coVerify(exactly = 1) { dispatcher.dispatch(match { it.command == "自动空调" }, any()) }
    }

    /**
     * Resolution chain: phrase unrecognized by built-in NluParser but matched by
     * automationResolver → AutomationEngine.fireVoiceRule called; built-in
     * param-dispatch (ActionDispatcher.dispatch) is NOT called.
     *
     * Uses "навигатор" — a phrase NluParser does not recognize as a built-in command.
     */
    @Test
    fun `unrecognized builtin but matched automation fires the rule`() {
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val fakeAsr = FakeContinuousAsr(ready = true)
        val controller = makeControllerWithAutomation(
            dispatcher = dispatcher, matchedRuleId = 42L, continuousAsr = fakeAsr
        )
        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)
        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("навигатор"))
        Thread.sleep(300)
        // Built-in param-path must NOT be called when automation resolver handles it.
        coVerify(exactly = 0) { dispatcher.dispatch(match { it.kind == "param" }, any()) }
    }

    // Helper: builds a full DiParsData with only acTemp set; all other fields null
    // (speed = 0 so the window-open guard does not fire on non-window commands).
    private fun snapshotWithAcTemp(t: Int?): DiParsData = DiParsData(
        speed = 0, soc = null, mileage = null, power = null,
        chargeGunState = null, maxBatTemp = null, avgBatTemp = null,
        minBatTemp = null, chargingStatus = null, batteryCapacityKwh = null,
        totalElecConsumption = null, voltage12v = null, maxCellVoltage = null,
        minCellVoltage = null, exteriorTemp = null, gear = null, powerState = null,
        insideTemp = null, acStatus = null, acTemp = t, fanLevel = null,
        acCirc = null, doorFL = null, doorFR = null, doorRL = null, doorRR = null,
        windowFL = null, windowFR = null, windowRL = null, windowRR = null,
        sunroof = null, trunk = null, hood = null, seatbeltFL = null,
        lockFL = null, tirePressFL = null, tirePressFR = null,
        tirePressRL = null, tirePressRR = null, driveMode = null,
        workMode = null, autoPark = null, rain = null, lightLow = null, drl = null,
    )

    @Test fun `relative warmer dispatches acTemp plus one`() {
        val captured = AtomicReference<String?>(null)
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        coEvery { dispatcher.dispatch(any<ActionDef>(), any()) } coAnswers {
            captured.set(firstArg<ActionDef>().command); DispatchResult(true)
        }
        val snap = snapshotWithAcTemp(22)
        val fakeAsr = FakeContinuousAsr(ready = true)
        val controller = makeController(true, snap, dispatcher, continuousAsr = fakeAsr)
        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)
        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("теплее"))
        Thread.sleep(500)
        assertEquals("设置温度23", captured.get())
    }

    @Test fun `relative warmer with null acTemp is blocked, no dispatch`() {
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val fakeAsr = FakeContinuousAsr(ready = true)
        val controller = makeController(true, snapshotWithAcTemp(null), dispatcher, continuousAsr = fakeAsr)
        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)
        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("теплее"))
        Thread.sleep(500)
        coVerify(exactly = 0) { dispatcher.dispatch(any(), any()) }
    }

    @Test fun `voice state auto-returns to Idle after a terminal state`() {
        // scheduleIdleReset() now only fires from onPttPressed()'s "model not ready" branch: the
        // continuous per-utterance path never sticks on a terminal state on its own -- it goes
        // back to Listening on the next utterance, and to Idle immediately when the session itself
        // ends. continuousAsr.isReady() == false drives that branch without starting a session.
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val fakeAsr = FakeContinuousAsr(ready = false)
        val controller = makeController(true, null, dispatcher, continuousAsr = fakeAsr)
        controller.idleResetDelayMs = 50L

        controller.onPttPressed()
        assertEquals(VoiceUiState.NotUnderstood(""), controller.state.value)

        // ~50ms later the scheduled reset fires.
        Thread.sleep(400)
        assertEquals(VoiceUiState.Idle, controller.state.value)
    }

    @Test fun `louder dispatches media_volume plus one`() {
        val captured = AtomicReference<ActionDef?>(null)
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        coEvery { dispatcher.dispatch(any<ActionDef>(), any()) } coAnswers {
            captured.set(firstArg<ActionDef>()); DispatchResult(true)
        }
        val fakeAsr = FakeContinuousAsr(ready = true)
        val controller = makeController(true, null, dispatcher, continuousAsr = fakeAsr)
        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)
        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("сделай громче"))
        Thread.sleep(500)
        assertEquals("media_volume", captured.get()?.kind)
        assertEquals("+1", captured.get()?.payload)
    }

    // --- Task 4: every terminal outcome is also spoken, not only shown as an overlay ---

    @Test fun `dispatched command speaks Готово when tts enabled`() {
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        coEvery { dispatcher.dispatch(any<ActionDef>(), any()) } returns DispatchResult(true)
        val ttsEngine = quietTtsEngine()
        val fakeAsr = FakeContinuousAsr(ready = true)
        val controller = makeController(
            gateEnabled = true, snapshot = null, dispatcher = dispatcher,
            ttsEnabled = true, ttsEngine = ttsEngine, continuousAsr = fakeAsr,
        )

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)
        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance(windowClosePhrase))
        Thread.sleep(500)

        val navigatorDonePool = listOf("Готово. Что-нибудь ещё?", "Сделано, командир.",
            "Выполнил. Хорошей дороги.", "Готово.")
        verify { ttsEngine.speak(match { it in navigatorDonePool }) }
    }

    @Test fun `announce speaks persona phrase for canonical outcome`() {
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        coEvery { dispatcher.dispatch(any<ActionDef>(), any()) } returns DispatchResult(true)
        val ttsEngine = quietTtsEngine()
        val fakeAsr = FakeContinuousAsr(ready = true)
        val controller = makeController(
            gateEnabled = true, snapshot = null, dispatcher = dispatcher,
            ttsEnabled = true, ttsEngine = ttsEngine,
            agentIdentity = { AgentIdentity("", AgentPersona.ENGINEER) },
            continuousAsr = fakeAsr,
        )

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)
        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance(windowClosePhrase))
        Thread.sleep(500)

        val engineerDonePool = listOf("Есть.", "Выполнено.", "Принято. Сделано.", "Готово.")
        verify { ttsEngine.speak(match { it in engineerDonePool }) }
    }

    @Test fun `announce passes through non-canonical spoken unchanged`() {
        val gate = mockk<VoiceGate>()
        every { gate.isEnabled() } returns true
        every { gate.vehicleSnapshot() } returns null
        every { gate.preferredLang() } returns null
        every { gate.ttsEnabled() } returns true

        val audioCapture = mockk<AudioCapture>(relaxed = true)
        val localePrefs = mockk<LocalePreferences>(relaxed = true)
        every { localePrefs.getLanguage() } returns "ru"
        val earcon = mockk<VoiceEarcon>(relaxed = true)
        val automationEngine = mockk<AutomationEngine>(relaxed = true)
        val automationResolver = mockk<VoiceAutomationResolver>()
        val agentOrchestrator = mockk<AgentOrchestrator>()
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val ttsEngine = quietTtsEngine()

        // voice_error_model_missing is a literal fixed string, not drawn from a persona
        // done-pool — this is what "non-canonical" means here (see the two pool-based tests above).
        val context = mockk<Context>(relaxed = true)
        every { context.getString(R.string.voice_error_model_missing) } returns modelMissingMsg
        val controller = VoiceController(audioCapture, dispatcher, localePrefs, earcon, gate,
            automationEngine, automationResolver, agentOrchestrator, context,
            ttsEngine, VoiceJournal(), FakeContinuousAsr(ready = false),
            agentIdentity = { AgentIdentity("", AgentPersona.ENGINEER) },
            ttsModelManager = mockk(relaxed = true),
            ruStressMarker = RuStressMarker { null },
            selectedTtsVoice = { TtsVoiceCatalog.byId("dmitri") })

        controller.onPttPressed()
        Thread.sleep(300)

        verify { ttsEngine.speak(modelMissingMsg) }
    }

    @Test fun `dispatched command does not speak when tts disabled`() {
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        coEvery { dispatcher.dispatch(any<ActionDef>(), any()) } returns DispatchResult(true)
        val ttsEngine = quietTtsEngine()
        val fakeAsr = FakeContinuousAsr(ready = true)
        val controller = makeController(
            gateEnabled = true, snapshot = null, dispatcher = dispatcher,
            ttsEnabled = false, ttsEngine = ttsEngine, continuousAsr = fakeAsr,
        )

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)
        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance(windowClosePhrase))
        Thread.sleep(500)

        verify(exactly = 0) { ttsEngine.speak(any()) }
    }

    // --- Task 2: VoiceJournal + orb-dialog wiring ---

    @Test fun `dispatched command records an NLU-OK journal entry and shows the success orb dialog`() {
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        coEvery { dispatcher.dispatch(any<ActionDef>(), any()) } returns DispatchResult(true)
        val journal = VoiceJournal()
        val fakeAsr = FakeContinuousAsr(ready = true)
        val controller = makeController(gateEnabled = true, snapshot = null, dispatcher = dispatcher, journal = journal, continuousAsr = fakeAsr)
        val presented = AtomicReference<String?>(null)
        controller.showAnswerHook = { text -> presented.set(text) }

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)
        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance(windowClosePhrase))
        Thread.sleep(500)

        val entry = journal.entries.value.first()
        assertEquals(VoiceJournalEntry.Route.NLU, entry.route)
        assertEquals(VoiceJournalEntry.Outcome.OK, entry.outcome)
        assertEquals(null, entry.reason)
        assertEquals(windowClosePhrase, entry.transcript)
        assertEquals("Услышал: «$windowClosePhrase». Выполнено", presented.get())
    }

    // --- Task 6: fast-path commands are noted into the agent's dialog history ---

    /**
     * A fast-path NLU dispatch is invisible to the agent unless VoiceController tells it. On
     * every success terminal, VoiceController must call agentOrchestrator.noteAction(transcript)
     * so a later follow-up ("а теперь закрой") has the "закрой окна" it refers to in context.
     *
     * Fix (Finding 3) coverage: noteAction is stubbed to hang for 60s (simulating a busy
     * AgentOrchestrator mutex) to prove the call is fire-and-forget (`scope.launch { ... }`), not
     * awaited. The real differentiator on the continuous path is that a SECOND utterance is still
     * processed (dispatched) well within this test's normal wait -- only possible if routeUtterance()
     * returned (and processingUtterance flipped back to false) without ever awaiting the hung
     * noteAction call. Under the old (reverted) synchronous
     * `runCatching { agentOrchestrator.noteAction(transcript) }`, the enclosing coroutine would be
     * suspended inside execute() for the full 60s, so the second utterance would still be dropped
     * ("busy") at the point this test checks.
     */
    @Test fun `NLU success calls agent noteAction with the transcript`() {
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        coEvery { dispatcher.dispatch(any<ActionDef>(), any()) } returns DispatchResult(true)

        val gate = mockk<VoiceGate>()
        every { gate.isEnabled() } returns true
        every { gate.vehicleSnapshot() } returns null
        every { gate.preferredLang() } returns null
        every { gate.ttsEnabled() } returns false

        val audioCapture = mockk<AudioCapture>(relaxed = true)
        every { audioCapture.captureSession(any()) } returns flow { /* empty — completes immediately */ }

        val localePrefs = mockk<LocalePreferences>(relaxed = true)
        every { localePrefs.getLanguage() } returns "ru"
        val earcon = mockk<VoiceEarcon>(relaxed = true)
        val automationEngine = mockk<AutomationEngine>(relaxed = true)
        val automationResolver = mockk<VoiceAutomationResolver>()
        coEvery { automationResolver.match(any()) } returns null

        val agentOrchestrator = mockk<AgentOrchestrator>()
        coEvery { agentOrchestrator.ask(any(), any()) } returns AgentResult.Disabled
        coEvery { agentOrchestrator.expectsFollowUp() } returns false
        // Simulates a busy AgentOrchestrator mutex (e.g. a concurrent ask() holding the lock) —
        // if VoiceController awaited this call, the whole voice session would stall behind it.
        coEvery { agentOrchestrator.noteAction(any()) } coAnswers { kotlinx.coroutines.delay(60_000) }

        val fakeAsr = FakeContinuousAsr(ready = true)
        val controller = VoiceController(audioCapture, dispatcher, localePrefs, earcon, gate,
            automationEngine, automationResolver, agentOrchestrator, mockk<Context>(relaxed = true),
            quietTtsEngine(), VoiceJournal(), fakeAsr,
            agentIdentity = { AgentIdentity("", AgentPersona.NAVIGATOR) },
            ttsModelManager = mockk(relaxed = true),
            ruStressMarker = RuStressMarker { null },
            selectedTtsVoice = { TtsVoiceCatalog.byId("dmitri") })
        val presented = AtomicReference<String?>(null)
        controller.showAnswerHook = { text -> presented.set(text) }

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)
        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance(windowClosePhrase))
        Thread.sleep(500)

        coVerify(exactly = 1) { agentOrchestrator.noteAction(windowClosePhrase) }
        assertEquals("Услышал: «$windowClosePhrase». Выполнено", presented.get())

        // Fire-and-forget proof: a second utterance is still accepted and dispatched, which
        // requires processingUtterance to have already flipped back to false.
        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance(windowClosePhrase))
        Thread.sleep(500)
        coVerify(exactly = 2) { dispatcher.dispatch(any(), any()) }
    }

    /**
     * Mirror of the NLU-dispatch noteAction test above, for the automation-fire success terminal
     * (fireAutomation's VoiceFireResult.Fired(true) branch) — a different noteAction call site than
     * the param-dispatch one, so it needs its own coverage.
     */
    @Test fun `automation success calls agent noteAction with the transcript`() {
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val automationEngine = mockk<AutomationEngine>(relaxed = true)
        coEvery { automationEngine.fireVoiceRule(any(), any()) } returns VoiceFireResult.Fired(true)
        val recognizedPhrase = "навигатор"

        val gate = mockk<VoiceGate>()
        every { gate.isEnabled() } returns true
        every { gate.vehicleSnapshot() } returns null
        every { gate.preferredLang() } returns null
        every { gate.ttsEnabled() } returns false

        val audioCapture = mockk<AudioCapture>(relaxed = true)
        every { audioCapture.captureSession(any()) } returns flow { /* empty */ }

        val localePrefs = mockk<LocalePreferences>(relaxed = true)
        every { localePrefs.getLanguage() } returns "ru"
        val earcon = mockk<VoiceEarcon>(relaxed = true)

        val automationResolver = mockk<VoiceAutomationResolver>()
        coEvery { automationResolver.match(any()) } returns 42L

        val agentOrchestrator = mockk<AgentOrchestrator>()
        coEvery { agentOrchestrator.ask(any(), any()) } returns AgentResult.Disabled
        coEvery { agentOrchestrator.expectsFollowUp() } returns false
        coEvery { agentOrchestrator.noteAction(any()) } returns Unit

        val fakeAsr = FakeContinuousAsr(ready = true)
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
        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance(recognizedPhrase))
        Thread.sleep(500)

        coVerify(exactly = 1) { agentOrchestrator.noteAction(recognizedPhrase) }
    }

    @Test fun `blocked command records an NLU-BLOCKED journal entry with reason and shows the block orb dialog`() {
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val journal = VoiceJournal()
        // "открой окна" + null snapshot -> fail-closed guard, blocked before dispatch.
        val fakeAsr = FakeContinuousAsr(ready = true)
        val controller = makeController(
            gateEnabled = true, snapshot = null, dispatcher = dispatcher,
            journal = journal, continuousAsr = fakeAsr,
        )
        val presented = AtomicReference<String?>(null)
        controller.showAnswerHook = { text -> presented.set(text) }

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)
        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("открой окна"))
        Thread.sleep(500)

        val entry = journal.entries.value.first()
        assertEquals(VoiceJournalEntry.Route.NLU, entry.route)
        assertEquals(VoiceJournalEntry.Outcome.BLOCKED, entry.outcome)
        assertEquals("Скорость неизвестна", entry.reason)
        assertEquals(
            "Услышал: «открой окна». Отказ: Скорость неизвестна",
            presented.get(),
        )
    }

    @Test fun `automation rule not found records a NONE-NOT_UNDERSTOOD journal entry`() {
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val automationEngine = mockk<AutomationEngine>(relaxed = true)
        coEvery { automationEngine.fireVoiceRule(any(), any()) } returns VoiceFireResult.NotFound
        val journal = VoiceJournal()

        val gate = mockk<VoiceGate>()
        every { gate.isEnabled() } returns true
        every { gate.vehicleSnapshot() } returns null
        every { gate.preferredLang() } returns null
        every { gate.ttsEnabled() } returns false

        val audioCapture = mockk<AudioCapture>(relaxed = true)
        every { audioCapture.captureSession(any()) } returns flow { /* empty */ }

        val localePrefs = mockk<LocalePreferences>(relaxed = true)
        every { localePrefs.getLanguage() } returns "ru"
        val earcon = mockk<VoiceEarcon>(relaxed = true)

        val automationResolver = mockk<VoiceAutomationResolver>()
        coEvery { automationResolver.match(any()) } returns 7L

        val agentOrchestrator = mockk<AgentOrchestrator>()
        coEvery { agentOrchestrator.ask(any(), any()) } returns AgentResult.Disabled
        coEvery { agentOrchestrator.expectsFollowUp() } returns false

        val fakeAsr = FakeContinuousAsr(ready = true)
        val controller = VoiceController(audioCapture, dispatcher, localePrefs, earcon, gate,
            automationEngine, automationResolver, agentOrchestrator, mockk<Context>(relaxed = true),
            quietTtsEngine(), journal, fakeAsr,
            agentIdentity = { AgentIdentity("", AgentPersona.NAVIGATOR) },
            ttsModelManager = mockk(relaxed = true),
            ruStressMarker = RuStressMarker { null },
            selectedTtsVoice = { TtsVoiceCatalog.byId("dmitri") })

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)
        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("навигатор"))
        Thread.sleep(500)

        val entry = journal.entries.value.first()
        assertEquals(VoiceJournalEntry.Route.NONE, entry.route)
        assertEquals(VoiceJournalEntry.Outcome.NOT_UNDERSTOOD, entry.outcome)
        assertEquals(VoiceUiState.NotUnderstood("навигатор"), controller.state.value)
    }

    @Test fun `relative warmer with null acTemp shows the block orb dialog exactly once`() {
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val fakeAsr = FakeContinuousAsr(ready = true)
        val controller = makeController(true, snapshotWithAcTemp(null), dispatcher, continuousAsr = fakeAsr)
        val feedbackCount = AtomicInteger(0)
        val presented = AtomicReference<String?>(null)
        controller.showAnswerHook = { text -> feedbackCount.incrementAndGet(); presented.set(text) }

        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitSubscribed(fakeAsr.events)
        fakeAsr.events.tryEmit(ContinuousAsrEvent.Utterance("теплее"))
        Thread.sleep(500)

        assertEquals(1, feedbackCount.get())
        assertEquals("Услышал: «теплее». Отказ: Не знаю текущую температуру", presented.get())
    }

    @Test fun `model not ready shows the not-loaded orb dialog exactly once`() {
        val gate = mockk<VoiceGate>()
        every { gate.isEnabled() } returns true
        every { gate.vehicleSnapshot() } returns null
        every { gate.preferredLang() } returns null
        every { gate.ttsEnabled() } returns false

        val audioCapture = mockk<AudioCapture>(relaxed = true)
        val localePrefs = mockk<LocalePreferences>(relaxed = true)
        every { localePrefs.getLanguage() } returns "ru"
        val earcon = mockk<VoiceEarcon>(relaxed = true)
        val automationEngine = mockk<AutomationEngine>(relaxed = true)
        val automationResolver = mockk<VoiceAutomationResolver>()
        val agentOrchestrator = mockk<AgentOrchestrator>()
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)

        val context = mockk<Context>(relaxed = true)
        every { context.getString(R.string.voice_error_model_missing) } returns modelMissingMsg
        val controller = VoiceController(audioCapture, dispatcher, localePrefs, earcon, gate,
            automationEngine, automationResolver, agentOrchestrator, context,
            quietTtsEngine(), VoiceJournal(), FakeContinuousAsr(ready = false),
            agentIdentity = { AgentIdentity("", AgentPersona.NAVIGATOR) },
            ttsModelManager = mockk(relaxed = true),
            ruStressMarker = RuStressMarker { null },
            selectedTtsVoice = { TtsVoiceCatalog.byId("dmitri") })
        val feedbackCount = AtomicInteger(0)
        val presented = AtomicReference<String?>(null)
        controller.showAnswerHook = { text -> feedbackCount.incrementAndGet(); presented.set(text) }

        controller.onPttPressed()
        Thread.sleep(300)

        assertEquals(1, feedbackCount.get())
        assertEquals(modelMissingMsg, presented.get())
    }

    @Test fun `a pipeline crash shows the failure orb dialog exactly once`() {
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        // A broken transcribe() stream reproduces the top-level catch(t: Throwable) path.
        val controller = makeController(
            gateEnabled = true, snapshot = null, dispatcher = dispatcher,
            continuousAsr = FailingContinuousAsr(),
        )
        val feedbackCount = AtomicInteger(0)
        val presented = AtomicReference<String?>(null)
        controller.showAnswerHook = { text -> feedbackCount.incrementAndGet(); presented.set(text) }

        controller.onPttPressed()
        Thread.sleep(500)

        assertEquals(1, feedbackCount.get())
        assertEquals("Отказ: boom", presented.get())
    }
}
