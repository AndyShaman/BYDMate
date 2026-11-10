package com.bydmate.app.voice.online

import com.bydmate.app.voice.TtsEngine
import com.bydmate.app.voice.TtsGender
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.BlockingQueue

/** Online work still in flight when new speech starts or stop() is called: an agent reply queue is
 *  superseded by any new speech, consecutive single speaks (automation actions) are not, and stop()
 *  cancels all of it. */
class TtsRouterInFlightTest {

    // The router's work runs on virtual time: runCurrent() lets a launched job enter the backend's
    // delay, advanceUntilIdle() plays out every synthesis still scheduled (nothing in the router
    // loops on a timer). Errors from those coroutines are collected and must stay empty.
    private val scheduler = TestCoroutineScheduler()
    private val errors: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())
    private val scope = CoroutineScope(
        SupervisorJob() + StandardTestDispatcher(scheduler) + CoroutineExceptionHandler { _, t -> errors += t })

    @After
    fun tearDown() {
        scope.cancel()
        assertTrue("router coroutines failed: $errors", errors.isEmpty())
    }

    private class FakeTtsEngine : TtsEngine {
        val playPcmCalls: MutableList<FloatArray> = Collections.synchronizedList(mutableListOf())
        override fun isReady() = true
        override fun speak(text: String) = true
        override fun stop() = Unit
        override fun warmUp() = Unit
        override val speaking: StateFlow<Boolean> = MutableStateFlow(false)
        override fun playPcm(samples: FloatArray, sampleRate: Int): Boolean {
            playPcmCalls += samples
            return true
        }
        override fun playPcmStream(chunks: BlockingQueue<FloatArray>, sampleRate: Int) = true
        override fun startQueue(): TtsEngine.SpeechQueue? = null
    }

    private class FakeBackend(private val delayMs: Long) : OnlineTtsBackend {
        override val id: String = "gemini"
        override suspend fun synthesize(text: String, gender: TtsGender): TtsPcm {
            delay(delayMs)
            return TtsPcm(floatArrayOf(0.1f, 0.2f), 16_000)
        }
        override suspend fun configured(): Boolean = true
    }

    @Test
    fun `a new speak drops an agent reply queue still awaiting synthesis`() {
        val backend = FakeBackend(delayMs = 1_000)
        val delegate = FakeTtsEngine()
        val router = TtsRouter(delegate = delegate, backends = listOf(backend), selectedSource = { "gemini" }, scope = scope)
        router.startQueue()!!.enqueue("Старый ответ.")
        scheduler.runCurrent()
        router.speak("новый ответ")
        scheduler.advanceUntilIdle()
        assertEquals(1, delegate.playPcmCalls.size)
    }

    // An automation with two "speak" actions in a row: the second must not cancel the first
    // while it is still being synthesized.
    @Test
    fun `consecutive single speaks all play`() {
        val backend = FakeBackend(delayMs = 1_000)
        val delegate = FakeTtsEngine()
        val router = TtsRouter(delegate = delegate, backends = listOf(backend), selectedSource = { "gemini" }, scope = scope)
        router.speak("первое")
        scheduler.runCurrent()
        router.speak("второе")
        scheduler.advanceUntilIdle()
        assertEquals(2, delegate.playPcmCalls.size)
    }

    @Test
    fun `a new queue drops the previous queue's pending sentences`() {
        val backend = FakeBackend(delayMs = 1_000)
        val delegate = FakeTtsEngine()
        val router = TtsRouter(delegate = delegate, backends = listOf(backend), selectedSource = { "gemini" }, scope = scope)
        val old = router.startQueue()!!
        old.enqueue("Старое.")
        scheduler.runCurrent()
        router.startQueue()!!.enqueue("Новое.")
        scheduler.advanceUntilIdle()
        assertEquals(1, delegate.playPcmCalls.size)
        assertFalse(old.enqueue("Ещё старое.")) // superseded
    }

    // Two single speaks in flight: stop() must cancel both, not only the latest one.
    @Test
    fun `stop cancels every single speak still in flight`() {
        val backend = FakeBackend(delayMs = 1_000)
        val delegate = FakeTtsEngine()
        val router = TtsRouter(delegate = delegate, backends = listOf(backend), selectedSource = { "gemini" }, scope = scope)
        router.speak("первое")
        router.speak("второе")
        scheduler.runCurrent()
        router.stop()
        scheduler.advanceUntilIdle()
        assertTrue(delegate.playPcmCalls.isEmpty())
    }

    @Test
    fun `a new queue cancels every single speak still in flight`() {
        val backend = FakeBackend(delayMs = 1_000)
        val delegate = FakeTtsEngine()
        val router = TtsRouter(delegate = delegate, backends = listOf(backend), selectedSource = { "gemini" }, scope = scope)
        router.speak("первое")
        router.speak("второе")
        scheduler.runCurrent()
        router.startQueue()!!.enqueue("Ответ.")
        scheduler.advanceUntilIdle()
        assertEquals(1, delegate.playPcmCalls.size)
    }
}
