package com.bydmate.app.camera

import android.util.Log
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.reflect.Proxy

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

    fun discover(): Boolean {
        return try {
            val info = Class.forName("android.hardware.BmmCameraInfo")
            val count = info.getMethod("getCameraNumbers").invoke(null)
            append("getCameraNumbers=$count")
            runCatching {
                append("getValidCameraTag=${info.getMethod("getValidCameraTag").invoke(null)}")
            }.onFailure { append("getValidCameraTag failed: $it") }
            for (tag in CAMERA_TAGS) {
                val id = (info.getMethod("getCameraId", String::class.java)
                    .invoke(null, tag) as Number).toInt()
                append("getCameraId($tag)=$id")
                if (id >= 0 && cameraId < 0) {
                    cameraId = id
                    runCatching {
                        val w = info.getMethod("getDefaultPreviewWidth", Int::class.java).invoke(null, id)
                        val h = info.getMethod("getDefaultPreviewHeight", Int::class.java).invoke(null, id)
                        append("defaultPreview=${w}x$h")
                    }
                }
            }
            cameraId >= 0
        } catch (e: Throwable) {
            append("discover failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
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
