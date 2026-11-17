package com.bydmate.app.split

import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.diagnostics.Trace
import com.bydmate.app.diagnostics.TraceArea
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Opens an [AppSet] on the Android 10 head units (DiLink 3 / 4): the firmware has its own split
 * screen there, which does not obey the freeform windows BYDMate's own split opens on newer firmware.
 *
 * The firmware puts the app that is on screen into the right half when a launch asks for the left
 * one (`am start --windowingMode 3`), so the right app is opened first, as an ordinary fullscreen
 * launch, and the left app is launched into the split after it ([WINDOWING_SPLIT_PRIMARY]). Both
 * launches go through the helper daemon: a command typed in a terminal app loses the screen to the
 * app it starts, and the second command never follows. Verified on a DiLink 3.0 Atto 3, 2026-11-17.
 */
@Singleton
class AppSetLauncher(
    private val helper: HelperClient,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    @Inject constructor(helper: HelperClient) : this(helper, { delay(it) })

    enum class Outcome { OK, RIGHT_FAILED, LEFT_FAILED }

    suspend fun launch(set: AppSet): Outcome {
        val right = set.rightPkg
        if (right != null) {
            if (!helper.launchApp(right)) {
                Trace.event(TraceArea.APP, "appset-launch", "set" to set.name, "result" to "right_failed", "pkg" to right)
                return Outcome.RIGHT_FAILED
            }
            waitForTask(right)
        }
        val ok = helper.launchAppInWindowingMode(set.leftPkg, WINDOWING_SPLIT_PRIMARY)
        Trace.event(TraceArea.APP, "appset-launch", "set" to set.name, "left" to set.leftPkg,
            "right" to (right ?: "-"), "result" to if (ok) "ok" else "left_failed")
        return if (ok) Outcome.OK else Outcome.LEFT_FAILED
    }

    /** The right app is on screen when its task exists; the settle time lets it become the active one. */
    private suspend fun waitForTask(pkg: String) {
        var waited = 0L
        while (waited < TASK_WAIT_MS) {
            if (runCatching { helper.getTaskState(pkg)?.taskId }.getOrNull()?.let { it > 0 } == true) break
            pause(TASK_POLL_MS)
            waited += TASK_POLL_MS
        }
        pause(SETTLE_MS)
    }

    companion object {
        /** WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY. */
        const val WINDOWING_SPLIT_PRIMARY = 3

        /** How long the right app may take to show up (a cold start of a heavy app). */
        const val TASK_WAIT_MS = 6000L
        const val TASK_POLL_MS = 250L

        /** After the task exists: the right app has to be the one on screen when the left one is launched. */
        const val SETTLE_MS = 1500L
    }
}
