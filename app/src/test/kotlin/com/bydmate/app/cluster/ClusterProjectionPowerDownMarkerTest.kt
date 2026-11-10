package com.bydmate.app.cluster

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.vehicle.HelperBootstrap
import com.bydmate.app.data.vehicle.HelperClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * #85/#62: the compositor power-down (18 -> pause -> 0) is an ИПЦ write too - it is sent only when
 * OUR write-ahead marker shows this app powered the compositor up. Platforms with no cluster
 * display (Song family, DiLink 3-4) never set the marker, so their cluster stays untouched.
 */
@RunWith(RobolectricTestRunner::class)
class ClusterProjectionPowerDownMarkerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = context.getSharedPreferences(
        ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        resetManagerState()
        prefs.edit().clear().commit()
    }

    @After
    fun tearDown() {
        resetManagerState()
        prefs.edit().clear().commit()
    }

    @Test fun `power-down is owed only when our write-ahead marker is set`() {
        prefs.edit().putBoolean(ClusterProjectionManager.KEY_COMPOSITOR_POWERED, true).commit()
        val helper = helper()

        endProjection(helper)

        coVerify(exactly = 1) { helper.setClusterContainerMode(false) }
    }

    @Test fun `no marker - compositor was never powered by us - no ИПЦ write`() {
        val helper = helper()

        endProjection(helper)

        coVerify(exactly = 0) { helper.setClusterContainerMode(any()) }
    }

    // --- helpers ---

    private fun helper(): HelperClient = mockk(relaxed = true) {
        coEvery { setClusterContainerMode(any()) } returns true
    }

    /** FULLSCREEN -> OFF with auto-container on (the default): the path that ends in the power-down. */
    private fun endProjection(helper: HelperClient) {
        field("currentMode").set(ClusterProjectionManager, ClusterMode.FULLSCREEN)
        val bootstrap = mockk<HelperBootstrap>(relaxed = true)
        ClusterProjectionManager.setMode(context, ClusterMode.OFF, helper, bootstrap)
        val shadow = shadowOf(Looper.getMainLooper())
        val mutex = field("mutex").get(ClusterProjectionManager) as Mutex
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            shadow.idle()
            if (ClusterProjectionManager.currentMode == ClusterMode.OFF && !mutex.isLocked) return
            Thread.sleep(25)
        }
        throw AssertionError("setMode(OFF) did not finish (currentMode=${ClusterProjectionManager.currentMode})")
    }

    /** Same process-wide state reset as ClusterProjectionPullbackHomeTest. */
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
