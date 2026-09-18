package com.bydmate.app.camera

import android.os.SystemClock
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.reflect.Proxy

/** Separators a firmware packs several camera tags into one string with. */
private val TAG_SEPARATORS = Regex("[,;|\\s]+")

/** Decoration around a tag in such a string: "[pano_h, front]" is a list, not a tag named "[pano_h". */
private const val TAG_TRIM_CHARS = "[]{}()\"'"

/**
 * Camera tags as the firmware reports them, in whatever shape getValidCameraTag answers with:
 * a separated string, an array, a collection or a single value. Distinct, order preserved.
 */
internal fun parseCameraTags(raw: Any?): List<String> {
    val parts = when (raw) {
        null -> emptyList()
        is String -> raw.split(TAG_SEPARATORS)
        is Array<*> -> raw.mapNotNull { it?.toString() }
        is Collection<*> -> raw.mapNotNull { it?.toString() }
        else -> listOf(raw.toString())
    }
    return parts.map { part -> part.trim { it.isWhitespace() || it in TAG_TRIM_CHARS } }
        .filter { it.isNotBlank() }
        .distinct()
}

/**
 * Reflection access to the hidden android.hardware.AVMCamera stack.
 * Sequence ported from the donor repo; every call is logged verbatim under the CameraProbe tag.
 */
class AvmCameraProbe {
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private var camera: Any? = null
    /** Preview index → surface currently bound to the open camera; close() unbinds every entry. */
    private val bound = linkedMapOf<Int, Surface>()
    var cameraId: Int = -1; private set

    /** Lines the LAST [discover] run wrote, for the dump; empty until one has run. */
    var discoverJournal: List<String> = emptyList(); private set

    /** elapsedRealtime of the last [discover] run — the same clock the fast loop measures its
     *  re-discover cool-down on; 0 until one has run. */
    var lastDiscoverAt: Long = 0L; private set

    /**
     * Looks the camera up, first under the tags this fleet is known to use, then under the tags
     * the firmware itself reports: the known ones come from Leopard 3 / Sea Lion, and the other
     * DiLink generations name their cameras differently (Song DiLink 4.0, 2026-09-18: none of the
     * four answered). The known tags stay first so the cars that already work keep their path.
     */
    fun discover(): Boolean {
        val journal = mutableListOf<String>()
        val note: (String) -> Unit = { line -> journal += line; append(line) }
        lastDiscoverAt = SystemClock.elapsedRealtime()
        try {
            val info = Class.forName("android.hardware.BmmCameraInfo")
            val count = info.getMethod("getCameraNumbers").invoke(null)
            note("getCameraNumbers=$count")
            // Tags this firmware names its own cameras with; none when it will not say.
            var firmwareTags = emptyList<String>()
            runCatching {
                val raw = info.getMethod("getValidCameraTag").invoke(null)
                note("getValidCameraTag=$raw")
                firmwareTags = parseCameraTags(raw)
            }.onFailure { note("getValidCameraTag failed: $it") }
            for (tag in CAMERA_TAGS) claimTag(info, tag, "", note)
            if (cameraId < 0) sweepFirmwareTags(info, firmwareTags, note)
        } catch (e: Throwable) {
            note("discover failed: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            discoverJournal = journal.toList()
        }
        return cameraId >= 0
    }

    /** Second pass, for the cars the known tags do not cover; the first tag that answers wins. */
    private fun sweepFirmwareTags(info: Class<*>, tags: List<String>, note: (String) -> Unit) {
        for (tag in tags.filterNot { it in CAMERA_TAGS }) {
            // One unknown tag the vendor stack refuses must not hide the ones after it.
            val claimed = runCatching { claimTag(info, tag, " via firmware tag", note) }
                .onFailure { note("getCameraId($tag) failed: $it") }
                .getOrDefault(false)
            if (claimed) break
        }
    }

    /** Reads the id behind one tag; adopts it, with its default preview size, if it is the first. */
    private fun claimTag(info: Class<*>, tag: String, suffix: String, note: (String) -> Unit): Boolean {
        val id = (info.getMethod("getCameraId", String::class.java)
            .invoke(null, tag) as Number).toInt()
        note("getCameraId($tag)=$id$suffix")
        if (id < 0 || cameraId >= 0) return false
        cameraId = id
        runCatching {
            val w = info.getMethod("getDefaultPreviewWidth", Int::class.java).invoke(null, id)
            val h = info.getMethod("getDefaultPreviewHeight", Int::class.java).invoke(null, id)
            note("defaultPreview=${w}x$h")
        }
        return true
    }

    fun open(previewIndex: Int, surface: Surface): Boolean = openBound(mapOf(previewIndex to surface))

    /**
     * Opens the camera with SEVERAL previews bound at once (the donor drives its multi-window
     * views that way), so one warm camera can feed both blind-spot windows and a side swap is
     * just an alpha flip. Same error contract as [open]: any failure closes the camera.
     */
    fun openWarm(surfaces: Map<Int, Surface>): Boolean = openBound(surfaces)

    @Synchronized
    private fun openBound(surfaces: Map<Int, Surface>): Boolean {
        if (cameraId < 0) {
            append("open: no camera id (run discover)")
            return false
        }
        if (surfaces.isEmpty()) {
            append("open: no surfaces to bind")
            return false
        }
        if (camera != null) close()
        return try {
            val avm = Class.forName("android.hardware.AVMCamera")
            var opened = avm.getMethod("open", Int::class.java).invoke(null, cameraId)
            if (opened == null) {
                append("static open returned null, trying constructor fallback")
                opened = openWithConstructor(avm)
            }
            checkNotNull(opened) { "AVMCamera.open returned null" }
            append("open($cameraId) ok: $opened")
            // Take ownership right after open: anything below can throw, and close()
            // on the catch path only works while the handle is stored here.
            camera = opened
            bound.clear()
            bound.putAll(surfaces)

            val cbType = Class.forName("android.hardware.AVMCamera\$IEventCallback")
            val proxy = Proxy.newProxyInstance(cbType.classLoader, arrayOf(cbType)) { p, m, args ->
                // Vendor thread: nothing may escape into the framework.
                try {
                    when {
                        m.declaringClass == Any::class.java -> when (m.name) {
                            "toString" -> "AvmProbeCallback"
                            "hashCode" -> System.identityHashCode(p)
                            "equals" -> p === args?.getOrNull(0)
                            else -> null
                        }
                        m.name == "onEvent" && args != null && args.size >= 4 -> {
                            append("avm event type=${args[1]} arg1=${args[2]} arg2=${args[3]}")
                            null
                        }
                        else -> {
                            append("avm callback ${m.name}(${args?.joinToString()})")
                            null
                        }
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "avm callback ${m.name} failed: $e")
                    null
                }
            }
            avm.getMethod("setEventCallback", cbType).invoke(opened, proxy)

            val addPreview = avm.getMethod("addPreviewSurface", Surface::class.java, Int::class.java)
            val setPreview = avm.getMethod("setPreviewSurface", Surface::class.java, Int::class.java)
            var allBound = true
            for ((index, previewSurface) in surfaces) {
                val added = addPreview.invoke(opened, previewSurface, index)
                val set = setPreview.invoke(opened, previewSurface, index)
                append("addPreviewSurface=$added setPreviewSurface=$set index=$index")
                if (added == false || set == false) allBound = false
            }
            // One startPreview for the whole binding set, as in the donor.
            val started = avm.getMethod("startPreview").invoke(opened)
            append("startPreview=$started indexes=${surfaces.keys.joinToString()}")
            // A refused bind or start leaves a half-open camera: the handle is live but nothing
            // will ever arrive on the surfaces, so close it instead of reporting a warm camera.
            if (!allBound || started == false) {
                append("open rejected by the vendor stack; closing")
                close()
                false
            } else {
                true
            }
        } catch (e: Throwable) {
            append("open failed: ${e.javaClass.simpleName}: ${rootMessage(e)}")
            close()
            false
        }
    }

    /**
     * Unbinds every preview, stops and closes the camera. Every step is attempted on its own —
     * a throwing stopPreview must not leave the surfaces bound or the handle open — and the
     * whole sequence is retried once if anything threw. Callers may release the surfaces only
     * after this returns, successfully or not: until then the vendor may still be writing.
     */
    @Synchronized
    fun close() {
        val cam = camera ?: return
        val avm = cam.javaClass
        var firstError: Throwable? = null
        var failed = false
        for (attempt in 1..2) {
            failed = false
            fun step(name: String, body: () -> Unit) {
                runCatching(body).onFailure {
                    failed = true
                    if (firstError == null) firstError = it
                    Log.w(TAG, "close step $name failed (attempt $attempt): $it")
                }
            }
            val rmPreview = runCatching {
                avm.getMethod("rmPreviewSurface", Surface::class.java, Int::class.java)
            }.onFailure { failed = true; if (firstError == null) firstError = it }.getOrNull()
            for ((index, surface) in bound) {
                step("rmPreviewSurface[$index]") { rmPreview?.invoke(cam, surface, index) }
            }
            step("stopPreview") { avm.getMethod("stopPreview").invoke(cam) }
            step("close") { avm.getMethod("close").invoke(cam) }
            if (!failed) break
        }
        camera = null
        bound.clear()
        val err = firstError
        append(when {
            err == null -> "closed cleanly"
            failed -> "close error after retry: ${rootMessage(err)}"
            else -> "closed after retry (first error: ${rootMessage(err)})"
        })
    }

    @Synchronized
    fun append(line: String) {
        Log.i(TAG, line)
        _log.value = (_log.value + "${System.currentTimeMillis() % 100_000} $line").takeLast(300)
    }

    private fun openWithConstructor(avm: Class<*>): Any? {
        val ctor = avm.getDeclaredConstructor(Int::class.java).apply { isAccessible = true }
        val cam = ctor.newInstance(cameraId)
        val open = avm.getDeclaredMethod("open").apply { isAccessible = true }
        return if (open.invoke(cam) == true) cam else null
    }

    private fun rootMessage(e: Throwable): String {
        var cur = e
        while (cur.cause != null && cur.cause !== cur) cur = cur.cause!!
        return "${cur.javaClass.simpleName}: ${cur.message}"
    }

    companion object {
        private val CAMERA_TAGS = listOf("pano_h", "pano_l", "apa", "byd_apa")
        private const val TAG = "CameraProbe"
    }
}
