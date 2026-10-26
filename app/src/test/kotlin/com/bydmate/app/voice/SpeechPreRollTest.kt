package com.bydmate.app.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SpeechPreRollTest {
    private fun pcm(start: Int, count: Int) = FloatArray(count) { (start + it).toFloat() }

    @Test fun `zero context preserves the original native segment`() {
        val roll = SpeechPreRoll(0)
        roll.append(pcm(0, 8000))
        roll.speechStart()
        val segment = pcm(7200, 2400)
        assertSame(segment, roll.finish(7200, segment))
    }

    @Test fun `400 ms history preserves order without duplicating native onset padding`() {
        val roll = SpeechPreRoll(6400)
        roll.append(pcm(0, 8000))
        roll.speechStart()
        assertArrayEquals(pcm(1600, 8000), roll.finish(7200, pcm(7200, 2400)), 0f)
    }

    @Test fun `short recording prepends only available samples`() {
        val roll = SpeechPreRoll(6400)
        roll.append(pcm(0, 1000))
        roll.speechStart()
        assertArrayEquals(pcm(0, 1000), roll.finish(200, pcm(200, 800)), 0f)
    }

    @Test fun `empty history leaves native samples unchanged`() {
        val roll = SpeechPreRoll(6400)
        roll.speechStart()
        val segment = pcm(0, 100)
        assertSame(segment, roll.finish(0, segment))
    }

    @Test fun `native onset before or at history start already contains all context`() {
        for (start in listOf(0, 1600)) {
            val roll = SpeechPreRoll(6400)
            roll.append(pcm(0, 8000))
            roll.speechStart()
            val segment = pcm(start, 9600 - start)
            assertSame(segment, roll.finish(start.toLong(), segment))
        }
    }

    @Test fun `ring boundaries wrap and oversized frames retain exactly the newest history`() {
        for (size in listOf(0, 1, 6399, 6400, 6401, 20000)) {
            val roll = SpeechPreRoll(6400)
            roll.append(pcm(0, 6421))
            roll.append(pcm(6421, size))
            roll.speechStart()
            val end = 6421 + size
            assertArrayEquals(pcm(end - 6400, 6401), roll.finish(end.toLong(), pcm(end, 1)), 0f)
        }
    }

    @Test fun `consecutive utterances cannot prepend previously consumed audio`() {
        val roll = SpeechPreRoll(6400)
        roll.append(pcm(0, 6400))
        roll.speechStart()
        roll.finish(0, pcm(0, 6400))
        repeat(4) { utterance ->
            val start = 6400 + utterance * 2000
            roll.append(pcm(start, 2000))
            roll.speechStart()
            assertArrayEquals(pcm(start, 2000), roll.finish(start + 1000L, pcm(start + 1000, 1000)), 0f)
        }
    }

    @Test fun `long utterance cannot overwrite its frozen onset context`() {
        val roll = SpeechPreRoll(6400)
        roll.append(pcm(0, 8000))
        roll.speechStart()
        repeat(100) { roll.append(FloatArray(20000) { -1f }) }
        assertArrayEquals(pcm(1600, 8000), roll.finish(7200, pcm(7200, 2400)), 0f)
    }

    @Test fun `a fresh session has no samples from the old session`() {
        val old = SpeechPreRoll(6400)
        old.append(FloatArray(6400) { -1f })
        old.speechStart()
        val fresh = SpeechPreRoll(6400)
        fresh.append(pcm(0, 100))
        fresh.speechStart()
        assertArrayEquals(pcm(0, 200), fresh.finish(100, pcm(100, 100)), 0f)
    }
}
