package com.bydmate.app.camera

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One-time diagnostic probe for non-panoramic (`apa` / `byd_apa`) cameras whose side preview
 * channels stay black (#409): logs the camera tag, reported picture size and format, and channel 0
 * luma next to channels 2 and 3. Logging only; never puts anything on screen.
 */
object BlindSpotApaProbe {
    private val APA_TAGS = setOf("apa", "byd_apa")
    private const val PREVIEW_INDEX_DEFAULT = 0
    private const val PROBE_W = 128
    private const val PROBE_H = 72
    private const val PROBE_OFFSCREEN_X = -(PROBE_W + 100)
    private const val PROBE_TIMEOUT_MS = 2_000L
    private const val PROBE_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

    fun shouldProbe(
        cameraTag: String?,
        firstLuma: Int?,
        lateLuma: Int?,
        alreadyProbed: Boolean,
    ): Boolean = !alreadyProbed && cameraTag in APA_TAGS && firstLuma == 0 && lateLuma == 0

    internal suspend fun run(
        context: Context,
        probe: AvmCameraProbe,
        cameraDispatcher: CoroutineDispatcher,
        sideLumas: () -> Pair<String, String>,
    ) {
        val tag = probe.cameraTag
        runCatching {
            val meta = withContext(cameraDispatcher) { probe.cameraMeta }
            val ch0 = measureChannel0Luma(context, probe, cameraDispatcher)
            val (ch2, ch3) = sideLumas()
            Log.i(BLIND_SPOT_TAG, "apa probe tag=$tag $meta ch0=$ch0 ch2=$ch2 ch3=$ch3")
        }.onFailure { e ->
            if (e is CancellationException) throw e
            Log.w(BLIND_SPOT_TAG, "apa probe failed tag=$tag: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    internal fun formatSideLuma(live: Result<Int?>?, fallback: Int?): String =
        live?.lumaText() ?: fallback?.toString() ?: "none"

    private suspend fun measureChannel0Luma(
        context: Context,
        probe: AvmCameraProbe,
        cameraDispatcher: CoroutineDispatcher,
    ): String {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val textureReady = CompletableDeferred<SurfaceTexture>()
        val frameReady = CompletableDeferred<Unit>()
        var frames = 0
        val view = TextureView(context).apply {
            setOpaque(true)
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    textureReady.complete(st)
                }

                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit

                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = false

                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                    if (++frames >= 2) frameReady.complete(Unit)
                }
            }
        }
        val params = WindowManager.LayoutParams(
            PROBE_W, PROBE_H,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            PROBE_FLAGS,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = PROBE_OFFSCREEN_X
            y = 0
        }
        val attached = runCatching { wm.addView(view, params) }.isSuccess
        if (!attached) return "window-failed"
        var st: SurfaceTexture? = null
        var surface: Surface? = null
        return try {
            st = withTimeoutOrNull(PROBE_TIMEOUT_MS) { textureReady.await() }
                ?: return "surface-timeout"
            surface = Surface(st)
            val bound = withContext(cameraDispatcher) {
                probe.setProbeSurface(PREVIEW_INDEX_DEFAULT, surface, attach = true)
            }
            if (!bound) return "bind-failed"
            withTimeoutOrNull(PROBE_TIMEOUT_MS) { frameReady.await() }
            if (frames == 0) "frame-timeout" else frameLuma(view).lumaText()
        } finally {
            withContext(NonCancellable + cameraDispatcher) {
                surface?.let { probe.setProbeSurface(PREVIEW_INDEX_DEFAULT, it, attach = false) }
            }
            runCatching { wm.removeView(view) }
            runCatching { surface?.release() }
            runCatching { st?.release() }
        }
    }
}
