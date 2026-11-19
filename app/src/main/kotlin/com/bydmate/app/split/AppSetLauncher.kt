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
 *
 * Apps that are already running (the split was left with Home) came back fullscreen, the screen
 * rotated and stayed whole, while the launch reported success (2026-11-19). So the right app is
 * waited for until it is on top, the left one is moved by the daemon into the split when it is
 * running, and its windowing mode is read back: one retry, then [Outcome.NOT_SPLIT].
 */
@Singleton
class AppSetLauncher(
    private val helper: HelperClient,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    @Inject constructor(helper: HelperClient) : this(helper, { delay(it) })

    /** [NOT_SPLIT]: both apps opened, but the first one is not in the split (read back from the firmware). */
    enum class Outcome { OK, RIGHT_FAILED, LEFT_FAILED, NOT_SPLIT }

    suspend fun launch(set: AppSet): Outcome {
        val right = set.rightPkg
        if (right != null) {
            if (!helper.launchApp(right)) {
                Trace.event(TraceArea.APP, "appset-launch", "set" to set.name, "result" to "right_failed", "pkg" to right)
                return Outcome.RIGHT_FAILED
            }
            waitForScreen(right)
        }
        // A left app that is already running is moved into the split by the daemon instead of
        // being started; the trace says which case this was.
        val leftRunning = taskOf(set.leftPkg) != null
        if (!helper.launchAppInWindowingMode(set.leftPkg, WINDOWING_SPLIT_PRIMARY)) {
            trace(set, leftRunning, mode = null, retried = false, result = "left_failed")
            return Outcome.LEFT_FAILED
        }
        var mode = settledMode(set.leftPkg)
        var retried = false
        // Not in the split: the right app can still have been taking over the screen (a cold start
        // behind a splash screen). The left task exists by now, so the retry moves it by id.
        if (mode != null && mode != WINDOWING_SPLIT_PRIMARY) {
            retried = true
            if (helper.launchAppInWindowingMode(set.leftPkg, WINDOWING_SPLIT_PRIMARY)) mode = settledMode(set.leftPkg)
        }
        // An unreadable mode (old daemon, no task list) is not held against the launch.
        val outcome = if (mode == null || mode == WINDOWING_SPLIT_PRIMARY) Outcome.OK else Outcome.NOT_SPLIT
        trace(set, leftRunning, mode, retried, if (outcome == Outcome.OK) "ok" else "not_split")
        return outcome
    }

    private fun trace(set: AppSet, leftRunning: Boolean, mode: Int?, retried: Boolean, result: String) =
        Trace.event(TraceArea.APP, "appset-launch", "set" to set.name, "left" to set.leftPkg,
            "right" to (set.rightPkg ?: "-"), "left_running" to leftRunning, "mode" to (mode ?: "?"),
            "retry" to retried, "result" to result)

    private suspend fun taskOf(pkg: String) = runCatching { helper.getTaskState(pkg) }.getOrNull()?.takeIf { it.taskId > 0 }

    /** The windowing mode of [pkg]'s task once the firmware has laid the split out, or null when unreadable. */
    private suspend fun settledMode(pkg: String): Int? {
        pause(VERIFY_MS)
        return taskOf(pkg)?.windowingMode
    }

    /**
     * The right app is on screen when it is the top task; the settle time lets it finish coming up.
     * Without a readable top task (old daemon) its task existing is enough, as before.
     */
    private suspend fun waitForScreen(pkg: String) {
        var waited = 0L
        while (waited < TASK_WAIT_MS) {
            val top = runCatching { helper.getTopTask() }.getOrNull()
            val shown = if (top != null) top.pkg == pkg else taskOf(pkg) != null
            if (shown) break
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

        /** After the right app is on top: it has to be the one on screen when the left one is launched. */
        const val SETTLE_MS = 1500L

        /** After the left launch: the firmware has laid the split out by then. */
        const val VERIFY_MS = 1500L
    }
}
