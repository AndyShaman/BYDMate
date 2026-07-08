package com.bydmate.app.voice

import android.media.AudioTrack
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaTtsEngineTest {

    private val modelManager = mockk<TtsModelManager>()

    @Test
    fun `speak is a no-op when model not ready`() {
        every { modelManager.isReady(any()) } returns false
        val engine = SherpaTtsEngine(modelManager)
        engine.speak("привет")   // must not throw, must not touch JNI
    }

    @Test
    fun `speak is a no-op on blank text`() {
        every { modelManager.isReady(any()) } returns true
        val engine = SherpaTtsEngine(modelManager)
        engine.speak("   ")      // blank check happens before any JNI path
    }

    @Test
    fun `stop before any speak does not throw`() {
        every { modelManager.isReady(any()) } returns false
        SherpaTtsEngine(modelManager).stop()
    }

    @Test
    fun `reload before any speak does not throw`() {
        every { modelManager.isReady(any()) } returns false
        SherpaTtsEngine(modelManager).reload()   // must not touch JNI when tts/track are null
    }

    // --- Wave F, Task 1: TTS on BYD Voice stream (BTTS=17), with fallback to accessibility ---

    @Test
    fun `createTrackWithFallback falls back when primary throws`() {
        val result = SherpaTtsEngine.createTrackWithFallback(
            primary = { throw IllegalArgumentException() },
            fallback = { "fb" },
        )
        assertEquals("fb", result)
    }

    @Test
    fun `createTrackWithFallback falls back when primary returns null`() {
        val result = SherpaTtsEngine.createTrackWithFallback(
            primary = { null },
            fallback = { "fb" },
        )
        assertEquals("fb", result)
    }

    @Test
    fun `createTrackWithFallback uses primary result without calling fallback`() {
        val result = SherpaTtsEngine.createTrackWithFallback(
            primary = { "p" },
            fallback = { error("must not be called") },
        )
        assertEquals("p", result)
    }

    @Test
    fun `BYD_STREAM_BTTS is 17`() {
        assertEquals(17, SherpaTtsEngine.BYD_STREAM_BTTS)
    }

    // --- Fix wave 2, finding 1: barge-in must free the drain loop promptly, not spin the timeout ---

    @Test
    fun `awaitDrain bails as soon as generation is superseded, not after the full timeout`() {
        var stillCurrentCalls = 0
        var clock = 0L
        SherpaTtsEngine.awaitDrain(
            targetFrames = 1_000L,
            timeoutMs = 5_000L,
            pollMs = 20L,
            currentFrames = { 0L }, // never reaches the target on its own
            stillCurrent = { stillCurrentCalls++; stillCurrentCalls <= 2 }, // barge-in on the 3rd check
            sleep = { clock += it },
            now = { clock },
        )
        // Bailed on the 3rd stillCurrent() check (the simulated barge-in), not after spinning
        // through the full 5s timeout -- the fake clock only advanced ~2 poll intervals.
        assertEquals(3, stillCurrentCalls)
        assertTrue(clock < 100L)
    }

    @Test
    fun `awaitDrain returns once the target frame count is reached`() {
        var frames = 0L
        SherpaTtsEngine.awaitDrain(
            targetFrames = 100L,
            timeoutMs = 5_000L,
            pollMs = 20L,
            currentFrames = { frames },
            stillCurrent = { true },
            sleep = { frames += 100L }, // each poll "plays" past the target
            now = { 0L },
        )
        assertTrue(frames >= 100L)
    }

    // --- Hotfix APK 345: drain timeout must cover the full 8s track buffer ---

    @Test
    fun `drain cap covers a full track buffer of remaining audio`() {
        // The blocking write returns with up to TRACK_LOOKAHEAD_SECONDS of audio still buffered;
        // the old 5s cap expired mid-tail and the post-drain pause() would cut real speech.
        val bufferFrames = 22_050L * SherpaTtsEngine.TRACK_LOOKAHEAD_SECONDS
        assertEquals(
            SherpaTtsEngine.TRACK_LOOKAHEAD_SECONDS * 1_000L + SherpaTtsEngine.DRAIN_MARGIN_MS,
            SherpaTtsEngine.queueDrainTimeoutMs(targetFrames = bufferFrames, currentFrames = 0L, sampleRate = 22_050),
        )
    }

    @Test
    fun `drain cap covers a full track buffer at half playback speed`() {
        // At the 0.5x floor 8s of buffered source frames sound for ~16s wall-clock; a cap sized
        // for 1.0x would truncate the honest remaining-frames timeout and the post-drain pause()
        // would cut the tail of a slow online reply mid-speech.
        val bufferFrames = 24_000L * SherpaTtsEngine.TRACK_LOOKAHEAD_SECONDS
        val slowRate = SherpaTtsEngine.effectivePlaybackRate(24_000, 0.5f)  // 12_000
        assertEquals(
            SherpaTtsEngine.TRACK_LOOKAHEAD_SECONDS * 2 * 1_000L + SherpaTtsEngine.DRAIN_MARGIN_MS,
            SherpaTtsEngine.queueDrainTimeoutMs(targetFrames = bufferFrames, currentFrames = 0L, sampleRate = slowRate),
        )
    }

    @Test
    fun `queueDrainTimeoutMs does not throw when sampleRate is zero`() {
        SherpaTtsEngine.queueDrainTimeoutMs(targetFrames = 1_000L, currentFrames = 0L, sampleRate = 0)
    }

    // --- Wave K, Task 3: sentence queue for streamed replies ---

    @Test
    fun `startQueue returns null when model not ready`() {
        val mm = mockk<TtsModelManager> { every { isReady(any()) } returns false }
        val engine = SherpaTtsEngine(mm)
        assertNull(engine.startQueue())
    }

    @Test
    fun `enqueue does not bump generation and stop invalidates the queue`() {
        val mm = mockk<TtsModelManager>(relaxed = true) { every { isReady(any()) } returns true }
        val engine = SherpaTtsEngine(mm)
        val queue = engine.startQueue()!!
        val genAfterStart = engine.generationForTest()
        assertTrue(queue.enqueue("Первое предложение."))
        assertTrue(queue.enqueue("Второе предложение."))
        assertEquals(genAfterStart, engine.generationForTest())
        engine.stop()
        assertFalse(queue.enqueue("Третье."))
    }

    @Test
    fun `enqueue blank returns false`() {
        val mm = mockk<TtsModelManager>(relaxed = true) { every { isReady(any()) } returns true }
        val engine = SherpaTtsEngine(mm)
        val queue = engine.startQueue()!!
        assertFalse(queue.enqueue("   "))
    }

    @Test
    fun `queueDrainTimeoutMs returns exactly the margin when playback already caught up`() {
        // Gap-heavy case: currentFrames == targetFrames (LLM paused between sentences, the
        // written audio already fully played). Must never collapse to 0 -- the margin still
        // covers buffer priming for the next enqueue.
        assertEquals(
            SherpaTtsEngine.DRAIN_MARGIN_MS,
            SherpaTtsEngine.queueDrainTimeoutMs(targetFrames = 22_050L, currentFrames = 22_050L, sampleRate = 22_050),
        )
    }

    @Test
    fun `queueDrainTimeoutMs is remaining audio duration plus margin`() {
        assertEquals(
            1_700L,
            SherpaTtsEngine.queueDrainTimeoutMs(targetFrames = 22_050L, currentFrames = 0L, sampleRate = 22_050),
        )
    }

    @Test
    fun `queueDrainTimeoutMs is capped at DRAIN_TIMEOUT_MS`() {
        assertEquals(
            SherpaTtsEngine.DRAIN_TIMEOUT_MS,
            SherpaTtsEngine.queueDrainTimeoutMs(targetFrames = 22_050L * 60, currentFrames = 0L, sampleRate = 22_050),
        )
    }

    @Test
    fun `finish without played audio releases speaking flag`() {
        val mm = mockk<TtsModelManager>(relaxed = true) { every { isReady(any()) } returns true }
        val engine = SherpaTtsEngine(mm)
        val queue = engine.startQueue()!!
        queue.enqueue("Текст.")   // createTts() fails on JVM -> nothing plays, speaking stays false
        queue.finish()
        Thread.sleep(200)          // let the single worker drain its jobs
        assertFalse(engine.speaking.value)
    }

    // --- Task 6: audible() -- mic mute keys off physical playback, not the logical `speaking`
    // flag. speak()/enqueue() never reach real synthesis on the JVM (no native sherpa-onnx lib),
    // so primeAudibleStateForTest() drives the worker-confined track/pendingTargetFrames state
    // directly instead of going through the real (JNI-backed) write path. ---

    private fun fakeTrack(headPosition: Int): AudioTrack = mockk<AudioTrack> {
        every { playbackHeadPosition } returns headPosition
    }

    @Test
    fun `audible is true while written frames have not been played yet`() {
        val mm = mockk<TtsModelManager>(relaxed = true) { every { isReady(any()) } returns true }
        val engine = SherpaTtsEngine(mm)
        engine.primeAudibleStateForTest(track = fakeTrack(headPosition = 400), speaking = true, pendingTargetFrames = 1_000L)
        assertTrue(engine.audible())
    }

    @Test
    fun `audible is false once playback has drained past the pending target`() {
        val mm = mockk<TtsModelManager>(relaxed = true) { every { isReady(any()) } returns true }
        val engine = SherpaTtsEngine(mm)
        engine.primeAudibleStateForTest(track = fakeTrack(headPosition = 1_000), speaking = true, pendingTargetFrames = 1_000L)
        assertFalse(engine.audible())
    }

    @Test
    fun `audible is false between queue sentences once all written frames have played`() {
        // Mirrors the gap between two enqueue()d sentences: speaking stays true (queue still
        // hot) and the previous sentence's positive target is still published, but playback
        // has already caught up to it -- nothing is physically sounding.
        val mm = mockk<TtsModelManager>(relaxed = true) { every { isReady(any()) } returns true }
        val engine = SherpaTtsEngine(mm)
        engine.primeAudibleStateForTest(track = fakeTrack(headPosition = 500), speaking = true, pendingTargetFrames = 500L)
        assertFalse(engine.audible())
    }

    @Test
    fun `audible ignores a target published by a superseded generation`() {
        // A write callback blocked in AudioTrack.write can publish its target after stop() has
        // already superseded it; the generation tag on the target must make audible() reject it.
        val mm = mockk<TtsModelManager>(relaxed = true) { every { isReady(any()) } returns true }
        val engine = SherpaTtsEngine(mm)
        engine.primeAudibleStateForTest(track = fakeTrack(headPosition = 0), speaking = true, pendingTargetFrames = 1_000L)
        assertTrue(engine.audible())
        engine.startQueue()   // bumps the generation without touching speaking or the target
        assertFalse(engine.audible())
    }

    @Test
    fun `audible is false after stop`() {
        val mm = mockk<TtsModelManager>(relaxed = true) { every { isReady(any()) } returns true }
        val engine = SherpaTtsEngine(mm)
        engine.primeAudibleStateForTest(track = fakeTrack(headPosition = 0), speaking = true, pendingTargetFrames = 1_000L)
        assertTrue(engine.audible())
        engine.stop()
        assertFalse(engine.audible())
    }

    // --- Wall-clock audible floor: the DiLink HAL can report a head position AHEAD of the
    // written target (garbage after a flushed-track restart, field bug APK 340 -- ASR heard the
    // agent's own speech). Until the nominal duration of written audio has elapsed, audible()
    // must stay true no matter what the frame comparison says. SystemClock.elapsedRealtime()
    // returns 0 under returnDefaultValues, so any positive floor is "in the future". ---

    @Test
    fun `audible stays true on the clock floor even when the head claims playback drained`() {
        val mm = mockk<TtsModelManager>(relaxed = true) { every { isReady(any()) } returns true }
        val engine = SherpaTtsEngine(mm)
        engine.primeAudibleStateForTest(
            track = fakeTrack(headPosition = 5_000), speaking = true,
            pendingTargetFrames = 1_000L, audibleUntilMs = 60_000L,
        )
        assertTrue(engine.audible())
    }

    @Test
    fun `audible stays true on the clock floor even without a track`() {
        val mm = mockk<TtsModelManager>(relaxed = true) { every { isReady(any()) } returns true }
        val engine = SherpaTtsEngine(mm)
        engine.primeAudibleStateForTest(
            track = null, speaking = true, pendingTargetFrames = -1L, audibleUntilMs = 60_000L,
        )
        assertTrue(engine.audible())
    }

    @Test
    fun `stop zeroes the clock floor`() {
        val mm = mockk<TtsModelManager>(relaxed = true) { every { isReady(any()) } returns true }
        val engine = SherpaTtsEngine(mm)
        engine.primeAudibleStateForTest(
            track = null, speaking = true, pendingTargetFrames = -1L, audibleUntilMs = 60_000L,
        )
        assertTrue(engine.audible())
        engine.stop()
        assertFalse(engine.audible())
    }

    // --- Task 3: prebuffer sentence synthesis -- accumulateSentence stands in for
    // generateWithCallback (never reachable on the JVM without the native lib) so the
    // accumulate-then-write ordering and the mid-synthesis stop case are testable without JNI. ---

    @Test
    fun `accumulateSentence merges all chunks in synthesis order before returning`() {
        val result = SherpaTtsEngine.accumulateSentence(
            generate = { onChunk ->
                onChunk(floatArrayOf(1f, 2f))
                onChunk(floatArrayOf(3f))
                onChunk(floatArrayOf(4f, 5f))
            },
            stillCurrent = { true },
        )
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f, 5f), result, 0f)
    }

    @Test
    fun `no write happens until synthesis has fully returned`() {
        // Pins ordering (a): the caller only ever writes with the value accumulateSentence
        // returns, so nothing resembling a write can happen while chunks are still streaming in.
        val events = mutableListOf<String>()
        val result = SherpaTtsEngine.accumulateSentence(
            generate = { onChunk ->
                events += "chunk"
                onChunk(floatArrayOf(1f))
                events += "chunk"
                onChunk(floatArrayOf(2f))
            },
            stillCurrent = { true },
        )
        events += "write"
        assertEquals(listOf("chunk", "chunk", "write"), events)
        assertArrayEquals(floatArrayOf(1f, 2f), result, 0f)
    }

    @Test
    fun `accumulateSentence returns null when superseded mid-synthesis, so nothing gets written`() {
        var chunkCount = 0
        val result = SherpaTtsEngine.accumulateSentence(
            generate = { onChunk ->
                onChunk(floatArrayOf(1f, 2f)); chunkCount++
                onChunk(floatArrayOf(3f)); chunkCount++
                onChunk(floatArrayOf(4f)); chunkCount++
            },
            stillCurrent = { chunkCount < 2 }, // superseded after the 2nd chunk lands
        )
        assertNull(result)
    }

    @Test
    fun `accumulateSentence returns null when superseded right after synthesis finishes, before the write`() {
        // Pins ordering (b): stop() landing in the gap between "synthesis done" and "write" must
        // still block the write.
        var current = true
        val result = SherpaTtsEngine.accumulateSentence(
            generate = { onChunk -> onChunk(floatArrayOf(1f, 2f)); current = false },
            stillCurrent = { current },
        )
        assertNull(result)
    }

    @Test
    fun `accumulateSentence tells the generation loop to stop as soon as superseded`() {
        var current = true
        val returns = mutableListOf<Int>()
        SherpaTtsEngine.accumulateSentence(
            generate = { onChunk ->
                returns += onChunk(floatArrayOf(1f))
                current = false
                returns += onChunk(floatArrayOf(2f))
            },
            stillCurrent = { current },
        )
        assertEquals(listOf(1, 0), returns)
    }

    // --- Fix round 1: publish audible target/floor BEFORE the blocking write, not after --
    // the track buffer now holds only a fraction of a sentence, so the write blocks for nearly
    // the whole playback duration; publishing afterwards left audible() unmuted-blind for that
    // whole window (field bug: agent heard its own speech back). ---

    @Test
    fun `writeSentence publishes the target and floor before the blocking write begins`() {
        val events = mutableListOf<String>()
        SherpaTtsEngine.writeSentence(
            samples = floatArrayOf(1f, 2f),
            write = { events += "write"; it.size },
            publish = { events += "publish" },
            stillCurrent = { true },
            retract = { events += "retract" },
        )
        assertEquals(listOf("publish", "write"), events)
    }

    @Test
    fun `writeSentence retracts when superseded while the write was blocked`() {
        val events = mutableListOf<String>()
        SherpaTtsEngine.writeSentence(
            samples = floatArrayOf(1f),
            write = { events += "write"; it.size },
            publish = { events += "publish" },
            stillCurrent = { false }, // stop() landed during the write
            retract = { events += "retract" },
        )
        assertEquals(listOf("publish", "write", "retract"), events)
    }

    @Test
    fun `writeSentence does not retract when still current after the write`() {
        val events = mutableListOf<String>()
        SherpaTtsEngine.writeSentence(
            samples = floatArrayOf(1f),
            write = { 1 },
            publish = {},
            stillCurrent = { true },
            retract = { events += "retract" },
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun `writeSentence returns what the write function returned`() {
        val written = SherpaTtsEngine.writeSentence(
            samples = floatArrayOf(1f, 2f, 3f),
            write = { 2 }, // simulates a short write
            publish = {},
            stillCurrent = { true },
            retract = {},
        )
        assertEquals(2, written)
    }

    // --- Task 5: dataDir only for PIPER (espeak-ng-data); VITS_MULTI ships no espeak data ---

    @Test
    fun `dataDirFor points at espeak-ng-data for PIPER voices`() {
        assertEquals("/some/dir/espeak-ng-data", SherpaTtsEngine.dataDirFor(TtsVoiceEngine.PIPER, "/some/dir"))
    }

    @Test
    fun `dataDirFor is empty for VITS_MULTI voices`() {
        assertEquals("", SherpaTtsEngine.dataDirFor(TtsVoiceEngine.VITS_MULTI, "/some/dir"))
    }

    // --- Wave P, Task 6: 8-second AudioTrack lookahead buffer removes inter-sentence pauses ---

    @Test
    fun `track buffer holds the lookahead seconds and never shrinks below 4x min buffer`() {
        // 44.1 kHz float mono: 8 s lookahead = 44100 * 4 * 8 bytes
        assertEquals(1_411_200, SherpaTtsEngine.trackBufferBytes(sampleRate = 44100, minBufBytes = 40_000))
        // Tiny sample rate + huge min buffer: the 4x floor wins
        assertEquals(400_000, SherpaTtsEngine.trackBufferBytes(sampleRate = 8000, minBufBytes = 100_000))
    }

    @Test
    fun `track buffer stays at the old 4x min size when the start threshold cannot be lowered`() {
        // API < 31: the default start threshold equals the full buffer, so an 8 s lookahead
        // buffer would never fill from a short utterance and playback would never begin.
        assertEquals(160_000, SherpaTtsEngine.trackBufferBytes(sampleRate = 44100, minBufBytes = 40_000, thresholdAdjustable = false))
    }

    @Test
    fun `start threshold equals one min buffer of frames`() {
        // Leopard 3 field values: minBuf 7088 bytes, float mono = 4 bytes/frame -> 1772 frames
        // (~80 ms at 22050 Hz) -- below the shortest real utterance, so even a clipped "да"
        // starts playback, while still priming the track against an instant start underrun.
        assertEquals(1_772, SherpaTtsEngine.startThresholdFrames(minBufBytes = 7_088, bytesPerFrame = SherpaTtsEngine.FLOAT_FRAME_BYTES))
    }

    @Test
    fun `start threshold is at least one frame and survives a zero bytesPerFrame`() {
        assertEquals(1, SherpaTtsEngine.startThresholdFrames(minBufBytes = 0, bytesPerFrame = SherpaTtsEngine.FLOAT_FRAME_BYTES))
        assertEquals(1, SherpaTtsEngine.startThresholdFrames(minBufBytes = 1, bytesPerFrame = 0))
    }

    // --- Task 5b: dictionary stress marking uses the style required by each offline engine ---

    @Test
    fun `textForSynthesis marks text for VITS_MULTI voices`() {
        val result = SherpaTtsEngine.textForSynthesis(TtsVoiceEngine.VITS_MULTI, "малина") { text, style ->
            assertEquals("малина", text)
            assertEquals(RuStressMarker.Style.PLUS, style)
            "мал+ина"
        }
        assertEquals("мал+ина", result)
    }

    @Test
    fun `textForSynthesis marks text for SUPERTONIC voices with uppercase style`() {
        val result = SherpaTtsEngine.textForSynthesis(TtsVoiceEngine.SUPERTONIC, "позвонит") { text, style ->
            assertEquals("позвонит", text)
            assertEquals(RuStressMarker.Style.UPPERCASE, style)
            "позвонИт"
        }
        assertEquals("позвонИт", result)
    }

    @Test
    fun `textForSynthesis leaves PIPER voices untouched, never calling the marker`() {
        val result = SherpaTtsEngine.textForSynthesis(
            TtsVoiceEngine.PIPER, "малина",
            mark = { _, _ -> error("marker must not run for PIPER voices") },
        )
        assertEquals("малина", result)
    }

    // --- Task 8: raw PCM playback path for online TTS (Phase D) -- reuses the exact
    // generation/worker/track/publish-before-write machinery as speak(), without any synthesis.
    // createTrack() itself touches real android.media.AudioTrack/AudioFormat.Builder code that
    // NPEs on the JVM stub jar (Builder.setEncoding() returns null under returnDefaultValues), so
    // every test here primes a fake track whose sampleRate already matches the request; the
    // recreate DECISION is exercised separately as the pure shouldRecreateTrack seam. ---

    @Test
    fun `shouldRecreateTrack is true when the sample rate changes`() {
        assertTrue(SherpaTtsEngine.shouldRecreateTrack(currentSampleRate = 22_050, requestedSampleRate = 16_000))
    }

    @Test
    fun `shouldRecreateTrack is false when the sample rate is unchanged`() {
        assertFalse(SherpaTtsEngine.shouldRecreateTrack(currentSampleRate = 16_000, requestedSampleRate = 16_000))
    }

    @Test
    fun `playPcm is a no-op on empty samples`() {
        val mm = mockk<TtsModelManager>(relaxed = true) { every { isReady(any()) } returns true }
        val engine = SherpaTtsEngine(mm)
        assertFalse(engine.playPcm(FloatArray(0), 16_000))
    }

    @Test
    fun `playPcm stamps the audible window matching the samples duration`() {
        val mm = mockk<TtsModelManager>(relaxed = true) { every { isReady(any()) } returns true }
        val engine = SherpaTtsEngine(mm)
        val rate = 16_000
        val samples = FloatArray(1_600) // 100ms at 16kHz
        val track = mockk<AudioTrack>(relaxed = true) {
            every { sampleRate } returns rate
            every { playbackHeadPosition } returns 0
            every { write(any<FloatArray>(), any(), any(), any()) } returns samples.size
        }
        engine.primeAudibleStateForTest(track = track, speaking = false, pendingTargetFrames = -1L)
        assertTrue(engine.playPcm(samples, rate))
        val deadline = System.currentTimeMillis() + 2_000
        var sawAudible = false
        while (System.currentTimeMillis() < deadline) {
            if (engine.audible()) { sawAudible = true; break }
            Thread.sleep(5)
        }
        assertTrue(sawAudible)
    }

    @Test
    fun `playPcm targets cumulative written frames when the previous sentence is still buffered`() {
        // Field defect APK 347: each playPcm() computed its drain/audible target as "playback
        // head at write start + its own samples". With the 8s track buffer the online queue
        // writes sentence N+1 while N's tail is still buffered, so the head lags the total
        // written frames and the target lands short -- the final drain returned early and
        // pause() parked the track with seconds of unplayed tail (reply cut mid-word), which
        // then replayed at the start of the NEXT reply. The target must be the cumulative
        // frames written into the track, not head-relative.
        val mm = mockk<TtsModelManager>(relaxed = true) { every { isReady(any()) } returns true }
        val engine = SherpaTtsEngine(mm)
        val rate = 16_000
        val head = java.util.concurrent.atomic.AtomicInteger(0)
        val track = mockk<AudioTrack>(relaxed = true) {
            every { sampleRate } returns rate
            every { playbackHeadPosition } answers { head.get() }
            every { write(any<FloatArray>(), any(), any(), any()) } answers { arg<FloatArray>(0).size }
        }
        engine.primeAudibleStateForTest(track = track, speaking = false, pendingTargetFrames = -1L)

        assertTrue(engine.playPcm(FloatArray(1_000), rate)) // sentence 1 fully written
        head.set(300)                                       // only 300 of its 1000 frames played yet
        assertTrue(engine.playPcm(FloatArray(500), rate))   // sentence 2 written behind the tail

        // 1500 frames entered the track; a head-relative target stops at 300+500=800 and
        // parks the track with 700 unplayed frames still buffered.
        assertEquals(1_500L, engine.pendingTargetFramesForTest())
        engine.stop() // release the drain wait pinned by the frozen head
    }

    @Test
    fun `stop interrupts playPcm and clears audible`() {
        val mm = mockk<TtsModelManager>(relaxed = true) { every { isReady(any()) } returns true }
        val engine = SherpaTtsEngine(mm)
        val rate = 16_000
        val samples = FloatArray(16_000) // 1s -- long enough that the drain wait is still running
        val track = mockk<AudioTrack>(relaxed = true) {
            every { sampleRate } returns rate
            every { playbackHeadPosition } returns 0 // never advances: drain loop stays blocked until stop()
            every { write(any<FloatArray>(), any(), any(), any()) } returns samples.size
        }
        engine.primeAudibleStateForTest(track = track, speaking = false, pendingTargetFrames = -1L)
        assertTrue(engine.playPcm(samples, rate))
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline && !engine.audible()) Thread.sleep(5)
        assertTrue(engine.audible())
        engine.stop()
        assertFalse(engine.audible())
    }

    @Test
    fun `stop releases the track so the next utterance starts on a fresh one`() {
        // A flushed track restarts with a garbage server-side position on the DiLink HAL, so
        // stop() must not keep it around for frame-position arithmetic.
        val mm = mockk<TtsModelManager>(relaxed = true) { every { isReady(any()) } returns true }
        val engine = SherpaTtsEngine(mm)
        val track = mockk<AudioTrack>(relaxed = true) { every { playbackHeadPosition } returns 0 }
        engine.primeAudibleStateForTest(track = track, speaking = true, pendingTargetFrames = 1_000L)
        engine.stop()
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            runCatching { io.mockk.verify(exactly = 1) { track.release() } }
                .onSuccess { return }
            Thread.sleep(20)
        }
        io.mockk.verify(exactly = 1) { track.release() }
    }

    // --- Final review fix, finding 2: the offline queue's enqueue() reused a non-null track
    // unconditionally, without the sample-rate recreation guard playPcm() already had. Both
    // paths now share ensureTrackForRate() (SherpaTtsEngine.kt), tested directly here rather
    // than through enqueue(): enqueue()'s worker body needs a real, non-null OfflineTts before
    // it reaches this guard, and OfflineTts cannot be constructed or mocked on the JVM unit test
    // stub jar -- its static initializer unconditionally loads the native sherpa-onnx library,
    // which throws on any reference to the class (even mockk's), not just on real synthesis. ---

    @Test
    fun `ensureTrackForRate reuses the current track when the sample rate matches`() {
        val mm = mockk<TtsModelManager>(relaxed = true) { every { isReady(any()) } returns true }
        val engine = SherpaTtsEngine(mm)
        val rate = 16_000
        val track = mockk<AudioTrack>(relaxed = true) { every { sampleRate } returns rate }
        engine.primeAudibleStateForTest(track = track, speaking = false, pendingTargetFrames = -1L)

        val out = engine.ensureTrackForRate(rate)

        assertSame(track, out)
        io.mockk.verify(exactly = 0) { track.release() }
    }

    @Test
    fun `ensureTrackForRate releases the old track and attempts recreation when the sample rate differs`() {
        // createTrack() touches real AudioTrack/AudioFormat.Builder, not constructible on the
        // JVM unit test stub jar (same constraint as the playPcm tests above), so a genuine
        // recreation attempt surfaces here as a thrown exception rather than silently returning
        // the stale-rate track -- which is exactly what enqueue() did before this fix.
        val mm = mockk<TtsModelManager>(relaxed = true) { every { isReady(any()) } returns true }
        val engine = SherpaTtsEngine(mm)
        val track = mockk<AudioTrack>(relaxed = true) { every { sampleRate } returns 16_000 }
        engine.primeAudibleStateForTest(track = track, speaking = false, pendingTargetFrames = -1L)

        val recreateAttempted = runCatching { engine.ensureTrackForRate(24_000) }.isFailure

        assertTrue(recreateAttempted)
        io.mockk.verify(exactly = 1) { track.release() }
    }

    // --- Wave O, Task 5: playbackOutcome -- silence is failure, interruption is not.
    // A pure companion function so it is testable without an AudioTrack or JNI; playPcm wires
    // through it to decide whether TtsRouter should fall back to the offline voice. ---

    @Test
    fun `playbackOutcome is true when all frames were written`() {
        assertTrue(SherpaTtsEngine.playbackOutcome(written = 1_000, expected = 1_000, interrupted = false))
    }

    @Test
    fun `playbackOutcome is false when write is short and playback was not interrupted`() {
        assertFalse(SherpaTtsEngine.playbackOutcome(written = 0, expected = 1_000, interrupted = false))
    }

    @Test
    fun `playbackOutcome is true on interruption regardless of bytes written`() {
        assertTrue(SherpaTtsEngine.playbackOutcome(written = 0, expected = 1_000, interrupted = true))
    }

    // --- Review round 1, Finding 1: pcmWriteTimeoutMs -- bounded write-wait for playPcm ---

    @Test
    fun `pcmWriteTimeoutMs returns floor of 3000 for short audio`() {
        // 160 frames at 16 kHz = 10 ms audio -> 10 + 2000 = 2010 ms < floor
        assertEquals(3_000L, SherpaTtsEngine.pcmWriteTimeoutMs(expectedFrames = 160, sampleRate = 16_000))
    }

    @Test
    fun `pcmWriteTimeoutMs is duration plus 2000 when result exceeds floor`() {
        // 32000 frames at 16 kHz = 2000 ms audio -> 2000 + 2000 = 4000 ms > 3000 floor
        assertEquals(4_000L, SherpaTtsEngine.pcmWriteTimeoutMs(expectedFrames = 32_000, sampleRate = 16_000))
    }

    @Test
    fun `pcmWriteTimeoutMs does not throw when sampleRate is zero`() {
        SherpaTtsEngine.pcmWriteTimeoutMs(expectedFrames = 1_000, sampleRate = 0)
    }

    // --- Review round 1, Finding 3: wiring tests for playPcm honest outcome ---

    @Test
    fun `playPcm returns false on zero write with no interruption`() {
        val mm = mockk<TtsModelManager>(relaxed = true) { every { isReady(any()) } returns true }
        val engine = SherpaTtsEngine(mm)
        val rate = 16_000
        val samples = FloatArray(1_600)
        val track = mockk<AudioTrack>(relaxed = true) {
            every { sampleRate } returns rate
            every { playbackHeadPosition } returns 0
            every { write(any<FloatArray>(), any(), any(), any()) } returns 0
        }
        engine.primeAudibleStateForTest(track = track, speaking = false, pendingTargetFrames = -1L)
        assertFalse(engine.playPcm(samples, rate))
    }

    @Test
    fun `playPcm returns false without hang when AudioTrack write throws`() {
        val mm = mockk<TtsModelManager>(relaxed = true) { every { isReady(any()) } returns true }
        val engine = SherpaTtsEngine(mm)
        val rate = 16_000
        val samples = FloatArray(1_600)
        val track = mockk<AudioTrack>(relaxed = true) {
            every { sampleRate } returns rate
            every { playbackHeadPosition } returns 0
            every { write(any<FloatArray>(), any(), any(), any()) } throws RuntimeException("write failed")
        }
        engine.primeAudibleStateForTest(track = track, speaking = false, pendingTargetFrames = -1L)
        // Must return promptly -- write() throws immediately, so the worker completes the future fast.
        assertFalse(engine.playPcm(samples, rate))
    }

    // Finding 3(c): timeout-rescue test (never-returning write) -- NOT feasible without a seam
    // to inject a small timeout value into playPcm. pcmWriteTimeoutMs has a hard floor of 3000 ms
    // regardless of sample count/rate, so there is no way to drive a sub-second rescue in a unit
    // test without architecture changes (adding an injected-timeout parameter). Skipped.

    // --- Wave O, Task 6: effectivePlaybackRate seam -- speed slider applies to online voice ---
    // PlaybackParams cannot be constructed on the JVM unit-test stub jar, so the application of
    // the params is not directly unit-testable; only the pure numeric seam is tested here.

    @Test
    fun `effectivePlaybackRate clamps speed below minimum to 0_5`() {
        assertEquals(12_000, SherpaTtsEngine.effectivePlaybackRate(24_000, 0.3f))
    }

    @Test
    fun `effectivePlaybackRate clamps speed above maximum to 2_0`() {
        assertEquals(48_000, SherpaTtsEngine.effectivePlaybackRate(24_000, 3.0f))
    }

    @Test
    fun `effectivePlaybackRate passes mid-range speed through unchanged`() {
        assertEquals(36_000, SherpaTtsEngine.effectivePlaybackRate(24_000, 1.5f))
    }

    @Test
    fun `effectivePlaybackRate returns base rate on non-finite input`() {
        assertEquals(24_000, SherpaTtsEngine.effectivePlaybackRate(24_000, Float.NaN))
    }

    @Test
    fun `effectivePlaybackRate returns base rate on zero input`() {
        // 0f is finite but not > 0, so the guard treats it as corrupt and falls back to 1.0x.
        assertEquals(24_000, SherpaTtsEngine.effectivePlaybackRate(24_000, 0f))
    }

    @Test
    fun `pcmWriteTimeoutMs at half playback speed exceeds nominal bound`() {
        // At 0.5x the AudioTrack drains PCM at half speed, so a blocking write on a long
        // sentence legitimately takes ~2x the nominal duration.  pcmWriteTimeoutMs must be
        // called with effectivePlaybackRate so the bound scales accordingly.
        // frames=48000 at base=24000 Hz -> nominal dur=2000 ms -> timeout=max(3000,4000)=4000
        // effective rate at 0.5x = 12000 Hz -> dur=4000 ms -> timeout=max(3000,6000)=6000
        val frames = 48_000
        val base = 24_000
        val slowRate = SherpaTtsEngine.effectivePlaybackRate(base, 0.5f)  // 12_000
        assertEquals(4_000L, SherpaTtsEngine.pcmWriteTimeoutMs(frames, base))
        assertEquals(6_000L, SherpaTtsEngine.pcmWriteTimeoutMs(frames, slowRate))
    }

    @Test
    fun `queueDrainTimeoutMs with effectivePlaybackRate reflects shorter audio at double speed`() {
        // At 2×, 24000 frames play in 500 ms instead of 1000 ms; drain timeout must shrink.
        val effectiveRate = SherpaTtsEngine.effectivePlaybackRate(24_000, 2.0f)
        assertEquals(1_200L, SherpaTtsEngine.queueDrainTimeoutMs(targetFrames = 24_000L, currentFrames = 0L, sampleRate = effectiveRate))
    }

    // --- Fix report (review round 2): writeWaitBoundMs -- pessimistic write-wait bound for
    // playPcm. Root cause: pcmWriteTimeoutMs was called with effectivePlaybackRate(sampleRate, rate()),
    // shortening the bound at 2.0x -- if HAL rejects PlaybackParams, actual playback runs at 1.0x
    // but the rescue timeout was computed for 2.0x (half duration), so a long sentence got a
    // premature stop. writeWaitBoundMs clamps speedup to 1.0x for the bound so the 1.0x case is
    // always covered. The 2.0x-not-shorter test is listed first: it must FAIL on old code that
    // used pcmWriteTimeoutMs(samples.size, effectivePlaybackRate(sampleRate, rate())) directly. ---

    @Test
    fun `writeWaitBoundMs at 2_0x is not shorter than the 1_0x bound`() {
        // If PlaybackParams are rejected by the HAL, playback falls back to 1.0x, so the
        // rescue timeout must cover the full 1.0x duration even when 2.0x was requested.
        // Before the fix: pcmWriteTimeoutMs(48000, 48000) = 3000ms < 1.0x bound of 4000ms.
        val frames = 48_000
        val base = 24_000
        val bound1x = SherpaTtsEngine.writeWaitBoundMs(frames, base, 1.0f)
        val bound2x = SherpaTtsEngine.writeWaitBoundMs(frames, base, 2.0f)
        assertTrue("2.0x bound ($bound2x) must be >= 1.0x bound ($bound1x)", bound2x >= bound1x)
    }

    @Test
    fun `writeWaitBoundMs at 0_5x lengthens the bound to cover slower drain`() {
        // At 0.5x AudioTrack drains PCM at half speed, so the write legitimately takes 2x
        // the nominal duration. 48000 frames at 24kHz = 2000ms nominal; at 0.5x: 4000ms drain
        // -> bound = max(3000, 4000 + 2000) = 6000ms.
        assertEquals(6_000L, SherpaTtsEngine.writeWaitBoundMs(48_000, 24_000, 0.5f))
    }

    @Test
    fun `writeWaitBoundMs at 1_0x equals nominal duration plus 2000`() {
        // 48000 frames at 24kHz = 2000ms -> bound = max(3000, 2000 + 2000) = 4000ms.
        assertEquals(4_000L, SherpaTtsEngine.writeWaitBoundMs(48_000, 24_000, 1.0f))
    }
}
