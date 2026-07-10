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
        return GigaAmModelManager(ctx, OkHttpClient())
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

    private fun managerWith(filesDir: File, http: OkHttpClient): GigaAmModelManager {
        val ctx = mockk<Context>()
        every { ctx.filesDir } returns filesDir
        every { ctx.cacheDir } returns filesDir
        return GigaAmModelManager(ctx, http)
    }

    // --- Step 1 (a, b): isReady() on an empty dir vs. all 3 files present ---

    @Test
    fun `isReady requires model, tokens and vad file`() {
        val filesDir = tmp.newFolder("files1")
        val m = manager(filesDir)
        assertFalse(m.isReady())

        val dir = File(filesDir, "asr/gigaam-v2-ru").apply { mkdirs() }
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

        assertTrue(m.modelPath().endsWith("asr/gigaam-v2-ru/model.int8.onnx"))
        assertTrue(m.tokensPath().endsWith("asr/gigaam-v2-ru/tokens.txt"))
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
        val staging = File(filesDir, "asr/.staging-gigaam-v2-ru").apply { mkdirs() }
        File(staging, "model.int8.onnx").writeText("x")
        File(staging, "tokens.txt").writeText("t")
        File(filesDir, "asr/silero_vad.onnx").writeText("v")   // vad present, model dir is not
        assertFalse(m.isReady())
    }

    @Test
    fun `untarFlatten drops top-level dir and preserves nested layout`() = runBlocking {
        val target = tmp.newFolder("target")
        val archive = makeArchive(mapOf(
            "sherpa-onnx-nemo-ctc-giga-am-v2-russian-2025-04-19/model.int8.onnx" to "onnx-bytes",
            "sherpa-onnx-nemo-ctc-giga-am-v2-russian-2025-04-19/tokens.txt" to "tokens",
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
        val dir = File(filesDir, "asr/gigaam-v2-ru").apply { mkdirs() }
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
        val dir = File(filesDir, "asr/gigaam-v2-ru").apply { mkdirs() }
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

        val result = m.download { }

        assertTrue(result.isSuccess)
        assertTrue(m.isReady())
        assertEquals("onnx-bytes", File(m.modelPath()).readText())
        assertEquals("tokens", File(m.tokensPath()).readText())
        assertEquals("vad-bytes", File(m.vadPath()).readText())
        assertFalse(File(filesDir, "asr/.staging-gigaam-v2-ru").exists())
    }

    @Test
    fun `failed unpack cleans the model dir`() = runBlocking {
        val filesDir = tmp.newFolder("files9")
        // Archive unpacks fine but is INCOMPLETE (no tokens.txt) -> check(isModelComplete) fails.
        val archive = makeArchive(mapOf("top/model.int8.onnx" to "x"))
        val client = clientFor(archive, "vad-bytes".toByteArray())
        val m = managerWith(filesDir, client)

        val result = m.download { }

        assertTrue(result.isFailure)
        assertFalse(m.isReady())
        assertFalse(File(filesDir, "asr/gigaam-v2-ru").exists())
        assertFalse(File(filesDir, "asr/.staging-gigaam-v2-ru").exists())
    }

    @Test
    fun `failed download keeps the previous model`() = runBlocking {
        val filesDir = tmp.newFolder("files10")
        // Seed a complete, previously installed model + vad.
        val dir = File(filesDir, "asr/gigaam-v2-ru").apply { mkdirs() }
        File(dir, "model.int8.onnx").writeText("existing-onnx")
        File(dir, "tokens.txt").writeText("existing-tokens")
        File(filesDir, "asr/silero_vad.onnx").writeText("existing-vad")

        // New download serves an INCOMPLETE archive (missing tokens.txt).
        val archive = makeArchive(mapOf("top/model.int8.onnx" to "new-onnx"))
        val client = clientFor(archive, "vad-bytes".toByteArray())
        val m = managerWith(filesDir, client)

        val result = m.download { }

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
            m.download { job.cancel() }   // cancel from the very first progress tick
        }
        job.start()
        job.join()

        assertTrue(job.isCancelled)
        assertFalse(File(filesDir, "asr/gigaam-v2-ru").exists())   // unpack never ran
        assertFalse(File(filesDir, "asr/silero_vad.onnx").exists())   // vad phase never ran
    }
}
