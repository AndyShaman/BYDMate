package com.bydmate.app.camera

import android.util.Log
import com.bydmate.app.diagnostics.Trace
import com.bydmate.app.diagnostics.TraceArea
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * How long the on-demand camera takes to appear: one log and trace line when a turn signal asks
 * for it, and one when the first honest frame of that side arrives, timed from the signal edge.
 * Never per frame. Times are elapsedRealtime; the log line carries the wall clock as well.
 */
internal class BlindSpotShowLog {
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** elapsedRealtime of the signal edge that asked for a cold camera; 0 when none is pending. */
    private var signalAt = 0L

    /** The side the last signal asked for, whose window the first frame is read from. */
    var side = BlindSpotSide.NONE
        private set

    val awaitingFrame: Boolean get() = signalAt > 0L

    /** Rising edge of a turn signal that wants the camera. */
    fun requested(side: BlindSpotSide, cameraOpen: Boolean, now: Long) {
        this.side = side
        if (cameraOpen) {
            signalAt = 0L
            Log.i(TAG, "signal $side: camera still open, reused")
            Trace.event(TraceArea.CAMERA, "reuse", "side" to side)
        } else {
            signalAt = now
            Log.i(TAG, "signal $side: open requested at ${stamp.format(Date())}")
            Trace.event(TraceArea.CAMERA, "open-request", "side" to side)
        }
    }

    /** The first honest frame of the side asked for; [openedAt] is when the vendor open returned. */
    fun firstFrame(frameAt: Long, openedAt: Long) {
        if (!awaitingFrame) return
        val latencyMs = frameAt - signalAt
        val openMs = openedAt - signalAt
        Log.i(
            TAG,
            "first frame $side at ${stamp.format(Date())}: $latencyMs ms after the signal " +
                "(camera open at $openMs ms)",
        )
        Trace.event(TraceArea.CAMERA, "first-frame", "side" to side, "ms" to latencyMs, "open_ms" to openMs)
        signalAt = 0L
    }

    /** The camera closed: a frame still pending belongs to no show any more. */
    fun reset() {
        signalAt = 0L
    }

    private companion object {
        const val TAG = "BlindSpot"
    }
}
