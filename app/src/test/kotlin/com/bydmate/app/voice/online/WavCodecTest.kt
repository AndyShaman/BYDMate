package com.bydmate.app.voice.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

class WavCodecTest {

    private fun leInt(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
    private fun leShort(v: Int) = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()

    private fun buildWav(
        sampleRate: Int,
        numChannels: Int,
        bitsPerSample: Int,
        audioFormat: Int,
        data: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val byteRate = sampleRate * numChannels * bitsPerSample / 8
        val blockAlign = numChannels * bitsPerSample / 8
        val riffSize = 4 + (8 + 16) + (8 + data.size)
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(leInt(riffSize))
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        out.write(leInt(16))
        out.write(leShort(audioFormat))
        out.write(leShort(numChannels))
        out.write(leInt(sampleRate))
        out.write(leInt(byteRate))
        out.write(leShort(blockAlign))
        out.write(leShort(bitsPerSample))
        out.write("data".toByteArray(Charsets.US_ASCII))
        out.write(leInt(data.size))
        out.write(data)
        return out.toByteArray()
    }

    private fun sinePcm16(sampleCount: Int): ByteArray {
        val buf = ByteBuffer.allocate(sampleCount * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sampleCount) {
            buf.putShort((sin(2.0 * PI * i / sampleCount) * 32_000).roundToInt().toShort())
        }
        return buf.array()
    }

    @Test
    fun `decodeWav parses a mono PCM16 sine wave`() {
        val wav = buildWav(sampleRate = 16_000, numChannels = 1, bitsPerSample = 16, audioFormat = 1, data = sinePcm16(100))
        val pcm = WavCodec.decodeWav(wav)
        assertEquals(16_000, pcm.sampleRate)
        assertEquals(100, pcm.samples.size)
        assertTrue(pcm.samples.all { it in -1f..1f })
        // Sample 25 of a 100-sample sine cycle sits near the +peak (sin(pi/2) ~ 1).
        assertTrue(pcm.samples[25] > 0.9f)
    }

    @Test
    fun `decodeWav averages stereo channels down to mono`() {
        val frames = 10
        val buf = ByteBuffer.allocate(frames * 2 * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) { buf.putShort(16_000); buf.putShort(-16_000) } // L/R symmetric -> avg ~0
        val wav = buildWav(sampleRate = 22_050, numChannels = 2, bitsPerSample = 16, audioFormat = 1, data = buf.array())
        val pcm = WavCodec.decodeWav(wav)
        assertEquals(22_050, pcm.sampleRate)
        assertEquals(frames, pcm.samples.size)
        assertTrue(pcm.samples.all { abs(it) < 0.001f })
    }

    @Test
    fun `decodeWav rejects IEEE float WAV`() {
        val wav = buildWav(sampleRate = 16_000, numChannels = 1, bitsPerSample = 32, audioFormat = 3, data = ByteArray(16))
        assertThrows(IllegalArgumentException::class.java) { WavCodec.decodeWav(wav) }
    }

    @Test
    fun `decodeWav rejects a non-RIFF file`() {
        assertThrows(IllegalArgumentException::class.java) { WavCodec.decodeWav(ByteArray(20)) }
    }

    @Test
    fun `decodePcm16 wraps raw PCM16LE mono bytes at the given sample rate`() {
        val pcm = WavCodec.decodePcm16(sinePcm16(50), sampleRate = 24_000)
        assertEquals(24_000, pcm.sampleRate)
        assertEquals(50, pcm.samples.size)
        assertTrue(pcm.samples.all { it in -1f..1f })
    }
}
