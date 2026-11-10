package com.bydmate.app.camera

import android.os.Looper
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * One loop step of [BlindSpotController] under Robolectric, without a race against the camera
 * thread: moves the main looper's virtual clock by [step], then alternately drains the camera
 * thread and the main looper until neither has work left. The vendor calls run on the controller's
 * single camera thread and post their continuation back to Main; an empty task queued behind them
 * returns only once they have finished and posted. The virtual clock does not move while waiting,
 * so tick counts stay exact.
 */
internal fun BlindSpotController.idleWithCameraThread(step: Duration) {
    val shadow = shadowOf(Looper.getMainLooper())
    shadow.idleFor(step)
    while (true) {
        drainCameraThread()
        if (shadow.isIdle) return
        shadow.idle()
    }
}

/** Waits (10 s) until every task queued on the camera thread so far has run. No thread: no-op. */
private fun BlindSpotController.drainCameraThread() {
    val dispatcher = cameraThreadField().get(this) as ExecutorCoroutineDispatcher? ?: return
    val marker = FutureTask { }
    try {
        dispatcher.executor.execute(marker)
    } catch (_: RejectedExecutionException) {
        return // stop() closed the thread: nothing queued on it can still post back
    }
    marker.get(10, TimeUnit.SECONDS)
}

/**
 * The controller's private `cameraThread` (its single-thread camera dispatcher). Fails the test
 * loudly when the field is renamed or retyped, so the drain can never be skipped silently.
 */
private fun cameraThreadField() = runCatching {
    BlindSpotController::class.java.getDeclaredField("cameraThread")
}.getOrElse {
    throw AssertionError("BlindSpotController.cameraThread not found: update CameraThreadDrain", it)
}.also {
    if (it.type != ExecutorCoroutineDispatcher::class.java) {
        throw AssertionError("BlindSpotController.cameraThread is ${it.type}, not ExecutorCoroutineDispatcher")
    }
    it.isAccessible = true
}
