package com.bydmate.app.voice.online

import com.bydmate.app.agent.LlmConnection
import com.bydmate.app.agent.LlmConnectionResolver
import com.bydmate.app.voice.TtsGender
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class OpenRouterTtsBackendTest {

    private lateinit var server: MockWebServer
    private val connections = mockk<LlmConnectionResolver>()
    private lateinit var backend: OpenRouterTtsBackend

    @Before fun setUp() {
        server = MockWebServer(); server.start()
        backend = OpenRouterTtsBackend(
            id = "gemini",
            model = "google/gemini-3.1-flash-tts-preview",
            maleVoice = "Charon",
            femaleVoice = "Kore",
            http = OkHttpClient(),
            connections = connections,
        )
    }

    @After fun tearDown() { server.shutdown() }

    private fun stubConnection(
        baseUrl: String = server.url("/api/v1").toString().trimEnd('/'),
        apiKey: String = "sk-test",
    ) {
        coEvery { connections.get(LlmConnectionResolver.ID_OPENROUTER) } returns
            LlmConnection(LlmConnectionResolver.ID_OPENROUTER, "OpenRouter", baseUrl, apiKey, "unused-model")
    }

    private fun leInt(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
    private fun leShort(v: Int) = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()

    /** Raw headerless PCM16LE mono -- [numSamples] int16 frames; 200 frames = 400 bytes > MIN_AUDIO_BYTES. */
    private fun rawPcm16(numSamples: Int = 200): ByteArray {
        val buf = ByteBuffer.allocate(numSamples * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(numSamples) { i -> buf.putShort((i * 100 % Short.MAX_VALUE).toShort()) }
        return buf.array()
    }

    /** Minimal valid mono PCM16 WAV (16kHz, 2 samples) -- just enough for WavCodec.decodeWav. */
    private fun minimalWav(): ByteArray {
        val data = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(1000).putShort(-1000).array()
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(leInt(4 + (8 + 16) + (8 + data.size)))
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        out.write(leInt(16))
        out.write(leShort(1)) // PCM
        out.write(leShort(1)) // mono
        out.write(leInt(16_000))
        out.write(leInt(16_000 * 2))
        out.write(leShort(2))
        out.write(leShort(16))
        out.write("data".toByteArray(Charsets.US_ASCII))
        out.write(leInt(data.size))
        out.write(data)
        return out.toByteArray()
    }

    @Test
    fun `configured is true when OpenRouter connection is resolved`() = runTest {
        stubConnection()
        assertTrue(backend.configured())
    }

    @Test
    fun `configured is false when OpenRouter is not set up`() = runTest {
        coEvery { connections.get(LlmConnectionResolver.ID_OPENROUTER) } returns null
        assertFalse(backend.configured())
    }

    @Test
    fun `synthesize posts model, male voice and pcm format to audio-speech`() = runTest {
        stubConnection(apiKey = "sk-abc")
        server.enqueue(MockResponse()
            .addHeader("Content-Type", "audio/pcm;rate=24000;channels=1")
            .setBody(Buffer().write(rawPcm16())))
        backend.synthesize("привет", TtsGender.MALE)
        val req = server.takeRequest()
        assertEquals("/api/v1/audio/speech", req.path)
        assertEquals("Bearer sk-abc", req.getHeader("Authorization"))
        val sent = JSONObject(req.body.readUtf8())
        assertEquals("google/gemini-3.1-flash-tts-preview", sent.getString("model"))
        assertEquals("привет", sent.getString("input"))
        assertEquals("Charon", sent.getString("voice"))
        assertEquals("pcm", sent.getString("response_format"))
    }

    @Test
    fun `synthesize uses the female voice for FEMALE gender`() = runTest {
        stubConnection()
        server.enqueue(MockResponse()
            .addHeader("Content-Type", "audio/pcm;rate=24000;channels=1")
            .setBody(Buffer().write(rawPcm16())))
        backend.synthesize("привет", TtsGender.FEMALE)
        val sent = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("Kore", sent.getString("voice"))
    }

    @Test
    fun `synthesize decodes a pcm response into TtsPcm`() = runTest {
        stubConnection()
        server.enqueue(MockResponse()
            .addHeader("Content-Type", "audio/pcm;rate=22050;channels=1")
            .setBody(Buffer().write(rawPcm16(200))))
        val pcm = backend.synthesize("привет", TtsGender.MALE)
        assertEquals(22_050, pcm.sampleRate)
        assertEquals(200, pcm.samples.size)
    }

    @Test
    fun `non-2xx response throws with the http code and error body in the message`() = runTest {
        stubConnection()
        server.enqueue(MockResponse()
            .setResponseCode(400)
            .setBody("""{"error":{"message":"ZodError: invalid response_format"}}"""))
        try {
            backend.synthesize("привет", TtsGender.MALE)
            fail("expected synthesize to throw")
        } catch (e: IOException) {
            assertTrue("message should contain 400", e.message!!.contains("400"))
            assertTrue("message should contain ZodError", e.message!!.contains("ZodError"))
        }
    }

    @Test
    fun `synthesize throws without a network call when OpenRouter is not configured`() = runTest {
        coEvery { connections.get(LlmConnectionResolver.ID_OPENROUTER) } returns null
        try {
            backend.synthesize("привет", TtsGender.MALE)
            fail("expected synthesize to throw")
        } catch (e: IOException) {
            // expected
        }
        assertEquals(0, server.requestCount)
    }

    // --- TDD tests for pcm format, rate header, empty-audio guard ---

    @Test
    fun `synthesize requests pcm format`() = runTest {
        stubConnection()
        server.enqueue(MockResponse()
            .addHeader("Content-Type", "audio/pcm;rate=24000;channels=1")
            .setBody(Buffer().write(rawPcm16())))
        backend.synthesize("тест", TtsGender.MALE)
        val sent = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("pcm", sent.getString("response_format"))
    }

    @Test
    fun `synthesize parses rate from content type`() = runTest {
        stubConnection()
        server.enqueue(MockResponse()
            .addHeader("Content-Type", "audio/pcm;rate=22050;channels=1")
            .setBody(Buffer().write(rawPcm16())))
        val pcm = backend.synthesize("тест", TtsGender.MALE)
        assertEquals(22_050, pcm.sampleRate)
    }

    @Test
    fun `synthesize parses rate when spaces surround equals in content type`() = runTest {
        stubConnection()
        server.enqueue(MockResponse()
            .addHeader("Content-Type", "audio/pcm; rate = 22050; channels=1")
            .setBody(Buffer().write(rawPcm16())))
        val pcm = backend.synthesize("тест", TtsGender.MALE)
        assertEquals(22_050, pcm.sampleRate)
    }

    @Test
    fun `synthesize defaults to 24000 without rate`() = runTest {
        stubConnection()
        server.enqueue(MockResponse()
            .addHeader("Content-Type", "application/octet-stream")
            .setBody(Buffer().write(rawPcm16())))
        val pcm = backend.synthesize("тест", TtsGender.MALE)
        assertEquals(24_000, pcm.sampleRate)
    }

    @Test
    fun `synthesize throws on empty audio body`() = runTest {
        stubConnection()
        val tiny = ByteArray(100) { it.toByte() }
        server.enqueue(MockResponse()
            .addHeader("Content-Type", "audio/pcm;rate=24000;channels=1")
            .setBody(Buffer().write(tiny)))
        try {
            backend.synthesize("тест", TtsGender.MALE)
            fail("expected IOException for audio smaller than MIN_AUDIO_BYTES")
        } catch (e: IOException) {
            assertTrue("message should mention 'empty audio'", e.message!!.contains("empty audio"))
        }
    }
}
