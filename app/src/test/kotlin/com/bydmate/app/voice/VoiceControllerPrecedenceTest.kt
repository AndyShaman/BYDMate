package com.bydmate.app.voice

import android.content.Context
import com.bydmate.app.agent.AgentOrchestrator
import com.bydmate.app.agent.AgentResult
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.AutomationEngine
import com.bydmate.app.data.automation.DispatchResult
import com.bydmate.app.data.automation.VoiceFireResult
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.split.AppSet
import com.bydmate.app.split.AppSetLauncher
import com.bydmate.app.split.AppSetStore
import com.bydmate.app.split.SplitSessionManager
import com.bydmate.app.split.SplitStartResult
import com.bydmate.app.util.appStringsOver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Collections

/**
 * Resolver precedence on the continuous path (VoiceController.resolve): an automation or user
 * phrase equal to the whole utterance first, then the built-in parser, then the agent. A phrase
 * inside a longer utterance never fires.
 */
class VoiceControllerPrecedenceTest {

    private class FakeContinuousAsr : ContinuousAsr {
        val events = MutableSharedFlow<ContinuousAsrEvent>(extraBufferCapacity = 16)
        override fun isReady(): Boolean = true
        override fun transcribe(pcm: Flow<ShortArray>): Flow<ContinuousAsrEvent> = events
    }

    private class Rig(
        val controller: VoiceController,
        val asr: FakeContinuousAsr,
        val dispatcher: ActionDispatcher,
        val engine: AutomationEngine,
        val journal: VoiceJournal,
    ) {
        val dispatched: MutableList<String> = Collections.synchronizedList(mutableListOf())
    }

    private fun awaitTrue(timeoutMs: Long = 10_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        fail("condition not met within ${timeoutMs}ms")
    }

    /** «Разделение экрана»: [mainMethod] = the split master switch on (Android 10: «Основной»). */
    private class SplitSetup(
        val mainMethod: Boolean = false,
        val manager: SplitSessionManager = mockk { coEvery { startLastPair() } returns SplitStartResult.OK },
        val legacy: Boolean = true,
    )

    /** A real VoiceAutomationResolver over one enabled rule (id 9, name «Моё») with [automationPhrase]. */
    private fun rig(
        automationPhrase: String?,
        userPhrases: VoiceUserPhrases,
        appSets: Pair<AppSetStore, AppSetLauncher>? = null,
        split: SplitSetup = SplitSetup(),
    ): Rig {
        val gate = mockk<VoiceGate> {
            every { isEnabled() } returns true
            every { vehicleSnapshot() } returns null
            every { ttsEnabled() } returns false
        }
        val audioCapture = mockk<AudioCapture>(relaxed = true)
        every { audioCapture.captureSession(any(), any<(ShortArray) -> Any?>()) } returns flow { }
        val rules = listOfNotNull(automationPhrase?.let { voiceRule(it) })
        val resolver = VoiceAutomationResolver(mockk<RuleDao> { coEvery { getEnabled() } returns rules })
        val engine = mockk<AutomationEngine>(relaxed = true)
        coEvery { engine.fireVoiceRule(any(), any()) } returns VoiceFireResult.Fired
        val agent = mockk<AgentOrchestrator>(relaxed = true)
        coEvery { agent.ask(any(), any(), any()) } returns AgentResult.Disabled
        coEvery { agent.expectsFollowUp() } returns false
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.createConfigurationContext(any()) } returns ctx
        val tts = mockk<TtsEngine>(relaxed = true) { every { speaking } returns MutableStateFlow(false) }
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val asr = FakeContinuousAsr()
        val journal = VoiceJournal()
        val controller = VoiceController(
            audioCapture, dispatcher, mockk(relaxed = true), gate,
            engine, resolver, agent, ctx, tts, journal, asr,
            agentIdentity = { AgentIdentity("", AgentPersona.NAVIGATOR) },
            ttsModelManager = mockk(relaxed = true),
            ruStressMarker = RuStressMarker { null },
            selectedTtsVoice = { TtsVoiceCatalog.byId("dmitri") },
            appStrings = appStringsOver(ctx),
            userPhrases = userPhrases,
        )
        controller.legacyHeadUnit = { split.legacy }
        appSets?.let { (store, launcher) ->
            controller.injectAppSets(
                store, launcher, mockk { every { isFeatureEnabled() } returns split.mainMethod },
                dagger.Lazy { split.manager },
            )
        }
        return Rig(controller, asr, dispatcher, engine, journal).also { r ->
            coEvery { dispatcher.dispatch(any<ActionDef>(), any()) } answers {
                r.dispatched += firstArg<ActionDef>().command
                DispatchResult(true)
            }
        }
    }

    private fun voiceRule(phrase: String) = RuleEntity(
        id = 9L, name = "Моё", enabled = true, triggerLogic = "AND",
        triggers = """[{"param":"Voice","chineseName":"语音","operator":"==","value":"$phrase","displayName":"$phrase","kind":"voice"}]""",
        actions = """[{"command":"","displayName":"x","kind":"app_launch","payload":"{}"}]""",
    )

    /** The journal label the built-in parser alone gives [text]; fails when it does not parse. */
    private fun parserLabel(text: String): String {
        val parsed = NluParser.parse(text)
        assertTrue("parser must understand «$text», got $parsed", parsed is ParseResult.Command)
        return VoiceCommandLabels.of((parsed as ParseResult.Command).commands)
    }

    private fun phrases(id: String, phrase: String) = VoiceUserPhrases().apply { add(id, phrase) }

    private fun Rig.say(text: String): VoiceJournalEntry {
        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitTrue { asr.events.subscriptionCount.value >= 1 }
        asr.events.tryEmit(ContinuousAsrEvent.Utterance(text))
        awaitTrue { journal.entries.value.isNotEmpty() }
        return journal.entries.value.first()
    }

    // --- exact matches outrank the parser ---

    @Test fun `exact automation phrase beats a built-in command phrase`() {
        val r = rig("закрой окна", VoiceUserPhrases())
        val entry = r.say("закрой окна")

        assertEquals(VoiceJournalEntry.Route.AUTOMATION, entry.route)
        assertEquals("Моё", entry.command)
        coVerify(exactly = 1) { r.engine.fireVoiceRule(9L, any()) }
        assertEquals(emptyList<String>(), r.dispatched.toList())
    }

    @Test fun `exact automation phrase beats an exact user phrase`() {
        val r = rig("задраить трюм", phrases("windows_close_all", "задраить трюм"))

        assertEquals(VoiceJournalEntry.Route.AUTOMATION, r.say("задраить трюм").route)
        assertEquals(emptyList<String>(), r.dispatched.toList())
    }

    @Test fun `automation phrase with fillers around it is exact`() {
        val r = rig("включи режим дом", VoiceUserPhrases())
        val entry = r.say("эй включи режим дом")

        assertEquals(VoiceJournalEntry.Route.AUTOMATION, entry.route)
        coVerify(exactly = 1) { r.engine.fireVoiceRule(9L, any()) }
    }

    @Test fun `exact user phrase beats the built-in parser`() {
        // The parser alone would open the windows; the user tied this phrase to closing them.
        val r = rig(null, phrases("windows_close_all", "открой окна"))
        val entry = r.say("открой окна")

        assertEquals(VoiceJournalEntry.Route.NLU, entry.route)
        assertEquals(VoiceJournalEntry.Outcome.OK, entry.outcome)
        assertEquals("phrase:windows_close_all", entry.command)
        assertEquals(listOf("车窗关闭"), r.dispatched.toList())
    }

    @Test fun `user phrase with fillers stripped is exact and beats the parser`() {
        val text = "слушай открой окно пожалуйста"
        assertNotEquals("phrase:windows_close_all", parserLabel(text))
        val r = rig(null, phrases("windows_close_all", "открой окно"))

        assertEquals("phrase:windows_close_all", r.say(text).command)
        assertEquals(listOf("车窗关闭"), r.dispatched.toList())
    }

    // --- the driver's app sets (Android 10 head units) ---

    private val setStore = AppSetStore().apply {
        upsert(AppSet("set1", "открой окна", "com.zhiliaoapp.musically", "ru.yandex.music"))
    }

    private fun setLauncher(outcome: AppSetLauncher.Outcome = AppSetLauncher.Outcome.OK) =
        mockk<AppSetLauncher> { coEvery { launch(any()) } returns outcome }

    @Test fun `split plus a set name beats the built-in parser and opens the set`() {
        val launcher = setLauncher()
        val r = rig(null, VoiceUserPhrases(), appSets = setStore to launcher)
        val entry = r.say("сплит открой окна")

        assertEquals(VoiceJournalEntry.Route.NLU, entry.route)
        assertEquals(VoiceJournalEntry.Outcome.OK, entry.outcome)
        assertEquals("appset:открой окна", entry.command)
        coVerify(exactly = 1) { launcher.launch(match { it.id == "set1" }) }
        assertEquals(emptyList<String>(), r.dispatched.toList())
    }

    @Test fun `the split screen phrase opens the first set`() {
        val launcher = setLauncher()
        val r = rig(null, VoiceUserPhrases(), appSets = setStore to launcher)

        assertEquals("appset:открой окна", r.say("включи разделение экрана").command)
        coVerify(exactly = 1) { launcher.launch(match { it.id == "set1" }) }
    }

    @Test fun `with the 1-3 split switched on no set opens, by name either`() {
        // One utterance per rig: say() reads the first journal entry.
        val launcher = setLauncher()
        val bare = rig(null, VoiceUserPhrases(), appSets = setStore to launcher, split = SplitSetup(mainMethod = true))
        assertNotEquals("appset:открой окна", bare.say("включи разделение экрана").command)

        val named = rig(null, VoiceUserPhrases(), appSets = setStore to launcher, split = SplitSetup(mainMethod = true))
        assertNotEquals("appset:открой окна", named.say("сплит открой окна").command)

        coVerify(exactly = 0) { launcher.launch(any()) }
    }

    @Test fun `with the main method the split screen phrase opens its saved pair`() {
        val manager = mockk<SplitSessionManager> { coEvery { startLastPair() } returns SplitStartResult.OK }
        val launcher = setLauncher()
        val r = rig(null, VoiceUserPhrases(), appSets = setStore to launcher, split = SplitSetup(mainMethod = true, manager = manager))

        val entry = r.say("включи разделение экрана")

        assertEquals("split:start", entry.command)
        assertEquals(VoiceJournalEntry.Outcome.OK, entry.outcome)
        coVerify(exactly = 1) { manager.startLastPair() }
        coVerify(exactly = 0) { launcher.launch(any()) }
    }

    @Test fun `with the main method and no saved pair the phrase is reported as blocked`() {
        val manager = mockk<SplitSessionManager> { coEvery { startLastPair() } returns null }
        val r = rig(null, VoiceUserPhrases(), appSets = setStore to setLauncher(), split = SplitSetup(mainMethod = true, manager = manager))

        assertEquals(VoiceJournalEntry.Outcome.BLOCKED, r.say("сплит").outcome)
    }

    @Test fun `other firmwares keep the split screen phrase for the agent`() {
        val manager = mockk<SplitSessionManager> { coEvery { startLastPair() } returns SplitStartResult.OK }
        val r = rig(
            null, VoiceUserPhrases(), appSets = setStore to setLauncher(),
            split = SplitSetup(mainMethod = true, manager = manager, legacy = false),
        )

        assertNotEquals("split:start", r.say("включи разделение экрана").command)
        coVerify(exactly = 0) { manager.startLastPair() }
    }

    @Test fun `an app set that does not open is reported as blocked`() {
        val r = rig(null, VoiceUserPhrases(), appSets = setStore to setLauncher(AppSetLauncher.Outcome.LEFT_FAILED))

        assertEquals(VoiceJournalEntry.Outcome.BLOCKED, r.say("сплит открой окна").outcome)
    }

    @Test fun `a set name without split is left to the parser`() {
        val text = "открой окна"
        val expected = parserLabel(text)
        val launcher = setLauncher()
        val r = rig(null, VoiceUserPhrases(), appSets = setStore to launcher)

        assertEquals(expected, r.say(text).command)
        coVerify(exactly = 0) { launcher.launch(any()) }
    }

    @Test fun `an automation phrase outranks an app set`() {
        val r = rig("сплит открой окна", VoiceUserPhrases(), appSets = setStore to setLauncher())

        assertEquals(VoiceJournalEntry.Route.AUTOMATION, r.say("сплит открой окна").route)
    }

    // --- a phrase inside a longer utterance never fires ---

    @Test fun `a user phrase inside a longer utterance leaves it to the parser`() {
        val text = "открой окно наполовину"
        val expected = parserLabel(text)
        val r = rig(null, phrases("windows_close_all", "открой окно"))
        val entry = r.say(text)

        assertEquals(VoiceJournalEntry.Route.NLU, entry.route)
        assertEquals(expected, entry.command)
        assertTrue(r.dispatched.none { it == "车窗关闭" })
    }

    @Test fun `an automation phrase inside a longer utterance leaves it to the parser`() {
        val text = "открой окно наполовину"
        val expected = parserLabel(text)
        val r = rig("окно", VoiceUserPhrases())
        val entry = r.say(text)

        assertEquals(VoiceJournalEntry.Route.NLU, entry.route)
        assertEquals(expected, entry.command)
        coVerify(exactly = 0) { r.engine.fireVoiceRule(any(), any()) }
    }

    // --- exact means word for word, not the same stems ---

    @Test fun `another inflection of a user phrase is not exact and the parser keeps the phrase`() {
        val text = "открой окно"
        val expected = parserLabel(text)
        val r = rig(null, phrases("windows_close_all", "открой окна"))
        val entry = r.say(text)

        assertEquals(VoiceJournalEntry.Route.NLU, entry.route)
        assertEquals(expected, entry.command)
        assertTrue(r.dispatched.none { it == "车窗关闭" })
    }

    @Test fun `another inflection of an automation phrase is not exact and the parser keeps the phrase`() {
        val text = "открой окно"
        val expected = parserLabel(text)
        val r = rig("открой окна", VoiceUserPhrases())
        val entry = r.say(text)

        assertEquals(VoiceJournalEntry.Route.NLU, entry.route)
        assertEquals(expected, entry.command)
        coVerify(exactly = 0) { r.engine.fireVoiceRule(any(), any()) }
    }

    // --- anything else goes to the agent and no phrase inside it acts ---

    /** [text] went to the agent with [reason] and nothing acted on it. */
    private fun Rig.assertRefused(text: String, reason: String) {
        val entry = say(text)
        assertEquals(text, VoiceJournalEntry.Route.REFUSED, entry.route)
        assertEquals(text, reason, entry.refusal)
        assertEquals(text, emptyList<String>(), dispatched.toList())
        coVerify(exactly = 0) { engine.fireVoiceRule(any(), any()) }
    }

    @Test fun `a user phrase inside an utterance the parser does not read goes to the agent`() {
        rig(null, phrases("windows_close_all", "задраить трюм"))
            .assertRefused("ну задраить трюм быстро", VoiceRefusal.UNRECOGNIZED)
    }

    @Test fun `an automation phrase inside an utterance the parser does not read goes to the agent`() {
        rig("задраить трюм", VoiceUserPhrases()).assertRefused("ну задраить трюм быстро", VoiceRefusal.UNRECOGNIZED)
        rig("открой окно", VoiceUserPhrases()).assertRefused("открой окно в салоне на палец", VoiceRefusal.UNRECOGNIZED)
        rig("открой люк", VoiceUserPhrases()).assertRefused("открой люк позже", VoiceRefusal.UNRECOGNIZED)
    }

    @Test fun `negation around a user or automation phrase goes to the agent`() {
        rig(null, phrases("windows_close_all", "открой окно")).assertRefused("не открой окно", VoiceRefusal.UNRECOGNIZED)
        rig("задраить трюм", VoiceUserPhrases()).assertRefused("нельзя задраить трюм", VoiceRefusal.UNRECOGNIZED)
    }

    @Test fun `phrases the dictionary does not hold go to the agent`() {
        rig("открой окно", VoiceUserPhrases()).assertRefused("открой окно полностью наполовину", VoiceRefusal.UNRECOGNIZED)
        rig(null, phrases("windows_close_all", "открой окна"))
            .assertRefused("открой окна и люк наполовину", VoiceRefusal.UNRECOGNIZED)
    }

    @Test fun `an exact user phrase with a negation word is still the user's own`() {
        val r = rig(null, phrases("windows_close_all", "не дуй"))
        assertEquals("phrase:windows_close_all", r.say("не дуй").command)
        assertEquals(listOf("车窗关闭"), r.dispatched.toList())
    }

    // --- no user-made phrase ---

    @Test fun `without automation or user phrase the parser handles the phrase`() {
        val r = rig(null, VoiceUserPhrases())
        val entry = r.say("закрой окна")

        assertEquals(VoiceJournalEntry.Route.NLU, entry.route)
        assertEquals(listOf("车窗关闭"), r.dispatched.toList())
    }

    @Test fun `nothing matched goes to the agent with the unrecognized reason`() {
        val r = rig(null, VoiceUserPhrases())
        val entry = r.say("расскажи анекдот")

        assertEquals(VoiceJournalEntry.Route.REFUSED, entry.route)
        assertEquals(VoiceRefusal.UNRECOGNIZED, entry.refusal)
        assertEquals(emptyList<String>(), r.dispatched.toList())
    }
}
