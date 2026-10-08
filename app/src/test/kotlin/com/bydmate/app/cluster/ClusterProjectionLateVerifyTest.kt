package com.bydmate.app.cluster

import android.content.Context
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.vehicle.HelperBootstrap
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.SplitTaskState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.TimeUnit.MILLISECONDS
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
 * #288 ([FIXTURE], 3.19.9, 2GIS): a VD projection that starts from a native split pane passes the
 * immediate check (the daemon's fallback put the task on the VD), then the app dies and restarts,
 * and the 3 s verify reads task=none. The projection must end there the way the immediate check
 * ends it (OFF, pullback), not stay "active" over an empty cluster. Yandex Navigator survives the
 * move and its verify finds the task on the VD: that projection stays.
 *
 * The task reads are replayed in the order the manager makes them: the moveContext read and the
 * `before` read (split pane), the post-launch read (on the VD), then the verify read.
 *
 * Mechanics follow [ClusterProjectionSendFailureTest] (surface completed by hand) and
 * [ClusterProjectionDirectDeathWatchTest] (reflective state reset, virtual-clock idleFor).
 */
@RunWith(RobolectricTestRunner::class)
class ClusterProjectionLateVerifyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var addedDisplayId: Int = -1
    private val fixture: List<String> by lazy {
        requireNotNull(javaClass.classLoader?.getResource(FIXTURE)).readText().lines()
    }

    @Before
    fun setUp() {
        ShadowWindowManagerGlobal.reset()
        ShadowWindowManagerImpl.reset()
        ShadowChoreographer.setPaused(true)
        ShadowSettings.setCanDrawOverlays(true)
        resetManagerState()
        val prefs = context.getSharedPreferences(ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        prefs.edit()
            .putBoolean(ClusterProjectionManager.KEY_DIRECT_PROJECTION, false)
            .putBoolean(ClusterProjectionManager.KEY_AUTO_CONTAINER, false)
            .putString(ClusterProjectionManager.KEY_TARGET_PACKAGE, DGIS)
            .commit()
        addedDisplayId = ShadowDisplayManager.addDisplay("w1280dp-h480dp", "XDJAScreenProjection_0")
    }

    @After
    fun tearDown() {
        resetManagerState()
        ShadowSettings.setCanDrawOverlays(false)
        if (addedDisplayId != -1) ShadowDisplayManager.removeDisplay(addedDisplayId)
        context.getSharedPreferences(ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        val shadow = shadowOf(Looper.getMainLooper())
        shadow.runToEndOfTasks()
        shadow.idle()
    }

    /**
     * The bad pass of 11:56:20: split pane before, fallback ok on vd=12, death, verify task=none.
     *
     * Anti-vacuity: 3.19.9 only logs the verify line and stays FULLSCREEN.
     */
    @Test
    fun `split start whose task is gone at the verify ends the projection`() {
        val vdId = 12
        val before = journalStart("11:56:20")
        assertTrue("the bad pass starts from a split pane", inSplitPane(before))
        val placed = fallbackState("task=24 fallback ok")
        assertEquals("the daemon reported the task on the VD", vdId, placed.displayId)
        val verify = verifyState("11:56:25 verify after_ms=3000 vd=$vdId")
        assertTrue("the fixture must show the late task=none", verify.taskId <= 0)

        val helper = vdHelper(vdId, before, placed, verify)
        project(helper)
        shadowOf(Looper.getMainLooper()).idleFor(VERIFY_AFTER_MS + 100, MILLISECONDS)

        assertEquals(ClusterMode.OFF, ClusterProjectionManager.currentMode)
        assertEquals("the VD is released", -1, ClusterProjectionManager.diag().vdDisplayId)
        assertFalse("the overlay is gone", ClusterProjectionManager.diag().overlayAttached)
        coVerify { helper.getTaskId(DGIS) }
        assertTrue(
            "the honest end must be journaled: $journalDump",
            journalHas("projection failed (projection); falling back to OFF"),
        )
    }

    /**
     * Yandex Navigator from a split pane (the 3.19.9 fix): the task survives the move, and the
     * verify finds it on the VD — the 11:55:41 verify line shape. The projection stays, nothing is
     * pulled back.
     *
     * Anti-vacuity: ending on any verify after a split start pulls back and switches OFF.
     */
    @Test
    fun `split start whose task is on the VD at the verify keeps the projection`() {
        val vdId = 11
        val before = journalStart("11:56:20")
        val verify = verifyState("11:55:41 verify after_ms=3000 vd=$vdId")
        assertEquals(vdId, verify.displayId)
        val placed = SplitTaskState(24, 1, 0, 0, 0, 0, vdId)

        val helper = vdHelper(vdId, before, placed, verify)
        project(helper)
        shadowOf(Looper.getMainLooper()).idleFor(VERIFY_AFTER_MS + 100, MILLISECONDS)

        assertEquals(ClusterMode.FULLSCREEN, ClusterProjectionManager.currentMode)
        assertEquals(vdId, ClusterProjectionManager.diag().vdDisplayId)
        coVerify(exactly = 0) { helper.getTaskId(any()) }
        coVerify(exactly = 0) { helper.setTaskWindowingMode(any(), any(), any()) }
    }

    /**
     * Not a split start (the 11:55:35 pass: no task before): the immediate check does not apply,
     * and neither does the late one — a late task=none stays a log line, as in 3.19.9.
     */
    @Test
    fun `non-split start keeps the 3_19_9 outcome on a late task none`() {
        val vdId = 11
        val before = journalStart("11:55:35")
        assertFalse(inSplitPane(before))
        val placed = SplitTaskState(23, 1, 0, 0, 0, 0, vdId)

        val helper = vdHelper(vdId, before, placed, SplitTaskState(-1, 0, 0, 0, 0, 0))
        project(helper)
        shadowOf(Looper.getMainLooper()).idleFor(VERIFY_AFTER_MS + 100, MILLISECONDS)

        assertEquals(ClusterMode.FULLSCREEN, ClusterProjectionManager.currentMode)
    }

    // --- fixture replay ---

    /** The navigator's state before the move, from the journal's setMode line at [time]. */
    private fun journalStart(time: String): SplitTaskState? {
        val line = fixture.single { "$time setMode OFF -> FULLSCREEN" in it }
        val m = Regex("""navi_wm=(\d+) navi_display=(\d+)""").find(line) ?: return null
        return SplitTaskState(24, m.groupValues[1].toInt(), 0, 0, 0, 0, m.groupValues[2].toInt())
    }

    /** What the daemon read right after its fallback am start. */
    private fun fallbackState(marker: String): SplitTaskState {
        val line = fixture.single { marker in it }
        val m = requireNotNull(Regex("""windowingMode=(\d+), displayId=(\d+)""").find(line))
        return SplitTaskState(24, m.groupValues[1].toInt(), 0, 0, 0, 0, m.groupValues[2].toInt())
    }

    /** The verify read: task=none or task_display=N wm=M. */
    private fun verifyState(marker: String): SplitTaskState {
        val line = fixture.single { marker in it }
        if ("task=none" in line) return SplitTaskState(-1, 0, 0, 0, 0, 0)
        val m = requireNotNull(Regex("""task_display=(\d+) wm=(\d+)""").find(line))
        return SplitTaskState(24, m.groupValues[2].toInt(), 0, 0, 0, 0, m.groupValues[1].toInt())
    }

    // --- helpers ---

    private fun vdHelper(
        vdId: Int, before: SplitTaskState?, placed: SplitTaskState, verify: SplitTaskState,
    ): HelperClient = mockk<HelperClient>(relaxed = true).also {
        var reads = 0
        val split = inSplitPane(before)
        coEvery { it.getTaskState(DGIS) } answers {
            reads++
            when {
                reads <= 2 -> before
                split && reads == 3 -> placed
                else -> verify
            }
        }
        coEvery { it.createVirtualDisplay(any(), any(), any(), any(), any(), any()) } returns vdId
        coEvery { it.releaseVirtualDisplay(any()) } returns true
        coEvery { it.launchAndForce(any(), any(), any(), any()) } returns true
        coEvery { it.getTaskId(DGIS) } returns null
    }

    private fun bootstrap(): HelperBootstrap = mockk<HelperBootstrap>(relaxed = true).also {
        coEvery { it.ensureRunning() } returns true
    }

    /** Drives the VD pipeline to FULLSCREEN: completes the overlay surface by hand. */
    private fun project(helper: HelperClient) {
        ClusterProjectionManager.setMode(context, ClusterMode.FULLSCREEN, helper, bootstrap())
        check(drainUntil { overlayView() != null }) { "the VD pipeline never reached the overlay surface wait" }
        val container = checkNotNull(overlayView())
        val holder = mockk<SurfaceHolder>()
        every { holder.surface } returns mockk<Surface>()
        shadowOf(container.getChildAt(0) as SurfaceView).fakeSurfaceHolder.callbacks
            .forEach { it.surfaceCreated(holder) }
        if (!drainUntil { ClusterProjectionManager.currentMode == ClusterMode.FULLSCREEN }) {
            fail("projection did not reach FULLSCREEN: $journalDump")
        }
    }

    /**
     * Drains the main looper until [done]. Thread.sleep between drains lets the real IO thread
     * post its continuation back without advancing the virtual clock (no verify can fire here).
     */
    private fun drainUntil(done: () -> Boolean): Boolean {
        val shadow = shadowOf(Looper.getMainLooper())
        repeat(40) {
            shadow.idle()
            if (done()) return true
            Thread.sleep(25)
        }
        return false
    }

    private fun overlayView(): FrameLayout? =
        field("overlayView").get(ClusterProjectionManager) as? FrameLayout

    private fun journalHas(fragment: String): Boolean =
        ClusterProjectionManager.journalLines(context).any { fragment in it }

    private val journalDump: String
        get() = ClusterProjectionManager.journalLines(context).joinToString("\n")

    private fun resetManagerState() {
        field("currentMode").set(ClusterProjectionManager, ClusterMode.OFF)
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

    private companion object {
        const val FIXTURE = "native-stack-fixtures/cluster-split-relaunch-dgis-20261007.txt"
        const val DGIS = "ru.dublgis.dgismobile"

        /** Mirrors ClusterProjectionManager.VERIFY_AFTER_MS (private). */
        const val VERIFY_AFTER_MS = 3000L
    }
}
