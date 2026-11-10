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

    // --- The meter: one line per playback end, once its window closes ---

    private fun pcm(level: Int) = ShortArray(1_600) { if (it % 2 == 0) level.toShort() else (-level).toShort() }

    @Test fun `playback resuming inside the window starts the measurement over`() {
        val meter = EchoTailMeter()
        meter.onFrame(1_000L, audible = true, pcm = pcm(5_000))
        assertNull(meter.onFrame(1_100L, audible = false, pcm = pcm(2_000)))
        assertNull(meter.onFrame(1_200L, audible = true, pcm = pcm(5_000)))
        val lines = (1..10).map { meter.onFrame(1_200L + it * 100L, audible = false, pcm = pcm(100)) }
        assertEquals("echo tail: quiet at +100ms (floor=220 peak=100)", lines.last())
        assertEquals(9, lines.count { it == null })
    }
}
