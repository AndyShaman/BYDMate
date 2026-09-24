package com.bydmate.app.voice

import android.content.Context
import com.bydmate.app.agent.AgentOrchestrator
import com.bydmate.app.agent.AgentResult
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.AutomationEngine
import com.bydmate.app.data.automation.DispatchResult
import com.bydmate.app.data.automation.VoiceFireResult
import com.bydmate.app.data.local.entity.ActionDef
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
import org.junit.Assert.fail
import org.junit.Test
import java.util.Collections

/**
 * Resolver precedence on the continuous path (VoiceController.resolve): automations first,
 * then the user's own command phrases, then the built-in parser, then the agent.
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

    private fun awaitTrue(timeoutMs: Long = 2_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        fail("condition not met within ${timeoutMs}ms")
    }

    private fun rig(automation: VoiceAutomationMatch?, userPhrases: VoiceUserPhrases): Rig {
        val gate = mockk<VoiceGate> {
            every { isEnabled() } returns true
            every { vehicleSnapshot() } returns null
            every { ttsEnabled() } returns false
        }
        val audioCapture = mockk<AudioCapture>(relaxed = true)
        every { audioCapture.captureSession(any()) } returns flow { }
        val resolver = mockk<VoiceAutomationResolver>()
        coEvery { resolver.match(any()) } returns automation
        val engine = mockk<AutomationEngine>(relaxed = true)
        coEvery { engine.fireVoiceRule(any(), any()) } returns VoiceFireResult.Fired(true)
        val agent = mockk<AgentOrchestrator>(relaxed = true)
        coEvery { agent.ask(any(), any()) } returns AgentResult.Disabled
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
        return Rig(controller, asr, dispatcher, engine, journal).also { r ->
            coEvery { dispatcher.dispatch(any<ActionDef>(), any()) } answers {
                r.dispatched += firstArg<ActionDef>().command
                DispatchResult(true)
            }
        }
    }

    private fun Rig.say(text: String): VoiceJournalEntry {
        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitTrue { asr.events.subscriptionCount.value >= 1 }
        asr.events.tryEmit(ContinuousAsrEvent.Utterance(text))
        awaitTrue { journal.entries.value.isNotEmpty() }
        return journal.entries.value.first()
    }

    @Test fun `automation beats a built-in command phrase`() {
        val r = rig(VoiceAutomationMatch(9L, "Моё"), VoiceUserPhrases())
        val entry = r.say("закрой окна")

        assertEquals(VoiceJournalEntry.Route.AUTOMATION, entry.route)
        assertEquals("Моё", entry.command)
        coVerify(exactly = 1) { r.engine.fireVoiceRule(9L, any()) }
        assertEquals(emptyList<String>(), r.dispatched.toList())
    }

    @Test fun `automation beats a user phrase`() {
        val phrases = VoiceUserPhrases().apply { add("windows_close_all", "задраить люки") }
        val r = rig(VoiceAutomationMatch(3L, "Люки"), phrases)

        assertEquals(VoiceJournalEntry.Route.AUTOMATION, r.say("задраить люки").route)
        assertEquals(emptyList<String>(), r.dispatched.toList())
    }

    @Test fun `user phrase beats the built-in parser`() {
        // The parser alone would open the windows; the user tied this phrase to closing them.
        val phrases = VoiceUserPhrases().apply { add("windows_close_all", "открой окна") }
        val r = rig(null, phrases)
        val entry = r.say("открой окна")

        assertEquals(VoiceJournalEntry.Route.NLU, entry.route)
        assertEquals(VoiceJournalEntry.Outcome.OK, entry.outcome)
        assertEquals("phrase:windows_close_all", entry.command)
        assertEquals(listOf("车窗关闭"), r.dispatched.toList())
    }

    @Test fun `user phrase contained in a longer utterance runs its command`() {
        val phrases = VoiceUserPhrases().apply { add("windows_close_all", "задраить люки") }
        val r = rig(null, phrases)

        assertEquals("phrase:windows_close_all", r.say("эй задраить люки пожалуйста").command)
        assertEquals(listOf("车窗关闭"), r.dispatched.toList())
    }

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
