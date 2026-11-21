package com.bydmate.app.split

import com.bydmate.app.data.camera.CameraStateMonitor
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
 *
 * The BYD camera (`com.byd.avc`: reverse, the slow-forward pop-up, 360°) is not covered either: a set
 * opened over it put the first app into the split beside the camera, and the second half stayed empty
 * once the camera closed (2026-11-21). So a launch waits until the camera is gone ([cameraClosed]),
 * and a second app that the screen shows something else instead of is launched once more, then the
 * set stops ([Outcome.RIGHT_FAILED]) rather than splitting with a foreign half.
 */
@Singleton
class AppSetLauncher(
    private val helper: HelperClient,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val cameraOn: () -> Boolean = { false },
) {
    @Inject constructor(helper: HelperClient, camera: CameraStateMonitor) :
        this(helper, { delay(it) }, { camera.active.value })

    /**
     * [NOT_SPLIT]: both apps opened, but the first one is not in the split (read back from the firmware).
     * [CAMERA]: the BYD camera stayed on screen for [CAMERA_WAIT_MS], nothing was opened.
     */
    enum class Outcome { OK, RIGHT_FAILED, LEFT_FAILED, NOT_SPLIT, CAMERA }

    suspend fun launch(set: AppSet): Outcome {
        if (!cameraClosed()) {
            Trace.event(TraceArea.APP, "appset-launch", "set" to set.name, "result" to "camera")
            return Outcome.CAMERA
        }
        val right = set.rightPkg
        if (right != null) {
            val failure = openRight(right)
            if (failure != null) {
                Trace.event(TraceArea.APP, "appset-launch", "set" to set.name, "result" to failure, "pkg" to right)
                return if (failure == "camera") Outcome.CAMERA else Outcome.RIGHT_FAILED
            }
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

    /**
     * True once the BYD camera is not on screen, at once when it is not; false when it stayed for
     * [CAMERA_WAIT_MS]. The camera counts as on screen while [CameraStateMonitor] says so or the
     * top task is the camera's.
     */
    suspend fun cameraClosed(): Boolean {
        var waited = 0L
        while (cameraShown()) {
            if (waited >= CAMERA_WAIT_MS) {
                Trace.event(TraceArea.APP, "appset-camera", "waited_ms" to waited, "closed" to false)
                return false
            }
            pause(CAMERA_POLL_MS)
            waited += CAMERA_POLL_MS
        }
        if (waited > 0) Trace.event(TraceArea.APP, "appset-camera", "waited_ms" to waited, "closed" to true)
        return true
    }

    private suspend fun cameraShown(): Boolean =
        cameraOn() || runCatching { helper.getTopTask() }.getOrNull()?.pkg == CAMERA_PACKAGE

    /**
     * Launches the right app and waits until it is on screen: null when it is (or when the screen
     * cannot be read), else the trace result. The camera that came up meanwhile is waited out and the
     * app launched once more.
     */
    private suspend fun openRight(right: String): String? {
        if (!helper.launchApp(right)) return "right_failed"
        if (waitForScreen(right)) return null
        if (!cameraClosed()) return "camera"
        if (!helper.launchApp(right)) return "right_failed"
        return if (waitForScreen(right)) null else "right_not_shown"
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
     * Without a readable top task (old daemon) its task existing is enough, as before. False only
     * when the last read top task is another app (the camera, the launcher): an unreadable screen is
     * not held against the launch.
     */
    private suspend fun waitForScreen(pkg: String): Boolean {
        var waited = 0L
        var otherOnTop = false
        while (waited < TASK_WAIT_MS) {
            val top = runCatching { helper.getTopTask() }.getOrNull()
            otherOnTop = top != null && top.pkg != pkg
            val shown = if (top != null) top.pkg == pkg else taskOf(pkg) != null
            if (shown) break
            pause(TASK_POLL_MS)
            waited += TASK_POLL_MS
        }
        if (otherOnTop) return false
        pause(SETTLE_MS)
        return true
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

        /** How long a launch waits for the BYD camera to close (reverse, parking). */
        const val CAMERA_WAIT_MS = 120_000L
        const val CAMERA_POLL_MS = 1000L

        /** The BYD camera surface, as [CameraStateMonitor] knows it. */
        const val CAMERA_PACKAGE = "com.byd.avc"
    }
}
