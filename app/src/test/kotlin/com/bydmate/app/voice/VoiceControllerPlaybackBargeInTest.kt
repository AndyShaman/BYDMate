package com.bydmate.app.voice

import android.content.Context
import com.bydmate.app.R
import com.bydmate.app.agent.AgentOrchestrator
import com.bydmate.app.agent.AgentResult
import com.bydmate.app.agent.AgentToolOutcome
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Name barge-in while the agent is speaking, through the real GigaAmAsrEngine control flow
 * (PCM -> VAD -> decode, one frame at a time) over fake VadHandle/RecognizerHandle. The test
 * plays the capture thread: [Rig.capture] marks a frame with the controller's capture-time
 * mark on a test clock and queues it, the session consumes the queue at its own pace. No
 * real time is involved; waits are barriers on the engine's per-frame progress.
 */
class VoiceControllerPlaybackBargeInTest {

    /** Speech is any non-zero frame; the first silent frame after speech closes the segment. */
    private class FakeVad : VadHandle {
        private var speech = false
        private val current = mutableListOf<Float>()
        private val segments = ArrayDeque<FloatArray>()
        // GigaAM asks empty() once more after the frame's last segment: at that point every
        // event of the frame has been handled by the controller.
        val framesDone = MutableStateFlow(0)
        override fun acceptWaveform(samples: FloatArray) {
            speech = samples.any { it != 0f }
            if (speech) current += samples.asList()
            else if (current.isNotEmpty()) { segments.addLast(current.toFloatArray()); current.clear() }
        }
        override fun isSpeechDetected(): Boolean = speech
        override fun empty(): Boolean = segments.isEmpty().also { if (it) framesDone.value++ }
        override fun front(): FloatArray = segments.first()
        override fun pop() { segments.removeFirst() }
        override fun close() = Unit
    }

    /** Decodes the phrase id the speech frames carry; [holdNextDecode] blocks one decode like a
     *  slow GigaAM pass, so the frames captured meanwhile pile up in the capture queue. */
    private class FakeRecognizer(private val phrases: List<String>) : RecognizerHandle {
        @Volatile private var hold: CountDownLatch? = null
        val decodeEntered = CountDownLatch(1)
        fun holdNextDecode(release: CountDownLatch) { hold = release }
        override fun decode(samples: FloatArray): String {
            hold?.let { release ->
                hold = null
                decodeEntered.countDown()
                release.await(WAIT_MS, TimeUnit.MILLISECONDS)
            }
            return phrases[(samples[0] * 32768f).roundToInt() - 1]
        }
        override fun close() = Unit
    }

    @Suppress("LongParameterList") // the controller plus every collaborator a test asserts on
    private inner class Rig(
        val controller: VoiceController,
        val vad: FakeVad,
        val recognizer: FakeRecognizer,
        val tts: TtsEngine,
        val earcon: VoiceEarcon,
        val dispatcher: ActionDispatcher,
        val journal: VoiceJournal,
        val orchestrator: AgentOrchestrator,
        private val mic: Channel<MicFrame<Any?>>,
        private val mark: () -> Any?,
    ) {
        private var captured = 0

        fun capture(pcm: ShortArray) {
            now += FRAME_MS
            mic.trySend(MicFrame(pcm, mark()))
            captured++
        }

        fun silence(frames: Int = 1) = repeat(frames) { capture(ShortArray(FRAME_SAMPLES)) }

        /** Speech frames carrying [text] and the silent frame that ends the segment. */
        fun utter(text: String) {
            val id = (phrases.indexOf(text).takeIf { it >= 0 } ?: phrases.size.also { phrases += text }) + 1
            repeat(3) { capture(ShortArray(FRAME_SAMPLES) { id.toShort() }) }
            silence()
        }

        /** Barrier: every frame captured so far went through VAD, decode and the event handler. */
        fun sync() = await(vad.framesDone) { it >= captured }

        fun close() {
            if (controller.listening.value) controller.onPttPressed()
            await(controller.listening) { !it }
            mic.close()
        }
    }

    private val phrases = mutableListOf<String>()
    private val rigs = mutableListOf<Rig>()
    @Volatile private var now = 1_000_000L
    @Volatile private var audible = false
    private val speaking = MutableStateFlow(false)
    // Answers shown in the orb: the agent's answer is shown after it went to TTS.
    private val answersShown = MutableStateFlow(0)

    @After fun tearDown() {
        rigs.forEach { it.close() }
    }

    private fun <T> await(flow: Flow<T>, condition: (T) -> Boolean): T =
        runBlocking { withTimeout(WAIT_MS) { flow.first(condition) } }

    private fun orchestrator(answer: AgentResult = AgentResult.Disabled) = mockk<AgentOrchestrator>().also {
        coEvery { it.ask(any(), any(), any()) } returns answer
        coEvery { it.noteAction(any()) } returns Unit
        coEvery { it.expectsFollowUp() } returns false
        coEvery { it.prewarm() } returns Unit
    }

    private fun rig(orchestrator: AgentOrchestrator = orchestrator(), ttsEnabled: Boolean = false, name: String = "Лео"): Rig {
        val vad = FakeVad()
        val recognizer = FakeRecognizer(phrases)
        val asr = GigaAmAsrEngine(
            mockk<GigaAmModelManager>(relaxed = true).also { every { it.isReady() } returns true },
            recognizerFactory = { recognizer },
            vadFactory = { vad },
        )
        val mic = Channel<MicFrame<Any?>>(Channel.UNLIMITED)
        val mark = CompletableDeferred<() -> Any?>()
        val audioCapture = mockk<AudioCapture>(relaxed = true)
        every { audioCapture.captureSession(any(), any<() -> Any?>()) } answers {
            mark.complete(secondArg())
            mic.consumeAsFlow()
        }
        val tts = mockk<TtsEngine>(relaxed = true)
        every { tts.speaking } returns speaking
        every { tts.audible() } answers { audible }
        every { tts.speak(any()) } returns true
        every { tts.startQueue() } returns null
        every { tts.stop() } answers { speaking.value = false; audible = false }
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
        controller.clock = { now }
        controller.updateListeningOverlay = {}
        controller.showListeningOverlay = {}
        controller.hideListeningOverlay = {}
        controller.showHeardHook = {}
        controller.showAnswerHook = { answersShown.value++ }
        controller.clearDialogHook = {}
        controller.onPttPressed()
        val markFn = runBlocking { withTimeout(WAIT_MS) { mark.await() } }
        clearMocks(tts, earcon, answers = false)
        return Rig(controller, vad, recognizer, tts, earcon, dispatcher, journal, orchestrator, mic, markFn)
            .also { rigs += it }
    }

    private fun VoiceJournal.bargeIns() = entries.value.filter { it.refusal == VoiceRefusal.BARGE_IN }

    /** Routes [question] outside playback and waits until the agent's answer went to TTS. */
    private fun Rig.askAgent(question: String) {
        utter(question)
        sync()
        await(answersShown) { it >= 1 }
    }

    // --- Review finding 1: the playback window is judged at capture time ---

    @Test fun `agent voice captured during playback stays overlapped when processed after playback and grace ended`() {
        val r = rig()
        val release = CountDownLatch(1)
        r.recognizer.holdNextDecode(release)
        r.utter("") // noise before playback, decodes to nothing; its decode blocks the consumer
        assertTrue(r.recognizer.decodeEntered.await(WAIT_MS, TimeUnit.MILLISECONDS))

        audible = true
        r.utter("закрой окна") // the agent's voice, captured while it is audible
        audible = false
        r.silence(10) // a second past the end of playback: well outside the 500 ms echo grace
        release.countDown()
        r.sync()

        assertEquals(1, r.controller.droppedDuringPlaybackForTest())
        assertEquals(null, r.controller.routingJobForTest())
        assertTrue(r.journal.entries.value.isEmpty())
        coVerify(exactly = 0) { r.dispatcher.dispatch(any<ActionDef>(), any()) }
        coVerify(exactly = 0) { r.orchestrator.ask(any(), any(), any()) }
    }

    @Test fun `speech captured inside the echo grace stays overlapped however late it is decoded`() {
        val r = rig()
        val release = CountDownLatch(1)
        r.recognizer.holdNextDecode(release)
        r.utter("") // noise, decodes to nothing
        assertTrue(r.recognizer.decodeEntered.await(WAIT_MS, TimeUnit.MILLISECONDS))

        audible = true
        r.silence() // the last audible frame
        audible = false
        r.utter("закрой окна") // starts 100 ms after the audio, inside TTS_ECHO_GRACE_MS
        r.silence(10)
        release.countDown()
        r.sync()

        assertEquals(1, r.controller.droppedDuringPlaybackForTest())
        coVerify(exactly = 0) { r.dispatcher.dispatch(any<ActionDef>(), any()) }
    }

    // --- Name barge-in during playback ---

    @Test fun `name while the last sentences play and no ask is in flight barges in without routing`() {
        val r = rig(name = "Лёша")

        audible = true
        r.utter("леш посмотри") // how GigaAM renders "Лёша, посмотри" in field logs
        r.sync()

        assertEquals(1, r.journal.bargeIns().size)
        assertEquals(VoiceJournalEntry.Route.AGENT, r.journal.bargeIns().single().route)
        verify(exactly = 1) { r.tts.stop() }
        verify(exactly = 1) { r.earcon.ok() }
        assertEquals(VoiceUiState.Listening, r.controller.state.value)
        assertEquals(0, r.controller.droppedDuringPlaybackForTest())
        coVerify(exactly = 0) { r.orchestrator.ask(any(), any(), any()) }
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

        r.utter("расскажи про заряд") // before any playback: routed
        runBlocking { withTimeout(WAIT_MS) { askStarted.await() } }
        audible = true
        r.utter("Лео")
        r.sync()

        runBlocking { withTimeout(WAIT_MS) { askCancelled.await() } }
        await(r.journal.entries) { list -> list.any { it.refusal == VoiceRefusal.BARGE_IN && it.outcome == VoiceJournalEntry.Outcome.ERROR } }
        verify(atLeast = 1) { r.tts.stop() }
        assertTrue(r.journal.bargeIns().any { it.outcome == VoiceJournalEntry.Outcome.OK && it.transcript == "Лео" })
        coVerify(exactly = 1) { orchestrator.ask(any(), any(), any()) }
    }

    @Test fun `a phrase without the name at its start during playback is dropped, not routed`() {
        val r = rig()

        audible = true
        r.utter("закрой окна")
        // A name later in the phrase does not count: the segment began with the agent's voice.
        r.utter("погода хорошая Лео")
        r.sync()

        assertEquals(2, r.controller.droppedDuringPlaybackForTest())
        verify(exactly = 0) { r.tts.stop() }
        assertTrue(r.journal.entries.value.isEmpty())
        coVerify(exactly = 0) { r.dispatcher.dispatch(any<ActionDef>(), any()) }
        coVerify(exactly = 0) { r.orchestrator.ask(any(), any(), any()) }
    }

    // --- Review finding 2: the self-name guard follows the reply's playback, not its enqueue ---

    @Test fun `echo of the agent's own name is ignored while its reply is still playing long after enqueue`() {
        val r = rig(orchestrator(AgentResult.Answer("Лео на связи.")), ttsEnabled = true)
        r.askAgent("как тебя зовут")
        verify { r.tts.speak("Лео на связи.") }

        // The reply is still going 21 s later (earlier sentences, synthesis) -- past the old 20 s guard.
        speaking.value = true
        r.silence(210)
        audible = true
        r.utter("Лео на связи") // its own voice, echoed back through the mic
        r.sync()

        assertEquals(1, r.controller.droppedDuringPlaybackForTest())
        verify(exactly = 0) { r.tts.stop() }
        assertTrue(r.journal.bargeIns().isEmpty())
    }

    @Test fun `echo right after the named reply ended is ignored, a name once the guard is released barges in`() {
        val r = rig(orchestrator(AgentResult.Answer("Лео на связи.")), ttsEnabled = true)
        r.askAgent("как тебя зовут")

        speaking.value = true
        audible = true
        r.silence(3) // the named reply plays
        speaking.value = false
        audible = false
        r.utter("Лео на связи") // the echo tail, right after the audio ended
        r.sync()
        assertEquals(1, r.controller.droppedDuringPlaybackForTest())
        assertTrue(r.journal.bargeIns().isEmpty())

        r.silence(60) // past the start grace of the reply and the guard's own grace
        audible = true // a later reply without the name
        r.utter("Лео")
        r.sync()

        assertEquals(1, r.journal.bargeIns().size)
        verify(exactly = 1) { r.tts.stop() }
    }

    @Test fun `a named reply that never played does not block a real name later`() {
        val r = rig(orchestrator(AgentResult.Answer("Лео на связи.")), ttsEnabled = true)
        r.askAgent("как тебя зовут") // speak() accepted it, but synthesis failed: never audible

        r.silence(60)
        audible = true
        r.utter("Лео")
        r.sync()

        assertEquals(1, r.journal.bargeIns().size)
        assertEquals(0, r.controller.droppedDuringPlaybackForTest())
    }

    @Test fun `after a name barge-in the next reply without the name is interruptible by the name`() {
        val askStarted = CompletableDeferred<Unit>()
        val orchestrator = orchestrator()
        var asks = 0
        coEvery { orchestrator.ask(any(), any(), any()) } coAnswers {
            val onSentence = thirdArg<((String) -> Unit)?>()!!
            if (++asks == 1) {
                onSentence("Лео на связи.") // arms the guard, then a slow tool round
                askStarted.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
            onSentence("Пять градусов.")
            AgentResult.Answer("Пять градусов.")
        }
        val r = rig(orchestrator, ttsEnabled = true)
        every { r.tts.startQueue() } returns mockk<TtsEngine.SpeechQueue>(relaxed = true).also {
            every { it.enqueue(any()) } returns true
        }

        r.utter("как тебя зовут")
        runBlocking { withTimeout(WAIT_MS) { askStarted.await() } }
        val firstTurn = r.controller.routingJobForTest()!!
        r.silence(10) // the named sentence is still synthesizing: nothing audible
        r.utter("Лео") // barges in during the tool round
        r.sync()
        runBlocking { withTimeout(WAIT_MS) { firstTurn.join() } }
        assertEquals(1, r.journal.bargeIns().count { it.outcome == VoiceJournalEntry.Outcome.OK })

        r.utter("а на улице") // a new question, its reply does not name the agent
        await(r.journal.entries) { list -> list.any { it.answer == "Пять градусов." } }
        speaking.value = true
        audible = true
        r.utter("Лео")
        r.sync()

        assertEquals(0, r.controller.droppedDuringPlaybackForTest())
        assertEquals(2, r.journal.bargeIns().count { it.outcome == VoiceJournalEntry.Outcome.OK })
    }

    // --- Review finding 3: a long answer does not eat the driver's waiting time ---

    @Test fun `after an answer longer than the silence timeout the driver gets the full wait`() {
        val r = rig()

        audible = true
        r.silence(400) // 40 s of the agent talking quietly: the VAD hears silence
        audible = false
        r.silence(4) // inside the 500 ms echo grace
        r.silence(299) // 29.9 s of real silence after the answer
        r.sync()
        assertTrue(r.controller.listening.value)

        r.silence()
        await(r.controller.listening) { !it }
    }

    @Test fun `neither a long thinking phase nor a long answer eats the driver's wait after the turn`() {
        val askStarted = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<AgentResult>()
        val orchestrator = orchestrator()
        coEvery { orchestrator.ask(any(), any(), any()) } coAnswers {
            askStarted.complete(Unit)
            answer.await()
        }
        val r = rig(orchestrator)

        r.utter("расскажи про заряд")
        runBlocking { withTimeout(WAIT_MS) { askStarted.await() } }
        val turn = r.controller.routingJobForTest()!!
        r.silence(250) // 25 s of the agent thinking
        r.sync()
        answer.complete(AgentResult.Answer("Заряд восемьдесят процентов."))
        runBlocking { withTimeout(WAIT_MS) { turn.join() } }

        audible = true
        r.silence(310) // 31 s of the agent talking quietly: the VAD hears silence
        audible = false
        r.silence(4) // inside the 500 ms echo grace
        r.silence(299) // 29.9 s of real silence after the answer
        r.sync()
        assertTrue(r.controller.listening.value)

        r.silence()
        await(r.controller.listening) { !it }
    }

    // --- Review finding 4: a barge-in cancels the play_music auto-close ---

    @Test fun `name barge-in during a play_music reply keeps the session open`() {
        val r = rig(orchestrator(AgentResult.Answer("Включаю.", listOf(AgentToolOutcome("play_music", true)))), ttsEnabled = true)
        every { r.tts.speak(any()) } answers { speaking.value = true; true }

        r.askAgent("поставь что-нибудь")
        // Both the dialog clear and the play_music auto-close now wait for the reply to end.
        await(speaking.subscriptionCount) { it >= 2 }
        audible = true
        r.utter("Лео")
        r.sync()
        assertEquals(1, r.journal.bargeIns().size)

        await(speaking.subscriptionCount) { it == 0 }
        r.silence()
        r.sync() // a closed session would never process this frame
        assertTrue(r.controller.listening.value)
    }

    private companion object {
        const val WAIT_MS = 5_000L
        const val FRAME_SAMPLES = 1600 // 100 ms at 16 kHz, like AudioCapture's reads
        const val FRAME_MS = 100L
    }
}
