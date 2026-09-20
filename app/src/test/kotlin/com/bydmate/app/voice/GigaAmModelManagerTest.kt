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

class GigaAmModelManagerTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun manager(filesDir: File): GigaAmModelManager {
        val ctx = mockk<Context>()
        every { ctx.filesDir } returns filesDir
        every { ctx.cacheDir } returns filesDir
        // Pinned, not read from the host disk: a nearly-full CI volume must not turn
        // every download test into a storage failure.
        return GigaAmModelManager(ctx, OkHttpClient(), usableSpace = { Long.MAX_VALUE })
    }

    /** Builds a tar.bz2 with the sherpa nemo-ctc archive layout under one top-level dir. */
    private fun makeArchive(entries: Map<String, String>): File {
        val f = tmp.newFile("gigaam.tar.bz2")
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

    /** Routes MODEL_URL to the archive body and VAD_URL to the plain VAD body. */
    private fun clientFor(archive: File, vadBytes: ByteArray): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                val bytes = if (url == GigaAmModelManager.VAD_URL) vadBytes else archive.readBytes()
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200).message("OK")
                    .body(bytes.toResponseBody("application/octet-stream".toMediaType()))
                    .build()
            }
            .build()

    private fun managerWith(
        filesDir: File,
        http: OkHttpClient,
        usableSpace: Long = Long.MAX_VALUE,
    ): GigaAmModelManager {
        val ctx = mockk<Context>()
        every { ctx.filesDir } returns filesDir
        every { ctx.cacheDir } returns filesDir
        return GigaAmModelManager(ctx, http, usableSpace = { usableSpace })
    }

    // --- Step 1 (a, b): isReady() on an empty dir vs. all 3 files present ---

    @Test
    fun `isReady requires model, tokens and vad file`() {
        val filesDir = tmp.newFolder("files1")
        val m = manager(filesDir)
        assertFalse(m.isReady())

        val dir = File(filesDir, "asr/gigaam-v3-ru").apply { mkdirs() }
        File(dir, "model.int8.onnx").writeText("x")
        assertFalse(m.isReady())                       // no tokens yet

        File(dir, "tokens.txt").writeText("t")
        assertFalse(m.isReady())                       // no vad file yet

        File(filesDir, "asr/silero_vad.onnx").writeText("v")
        assertTrue(m.isReady())
    }

    // --- Step 1 (c): paths are stable ---

    @Test
    fun `paths are stable and point at the filesDir asr layout`() {
        val filesDir = tmp.newFolder("files2")
        val m = manager(filesDir)

        assertTrue(m.modelPath().endsWith("asr/gigaam-v3-ru/model.int8.onnx"))
        assertTrue(m.tokensPath().endsWith("asr/gigaam-v3-ru/tokens.txt"))
        assertTrue(m.vadPath().endsWith("asr/silero_vad.onnx"))
        // Stable across repeated calls.
        assertEquals(m.modelPath(), m.modelPath())
        assertEquals(m.tokensPath(), m.tokensPath())
        assertEquals(m.vadPath(), m.vadPath())
    }

    // --- Step 1 (d): an unfinished staging dir must never satisfy isReady() ---

    @Test
    fun `partial staging dir is never ready`() {
        val filesDir = tmp.newFolder("files3")
        val m = manager(filesDir)
        val staging = File(filesDir, "asr/.staging-gigaam-v3-ru").apply { mkdirs() }
        File(staging, "model.int8.onnx").writeText("x")
        File(staging, "tokens.txt").writeText("t")
        File(filesDir, "asr/silero_vad.onnx").writeText("v")   // vad present, model dir is not
        assertFalse(m.isReady())
    }

    @Test
    fun `untarFlatten drops top-level dir and preserves nested layout`() = runBlocking {
        val target = tmp.newFolder("target")
        val archive = makeArchive(mapOf(
            "sherpa-onnx-nemo-ctc-giga-am-v3-russian-2025-12-16/model.int8.onnx" to "onnx-bytes",
            "sherpa-onnx-nemo-ctc-giga-am-v3-russian-2025-12-16/tokens.txt" to "tokens",
        ))
        manager(tmp.newFolder("files4")).untarFlatten(archive, target)
        assertEquals("onnx-bytes", File(target, "model.int8.onnx").readText())
        assertEquals("tokens", File(target, "tokens.txt").readText())
    }

    @Test
    fun `untarFlatten rejects tar-slip entries`() = runBlocking {
        val target = tmp.newFolder("target2")
        val archive = makeArchive(mapOf(
            "top/../../escaped.txt" to "evil",
            "top/ok.txt" to "ok",
        ))
        manager(tmp.newFolder("files5")).untarFlatten(archive, target)
        assertFalse(File(target, "escaped.txt").exists())
        assertFalse(File(target.parentFile, "escaped.txt").exists())
        assertFalse(File(target.parentFile.parentFile, "escaped.txt").exists())
        assertTrue(File(target, "ok.txt").exists())
    }

    @Test
    fun `delete removes the model dir and the vad file`() = runBlocking {
        val filesDir = tmp.newFolder("files6")
        val m = manager(filesDir)
        val dir = File(filesDir, "asr/gigaam-v3-ru").apply { mkdirs() }
        File(dir, "model.int8.onnx").writeText("x")
        val vad = File(filesDir, "asr/silero_vad.onnx").apply { writeText("v") }
        m.delete()
        assertFalse(dir.exists())
        assertFalse(vad.exists())
    }

    @Test
    fun `delete waits for the disk mutex held by a commit section`() = runBlocking {
        val filesDir = tmp.newFolder("files7")
        val m = manager(filesDir)
        val dir = File(filesDir, "asr/gigaam-v3-ru").apply { mkdirs() }
        File(dir, "model.int8.onnx").writeText("x")
        m.diskMutex.lock()   // simulate download's commit section holding the lock
        val job = launch { m.delete() }
        kotlinx.coroutines.delay(50)
        assertTrue(dir.exists())   // delete must be blocked, not interleaved
        m.diskMutex.unlock()
        job.join()
        assertFalse(dir.exists())
    }

    @Test
    fun `successful download publishes model, tokens and vad with no staging left`() = runBlocking {
        val filesDir = tmp.newFolder("files8")
        val archive = makeArchive(mapOf(
            "top/model.int8.onnx" to "onnx-bytes",
            "top/tokens.txt" to "tokens",
        ))
        val client = clientFor(archive, "vad-bytes".toByteArray())
        val m = managerWith(filesDir, client)

        val result = m.download { _, _ -> }

        assertTrue(result.isSuccess)
        assertTrue(m.isReady())
        assertEquals("onnx-bytes", File(m.modelPath()).readText())
        assertEquals("tokens", File(m.tokensPath()).readText())
        assertEquals("vad-bytes", File(m.vadPath()).readText())
        assertFalse(File(filesDir, "asr/.staging-gigaam-v3-ru").exists())
    }

    @Test
    fun `failed unpack cleans the model dir`() = runBlocking {
        val filesDir = tmp.newFolder("files9")
        // Archive unpacks fine but is INCOMPLETE (no tokens.txt) -> check(isModelComplete) fails.
        val archive = makeArchive(mapOf("top/model.int8.onnx" to "x"))
        val client = clientFor(archive, "vad-bytes".toByteArray())
        val m = managerWith(filesDir, client)

        val result = m.download { _, _ -> }

        assertTrue(result.isFailure)
        assertFalse(m.isReady())
        assertFalse(File(filesDir, "asr/gigaam-v3-ru").exists())
        assertFalse(File(filesDir, "asr/.staging-gigaam-v3-ru").exists())
    }

    @Test
    fun `failed download keeps the previous model`() = runBlocking {
        val filesDir = tmp.newFolder("files10")
        // Seed a complete, previously installed model + vad.
        val dir = File(filesDir, "asr/gigaam-v3-ru").apply { mkdirs() }
        File(dir, "model.int8.onnx").writeText("existing-onnx")
        File(dir, "tokens.txt").writeText("existing-tokens")
        File(filesDir, "asr/silero_vad.onnx").writeText("existing-vad")

        // New download serves an INCOMPLETE archive (missing tokens.txt).
        val archive = makeArchive(mapOf("top/model.int8.onnx" to "new-onnx"))
        val client = clientFor(archive, "vad-bytes".toByteArray())
        val m = managerWith(filesDir, client)

        val result = m.download { _, _ -> }

        assertTrue(result.isFailure)
        assertTrue(m.isReady())
        assertEquals("existing-onnx", File(dir, "model.int8.onnx").readText())
        assertEquals("existing-vad", File(m.vadPath()).readText())
    }

    @Test
    fun `download stops when coroutine is cancelled`() = runBlocking {
        val filesDir = tmp.newFolder("files11")
        // A genuinely valid, extractable archive with a ~1 MiB low-compressibility payload:
        // the body must stream over multiple 64 KiB reads (so onProgress fires more than
        // once, giving a real mid-transfer cancellation point) AND unpack must be able to
        // succeed if cancellation is ignored.
        val onnxBytes = kotlin.random.Random(42).nextBytes(1_000_000)
        val archive = tmp.newFile("gigaam-big.tar.bz2")
        BZip2CompressorOutputStream(archive.outputStream()).use { bz ->
            TarArchiveOutputStream(bz).use { tar ->
                fun put(name: String, bytes: ByteArray) {
                    val e = TarArchiveEntry(name)
                    e.size = bytes.size.toLong()
                    tar.putArchiveEntry(e)
                    tar.write(bytes)
                    tar.closeArchiveEntry()
                }
                put("top/model.int8.onnx", onnxBytes)
                put("top/tokens.txt", "t".toByteArray())
            }
        }
        val client = clientFor(archive, "vad-bytes".toByteArray())
        val m = managerWith(filesDir, client)

        lateinit var job: Job
        job = launch(start = CoroutineStart.LAZY) {
            m.download { _, _ -> job.cancel() }   // cancel from the very first progress tick
        }
        job.start()
        job.join()

        assertTrue(job.isCancelled)
        assertFalse(File(filesDir, "asr/gigaam-v3-ru").exists())   // unpack never ran
        assertFalse(File(filesDir, "asr/silero_vad.onnx").exists())   // vad phase never ran
    }

    // --- v2 -> v3 migration: legacy dir is swept, never resurrected ---

    @Test
    fun `successful download publishes v3 dir and removes legacy v2 dir`() = runBlocking {
        val filesDir = tmp.newFolder("files12")
        val legacy = File(filesDir, "asr/gigaam-v2-ru").apply { mkdirs() }
        File(legacy, "model.int8.onnx").writeText("old model")
        File(legacy, "tokens.txt").writeText("old tokens")
        val legacyStaging = File(filesDir, "asr/.staging-gigaam-v2-ru").apply { mkdirs() }
        val archive = makeArchive(mapOf(
            "top/model.int8.onnx" to "model-bytes",
            "top/tokens.txt" to "tokens-bytes",
        ))
        val m = managerWith(filesDir, clientFor(archive, byteArrayOf(1, 2, 3)))

        val result = m.download { _, _ -> }

        assertTrue(result.isSuccess)
        assertTrue(File(filesDir, "asr/gigaam-v3-ru/model.int8.onnx").isFile)
        assertTrue(File(filesDir, "asr/gigaam-v3-ru/tokens.txt").isFile)
        assertFalse("legacy v2 dir must be cleaned up", legacy.exists())
        assertFalse("orphaned legacy v2 staging dir must be cleaned up", legacyStaging.exists())
        assertTrue(m.isReady())
    }

    @Test
    fun `failed download leaves legacy v2 dir untouched`() = runBlocking {
        val filesDir = tmp.newFolder("files13")
        val legacy = File(filesDir, "asr/gigaam-v2-ru").apply { mkdirs() }
        File(legacy, "model.int8.onnx").writeText("old model")
        File(legacy, "tokens.txt").writeText("old tokens")
        val failing = OkHttpClient.Builder()
            .addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(500).message("boom")
                    .body("".toResponseBody("text/plain".toMediaType()))
                    .build()
            }
            .build()
        val m = managerWith(filesDir, failing)

        val result = m.download { _, _ -> }

        assertTrue(result.isFailure)
        assertTrue("legacy v2 dir must survive a failed download", legacy.exists())
        assertFalse(File(filesDir, "asr/gigaam-v3-ru").exists())
    }

    @Test
    fun `delete removes v3 staging and legacy v2`() = runBlocking {
        val filesDir = tmp.newFolder("files14")
        val v3 = File(filesDir, "asr/gigaam-v3-ru").apply { mkdirs() }
        File(v3, "model.int8.onnx").writeText("m")
        File(v3, "tokens.txt").writeText("t")
        val legacy = File(filesDir, "asr/gigaam-v2-ru").apply { mkdirs() }
        File(legacy, "model.int8.onnx").writeText("old")
        val staging = File(filesDir, "asr/.staging-gigaam-v3-ru").apply { mkdirs() }
        val legacyStaging = File(filesDir, "asr/.staging-gigaam-v2-ru").apply { mkdirs() }
        val m = manager(filesDir)

        m.delete()

        assertFalse(v3.exists())
        assertFalse(legacy.exists())
        assertFalse(staging.exists())
        assertFalse(legacyStaging.exists())
    }

    @Test
    fun `isReady is false when only legacy v2 model is present`() {
        val filesDir = tmp.newFolder("files15")
        val legacy = File(filesDir, "asr/gigaam-v2-ru").apply { mkdirs() }
        File(legacy, "model.int8.onnx").writeText("old model")
        File(legacy, "tokens.txt").writeText("old tokens")
        File(filesDir, "asr").mkdirs()
        File(filesDir, "asr/silero_vad.onnx").writeText("vad")

        assertFalse("after the APK update the model must read as not downloaded", manager(filesDir).isReady())
    }

    // --- Phased progress: the unpack of a ~226 MiB archive must not look like a hang ---

    @Test
    fun `download reports download, unpack and vad phases with monotonic progress`() = runBlocking {
        val filesDir = tmp.newFolder("files16")
        val archive = makeArchive(mapOf(
            "top/model.int8.onnx" to "onnx-bytes",
            "top/tokens.txt" to "tokens",
        ))
        val m = managerWith(filesDir, clientFor(archive, "vad-bytes".toByteArray()))

        val ticks = mutableListOf<Pair<GigaAmModelManager.Phase, Int>>()
        val result = m.download { phase, pct -> ticks += phase to pct }

        assertTrue(result.isSuccess)
        // Every phase is represented, in order, and the bar never goes backwards.
        assertEquals(
            listOf(
                GigaAmModelManager.Phase.DOWNLOAD,
                GigaAmModelManager.Phase.UNPACK,
                GigaAmModelManager.Phase.VAD,
            ),
            ticks.map { it.first }.distinct(),
        )
        assertEquals(ticks.map { it.second }, ticks.map { it.second }.sorted())
        assertEquals(100, ticks.last().second)
        // Each phase stays inside its own slice of the combined bar.
        ticks.forEach { (phase, pct) ->
            val range = when (phase) {
                GigaAmModelManager.Phase.DOWNLOAD -> 0..90
                GigaAmModelManager.Phase.UNPACK -> 90..98
                GigaAmModelManager.Phase.VAD -> 98..100
            }
            assertTrue("$phase reported $pct, outside $range", pct in range)
        }
    }

    @Test
    fun `unpack phase reports intermediate progress, not just its end value`() = runBlocking {
        val filesDir = tmp.newFolder("files17")
        // ~1 MiB of incompressible payload: the unpack must stream over many reads of the
        // archive file, so progress has real intermediate points to report.
        val onnxBytes = kotlin.random.Random(7).nextBytes(1_000_000)
        val archive = tmp.newFile("gigaam-progress.tar.bz2")
        BZip2CompressorOutputStream(archive.outputStream()).use { bz ->
            TarArchiveOutputStream(bz).use { tar ->
                fun put(name: String, bytes: ByteArray) {
                    val e = TarArchiveEntry(name)
                    e.size = bytes.size.toLong()
                    tar.putArchiveEntry(e)
                    tar.write(bytes)
                    tar.closeArchiveEntry()
                }
                put("top/model.int8.onnx", onnxBytes)
                put("top/tokens.txt", "t".toByteArray())
            }
        }
        val m = managerWith(filesDir, clientFor(archive, "vad-bytes".toByteArray()))

        val unpackTicks = mutableListOf<Int>()
        val result = m.download { phase, pct ->
            if (phase == GigaAmModelManager.Phase.UNPACK) unpackTicks += pct
        }

        assertTrue(result.isSuccess)
        assertTrue("unpack must tick more than once", unpackTicks.distinct().size > 1)
        assertEquals(98, unpackTicks.last())
    }

    @Test
    fun `untarFlatten reports monotonic progress ending at 100`() = runBlocking {
        val target = tmp.newFolder("target3")
        val onnxBytes = kotlin.random.Random(11).nextBytes(1_000_000)
        val archive = tmp.newFile("gigaam-untar.tar.bz2")
        BZip2CompressorOutputStream(archive.outputStream()).use { bz ->
            TarArchiveOutputStream(bz).use { tar ->
                val e = TarArchiveEntry("top/model.int8.onnx")
                e.size = onnxBytes.size.toLong()
                tar.putArchiveEntry(e)
                tar.write(onnxBytes)
                tar.closeArchiveEntry()
            }
        }
        val ticks = mutableListOf<Int>()
        manager(tmp.newFolder("files18")).untarFlatten(archive, target) { ticks += it }

        assertTrue(ticks.isNotEmpty())
        assertEquals(ticks, ticks.sorted())
        assertTrue("progress must never exceed 100", ticks.all { it in 0..100 })
        assertEquals(100, ticks.last())
    }

    // --- Storage precheck: fail early and explicitly, not mid-unpack ---

    @Test
    fun `download fails fast with InsufficientStorage when the volume is too small`() = runBlocking {
        val filesDir = tmp.newFolder("files19")
        val archive = makeArchive(mapOf(
            "top/model.int8.onnx" to "onnx-bytes",
            "top/tokens.txt" to "tokens",
        ))
        val m = managerWith(
            filesDir,
            clientFor(archive, "vad-bytes".toByteArray()),
            usableSpace = GigaAmModelManager.REQUIRED_FREE_BYTES - 1,
        )

        var ticked = false
        val result = m.download { _, _ -> ticked = true }

        val error = result.exceptionOrNull()
        assertTrue("expected InsufficientStorageException, got $error", error is GigaAmModelManager.InsufficientStorageException)
        error as GigaAmModelManager.InsufficientStorageException
        assertEquals(GigaAmModelManager.REQUIRED_FREE_BYTES, error.requiredBytes)
        assertEquals(GigaAmModelManager.REQUIRED_FREE_BYTES - 1, error.availableBytes)
        // Nothing was attempted: no bytes pulled, no files created.
        assertFalse("the precheck must run before any transfer", ticked)
        assertFalse(m.isReady())
        assertFalse(File(filesDir, "asr/gigaam-v3-ru").exists())
    }

    @Test
    fun `download proceeds when free space exactly meets the requirement`() = runBlocking {
        val filesDir = tmp.newFolder("files20")
        val archive = makeArchive(mapOf(
            "top/model.int8.onnx" to "onnx-bytes",
            "top/tokens.txt" to "tokens",
        ))
        val m = managerWith(
            filesDir,
            clientFor(archive, "vad-bytes".toByteArray()),
            usableSpace = GigaAmModelManager.REQUIRED_FREE_BYTES,
        )

        assertTrue(m.download { _, _ -> }.isSuccess)
        assertTrue(m.isReady())
    }

    @Test
    fun `leftovers of an interrupted run are cleared before the storage precheck`() = runBlocking {
        val filesDir = tmp.newFolder("files22")
        // What a power-off mid-unpack leaves behind: the archive in cacheDir (== filesDir in
        // these tests) and a half-written staging dir.
        val staleArchive = File(filesDir, "gigaam-v3-ru.tar.bz2").apply { writeText("stale-archive") }
        val staleStaging = File(filesDir, "asr/.staging-gigaam-v3-ru").apply { mkdirs() }
        File(staleStaging, "model.int8.onnx").writeText("half")
        val archive = makeArchive(mapOf("top/model.int8.onnx" to "new", "top/tokens.txt" to "new"))
        // The volume only meets the requirement once the leftovers are gone, measured at
        // precheck time, not at construction.
        val ctx = mockk<Context>()
        every { ctx.filesDir } returns filesDir
        every { ctx.cacheDir } returns filesDir
        val m = GigaAmModelManager(ctx, clientFor(archive, "v".toByteArray())) {
            if (staleArchive.exists() || staleStaging.exists()) {
                GigaAmModelManager.REQUIRED_FREE_BYTES - 1
            } else {
                GigaAmModelManager.REQUIRED_FREE_BYTES
            }
        }

        assertTrue(m.download { _, _ -> }.isSuccess)
        assertTrue(m.isReady())
    }

    @Test
    fun `storage precheck leaves an existing model untouched`() = runBlocking {
        val filesDir = tmp.newFolder("files21")
        val dir = File(filesDir, "asr/gigaam-v3-ru").apply { mkdirs() }
        File(dir, "model.int8.onnx").writeText("existing-onnx")
        File(dir, "tokens.txt").writeText("existing-tokens")
        File(filesDir, "asr/silero_vad.onnx").writeText("existing-vad")
        val archive = makeArchive(mapOf("top/model.int8.onnx" to "new", "top/tokens.txt" to "new"))
        val m = managerWith(filesDir, clientFor(archive, "v".toByteArray()), usableSpace = 0L)

        assertTrue(m.download { _, _ -> }.isFailure)
        assertTrue(m.isReady())
        assertEquals("existing-onnx", File(dir, "model.int8.onnx").readText())
    }
}
