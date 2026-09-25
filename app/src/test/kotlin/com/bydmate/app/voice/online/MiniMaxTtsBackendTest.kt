package com.bydmate.app.voice.online

import android.util.Log
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.voice.TtsGender
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class MiniMaxTtsBackendTest {

    private lateinit var official: MockWebServer
    private lateinit var fal: MockWebServer
    private lateinit var replicate: MockWebServer
    private val settingsRepository = mockk<SettingsRepository>()
    private lateinit var backend: MiniMaxTtsBackend

    @Before fun setUp() {
        official = MockWebServer(); official.start()
        fal = MockWebServer(); fal.start()
        replicate = MockWebServer(); replicate.start()
        backend = MiniMaxTtsBackend(
            http = OkHttpClient(),
            settingsRepository = settingsRepository,
            officialBaseUrl = official.url("/").toString().trimEnd('/'),
            falBaseUrl = fal.url("/").toString().trimEnd('/'),
            replicateBaseUrl = replicate.url("/").toString().trimEnd('/'),
        )
    }

    @After fun tearDown() {
        official.shutdown(); fal.shutdown(); replicate.shutdown()
    }

    private fun stubSettings(key: String = "mm-test-key", provider: String = "official") {
        coEvery { settingsRepository.getString(SettingsRepository.KEY_MINIMAX_TTS_KEY, "") } returns key
        coEvery {
            settingsRepository.getString(SettingsRepository.KEY_MINIMAX_TTS_PROVIDER, "official")
        } returns provider
    }

    private fun leInt(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
    private fun leShort(v: Int) = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()

    /** Minimal valid mono PCM16 WAV (16kHz, 2 samples) -- just enough for WavCodec.decodeWav. */
    private fun minimalWav(): ByteArray {
        val data = minimalPcm()
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

    /** Two PCM16LE samples (1000, -1000) -- the headerless payload MiniMax's official transport hex-encodes. */
    private fun minimalPcm(): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putShort(1000).putShort(-1000).array()

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun officialSuccessBody(audioHex: String = hex(minimalPcm())) = JSONObject().apply {
        put("data", JSONObject().put("audio", audioHex))
        put("base_resp", JSONObject().apply { put("status_code", 0); put("status_msg", "success") })
    }.toString()

    // --- configured() ---

    @Test
    fun `configured is true when the MiniMax key is set`() = runTest {
        coEvery { settingsRepository.getString(SettingsRepository.KEY_MINIMAX_TTS_KEY, "") } returns "mm-key"
        assertTrue(backend.configured())
    }

    @Test
    fun `configured is false when the MiniMax key is blank`() = runTest {
        coEvery { settingsRepository.getString(SettingsRepository.KEY_MINIMAX_TTS_KEY, "") } returns ""
        assertFalse(backend.configured())
    }

    // --- official transport ---

    @Test
    fun `official transport posts model, voice and pcm audio_setting to t2a_v2, decodes hex pcm`() = runTest {
        stubSettings(provider = "official")
        official.enqueue(MockResponse().setBody(officialSuccessBody()))

        val pcm = backend.synthesize("привет", TtsGender.MALE)

        val req = official.takeRequest()
        assertEquals("/v1/t2a_v2", req.path)
        assertEquals("Bearer mm-test-key", req.getHeader("Authorization"))
        val sent = JSONObject(req.body.readUtf8())
        assertEquals("speech-2.8-turbo", sent.getString("model"))
        assertEquals("привет", sent.getString("text"))
        assertEquals("Russian_ReliableMan", sent.getJSONObject("voice_setting").getString("voice_id"))
        val audioSetting = sent.getJSONObject("audio_setting")
        assertEquals("pcm", audioSetting.getString("format"))
        assertEquals(24_000, audioSetting.getInt("sample_rate"))
        assertEquals(1, audioSetting.getInt("channel"))

        assertEquals(24_000, pcm.sampleRate)
        assertEquals(2, pcm.samples.size)
    }

    @Test
    fun `official transport boosts Russian for Cyrillic text and leaves other text as before`() = runTest {
        stubSettings(provider = "official")
        official.enqueue(MockResponse().setBody(officialSuccessBody()))
        official.enqueue(MockResponse().setBody(officialSuccessBody()))

        backend.synthesize("Заряд 80 процентов", TtsGender.MALE)
        backend.synthesize("Battery at 80 percent", TtsGender.MALE)

        assertEquals("Russian", JSONObject(official.takeRequest().body.readUtf8()).getString("language_boost"))
        assertFalse(JSONObject(official.takeRequest().body.readUtf8()).has("language_boost"))
    }

    @Test
    fun `official transport sends no language_boost for Belarusian or Ukrainian Cyrillic`() = runTest {
        stubSettings(provider = "official")
        official.enqueue(MockResponse().setBody(officialSuccessBody()))
        official.enqueue(MockResponse().setBody(officialSuccessBody()))

        backend.synthesize("Зарад восемдзесят працэнтаў", TtsGender.MALE)
        backend.synthesize("Заряд вісімдесят відсотків", TtsGender.MALE)

        assertFalse(JSONObject(official.takeRequest().body.readUtf8()).has("language_boost"))
        assertFalse(JSONObject(official.takeRequest().body.readUtf8()).has("language_boost"))
    }

    @Test
    fun `official transport uses the female voice for FEMALE gender`() = runTest {
        stubSettings(provider = "official")
        official.enqueue(MockResponse().setBody(officialSuccessBody()))
        backend.synthesize("привет", TtsGender.FEMALE)
        val sent = JSONObject(official.takeRequest().body.readUtf8())
        assertEquals("Russian_BrightHeroine", sent.getJSONObject("voice_setting").getString("voice_id"))
    }

    @Test
    fun `official transport throws with status_msg when status_code is non-zero`() = runTest {
        stubSettings(provider = "official")
        val body = JSONObject().apply {
            put("base_resp", JSONObject().apply {
                put("status_code", 1002)
                put("status_msg", "rate limited")
            })
        }
        official.enqueue(MockResponse().setBody(body.toString()))
        try {
            backend.synthesize("привет", TtsGender.MALE)
            fail("expected synthesize to throw")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("rate limited"))
        }
    }

    @Test
    fun `official transport throws IOException when audio hex has odd length`() = runTest {
        stubSettings(provider = "official")
        official.enqueue(MockResponse().setBody(officialSuccessBody(audioHex = "abc")))
        try {
            backend.synthesize("привет", TtsGender.MALE)
            fail("expected synthesize to throw")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("odd length"))
        }
    }

    @Test
    fun `official transport throws IOException when audio hex contains non-hex characters`() = runTest {
        stubSettings(provider = "official")
        official.enqueue(MockResponse().setBody(officialSuccessBody(audioHex = "zzzz")))
        try {
            backend.synthesize("привет", TtsGender.MALE)
            fail("expected synthesize to throw")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("invalid character"))
        }
    }

    // --- fal transport ---

    @Test
    fun `fal transport posts voice_setting and wav output_format, follows audio-url, decodes wav`() = runTest {
        stubSettings(provider = "fal")
        fal.enqueue(
            MockResponse().setBody(
                JSONObject().put("audio", JSONObject().put("url", fal.url("/files/out.wav").toString())).toString(),
            ),
        )
        fal.enqueue(MockResponse().setBody(Buffer().write(minimalWav())))

        val pcm = backend.synthesize("привет", TtsGender.MALE)

        val postReq = fal.takeRequest()
        assertEquals("/fal-ai/minimax/speech-2.8-turbo", postReq.path)
        assertEquals("Key mm-test-key", postReq.getHeader("Authorization"))
        val sent = JSONObject(postReq.body.readUtf8())
        assertEquals("привет", sent.getString("text"))
        assertEquals("Russian_ReliableMan", sent.getJSONObject("voice_setting").getString("voice_id"))
        assertEquals("wav", sent.getString("output_format"))

        val audioReq = fal.takeRequest()
        assertEquals("/files/out.wav", audioReq.path)

        assertEquals(16_000, pcm.sampleRate)
    }

    @Test
    fun `fal transport throws unsupported format when downloaded bytes are not RIFF`() = runTest {
        stubSettings(provider = "fal")
        fal.enqueue(
            MockResponse().setBody(
                JSONObject().put("audio", JSONObject().put("url", fal.url("/files/out.mp3").toString())).toString(),
            ),
        )
        fal.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(0x49, 0x44, 0x33)))) // "ID3" mp3 tag

        try {
            backend.synthesize("привет", TtsGender.MALE)
            fail("expected synthesize to throw")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("unsupported format"))
        }
    }

    // --- replicate transport ---

    @Test
    fun `replicate transport posts input text-voice_id with Prefer wait, follows output url, decodes wav`() = runTest {
        stubSettings(provider = "replicate")
        replicate.enqueue(
            MockResponse().setBody(
                JSONObject().put("output", replicate.url("/files/out.wav").toString()).toString(),
            ),
        )
        replicate.enqueue(MockResponse().setBody(Buffer().write(minimalWav())))

        val pcm = backend.synthesize("привет", TtsGender.FEMALE)

        val postReq = replicate.takeRequest()
        assertEquals("/v1/models/minimax/speech-2.8-turbo/predictions", postReq.path)
        assertEquals("Bearer mm-test-key", postReq.getHeader("Authorization"))
        assertEquals("wait", postReq.getHeader("Prefer"))
        val sent = JSONObject(postReq.body.readUtf8())
        val input = sent.getJSONObject("input")
        assertEquals("привет", input.getString("text"))
        assertEquals("Russian_BrightHeroine", input.getString("voice_id"))

        val audioReq = replicate.takeRequest()
        assertEquals("/files/out.wav", audioReq.path)

        assertEquals(16_000, pcm.sampleRate)
    }

    @Test
    fun `replicate transport throws unsupported format when downloaded bytes are not RIFF`() = runTest {
        stubSettings(provider = "replicate")
        replicate.enqueue(
            MockResponse().setBody(
                JSONObject().put("output", replicate.url("/files/out.mp3").toString()).toString(),
            ),
        )
        replicate.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(0x49, 0x44, 0x33))))

        try {
            backend.synthesize("привет", TtsGender.MALE)
            fail("expected synthesize to throw")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("unsupported format"))
        }
    }

    // --- transport selection by pref ---

    @Test
    fun `defaults to the official transport when the provider pref is unset`() = runTest {
        coEvery { settingsRepository.getString(SettingsRepository.KEY_MINIMAX_TTS_KEY, "") } returns "mm-test-key"
        coEvery {
            settingsRepository.getString(SettingsRepository.KEY_MINIMAX_TTS_PROVIDER, any())
        } answers { secondArg() }
        official.enqueue(MockResponse().setBody(officialSuccessBody()))

        backend.synthesize("привет", TtsGender.MALE)

        assertEquals("/v1/t2a_v2", official.takeRequest().path)
        assertEquals(0, fal.requestCount)
        assertEquals(0, replicate.requestCount)
    }

    @Test
    fun `provider pref fal routes to the fal transport only`() = runTest {
        stubSettings(provider = "fal")
        fal.enqueue(
            MockResponse().setBody(
                JSONObject().put("audio", JSONObject().put("url", fal.url("/files/out.wav").toString())).toString(),
            ),
        )
        fal.enqueue(MockResponse().setBody(Buffer().write(minimalWav())))

        backend.synthesize("привет", TtsGender.MALE)

        assertEquals(0, official.requestCount)
        assertEquals(0, replicate.requestCount)
    }

    // --- official streaming (SSE) ---

    private fun pcm16(vararg values: Int): ByteArray =
        ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            .apply { values.forEach { putShort(it.toShort()) } }.array()

    private fun sseEvent(audioHex: String, status: Int = 1, statusCode: Int = 0, statusMsg: String = "") =
        "data: " + JSONObject().apply {
            put("data", JSONObject().put("audio", audioHex).put("status", status))
            put("base_resp", JSONObject().put("status_code", statusCode).put("status_msg", statusMsg))
        } + "\n\n"

    private fun sse(vararg events: String) =
        MockResponse().setHeader("Content-Type", "text/event-stream").setBody(events.joinToString(""))

    private suspend fun collectStream(text: String = "привет"): List<FloatArray> {
        val chunks = mutableListOf<FloatArray>()
        backend.synthesizeStream(text, TtsGender.MALE) { chunks += it }
        return chunks
    }

    @Test
    fun `stream request carries stream, exclude_aggregated_audio and language_boost`() = runTest {
        stubSettings(provider = "official")
        official.enqueue(sse(sseEvent(hex(pcm16(1000))), sseEvent("", status = 2)))

        collectStream("Заряд 80 процентов")

        val req = official.takeRequest()
        assertEquals("/v1/t2a_v2", req.path)
        assertEquals("Bearer mm-test-key", req.getHeader("Authorization"))
        val sent = JSONObject(req.body.readUtf8())
        assertTrue(sent.getBoolean("stream"))
        assertTrue(sent.getJSONObject("stream_options").getBoolean("exclude_aggregated_audio"))
        assertEquals("Russian", sent.getString("language_boost"))
        assertEquals("speech-2.8-turbo", sent.getString("model"))
        assertEquals("pcm", sent.getJSONObject("audio_setting").getString("format"))
    }

    @Test
    fun `stream delivers decoded chunks in order and never the final aggregated audio`() = runTest {
        stubSettings(provider = "official")
        official.enqueue(
            sse(
                sseEvent(hex(pcm16(1000, -1000))),
                sseEvent(hex(pcm16(2000))),
                sseEvent(hex(pcm16(1000, -1000, 2000)), status = 2), // whole sentence again
            ),
        )

        val chunks = collectStream()

        assertEquals(2, chunks.size)
        assertArrayEquals(floatArrayOf(1000 / 32_768f, -1000 / 32_768f), chunks[0], 0f)
        assertArrayEquals(floatArrayOf(2000 / 32_768f), chunks[1], 0f)
    }

    @Test
    fun `stream carries an odd trailing byte over so a split sample decodes intact`() = runTest {
        stubSettings(provider = "official")
        val bytes = pcm16(1000, -1000, 3000)
        official.enqueue(
            sse(
                sseEvent(hex(bytes.copyOfRange(0, 3))),
                sseEvent(hex(bytes.copyOfRange(3, 6))),
                sseEvent("", status = 2),
            ),
        )

        val samples = collectStream().flatMap { it.toList() }

        assertEquals(listOf(1000 / 32_768f, -1000 / 32_768f, 3000 / 32_768f), samples)
    }

    @Test
    fun `stream throws on a base_resp error event`() = runTest {
        stubSettings(provider = "official")
        official.enqueue(sse(sseEvent("", statusCode = 1002, statusMsg = "rate limited")))
        try {
            collectStream()
            fail("expected synthesizeStream to throw")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("rate limited"))
        }
    }

    @Test
    fun `stream throws on a plain JSON error body`() = runTest {
        stubSettings(provider = "official")
        val body = JSONObject().put(
            "base_resp",
            JSONObject().put("status_code", 1004).put("status_msg", "authorization failed"),
        )
        official.enqueue(MockResponse().setBody(body.toString()))
        try {
            collectStream()
            fail("expected synthesizeStream to throw")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("authorization failed"))
        }
    }

    @Test
    fun `stream throws when it ends without a single chunk`() = runTest {
        stubSettings(provider = "official")
        official.enqueue(sse(sseEvent(hex(pcm16(1000, 2000)), status = 2)))
        try {
            collectStream()
            fail("expected synthesizeStream to throw")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("without audio"))
        }
    }

    @Test
    fun `stream closed after audio without a status 2 event is a failure`() = runTest {
        stubSettings(provider = "official")
        official.enqueue(sse(sseEvent(hex(pcm16(1000, -1000)))))
        val chunks = mutableListOf<FloatArray>()
        try {
            backend.synthesizeStream("привет", TtsGender.MALE) { chunks += it }
            fail("expected synthesizeStream to throw")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("before synthesis completed"))
        }
        assertEquals(1, chunks.size) // the audio that did arrive was still delivered
    }

    @Test
    fun `an odd trailing byte at the end is dropped with a warning and the sentence still succeeds`() = runTest {
        stubSettings(provider = "official")
        val bytes = pcm16(1000, -1000)
        official.enqueue(sse(sseEvent(hex(bytes + byteArrayOf(0x11))), sseEvent("", status = 2)))
        mockkStatic(Log::class)
        try {
            every { Log.w(any(), any<String>()) } returns 0
            val samples = collectStream().flatMap { it.toList() }
            assertEquals(listOf(1000 / 32_768f, -1000 / 32_768f), samples)
            verify { Log.w(any(), match<String> { it.contains("unpaired PCM byte") }) }
        } finally {
            unmockkStatic(Log::class)
        }
    }

    @Test
    fun `a cancellation landing before the watcher is dispatched still cancels the blocking call`() {
        stubSettings(provider = "official")
        // The job is cancelled while synthesizeStream is already inside its IO block, before the
        // watcher is launched; the server never answers, so only call.cancel() can unblock execute().
        coEvery { settingsRepository.getString(SettingsRepository.KEY_MINIMAX_TTS_KEY, "") } coAnswers {
            currentCoroutineContext().job.cancel()
            "mm-test-key"
        }
        official.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val slowBackend = MiniMaxTtsBackend(
            http = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build(),
            settingsRepository = settingsRepository,
            officialBaseUrl = official.url("/").toString().trimEnd('/'),
        )
        val finished = CountDownLatch(1)
        thread {
            runCatching { runBlocking { slowBackend.synthesizeStream("привет", TtsGender.MALE) {} } }
            finished.countDown()
        }
        assertTrue("execute() stayed blocked after cancellation", finished.await(10, TimeUnit.SECONDS))
    }

    @Test
    fun `cancelling the whole-sentence synthesize while the server never responds returns promptly`() {
        stubSettings(provider = "official")
        // Same setup as the streaming cancellation test above: the job is cancelled while
        // synthesizeOfficial is already inside its IO block, before the watcher is launched; the
        // server never answers, so only call.cancel() can unblock execute().
        coEvery { settingsRepository.getString(SettingsRepository.KEY_MINIMAX_TTS_KEY, "") } coAnswers {
            currentCoroutineContext().job.cancel()
            "mm-test-key"
        }
        official.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val slowBackend = MiniMaxTtsBackend(
            http = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build(),
            settingsRepository = settingsRepository,
            officialBaseUrl = official.url("/").toString().trimEnd('/'),
        )
        val finished = CountDownLatch(1)
        thread {
            runCatching { runBlocking { slowBackend.synthesize("привет", TtsGender.MALE) } }
            finished.countDown()
        }
        assertTrue("execute() stayed blocked after cancellation", finished.await(10, TimeUnit.SECONDS))
    }

    @Test
    fun `streamSampleRate is 24000 for official, unset and unknown providers, null for fal and replicate`() = runTest {
        stubSettings(provider = "official")
        assertEquals(24_000, backend.streamSampleRate())
        stubSettings(provider = "something-new")
        assertEquals(24_000, backend.streamSampleRate())
        stubSettings(provider = "fal")
        assertNull(backend.streamSampleRate())
        stubSettings(provider = "replicate")
        assertNull(backend.streamSampleRate())
        coEvery {
            settingsRepository.getString(SettingsRepository.KEY_MINIMAX_TTS_PROVIDER, any())
        } answers { secondArg() }
        assertEquals(24_000, backend.streamSampleRate())
    }
}
