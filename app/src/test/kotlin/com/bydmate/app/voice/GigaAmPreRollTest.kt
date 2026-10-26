package com.bydmate.app.voice

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GigaAmPreRollTest {
    private class Vad(
        private val speechFrame: Int = 5,
        private val segmentFrame: Int = 6,
        private val nativeStart: Int = 7200,
    ) : VadHandle {
        var frame = 0
        var pending = false
        var closed = false
        var positionedReads = 0
        private var native = FloatArray(0)
        override fun acceptWaveform(samples: FloatArray) {
            frame++
            pending = frame % segmentFrame == 0
            if (pending) native = FloatArray(segmentFrame * 1600 - nativeStart) {
                ((frame - segmentFrame) * 1600 + nativeStart + it) / 32768f
            }
        }
        override fun isSpeechDetected() = frame % segmentFrame == speechFrame
        override fun empty() = !pending
        override fun front() = native
        override fun frontWithStart(): Pair<Long, FloatArray> {
            positionedReads++
            return (frame - segmentFrame) * 1600L + nativeStart to native
        }
        override fun pop() { pending = false }
        override fun close() { closed = true }
    }

    private class Recognizer(private val text: String = "exact") : RecognizerHandle {
        val decoded = mutableListOf<FloatArray>()
        override fun decode(samples: FloatArray): String { decoded.add(samples); return text }
        override fun close() = Unit
    }

    private fun manager() = mockk<GigaAmModelManager> { every { isReady() } returns true }
    private fun pcm(chunks: Int) = flow {
        repeat(chunks) { chunk -> emit(ShortArray(1600) { (chunk * 1600 + it).toShort() }) }
    }

    @Test fun `400 ms restores onset and retains the existing speech and silence event flow`() = runTest {
        val vad = Vad()
        val recognizer = Recognizer()
        val engine = GigaAmAsrEngine(manager(), { recognizer }, { vad }, preRollMs = 400)
        val events = engine.transcribe(pcm(6)).toList()
        assertArrayEquals(FloatArray(8000) { (1600 + it) / 32768f }, recognizer.decoded.single(), 0f)
        assertEquals(1, events.filterIsInstance<ContinuousAsrEvent.SpeechStart>().size)
        assertEquals(listOf(100L, 200L, 300L, 400L, 100L),
            events.filterIsInstance<ContinuousAsrEvent.SilenceTick>().map { it.silentMs })
        assertEquals(listOf("exact"), events.filterIsInstance<ContinuousAsrEvent.Utterance>().map { it.text })
        assertTrue(vad.closed)
    }

    @Test fun `default and explicit zero retain the original native front path and samples`() = runTest {
        for (explicitZero in listOf(false, true)) {
            val vad = Vad()
            val recognizer = Recognizer()
            val engine = if (explicitZero) {
                GigaAmAsrEngine(manager(), { recognizer }, { vad }, preRollMs = 0)
            } else {
                GigaAmAsrEngine(manager(), { recognizer }, { vad })
            }
            engine.transcribe(pcm(6)).toList()
            assertSame(vad.front(), recognizer.decoded.single())
            assertEquals(0, vad.positionedReads)
        }
    }

    @Test fun `consecutive native segments receive ordered context without earlier utterance samples`() = runTest {
        val recognizer = Recognizer()
        val engine = GigaAmAsrEngine(manager(), { recognizer }, { Vad() }, preRollMs = 400)
        engine.transcribe(pcm(12)).toList()
        assertEquals(2, recognizer.decoded.size)
        assertArrayEquals(FloatArray(8000) { (1600 + it) / 32768f }, recognizer.decoded[0], 0f)
        assertArrayEquals(FloatArray(8000) { (11200 + it) / 32768f }, recognizer.decoded[1], 0f)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `cancellation releases VAD and another collection starts with fresh history`() = runTest {
        val vads = mutableListOf<Vad>()
        val recognizer = Recognizer("")
        val engine = GigaAmAsrEngine(manager(), { recognizer }, {
            (if (vads.isEmpty()) Vad() else Vad(1, 2, 400)).also { vads.add(it) }
        }, preRollMs = 400)
        val session = launch {
            engine.transcribe(flow { repeat(4) { emit(ShortArray(1600) { -123 }) }; awaitCancellation() }).collect {}
        }
        runCurrent()
        session.cancelAndJoin()
        val events = engine.transcribe(pcm(2)).toList()
        assertTrue(events.none { it is ContinuousAsrEvent.Utterance })
        assertArrayEquals(FloatArray(3200) { it / 32768f }, recognizer.decoded.single(), 0f)
        assertEquals(2, vads.size)
        assertTrue(vads.all { it.closed })
    }

    @Test fun `completed collections also reset native sample positions and history`() = runTest {
        val recognizer = Recognizer()
        val engine = GigaAmAsrEngine(manager(), { recognizer }, { Vad(1, 2, 400) }, preRollMs = 400)
        repeat(2) { engine.transcribe(pcm(2)).toList() }
        assertEquals(2, recognizer.decoded.size)
        recognizer.decoded.forEach { assertArrayEquals(FloatArray(3200) { i -> i / 32768f }, it, 0f) }
    }

    @Test fun `completed segment without an observed onset remains native and unmodified`() = runTest {
        val native = floatArrayOf(0.2f, 0.3f)
        var pending = false
        val vad = object : VadHandle {
            override fun acceptWaveform(samples: FloatArray) { pending = true }
            override fun isSpeechDetected() = false
            override fun empty() = !pending
            override fun front() = native
            override fun frontWithStart() = 100L to native
            override fun pop() { pending = false }
            override fun close() = Unit
        }
        val recognizer = Recognizer()
        GigaAmAsrEngine(manager(), { recognizer }, { vad }, preRollMs = 400)
            .transcribe(flow { emit(ShortArray(20000)) }).toList()
        assertSame(native, recognizer.decoded.single())
    }

    @Test fun `queued segments cannot reuse the first prefix and a later onset excludes both`() = runTest {
        var frame = 0
        val queued = java.util.ArrayDeque<Pair<Long, FloatArray>>()
        val vad = object : VadHandle {
            override fun acceptWaveform(samples: FloatArray) {
                frame++
                when (frame) {
                    3 -> {
                        queued.add(800L to FloatArray(2400) { (800 + it) / 32768f })
                        queued.add(3200L to FloatArray(1600) { (3200 + it) / 32768f })
                    }
                    5 -> queued.add(5600L to FloatArray(2400) { (5600 + it) / 32768f })
                }
            }
            override fun isSpeechDetected() = frame == 2 || frame == 4
            override fun empty() = queued.isEmpty()
            override fun front() = queued.first.second
            override fun frontWithStart() = queued.first
            override fun pop() { queued.removeFirst() }
            override fun close() = Unit
        }
        val recognizer = Recognizer()
        GigaAmAsrEngine(manager(), { recognizer }, { vad }, preRollMs = 400).transcribe(pcm(5)).toList()
        assertEquals(3, recognizer.decoded.size)
        assertArrayEquals(FloatArray(3200) { it / 32768f }, recognizer.decoded[0], 0f)
        assertArrayEquals(FloatArray(1600) { (3200 + it) / 32768f }, recognizer.decoded[1], 0f)
        assertArrayEquals(FloatArray(3200) { (4800 + it) / 32768f }, recognizer.decoded[2], 0f)
    }
}
