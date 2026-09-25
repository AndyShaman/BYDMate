package com.bydmate.app.voice.online

import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.roundToInt

/** Second level of [TtsRouter]'s short-phrase cache: synthesized persona phrases kept in
 *  app-private storage, so they survive process restarts and are synthesized once per voice.
 *
 *  One file per phrase, named by the SHA-256 of the full cache key (backend, gender, voice
 *  identity, text, format version). File format, little-endian:
 *  magic "BTMP" (4 bytes) | sample rate (int32) | sample count (int32) | PCM16 mono samples.
 *  Every backend decodes PCM16, so the float -> int16 round trip is lossless.
 *
 *  Bounded by file count: a hit touches the file's mtime, a write evicts the oldest files beyond
 *  [maxFiles] (phrases of a voice no longer used age out). Every method is blocking and must be
 *  called off the audio thread; failures are logged and degrade to "not cached", never throw. */
class PhraseDiskCache(private val dir: File, private val maxFiles: Int = MAX_FILES) {

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
    // leaves only a temp file, evicted like any other by the count cap.
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

    private fun prune(keep: File) {
        val files = dir.listFiles()?.filter { it != keep } ?: return
        val excess = files.size + 1 - maxFiles
        if (excess <= 0) return
        files.sortedBy { it.lastModified() }.take(excess).forEach { it.delete() }
        Log.i(TAG, "phrase disk: evicted $excess oldest file(s), cap=$maxFiles")
    }

    private fun fileFor(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        return File(dir, digest.joinToString("") { "%02x".format(it) } + ".pcm")
    }

    companion object {
        private const val TAG = "TtsRouter" // logs belong to the router's cache story
        private const val MAGIC = 0x504D5442 // bytes "BTMP" on disk
        private const val HEADER_BYTES = 12
        private const val PCM16_MAX = 32_768f
        private const val MAX_SAMPLE_RATE = 192_000

        // Persona phrases are ~13 per pool and at most ~2.5 s (~120 KB); 96 files hold a few
        // pool/voice switches and stay within a few MB.
        const val MAX_FILES = 96

        internal fun encode(pcm: TtsPcm): ByteArray {
            val buf = ByteBuffer.allocate(HEADER_BYTES + pcm.samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(MAGIC).putInt(pcm.sampleRate).putInt(pcm.samples.size)
            for (s in pcm.samples) {
                buf.putShort((s * PCM16_MAX).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            }
            return buf.array()
        }

        /** Null for anything that is not a complete file of this format. */
        internal fun decode(bytes: ByteArray): TtsPcm? {
            if (bytes.size < HEADER_BYTES) return null
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = buf.int
            val rate = buf.int
            val count = buf.int
            val valid = magic == MAGIC && rate in 1..MAX_SAMPLE_RATE && count > 0 &&
                bytes.size.toLong() == HEADER_BYTES + count * 2L
            if (!valid) return null
            return TtsPcm(FloatArray(count) { buf.short / PCM16_MAX }, rate)
        }
    }
}
