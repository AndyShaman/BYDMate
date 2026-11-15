package com.bydmate.app.voice.online

import com.bydmate.app.voice.TtsEngine
import com.bydmate.app.voice.TtsGender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.BlockingQueue
import java.util.concurrent.TimeUnit

/** When the online voice fails, the reply is spoken by the offline voice and the failure is
 *  reported once. */
class TtsRouterOfflineFallbackTest {

    private class FakeTtsEngine(private val ready: Boolean = true) : TtsEngine {
        val speakCalls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val playPcmCalls: MutableList<Pair<FloatArray, Int>> = Collections.synchronizedList(mutableListOf())
        val queueEnqueued: MutableList<String> = Collections.synchronizedList(mutableListOf())
        @Volatile var queueFinished = false
        @Volatile var stopCalls = 0
        @Volatile var playPcmResult = true

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
        override fun playPcmStream(chunks: BlockingQueue<FloatArray>, sampleRate: Int): Boolean {
            var chunk = chunks.poll(5, TimeUnit.SECONDS)
            while (chunk != null && chunk.isNotEmpty()) chunk = chunks.poll(5, TimeUnit.SECONDS)
            return true
        }
        override fun startQueue(): TtsEngine.SpeechQueue = object : TtsEngine.SpeechQueue {
            override fun enqueue(text: String): Boolean { queueEnqueued += text; return true }
            override fun finish() { queueFinished = true }
        }
    }

    private class ThrowingBackend(
        override val id: String = "gemini",
        private val delayMs: Long = 0,
        val error: Exception = IOException("OpenRouter TTS HTTP 404: refused"),
    ) : OnlineTtsBackend {
        @Volatile var synthCalls = 0
        override suspend fun synthesize(text: String, gender: TtsGender): TtsPcm {
            synthCalls++
            if (delayMs > 0) delay(delayMs)
            throw error
        }
        override suspend fun configured() = true
    }

    private class OkBackend(override val id: String = "gemini") : OnlineTtsBackend {
        override suspend fun synthesize(text: String, gender: TtsGender) = TtsPcm(floatArrayOf(0.1f, 0.2f), 16_000)
        override suspend fun configured() = true
    }

    /** Succeeds for the sentence "one" only. Keyed by text, not by call order: the router
     *  synthesizes ahead of playback, so the order requests reach the backend is not fixed. */
    private class FlakyBackend : OnlineTtsBackend {
        override val id = "gemini"
        override suspend fun synthesize(text: String, gender: TtsGender): TtsPcm {
            if (text != "one") throw IOException("synthesis failed")
            return TtsPcm(floatArrayOf(0.1f), 16_000)
        }
        override suspend fun configured() = true
    }

    /** Streams at 24 kHz; every synthesis path throws, after one chunk for texts in [chunkThenFail]. */
    private class FailingStreamBackend(private val chunkThenFail: Set<String> = emptySet()) : OnlineTtsBackend {
        override val id = "gemini"
        override suspend fun synthesize(text: String, gender: TtsGender): TtsPcm = throw IOException("whole refused")
        override suspend fun configured() = true
        override suspend fun streamSampleRate() = 24_000
        override suspend fun synthesizeStream(text: String, gender: TtsGender, onChunk: (FloatArray) -> Unit) {
            if (text in chunkThenFail) onChunk(floatArrayOf(1f))
            throw IOException("stream broke")
        }
    }

    private fun awaitTrue(deadlineMs: Long = 10_000, pollMs: Long = 20L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(pollMs)
        }
    }

    private fun awaitIdle(scope: CoroutineScope) = runBlocking {
        withTimeout(5_000) { scope.coroutineContext.job.children.toList().joinAll() }
    }

    private fun testScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val failures: MutableList<OnlineTtsFailure> = Collections.synchronizedList(mutableListOf())

    private fun router(
        delegate: TtsEngine,
        backend: OnlineTtsBackend,
        scope: CoroutineScope,
        synthTimeoutMs: Long = TtsRouter.SYNTH_TIMEOUT_MS,
        precache: List<String> = emptyList(),
    ) = TtsRouter(
        delegate = delegate,
        backends = listOf(backend),
        selectedSource = { backend.id },
        precachePhrases = { precache },
        scope = scope,
        synthTimeoutMs = synthTimeoutMs,
        onOnlineFailure = { failures += it },
    )

    private fun playAll(queue: TtsEngine.SpeechQueue, vararg texts: String) {
        texts.forEach { queue.enqueue(it) }
        queue.finish()
        runBlocking { withTimeout(10_000) { queue.awaitPlayed() } }
    }

    // B
    @Test
    fun `speak with a failing online backend is spoken offline and the failure reported once`() {
        val backend = ThrowingBackend()
        val delegate = FakeTtsEngine()
        val scope = testScope()
        assertTrue(router(delegate, backend, scope).speak("text"))
        awaitIdle(scope)
        assertEquals(listOf("text"), delegate.speakCalls.toList())
        assertTrue(delegate.playPcmCalls.isEmpty())
        assertEquals(1, failures.size)
        assertEquals("gemini", failures[0].backendId)
        assertSame(backend.error, failures[0].cause)
        assertTrue(failures[0].spokenOffline)
    }

    // C
    @Test
    fun `speak with a failing backend and an unready offline voice reports spokenOffline false`() {
        val delegate = FakeTtsEngine(ready = false)
        val scope = testScope()
        router(delegate, ThrowingBackend(), scope).speak("text")
        awaitIdle(scope)
        assertTrue(delegate.speakCalls.isEmpty())
        assertEquals(1, failures.size)
        assertFalse(failures[0].spokenOffline)
    }

    // D
    @Test
    fun `speak with a synthesis timeout is spoken offline and the failure reported once`() {
        val delegate = FakeTtsEngine()
        val scope = testScope()
        router(delegate, ThrowingBackend(delayMs = 60_000), scope, synthTimeoutMs = 100).speak("text")
        awaitIdle(scope)
        assertEquals(listOf("text"), delegate.speakCalls.toList())
        assertTrue(delegate.playPcmCalls.isEmpty())
        assertEquals(1, failures.size)
        assertTrue(failures[0].spokenOffline)
    }

    // E
    @Test
    fun `a playback failure is not an online voice failure and stays silent`() {
        val delegate = FakeTtsEngine().apply { playPcmResult = false }
        val scope = testScope()
        router(delegate, OkBackend(), scope).speak("text")
        awaitIdle(scope)
        assertEquals(1, delegate.playPcmCalls.size)
        assertTrue(delegate.speakCalls.isEmpty())
        assertTrue(failures.isEmpty())
    }

    // F
    @Test
    fun `queue with a backend failing every sentence speaks the whole reply offline, reports once`() {
        val backend = ThrowingBackend()
        val delegate = FakeTtsEngine()
        val r = router(delegate, backend, testScope())
        val reply = listOf("one", "two", "three", "four", "five", "six")
        playAll(r.startQueue()!!, *reply.toTypedArray())
        assertEquals(reply, delegate.queueEnqueued.toList())
        assertTrue(delegate.queueFinished)
        assertTrue(delegate.playPcmCalls.isEmpty())
        assertEquals(1, failures.size)
        assertTrue(failures[0].spokenOffline)
        // After the first failure no further online requests start. Up to three may already be
        // in flight by then (one awaited by the player, one buffered, one being handed over).
        assertTrue("synthesize calls: ${backend.synthCalls}", backend.synthCalls <= 3)
    }

    // G
    @Test
    fun `queue failing after the first sentence speaks the rest offline`() {
        val delegate = FakeTtsEngine()
        val r = router(delegate, FlakyBackend(), testScope())
        playAll(r.startQueue()!!, "one", "two", "three")
        assertEquals(1, delegate.playPcmCalls.size)
        assertEquals(listOf("two", "three"), delegate.queueEnqueued.toList())
        assertTrue(delegate.queueFinished)
        assertEquals(1, failures.size)
    }

    // H
    @Test
    fun `queue failing with an unready offline voice stays silent and reports spokenOffline false`() {
        val delegate = FakeTtsEngine(ready = false)
        val r = router(delegate, ThrowingBackend(), testScope())
        playAll(r.startQueue()!!, "one", "two", "three")
        assertTrue(delegate.queueEnqueued.isEmpty())
        assertTrue(delegate.playPcmCalls.isEmpty())
        assertEquals(1, failures.size)
        assertFalse(failures[0].spokenOffline)
    }

    // I
    @Test
    fun `streaming queue failing after audio started does not repeat that sentence offline`() {
        val delegate = FakeTtsEngine()
        val r = router(delegate, FailingStreamBackend(chunkThenFail = setOf("one")), testScope())
        playAll(r.startQueue()!!, "one", "two", "three")
        assertEquals(listOf("two", "three"), delegate.queueEnqueued.toList())
        assertEquals(1, failures.size)
    }

    // J
    @Test
    fun `streaming queue failing before any chunk speaks the whole reply offline`() {
        val delegate = FakeTtsEngine()
        val r = router(delegate, FailingStreamBackend(), testScope())
        playAll(r.startQueue()!!, "one", "two", "three")
        assertEquals(listOf("one", "two", "three"), delegate.queueEnqueued.toList())
        assertEquals(1, failures.size)
    }

    // K
    @Test
    fun `a failed background precache at warmUp neither notifies nor speaks`() {
        val delegate = FakeTtsEngine()
        val scope = testScope()
        router(delegate, ThrowingBackend(), scope, precache = listOf("Есть.", "Готово.")).warmUp()
        awaitIdle(scope)
        assertTrue(failures.isEmpty())
        assertTrue(delegate.speakCalls.isEmpty())
        assertTrue(delegate.queueEnqueued.isEmpty())
    }

    // L
    @Test
    fun `stop after the queue fell back to offline rejects further sentences`() {
        val delegate = FakeTtsEngine()
        val r = router(delegate, ThrowingBackend(), testScope())
        val queue = r.startQueue()!!
        queue.enqueue("one")
        awaitTrue { delegate.queueEnqueued.contains("one") }
        assertEquals(listOf("one"), delegate.queueEnqueued.toList())
        r.stop()
        assertFalse(queue.enqueue("two"))
        Thread.sleep(100)
        assertEquals(listOf("one"), delegate.queueEnqueued.toList())
        assertTrue(delegate.stopCalls >= 1)
    }
}
