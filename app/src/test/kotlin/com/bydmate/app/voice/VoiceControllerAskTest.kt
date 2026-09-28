package com.bydmate.app.voice

import android.content.Context
import com.bydmate.app.agent.AgentOrchestrator
import com.bydmate.app.agent.AgentResult
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.AutomationEngine
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.remote.diParsData
import com.bydmate.app.util.appStringsOver
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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * Read questions of the built-in dictionary («какой заряд», «температура снаружи»): answered
 * from the live snapshot without the agent, or handed to the agent like an unrecognized phrase
 * when the car does not report the value.
 */
class VoiceControllerAskTest {

    private class FakeContinuousAsr : ContinuousAsr {
        val events = MutableSharedFlow<ContinuousAsrEvent>(extraBufferCapacity = 16)
        override fun isReady(): Boolean = true
        override fun transcribe(pcm: Flow<ShortArray>): Flow<ContinuousAsrEvent> = events
    }

    private class Rig(
        val controller: VoiceController,
        val asr: FakeContinuousAsr,
        val agent: AgentOrchestrator,
        val dispatcher: ActionDispatcher,
        val tts: TtsEngine,
        val journal: VoiceJournal,
    ) {
        /** What the orb's answer row shows. */
        val shown = AtomicReference<String?>(null)
    }

    private fun awaitTrue(timeoutMs: Long = 2_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        fail("condition not met within ${timeoutMs}ms")
    }

    private fun rig(
        snapshot: DiParsData?,
        rangeKm: Double? = null,
        agentResult: AgentResult = AgentResult.Disabled,
        ageMs: Long? = FRESH_MS,
    ): Rig {
        val gate = mockk<VoiceGate> {
            every { isEnabled() } returns true
            every { vehicleSnapshot() } returns snapshot
            every { snapshotAgeMs() } returns ageMs
            every { rangeKm() } returns rangeKm
            every { ttsEnabled() } returns true
        }
        val audioCapture = mockk<AudioCapture>(relaxed = true)
        every { audioCapture.captureSession(any(), any<(ShortArray) -> Any?>()) } returns flow { }
        val resolver = mockk<VoiceAutomationResolver>()
        coEvery { resolver.match(any()) } returns null
        val agent = mockk<AgentOrchestrator>(relaxed = true)
        coEvery { agent.ask(any(), any(), any()) } returns agentResult
        coEvery { agent.expectsFollowUp() } returns false
        val ctx = mockk<Context>(relaxed = true)
        val tts = mockk<TtsEngine>(relaxed = true) { every { speaking } returns MutableStateFlow(false) }
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val asr = FakeContinuousAsr()
        val journal = VoiceJournal()
        val controller = VoiceController(
            audioCapture, dispatcher, mockk(relaxed = true), gate,
            mockk<AutomationEngine>(relaxed = true), resolver, agent, ctx, tts, journal, asr,
            agentIdentity = { AgentIdentity("", AgentPersona.NAVIGATOR) },
            ttsModelManager = mockk(relaxed = true),
            ruStressMarker = RuStressMarker { null },
            selectedTtsVoice = { TtsVoiceCatalog.byId("dmitri") },
            appStrings = appStringsOver(ctx),
        )
        return Rig(controller, asr, agent, dispatcher, tts, journal).also { r ->
            controller.showAnswerHook = { r.shown.set(it) }
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

    @Test fun `a read question is answered from the snapshot, said and shown, without the agent`() {
        val r = rig(diParsData(soc = 65))
        val entry = r.say("какой заряд")

        assertEquals(VoiceJournalEntry.Route.NLU, entry.route)
        assertEquals(VoiceJournalEntry.Outcome.OK, entry.outcome)
        assertEquals("ask:soc", entry.command)
        assertEquals("Заряд 65 процентов.", entry.answer)
        awaitTrue { r.shown.get() == "Заряд 65 процентов." }
        verify { r.tts.speak("Заряд 65 процентов.") }
        assertEquals(VoiceUiState.Done("какой заряд"), r.controller.state.value)
        coVerify(exactly = 0) { r.agent.ask(any(), any(), any()) }
        coVerify(exactly = 0) { r.dispatcher.dispatch(any<ActionDef>(), any()) }
    }

    @Test fun `the log line tells a local answer from an agent one`() {
        val entry = rig(diParsData(exteriorTemp = 19)).say("температура снаружи")

        assertTrue(VoiceController.logLine(entry), "route=nlu cmd=ask:outside_temp reason=-" in VoiceController.logLine(entry))
    }

    @Test fun `a frost reading is said with минус`() {
        assertEquals("Снаружи минус 5 градусов.", rig(diParsData(exteriorTemp = -5)).say("сколько градусов на улице").answer)
    }

    @Test fun `range is the range the dashboard shows`() {
        assertEquals("Запас хода 214 километров.", rig(diParsData(soc = 65), rangeKm = 214.4).say("какой запас хода").answer)
    }

    @Test fun `climate setpoint`() {
        assertEquals("Климат на 19 градусов.", rig(diParsData(acTemp = 19)).say("на сколько стоит климат").answer)
    }

    @Test fun `an unknown value goes to the agent with the phrase as said`() {
        val r = rig(diParsData(soc = 65), agentResult = AgentResult.Answer("В салоне около двадцати."))
        val entry = r.say("температура в салоне")

        coVerify(exactly = 1) { r.agent.ask("температура в салоне", any(), any()) }
        assertEquals(VoiceJournalEntry.Route.AGENT, entry.route)
        assertEquals(VoiceRefusal.VALUE_UNKNOWN, entry.refusal)
    }

    @Test fun `no snapshot at all goes to the agent`() {
        val r = rig(null, agentResult = AgentResult.Answer("Нет связи с машиной."))
        r.say("какой заряд")

        coVerify(exactly = 1) { r.agent.ask("какой заряд", any(), any()) }
    }

    @Test fun `a fresh snapshot answers`() {
        assertEquals("Заряд 65 процентов.", rig(diParsData(soc = 65), ageMs = 2_000L).say("какой заряд").answer)
    }

    @Test fun `a snapshot of unknown age goes to the agent`() {
        val r = rig(diParsData(soc = 65), rangeKm = 214.4, agentResult = AgentResult.Answer("Около двухсот."), ageMs = null)
        val entry = r.say("какой запас хода")

        coVerify(exactly = 1) { r.agent.ask("какой запас хода", any(), any()) }
        assertEquals(VoiceRefusal.VALUE_UNKNOWN, entry.refusal)
    }

    @Test fun `a snapshot older than 30 s goes to the agent`() {
        val r = rig(diParsData(soc = 65), agentResult = AgentResult.Answer("Около шестидесяти."), ageMs = 30_001L)
        val entry = r.say("какой заряд")

        coVerify(exactly = 1) { r.agent.ask("какой заряд", any(), any()) }
        assertEquals(VoiceJournalEntry.Route.AGENT, entry.route)
        assertEquals(VoiceRefusal.VALUE_UNKNOWN, entry.refusal)
    }

    @Test fun `a snapshot exactly 30 s old still answers`() {
        val r = rig(diParsData(soc = 65), ageMs = 30_000L)
        val entry = r.say("какой заряд")

        assertEquals("Заряд 65 процентов.", entry.answer)
        coVerify(exactly = 0) { r.agent.ask(any(), any(), any()) }
    }

    @Test fun `an unknown value with the agent off is не понял`() {
        val r = rig(diParsData(), agentResult = AgentResult.Disabled)
        val entry = r.say("какой запас хода")

        assertEquals(VoiceJournalEntry.Route.REFUSED, entry.route)
        assertEquals(VoiceJournalEntry.Outcome.NOT_UNDERSTOOD, entry.outcome)
        assertEquals(VoiceRefusal.VALUE_UNKNOWN, entry.refusal)
        assertEquals(VoiceUiState.NotUnderstood("какой запас хода"), r.controller.state.value)
    }

    private companion object {
        /** A snapshot read a moment ago: the age every rig has unless a test says otherwise. */
        const val FRESH_MS = 1_000L
    }
}
