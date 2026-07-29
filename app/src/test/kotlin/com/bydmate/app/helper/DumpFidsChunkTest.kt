package com.bydmate.app.helper

import com.bydmate.app.helper.HelperBinderProtocol.DUMP_CHUNK_MAX
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Unit tests for [dumpFidsChunkBytes] — the daemon-side chunking helper used by
 * TX_DUMP_FIDS chunked transport (Q4 / F-8).
 *
 * These tests are independent of [dumpFidsCore] and the BYD SDK: they operate on
 * synthetic strings passed directly to [dumpFidsChunkBytes], so they run on any JVM.
 */
class DumpFidsChunkTest {

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /** Assembles all chunks of [dumpUtf8Bytes] and returns the byte-exact concatenation. */
    private fun assembleAllChunks(dumpUtf8Bytes: ByteArray, chunkMax: Int = DUMP_CHUNK_MAX): ByteArray {
        val out = ByteArrayOutputStream()
        var offset = 0
        while (offset < dumpUtf8Bytes.size) {
            val chunk = dumpFidsChunkBytes(dumpUtf8Bytes, offset, chunkMax)
            out.write(chunk)
            offset += chunk.size
        }
        return out.toByteArray()
    }

    /** Builds a UTF-8 dump string that is guaranteed to exceed [DUMP_CHUNK_MAX] bytes. */
    private fun largeDump(): String {
        // ASCII "FID=1" lines are 1 byte per char, so just repeat enough times.
        return "BYDAutoFeatureIds.FID_EXAMPLE=1234567890\n"
            .repeat(DUMP_CHUNK_MAX / 40 + 10)   // ~41 bytes × (1639 + 10) = ~67 KB > CHUNK_MAX
    }

    // ---------------------------------------------------------------------------
    // Basic chunking
    // ---------------------------------------------------------------------------

    @Test
    fun `chunk 0 of a large dump is exactly CHUNK_MAX bytes`() {
        val dump = largeDump()
        val dumpBytes = dump.toByteArray(Charsets.UTF_8)
        assertTrue(
            "synthesized dump (${dumpBytes.size} bytes) must exceed CHUNK_MAX ($DUMP_CHUNK_MAX)",
            dumpBytes.size > DUMP_CHUNK_MAX,
        )
        val chunk0 = dumpFidsChunkBytes(dumpBytes, 0)
        assertEquals("chunk 0 must be exactly CHUNK_MAX bytes", DUMP_CHUNK_MAX, chunk0.size)
    }

    @Test
    fun `last chunk contains the remainder bytes`() {
        val dump = largeDump()
        val dumpBytes = dump.toByteArray(Charsets.UTF_8)
        val lastOffset = (dumpBytes.size / DUMP_CHUNK_MAX) * DUMP_CHUNK_MAX
        val lastChunk = dumpFidsChunkBytes(dumpBytes, lastOffset)
        val expectedSize = dumpBytes.size - lastOffset
        assertTrue("last offset must be > 0 for anti-vacuity", lastOffset > 0)
        assertEquals("last chunk must contain the tail bytes", expectedSize, lastChunk.size)
    }

    @Test
    fun `past-end offset returns empty byte array`() {
        val dumpBytes = "FID=1".toByteArray(Charsets.UTF_8)
        val chunk = dumpFidsChunkBytes(dumpBytes, dumpBytes.size)   // offset == size
        assertEquals("past-end chunk must be empty", 0, chunk.size)
        val chunkBeyond = dumpFidsChunkBytes(dumpBytes, dumpBytes.size + 10)
        assertEquals("well-past-end chunk must be empty", 0, chunkBeyond.size)
    }

    @Test
    fun `assembling all chunks equals original bytes`() {
        val dump = largeDump()
        val dumpBytes = dump.toByteArray(Charsets.UTF_8)
        val assembled = assembleAllChunks(dumpBytes)
        assertArrayEquals(
            "assembled bytes must be byte-identical to original",
            dumpBytes,
            assembled,
        )
    }

    // ---------------------------------------------------------------------------
    // UTF-8 multibyte safety
    // ---------------------------------------------------------------------------

    @Test
    fun `multibyte chars in payload are reassembled correctly`() {
        // Kanji + Chinese ideographs — each 3 bytes in UTF-8, present in BYD SDK constant names.
        val multibyteUnit = "日本語BYD中文テスト"  // 10 chars, 28 bytes in UTF-8
        val unitBytes = multibyteUnit.toByteArray(Charsets.UTF_8).size
        // Repeat until we exceed one chunk so at least one chunk boundary falls mid-string.
        val repeated = multibyteUnit.repeat(DUMP_CHUNK_MAX / unitBytes + 2)
        val dumpBytes = repeated.toByteArray(Charsets.UTF_8)
        assertTrue(
            "repeated multibyte dump (${dumpBytes.size}) must exceed CHUNK_MAX ($DUMP_CHUNK_MAX)",
            dumpBytes.size > DUMP_CHUNK_MAX,
        )

        // The client assembles ALL bytes first, then decodes the whole buffer at once.
        // This means even if a chunk boundary falls in the middle of a multibyte sequence,
        // the final decode must reconstruct the original string perfectly.
        val assembled = assembleAllChunks(dumpBytes)
        val decoded = assembled.toString(Charsets.UTF_8)
        assertEquals("decoded assembly must equal the original multibyte string", repeated, decoded)
    }

    @Test
    fun `small dump fits in a single chunk`() {
        val small = "BYDAutoFeatureIds.FID_SOC=1246777400"
        val dumpBytes = small.toByteArray(Charsets.UTF_8)
        assertTrue("small dump must be < CHUNK_MAX", dumpBytes.size < DUMP_CHUNK_MAX)
        val chunk = dumpFidsChunkBytes(dumpBytes, 0)
        assertArrayEquals("single chunk must equal original bytes", dumpBytes, chunk)
        // One iteration exhausts the dump; offset after chunk == dumpBytes.size → loop exits.
        assertEquals("chunk size must equal dump size", dumpBytes.size, chunk.size)
    }

    // ---------------------------------------------------------------------------
    // Anti-vacuity: dropping the loop breaks the multi-chunk test
    // ---------------------------------------------------------------------------

    @Test
    fun `anti-vacuity single-chunk only gives partial result for large dump`() {
        // This test proves that a loop is necessary: reading only chunk 0 yields fewer bytes
        // than the full dump, so any assertion of equality on the full string would fail.
        val dump = largeDump()
        val dumpBytes = dump.toByteArray(Charsets.UTF_8)
        assertTrue("dump must exceed CHUNK_MAX", dumpBytes.size > DUMP_CHUNK_MAX)

        // Simulate "no loop" — only chunk 0.
        val partialBytes = dumpFidsChunkBytes(dumpBytes, 0)
        assertTrue(
            "partial (chunk-0-only) bytes must be shorter than full dump bytes",
            partialBytes.size < dumpBytes.size,
        )
        // The partial decode will be shorter and differ from the original.
        val partialDecoded = partialBytes.toString(Charsets.UTF_8)
        assertTrue(
            "chunk-0-only string must be shorter than original",
            partialDecoded.length < dump.length,
        )
    }
}
