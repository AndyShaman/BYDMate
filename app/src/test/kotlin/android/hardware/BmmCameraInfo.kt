// The vendor signatures are what reflection looks up, used or not.
@file:Suppress("UnusedParameter", "FunctionOnlyReturningConstant")

package android.hardware

/** Test stand-in for the vendor camera lookup: the first known tag answers [CAMERA_ID]. */
object BmmCameraInfo {
    const val CAMERA_ID = 7

    @JvmStatic fun getCameraNumbers(): Int = 1

    @JvmStatic fun getValidCameraTag(): String = "pano_h"

    @JvmStatic fun getCameraId(tag: String): Int = if (tag == "pano_h") CAMERA_ID else -1

    @JvmStatic fun getDefaultPreviewWidth(id: Int): Int = 1280

    @JvmStatic fun getDefaultPreviewHeight(id: Int): Int = 720
}
