package com.bydmate.app.voice.online

import com.bydmate.app.voice.TtsEngine
import com.bydmate.app.voice.TtsGender
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsRouterTest {

    /** Records every call so tests can assert on what the router delegated. */
    private class FakeTtsEngine(private val ready: Boolean = true) : TtsEngine {
        val speakCalls = mutableListOf<String>()
        val playPcmCalls = mutableListOf<Pair<FloatArray, Int>>()
        val queueEnqueued = mutableListOf<String>()
        var queueFinished = false
        var stopCalls = 0
        var playPcmResult = true

        override fun isReady() = ready
        override fun speak(text: String): Boolean {
            speakCalls += text
            return true
        }
        override fun stop() { stopCalls++ }
        override val speaking: StateFlow<Boolean> = MutableStateFlow(false)
        override fun playPcm(samples: FloatArray, sampleRate: Int): Boolean {
            playPcmCalls += samples to sampleRate
            return playPcmResult
        }
        override fun startQueue(): TtsEngine.SpeechQueue = object : TtsEngine.SpeechQueue {
            override fun enqueue(text: String): Boolean { queueEnqueued += text; return true }
            override fun finish() { queueFinished = true }
        }
    }

    private class FakeBackend(
        override val id: String = "gemini",
        private val delayMs: Long = 0,
        private val fail: Boolean = false,
        private val configuredValue: Boolean = true,
    ) : OnlineTtsBackend {
        override suspend fun synthesize(text: String, gender: TtsGender): TtsPcm {
            if (delayMs > 0) delay(delayMs)
            if (fail) throw RuntimeException("synthesis failed")
            return TtsPcm(floatArrayOf(0.1f, 0.2f), 16_000)
        }
        override suspend fun configured(): Boolean = configuredValue
    }

    /** Fails only from the [failFrom]-th call onward (1-indexed) -- lets a queue test drive
     *  "first sentence succeeds, second fails". */
    private class FlakyBackend(private val failFrom: Int) : OnlineTtsBackend {
        override val id: String = "gemini"
        private var calls = 0
        override suspend fun synthesize(text: String, gender: TtsGender): TtsPcm {
            calls++
            if (calls >= failFrom) throw RuntimeException("synthesis failed")
            return TtsPcm(floatArrayOf(0.1f), 16_000)
        }
        override suspend fun configured(): Boolean = true
    }

    /** Polls [condition] on a real clock -- the router dispatches online work onto a real
     *  background dispatcher (Dispatchers.IO by default), so tests wait for it the same way
     *  SherpaTtsEngineTest waits for its own worker thread, instead of a virtual-time TestScope
     *  (which would install a global uncaught-coroutine-exception collector that -- unrelated to
     *  this router -- also snags a pre-existing leaked exception from VoiceControllerTest's
     *  scheduleClear coroutine and fails whichever test happens to run next). */
    private fun awaitTrue(deadlineMs: Long = 3_000, pollMs: Long = 20L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(pollMs)
        }
    }

    // --- speak(): offline source delegates straight through ---

    @Test
    fun `speak on offline source delegates directly, no backend involved`() {
        val delegate = FakeTtsEngine()
        val router = TtsRouter(delegate = delegate, selectedSource = { TtsRouter.OFFLINE })
        assertTrue(router.speak("привет"))
        assertEquals(listOf("привет"), delegate.speakCalls)
    }

    @Test
    fun `speak is a no-op on blank text`() {
        val delegate = FakeTtsEngine()
        val router = TtsRouter(delegate = delegate, selectedSource = { TtsRouter.OFFLINE })
        assertFalse(router.speak("   "))
        assertTrue(delegate.speakCalls.isEmpty())
    }

    // --- speak(): online source success plays PCM through the delegate ---

    @Test
    fun `speak on online source plays the synthesized PCM through the delegate`() {
        val backend = FakeBackend()
        val delegate = FakeTtsEngine()
        val router = TtsRouter(delegate = delegate, backends = listOf(backend), selectedSource = { "gemini" })
        assertTrue(router.speak("hello"))
        awaitTrue { delegate.playPcmCalls.isNotEmpty() }
        assertEquals(1, delegate.playPcmCalls.size)
        assertTrue(delegate.speakCalls.isEmpty()) // no offline fallback needed
    }

    // --- speak(): online failure => reply stays silent, no fallback ---

    @Test
    fun `speak on online source stays silent after synth timeout (no fallback)`() {
        val backend = FakeBackend(delayMs = 60_000)
        val delegate = FakeTtsEngine()
        val router = TtsRouter(
            delegate = delegate,
            backends = listOf(backend),
            selectedSource = { "gemini" },
            synthTimeoutMs = 100,
        )
        assertTrue(router.speak("привет"))
        // synthTimeoutMs fires long before the backend responds; coroutine resolves to null.
        Thread.sleep(600)
        assertTrue(delegate.playPcmCalls.isEmpty())
        assertTrue(delegate.speakCalls.isEmpty())    // no offline fallback
        assertTrue(delegate.queueEnqueued.isEmpty())
    }

    @Test
    fun `speak on online source stays silent on backend exception (no fallback)`() {
        val backend = FakeBackend(fail = true)
        val delegate = FakeTtsEngine()
        val router = TtsRouter(delegate = delegate, backends = listOf(backend), selectedSource = { "gemini" })
        router.speak("привет")
        Thread.sleep(200) // exception is thrown instantly; let the coroutine finish
        assertTrue(delegate.playPcmCalls.isEmpty())
        assertTrue(delegate.speakCalls.isEmpty())
    }

    @Test
    fun `speak on online source stays silent when playPcm reports it did not play (no fallback)`() {
        val backend = FakeBackend()
        val delegate = FakeTtsEngine().apply { playPcmResult = false }
        val router = TtsRouter(delegate = delegate, backends = listOf(backend), selectedSource = { "gemini" })
        router.speak("привет")
        awaitTrue { delegate.playPcmCalls.isNotEmpty() }
        assertEquals(1, delegate.playPcmCalls.size)
        assertTrue(delegate.speakCalls.isEmpty()) // no fallback speak after playPcm=false
    }

    @Test
    fun `unresolved online source id falls back to offline speak directly`() {
        val delegate = FakeTtsEngine()
        val router = TtsRouter(delegate = delegate, backends = emptyList(), selectedSource = { "gemini" })
        router.speak("привет")
        assertEquals(listOf("привет"), delegate.speakCalls) // no backend matches -> same call, no async hop
    }

    // --- startQueue(): sequential synthesis, first failure switches the whole remainder ---

    @Test
    fun `queue plays every sentence online when all succeed`() {
        val backend = FakeBackend()
        val delegate = FakeTtsEngine()
        val router = TtsRouter(delegate = delegate, backends = listOf(backend), selectedSource = { "gemini" })
        val queue = router.startQueue()!!
        assertTrue(queue.enqueue("Первое."))
        assertTrue(queue.enqueue("Второе."))
        queue.finish()
        awaitTrue { delegate.playPcmCalls.size == 2 }
        assertEquals(2, delegate.playPcmCalls.size)
        assertTrue(delegate.queueEnqueued.isEmpty()) // offline queue never started
    }

    @Test
    fun `queue silences the remainder when the first sentence fails (no fallback)`() {
        val backend = FlakyBackend(failFrom = 1) // all sentences fail from the very first
        val delegate = FakeTtsEngine()
        val router = TtsRouter(delegate = delegate, backends = listOf(backend), selectedSource = { "gemini" })
        val queue = router.startQueue()!!
        queue.enqueue("Первое.")
        queue.enqueue("Второе.")
        queue.enqueue("Третье.")
        queue.finish()
        Thread.sleep(500) // all three fail instantly; let both coroutines finish
        assertTrue(delegate.playPcmCalls.isEmpty())
        assertTrue(delegate.queueEnqueued.isEmpty()) // no offline fallback
        assertEquals(0, delegate.speakCalls.size)
    }

    @Test
    fun `queue on offline source delegates straight to the offline queue`() {
        val delegate = FakeTtsEngine()
        val router = TtsRouter(delegate = delegate, selectedSource = { TtsRouter.OFFLINE })
        val queue = router.startQueue()!!
        queue.enqueue("Текст.")
        queue.finish()
        assertEquals(listOf("Текст."), delegate.queueEnqueued)
        assertTrue(delegate.queueFinished)
    }

    // --- stop(): barge-in cancels in-flight online work, not just the offline delegate ---

    @Test
    fun `stop cancels an in-flight online speak before it can play`() {
        val backend = FakeBackend(delayMs = 1_000)
        val delegate = FakeTtsEngine()
        val router = TtsRouter(delegate = delegate, backends = listOf(backend), selectedSource = { "gemini" })
        router.speak("привет")
        Thread.sleep(50) // let the coroutine actually enter backend.synthesize()'s delay
        router.stop()
        // If cancellation failed, the 1s delay would elapse well within this window and playPcm
        // would fire; it must not, because stop() tore the coroutine down at ~50ms.
        Thread.sleep(1_500)
        assertTrue(delegate.playPcmCalls.isEmpty())
        // A structural cancellation must not fall through to the offline voice either -- that
        // would speak the interrupted reply right after the user tried to interrupt it.
        assertTrue(delegate.speakCalls.isEmpty())
        assertTrue(delegate.queueEnqueued.isEmpty())
        assertEquals(1, delegate.stopCalls)
    }

    @Test
    fun `stop cancels an in-flight online queue before the next sentence plays`() {
        val backend = FakeBackend(delayMs = 1_000)
        val delegate = FakeTtsEngine()
        val router = TtsRouter(delegate = delegate, backends = listOf(backend), selectedSource = { "gemini" })
        val queue = router.startQueue()!!
        queue.enqueue("Первое.")
        Thread.sleep(50)
        router.stop()
        Thread.sleep(1_500)
        assertTrue(delegate.playPcmCalls.isEmpty())
        // A structural cancellation must not fall through to the offline voice either -- the
        // interrupted sentence must not resurface via a speak() call or a freshly started
        // offline queue.
        assertTrue(delegate.speakCalls.isEmpty())
        assertTrue(delegate.queueEnqueued.isEmpty())
        assertFalse(queue.enqueue("Второе.")) // superseded
    }

    // --- queue prefetch: sentence N+1 is synthesized while sentence N plays ---

    @Test
    fun `queue prefetches next sentence and plays both in strict enqueue order`() {
        // Distinct samples per text let us verify that playback order matches enqueue order.
        val backend = object : OnlineTtsBackend {
            override val id = "gemini"
            override suspend fun synthesize(text: String, gender: TtsGender): TtsPcm {
                delay(100) // simulate network latency
                return if (text == "Первое.") TtsPcm(floatArrayOf(1.0f), 16_000)
                else TtsPcm(floatArrayOf(2.0f), 16_000)
            }
            override suspend fun configured() = true
        }
        val delegate = FakeTtsEngine()
        val router = TtsRouter(delegate = delegate, backends = listOf(backend), selectedSource = { "gemini" })
        val queue = router.startQueue()!!
        queue.enqueue("Первое.")
        queue.enqueue("Второе.")
        queue.finish()
        awaitTrue(deadlineMs = 4_000) { delegate.playPcmCalls.size == 2 }
        assertEquals(2, delegate.playPcmCalls.size)
        // Playback order strictly matches enqueue order even with prefetch parallelism.
        assertEquals(1.0f, delegate.playPcmCalls[0].first[0])
        assertEquals(2.0f, delegate.playPcmCalls[1].first[0])
    }

    // --- isReady(): online ready requires only backend.configured(), no delegate dependency ---

    @Test
    fun `isReady is true for online source only when backend is configured and offline delegate is ready`() {
        val backend = FakeBackend(configuredValue = true)
        val delegate = FakeTtsEngine(ready = true)
        val router = TtsRouter(delegate = delegate, backends = listOf(backend), selectedSource = { "gemini" })
        assertTrue(router.isReady())
    }

    @Test
    fun `isReady is false for online source when backend is not configured`() {
        val backend = FakeBackend(configuredValue = false)
        val delegate = FakeTtsEngine(ready = true)
        val router = TtsRouter(delegate = delegate, backends = listOf(backend), selectedSource = { "gemini" })
        assertFalse(router.isReady())
    }

    @Test
    fun `isReady for online source requires only backend configured() regardless of delegate readiness`() {
        // No offline fallback => the local model being absent is irrelevant for online.
        val backend = FakeBackend(configuredValue = true)
        val delegate = FakeTtsEngine(ready = false)
        val router = TtsRouter(delegate = delegate, backends = listOf(backend), selectedSource = { "gemini" })
        assertTrue(router.isReady()) // online: backend.configured() alone decides
    }

    @Test
    fun `isReady on offline source follows the delegate directly`() {
        val delegate = FakeTtsEngine(ready = false)
        val router = TtsRouter(delegate = delegate, selectedSource = { TtsRouter.OFFLINE })
        assertFalse(router.isReady())
    }

    // --- speakOffline(): always plays through the offline delegate, ignoring the online source ---

    @Test
    fun `speakOffline with online source selected invokes delegate speak and never synthesizes online`() {
        var synthesizeCalled = false
        val backend = object : OnlineTtsBackend {
            override val id: String = "gemini"
            override suspend fun synthesize(text: String, gender: TtsGender): TtsPcm {
                synthesizeCalled = true
                return TtsPcm(floatArrayOf(0.1f), 16_000)
            }
            override suspend fun configured(): Boolean = true
        }
        val delegate = FakeTtsEngine()
        val router = TtsRouter(delegate = delegate, backends = listOf(backend), selectedSource = { "gemini" })
        val result = router.speakOffline("тест превью")
        assertTrue(result)
        assertEquals(listOf("тест превью"), delegate.speakCalls)
        assertFalse("online backend synthesize must NOT be called by speakOffline", synthesizeCalled)
    }

    // --- pure delegation ---

    @Test
    fun `audible and reload delegate to the offline engine`() {
        val delegate = FakeTtsEngine()
        val router = TtsRouter(delegate = delegate, selectedSource = { TtsRouter.OFFLINE })
        router.reload()
        assertFalse(router.audible())
    }

    @Test
    fun `playPcm delegates to the offline engine`() {
        val delegate = FakeTtsEngine()
        val router = TtsRouter(delegate = delegate, selectedSource = { TtsRouter.OFFLINE })
        val samples = floatArrayOf(0.1f, 0.2f)
        assertTrue(router.playPcm(samples, 16_000))
        assertEquals(listOf(samples to 16_000), delegate.playPcmCalls)
    }
}
