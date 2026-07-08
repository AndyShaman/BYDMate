package com.bydmate.app.voice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.zip.ZipInputStream

class VoiceModelManager(
    private val context: Context,
    private val http: OkHttpClient,
) {
    private fun baseDir(lang: VoiceLang) = File(context.filesDir, "vosk/${lang.name.lowercase()}")

    fun modelPath(lang: VoiceLang): String = baseDir(lang).absolutePath

    fun isReady(lang: VoiceLang): Boolean =
        baseDir(lang).let { it.isDirectory && (it.listFiles()?.isNotEmpty() == true) }

    fun delete(lang: VoiceLang) { baseDir(lang).deleteRecursively() }

    suspend fun download(lang: VoiceLang, onProgress: (Int) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val tmpZip = File(context.cacheDir, "vosk-${lang.name.lowercase()}.zip")
                http.newCall(Request.Builder().url(modelUrl(lang)).build()).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val body = resp.body ?: error("empty response body")
                    val total = body.contentLength()
                    var read = 0L
                    body.byteStream().use { input ->
                        tmpZip.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf); if (n < 0) break
                                out.write(buf, 0, n); read += n
                                if (total > 0) onProgress(((read * 100) / total).toInt())
                            }
                        }
                    }
                }
                val target = baseDir(lang)
                try {
                    target.deleteRecursively(); target.mkdirs()
                    unzipFlatten(tmpZip, target)
                    check(isReady(lang)) { "unzip produced empty model dir" }
                } finally {
                    tmpZip.delete()
                }
            }
        }

    /** Vosk model zips contain a single top-level folder; flatten it into [target].
     *  Internal visibility so unit tests can call it directly. */
    internal fun unzipFlatten(zip: File, target: File) {
        val canonicalTarget = target.canonicalFile
        ZipInputStream(zip.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val rel = entry.name.substringAfter('/')  // drop top folder
                if (rel.isNotBlank()) {
                    val outFile = File(target, rel)
                    // Fix B — Zip Slip guard: reject any entry whose resolved path
                    // escapes the target directory (e.g. "model/../../escaped.txt").
                    val canonicalOut = outFile.canonicalFile
                    if (canonicalOut.path != canonicalTarget.path &&
                        !canonicalOut.path.startsWith(canonicalTarget.path + File.separator)) {
                        // Zip Slip: entry escapes the target dir — skip it.
                        zis.closeEntry(); entry = zis.nextEntry; continue
                    }
                    if (entry.isDirectory) outFile.mkdirs()
                    else { outFile.parentFile?.mkdirs(); outFile.outputStream().use { zis.copyTo(it) } }
                }
                zis.closeEntry(); entry = zis.nextEntry
            }
        }
    }

    companion object {
        fun modelUrl(lang: VoiceLang): String = when (lang) {
            VoiceLang.RU -> "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"
            VoiceLang.EN -> "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        }
        fun modelSizeMb(lang: VoiceLang): Int = when (lang) {
            VoiceLang.RU -> 45
            VoiceLang.EN -> 40
        }
    }
}
