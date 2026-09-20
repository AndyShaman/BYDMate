package com.bydmate.app.voice

import android.content.Context
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Downloads and unpacks the GigaAM v3 Russian ASR model (sherpa-onnx nemo-ctc archive:
 *  model.int8.onnx + tokens.txt under one top-level dir) plus the standalone silero VAD
 *  .onnx file, into filesDir/asr/. Same staging + atomic-rename shape as TtsModelManager. */
class GigaAmModelManager(
    private val context: Context,
    private val http: OkHttpClient,
    /** Free bytes on the volume backing filesDir and cacheDir -- the same /data partition
     *  on every DiLink head unit. Injectable so unit tests never depend on the host disk. */
    private val usableSpace: () -> Long = { context.filesDir.usableSpace },
) {
    /** Which stage of [download] a reported percentage belongs to. The unpack of a ~226 MiB
     *  bzip2 archive takes minutes on a DiLink 3 and pulls nothing over the network, so the
     *  UI must be able to label it as something other than "Downloading" (issue: progress
     *  appears stuck at 99% on Song Plus / DiLink 3.0). */
    enum class Phase { DOWNLOAD, UNPACK, VAD }

    /** Thrown by [download] when the storage precheck refuses to start. Carries both numbers
     *  so the UI can tell the user how much needs freeing instead of just "failed". */
    class InsufficientStorageException(
        val requiredBytes: Long,
        val availableBytes: Long,
    ) : IOException("needs $requiredBytes free bytes, only $availableBytes available")

    private fun baseDir() = File(context.filesDir, "asr/gigaam-v3-ru")

    private fun stagingDir() = File(context.filesDir, "asr/.staging-gigaam-v3-ru")

    /** v2 model dir left behind by pre-v3.7 installs; swept on the next download/delete. */
    private fun legacyDir() = File(context.filesDir, "asr/gigaam-v2-ru")

    /** v2-era staging dir orphaned by a download killed mid-unpack; swept alongside legacyDir(). */
    private fun legacyStagingDir() = File(context.filesDir, "asr/.staging-gigaam-v2-ru")

    private fun vadFile() = File(context.filesDir, "asr/silero_vad.onnx")

    fun modelPath(): String = File(baseDir(), "model.int8.onnx").absolutePath

    fun tokensPath(): String = File(baseDir(), "tokens.txt").absolutePath

    fun vadPath(): String = vadFile().absolutePath

    private fun isModelComplete(dir: File): Boolean =
        File(dir, "model.int8.onnx").let { it.isFile && it.length() > 0 } &&
            File(dir, "tokens.txt").let { it.isFile && it.length() > 0 }

    private fun isVadComplete(): Boolean = vadFile().let { it.isFile && it.length() > 0 }

    fun isReady(): Boolean = isModelComplete(baseDir()) && isVadComplete()

    /** Serializes all disk mutations of the model dir / vad file: a delete can never
     *  interleave with download's commit (unpack / rename) sections, so a cancelled
     *  download cannot recreate files the user just deleted. */
    internal val diskMutex = Mutex()

    suspend fun delete() {
        diskMutex.withLock {
            baseDir().deleteRecursively()
            stagingDir().deleteRecursively()
            legacyDir().deleteRecursively()
            legacyStagingDir().deleteRecursively()
            vadFile().delete()
        }
    }

    suspend fun download(onProgress: (Phase, Int) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val tmpArchive = File(context.cacheDir, "gigaam-v3-ru.tar.bz2")
                val tmpVad = File(context.cacheDir, "silero_vad.onnx.tmp")
                // Leftovers of a run the car's power-off killed before `finally` ran: the
                // archive in cacheDir and a half-written staging dir. They would count as
                // occupied space in the precheck below and refuse every retry although
                // freeing them is enough, with no delete button until a model is ready.
                tmpArchive.delete()
                tmpVad.delete()
                diskMutex.withLock { stagingDir().deleteRecursively() }
                // Precheck before the first byte: the peak need is the archive in cacheDir plus
                // its same-size unpack in staging. Without this the run dies ~450 MB and many
                // minutes in, as an opaque "unpack produced incomplete model dir".
                val free = usableSpace()
                if (free < REQUIRED_FREE_BYTES) throw InsufficientStorageException(REQUIRED_FREE_BYTES, free)
                try {
                    // Model archive: 0..DOWNLOAD_WEIGHT of the combined progress.
                    downloadToFile(MODEL_URL, tmpArchive) { pct ->
                        onProgress(Phase.DOWNLOAD, (pct * DOWNLOAD_WEIGHT) / 100)
                    }
                    ensureActive()
                    diskMutex.withLock {
                        // Checked under the lock: a delete that cancelled us has
                        // either finished (we bail here) or runs strictly after
                        // this whole commit section (and removes its result).
                        ensureActive()
                        val staging = stagingDir()
                        val target = baseDir()
                        staging.deleteRecursively()
                        staging.mkdirs()
                        try {
                            // Unpack: DOWNLOAD_WEIGHT..UNPACK_END of the combined progress.
                            untarFlatten(tmpArchive, staging) { pct ->
                                onProgress(Phase.UNPACK, DOWNLOAD_WEIGHT + (pct * (UNPACK_END - DOWNLOAD_WEIGHT)) / 100)
                            }
                            check(isModelComplete(staging)) { "unpack produced incomplete model dir" }
                            // Atomic publish: the final dir only ever appears as a
                            // fully verified unpack, so a process kill mid-unpack can
                            // never leave a dir that isModelComplete() accepts.
                            target.deleteRecursively()
                            check(staging.renameTo(target)) { "failed to publish staged model" }
                            // v2 -> v3 migration: the old model can never be read again
                            // (baseDir points at v3), so sweep it with the same lock held.
                            legacyDir().deleteRecursively()
                            legacyStagingDir().deleteRecursively()
                        } catch (t: Throwable) {
                            staging.deleteRecursively()
                            throw t
                        }
                    }
                    ensureActive()

                    // VAD: UNPACK_END..100 of the combined progress.
                    downloadToFile(VAD_URL, tmpVad) { pct ->
                        onProgress(Phase.VAD, UNPACK_END + (pct * (100 - UNPACK_END)) / 100)
                    }
                    ensureActive()
                    diskMutex.withLock {
                        ensureActive()
                        check(tmpVad.length() > 0) { "downloaded VAD file is empty" }
                        vadFile().delete()
                        check(tmpVad.renameTo(vadFile())) { "failed to publish VAD file" }
                    }
                    // Explicit 100: a chunked VAD response has no contentLength, so the loop
                    // above would never emit a final tick and the bar would stop short.
                    onProgress(Phase.VAD, 100)
                    // Cancelled between commit and return: don't report success --
                    // the serialized delete() removes the files, state must not flip.
                    ensureActive()
                } finally {
                    tmpArchive.delete()
                    tmpVad.delete()
                }
            }.onFailure { if (it is CancellationException) throw it }
        }

    private suspend fun downloadToFile(url: String, dest: File, onProgress: (Int) -> Unit) {
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body ?: error("empty response body")
            val total = body.contentLength()
            var read = 0L
            body.byteStream().use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); read += n
                        if (total > 0) onProgress(((read * 100) / total).toInt())
                    }
                    // fsync before the caller's rename-publish: the head unit powers off
                    // with the car (no clean shutdown), and a rename whose data blocks
                    // never hit flash survives as a full-size file of garbage (field
                    // defect: Sea Lion 07 crash loop on a corrupt model, 2026-07-16).
                    out.fd.sync()
                }
            }
        }
    }

    /** Archive has a single top-level folder; flatten it into [target].
     *  Tar Slip guard mirrors TtsModelManager.untarFlatten. Suspend: the model is a
     *  single ~226 MiB entry and diskMutex is held for the whole unpack, so the
     *  per-entry copy loop must stay cancellable (chunked copy + ensureActive).
     *
     *  [onProgress] reports 0..100 measured on the *compressed* archive file, not on the
     *  bytes written: the single tar entry gives no usable intermediate position, while the
     *  file position is exact and monotonic whatever the compression ratio turns out to be.
     *  It fires only when the whole percent changes, so a 226 MiB unpack costs ~100 calls. */
    internal suspend fun untarFlatten(archive: File, target: File, onProgress: (Int) -> Unit = {}) {
        val canonicalTarget = target.canonicalFile
        val totalBytes = archive.length().coerceAtLeast(1L)
        var lastPct = -1
        val counting = CountingInputStream(archive.inputStream()) { consumed ->
            val pct = ((consumed * 100) / totalBytes).toInt().coerceIn(0, 100)
            if (pct != lastPct) { lastPct = pct; onProgress(pct) }
        }
        BZip2CompressorInputStream(BufferedInputStream(counting)).use { bz ->
            TarArchiveInputStream(bz).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    coroutineContext.ensureActive()
                    extractEntry(tar, entry.name, entry.isDirectory, target, canonicalTarget)
                    entry = tar.nextEntry
                }
            }
        }
        // The tar stream stops at the end-of-archive marker and may leave the last few
        // compressed bytes unread, so the counter alone would stall at 99.
        onProgress(100)
    }

    /** One tar entry into [target] with its top-level folder stripped; entries that would
     *  land outside [canonicalTarget] (Tar Slip) are skipped. */
    private suspend fun extractEntry(
        tar: TarArchiveInputStream,
        name: String,
        isDirectory: Boolean,
        target: File,
        canonicalTarget: File,
    ) {
        val rel = name.substringAfter('/')
        if (rel.isBlank()) return
        val outFile = File(target, rel)
        val canonicalOut = outFile.canonicalFile
        if (canonicalOut.path != canonicalTarget.path &&
            !canonicalOut.path.startsWith(canonicalTarget.path + File.separator)) return
        if (isDirectory) outFile.mkdirs() else copyEntry(tar, outFile)
    }

    /** Chunked, cancellable copy of the current tar entry into [outFile]. */
    private suspend fun copyEntry(tar: TarArchiveInputStream, outFile: File) {
        outFile.parentFile?.mkdirs()
        outFile.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val n = tar.read(buf); if (n < 0) break
                out.write(buf, 0, n)
            }
            // fsync before the staging dir is rename-published: an
            // abrupt power-off after the rename must not leave a
            // "complete" model dir whose data blocks are garbage.
            out.fd.sync()
        }
    }

    companion object {
        const val MODEL_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
                "sherpa-onnx-nemo-ctc-giga-am-v3-russian-2025-12-16.tar.bz2"
        const val VAD_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
        const val MODEL_SIZE_LABEL = "226 МБ"

        /** Uncompressed size ~= download size: an int8 .onnx barely compresses. */
        const val MODEL_ARCHIVE_BYTES = 226L * 1024 * 1024

        /** Peak on-disk need: the archive in cacheDir plus its same-size unpack in staging
         *  (the previous model dir is only deleted once the unpack verifies), with ~10%
         *  headroom for the VAD file and filesystem overhead. DiLink 3 ships a small /data
         *  that is often nearly full, so this is checked before the first byte is pulled. */
        const val REQUIRED_FREE_BYTES = MODEL_ARCHIVE_BYTES * 22 / 10

        /** Slice of the combined progress spent pulling the model archive. The rest is split
         *  between the unpack (DOWNLOAD_WEIGHT..UNPACK_END) and the small standalone VAD file. */
        private const val DOWNLOAD_WEIGHT = 90
        private const val UNPACK_END = 98
    }
}

/** Counts bytes pulled from [delegate] and reports the running total. Used to drive unpack
 *  progress from the archive file position; no commons-compress equivalent is available on
 *  the version pinned here. */
private class CountingInputStream(
    private val delegate: InputStream,
    private val onCount: (Long) -> Unit,
) : InputStream() {
    private var count = 0L

    override fun read(): Int = delegate.read().also { if (it >= 0) bump(1) }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        delegate.read(b, off, len).also { if (it > 0) bump(it.toLong()) }

    override fun available(): Int = delegate.available()

    override fun close() = delegate.close()

    private fun bump(n: Long) {
        count += n
        onCount(count)
    }
}
