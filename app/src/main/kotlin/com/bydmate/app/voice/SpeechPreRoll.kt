package com.bydmate.app.voice

internal class SpeechPreRoll(sampleCount: Int) {
    private val ring = FloatArray(sampleCount)
    private var position = 0L
    private var retained = 0
    private var consumedThrough = 0L
    private var prefix = FloatArray(0)
    private var prefixStart = 0L

    fun append(frame: FloatArray) {
        if (ring.isEmpty()) return
        val offset = maxOf(0, frame.size - ring.size)
        val start = ((position + offset) % ring.size).toInt()
        val first = minOf(frame.size - offset, ring.size - start)
        frame.copyInto(ring, start, offset, offset + first)
        frame.copyInto(ring, 0, offset + first, frame.size)
        position += frame.size
        retained = minOf(ring.size, retained + frame.size)
    }

    fun speechStart() {
        val count = minOf(retained, (position - consumedThrough).coerceIn(0L, ring.size.toLong()).toInt())
        prefixStart = position - count
        prefix = FloatArray(count) { ring[((prefixStart + it) % ring.size).toInt()] }
    }

    fun finish(segmentStart: Long, segment: FloatArray): FloatArray {
        // Native start includes VAD onset padding; prepend only samples before it.
        val count = (segmentStart - prefixStart).coerceIn(0L, prefix.size.toLong()).toInt()
        val result = if (count == 0) segment else FloatArray(count + segment.size).also {
            prefix.copyInto(it, endIndex = count)
            segment.copyInto(it, count)
        }
        consumedThrough = maxOf(consumedThrough, segmentStart + segment.size)
        prefix = FloatArray(0)
        return result
    }
}
