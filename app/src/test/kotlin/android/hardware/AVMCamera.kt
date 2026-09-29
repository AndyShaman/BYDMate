// The vendor signatures are what reflection looks up, used or not.
@file:Suppress("UnusedParameter", "FunctionOnlyReturningConstant")

package android.hardware

import android.view.Surface

/**
 * Test stand-in for the vendor camera AvmCameraProbe reaches by reflection: the SDK has no such
 * class, so without this one every open in a unit test fails before it reaches the vendor calls.
 * The companion fields script the next camera; [last] is the one the probe holds.
 */
class AVMCamera(val id: Int) {

    /** Same shape as the vendor callback: the camera, the event type and its two arguments. */
    interface IEventCallback {
        fun onEvent(camera: Any?, type: Int, arg1: Int, arg2: Int)
    }

    private var callback: IEventCallback? = null

    fun setEventCallback(cb: IEventCallback) {
        callback = cb
    }

    fun addPreviewSurface(surface: Surface, index: Int): Boolean = true

    fun setPreviewSurface(surface: Surface, index: Int): Boolean = true

    fun startPreview(): Boolean = startResult

    fun rmPreviewSurface(surface: Surface, index: Int) = Unit

    fun stopPreview() {
        if (stopFailures > 0) {
            stopFailures--
            error("stop refused")
        }
    }

    fun close() = Unit

    /** One vendor event, as the camera service would deliver it. */
    fun fire(type: Int, arg1: Int = 0, arg2: Int = 0) {
        callback!!.onEvent(this, type, arg1, arg2)
    }

    companion object {
        var openError: Throwable? = null
        var startResult = true
        /** How many stopPreview calls throw before one goes through. */
        var stopFailures = 0
        var last: AVMCamera? = null

        @JvmStatic
        fun open(id: Int): AVMCamera {
            openError?.let { throw it }
            return AVMCamera(id).also { last = it }
        }

        fun reset() {
            openError = null
            startResult = true
            stopFailures = 0
            last = null
        }
    }
}
