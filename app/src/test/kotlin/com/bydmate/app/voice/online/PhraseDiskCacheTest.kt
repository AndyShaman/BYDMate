package com.bydmate.app.voice.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Low-level tests of the on-disk phrase format (CRC32, sample rate range) and the prune sweep --
 *  the higher-level disk-cache behavior (through [TtsRouter]) is covered at the end of
 *  TtsRouterTest. */
class PhraseDiskCacheTest {

    @get:Rule val tmp = TemporaryFolder()

    // --- format validation (review finding: corrupted content with a valid structure is accepted forever) ---

    @Test
    fun `a flipped PCM byte fails the checksum and is a miss that deletes the file`() {
        val dir = tmp.newFolder()
        val cache = PhraseDiskCache(dir)
        cache.write("key", TtsPcm(floatArrayOf(0.1f, 0.2f, 0.3f), 24_000))
        val file = dir.listFiles()!!.single()
        val bytes = file.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1] + 1).toByte() // flip one PCM byte, length unchanged
        file.writeBytes(bytes)

        assertNull(cache.read("key"))
        assertTrue("corrupt file must be deleted on a checksum miss", dir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `a sample rate outside 8000 to 48000 is a miss that deletes the file`() {
        val dir = tmp.newFolder()
        val cache = PhraseDiskCache(dir)
        cache.write("key", TtsPcm(floatArrayOf(0.1f), 24_000))
        val file = dir.listFiles()!!.single()
        // Freshly encoded, so its checksum matches its own content -- isolates the rate check
        // from the checksum check above.
        file.writeBytes(PhraseDiskCache.encode(TtsPcm(floatArrayOf(0.1f), 96_000)))

        assertNull(cache.read("key"))
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    // --- prune (review finding: prune() can delete a temp file of a write in progress) ---

    @Test
    fun `prune deletes a stale tmp file left by a crash and still respects the cap`() {
        val dir = tmp.newFolder()
        val cache = PhraseDiskCache(dir, maxFiles = 3)
        repeat(3) { cache.write("key-$it", TtsPcm(floatArrayOf(0.1f), 24_000)) }
        val staleTmp = File.createTempFile("phrase", ".tmp", dir)
        staleTmp.writeBytes(byteArrayOf(1, 2, 3))
        staleTmp.setLastModified(System.currentTimeMillis() - 11 * 60 * 1000) // older than the 10 min grace

        cache.write("key-3", TtsPcm(floatArrayOf(0.1f), 24_000)) // triggers prune()

        assertFalse("a stale tmp is presumed abandoned by a dead write and must be swept", staleTmp.exists())
        assertEquals(3, dir.listFiles()!!.count { it.name.endsWith(".pcm") })
    }

    @Test
    fun `prune does not delete a fresh tmp file that a write may still be populating`() {
        val dir = tmp.newFolder()
        val cache = PhraseDiskCache(dir, maxFiles = 3)
        val freshTmp = File.createTempFile("phrase", ".tmp", dir)
        freshTmp.writeBytes(byteArrayOf(1, 2, 3))

        cache.write("key", TtsPcm(floatArrayOf(0.1f), 24_000)) // triggers prune()

        assertTrue("a fresh tmp could still be a write in progress and must survive", freshTmp.exists())
    }
}
