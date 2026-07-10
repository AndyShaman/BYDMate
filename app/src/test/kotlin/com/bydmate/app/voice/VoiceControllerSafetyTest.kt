package com.bydmate.app.voice

import android.content.Context
import com.bydmate.app.agent.AgentOrchestrator
import com.bydmate.app.agent.AgentResult
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.AutomationEngine
import com.bydmate.app.data.automation.DispatchResult
import com.bydmate.app.data.automation.VoiceFireResult
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.remote.DiParsData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    /** A relaxed TtsEngine mock's `speaking` StateFlow<Boolean> property, being a mocked
     *  interface itself, has no real backing state -- its collect() completes without ever
     *  emitting. VoiceController's scheduleClear() awaits `speaking.first { !it }` after every
     *  terminal outcome, so an unstubbed `speaking` throws NoSuchElementException on that
     *  background job. A real StateFlow backing it (already false) lets first{} match immediately. */
    private fun quietTtsEngine(): TtsEngine = mockk<TtsEngine>(relaxed = true) {
        every { speaking } returns MutableStateFlow(false)
    }

    private fun makeController(
        gateEnabled: Boolean,
        snapshot: DiParsData?,
        dispatcher: ActionDispatcher,
        // Parameterized so callers can inject any transcript for NLU tests.
        recognizedPhrase: String = windowClosePhrase,
        journal: VoiceJournal = VoiceJournal(),
        ttsEnabled: Boolean = false,
        ttsEngine: TtsEngine = quietTtsEngine(),
        agentIdentity: () -> AgentIdentity = { AgentIdentity("", AgentPersona.NAVIGATOR) },
    ): VoiceController {
        val gate = mockk<VoiceGate>()
        every { gate.isEnabled() } returns gateEnabled
        every { gate.vehicleSnapshot() } returns snapshot
        // Fix D: gate.preferredLang() must be stubbed so existing tests continue to compile.
        every { gate.preferredLang() } returns null
        every { gate.ttsEnabled() } returns ttsEnabled

        val audioCapture = mockk<AudioCapture>(relaxed = true)
        every { audioCapture.captureSession(any()) } returns flow { /* empty — completes immediately */ }

        val asrEngine = mockk<AsrEngine>(relaxed = true)
        every { asrEngine.isModelReady(any()) } returns true
        // Single Final event reproduces the old "recognize the whole phrase, then route" behaviour.
        every { asrEngine.recognize(any(), any(), any()) } returns flowOf(AsrEvent.Final(recognizedPhrase))

        val modelManager = mockk<VoiceModelManager>(relaxed = true)

        val localePrefs = mockk<LocalePreferences>(relaxed = true)
        every { localePrefs.getLanguage() } returns "ru"

        val earcon = mockk<VoiceEarcon>(relaxed = true)

        val automationEngine = mockk<AutomationEngine>(relaxed = true)
        val automationResolver = mockk<VoiceAutomationResolver>()
        coEvery { automationResolver.phrases() } returns emptyList()
        coEvery { automationResolver.match(any()) } returns null

        val agentOrchestrator = mockk<AgentOrchestrator>()
        coEvery { agentOrchestrator.ask(any(), any()) } returns AgentResult.Disabled
        // Default: no pending agent question (individual tests override AFTER construction).
        coEvery { agentOrchestrator.expectsFollowUp() } returns false

        return VoiceController(audioCapture, asrEngine, modelManager, dispatcher, localePrefs, earcon, gate,
            automationEngine, automationResolver, agentOrchestrator, mockk<Context>(relaxed = true),
            ttsEngine, journal, mockk<ContinuousAsr>(relaxed = true), agentIdentity = agentIdentity,
            ttsModelManager = mockk(relaxed = true),
            ruStressMarker = RuStressMarker { null },
            selectedTtsVoice = { TtsVoiceCatalog.byId("dmitri") })
    }

    private fun makeControllerWithAutomation(
        dispatcher: ActionDispatcher,
        recognizedPhrase: String,
        matchedRuleId: Long,
        journal: VoiceJournal = VoiceJournal(),
    ): VoiceController {
        val gate = mockk<VoiceGate>()
        every { gate.isEnabled() } returns true
        every { gate.vehicleSnapshot() } returns null
        every { gate.preferredLang() } returns null
        every { gate.ttsEnabled() } returns false

        val audioCapture = mockk<AudioCapture>(relaxed = true)
        every { audioCapture.captureSession(any()) } returns flow { /* empty — completes immediately */ }

        val asrEngine = mockk<AsrEngine>(relaxed = true)
        every { asrEngine.isModelReady(any()) } returns true
        // Single Final event reproduces the old "recognize the whole phrase, then route" behaviour.
        every { asrEngine.recognize(any(), any(), any()) } returns flowOf(AsrEvent.Final(recognizedPhrase))

        val modelManager = mockk<VoiceModelManager>(relaxed = true)

        val localePrefs = mockk<LocalePreferences>(relaxed = true)
        every { localePrefs.getLanguage() } returns "ru"

        val earcon = mockk<VoiceEarcon>(relaxed = true)

        val automationEngine = mockk<AutomationEngine>(relaxed = true)
        coEvery { automationEngine.fireVoiceRule(any(), any()) } returns VoiceFireResult.Fired(true)

        val automationResolver = mockk<VoiceAutomationResolver>()
        coEvery { automationResolver.phrases() } returns emptyList()
        coEvery { automationResolver.match(any()) } returns matchedRuleId

        val agentOrchestrator = mockk<AgentOrchestrator>()
        coEvery { agentOrchestrator.ask(any(), any()) } returns AgentResult.Disabled
        // Default: no pending agent question (individual tests override AFTER construction).
        coEvery { agentOrchestrator.expectsFollowUp() } returns false

        return VoiceController(audioCapture, asrEngine, modelManager, dispatcher, localePrefs, earcon, gate,
            automationEngine, automationResolver, agentOrchestrator, mockk<Context>(relaxed = true),
            quietTtsEngine(), journal, mockk<ContinuousAsr>(relaxed = true),
            agentIdentity = { AgentIdentity("", AgentPersona.NAVIGATOR) },
            ttsModelManager = mockk(relaxed = true),
            ruStressMarker = RuStressMarker { null },
            selectedTtsVoice = { TtsVoiceCatalog.byId("dmitri") })
    }

    /**
     * Assertion 1: execute() passes the live snapshot (not null) to dispatch.
     * Mocks gate.vehicleSnapshot() = DiParsData(speed=120), calls startSession(),
     * waits for the internal coroutine (VoiceController uses Dispatchers.Default),
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
        val controller = makeController(gateEnabled = true, snapshot = snapshot, dispatcher = dispatcher)
        controller.startSession()

        // Give the internal SupervisorJob coroutine time to execute.
        Thread.sleep(500)

        coVerify(atLeast = 1) { dispatcher.dispatch(any(), any()) }
        assertNotNull("dispatch must receive a non-null snapshot", capturedData.get())
        assertEquals(120, capturedData.get()?.speed)
    }

    /**
     * Assertion 2: Disabled VoiceGate prevents dispatch from being called at all.
     * gate.isEnabled() = false → startSession() returns immediately before the coroutine launches.
     */
    @Test
    fun `disabled gate blocks session — dispatch is never called`() {
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val controller = makeController(gateEnabled = false, snapshot = null, dispatcher = dispatcher)

        controller.startSession()

        // No coroutine was launched; nothing to wait for.
        Thread.sleep(200)

        coVerify(exactly = 0) { dispatcher.dispatch(any(), any()) }
    }

    /**
     * Fix (Finding 1): a window-OPEN command with an unknown (null) vehicle snapshot must fail
     * CLOSED — ActionDispatcher.getBlockReason() returns null (no block) when data == null, which
     * would otherwise let a voice "открой окна" through unchecked at unknown speed. VoiceController
     * must intercept this itself, before ever calling dispatch.
     * "открой окна" resolves to "车窗全开" (isWindowOpenCommand == true) per the comment on the
     * aperture-open early-fire test below.
     */
    @Test fun `null snapshot fail-closed blocks window-open command, no dispatch`() {
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val controller = makeController(
            gateEnabled = true, snapshot = null, dispatcher = dispatcher, recognizedPhrase = "открой окна"
        )
        controller.startSession()
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
        val controller = makeController(
            gateEnabled = true, snapshot = null, dispatcher = dispatcher, recognizedPhrase = "открой люк"
        )
        controller.startSession()
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
        val controller = makeController(
            gateEnabled = true, snapshot = null, dispatcher = dispatcher, recognizedPhrase = "включи кондиционер"
        )
        controller.startSession()
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
        val controller = makeControllerWithAutomation(
            dispatcher = dispatcher, recognizedPhrase = "навигатор", matchedRuleId = 42L
        )
        controller.startSession()
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
        val controller = makeController(true, snap, dispatcher, recognizedPhrase = "теплее")
        controller.startSession()
        Thread.sleep(500)
        assertEquals("设置温度23", captured.get())
    }

    @Test fun `relative warmer with null acTemp is blocked, no dispatch`() {
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val controller = makeController(true, snapshotWithAcTemp(null), dispatcher, recognizedPhrase = "теплее")
        controller.startSession()
        Thread.sleep(500)
        coVerify(exactly = 0) { dispatcher.dispatch(any(), any()) }
    }

    @Test fun `voice state auto-returns to Idle after a terminal state`() {
        // "навигатор" is unrecognized by NluParser and automationResolver.match() returns null
        // (makeController default) → terminal NotUnderstood. The state must then fall back to Idle
        // on its own instead of sticking red. Short reset delay via the test seam.
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val controller = makeController(true, null, dispatcher, recognizedPhrase = "навигатор")
        controller.idleResetDelayMs = 50L
        controller.startSession()
        // Session completes immediately (empty capture flow) → NotUnderstood, then ~50ms → Idle.
        Thread.sleep(400)
        assertEquals(VoiceUiState.Idle, controller.state.value)
    }

    @Test fun `louder dispatches media_volume plus one`() {
        val captured = AtomicReference<ActionDef?>(null)
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        coEvery { dispatcher.dispatch(any<ActionDef>(), any()) } coAnswers {
            captured.set(firstArg<ActionDef>()); DispatchResult(true)
        }
        val controller = makeController(true, null, dispatcher, recognizedPhrase = "сделай громче")
        controller.startSession()
        Thread.sleep(500)
        assertEquals("media_volume", captured.get()?.kind)
        assertEquals("+1", captured.get()?.payload)
    }

    // Streaming helper: drives the controller with a scripted AsrEvent flow so partial/final routing
    // and early-fire can be exercised directly (the regular helpers emit a single Final).
    private fun makeControllerStreaming(
        dispatcher: ActionDispatcher,
        events: Flow<AsrEvent>,
        snapshot: DiParsData? = null,
        journal: VoiceJournal = VoiceJournal(),
    ): VoiceController {
        val gate = mockk<VoiceGate>()
        every { gate.isEnabled() } returns true
        every { gate.vehicleSnapshot() } returns snapshot
        every { gate.preferredLang() } returns null
        every { gate.ttsEnabled() } returns false

        val audioCapture = mockk<AudioCapture>(relaxed = true)
        every { audioCapture.captureSession(any()) } returns flow { /* empty */ }

        val asrEngine = mockk<AsrEngine>(relaxed = true)
        every { asrEngine.isModelReady(any()) } returns true
        every { asrEngine.recognize(any(), any(), any()) } returns events

        val modelManager = mockk<VoiceModelManager>(relaxed = true)
        val localePrefs = mockk<LocalePreferences>(relaxed = true)
        every { localePrefs.getLanguage() } returns "ru"
        val earcon = mockk<VoiceEarcon>(relaxed = true)
        val automationEngine = mockk<AutomationEngine>(relaxed = true)
        val automationResolver = mockk<VoiceAutomationResolver>()
        coEvery { automationResolver.phrases() } returns emptyList()
        coEvery { automationResolver.match(any()) } returns null

        val agentOrchestrator = mockk<AgentOrchestrator>()
        coEvery { agentOrchestrator.ask(any(), any()) } returns AgentResult.Disabled
        // Default: no pending agent question (individual tests override AFTER construction).
        coEvery { agentOrchestrator.expectsFollowUp() } returns false

        return VoiceController(audioCapture, asrEngine, modelManager, dispatcher, localePrefs, earcon, gate,
            automationEngine, automationResolver, agentOrchestrator, mockk<Context>(relaxed = true),
            quietTtsEngine(), journal, mockk<ContinuousAsr>(relaxed = true),
            agentIdentity = { AgentIdentity("", AgentPersona.NAVIGATOR) },
            ttsModelManager = mockk(relaxed = true),
            ruStressMarker = RuStressMarker { null },
            selectedTtsVoice = { TtsVoiceCatalog.byId("dmitri") })
    }

    /**
     * Early-fire: a confident command that stabilises across EARLY_FIRE_STABLE_READS (3) identical
     * partials is executed immediately — before the Vosk VAD final arrives. The bare verb "закрой"
     * does not resolve (no device) so it never fires; the full "закрой окна" held for 3 reads does.
     * The Final carries a different phrase that must NEVER be routed (proves early-fire won).
     */
    @Test fun `early-fires on a stable resolved partial before the final`() {
        val captured = AtomicReference<String?>(null)
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        coEvery { dispatcher.dispatch(any<ActionDef>(), any()) } coAnswers {
            captured.set(firstArg<ActionDef>().command); DispatchResult(true)
        }
        val events = flow {
            emit(AsrEvent.Partial("закрой"))
            emit(AsrEvent.Partial("закрой окна"))
            emit(AsrEvent.Partial("закрой окна"))
            emit(AsrEvent.Partial("закрой окна"))   // 3rd identical → early-fire
            emit(AsrEvent.Final("совершенно другое"))
        }
        val controller = makeControllerStreaming(dispatcher, events)
        controller.startSession()
        Thread.sleep(500)
        assertEquals("车窗关闭", captured.get())
    }

    /**
     * Prefix-misfire guard: an aperture OPEN must NOT early-fire, because a bare noun resolves to a
     * DIFFERENT command than the qualified phrase. "открой окна" → 车窗全开 (all windows) holds stable
     * for 3 reads while the user pauses mid-phrase, but it must wait for the Vosk final
     * "открой окно водителя" → 主驾打开100 (driver). Without the guard, early-fire would open all
     * windows instead of the driver's.
     * Uses a known-speed snapshot (not the default null) — the final resolves to a window-OPEN
     * command, and a null snapshot would now correctly fail-closed (Finding 1) before dispatch,
     * which is not what this test is exercising.
     */
    @Test fun `aperture-open partial does not early-fire — waits for the qualified final`() {
        val captured = AtomicReference<String?>(null)
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        coEvery { dispatcher.dispatch(any<ActionDef>(), any()) } coAnswers {
            captured.set(firstArg<ActionDef>().command); DispatchResult(true)
        }
        val events = flow {
            emit(AsrEvent.Partial("открой окна"))
            emit(AsrEvent.Partial("открой окна"))
            emit(AsrEvent.Partial("открой окна"))   // 3rd identical → would early-fire 车窗全开 if ungated
            emit(AsrEvent.Final("открой окно водителя"))
        }
        val controller = makeControllerStreaming(dispatcher, events, snapshot = snapshotWithAcTemp(null))
        controller.startSession()
        Thread.sleep(500)
        assertEquals("主驾打开100", captured.get())
    }

    /**
     * Sunroof mirror of the aperture-open early-fire guard: after the T12 predicate split 天窗
     * left isWindowOpenCommand, so "открой люк" partials would early-fire unless the guard also
     * checks isSunroofOpenCommand. Known-speed snapshot so a dispatch, if any, is not stopped by
     * the fail-closed guard — exactly one dispatch (the final) must happen.
     */
    @Test fun `sunroof-open partial does not early-fire — waits for the final`() {
        val captured = AtomicReference<String?>(null)
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        coEvery { dispatcher.dispatch(any<ActionDef>(), any()) } coAnswers {
            captured.set(firstArg<ActionDef>().command); DispatchResult(true)
        }
        val events = flow {
            emit(AsrEvent.Partial("открой люк"))
            emit(AsrEvent.Partial("открой люк"))
            emit(AsrEvent.Partial("открой люк"))   // 3rd identical → would early-fire 天窗打开100 if ungated
            emit(AsrEvent.Final("закрой люк"))
        }
        val controller = makeControllerStreaming(dispatcher, events, snapshot = snapshotWithAcTemp(null))
        controller.startSession()
        Thread.sleep(500)
        coVerify(exactly = 1) { dispatcher.dispatch(any(), any()) }
        assertEquals("天窗打开0", captured.get())
    }

    /**
     * Sunshade mirror: 遮阳帘 is in NEITHER speed predicate after the split (it is never
     * speed-gated), but it is still an aperture whose bare-noun partial can be qualified —
     * the early-fire guard uses isSunshadeOpenCommand to keep waiting for the final.
     */
    @Test fun `sunshade-open partial does not early-fire — waits for the final`() {
        val captured = AtomicReference<String?>(null)
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        coEvery { dispatcher.dispatch(any<ActionDef>(), any()) } coAnswers {
            captured.set(firstArg<ActionDef>().command); DispatchResult(true)
        }
        val events = flow {
            emit(AsrEvent.Partial("открой шторку"))
            emit(AsrEvent.Partial("открой шторку"))
            emit(AsrEvent.Partial("открой шторку"))   // 3rd identical → would early-fire 遮阳帘打开 if ungated
            emit(AsrEvent.Final("закрой шторку"))
        }
        val controller = makeControllerStreaming(dispatcher, events, snapshot = snapshotWithAcTemp(null))
        controller.startSession()
        Thread.sleep(500)
        coVerify(exactly = 1) { dispatcher.dispatch(any(), any()) }
        assertEquals("遮阳帘关闭", captured.get())
    }

    /**
     * No partial stabilises into a command (the lone "ммм" never resolves), so routing falls through
     * to the Vosk VAD final, which resolves and dispatches normally.
     */
    @Test fun `routes the final when no partial stabilises into a command`() {
        val captured = AtomicReference<String?>(null)
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        coEvery { dispatcher.dispatch(any<ActionDef>(), any()) } coAnswers {
            captured.set(firstArg<ActionDef>().command); DispatchResult(true)
        }
        val events = flow {
            emit(AsrEvent.Partial("ммм"))
            emit(AsrEvent.Final("закрой окна"))
        }
        val controller = makeControllerStreaming(dispatcher, events)
        controller.startSession()
        Thread.sleep(500)
        assertEquals("车窗关闭", captured.get())
    }

    // --- Task 4: every terminal outcome is also spoken, not only shown as an overlay ---

    @Test fun `dispatched command speaks Готово when tts enabled`() {
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        coEvery { dispatcher.dispatch(any<ActionDef>(), any()) } returns DispatchResult(true)
        val ttsEngine = quietTtsEngine()
        val controller = makeController(
            gateEnabled = true, snapshot = null, dispatcher = dispatcher,
            ttsEnabled = true, ttsEngine = ttsEngine,
        )

        controller.startSession()
        Thread.sleep(500)

        val navigatorDonePool = listOf("Готово. Что-нибудь ещё?", "Сделано, командир.",
            "Выполнил. Хорошей дороги.", "Готово.")
        verify { ttsEngine.speak(match { it in navigatorDonePool }) }
    }

    @Test fun `announce speaks persona phrase for canonical outcome`() {
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        coEvery { dispatcher.dispatch(any<ActionDef>(), any()) } returns DispatchResult(true)
        val ttsEngine = quietTtsEngine()
        val controller = makeController(
            gateEnabled = true, snapshot = null, dispatcher = dispatcher,
            ttsEnabled = true, ttsEngine = ttsEngine,
            agentIdentity = { AgentIdentity("", AgentPersona.ENGINEER) },
        )

        controller.startSession()
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
        val asrEngine = mockk<AsrEngine>(relaxed = true)
        every { asrEngine.isModelReady(any()) } returns false

        val modelManager = mockk<VoiceModelManager>(relaxed = true)
        val localePrefs = mockk<LocalePreferences>(relaxed = true)
        every { localePrefs.getLanguage() } returns "ru"
        val earcon = mockk<VoiceEarcon>(relaxed = true)
        val automationEngine = mockk<AutomationEngine>(relaxed = true)
        val automationResolver = mockk<VoiceAutomationResolver>()
        coEvery { automationResolver.phrases() } returns emptyList()
        val agentOrchestrator = mockk<AgentOrchestrator>()
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val ttsEngine = quietTtsEngine()

        val controller = VoiceController(audioCapture, asrEngine, modelManager, dispatcher, localePrefs, earcon, gate,
            automationEngine, automationResolver, agentOrchestrator, mockk<Context>(relaxed = true),
            ttsEngine, VoiceJournal(), mockk<ContinuousAsr>(relaxed = true),
            agentIdentity = { AgentIdentity("", AgentPersona.ENGINEER) },
            ttsModelManager = mockk(relaxed = true),
            ruStressMarker = RuStressMarker { null },
            selectedTtsVoice = { TtsVoiceCatalog.byId("dmitri") })

        controller.startSession()
        Thread.sleep(300)

        verify { ttsEngine.speak("Голосовая модель не загружена") }
    }

    @Test fun `dispatched command does not speak when tts disabled`() {
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        coEvery { dispatcher.dispatch(any<ActionDef>(), any()) } returns DispatchResult(true)
        val ttsEngine = quietTtsEngine()
        val controller = makeController(
            gateEnabled = true, snapshot = null, dispatcher = dispatcher,
            ttsEnabled = false, ttsEngine = ttsEngine,
        )

        controller.startSession()
        Thread.sleep(500)

        verify(exactly = 0) { ttsEngine.speak(any()) }
    }

    // --- Task 2: VoiceJournal + orb-dialog wiring ---

    @Test fun `dispatched command records an NLU-OK journal entry and shows the success orb dialog`() {
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        coEvery { dispatcher.dispatch(any<ActionDef>(), any()) } returns DispatchResult(true)
        val journal = VoiceJournal()
        val controller = makeController(gateEnabled = true, snapshot = null, dispatcher = dispatcher, journal = journal)
        val presented = AtomicReference<String?>(null)
        controller.showAnswerHook = { text -> presented.set(text) }

        controller.startSession()
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
     * awaited. `announce()`'s orb-dialog feed already runs before the noteAction call site in source
     * order regardless of sync/async, so that assertion alone would not catch a regression here —
     * the real differentiator is that the session's own terminal bookkeeping (busy flag release +
     * the scheduled Idle auto-reset, both in startSession()'s `finally`) still completes within the
     * test's normal wait. Under the old (reverted) synchronous
     * `runCatching { agentOrchestrator.noteAction(transcript) }`, the enclosing coroutine would be
     * suspended inside execute() for the full 60s, `finally` would never run in time, and the state
     * would still be stuck on Done — never reaching Idle — within this test's window.
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

        val asrEngine = mockk<AsrEngine>(relaxed = true)
        every { asrEngine.isModelReady(any()) } returns true
        every { asrEngine.recognize(any(), any(), any()) } returns flowOf(AsrEvent.Final(windowClosePhrase))

        val modelManager = mockk<VoiceModelManager>(relaxed = true)
        val localePrefs = mockk<LocalePreferences>(relaxed = true)
        every { localePrefs.getLanguage() } returns "ru"
        val earcon = mockk<VoiceEarcon>(relaxed = true)
        val automationEngine = mockk<AutomationEngine>(relaxed = true)
        val automationResolver = mockk<VoiceAutomationResolver>()
        coEvery { automationResolver.phrases() } returns emptyList()
        coEvery { automationResolver.match(any()) } returns null

        val agentOrchestrator = mockk<AgentOrchestrator>()
        coEvery { agentOrchestrator.ask(any(), any()) } returns AgentResult.Disabled
        coEvery { agentOrchestrator.expectsFollowUp() } returns false
        // Simulates a busy AgentOrchestrator mutex (e.g. a concurrent ask() holding the lock) —
        // if VoiceController awaited this call, the whole voice session would stall behind it.
        coEvery { agentOrchestrator.noteAction(any()) } coAnswers { kotlinx.coroutines.delay(60_000) }

        val controller = VoiceController(audioCapture, asrEngine, modelManager, dispatcher, localePrefs, earcon, gate,
            automationEngine, automationResolver, agentOrchestrator, mockk<Context>(relaxed = true),
            quietTtsEngine(), VoiceJournal(), mockk<ContinuousAsr>(relaxed = true),
            agentIdentity = { AgentIdentity("", AgentPersona.NAVIGATOR) },
            ttsModelManager = mockk(relaxed = true),
            ruStressMarker = RuStressMarker { null },
            selectedTtsVoice = { TtsVoiceCatalog.byId("dmitri") })
        controller.idleResetDelayMs = 50L
        val presented = AtomicReference<String?>(null)
        controller.showAnswerHook = { text -> presented.set(text) }

        controller.startSession()
        Thread.sleep(500)

        coVerify(exactly = 1) { agentOrchestrator.noteAction(windowClosePhrase) }
        // announce()'s orb-dialog feed already ran before the noteAction call site regardless of
        // sync/async — kept as the existing overlay-completion sanity check.
        assertEquals("Услышал: «$windowClosePhrase». Выполнено", presented.get())
        // The real fire-and-forget proof: the session's own finally-block bookkeeping (busy
        // release + scheduled Idle auto-reset) still completed and fired within this normal wait,
        // which is only possible if noteAction's 60s hang was never awaited.
        assertEquals(VoiceUiState.Idle, controller.state.value)
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

        val asrEngine = mockk<AsrEngine>(relaxed = true)
        every { asrEngine.isModelReady(any()) } returns true
        every { asrEngine.recognize(any(), any(), any()) } returns flowOf(AsrEvent.Final(recognizedPhrase))

        val modelManager = mockk<VoiceModelManager>(relaxed = true)
        val localePrefs = mockk<LocalePreferences>(relaxed = true)
        every { localePrefs.getLanguage() } returns "ru"
        val earcon = mockk<VoiceEarcon>(relaxed = true)

        val automationResolver = mockk<VoiceAutomationResolver>()
        coEvery { automationResolver.phrases() } returns emptyList()
        coEvery { automationResolver.match(any()) } returns 42L

        val agentOrchestrator = mockk<AgentOrchestrator>()
        coEvery { agentOrchestrator.ask(any(), any()) } returns AgentResult.Disabled
        coEvery { agentOrchestrator.expectsFollowUp() } returns false
        coEvery { agentOrchestrator.noteAction(any()) } returns Unit

        val controller = VoiceController(audioCapture, asrEngine, modelManager, dispatcher, localePrefs, earcon, gate,
            automationEngine, automationResolver, agentOrchestrator, mockk<Context>(relaxed = true),
            quietTtsEngine(), VoiceJournal(), mockk<ContinuousAsr>(relaxed = true),
            agentIdentity = { AgentIdentity("", AgentPersona.NAVIGATOR) },
            ttsModelManager = mockk(relaxed = true),
            ruStressMarker = RuStressMarker { null },
            selectedTtsVoice = { TtsVoiceCatalog.byId("dmitri") })

        controller.startSession()
        Thread.sleep(500)

        coVerify(exactly = 1) { agentOrchestrator.noteAction(recognizedPhrase) }
    }

    @Test fun `blocked command records an NLU-BLOCKED journal entry with reason and shows the block orb dialog`() {
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val journal = VoiceJournal()
        // "открой окна" + null snapshot -> fail-closed guard, blocked before dispatch.
        val controller = makeController(
            gateEnabled = true, snapshot = null, dispatcher = dispatcher,
            recognizedPhrase = "открой окна", journal = journal,
        )
        val presented = AtomicReference<String?>(null)
        controller.showAnswerHook = { text -> presented.set(text) }

        controller.startSession()
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

        val asrEngine = mockk<AsrEngine>(relaxed = true)
        every { asrEngine.isModelReady(any()) } returns true
        every { asrEngine.recognize(any(), any(), any()) } returns flowOf(AsrEvent.Final("навигатор"))

        val modelManager = mockk<VoiceModelManager>(relaxed = true)
        val localePrefs = mockk<LocalePreferences>(relaxed = true)
        every { localePrefs.getLanguage() } returns "ru"
        val earcon = mockk<VoiceEarcon>(relaxed = true)

        val automationResolver = mockk<VoiceAutomationResolver>()
        coEvery { automationResolver.phrases() } returns emptyList()
        coEvery { automationResolver.match(any()) } returns 7L

        val agentOrchestrator = mockk<AgentOrchestrator>()
        coEvery { agentOrchestrator.ask(any(), any()) } returns AgentResult.Disabled
        coEvery { agentOrchestrator.expectsFollowUp() } returns false

        val controller = VoiceController(audioCapture, asrEngine, modelManager, dispatcher, localePrefs, earcon, gate,
            automationEngine, automationResolver, agentOrchestrator, mockk<Context>(relaxed = true),
            quietTtsEngine(), journal, mockk<ContinuousAsr>(relaxed = true),
            agentIdentity = { AgentIdentity("", AgentPersona.NAVIGATOR) },
            ttsModelManager = mockk(relaxed = true),
            ruStressMarker = RuStressMarker { null },
            selectedTtsVoice = { TtsVoiceCatalog.byId("dmitri") })

        controller.startSession()
        Thread.sleep(500)

        val entry = journal.entries.value.first()
        assertEquals(VoiceJournalEntry.Route.NONE, entry.route)
        assertEquals(VoiceJournalEntry.Outcome.NOT_UNDERSTOOD, entry.outcome)
        assertEquals(VoiceUiState.NotUnderstood("навигатор"), controller.state.value)
    }

    @Test fun `relative warmer with null acTemp shows the block orb dialog exactly once`() {
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val controller = makeController(true, snapshotWithAcTemp(null), dispatcher, recognizedPhrase = "теплее")
        val feedbackCount = AtomicInteger(0)
        val presented = AtomicReference<String?>(null)
        controller.showAnswerHook = { text -> feedbackCount.incrementAndGet(); presented.set(text) }

        controller.startSession()
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
        val asrEngine = mockk<AsrEngine>(relaxed = true)
        every { asrEngine.isModelReady(any()) } returns false

        val modelManager = mockk<VoiceModelManager>(relaxed = true)
        val localePrefs = mockk<LocalePreferences>(relaxed = true)
        every { localePrefs.getLanguage() } returns "ru"
        val earcon = mockk<VoiceEarcon>(relaxed = true)
        val automationEngine = mockk<AutomationEngine>(relaxed = true)
        val automationResolver = mockk<VoiceAutomationResolver>()
        coEvery { automationResolver.phrases() } returns emptyList()
        val agentOrchestrator = mockk<AgentOrchestrator>()
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)

        val controller = VoiceController(audioCapture, asrEngine, modelManager, dispatcher, localePrefs, earcon, gate,
            automationEngine, automationResolver, agentOrchestrator, mockk<Context>(relaxed = true),
            quietTtsEngine(), VoiceJournal(), mockk<ContinuousAsr>(relaxed = true),
            agentIdentity = { AgentIdentity("", AgentPersona.NAVIGATOR) },
            ttsModelManager = mockk(relaxed = true),
            ruStressMarker = RuStressMarker { null },
            selectedTtsVoice = { TtsVoiceCatalog.byId("dmitri") })
        val feedbackCount = AtomicInteger(0)
        val presented = AtomicReference<String?>(null)
        controller.showAnswerHook = { text -> feedbackCount.incrementAndGet(); presented.set(text) }

        controller.startSession()
        Thread.sleep(300)

        assertEquals(1, feedbackCount.get())
        assertEquals("Голосовая модель не загружена", presented.get())
    }

    @Test fun `a pipeline crash shows the failure orb dialog exactly once`() {
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        // A broken recognize() stream reproduces the top-level catch(t: Throwable) path.
        val controller = makeControllerStreaming(
            dispatcher, flow { throw RuntimeException("boom") },
        )
        val feedbackCount = AtomicInteger(0)
        val presented = AtomicReference<String?>(null)
        controller.showAnswerHook = { text -> feedbackCount.incrementAndGet(); presented.set(text) }

        controller.startSession()
        Thread.sleep(500)

        assertEquals(1, feedbackCount.get())
        assertEquals("Отказ: boom", presented.get())
    }
}
