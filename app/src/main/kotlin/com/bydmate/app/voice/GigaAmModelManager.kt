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
import okhttp3.Response
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream

/** Downloads and unpacks the GigaAM v3 Russian ASR model (sherpa-onnx nemo-ctc archive:
 *  model.int8.onnx + tokens.txt under one top-level dir) plus the standalone silero VAD
 *  .onnx file, into filesDir/asr/. Same staging + atomic-rename shape as TtsModelManager. */
class GigaAmModelManager(
    private val context: Context,
    private val http: OkHttpClient,
) {
    private fun baseDir() = File(context.filesDir, "asr/gigaam-v3-ru")

    private fun stagingDir() = File(context.filesDir, "asr/.staging-gigaam-v3-ru")

    /** v2 model dir left behind by pre-v3.7 installs; swept on the next download/delete. */
    private fun legacyDir() = File(context.filesDir, "asr/gigaam-v2-ru")

    /** v2-era staging dir orphaned by a download killed mid-unpack; swept alongside legacyDir(). */
    private fun legacyStagingDir() = File(context.filesDir, "asr/.staging-gigaam-v2-ru")

    private fun vadFile() = File(context.filesDir, "asr/silero_vad.onnx")

    private fun modelDownloadFile() = File(context.cacheDir, "gigaam-v3-ru.tar.bz2")

    private fun vadDownloadFile() = File(context.cacheDir, "silero_vad.onnx.tmp")

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
            modelDownloadFile().delete()
            vadDownloadFile().delete()
        }
    }

    suspend fun download(onProgress: (Int) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val tmpArchive = modelDownloadFile()
                val tmpVad = vadDownloadFile()
                try {
                    // Model archive: 0..MODEL_WEIGHT of the combined progress.
                    downloadToFile(MODEL_URL, tmpArchive) { pct -> onProgress((pct * MODEL_WEIGHT) / 100) }
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
                            untarFlatten(tmpArchive, staging)
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
                            tmpArchive.delete()
                            throw t
                        }
                    }
                    tmpArchive.delete()
                    ensureActive()

                    // VAD: MODEL_WEIGHT..100 of the combined progress.
                    downloadToFile(VAD_URL, tmpVad) { pct ->
                        onProgress(MODEL_WEIGHT + (pct * (100 - MODEL_WEIGHT)) / 100)
                    }
                    ensureActive()
                    diskMutex.withLock {
                        ensureActive()
                        check(tmpVad.length() > 0) { "downloaded VAD file is empty" }
                        vadFile().delete()
                        check(tmpVad.renameTo(vadFile())) { "failed to publish VAD file" }
                    }
                    // Cancelled between commit and return: don't report success --
                    // the serialized delete() removes the files, state must not flip.
                    ensureActive()
                } finally {
                    if (isReady()) {
                        tmpArchive.delete()
                        tmpVad.delete()
                    }
                }
            }.onFailure { if (it is CancellationException) throw it }
        }

    private suspend fun downloadToFile(url: String, dest: File, onProgress: (Int) -> Unit) {
        var offset = dest.takeIf(File::isFile)?.length() ?: 0L
        var resetUsed = false

        while (true) {
            when (downloadOnce(url, dest, offset, onProgress)) {
                DownloadAttempt.COMPLETE -> return
                DownloadAttempt.RESET -> {
                    if (resetUsed) error("download resume rejected twice")
                    dest.delete()
                    offset = 0L
                    resetUsed = true
                }
            }
        }
    }

    private suspend fun downloadOnce(
        url: String,
        dest: File,
        offset: Long,
        onProgress: (Int) -> Unit,
    ): DownloadAttempt {
        val request = Request.Builder().url(url).apply {
            if (offset > 0L) header("Range", "bytes=${offset}-")
        }.build()

        http.newCall(request).execute().use { response ->
            if (response.code == 416 && offset > 0L) return DownloadAttempt.RESET
            if (!response.isSuccessful) error("HTTP ${response.code}")
            writeResponse(response, dest, offset, onProgress)
        }
        return DownloadAttempt.COMPLETE
    }

    private suspend fun writeResponse(
        response: Response,
        dest: File,
        requestedOffset: Long,
        onProgress: (Int) -> Unit,
    ) {
        val body = response.body ?: error("empty response body")
        val append = requestedOffset > 0L && response.code == 206
        val offset = if (append) requestedOffset else 0L
        val total = responseTotal(response, offset)

        var written = offset
        body.byteStream().use { input ->
            FileOutputStream(dest, append).use { output ->
                val buffer = ByteArray(64 * 1024)
                var count = input.read(buffer)
                while (count >= 0) {
                    coroutineContext.ensureActive()
                    if (count > 0) {
                        output.write(buffer, 0, count)
                        written += count
                        reportProgress(written, total, onProgress)
                    }
                    count = input.read(buffer)
                }
                output.fd.sync()
            }
        }

        if (total != null && dest.length() != total) {
            error("incomplete download: ${dest.length()}/${total}")
        }
        onProgress(100)
    }

    private fun responseTotal(response: Response, offset: Long): Long? =
        if (response.code == 206) {
            response.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                ?: response.body?.contentLength()?.takeIf { it > 0L }?.plus(offset)
        } else {
            response.body?.contentLength()?.takeIf { it > 0L }
        }

    private fun reportProgress(written: Long, total: Long?, onProgress: (Int) -> Unit) {
        if (total != null) {
            onProgress(((written * 100) / total).toInt().coerceIn(0, 100))
        }
    }

    private enum class DownloadAttempt { COMPLETE, RESET }

    /** Archive has a single top-level folder; flatten it into [target].
     *  Tar Slip guard mirrors TtsModelManager.untarFlatten. Suspend: the model is a
     *  single ~226 MiB entry and diskMutex is held for the whole unpack, so the
     *  per-entry copy loop must stay cancellable (chunked copy + ensureActive). */
    internal suspend fun untarFlatten(archive: File, target: File) {
        val canonicalTarget = target.canonicalFile
        BZip2CompressorInputStream(BufferedInputStream(archive.inputStream())).use { bz ->
            TarArchiveInputStream(bz).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    coroutineContext.ensureActive()
                    val rel = entry.name.substringAfter('/')
                    if (rel.isNotBlank()) {
                        val outFile = File(target, rel)
                        val canonicalOut = outFile.canonicalFile
                        if (canonicalOut.path != canonicalTarget.path &&
                            !canonicalOut.path.startsWith(canonicalTarget.path + File.separator)) {
                            entry = tar.nextEntry; continue
                        }
                        if (entry.isDirectory) outFile.mkdirs()
                        else {
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
                    }
                    entry = tar.nextEntry
                }
            }
        }
    }

    companion object {
        const val MODEL_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
                "sherpa-onnx-nemo-ctc-giga-am-v3-russian-2025-12-16.tar.bz2"
        const val VAD_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
        const val MODEL_SIZE_LABEL = "226 МБ"

        /** Percentage of the combined progress spent on the (much larger) model archive;
         *  the remainder covers the small standalone VAD file. */
        private const val MODEL_WEIGHT = 99
    }
}
