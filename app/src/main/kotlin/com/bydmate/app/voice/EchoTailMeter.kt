package com.bydmate.app.voice

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Measurement only: how long the agent's own voice keeps reaching the mic after playback ends
 * (speaker latency, cabin reverberation), so the playback gate's echo grace can be set from a
 * field log instead of a guess. Fed every captured frame on the capture thread, it never changes
 * which frames reach the recognizer. After the last audible frame it keeps the levels of the
 * frames captured within [windowMs], and returns one log line when that window closes; playback
 * resuming inside the window starts it over. Not thread-safe: one capture thread.
 */
internal class EchoTailMeter(private val windowMs: Long = WINDOW_MS) {

    private var lastAudibleMs = 0L
    private var armed = false
    private val frames = ArrayList<TailFrame>()

    /** The "echo tail:" log line when this frame closes the window, null otherwise. */
    fun onFrame(nowMs: Long, audible: Boolean, pcm: ShortArray): String? {
        if (audible) {
            lastAudibleMs = nowMs
            armed = true
            frames.clear()
            return null
        }
        if (!armed) return null
        val offsetMs = nowMs - lastAudibleMs
        if (offsetMs <= windowMs) frames += TailFrame(offsetMs, frameRms(pcm))
        if (offsetMs < windowMs) return null
        armed = false
        return echoTailLine(frames, windowMs)
    }

    private companion object {
        const val WINDOW_MS = 1_000L
    }
}

/** A frame captured [offsetMs] after the last audible one, and its level. */
internal class TailFrame(val offsetMs: Long, val rms: Double)

// The floor sits 6 dB over the quietest frame of the window, plus a little for a digitally
// silent mic: frame-to-frame variation of steady road noise stays under it.
private const val FLOOR_FACTOR = 2.0
private const val FLOOR_MARGIN = 20.0

/** Quiet = the first frame from which every later one stays at or below the floor; a window
 *  whose last frame is still louder never settled (the driver is likely talking). */
internal fun echoTailLine(frames: List<TailFrame>, windowMs: Long): String {
    if (frames.isEmpty()) return "echo tail: no frames in ${windowMs}ms"
    val floor = frames.minOf { it.rms } * FLOOR_FACTOR + FLOOR_MARGIN
    val lastLoud = frames.indexOfLast { it.rms > floor }
    if (lastLoud == frames.lastIndex) return "echo tail: not settled in ${windowMs}ms (speech?)"
    val peak = frames.maxOf { it.rms }
    return "echo tail: quiet at +${frames[lastLoud + 1].offsetMs}ms " +
        "(floor=${floor.roundToInt()} peak=${peak.roundToInt()})"
}

/** RMS of a PCM16 frame on the int16 scale; 0 for an empty frame. */
internal fun frameRms(pcm: ShortArray): Double {
    if (pcm.isEmpty()) return 0.0
    var sum = 0.0
    for (s in pcm) sum += s.toDouble() * s
    return sqrt(sum / pcm.size)
}
