package com.bydmate.app.voice

import com.bydmate.app.voice.online.TtsRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins playPcmStream's DiLink audio rules through the pure [SherpaTtsEngine.pumpStream] seam. */
class SherpaTtsEnginePumpStreamTest {

    /** Scripted chunk source on a fake clock: a FloatArray arrives after [chunkLatencyMs], a null
     *  entry (and the exhausted script) is a poll that timed out, advancing the clock by the poll
     *  interval. One frame sounds for 1 ms, so the fake write stamps the audible floor like the
     *  engine. [onNext] runs inside next() with the 1-based call number, before it returns. */
    private class Harness(vararg script: FloatArray?) {
        private val script = ArrayDeque(script.toList())
        val events = mutableListOf<String>()
        var clockMs = 0L
        var audibleUntil = 0L
        var current = true
        var writeLimit = Int.MAX_VALUE
        var chunkLatencyMs = 0L
        var onWrite: () -> Unit = {}
        var onNext: (call: Int) -> Unit = {}
        private var nextCalls = 0

        fun run(initiallyPlaying: Boolean = false) = SherpaTtsEngine.pumpStream(
            next = { pollMs ->
                val chunk = script.removeFirstOrNull()
                clockMs += if (chunk == null) pollMs else chunkLatencyMs
                onNext(++nextCalls)
                chunk
            },
            prerollFrames = PREROLL,
            initiallyPlaying = initiallyPlaying,
            play = { events += "play" },
            pause = { events += "pause" },
            write = { samples ->
                events += "write:${samples.size}"
                val n = minOf(samples.size, writeLimit)
                audibleUntil = maxOf(audibleUntil, clockMs) + n
                onWrite()
                n
            },
            audibleUntil = { audibleUntil },
            stillCurrent = { current },
            now = { clockMs },
            stallMs = STALL_MS,
            pollMs = POLL_MS,
        )
    }

    private fun chunk(frames: Int) = FloatArray(frames) { 0.1f }
    private val end = FloatArray(0)
    private fun idle(polls: Int) = arrayOfNulls<FloatArray>(polls)

    @Test
    fun `nothing is played or written before the pre-roll is buffered`() {
        val h = Harness(chunk(100), chunk(100), chunk(100), end)
        val result = h.run()
        assertEquals(listOf("play", "write:300"), h.events)
        assertTrue(result.played)
        assertEquals(300L, result.framesWritten)
    }

    @Test
    fun `a stream shorter than the pre-roll is played at the end marker`() {
        val h = Harness(chunk(100), end)
        val result = h.run()
        assertEquals(listOf("play", "write:100"), h.events)
        assertTrue(result.played)
    }

    @Test
    fun `an empty stream plays nothing and is not a success`() {
        val h = Harness(end)
        val result = h.run()
        assertTrue(h.events.isEmpty())
        assertTrue(result.ended)
        assertFalse(result.played)
    }

    @Test
    fun `a still-playing track takes chunks without waiting for a pre-roll`() {
        val h = Harness(chunk(50), end)
        h.audibleUntil = 1_000 // previous sentence still sounding
        h.run(initiallyPlaying = true)
        assertEquals(listOf("write:50"), h.events)
    }

    @Test
    fun `starvation pauses the track and it plays again only after a fresh pre-roll`() {
        // 350 frames sound until t=350; the pause is due at t>=200, i.e. on the 5th empty poll.
        val h = Harness(chunk(350), *idle(8), chunk(100), chunk(100), chunk(100), end)
        val result = h.run()
        assertEquals(listOf("play", "write:350", "pause", "play", "write:300"), h.events)
        assertEquals(1, result.pauses)
        assertTrue(result.played)
    }

    @Test
    fun `the end marker resumes a paused track so its unplayed tail drains`() {
        val h = Harness(chunk(350), *idle(6), end)
        val result = h.run()
        assertEquals(listOf("play", "write:350", "pause", "play"), h.events)
        assertTrue(result.played)
    }

    @Test
    fun `no chunk within the stall limit ends the pump as stalled`() {
        val h = Harness(chunk(100))
        val result = h.run()
        assertTrue(result.stalled)
        assertFalse(result.played)
        assertTrue(h.events.isEmpty())
        assertTrue(h.clockMs >= STALL_MS)
    }

    @Test
    fun `supersession ends the pump without taking further chunks`() {
        val h = Harness(chunk(400), chunk(400), chunk(400), end)
        h.onWrite = { h.current = false }
        val result = h.run()
        assertEquals(listOf("play", "write:400"), h.events)
        assertFalse(result.ended)
        assertEquals(400L, result.framesReceived)
    }

    @Test
    fun `a stop landing during next never plays or writes the chunk it returns`() {
        val h = Harness(chunk(400), end)
        h.onNext = { h.current = false }
        val result = h.run()
        assertTrue(h.events.isEmpty())
        assertEquals(0L, result.framesReceived)
    }

    @Test
    fun `a stop landing during next never resumes a parked track`() {
        // Parked at t=200 (5th empty poll); the stop lands inside the 8th next(), which returns a chunk.
        val h = Harness(chunk(350), *idle(6), chunk(400), end)
        h.onNext = { call -> if (call == 8) h.current = false }
        h.run()
        assertEquals(listOf("play", "write:350", "pause"), h.events)
    }

    @Test
    fun `trickling chunks that carry less audio than their spacing park the track for a fresh pre-roll`() {
        // 10 ms of audio every 35 ms: the floor shrinks 25 ms per chunk and reaches the 150 ms
        // margin on the 6th small chunk, although every poll returned data.
        val trickle = Array(36) { chunk(10) }
        val h = Harness(chunk(300), *trickle, end)
        h.chunkLatencyMs = 35
        val result = h.run()
        val expected = listOf("play", "write:300") + List(6) { "write:10" } + listOf("pause", "play", "write:300")
        assertEquals(expected, h.events)
        assertEquals(1, result.pauses)
        assertTrue(result.played)
    }

    @Test
    fun `a stall while parked resumes the track so the written tail plays out`() {
        val h = Harness(chunk(350))
        val result = h.run()
        assertEquals(listOf("play", "write:350", "pause", "play"), h.events)
        assertTrue(result.stalled)
        assertFalse(result.played)
    }

    @Test
    fun `the stall limit outlasts the router's stream timeout plus its whole fallback`() {
        assertTrue(SherpaTtsEngine.STREAM_STALL_MS > 2 * TtsRouter.SYNTH_TIMEOUT_MS)
        assertTrue(SherpaTtsEngine.STREAM_STALL_MS < SherpaTtsEngine.STREAM_WAIT_BOUND_MS)
    }

    @Test
    fun `a short write stops the pump and is not a success`() {
        val h = Harness(chunk(400), chunk(400), end)
        h.writeLimit = 100
        val result = h.run()
        assertEquals(listOf("play", "write:400"), h.events)
        assertEquals(100L, result.framesWritten)
        assertFalse(result.played)
    }

    private companion object {
        const val PREROLL = 300
        const val STALL_MS = 10_000L
        const val POLL_MS = 40L
    }
}
