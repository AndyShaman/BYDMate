package com.bydmate.app.voice

import io.mockk.every
import io.mockk.mockk
import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TtsModelManagerTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun manager(filesDir: File): TtsModelManager {
        val ctx = mockk<Context>()
        every { ctx.filesDir } returns filesDir
        every { ctx.cacheDir } returns filesDir
        return TtsModelManager(ctx, OkHttpClient())
    }

    /** Builds a tar.bz2 with the sherpa archive layout under one top-level dir. */
    private fun makeArchive(entries: Map<String, String>): File {
        val f = tmp.newFile("voice.tar.bz2")
        BZip2CompressorOutputStream(f.outputStream()).use { bz ->
            TarArchiveOutputStream(bz).use { tar ->
                entries.forEach { (name, content) ->
                    val bytes = content.toByteArray()
                    val e = TarArchiveEntry(name)
                    e.size = bytes.size.toLong()
                    tar.putArchiveEntry(e)
                    tar.write(bytes)
                    tar.closeArchiveEntry()
                }
            }
        }
        return f
    }

    @Test
    fun `untarFlatten drops top-level dir and preserves nested layout`() {
        val target = tmp.newFolder("target")
        val archive = makeArchive(mapOf(
            "vits-piper-ru_RU-ruslan-medium-int8/ru_RU-ruslan-medium.onnx" to "onnx-bytes",
            "vits-piper-ru_RU-ruslan-medium-int8/tokens.txt" to "tokens",
            "vits-piper-ru_RU-ruslan-medium-int8/espeak-ng-data/phontab" to "ph",
        ))
        manager(tmp.newFolder("files1")).untarFlatten(archive, target)
        assertEquals("onnx-bytes", File(target, "ru_RU-ruslan-medium.onnx").readText())
        assertEquals("tokens", File(target, "tokens.txt").readText())
        assertEquals("ph", File(target, "espeak-ng-data/phontab").readText())
    }

    @Test
    fun `untarFlatten rejects tar-slip entries`() {
        val target = tmp.newFolder("target2")
        val archive = makeArchive(mapOf(
            "top/../../escaped.txt" to "evil",
            "top/ok.txt" to "ok",
        ))
        manager(tmp.newFolder("files2")).untarFlatten(archive, target)
        // The malicious entry must not be written anywhere — check every escape level.
        assertFalse(File(target, "escaped.txt").exists())
        assertFalse(File(target.parentFile, "escaped.txt").exists())
        assertFalse(File(target.parentFile.parentFile, "escaped.txt").exists())
        assertTrue(File(target, "ok.txt").exists())
    }

    @Test
    fun `isReady requires onnx, tokens and espeak dir`() {
        val filesDir = tmp.newFolder("files3")
        val m = manager(filesDir)
        assertFalse(m.isReady(TtsVoiceCatalog.byId("ruslan")))
        val dir = File(filesDir, "tts/ruslan").apply { mkdirs() }
        File(dir, "model.onnx").writeText("x")
        assertFalse(m.isReady(TtsVoiceCatalog.byId("ruslan")))                       // no tokens yet
        File(dir, "tokens.txt").writeText("t")
        assertFalse(m.isReady(TtsVoiceCatalog.byId("ruslan")))                       // no espeak dir yet
        File(dir, "espeak-ng-data").mkdirs()
        assertTrue(m.isReady(TtsVoiceCatalog.byId("ruslan")))
        assertEquals("model.onnx", m.onnxFile("ruslan")?.name)
    }

    @Test
    fun `isReady requires stress tsv instead of espeak dir for the vits multispeaker voices`() {
        val filesDir = tmp.newFolder("files3b")
        val m = manager(filesDir)
        val alena = TtsVoiceCatalog.byId("alena")
        val dir = File(filesDir, "tts/vits-ru-multi").apply { mkdirs() }
        File(dir, "model.onnx").writeText("x")
        File(dir, "tokens.txt").writeText("t")
        File(dir, "espeak-ng-data").mkdirs()           // present or not, irrelevant for VITS_MULTI
        assertFalse(m.isReady(alena))                  // no stress.tsv yet
        File(dir, "stress.tsv").writeText("s")
        assertTrue(m.isReady(alena))
    }

    @Test
    fun `delete removes the voice dir`() = runBlocking {
        val filesDir = tmp.newFolder("files4")
        val m = manager(filesDir)
        val dir = File(filesDir, "tts/ruslan").apply { mkdirs() }
        File(dir, "model.onnx").writeText("x")
        m.delete("ruslan")
        assertFalse(dir.exists())
    }

    @Test
    fun `delete waits for the disk mutex held by a commit section`() = runBlocking {
        val filesDir = tmp.newFolder("files6")
        val m = manager(filesDir)
        val dir = File(filesDir, "tts/ruslan").apply { mkdirs() }
        File(dir, "model.onnx").writeText("x")
        m.diskMutex.lock()   // simulate download's unpack-commit holding the lock
        val job = launch { m.delete("ruslan") }
        kotlinx.coroutines.delay(50)
        assertTrue(dir.exists())   // delete must be blocked, not interleaved
        m.diskMutex.unlock()
        job.join()
        assertFalse(dir.exists())
    }

    @Test
    fun `failed unpack cleans the model dir`() = kotlinx.coroutines.runBlocking {
        val filesDir = tmp.newFolder("files5")
        // Archive unpacks fine but is INCOMPLETE (no espeak-ng-data) -> check(isReady()) fails
        val archive = makeArchive(mapOf(
            "top/model.onnx" to "x",
            "top/tokens.txt" to "t",
        ))
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200).message("OK")
                    .body(archive.readBytes().toResponseBody("application/octet-stream".toMediaType()))
                    .build()
            }
            .build()
        val ctx = mockk<Context>()
        every { ctx.filesDir } returns filesDir
        every { ctx.cacheDir } returns filesDir
        val m = TtsModelManager(ctx, client)
        val result = m.download(TtsVoiceCatalog.byId("ruslan")) { }
        assertTrue(result.isFailure)
        assertFalse(m.isReady(TtsVoiceCatalog.byId("ruslan")))
        assertFalse(File(filesDir, "tts/ruslan").exists())   // dir cleaned, not left partial
        assertFalse(File(filesDir, "tts/.staging-ruslan").exists())   // staging cleaned too
    }

    @Test
    fun `failed download keeps the previous model`() = kotlinx.coroutines.runBlocking {
        val filesDir = tmp.newFolder("files7")
        // Seed a complete, previously installed model.
        val dir = File(filesDir, "tts/ruslan").apply { mkdirs() }
        File(dir, "model.onnx").writeText("existing-onnx")
        File(dir, "tokens.txt").writeText("existing-tokens")
        File(dir, "espeak-ng-data").mkdirs()

        // New download serves an INCOMPLETE archive (missing model + espeak dir).
        val archive = makeArchive(mapOf("top/tokens.txt" to "new-tokens"))
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200).message("OK")
                    .body(archive.readBytes().toResponseBody("application/octet-stream".toMediaType()))
                    .build()
            }
            .build()
        val ctx = mockk<Context>()
        every { ctx.filesDir } returns filesDir
        every { ctx.cacheDir } returns filesDir
        val m = TtsModelManager(ctx, client)

        val result = m.download(TtsVoiceCatalog.byId("ruslan")) { }

        assertTrue(result.isFailure)
        assertTrue(m.isReady(TtsVoiceCatalog.byId("ruslan")))
        assertEquals("existing-onnx", File(dir, "model.onnx").readText())
    }

    @Test
    fun `partial staging dir is never ready`() {
        val filesDir = tmp.newFolder("files8")
        val m = manager(filesDir)
        val staging = File(filesDir, "tts/.staging-ruslan").apply { mkdirs() }
        File(staging, "model.onnx").writeText("x")
        File(staging, "tokens.txt").writeText("t")
        File(staging, "espeak-ng-data").mkdirs()
        assertFalse(m.isReady(TtsVoiceCatalog.byId("ruslan")))
    }

    @Test
    fun `successful download leaves no staging dir`() = kotlinx.coroutines.runBlocking {
        val filesDir = tmp.newFolder("files9")
        val archive = makeArchive(mapOf(
            "top/model.onnx" to "onnx-bytes",
            "top/tokens.txt" to "tokens",
            "top/espeak-ng-data/phontab" to "ph",
        ))
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200).message("OK")
                    .body(archive.readBytes().toResponseBody("application/octet-stream".toMediaType()))
                    .build()
            }
            .build()
        val ctx = mockk<Context>()
        every { ctx.filesDir } returns filesDir
        every { ctx.cacheDir } returns filesDir
        val m = TtsModelManager(ctx, client)

        val result = m.download(TtsVoiceCatalog.byId("ruslan")) { }

        assertTrue(result.isSuccess)
        assertTrue(m.isReady(TtsVoiceCatalog.byId("ruslan")))
        assertFalse(File(filesDir, "tts/.staging-ruslan").exists())
    }

    @Test
    fun `untarFlatten skips AppleDouble junk entries`() {
        val target = tmp.newFolder("targetJunk")
        val archive = makeArchive(mapOf(
            "vits-ru-multi/model.int8.onnx" to "real",
            "vits-ru-multi/._model.int8.onnx" to "junk",
            "vits-ru-multi/.DS_Store" to "junk",
            "vits-ru-multi/espeak-ng-data/._phontab" to "junk",
            "vits-ru-multi/tokens.txt" to "t",
        ))
        manager(tmp.newFolder("filesJunk")).untarFlatten(archive, target)
        assertTrue(File(target, "model.int8.onnx").exists())
        assertFalse(File(target, "._model.int8.onnx").exists())
        assertFalse(File(target, ".DS_Store").exists())
        assertFalse(File(target, "espeak-ng-data/._phontab").exists())
    }

    @Test
    fun `onnxFile ignores AppleDouble junk already on disk`() {
        val filesDir = tmp.newFolder("filesJunk2")
        val m = manager(filesDir)
        val dir = File(filesDir, "tts/vits-ru-multi").apply { mkdirs() }
        File(dir, "._model.int8.onnx").writeText("junk") // sorts before the real file
        File(dir, "model.int8.onnx").writeText("real")
        assertEquals("model.int8.onnx", m.onnxFile("vits-ru-multi")?.name)
    }

    @Test
    fun `isReady for supertonic requires all seven model files and no tokens`() {
        val filesDir = tmp.newFolder("filesSt")
        val m = manager(filesDir)
        val voice = TtsVoice(
            id = "st-test", labelRes = 0, url = "http://x", gender = TtsGender.MALE,
            engine = TtsVoiceEngine.SUPERTONIC, modelDirId = "supertonic-ru", speakerId = 7, sizeMb = 145,
        )
        val dir = File(filesDir, "tts/supertonic-ru").apply { mkdirs() }
        val files = listOf(
            "duration_predictor.int8.onnx", "text_encoder.int8.onnx", "vector_estimator.int8.onnx",
            "vocoder.int8.onnx", "tts.json", "unicode_indexer.bin", "voice.bin",
        )
        files.dropLast(1).forEach { File(dir, it).writeText("x") }
        assertFalse(m.isReady(voice))          // voice.bin missing
        File(dir, "voice.bin").writeText("x")
        assertTrue(m.isReady(voice))           // complete WITHOUT tokens.txt
    }

    @Test
    fun `ensureStressDict is a no-op for non-supertonic voices`() = runBlocking {
        val filesDir = tmp.newFolder("filesStressNoop")
        var calls = 0
        val client = OkHttpClient.Builder()
            .addInterceptor { calls++; error("network must not be used") }
            .build()
        val ctx = mockk<Context>()
        every { ctx.filesDir } returns filesDir
        every { ctx.cacheDir } returns filesDir
        val m = TtsModelManager(ctx, client)

        assertTrue(m.ensureStressDict(TtsVoiceCatalog.byId("ruslan")))
        assertEquals(0, calls)
    }

    @Test
    fun `ensureStressDict returns true when supertonic dictionary already exists`() = runBlocking {
        val filesDir = tmp.newFolder("filesStressExisting")
        val dir = File(filesDir, "tts/supertonic-ru").apply { mkdirs() }
        File(dir, "stress.tsv").writeText("позвонит\t3")
        var calls = 0
        val client = OkHttpClient.Builder()
            .addInterceptor { calls++; error("network must not be used") }
            .build()
        val ctx = mockk<Context>()
        every { ctx.filesDir } returns filesDir
        every { ctx.cacheDir } returns filesDir
        val m = TtsModelManager(ctx, client)

        assertTrue(m.ensureStressDict(TtsVoiceCatalog.byId("mark")))
        assertEquals(0, calls)
    }

    @Test
    fun `ensureStressDict returns false without network when supertonic model dir is absent`() = runBlocking {
        val filesDir = tmp.newFolder("filesStressAbsent")
        var calls = 0
        val client = OkHttpClient.Builder()
            .addInterceptor { calls++; error("network must not be used") }
            .build()
        val ctx = mockk<Context>()
        every { ctx.filesDir } returns filesDir
        every { ctx.cacheDir } returns filesDir
        val m = TtsModelManager(ctx, client)

        assertFalse(m.ensureStressDict(TtsVoiceCatalog.byId("mark")))
        assertFalse(File(filesDir, "tts/supertonic-ru").exists())
        assertEquals(0, calls)
    }

    @Test
    fun `ensureStressDict downloads and unpacks stress tsv into supertonic dir`() = runBlocking {
        val filesDir = tmp.newFolder("filesStressDownload")
        val dir = File(filesDir, "tts/supertonic-ru").apply { mkdirs() }
        // Body must exceed STRESS_DICT_MIN_BYTES so the plausibility check accepts it
        // (Cyrillic is 2 bytes/char in UTF-8, so this clears the byte floor comfortably).
        val body = buildString {
            append("позвонит\t3\n")
            var i = 0
            while (length < TtsModelManager.STRESS_DICT_MIN_BYTES) append("слово").append(i++).append("\t3\n")
        }
        val archive = makeArchive(mapOf("stress-ru/stress.tsv" to body))
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                assertEquals(TtsModelManager.STRESS_DICT_URL, chain.request().url.toString())
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200).message("OK")
                    .body(archive.readBytes().toResponseBody("application/octet-stream".toMediaType()))
                    .build()
            }
            .build()
        val ctx = mockk<Context>()
        every { ctx.filesDir } returns filesDir
        every { ctx.cacheDir } returns filesDir
        val m = TtsModelManager(ctx, client)

        assertTrue(m.ensureStressDict(TtsVoiceCatalog.byId("sofia")))
        val published = File(dir, "stress.tsv")
        assertTrue(published.length() >= TtsModelManager.STRESS_DICT_MIN_BYTES)
        assertTrue(published.readText().startsWith("позвонит\t3\n"))
    }

    @Test
    fun `ensureStressDict rejects an implausibly small dictionary and stays retryable`() = runBlocking {
        val filesDir = tmp.newFolder("filesStressTiny")
        val dir = File(filesDir, "tts/supertonic-ru").apply { mkdirs() }
        val archive = makeArchive(mapOf("stress-ru/stress.tsv" to "позвонит\t3\n")) // 12 bytes < floor
        var calls = 0
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                calls++
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200).message("OK")
                    .body(archive.readBytes().toResponseBody("application/octet-stream".toMediaType()))
                    .build()
            }
            .build()
        val ctx = mockk<Context>()
        every { ctx.filesDir } returns filesDir
        every { ctx.cacheDir } returns filesDir
        val m = TtsModelManager(ctx, client)

        assertFalse(m.ensureStressDict(TtsVoiceCatalog.byId("sofia")))
        // Not published -> the isFile short-circuit won't cache the bad dict, so a later
        // call with a good asset can still succeed.
        assertFalse(File(dir, "stress.tsv").exists())
        assertEquals(1, calls)
    }

    @Test
    fun `download stops when coroutine is cancelled`() = runBlocking {
        val filesDir = tmp.newFolder("files6")
        // A genuinely valid, extractable archive with a ~1 MiB low-compressibility payload:
        // the body must stream over multiple 64 KiB reads (so onProgress fires more than
        // once, giving a real mid-transfer cancellation point) AND unpack must be able to
        // *succeed* if cancellation is ignored -- otherwise "the dir doesn't exist" would be
        // true either way (e.g. a garbage/invalid body fails unpack regardless of cancellation
        // and the failure-cleanup branch removes the dir on its own).
        val onnxBytes = kotlin.random.Random(42).nextBytes(1_000_000)
        val archive = tmp.newFile("voice-big.tar.bz2")
        BZip2CompressorOutputStream(archive.outputStream()).use { bz ->
            TarArchiveOutputStream(bz).use { tar ->
                fun put(name: String, bytes: ByteArray) {
                    val e = TarArchiveEntry(name)
                    e.size = bytes.size.toLong()
                    tar.putArchiveEntry(e)
                    tar.write(bytes)
                    tar.closeArchiveEntry()
                }
                put("top/model.onnx", onnxBytes)
                put("top/tokens.txt", "t".toByteArray())
                put("top/espeak-ng-data/phontab", "ph".toByteArray())
            }
        }
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200).message("OK")
                    .body(archive.readBytes().toResponseBody("application/octet-stream".toMediaType()))
                    .build()
            }
            .build()
        val ctx = mockk<Context>()
        every { ctx.filesDir } returns filesDir
        every { ctx.cacheDir } returns filesDir
        val m = TtsModelManager(ctx, client)

        lateinit var job: Job
        job = launch(start = CoroutineStart.LAZY) {
            m.download(TtsVoiceCatalog.byId("ruslan")) { job.cancel() }   // cancel from the very first progress tick
        }
        job.start()
        job.join()

        assertTrue(job.isCancelled)
        assertFalse(File(filesDir, "tts/ruslan").exists())   // unpack never ran
    }
}
