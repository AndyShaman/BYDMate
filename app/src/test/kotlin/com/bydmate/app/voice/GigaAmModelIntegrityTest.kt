package com.bydmate.app.voice

import android.content.Context
import com.bydmate.app.voice.GigaAmModelManager.Integrity
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The known-good hashes a tripped load guard checks before it lets the model be deleted. */
class GigaAmModelIntegrityTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun manager(filesDir: File): GigaAmModelManager {
        val ctx = mockk<Context>()
        every { ctx.filesDir } returns filesDir
        every { ctx.cacheDir } returns filesDir
        return GigaAmModelManager(ctx, OkHttpClient(), usableSpace = { Long.MAX_VALUE })
    }

    /** The files as an install from before the hashes has them: no sha256 records. */
    private fun install(name: String): Pair<File, GigaAmModelManager> {
        val filesDir = tmp.newFolder(name)
        File(filesDir, "asr/gigaam-v3-ru").apply { mkdirs() }.let { dir ->
            File(dir, "model.int8.onnx").writeText("onnx-bytes")
            File(dir, "tokens.txt").writeText("tokens")
        }
        File(filesDir, "asr/silero_vad.onnx").writeText("vad-bytes")
        return filesDir to manager(filesDir)
    }

    /** Both kinds of files loaded once: every hash recorded. */
    private fun recorded(name: String): Pair<File, GigaAmModelManager> = runBlocking {
        install(name).also { (_, m) ->
            m.recordIntegrityIfMissing(model = true)
            m.recordIntegrityIfMissing(model = false)
        }
    }

    @Test fun `an install older than the hashes is unverified until both kinds of files load`() = runBlocking {
        val (_, m) = install("legacy")
        assertEquals(Integrity.UNVERIFIED, m.verifyIntegrity())
        m.recordIntegrityIfMissing(model = true)
        assertEquals(Integrity.UNVERIFIED, m.verifyIntegrity())   // the VAD has not loaded yet
        m.recordIntegrityIfMissing(model = false)
        assertEquals(Integrity.INTACT, m.verifyIntegrity())
    }

    @Test fun `each changed or missing file verifies corrupt`() = runBlocking {
        val changes = listOf<Pair<String, (File) -> Unit>>(
            // Same length, other bytes: what a power cut leaves of blocks that never hit flash.
            "model bytes" to { d -> File(d, "asr/gigaam-v3-ru/model.int8.onnx").writeText("onnx-bytez") },
            "tokens bytes" to { d -> File(d, "asr/gigaam-v3-ru/tokens.txt").writeText("tokenz") },
            "vad bytes" to { d -> File(d, "asr/silero_vad.onnx").writeText("vad-bytez") },
            "vad gone" to { d -> File(d, "asr/silero_vad.onnx").delete() },
            "model emptied" to { d -> File(d, "asr/gigaam-v3-ru/model.int8.onnx").writeText("") },
        )
        changes.forEachIndexed { i, (label, change) ->
            val (dir, m) = recorded("corrupt$i")
            change(dir)
            assertEquals(label, Integrity.CORRUPT, m.verifyIntegrity())
        }
    }

    @Test fun `no files at all verify corrupt`() = runBlocking {
        assertEquals(Integrity.CORRUPT, manager(tmp.newFolder("empty")).verifyIntegrity())
    }

    @Test fun `recording never replaces an existing hash`() = runBlocking {
        val (dir, m) = recorded("keep")
        File(dir, "asr/gigaam-v3-ru/model.int8.onnx").writeText("onnx-bytez")
        m.recordIntegrityIfMissing(model = true)
        assertEquals(Integrity.CORRUPT, m.verifyIntegrity())
    }

    @Test fun `the unverified mark is cleared by a recorded hash and by delete`() = runBlocking {
        val (dir, m) = install("mark")
        m.markUnverifiedKept()
        assertTrue(m.unverifiedKeptBefore())
        m.recordIntegrityIfMissing(model = true)
        assertFalse(m.unverifiedKeptBefore())
        m.markUnverifiedKept()
        m.delete()
        assertFalse(m.unverifiedKeptBefore())
        assertFalse(File(dir, "asr/silero_vad.onnx.sha256").exists())
        assertFalse(File(dir, "asr/gigaam-v3-ru").exists())
    }

    // A load that succeeds clears the kept mark even when the hash was already recorded, so only
    // trips with no successful load between them add up to a delete.
    @Test fun `a successful load clears the kept mark when the hash already exists`() = runBlocking {
        val (_, m) = recorded("mark-intact")
        m.markUnverifiedKept()
        assertTrue(m.unverifiedKeptBefore())
        m.recordIntegrityIfMissing(model = true)
        assertFalse(m.unverifiedKeptBefore())
    }

    @Test fun `the dump names which hashes exist`() = runBlocking {
        val (_, m) = install("dump")
        assertEquals("model=no vad=no unverified_kept=false", m.manifestState())
        m.recordIntegrityIfMissing(model = true)
        assertEquals("model=yes vad=no unverified_kept=false", m.manifestState())
    }

    @Test fun `sha256 is the standard digest`() {
        val f = tmp.newFile("abc.txt").apply { writeText("abc") }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", GigaAmModelManager.sha256(f))
    }
}
