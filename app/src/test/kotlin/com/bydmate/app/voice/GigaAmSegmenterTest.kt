package com.bydmate.app.voice

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Segmentation logic tests for GigaAmAsrEngine, driven entirely through fake
 * RecognizerHandle/VadHandle seams -- the sherpa-onnx JNI classes are never touched.
 */
class GigaAmSegmenterTest {

    private fun readyModelManager(): GigaAmModelManager =
        mockk<GigaAmModelManager>(relaxed = true).also { every { it.isReady() } returns true }

    private fun notReadyModelManager(): GigaAmModelManager =
        mockk<GigaAmModelManager>(relaxed = true).also { every { it.isReady() } returns false }

    // Scripts one isSpeechDetected()/segment outcome per acceptWaveform() call, in order.
    // Out-of-range calls repeat the last entry (needed for the infinite-flow cancellation test).
    // Default (no args): silence forever, no segment -- fine for tests that only care about
    // recognizer/vad instance counts, not transcription content.
    private class FakeVadHandle(
        private val perFrame: List<FrameScript> = listOf(FrameScript(speech = false)),
    ) : VadHandle {
        data class FrameScript(val speech: Boolean, val segment: FloatArray? = null)

        private var idx = -1
        private var pendingSegment: FloatArray? = null
        var closed = false

        private fun current() = perFrame.getOrElse(idx) { perFrame.last() }

        override fun acceptWaveform(samples: FloatArray) {
            idx++
            current().segment?.let { pendingSegment = it }
        }

        override fun isSpeechDetected(): Boolean = current().speech
        override fun empty(): Boolean = pendingSegment == null
        override fun front(): FloatArray = pendingSegment!!
        override fun pop() { pendingSegment = null }
        override fun close() { closed = true }
    }

    private class FakeRecognizerHandle(
        private val text: String = "",
        private val onClose: () -> Unit = {},
    ) : RecognizerHandle {
        var closed = false
        var lastSamples: FloatArray? = null
        override fun decode(samples: FloatArray): String {
            lastSamples = samples
            return text
        }
        override fun close() { closed = true; onClose() }
    }

    @Test fun `speech then silence emits exactly one utterance with recognizer text`() = runTest {
        val segment = floatArrayOf(0.1f, 0.2f)
        val vad = FakeVadHandle(listOf(
            FakeVadHandle.FrameScript(speech = true),
            FakeVadHandle.FrameScript(speech = true),
            FakeVadHandle.FrameScript(speech = false, segment = segment),
        ))
        val recognizer = FakeRecognizerHandle("привет")
        val engine = GigaAmAsrEngine(readyModelManager(), { recognizer }, { vad })

        val events = engine.transcribe(List(3) { ShortArray(320) }.asFlow()).toList()

        val utterances = events.filterIsInstance<ContinuousAsrEvent.Utterance>()
        assertEquals(1, utterances.size)
        assertEquals("привет", utterances[0].text)
        assertSame(segment, recognizer.lastSamples)
    }

    @Test fun `silence ticks grow monotonically over 1000ms of silence`() = runTest {
        // 320 samples at 16kHz = 20ms/frame; 50 frames = 1000ms.
        val vad = FakeVadHandle(List(50) { FakeVadHandle.FrameScript(speech = false) })
        val engine = GigaAmAsrEngine(readyModelManager(), { FakeRecognizerHandle("") }, { vad })

        val ticks = engine.transcribe(List(50) { ShortArray(320) }.asFlow())
            .filterIsInstance<ContinuousAsrEvent.SilenceTick>()
            .toList()

        assertEquals(50, ticks.size)
        assertEquals(20L, ticks.first().silentMs)
        assertEquals(1000L, ticks.last().silentMs)
        for (i in 1 until ticks.size) {
            assertTrue(ticks[i].silentMs > ticks[i - 1].silentMs)
        }
    }

    @Test fun `speech start emits SpeechStart exactly once on the silence-to-speech transition`() = runTest {
        val vad = FakeVadHandle(listOf(
            FakeVadHandle.FrameScript(speech = false),
            FakeVadHandle.FrameScript(speech = true),
        ))
        val engine = GigaAmAsrEngine(readyModelManager(), { FakeRecognizerHandle("") }, { vad })

        val events = engine.transcribe(List(2) { ShortArray(320) }.asFlow()).toList()

        assertEquals(1, events.filterIsInstance<ContinuousAsrEvent.SpeechStart>().size)
    }

    // Wave G Task 1: the recognizer is now cached across collections (see
    // recognizer_created_once_across_two_collections_vad_per_collection below), so it is no
    // longer closed on cancellation/failure paths -- only the per-collection VAD is.

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `cancelling collection closes its vad, recognizer stays cached`() = runTest {
        val vad = FakeVadHandle(listOf(FakeVadHandle.FrameScript(speech = false)))
        val recognizer = FakeRecognizerHandle("")
        val engine = GigaAmAsrEngine(readyModelManager(), { recognizer }, { vad })
        val pcm = flow { while (true) { emit(ShortArray(320)); delay(10) } }

        val job = launch { engine.transcribe(pcm).collect {} }
        advanceTimeBy(50)
        job.cancelAndJoin()

        assertTrue(vad.closed)
        assertFalse(recognizer.closed)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `overlapping collections each own a vad but share the cached recognizer`() = runTest {
        // Pins the per-collection VAD scoping: two overlapping collections of the SAME returned
        // cold Flow must each own their own VAD (transcribe()-level shared variables made the
        // collections close one pair twice and leak the other) while sharing one recognizer.
        val vads = mutableListOf<FakeVadHandle>()
        val recognizers = mutableListOf<FakeRecognizerHandle>()
        val engine = GigaAmAsrEngine(
            readyModelManager(),
            { FakeRecognizerHandle("").also { recognizers += it } },
            { FakeVadHandle(listOf(FakeVadHandle.FrameScript(speech = false))).also { vads += it } },
        )
        val stream = engine.transcribe(flow { repeat(10) { emit(ShortArray(320)); delay(10) } })

        val jobA = launch { stream.collect {} }
        advanceTimeBy(15)
        val jobB = launch { stream.collect {} }
        jobA.join()
        jobB.join()

        assertEquals(2, vads.size)
        assertEquals(1, recognizers.size)
        assertTrue(vads.all { it.closed })
        assertFalse(recognizers.first().closed)
    }

    @Test fun `vad factory failure leaves the already-cached recognizer open`() = runTest {
        val recognizer = FakeRecognizerHandle("")
        val engine = GigaAmAsrEngine(
            readyModelManager(),
            { recognizer },
            { error("vad init failed") },
        )

        runCatching { engine.transcribe(List(1) { ShortArray(320) }.asFlow()).toList() }

        assertFalse(recognizer.closed)
    }

    @Test fun `recognizer stays cached even when vad close throws`() = runTest {
        val recognizer = FakeRecognizerHandle("")
        val vad = object : VadHandle by FakeVadHandle(listOf(FakeVadHandle.FrameScript(speech = false))) {
            override fun close() = error("vad close failed")
        }
        val engine = GigaAmAsrEngine(readyModelManager(), { recognizer }, { vad })

        runCatching { engine.transcribe(List(1) { ShortArray(320) }.asFlow()).toList() }

        assertFalse(recognizer.closed)
    }

    // --- Wave G Task 1: recognizer cached across sessions, VAD stays per-collection ---

    @Test
    fun recognizer_created_once_across_two_collections_vad_per_collection() = runTest {
        var recognizerCreations = 0
        var vadCreations = 0
        var recognizerClosed = 0
        val engine = GigaAmAsrEngine(
            modelManager = readyModelManager(),
            recognizerFactory = { recognizerCreations++; FakeRecognizerHandle(onClose = { recognizerClosed++ }) },
            vadFactory = { vadCreations++; FakeVadHandle() },
        )
        engine.transcribe(flowOf(ShortArray(160))).collect {}
        engine.transcribe(flowOf(ShortArray(160))).collect {}
        assertEquals(1, recognizerCreations)
        assertEquals(2, vadCreations)
        assertEquals(0, recognizerClosed)   // cached recognizer is NOT closed in finally
    }

    // --- Task 5: pre-warm the recognizer so the first PTT's transcribe() doesn't pay the
    // cold model-load cost before the mic starts recording. ---

    @Test fun `warmUp builds recognizer once and transcribe reuses it`() = runTest {
        var recognizerCreations = 0
        val engine = GigaAmAsrEngine(
            modelManager = readyModelManager(),
            recognizerFactory = { recognizerCreations++; FakeRecognizerHandle() },
            vadFactory = { FakeVadHandle() },
        )

        engine.warmUp()
        assertEquals(1, recognizerCreations)

        engine.transcribe(flowOf(ShortArray(160))).collect {}
        assertEquals(1, recognizerCreations)   // reused, not rebuilt
    }

    @Test fun `warmUp is a no-op when model files absent`() = runTest {
        var recognizerCreations = 0
        val engine = GigaAmAsrEngine(
            modelManager = notReadyModelManager(),
            recognizerFactory = { recognizerCreations++; FakeRecognizerHandle() },
            vadFactory = { FakeVadHandle() },
        )

        engine.warmUp()

        assertEquals(0, recognizerCreations)
    }

    @Test fun `blank recognizer text does not emit an utterance`() = runTest {
        val vad = FakeVadHandle(listOf(
            FakeVadHandle.FrameScript(speech = true),
            FakeVadHandle.FrameScript(speech = false, segment = floatArrayOf(0.1f)),
        ))
        val recognizer = FakeRecognizerHandle("")
        val engine = GigaAmAsrEngine(readyModelManager(), { recognizer }, { vad })

        val events = engine.transcribe(List(2) { ShortArray(320) }.asFlow()).toList()

        assertTrue(events.filterIsInstance<ContinuousAsrEvent.Utterance>().isEmpty())
    }
}
