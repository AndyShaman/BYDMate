package com.bydmate.app.cluster

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.vehicle.HelperBootstrap
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.SplitTaskState
import com.bydmate.app.helper.WINDOWING_MODE_FREEFORM
import com.bydmate.app.helper.WINDOWING_MODE_FULLSCREEN
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Pullback on Android 10 head units (DiLink 3.0 / 4.0). Until the daemon's getTasks compat fix,
 * getTaskId answered null there and pullback was a silent no-op; now it returns the user's own
 * live navigator task, which sits fullscreen on the main display. Restoring such a task would
 * shuffle a window the projection never touched, so the pullback has to recognise it and only
 * refocus.
 *
 * The manager is driven through setMode(OFF) with currentMode forced to FULLSCREEN: the OFF
 * branch of applyModeLocked is exactly the pullback path, without needing a real projection.
 */
@RunWith(RobolectricTestRunner::class)
class ClusterProjectionPullbackHomeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val taskId = 42

    @Before
    fun setUp() {
        resetManagerState()
        val prefs = context.getSharedPreferences(ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        prefs.edit()
            .putBoolean(ClusterProjectionManager.KEY_AUTO_CONTAINER, false)
            .putInt(ClusterProjectionManager.KEY_DIRECT_DISPLAY_ID, 2)
            .commit()
    }

    @After
    fun tearDown() {
        resetManagerState()
        context.getSharedPreferences(ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        val shadow = shadowOf(Looper.getMainLooper())
        shadow.runToEndOfTasks()
        shadow.idle()
    }

    /**
     * Anti-vacuity: dropping the already-home guard sends setTaskWindowingMode / setTaskBounds at
     * the user's task → the coVerify(exactly = 0) assertions fail.
     */
    @Test
    fun `task already fullscreen on the main display is only refocused`() {
        val helper = helperWith(SplitTaskState(taskId, WINDOWING_MODE_FULLSCREEN, 0, 0, 1920, 1200, displayId = 0))

        pullBack(helper)

        coVerify(exactly = 0) { helper.setTaskWindowingMode(any(), any(), any()) }
        coVerify(exactly = 0) { helper.moveTaskToDisplay(any(), any()) }
        coVerify(exactly = 0) { helper.setTaskBounds(any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { helper.setFocusedTask(taskId) }
        assertEquals(
            "a task that needs no restore is a completed reclaim: the marker must be cleared",
            -1,
            context.getSharedPreferences(ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(ClusterProjectionManager.KEY_DIRECT_DISPLAY_ID, 2),
        )
    }

    /**
     * The attempt failed before anything was sent to the daemon (no cluster display on this car)
     * and no crash marker is pending: the navigator on the main screen is the user's own window.
     *
     * Anti-vacuity: dropping the placementAttempted guard makes the pullback restore that task →
     * the coVerify(exactly = 0) assertions fail.
     */
    @Test
    fun `failure before any placement leaves the task untouched`() {
        context.getSharedPreferences(ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(ClusterProjectionManager.KEY_DIRECT_DISPLAY_ID, -1).commit()
        val helper = helperWith(SplitTaskState(taskId, WINDOWING_MODE_FREEFORM, 0, 0, 900, 600, displayId = 0))

        pullBack(helper)

        coVerify(exactly = 0) { helper.setTaskWindowingMode(any(), any(), any()) }
        coVerify(exactly = 0) { helper.moveTaskToDisplay(any(), any()) }
        coVerify(exactly = 0) { helper.setTaskBounds(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { helper.setFocusedTask(any()) }
    }

    /** A task still on the cluster display takes the unchanged restore path. */
    @Test
    fun `task left freeform on the cluster is restored as before`() {
        val helper = helperWith(SplitTaskState(taskId, WINDOWING_MODE_FREEFORM, 0, 0, 1280, 480, displayId = 2))

        pullBack(helper)

        coVerify(exactly = 1) { helper.setTaskWindowingMode(taskId, WINDOWING_MODE_FULLSCREEN, any()) }
        coVerify(exactly = 1) { helper.moveTaskToDisplay(taskId, 0) }
        coVerify(exactly = 1) { helper.setTaskBounds(taskId, 0, 0, 0, 0) }
    }

    // --- helpers ---

    private fun helperWith(state: SplitTaskState): HelperClient = mockk(relaxed = true) {
        coEvery { setDisplayDensity(any(), any()) } returns true
        coEvery { getTaskId(any()) } returns taskId
        coEvery { setTaskWindowingMode(any(), any(), any()) } returns true
        coEvery { getTaskState(any()) } returns state
        coEvery { moveTaskToDisplay(any(), any()) } returns true
    }

    private fun bootstrap(): HelperBootstrap = mockk<HelperBootstrap>(relaxed = true).also {
        coEvery { it.ensureRunning() } returns true
    }

    /** Forces the manager into FULLSCREEN (setMode ignores a transition to the current mode). */
    private fun pullBack(helper: HelperClient) {
        field("currentMode").set(ClusterProjectionManager, ClusterMode.FULLSCREEN)
        ClusterProjectionManager.setMode(context, ClusterMode.OFF, helper, bootstrap())
        val shadow = shadowOf(Looper.getMainLooper())
        for (attempt in 0 until 40) {
            shadow.idle()
            if (ClusterProjectionManager.currentMode == ClusterMode.OFF) return
            Thread.sleep(25)
        }
        throw AssertionError("pullback did not finish (currentMode=${ClusterProjectionManager.currentMode})")
    }

    /** Same process-wide state reset as ClusterProjectionDirectDeathWatchTest. */
    private fun resetManagerState() {
        field("directDeathWatchJob").let { f ->
            (f.get(ClusterProjectionManager) as? Job)?.cancel()
            f.set(ClusterProjectionManager, null)
        }
        field("currentMode").set(ClusterProjectionManager, ClusterMode.OFF)
        field("projectedPackage").set(ClusterProjectionManager, null)
        field("directDisplayId").set(ClusterProjectionManager, -1)
        field("remoteDisplayId").set(ClusterProjectionManager, -1)
        field("overlayView").set(ClusterProjectionManager, null)
        field("placementAttempted").setBoolean(ClusterProjectionManager, false)
        field("journal").set(ClusterProjectionManager, null)
        field("frame").set(ClusterProjectionManager, null)
        ClusterJournal::class.java.getDeclaredField("instance")
            .apply { isAccessible = true }
            .set(null, null)
    }

    private fun field(name: String) =
        ClusterProjectionManager::class.java.getDeclaredField(name).apply { isAccessible = true }
}
