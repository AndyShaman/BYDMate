package com.bydmate.app.cluster

import android.content.Context
import android.os.Looper
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.R
import com.bydmate.app.data.vehicle.FreeformLaunchResult
import com.bydmate.app.data.vehicle.HelperBootstrap
import com.bydmate.app.data.vehicle.HelperClient
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowToast
import org.robolectric.shadows.ShadowWindowManagerGlobal
import org.robolectric.shadows.ShadowWindowManagerImpl

/**
 * Verifies the user-visible Toast when [ClusterProjectionManager] cannot find a cluster display (#478).
 * Setup and state reset follow [ClusterProjectionSendFailureTest].
 */
@RunWith(RobolectricTestRunner::class)
class ClusterProjectionNoDisplayToastTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var addedDisplayId: Int = -1

    @Before
    fun setUp() {
        ShadowWindowManagerGlobal.reset()
        ShadowWindowManagerImpl.reset()
        ShadowChoreographer.setPaused(true)
        ShadowSettings.setCanDrawOverlays(true)
        ShadowToast.reset()
        resetManagerState()

        val prefs = context.getSharedPreferences(ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        prefs.edit()
            .putBoolean(ClusterProjectionManager.KEY_DIRECT_PROJECTION, true)
            .putBoolean(ClusterProjectionManager.KEY_AUTO_CONTAINER, false)
            .commit()
    }

    @After
    fun tearDown() {
        ShadowSettings.setCanDrawOverlays(false)
        ShadowToast.reset()
        if (addedDisplayId != -1) {
            ShadowDisplayManager.removeDisplay(addedDisplayId)
            addedDisplayId = -1
        }
        context.getSharedPreferences(ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        val shadow = shadowOf(Looper.getMainLooper())
        shadow.runToEndOfTasks()
        shadow.idle()
    }

    @Test
    fun `setMode FULLSCREEN without a cluster display shows no-display toast`() {
        val helper = mockk<HelperClient>(relaxed = true)
        val bootstrap = mockk<HelperBootstrap>(relaxed = true)
        coEvery { bootstrap.ensureRunning() } returns true

        ClusterProjectionManager.setMode(context, ClusterMode.FULLSCREEN, helper, bootstrap)
        drainUntilFailure()

        assertEquals(
            context.getString(R.string.cluster_no_display_for_output),
            ShadowToast.getTextOfLatestToast(),
        )
    }

    @Test
    fun `setMode FULLSCREEN with a cluster display that fails later does not show no-display toast`() {
        addedDisplayId = ShadowDisplayManager.addDisplay("w1280dp-h480dp", "XDJAScreenProjection_1")

        val helper = mockk<HelperClient>(relaxed = true)
        coEvery {
            helper.launchFreeform(any(), any(), any(), any(), any(), any(), any())
        } returns FreeformLaunchResult.UNAVAILABLE

        val bootstrap = mockk<HelperBootstrap>(relaxed = true)
        coEvery { bootstrap.ensureRunning() } returns true

        ClusterProjectionManager.setMode(context, ClusterMode.FULLSCREEN, helper, bootstrap)
        drainUntilSurfaceWait()

        val shadow = shadowOf(Looper.getMainLooper())
        shadow.runToEndOfTasks()
        shadow.idle()

        assertEquals("projection", ClusterProjectionManager.lastFailure)
        assertNotEquals(
            context.getString(R.string.cluster_no_display_for_output),
            ShadowToast.getTextOfLatestToast(),
        )
    }

    private fun drainUntilFailure() {
        val shadow = shadowOf(Looper.getMainLooper())
        val deadline = System.currentTimeMillis() + 10_000
        while (ClusterProjectionManager.lastFailure == null && System.currentTimeMillis() < deadline) {
            shadow.idle()
            if (ClusterProjectionManager.lastFailure == null) Thread.sleep(10)
        }
        shadow.runToEndOfTasks()
        shadow.idle()
    }

    private fun drainUntilSurfaceWait() {
        val shadow = shadowOf(Looper.getMainLooper())
        val deadline = System.currentTimeMillis() + 10_000
        while (overlayView() == null && System.currentTimeMillis() < deadline) {
            shadow.idle()
            if (overlayView() == null) Thread.sleep(10)
        }
        checkNotNull(overlayView()) {
            "ClusterProjectionManager.overlayView is null after 10 s"
        }
    }

    private fun overlayView(): FrameLayout? =
        field("overlayView").get(ClusterProjectionManager) as? FrameLayout

    private fun resetManagerState() {
        field("directDeathWatchJob").let { f ->
            (f.get(ClusterProjectionManager) as? Job)?.cancel()
            f.set(ClusterProjectionManager, null)
        }
        field("currentMode").set(ClusterProjectionManager, ClusterMode.OFF)
        field("lastFailure").set(ClusterProjectionManager, null)
        field("projectedPackage").set(ClusterProjectionManager, null)
        field("directDisplayId").set(ClusterProjectionManager, -1)
        field("remoteDisplayId").set(ClusterProjectionManager, -1)
        field("overlayView").set(ClusterProjectionManager, null)
        field("journal").set(ClusterProjectionManager, null)
        field("frame").set(ClusterProjectionManager, null)
        ClusterJournal::class.java.getDeclaredField("instance")
            .apply { isAccessible = true }
            .set(null, null)
    }

    private fun field(name: String) =
        ClusterProjectionManager::class.java.getDeclaredField(name).apply { isAccessible = true }
}
