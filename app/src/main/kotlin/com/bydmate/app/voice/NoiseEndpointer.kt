package com.bydmate.app.voice

import kotlin.math.sqrt

/**
 * End of a phrase in road noise, for the case Silero VAD misses it (field log 2026-11-15,
 * DiLink 3 at speed): tyre and wind noise keep the VAD in "speech" long after the driver
 * stopped talking, so the segment never closes, nothing reaches GigaAM and the session ends
 * without a command.
 *
 * Works on loudness relative to the cabin's own noise floor, never on an absolute level. The
 * floor is a low percentile of the recent frames that are not clearly louder than it, so the
 * driver's voice never lifts it however long they talk. Only acts when the floor is high (a
 * noisy cabin) and the VAD is still in speech: once the run since the VAD went into speech
 * holds enough frames clearly louder than the floor (words), a tail of frames back at the floor
 * ends the phrase ([Decision.FLUSH]: the caller flushes the VAD so the segment is decoded). The
 * loud frames are counted against the floor known at the tail, so words spoken right after the
 * button press, before the floor was measured, still count. A quiet cabin keeps the VAD's own
 * end-pointing unchanged. No length limit is imposed on a phrase.
 *
 * Pure: frame loudness in, decision out.
 */
internal class NoiseEndpointer(
    /** Below this floor (RMS of 16-bit PCM) the cabin counts as quiet: parked or slow, ~60. */
    private val noisyFloorRms: Double = NOISY_FLOOR_RMS,
    /** A frame this many times the floor is the driver's voice over the noise. */
    private val loudRatio: Double = 1.35,
    /** A frame below this many times the floor is the noise alone. */
    private val quietRatio: Double = 1.15,
    private val minLoudMs: Long = 400,
    /** Same as the VAD's own minimum silence, so a natural pause inside a phrase does not split it. */
    private val quietTailMs: Long = 800,
) {
    enum class Decision { NONE, FLUSH }

    private val floorHistory = ArrayDeque<Double>()
    private val run = ArrayDeque<Double>()   // frames since the VAD went into speech
    private var quietMs = 0L

    /** The current noise floor estimate (RMS); 0 before the first frame. */
    var floor: Double = 0.0
        private set

    /** Loud speech in the phrase that was ended last, for the log. */
    var lastLoudMs: Long = 0
        private set

    val noisy: Boolean get() = floorHistory.size >= MIN_FLOOR_FRAMES && floor >= noisyFloorRms

    fun onFrame(rms: Double, frameMs: Long, vadInSpeech: Boolean): Decision {
        // A read of a few samples rounds to 0 ms: it carries no time, and the tail math divides by it.
        if (frameMs <= 0) return Decision.NONE
        // Words do not move the floor: only frames not clearly louder than it are kept.
        if (floorHistory.size < MIN_FLOOR_FRAMES || rms < floor * loudRatio) {
            floorHistory.addLast(rms)
            if (floorHistory.size > FLOOR_FRAMES) floorHistory.removeFirst()
            floor = lowPercentile(floorHistory)
        }
        if (!vadInSpeech) {
            run.clear()
            quietMs = 0
            return Decision.NONE
        }
        run.addLast(rms)
        if (run.size > MAX_RUN_FRAMES) run.removeFirst()
        quietMs = if (rms < floor * quietRatio) quietMs + frameMs else 0
        if (!noisy || quietMs < quietTailMs) return Decision.NONE

        val tailFrames = (quietTailMs / frameMs).toInt()
        val loudMs = run.take((run.size - tailFrames).coerceAtLeast(0))
            .count { it >= floor * loudRatio } * frameMs
        if (loudMs < minLoudMs) return Decision.NONE
        lastLoudMs = loudMs
        run.clear()
        quietMs = 0
        return Decision.FLUSH
    }

    companion object {
        /** Quiet cabin measured at ~60 RMS, highway at ~420 (2026-11-15 log). */
        const val NOISY_FLOOR_RMS = 180.0
        /** Noise frames the floor is taken from (~5 s at 100 ms frames). */
        private const val FLOOR_FRAMES = 50
        /** Frames before the cabin can count as noisy (~1 s). */
        private const val MIN_FLOOR_FRAMES = 10
        /** Frames of one VAD speech run kept for the loud count (~60 s). */
        private const val MAX_RUN_FRAMES = 600

        /** 20th percentile: the noise between words, not the words. */
        internal fun lowPercentile(values: Collection<Double>): Double {
            if (values.isEmpty()) return 0.0
            val sorted = values.sorted()
            return sorted[((sorted.size - 1) * 0.2).toInt()]
        }

        fun rms(samples: ShortArray): Double {
            if (samples.isEmpty()) return 0.0
            var sum = 0.0
            for (s in samples) sum += s.toDouble() * s
            return sqrt(sum / samples.size)
        }
    }
}
