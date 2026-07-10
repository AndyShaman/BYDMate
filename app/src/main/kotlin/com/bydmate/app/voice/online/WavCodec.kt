package com.bydmate.app.voice.online

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Parses the two PCM shapes online TTS providers hand back: a full RIFF/WAV container
 *  (Gemini/OpenAI) or headerless raw PCM16LE (MiniMax). Both decode to mono float PCM in
 *  [-1, 1], the shape [com.bydmate.app.voice.TtsEngine.playPcm] expects. */
object WavCodec {
    private const val PCM_FORMAT = 1
    private const val PCM16_BITS = 16
    private const val PCM16_MAX = 32_768f

    /** Parses a PCM16 mono/stereo WAV (RIFF) into mono FloatArray [-1,1].
     *  @throws IllegalArgumentException on a malformed or non-PCM16 (e.g. IEEE float) WAV. */
    fun decodeWav(bytes: ByteArray): TtsPcm {
        require(bytes.size >= 12) { "WAV too short" }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(readTag(buf) == "RIFF") { "not a RIFF file" }
        buf.int // overall chunk size, unused
        require(readTag(buf) == "WAVE") { "not a WAVE file" }

        var audioFormat = -1
        var numChannels = -1
        var sampleRate = -1
        var bitsPerSample = -1
        var data: ByteArray? = null

        while (buf.remaining() >= 8) {
            val tag = readTag(buf)
            val size = buf.int
            when (tag) {
                "fmt " -> {
                    audioFormat = buf.short.toInt() and 0xFFFF
                    numChannels = buf.short.toInt() and 0xFFFF
                    sampleRate = buf.int
                    buf.int // byte rate, unused
                    buf.short // block align, unused
                    bitsPerSample = buf.short.toInt() and 0xFFFF
                    skip(buf, size - 16) // any extra fmt fields (e.g. WAVEFORMATEXTENSIBLE)
                }
                "data" -> {
                    data = ByteArray(size)
                    buf.get(data)
                }
                else -> skip(buf, size)
            }
            if (size % 2 != 0) skip(buf, 1) // chunks are word-aligned
        }

        require(audioFormat == PCM_FORMAT) { "unsupported WAV audio format $audioFormat (only PCM16 supported)" }
        require(bitsPerSample == PCM16_BITS) { "unsupported bits per sample $bitsPerSample (only PCM16 supported)" }
        require(numChannels >= 1) { "missing fmt chunk" }
        val samples = requireNotNull(data) { "missing data chunk" }
        return TtsPcm(pcm16ToMonoFloat(samples, numChannels), sampleRate)
    }

    /** Decodes headerless raw PCM16LE mono at [sampleRate] (MiniMax's "pcm" response format). */
    fun decodePcm16(bytes: ByteArray, sampleRate: Int): TtsPcm =
        TtsPcm(pcm16ToMonoFloat(bytes, numChannels = 1), sampleRate)

    private fun readTag(buf: ByteBuffer): String {
        val tag = ByteArray(4)
        buf.get(tag)
        return String(tag, Charsets.US_ASCII)
    }

    private fun skip(buf: ByteBuffer, count: Int) {
        if (count <= 0) return
        buf.position((buf.position() + count).coerceAtMost(buf.limit()))
    }

    /** Averages [numChannels] interleaved int16 samples per frame down to one float per frame. */
    private fun pcm16ToMonoFloat(bytes: ByteArray, numChannels: Int): FloatArray {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val frameCount = (bytes.size / 2) / numChannels
        val out = FloatArray(frameCount)
        for (frame in 0 until frameCount) {
            var sum = 0
            repeat(numChannels) { sum += buf.short.toInt() }
            out[frame] = (sum / numChannels) / PCM16_MAX
        }
        return out
    }
}
