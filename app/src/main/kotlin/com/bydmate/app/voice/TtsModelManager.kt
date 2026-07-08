package com.bydmate.app.voice

import android.content.Context
import android.util.Log
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

/** Downloads and unpacks a TTS voice archive, selected by [TtsVoice.modelDirId]
 *  (several voices can share one archive/dir -- see [TtsVoiceEngine.VITS_MULTI]).
 *  Archive layout: one top-level dir with model .onnx + tokens.txt, plus either
 *  espeak-ng-data/ (PIPER) or stress.tsv (VITS_MULTI), under
 *  filesDir/tts/<modelDirId>. Same shape as VoiceModelManager for the Vosk ASR
 *  models. */
class TtsModelManager(
    private val context: Context,
    private val http: OkHttpClient,
) {
    private fun baseDir(modelDirId: String) = File(context.filesDir, "tts/$modelDirId")

    private fun stagingDir(modelDirId: String) = File(context.filesDir, "tts/.staging-$modelDirId")

    private fun stressStagingDir(modelDirId: String) = File(context.filesDir, "tts/.staging-$modelDirId-stress")

    fun modelDirPath(modelDirId: String): String = baseDir(modelDirId).absolutePath

    /** The archive's .onnx filename is not hardcoded — find the single model file.
     *  AppleDouble sidecar files (._*.onnx) are skipped; they crash sherpa-onnx. */
    fun onnxFile(modelDirId: String): File? =
        baseDir(modelDirId).listFiles()?.firstOrNull { it.isFile && it.name.endsWith(".onnx") && !it.name.startsWith("._") }

    private fun hasRealOnnx(dir: File): Boolean =
        dir.listFiles()?.any { it.isFile && it.name.endsWith(".onnx") && !it.name.startsWith("._") } == true

    /** PIPER archives need espeak-ng-data/ for phonemization; the VITS_MULTI
     *  archive carries its own stress.tsv instead and ships no espeak data.
     *  Supertonic ships a fixed 7-file layout and NO tokens.txt. */
    private fun isComplete(dir: File, engine: TtsVoiceEngine): Boolean = when (engine) {
        TtsVoiceEngine.PIPER ->
            hasRealOnnx(dir) && File(dir, "tokens.txt").isFile && File(dir, "espeak-ng-data").isDirectory
        TtsVoiceEngine.VITS_MULTI ->
            hasRealOnnx(dir) && File(dir, "tokens.txt").isFile && File(dir, "stress.tsv").isFile
        // Supertonic ships a fixed 7-file layout and NO tokens.txt.
        TtsVoiceEngine.SUPERTONIC -> SUPERTONIC_FILES.all { File(dir, it).isFile }
    }

    fun isReady(voice: TtsVoice): Boolean = isComplete(baseDir(voice.modelDirId), voice.engine)

    /** Serializes all disk mutations of the model dir: a delete can never
     *  interleave with download's commit (unpack) section, so a cancelled
     *  download cannot recreate a dir the user just deleted. */
    internal val diskMutex = Mutex()

    suspend fun delete(modelDirId: String) {
        diskMutex.withLock {
            baseDir(modelDirId).deleteRecursively()
            stagingDir(modelDirId).deleteRecursively()
        }
    }

    suspend fun download(voice: TtsVoice, onProgress: (Int) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val tmp = File(context.cacheDir, "tts-${voice.modelDirId}.tar.bz2")
                try {
                    http.newCall(Request.Builder().url(voice.url).build()).execute().use { resp ->
                        if (!resp.isSuccessful) error("HTTP ${resp.code}")
                        val body = resp.body ?: error("empty response body")
                        val total = body.contentLength()
                        var read = 0L
                        body.byteStream().use { input ->
                            tmp.outputStream().use { out ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    ensureActive()
                                    val n = input.read(buf); if (n < 0) break
                                    out.write(buf, 0, n); read += n
                                    if (total > 0) onProgress(((read * 100) / total).toInt())
                                }
                            }
                        }
                    }
                    diskMutex.withLock {
                        // Checked under the lock: a delete that cancelled us has
                        // either finished (we bail here) or runs strictly after
                        // this whole commit section (and removes its result).
                        ensureActive()
                        val staging = stagingDir(voice.modelDirId)
                        val target = baseDir(voice.modelDirId)
                        staging.deleteRecursively()
                        staging.mkdirs()
                        try {
                            untarFlatten(tmp, staging)
                            check(isComplete(staging, voice.engine)) { "unpack produced incomplete model dir" }
                            // Atomic publish: the final dir only ever appears as a
                            // fully verified unpack, so a process kill mid-unpack can
                            // never leave a dir that isReady() accepts.
                            target.deleteRecursively()
                            check(staging.renameTo(target)) { "failed to publish staged model" }
                        } catch (t: Throwable) {
                            staging.deleteRecursively()
                            throw t
                        }
                    }
                    // Cancelled between commit and return: don't report success —
                    // the serialized delete() removes the dir, state must not flip.
                    ensureActive()
                } finally {
                    tmp.delete()
                }
            }.onFailure { if (it is CancellationException) throw it }
        }

    /** Supertonic archives (k2-fsa upstream) ship no stress dictionary; fetches our
     *  stress.tsv release asset into the voice dir so UPPERCASE stress marking works.
     *  Fail-soft: any error just leaves marking disabled. Returns true when the dict
     *  is present (already or downloaded just now). */
    suspend fun ensureStressDict(voice: TtsVoice): Boolean =
        withContext(Dispatchers.IO) {
            if (voice.engine != TtsVoiceEngine.SUPERTONIC) return@withContext true
            val targetDir = baseDir(voice.modelDirId)
            val targetFile = File(targetDir, STRESS_DICT_FILE)
            if (targetFile.isFile) return@withContext true
            if (!targetDir.isDirectory) return@withContext false

            val tmp = File(context.cacheDir, "tts-${voice.modelDirId}-stress-${System.nanoTime()}.tar.bz2")
            runCatching {
                try {
                    http.newCall(Request.Builder().url(STRESS_DICT_URL).build()).execute().use { resp ->
                        if (!resp.isSuccessful) error("HTTP ${resp.code}")
                        val body = resp.body ?: error("empty response body")
                        body.byteStream().use { input ->
                            tmp.outputStream().use { out ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    ensureActive()
                                    val n = input.read(buf); if (n < 0) break
                                    out.write(buf, 0, n)
                                }
                            }
                        }
                    }
                    diskMutex.withLock {
                        ensureActive()
                        if (!targetDir.isDirectory) return@withLock false
                        if (targetFile.isFile) return@withLock true
                        val staging = stressStagingDir(voice.modelDirId)
                        staging.deleteRecursively()
                        staging.mkdirs()
                        try {
                            untarFlatten(tmp, staging)
                            val staged = File(staging, STRESS_DICT_FILE)
                            // A truncated download or a wrong/tiny release asset can still unpack a
                            // short but valid stress.tsv; publishing it would poison the cache
                            // permanently (the isFile short-circuit above trusts it forever),
                            // silently disabling stress for almost every word. Require a plausible
                            // size so a bad asset stays retryable instead of cached.
                            check(staged.isFile && staged.length() >= STRESS_DICT_MIN_BYTES) {
                                "stress dictionary implausibly small (${staged.length()} bytes)"
                            }
                            check(staged.renameTo(targetFile)) { "failed to publish stress dictionary" }
                            true
                        } finally {
                            staging.deleteRecursively()
                        }
                    }
                } finally {
                    tmp.delete()
                }
            }.onFailure {
                if (it is CancellationException) throw it
                Log.w(TAG, "failed to ensure stress dictionary for ${voice.modelDirId}", it)
            }.getOrDefault(false)
        }

    /** Archive has a single top-level folder; flatten it into [target].
     *  Tar Slip guard mirrors VoiceModelManager.unzipFlatten's Zip Slip guard. */
    internal fun untarFlatten(archive: File, target: File) {
        val canonicalTarget = target.canonicalFile
        BZip2CompressorInputStream(BufferedInputStream(archive.inputStream())).use { bz ->
            TarArchiveInputStream(bz).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    val rel = entry.name.substringAfter('/')
                    if (isJunkPath(rel)) { entry = tar.nextEntry; continue }
                    if (rel.isNotBlank()) {
                        val outFile = File(target, rel)
                        val canonicalOut = outFile.canonicalFile
                        if (canonicalOut.path != canonicalTarget.path &&
                            !canonicalOut.path.startsWith(canonicalTarget.path + File.separator)) {
                            entry = tar.nextEntry; continue
                        }
                        if (entry.isDirectory) outFile.mkdirs()
                        else { outFile.parentFile?.mkdirs(); outFile.outputStream().use { tar.copyTo(it) } }
                    }
                    entry = tar.nextEntry
                }
            }
        }
    }

    companion object {
        private const val TAG = "TtsModelManager"
        const val DEFAULT_VOICE_ID = "dmitri"
        internal const val STRESS_DICT_URL = "https://github.com/AndyShaman/BYDMate/releases/download/tts-voices-v1/stress-ru.tar.bz2"
        internal const val STRESS_DICT_FILE = "stress.tsv"
        /** Sanity floor for a freshly downloaded dictionary: the real ru asset is ~11.9 MB /
         *  529k lines (tts-voices-v1), so anything far smaller is a truncated or wrong asset
         *  and must not be renamed into place (see ensureStressDict). */
        internal const val STRESS_DICT_MIN_BYTES = 1_000_000L

        /** Fixed file set for a Supertonic archive: 4-model flow-matching pipeline,
         *  char-based (no tokens.txt). All 7 files must be present for the model to load. */
        internal val SUPERTONIC_FILES = listOf(
            "duration_predictor.int8.onnx", "text_encoder.int8.onnx", "vector_estimator.int8.onnx",
            "vocoder.int8.onnx", "tts.json", "unicode_indexer.bin", "voice.bin",
        )

        /** macOS tar pollutes archives with AppleDouble (._*) and Finder junk; commons-compress
         *  faithfully extracts them, and a ._*.onnx picked up by onnxFile() crashes sherpa-onnx
         *  (field defect: the Artem voice went silent, 2026-07-08). */
        internal fun isJunkPath(path: String): Boolean =
            path.split('/').any { it.startsWith("._") || it == ".DS_Store" || it == "__MACOSX" }
    }
}
