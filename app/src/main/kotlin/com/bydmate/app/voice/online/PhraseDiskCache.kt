package com.bydmate.app.voice.online

import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.CRC32
import kotlin.math.roundToInt

/** Second level of [TtsRouter]'s short-phrase cache: synthesized persona phrases kept in
 *  app-private storage, so they survive process restarts and are synthesized once per voice.
 *
 *  One file per phrase, named by the SHA-256 of the full cache key (backend, gender, voice
 *  identity, text, format version). File format, little-endian:
 *  CRC32 of everything below (int32) | magic "BTMP" (4 bytes) | sample rate (int32) |
 *  sample count (int32) | PCM16 mono samples. The checksum catches on-disk corruption that
 *  leaves the header fields individually plausible; a mismatch is treated like any other
 *  unreadable file. Every backend decodes PCM16, so the float -> int16 round trip is lossless.
 *
 *  Bounded by file count: a hit touches the file's mtime, a write evicts the oldest files beyond
 *  [maxFiles] (phrases of a voice no longer used age out). All disk access of one instance is
 *  serialized (read/write/prune) under one lock -- small file I/O, off the audio thread -- so
 *  prune can never delete a write's temp file mid-rename, and a delete of a corrupt file can
 *  never race a fresh write of the same key landing right after. Every method is blocking and
 *  must be called off the audio thread; failures are logged and degrade to "not cached", never
 *  throw. */
class PhraseDiskCache(private val dir: File, private val maxFiles: Int = MAX_FILES) {

    @Synchronized
    fun read(key: String): TtsPcm? {
        val file = fileFor(key)
        if (!file.exists()) return null
        val pcm = try {
            decode(file.readBytes())
        } catch (e: IOException) {
            Log.w(TAG, "phrase disk read failed: ${file.name}: ${e.message}")
            null
        }
        if (pcm == null) {
            Log.w(TAG, "phrase disk: dropping unreadable ${file.name} (${file.length()} bytes)")
            file.delete()
            return null
        }
        file.setLastModified(System.currentTimeMillis())
        return pcm
    }

    // Temp file + rename: a reader never sees a half-written phrase, and a crash mid-write
    // leaves only a temp file, cleaned up by prune's stale-tmp sweep.
    @Synchronized
    fun write(key: String, pcm: TtsPcm) {
        if (pcm.samples.isEmpty()) return
        val target = fileFor(key)
        var tmp: File? = null
        try {
            dir.mkdirs()
            tmp = File.createTempFile("phrase", ".tmp", dir)
            tmp.writeBytes(encode(pcm))
            if (!tmp.renameTo(target)) {
                Log.w(TAG, "phrase disk write failed: rename to ${target.name}")
                tmp.delete()
                return
            }
            prune(keep = target)
        } catch (e: IOException) {
            Log.w(TAG, "phrase disk write failed: ${target.name}: ${e.message}")
            tmp?.delete()
        }
    }

    // Counts and evicts only finished phrase files: a *.tmp is either a write still in the
    // try-block above (protected by the same lock, so none is in flight here) or one left behind
    // by a process death mid-write, deleted here once it is old enough to be safely stale.
    private fun prune(keep: File) {
        val all = dir.listFiles() ?: return
        val stale = all.filter { it.name.endsWith(".tmp") && staleAgeMs(it) > TMP_STALE_MS }
        stale.forEach { it.delete() }
        if (stale.isNotEmpty()) Log.i(TAG, "phrase disk: deleted ${stale.size} stale tmp file(s)")
        val files = all.filter { it != keep && it.name.endsWith(".pcm") }
        val excess = files.size + 1 - maxFiles
        if (excess <= 0) return
        files.sortedBy { it.lastModified() }.take(excess).forEach { it.delete() }
        Log.i(TAG, "phrase disk: evicted $excess oldest file(s), cap=$maxFiles")
    }

    private fun staleAgeMs(file: File) = System.currentTimeMillis() - file.lastModified()

    private fun fileFor(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        return File(dir, digest.joinToString("") { "%02x".format(it) } + ".pcm")
    }

    companion object {
        private const val TAG = "TtsRouter" // logs belong to the router's cache story
        private const val MAGIC = 0x504D5442 // bytes "BTMP" on disk
        private const val CRC_BYTES = 4
        private const val HEADER_BYTES = 16 // crc32 + magic + sample rate + sample count
        private const val PCM16_MAX = 32_768f
        private const val MIN_SAMPLE_RATE = 8_000
        private const val MAX_SAMPLE_RATE = 48_000
        // A crash mid-write leaves a *.tmp that prune() must eventually clean up, but not one
        // that a write currently in the try-block above is still populating.
        private const val TMP_STALE_MS = 10 * 60 * 1000L

        // Persona phrases are ~13 per pool and at most ~2.5 s (~120 KB); 96 files hold a few
        // pool/voice switches and stay within a few MB.
        const val MAX_FILES = 96

        internal fun encode(pcm: TtsPcm): ByteArray {
            val body = ByteBuffer.allocate(HEADER_BYTES - CRC_BYTES + pcm.samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            body.putInt(MAGIC).putInt(pcm.sampleRate).putInt(pcm.samples.size)
            for (s in pcm.samples) {
                body.putShort((s * PCM16_MAX).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            }
            val bodyBytes = body.array()
            val crc = CRC32().apply { update(bodyBytes) }.value
            val out = ByteBuffer.allocate(CRC_BYTES + bodyBytes.size).order(ByteOrder.LITTLE_ENDIAN)
            out.putInt(crc.toInt()).put(bodyBytes)
            return out.array()
        }

        /** Null for anything that is not a complete, checksummed file of this format. */
        internal fun decode(bytes: ByteArray): TtsPcm? {
            if (bytes.size < HEADER_BYTES) return null
            val storedCrc = ByteBuffer.wrap(bytes, 0, CRC_BYTES).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            val crc = CRC32().apply { update(bytes, CRC_BYTES, bytes.size - CRC_BYTES) }.value
            if (crc != storedCrc) return null
            val buf = ByteBuffer.wrap(bytes, CRC_BYTES, bytes.size - CRC_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            val magic = buf.int
            val rate = buf.int
            val count = buf.int
            val valid = magic == MAGIC && rate in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE && count > 0 &&
                bytes.size.toLong() == HEADER_BYTES + count * 2L
            if (!valid) return null
            return TtsPcm(FloatArray(count) { buf.short / PCM16_MAX }, rate)
        }
    }
}
