package com.bydmate.app.cluster

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.vehicle.FreeformLaunchResult
import com.bydmate.app.data.vehicle.HelperBootstrap
import com.bydmate.app.data.vehicle.HelperClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowWindowManagerGlobal
import org.robolectric.shadows.ShadowWindowManagerImpl

/**
 * #279: an automation step may name the app for the cluster. That app is projected for this
 * projection only, through the same path as the saved choice («Приложение на приборку»), and the
 * saved choice is never written. Mechanics as in [ClusterProjectionDirectDeathWatchTest]: the
 * direct (freeform) transport, whose launchFreeform names the package that goes to the cluster.
 */
@RunWith(RobolectricTestRunner::class)
class ClusterProjectionPerCallAppTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var addedDisplayId: Int = -1

    @Before
    fun setUp() {
        ShadowWindowManagerGlobal.reset()
        ShadowWindowManagerImpl.reset()
        ShadowChoreographer.setPaused(true)
        ShadowSettings.setCanDrawOverlays(true)
        resetManagerState()
        prefs().edit().clear().commit()
        prefs().edit()
            .putBoolean(ClusterProjectionManager.KEY_DIRECT_PROJECTION, true)
            .putBoolean(ClusterProjectionManager.KEY_AUTO_CONTAINER, false)
            .putString(ClusterProjectionManager.KEY_TARGET_PACKAGE, SAVED)
            .putString(ClusterProjectionManager.KEY_TARGET_LABEL, "Навигатор")
            .commit()
        addedDisplayId = ShadowDisplayManager.addDisplay("w1280dp-h480dp", "XDJAScreenProjection_1")
    }

    @After
    fun tearDown() {
        resetManagerState()
        ShadowSettings.setCanDrawOverlays(false)
        if (addedDisplayId != -1) ShadowDisplayManager.removeDisplay(addedDisplayId)
        prefs().edit().clear().commit()
        val shadow = shadowOf(Looper.getMainLooper())
        shadow.runToEndOfTasks()
        shadow.idle()
    }

    @Test
    fun `the app named by the call goes to the cluster and the saved choice stays`() {
        val helper = helper()
        ClusterProjectionManager.setMode(context, ClusterMode.FULLSCREEN, helper, bootstrap(), app = RADIO)
        awaitProjected(RADIO)

        coVerify(exactly = 1) { helper.launchFreeform(RADIO, any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { helper.launchFreeform(SAVED, any(), any(), any(), any(), any(), any()) }
        assertEquals(SAVED, prefs().getString(ClusterProjectionManager.KEY_TARGET_PACKAGE, null))
        assertEquals("Навигатор", prefs().getString(ClusterProjectionManager.KEY_TARGET_LABEL, null))
    }

    @Test
    fun `another app asked while one is projected takes its place`() {
        val helper = helper()
        ClusterProjectionManager.setMode(context, ClusterMode.FULLSCREEN, helper, bootstrap())
        awaitProjected(SAVED)
        ClusterProjectionManager.setMode(context, ClusterMode.FULLSCREEN, helper, bootstrap(), app = RADIO)
        awaitProjected(RADIO)

        coVerify(exactly = 1) { helper.launchFreeform(RADIO, any(), any(), any(), any(), any(), any()) }
        assertEquals(ClusterMode.FULLSCREEN, ClusterProjectionManager.currentMode)
    }

    @Test
    fun `the same app asked again changes nothing`() {
        val helper = helper()
        ClusterProjectionManager.setMode(context, ClusterMode.FULLSCREEN, helper, bootstrap(), app = RADIO)
        awaitProjected(RADIO)
        ClusterProjectionManager.setMode(context, ClusterMode.FULLSCREEN, helper, bootstrap(), app = RADIO)
        ClusterProjectionManager.setMode(context, ClusterMode.FULLSCREEN, helper, bootstrap())
        drain()

        coVerify(exactly = 1) { helper.launchFreeform(any(), any(), any(), any(), any(), any(), any()) }
        assertEquals(RADIO, ClusterProjectionManager.diag().projectedPackage)
    }

    @Test
    fun `after the app's projection ends the next one is the saved choice again`() {
        val helper = helper()
        ClusterProjectionManager.setMode(context, ClusterMode.FULLSCREEN, helper, bootstrap(), app = RADIO)
        awaitProjected(RADIO)
        ClusterProjectionManager.setMode(context, ClusterMode.OFF, helper, bootstrap())
        awaitMode(ClusterMode.OFF)
        ClusterProjectionManager.setMode(context, ClusterMode.FULLSCREEN, helper, bootstrap())
        awaitProjected(SAVED)

        coVerify(exactly = 1) { helper.launchFreeform(SAVED, any(), any(), any(), any(), any(), any()) }
    }

    private fun prefs() = context.getSharedPreferences(ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE)

    private fun helper(): HelperClient = mockk<HelperClient>(relaxed = true).also {
        coEvery { it.launchFreeform(any(), any(), any(), any(), any(), any(), any()) } returns FreeformLaunchResult.OK
        coEvery { it.releaseVirtualDisplay(any()) } returns true
        coEvery { it.getTaskState(any()) } returns null
    }

    private fun bootstrap(): HelperBootstrap = mockk<HelperBootstrap>(relaxed = true).also {
        coEvery { it.ensureRunning() } returns true
    }

    private fun drain() {
        val shadow = shadowOf(Looper.getMainLooper())
        repeat(10) { shadow.idle(); Thread.sleep(25) }
    }

    private fun awaitProjected(pkg: String) {
        val shadow = shadowOf(Looper.getMainLooper())
        repeat(80) {
            shadow.idle()
            if (ClusterProjectionManager.currentMode == ClusterMode.FULLSCREEN &&
                ClusterProjectionManager.diag().projectedPackage == pkg
            ) return
            Thread.sleep(25)
        }
        fail("$pkg not projected (mode=${ClusterProjectionManager.currentMode} " +
            "pkg=${ClusterProjectionManager.diag().projectedPackage})")
    }

    private fun awaitMode(mode: ClusterMode) {
        val shadow = shadowOf(Looper.getMainLooper())
        repeat(40) {
            shadow.idle()
            if (ClusterProjectionManager.currentMode == mode) return
            Thread.sleep(25)
        }
        fail("projection did not reach $mode")
    }

    /** Process-wide object: a projection another class left live would make setMode a no-op. */
    private fun resetManagerState() {
        field("directDeathWatchJob").let { f ->
            (f.get(ClusterProjectionManager) as? Job)?.cancel()
            f.set(ClusterProjectionManager, null)
        }
        field("currentMode").set(ClusterProjectionManager, ClusterMode.OFF)
        field("projectedPackage").set(ClusterProjectionManager, null)
        field("sessionApp").set(ClusterProjectionManager, null)
        field("directDisplayId").set(ClusterProjectionManager, -1)
        field("remoteDisplayId").set(ClusterProjectionManager, -1)
        field("overlayView").set(ClusterProjectionManager, null)
        field("journal").set(ClusterProjectionManager, null)
        field("frame").set(ClusterProjectionManager, null)
        ClusterJournal::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }

    private fun field(name: String) =
        ClusterProjectionManager::class.java.getDeclaredField(name).apply { isAccessible = true }

    private companion object {
        const val SAVED = "com.example.player"
        const val RADIO = "com.example.radio"
    }
}
