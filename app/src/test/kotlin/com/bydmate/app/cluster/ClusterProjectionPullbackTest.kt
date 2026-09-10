package com.bydmate.app.cluster

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.vehicle.HelperBootstrap
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.SplitTaskState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Reclaim of a task left on the cluster by a dead process. Since #134 the daemon's fullscreen
 * switch can bring the task home by itself (light path), so the reclaim has to notice that: a
 * second move into the display area the task already sits in throws on AOSP 12, and a false
 * moveOk would keep [ClusterProjectionManager.KEY_DIRECT_DISPLAY_ID] set — every service start
 * would then replay this recovery against the navigator.
 */
@RunWith(RobolectricTestRunner::class)
class ClusterProjectionPullbackTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = context.getSharedPreferences(
        ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE)

    private val taskId = 42

    @Before
    fun armMarker() {
        prefs.edit().putInt(ClusterProjectionManager.KEY_DIRECT_DISPLAY_ID, 2).commit()
    }

    private fun helperWith(stateDisplayId: Int): HelperClient = mockk(relaxed = true) {
        coEvery { setDisplayDensity(any(), any()) } returns true
        coEvery { getTaskId(any()) } returns taskId
        coEvery { setTaskWindowingMode(any(), any(), any()) } returns true
        coEvery { getTaskState(any()) } returns
            SplitTaskState(taskId, 1, 0, 0, 0, 0, stateDisplayId)
        coEvery { moveTaskToDisplay(any(), any()) } returns true
    }

    private fun recover(helper: HelperClient) {
        val bootstrap = mockk<HelperBootstrap>(relaxed = true) {
            coEvery { ensureRunning() } returns true
        }
        ClusterProjectionManager.recoverStaleDirectTask(context, helper, bootstrap)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `task already on the main display is not moved again and the marker is cleared`() {
        val helper = helperWith(stateDisplayId = 0)
        recover(helper)
        coVerify(exactly = 0) { helper.moveTaskToDisplay(any(), any()) }
        assertEquals(-1, prefs.getInt(ClusterProjectionManager.KEY_DIRECT_DISPLAY_ID, 2))
    }

    @Test
    fun `task still on the cluster is moved back as before`() {
        val helper = helperWith(stateDisplayId = 2)
        recover(helper)
        coVerify(exactly = 1) { helper.moveTaskToDisplay(taskId, 0) }
        assertEquals(-1, prefs.getInt(ClusterProjectionManager.KEY_DIRECT_DISPLAY_ID, 2))
    }
}
