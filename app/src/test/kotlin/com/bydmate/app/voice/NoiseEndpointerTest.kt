package com.bydmate.app.voice

import com.bydmate.app.voice.NoiseEndpointer.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoiseEndpointerTest {

    private val frame = 100L

    /** Feeds [count] frames of [rms] with the VAD in [vadInSpeech]; the decisions in order. */
    private fun NoiseEndpointer.feed(rms: Double, count: Int, vadInSpeech: Boolean = true): List<Decision> =
        (1..count).map { onFrame(rms, frame, vadInSpeech) }

    // The 2026-11-15 highway case: floor ~420, the driver's words ~650-760, then the noise
    // alone while the VAD stays in "speech". The phrase must end, once.
    @Test fun `a phrase over road noise ends when the loudness falls back to the floor`() {
        val e = NoiseEndpointer()
        assertTrue(e.feed(420.0, 20).all { it == Decision.NONE })   // 2 s of road noise
        assertTrue(e.noisy)
        assertTrue(e.feed(700.0, 12).all { it == Decision.NONE })   // 1.2 s of speech
        val tail = e.feed(425.0, 8)                                  // 0.8 s back at the floor
        assertEquals(Decision.FLUSH, tail.last())
        assertEquals(1, tail.count { it == Decision.FLUSH })
        assertEquals(1200L, e.lastLoudMs)
        // Noise alone afterwards never ends another "phrase".
        assertTrue(e.feed(420.0, 60).all { it == Decision.NONE })
    }

    // AudioCapture can hand over a read of a few samples: its length rounds to 0 ms.
    @Test fun `a zero-length frame never throws`() {
        val e = NoiseEndpointer()
        e.feed(420.0, 20)
        e.feed(700.0, 2)     // too short to count as a phrase
        e.feed(425.0, 9)     // the quiet tail is already long enough
        assertEquals(Decision.NONE, e.onFrame(425.0, 0L, true))
    }

    // The driver often talks right after the button press, before any noise was measured.
    @Test fun `words spoken before the floor is known still end the phrase`() {
        val e = NoiseEndpointer()
        assertTrue(e.feed(700.0, 12).all { it == Decision.NONE })   // speech from the first frame
        val tail = e.feed(420.0, 12)
        assertEquals(1, tail.count { it == Decision.FLUSH })
    }

    @Test fun `a short pause inside the phrase does not split it`() {
        val e = NoiseEndpointer()
        e.feed(420.0, 20)
        e.feed(700.0, 6)
        assertTrue(e.feed(420.0, 5).all { it == Decision.NONE })    // 0.5 s pause between words
        assertTrue(e.feed(700.0, 6).all { it == Decision.NONE })
        assertEquals(Decision.FLUSH, e.feed(420.0, 8).last())
    }

    // No length cap: a long address read out over the noise stays one phrase.
    @Test fun `a long phrase is never cut while the driver keeps talking`() {
        val e = NoiseEndpointer()
        e.feed(420.0, 20)
        assertTrue(e.feed(700.0, 300).all { it == Decision.NONE })  // 30 s of talking
    }

    @Test fun `a quiet cabin keeps the VAD's own end-pointing`() {
        val e = NoiseEndpointer()
        e.feed(60.0, 20)
        assertFalse(e.noisy)
        e.feed(400.0, 10)
        assertTrue(e.feed(60.0, 20).all { it == Decision.NONE })
    }

    @Test fun `nothing happens while the VAD itself hears no speech`() {
        val e = NoiseEndpointer()
        e.feed(420.0, 20, vadInSpeech = false)
        e.feed(700.0, 10, vadInSpeech = false)
        assertTrue(e.feed(420.0, 20, vadInSpeech = false).all { it == Decision.NONE })
    }

    @Test fun `noise bursts too short to be words do not end a phrase`() {
        val e = NoiseEndpointer()
        e.feed(420.0, 20)
        e.feed(700.0, 2)                                             // 0.2 s bump (a pothole)
        assertTrue(e.feed(420.0, 20).all { it == Decision.NONE })
    }

    @Test fun `the floor is the noise between the words, not the words`() {
        val e = NoiseEndpointer()
        e.feed(420.0, 30)
        e.feed(800.0, 15)                                            // speech fills a third of the window
        assertTrue("floor=${e.floor}", e.floor in 400.0..440.0)
    }

    @Test fun `rms of 16-bit samples`() {
        assertEquals(0.0, NoiseEndpointer.rms(ShortArray(0)), 0.0)
        assertEquals(1000.0, NoiseEndpointer.rms(shortArrayOf(1000, -1000, 1000, -1000)), 1e-9)
    }
}
