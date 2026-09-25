package com.bydmate.app.voice.online

import com.bydmate.app.voice.TtsEngine
import com.bydmate.app.voice.TtsGender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TtsRouterTest {

    /** Records every call so tests can assert on what the router delegated. */
    private class FakeTtsEngine(private val ready: Boolean = true) : TtsEngine {
        val speakCalls = mutableListOf<String>()
        val playPcmCalls: MutableList<Pair<FloatArray, Int>> = Collections.synchronizedList(mutableListOf())
        val playStreamCalls: MutableList<Pair<List<Float>, Int>> = Collections.synchronizedList(mutableListOf())
        val queueEnqueued = mutableListOf<String>()
        var queueFinished = false
        var stopCalls = 0
        var warmUpCalls = 0
        var playPcmResult = true
        /** Released once playPcmStream has taken its first chunk: the player is really playing. */
        val streamChunkTaken = CountDownLatch(1)

        override fun isReady() = ready
        override fun speak(text: String): Boolean {
            speakCalls += text
            return true
        }
        override fun stop() { stopCalls++ }
        override fun warmUp() { warmUpCalls++ }
        override val speaking: StateFlow<Boolean> = MutableStateFlow(false)
        override fun playPcm(samples: FloatArray, sampleRate: Int): Boolean {
            playPcmCalls += samples to sampleRate
            return playPcmResult
        }
        // Takes chunks until the end marker (or a 5 s gap), like the engine's pump.
        override fun playPcmStream(chunks: BlockingQueue<FloatArray>, sampleRate: Int): Boolean {
            val got = mutableListOf<Float>()
            var chunk = chunks.poll(5, TimeUnit.SECONDS)
            while (chunk != null && chunk.isNotEmpty()) {
                got += chunk.toList()
                streamChunkTaken.countDown()
                chunk = chunks.poll(5, TimeUnit.SECONDS)
            }
            playStreamCalls += got to sampleRate
            return true
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

    /** Counts synth and prewarm calls; synthesis always succeeds. */
    private class CountingBackend(override val id: String = "minimax") : OnlineTtsBackend {
        val synthesized = java.util.Collections.synchronizedList(mutableListOf<Pair<String, TtsGender>>())
        @Volatile var prewarmCalls = 0
        override suspend fun synthesize(text: String, gender: TtsGender): TtsPcm {
            synthesized += text to gender
            return TtsPcm(floatArrayOf(0.1f, 0.2f), 24_000)
        }
        override suspend fun configured(): Boolean = true
        override suspend fun prewarm() { prewarmCalls++ }
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

    /** Joins everything the router launched on [scope] -- proof the work finished, so negative
     *  assertions after it cannot miss a late action. */
    private fun awaitIdle(scope: CoroutineScope) = runBlocking {
        withTimeout(5_000) { scope.coroutineContext.job.children.toList().joinAll() }
    }

    private fun testScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
    fun `warmUp skips the offline engine when an online source is selected`() {
        val delegate = FakeTtsEngine()
        val router = TtsRouter(
            delegate = delegate,
            backends = listOf(FakeBackend()),
            selectedSource = { "gemini" },
        )
        router.warmUp()
        assertEquals(0, delegate.warmUpCalls)
    }

    @Test
    fun `warmUp reaches the offline engine when offline is selected`() {
        val delegate = FakeTtsEngine()
        val router = TtsRouter(delegate = delegate, selectedSource = { TtsRouter.OFFLINE })
        router.warmUp()
        assertEquals(1, delegate.warmUpCalls)
    }

    // --- short-phrase cache (voice speed wave 1b) ---

    @Test
    fun `short phrase is synthesized once and replayed from the cache`() {
        val backend = CountingBackend()
        val delegate = FakeTtsEngine()
        val router = TtsRouter(delegate = delegate, backends = listOf(backend), selectedSource = { "minimax" })
        router.speak("Готово.")
        awaitTrue { delegate.playPcmCalls.size == 1 }
        router.speak("Готово.")
        awaitTrue { delegate.playPcmCalls.size == 2 }
        assertEquals(2, delegate.playPcmCalls.size)
        assertEquals(1, backend.synthesized.size)
    }

    @Test
    fun `long reply is never cached`() {
        val backend = CountingBackend()
        val delegate = FakeTtsEngine()
        val router = TtsRouter(delegate = delegate, backends = listOf(backend), selectedSource = { "minimax" })
        val reply = "Заряд восемьдесят процентов, запаса хватит на триста километров."
        router.speak(reply)
        awaitTrue { delegate.playPcmCalls.size == 1 }
        router.speak(reply)
        awaitTrue { delegate.playPcmCalls.size == 2 }
        assertEquals(2, delegate.playPcmCalls.size)
        assertEquals(2, backend.synthesized.size)
    }

    @Test
    fun `gender switch misses the cache so the old voice is never replayed`() {
        val backend = CountingBackend()
        val delegate = FakeTtsEngine()
        var gender = TtsGender.MALE
        val router = TtsRouter(
            delegate = delegate, backends = listOf(backend),
            selectedSource = { "minimax" }, selectedGender = { gender },
        )
        router.speak("Готово.")
        awaitTrue { delegate.playPcmCalls.size == 1 }
        gender = TtsGender.FEMALE
        router.speak("Готово.")
        awaitTrue { delegate.playPcmCalls.size == 2 }
        assertEquals(2, delegate.playPcmCalls.size)
        assertEquals(listOf("Готово." to TtsGender.MALE, "Готово." to TtsGender.FEMALE), backend.synthesized.toList())
    }

    @Test
    fun `warmUp on an online source precaches the phrases, later speech uses them`() {
        val backend = CountingBackend()
        val delegate = FakeTtsEngine()
        val router = TtsRouter(
            delegate = delegate, backends = listOf(backend), selectedSource = { "minimax" },
            precachePhrases = { listOf("Есть.", "Выполнено.") },
        )
        router.warmUp()
        // The cache write lands after the backend returns: wait for the cache, not the backend.
        awaitTrue { router.phraseCacheSizeForTest() == 2 }
        assertEquals(2, router.phraseCacheSizeForTest())
        router.speak("Выполнено.")
        awaitTrue { delegate.playPcmCalls.size == 1 }
        assertEquals(1, delegate.playPcmCalls.size)
        assertEquals(2, backend.synthesized.size)
        assertEquals(0, delegate.warmUpCalls)
    }

    @Test
    fun `prewarmNetwork reaches the online backend only`() {
        val backend = CountingBackend()
        TtsRouter(delegate = FakeTtsEngine(), backends = listOf(backend), selectedSource = { "minimax" })
            .prewarmNetwork()
        awaitTrue { backend.prewarmCalls == 1 }
        assertEquals(1, backend.prewarmCalls)

        val offline = CountingBackend()
        TtsRouter(delegate = FakeTtsEngine(), backends = listOf(offline), selectedSource = { TtsRouter.OFFLINE })
            .prewarmNetwork()
        Thread.sleep(100)
        assertEquals(0, offline.prewarmCalls)
    }

    @Test
    fun `playPcm delegates to the offline engine`() {
        val delegate = FakeTtsEngine()
        val router = TtsRouter(delegate = delegate, selectedSource = { TtsRouter.OFFLINE })
        val samples = floatArrayOf(0.1f, 0.2f)
        assertTrue(router.playPcm(samples, 16_000))
        assertEquals(listOf(samples to 16_000), delegate.playPcmCalls)
    }

    @Test
    fun `empty synthesized audio is never cached`() {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val backend = object : OnlineTtsBackend {
            override val id = "minimax"
            override suspend fun synthesize(text: String, gender: TtsGender): TtsPcm {
                calls += text
                return TtsPcm(FloatArray(0), 24_000)
            }
            override suspend fun configured() = true
        }
        val delegate = FakeTtsEngine()
        val router = TtsRouter(delegate = delegate, backends = listOf(backend), selectedSource = { "minimax" })
        router.speak("Готово.")
        awaitTrue { delegate.playPcmCalls.size == 1 }
        router.speak("Готово.")
        awaitTrue { calls.size == 2 }
        assertEquals(2, calls.size)
        assertEquals(0, router.phraseCacheSizeForTest())
    }

    @Test
    fun `precache stops at the first failed phrase`() {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val backend = object : OnlineTtsBackend {
            override val id = "minimax"
            override suspend fun synthesize(text: String, gender: TtsGender): TtsPcm {
                calls += text
                if (text == "Есть.") throw IOException("429")
                return TtsPcm(floatArrayOf(0.1f), 24_000)
            }
            override suspend fun configured() = true
        }
        val scope = testScope()
        val router = TtsRouter(
            delegate = FakeTtsEngine(), backends = listOf(backend), selectedSource = { "minimax" },
            precachePhrases = { listOf("Готово.", "Есть.", "Выполнено.") }, scope = scope,
        )
        router.warmUp()
        awaitIdle(scope)
        assertEquals(listOf("Готово.", "Есть."), calls.toList())
        assertEquals(1, router.phraseCacheSizeForTest())
    }

    @Test
    fun `precache stops at the first phrase that comes back without audio`() {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val backend = object : OnlineTtsBackend {
            override val id = "minimax"
            override suspend fun synthesize(text: String, gender: TtsGender): TtsPcm {
                calls += text
                return TtsPcm(if (text == "Есть.") FloatArray(0) else floatArrayOf(0.1f), 24_000)
            }
            override suspend fun configured() = true
        }
        val scope = testScope()
        val router = TtsRouter(
            delegate = FakeTtsEngine(), backends = listOf(backend), selectedSource = { "minimax" },
            precachePhrases = { listOf("Готово.", "Есть.", "Выполнено.") }, scope = scope,
        )
        router.warmUp()
        awaitIdle(scope)
        assertEquals(listOf("Готово.", "Есть."), calls.toList())
        assertEquals(1, router.phraseCacheSizeForTest())
    }

    // --- streamed reply queue (voice speed wave 2) ---

    /** Streams scripted chunks per text at 24 kHz. Texts in [failBefore] throw before any chunk,
     *  in [failAfterFirst] right after the first one, in [hang] after the first one wait until
     *  cancelled. synthesize() (the whole-sentence path) returns [9f]. */
    private class StreamingBackend(
        private val chunks: Map<String, List<FloatArray>>,
        private val failBefore: Set<String> = emptySet(),
        private val failAfterFirst: Set<String> = emptySet(),
        private val hang: Set<String> = emptySet(),
    ) : OnlineTtsBackend {
        override val id = "minimax"
        val streamed: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val synthesized: MutableList<String> = Collections.synchronizedList(mutableListOf())
        @Volatile var cancelled = false

        override suspend fun synthesize(text: String, gender: TtsGender): TtsPcm {
            synthesized += text
            return TtsPcm(floatArrayOf(9f), 24_000)
        }
        override suspend fun configured() = true
        override suspend fun streamSampleRate() = 24_000
        override suspend fun synthesizeStream(text: String, gender: TtsGender, onChunk: (FloatArray) -> Unit) {
            streamed += text
            if (text in failBefore) throw IOException("stream refused")
            val list = chunks[text].orEmpty()
            list.firstOrNull()?.let(onChunk)
            if (text in failAfterFirst) throw IOException("stream broke")
            if (text in hang) {
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
            list.drop(1).forEach(onChunk)
        }
    }

    private fun f(vararg v: Float) = v

    @Test
    fun `streamed queue plays each sentence via playPcmStream with chunks in order`() {
        val backend = StreamingBackend(mapOf("Первое." to listOf(f(1f), f(2f)), "Второе." to listOf(f(3f))))
        val delegate = FakeTtsEngine()
        val router = TtsRouter(delegate = delegate, backends = listOf(backend), selectedSource = { "minimax" })
        val queue = router.startQueue()!!
        queue.enqueue("Первое.")
        queue.enqueue("Второе.")
        queue.finish()
        awaitTrue { delegate.playStreamCalls.size == 2 }
        assertEquals(listOf(listOf(1f, 2f) to 24_000, listOf(3f) to 24_000), delegate.playStreamCalls.toList())
        assertTrue(delegate.playPcmCalls.isEmpty())
        assertTrue(backend.synthesized.isEmpty())
    }

    @Test
    fun `queue uses whole synthesis when the backend cannot stream`() {
        val backend = CountingBackend()
        val delegate = FakeTtsEngine()
        val router = TtsRouter(delegate = delegate, backends = listOf(backend), selectedSource = { "minimax" })
        val queue = router.startQueue()!!
        queue.enqueue("Первое.")
        queue.finish()
        awaitTrue { delegate.playPcmCalls.size == 1 }
        assertEquals(1, delegate.playPcmCalls.size)
        assertTrue(delegate.playStreamCalls.isEmpty())
    }

    @Test
    fun `queue plays a cached phrase whole instead of streaming it`() {
        val backend = StreamingBackend(mapOf("Готово." to listOf(f(1f))))
        val delegate = FakeTtsEngine()
        val router = TtsRouter(delegate = delegate, backends = listOf(backend), selectedSource = { "minimax" })
        router.speak("Готово.") // single phrases stay whole and fill the cache
        awaitTrue { delegate.playPcmCalls.size == 1 }
        val queue = router.startQueue()!!
        queue.enqueue("Готово.")
        queue.finish()
        awaitTrue { delegate.playPcmCalls.size == 2 }
        assertEquals(2, delegate.playPcmCalls.size)
        assertTrue(delegate.playStreamCalls.isEmpty())
        assertTrue(backend.streamed.isEmpty())
        assertEquals(1, backend.synthesized.size)
    }

    @Test
    fun `a stream failing before its first chunk falls back to whole synthesis for that sentence`() {
        val backend = StreamingBackend(
            mapOf("Второе." to listOf(f(3f))),
            failBefore = setOf("Первое."),
        )
        val delegate = FakeTtsEngine()
        val router = TtsRouter(delegate = delegate, backends = listOf(backend), selectedSource = { "minimax" })
        val queue = router.startQueue()!!
        queue.enqueue("Первое.")
        queue.enqueue("Второе.")
        queue.finish()
        awaitTrue { delegate.playStreamCalls.size == 2 }
        assertEquals(listOf(listOf(9f) to 24_000, listOf(3f) to 24_000), delegate.playStreamCalls.toList())
        assertEquals(listOf("Первое."), backend.synthesized.toList())
    }

    @Test
    fun `a stream failing after audio started silences the rest of the reply`() {
        val backend = StreamingBackend(
            mapOf("Первое." to listOf(f(1f), f(2f)), "Второе." to listOf(f(3f))),
            failAfterFirst = setOf("Первое."),
        )
        val delegate = FakeTtsEngine()
        val scope = testScope()
        val router = TtsRouter(delegate = delegate, backends = listOf(backend), selectedSource = { "minimax" }, scope = scope)
        val queue = router.startQueue()!!
        queue.enqueue("Первое.")
        queue.enqueue("Второе.")
        queue.finish()
        awaitIdle(scope)
        assertEquals(listOf(listOf(1f) to 24_000), delegate.playStreamCalls.toList())
        assertTrue(backend.synthesized.isEmpty()) // no whole fallback once audio started
        assertTrue(delegate.playPcmCalls.isEmpty())
    }

    @Test
    fun `a short streamed phrase lands in the cache`() {
        val backend = StreamingBackend(mapOf("Готово." to listOf(f(1f), f(2f))))
        val delegate = FakeTtsEngine()
        val router = TtsRouter(delegate = delegate, backends = listOf(backend), selectedSource = { "minimax" })
        val queue = router.startQueue()!!
        queue.enqueue("Готово.")
        queue.finish()
        awaitTrue { delegate.playStreamCalls.size == 1 }
        router.speak("Готово.")
        awaitTrue { delegate.playPcmCalls.size == 1 }
        assertEquals(1, delegate.playPcmCalls.size)
        assertEquals(listOf(1f, 2f), delegate.playPcmCalls[0].first.toList())
        assertEquals(1, backend.streamed.size)
        assertTrue(backend.synthesized.isEmpty())
    }

    @Test
    fun `stop during a stream cancels the backend job and ends playback`() {
        val backend = StreamingBackend(mapOf("Первое." to listOf(f(1f), f(2f))), hang = setOf("Первое."))
        val delegate = FakeTtsEngine()
        val scope = testScope()
        val router = TtsRouter(delegate = delegate, backends = listOf(backend), selectedSource = { "minimax" }, scope = scope)
        val queue = router.startQueue()!!
        queue.enqueue("Первое.")
        assertTrue(delegate.streamChunkTaken.await(3, TimeUnit.SECONDS))
        router.stop()
        awaitIdle(scope)
        assertTrue(backend.cancelled)
        // The end marker still reaches the player, so it stops at the chunk already delivered.
        assertEquals(listOf(listOf(1f) to 24_000), delegate.playStreamCalls.toList())
        assertEquals(1, delegate.stopCalls)
        assertFalse(queue.enqueue("Второе."))
    }
}
