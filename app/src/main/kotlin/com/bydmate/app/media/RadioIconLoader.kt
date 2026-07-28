package com.bydmate.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Loads radio station icons from an http(s) URL or a content:// / file:// URI.
 *
 * Remote icons are cached on disk under `cacheDir/radio_icons` keyed by the URL hash, so the
 * list does not re-download on every recomposition or app start. Bitmaps are downsampled to
 * [MAX_PX] — station logos are shown in a small tile and a 2000px PNG would waste memory.
 */
object RadioIconLoader {

    private const val TAG = "RadioIconLoader"
    private const val MAX_PX = 192
    private const val MAX_BYTES = 2L * 1024 * 1024
    private const val CACHE_DIR = "radio_icons"

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    // Small in-memory cache so scrolling the list never hits disk twice for the same icon.
    private val memory = object : LinkedHashMap<String, Bitmap>(0, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 32
    }

    /** Returns the decoded icon, or null when [source] is blank, unreachable or not an image. */
    suspend fun load(context: Context, source: String?): Bitmap? {
        val key = source?.trim().orEmpty()
        if (key.isEmpty()) return null
        synchronized(memory) { memory[key] }?.let { return it }

        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                when {
                    key.startsWith("http://", true) || key.startsWith("https://", true) ->
                        loadRemote(context, key)
                    else -> loadLocal(context, key)
                }
            }.onFailure { Log.w(TAG, "icon load failed: $key", it) }.getOrNull()
        } ?: return null

        synchronized(memory) { memory[key] = bitmap }
        return bitmap
    }

    /**
     * Drops the cached copy of [source] so the next [load] re-fetches it (used after an edit).
     * Safe to call from the main thread — the disk part is handed to an IO coroutine.
     */
    fun invalidate(context: Context, source: String?) {
        val key = source?.trim().orEmpty()
        if (key.isEmpty()) return
        synchronized(memory) { memory.remove(key) }
        val appContext = context.applicationContext
        ioScope.launch { runCatching { cacheFile(appContext, key).delete() } }
    }

    private fun loadRemote(context: Context, url: String): Bitmap? {
        val cached = cacheFile(context, url)
        if (cached.isFile && cached.length() > 0) {
            decode(cached.readBytes())?.let { return it }
            cached.delete() // corrupt or non-image payload — refetch below
        }
        val request = Request.Builder().url(url).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            if (body.contentLength() > MAX_BYTES) return null
            val bytes = body.byteStream().readCapped() ?: return null
            val bitmap = decode(bytes) ?: return null
            runCatching {
                cached.parentFile?.mkdirs()
                cached.writeBytes(bytes)
            }
            return bitmap
        }
    }

    private fun loadLocal(context: Context, uri: String): Bitmap? {
        val parsed = Uri.parse(uri)
        return context.contentResolver.openInputStream(parsed)?.use { stream ->
            stream.readCapped()?.let { decode(it) }
        }
    }

    /** Reads at most [MAX_BYTES]; returns null if the stream is larger (guards against huge files). */
    private fun InputStream.readCapped(): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = read(buf)
            if (read < 0) break
            total += read
            if (total > MAX_BYTES) return null
            out.write(buf, 0, read)
        }
        return out.toByteArray()
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val largest = maxOf(bounds.outWidth, bounds.outHeight)
        if (largest <= 0) return null
        var sample = 1
        while (largest / sample > MAX_PX) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun cacheFile(context: Context, url: String): File =
        File(File(context.cacheDir, CACHE_DIR), url.hashCode().toString().replace("-", "m"))
}
