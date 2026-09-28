package com.bydmate.app.platform

import android.os.Build
import android.util.Log
import com.bydmate.app.diagnostics.Trace
import com.bydmate.app.diagnostics.TraceArea

/**
 * The one place that decides "old head unit": the Android 10 firmwares (DiLink 3.0, its trinket
 * variant, DiLink 4.0). Matched on the SDK level, not on the fingerprint: the trinket firmware of
 * the same cars has no "DiLink3" in it.
 */
object LegacyHeadUnit {

    // Recorded by the log recorder; the service start line belongs with the service's own.
    private const val TAG = "TrackingService"

    val isAndroid10: Boolean get() = isAndroid10(Build.VERSION.SDK_INT)

    internal fun isAndroid10(sdkInt: Int): Boolean = sdkInt <= 29

    /** Once per service start, so every log and trace shows which branch this car runs. */
    fun noteServiceStart() {
        val release = Build.VERSION.RELEASE
        val sdk = Build.VERSION.SDK_INT
        Log.i(TAG, "head unit: android=$release sdk=$sdk legacy=$isAndroid10")
        Trace.event(TraceArea.APP, "head-unit", "android" to release, "sdk" to sdk, "legacy" to isAndroid10)
    }
}
