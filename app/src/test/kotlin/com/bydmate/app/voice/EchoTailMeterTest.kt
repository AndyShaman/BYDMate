package com.bydmate.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EchoTailMeterTest {

    private fun frames(vararg rms: Double) = rms.mapIndexed { i, level -> TailFrame((i + 1) * 100L, level) }

    @Test fun `settles at the first frame after which the level stays at the floor`() {
        // floor = 2 x the quietest frame (100) + 20; a dip at +200 ms does not count, the tail
        // goes on at +300 ms.
        val line = echoTailLine(frames(3_000.0, 150.0, 900.0, 180.0, 100.0, 120.0), windowMs = 1_000L)
        assertEquals("echo tail: quiet at +400ms (floor=220 peak=3000)", line)
    }

    @Test fun `a window that is quiet from its first frame settles at that frame`() {
        assertEquals("echo tail: quiet at +100ms (floor=220 peak=110)",
            echoTailLine(frames(110.0, 100.0, 105.0), windowMs = 1_000L))
    }

    @Test fun `a window that ends loud never settles`() {
        assertEquals("echo tail: not settled in 1000ms (speech?)",
            echoTailLine(frames(3_000.0, 100.0, 2_500.0), windowMs = 1_000L))
    }

    @Test fun `a window with no frames says so`() {
        assertEquals("echo tail: no frames in 1000ms", echoTailLine(emptyList(), windowMs = 1_000L))
    }

    @Test fun `rms of a frame`() {
        assertEquals(3.5355, frameRms(shortArrayOf(3, -4)), 0.0001)
        assertEquals(1_000.0, frameRms(ShortArray(1_600) { if (it % 2 == 0) 1_000 else -1_000 }), 0.0001)
        assertEquals(0.0, frameRms(ShortArray(0)), 0.0)
    }

    // --- The meter: one line per playback end, once its window closes ---

    private fun pcm(level: Int) = ShortArray(1_600) { if (it % 2 == 0) level.toShort() else (-level).toShort() }

    @Test fun `the meter logs once, when the window after the last audible frame closes`() {
        val meter = EchoTailMeter()
        assertNull(meter.onFrame(1_000L, audible = true, pcm = pcm(5_000)))
        val lines = (1..12).map { meter.onFrame(1_000L + it * 100L, audible = false, pcm = pcm(if (it <= 2) 2_000 else 100)) }
        assertEquals(listOf(null, null, null, null, null, null, null, null, null,
            "echo tail: quiet at +300ms (floor=220 peak=2000)", null, null), lines)
    }

    @Test fun `playback resuming inside the window starts the measurement over`() {
        val meter = EchoTailMeter()
        meter.onFrame(1_000L, audible = true, pcm = pcm(5_000))
        assertNull(meter.onFrame(1_100L, audible = false, pcm = pcm(2_000)))
        assertNull(meter.onFrame(1_200L, audible = true, pcm = pcm(5_000)))
        val lines = (1..10).map { meter.onFrame(1_200L + it * 100L, audible = false, pcm = pcm(100)) }
        assertEquals("echo tail: quiet at +100ms (floor=220 peak=100)", lines.last())
        assertEquals(9, lines.count { it == null })
    }

    @Test fun `no playback, no measurement`() {
        val meter = EchoTailMeter()
        assertNull((1..20).firstNotNullOfOrNull { meter.onFrame(it * 100L, audible = false, pcm = pcm(100)) })
    }

    @Test fun `a capture stall past the window closes it with no frames`() {
        val meter = EchoTailMeter()
        meter.onFrame(1_000L, audible = true, pcm = pcm(5_000))
        assertEquals("echo tail: no frames in 1000ms", meter.onFrame(2_500L, audible = false, pcm = pcm(100)))
    }
}
