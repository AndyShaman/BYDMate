package com.bydmate.app.voice.online

import com.bydmate.app.voice.TtsEngine
import com.bydmate.app.voice.TtsGender
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections
import java.util.concurrent.BlockingQueue
import java.util.concurrent.TimeUnit

/** Split out of TtsRouterTest (which was already at the LargeClass budget): review finding
 *  "wrong voice identity stored with the PCM" -- a fake backend that suspends mid-synthesis lets
 *  a test flip its voice identity while the router is still waiting on it, the same window a
 *  live provider/voice switch would open. See TtsRouter.cacheIfKeyStable. */
class TtsRouterPhraseIdentityTest {

    @get:Rule val tmp = TemporaryFolder()

    /** Records playPcm/playPcmStream calls, like TtsRouterTest's own fake; duplicated here
     *  (private to that class) to keep this file self-contained. */
    private class FakeTtsEngine : TtsEngine {
        val playPcmCalls: MutableList<Pair<FloatArray, Int>> = Collections.synchronizedList(mutableListOf())
        val playStreamCalls: MutableList<Pair<List<Float>, Int>> = Collections.synchronizedList(mutableListOf())
        var stopCalls = 0
        override fun isReady() = true
        override fun speak(text: String) = true
        override fun stop() { stopCalls++ }
        override val speaking: StateFlow<Boolean> = MutableStateFlow(false)
        override fun playPcm(samples: FloatArray, sampleRate: Int): Boolean {
            playPcmCalls += samples to sampleRate
            return true
        }
        override fun playPcmStream(chunks: BlockingQueue<FloatArray>, sampleRate: Int): Boolean {
            val got = mutableListOf<Float>()
            var chunk = chunks.poll(5, TimeUnit.SECONDS)
            while (chunk != null && chunk.isNotEmpty()) {
                got += chunk.toList()
                chunk = chunks.poll(5, TimeUnit.SECONDS)
            }
            playStreamCalls += got to sampleRate
            return true
        }
    }

    /** Suspends inside synthesize() until [gate] completes, so a test can flip [identity] while
     *  the router is still awaiting the backend. */
    private class GatedIdentityBackend(
        @Volatile var identity: String? = "voice-a",
        private val gate: CompletableDeferred<Unit>,
    ) : OnlineTtsBackend {
        override val id = "minimax"
        val synthesized: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override suspend fun synthesize(text: String, gender: TtsGender): TtsPcm {
            synthesized += text
            gate.await()
            return TtsPcm(floatArrayOf(0.1f, 0.2f), 24_000)
        }
        override suspend fun configured() = true
        override suspend fun voiceIdentity(gender: TtsGender) = identity
    }

    /** Streaming counterpart of [GatedIdentityBackend]: emits one chunk, then suspends on [gate]
     *  before the stream's last chunk, so a test can flip [identity] mid-stream. */
    private class GatedStreamBackend(
        @Volatile var identity: String? = "voice-a",
        private val gate: CompletableDeferred<Unit>,
    ) : OnlineTtsBackend {
        override val id = "minimax"
        val streamed: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override suspend fun synthesize(text: String, gender: TtsGender) = TtsPcm(floatArrayOf(0.1f), 24_000)
        override suspend fun configured() = true
        override suspend fun voiceIdentity(gender: TtsGender) = identity
        override suspend fun streamSampleRate() = 24_000
        override suspend fun synthesizeStream(text: String, gender: TtsGender, onChunk: (FloatArray) -> Unit) {
            streamed += text
            onChunk(floatArrayOf(0.1f, 0.2f))
            gate.await()
            onChunk(floatArrayOf(0.3f))
        }
    }

    private fun testScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun awaitTrue(deadlineMs: Long = 3_000, pollMs: Long = 20L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(pollMs)
        }
    }

    private fun awaitIdle(scope: CoroutineScope) = runBlocking {
        withTimeout(5_000) { scope.coroutineContext.job.children.toList().joinAll() }
    }

    /** Waits until nothing launched on [scope] is left, including the detached disk writes that
     *  finished jobs launch on their way out. */
    private fun awaitQuiet(scope: CoroutineScope) {
        while (scope.coroutineContext.job.children.any()) awaitIdle(scope)
    }

    private fun phraseFiles(dir: File) = dir.listFiles()?.filter { it.name.endsWith(".pcm") }.orEmpty()

    @Test
    fun `voice switched mid-synthesis is cached under neither the old nor the new key`() {
        val dir = tmp.newFolder()
        val gate = CompletableDeferred<Unit>()
        val backend = GatedIdentityBackend(gate = gate)
        val delegate = FakeTtsEngine()
        val scope = testScope()
        val router = TtsRouter(
            delegate = delegate, backends = listOf(backend), selectedSource = { "minimax" },
            scope = scope, phraseDir = dir,
        )
        router.speak("Готово.")
        awaitTrue { backend.synthesized.isNotEmpty() } // synthesis in flight, suspended on the gate
        backend.identity = "voice-b" // the user switched voice while the network call was open
        gate.complete(Unit)
        awaitTrue { delegate.playPcmCalls.size == 1 } // the reply still plays once
        awaitQuiet(scope)
        assertTrue("no phrase file must survive under either voice's key", dir.listFiles().isNullOrEmpty())
        assertEquals(0, router.phraseCacheSizeForTest())
    }

    @Test
    fun `voice identity unchanged during synthesis still caches as before`() {
        val dir = tmp.newFolder()
        val gate = CompletableDeferred<Unit>()
        val backend = GatedIdentityBackend(gate = gate)
        val delegate = FakeTtsEngine()
        val scope = testScope()
        val router = TtsRouter(
            delegate = delegate, backends = listOf(backend), selectedSource = { "minimax" },
            scope = scope, phraseDir = dir,
        )
        router.speak("Готово.")
        awaitTrue { backend.synthesized.isNotEmpty() }
        gate.complete(Unit) // identity never changes
        awaitTrue { delegate.playPcmCalls.size == 1 }
        awaitQuiet(scope)
        assertEquals(1, phraseFiles(dir).size)
        assertEquals(1, router.phraseCacheSizeForTest())
    }

    @Test
    fun `voice switched mid-stream is cached under neither the old nor the new key`() {
        val dir = tmp.newFolder()
        val gate = CompletableDeferred<Unit>()
        val backend = GatedStreamBackend(gate = gate)
        val delegate = FakeTtsEngine()
        val scope = testScope()
        val router = TtsRouter(
            delegate = delegate, backends = listOf(backend), selectedSource = { "minimax" },
            scope = scope, phraseDir = dir,
        )
        val queue = router.startQueue()!!
        queue.enqueue("Готово.")
        queue.finish()
        awaitTrue { backend.streamed.isNotEmpty() } // stream in flight, suspended on the gate
        backend.identity = "voice-b" // the user switched voice while the stream was open
        gate.complete(Unit)
        awaitTrue { delegate.playStreamCalls.size == 1 } // the reply still plays once
        awaitQuiet(scope)
        assertTrue("no phrase file must survive under either voice's key", dir.listFiles().isNullOrEmpty())
    }
}
