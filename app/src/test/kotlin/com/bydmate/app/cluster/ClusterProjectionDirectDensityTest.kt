package com.bydmate.app.cluster

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.BuildConfig
import com.bydmate.app.data.vehicle.DensityResult
import com.bydmate.app.data.vehicle.FreeformLaunchResult
import com.bydmate.app.data.vehicle.HelperBootstrap
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.SplitTaskState
import com.bydmate.app.helper.WINDOWING_MODE_FREEFORM
import com.bydmate.app.helper.WINDOWING_MODE_FULLSCREEN
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import java.util.concurrent.TimeUnit.MILLISECONDS
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
 * #121: in direct mode the content scale is a density override on the real cluster display, and
 * the crash class is changing that density under a LIVE window (2GIS/Qt dies on the Configuration
 * change). The order BYD DashCast proved safe is the one under test here: density first, settle,
 * then the launch — and never while a task of the package already sits on that display.
 *
 * Covers:
 *   - non-native scale → setDisplayDensity BEFORE launchFreeform;
 *   - scale 100% → an explicit reset (density 0) and a projection that still completes;
 *   - UNAVAILABLE after a non-zero density → the override is reset (the direct marker is
 *     cleared on that branch, so boot recovery would never reset it);
 *   - an in-place resize → bounds only, no density call under the live window;
 *   - a task already on the cluster display → density left untouched;
 *   - a death after a non-native density → reset, relaunch at native, package latched;
 *   - a latched package → no density call at all on later sends.
 *
 * Test mechanics mirror [ClusterProjectionDirectDeathWatchTest] / [ClusterProjectionSendFailureTest]:
 * idle → the CPM coroutine suspends at withContext(IO) for the write-ahead marker; Thread.sleep →
 * the real IO thread commits and posts the continuation back; idleFor advances the sandbox
 * looper's VIRTUAL clock so the 150 ms settle delay fires without consuming wall time.
 */
@RunWith(RobolectricTestRunner::class)
class ClusterProjectionDirectDensityTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var addedDisplayId: Int = -1

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
            .putBoolean(ClusterProjectionManager.KEY_DIRECT_PROJECTION, true)
            .putBoolean(ClusterProjectionManager.KEY_AUTO_CONTAINER, false)
            .commit()
        addedDisplayId = ShadowDisplayManager.addDisplay("w1280dp-h480dp", "XDJAScreenProjection_1")
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
     * The DashCast order: `wm density` first, then `am start`. A density applied after the launch
     * is the #121 crash; a density never applied leaves the slider inert.
     *
     * Anti-vacuity: moving the density call back below launchFreeform fails coVerifyOrder.
     */
    @Test
    fun `non-native scale sets the density before the launch`() {
        setScalePct(80)
        val helper = directProjectionHelper()
        coEvery { helper.getTaskState(NAVI_PACKAGE) } returns taskOnMainScreen()

        projectDirect(helper)

        coVerifyOrder {
            helper.setDisplayDensity(addedDisplayId, match { it > 0 })
            helper.launchFreeform(NAVI_PACKAGE, addedDisplayId, any(), any(), any(), any(), any())
        }
        assertEquals(
            "the density must be aimed at the display the projection actually resolved",
            addedDisplayId, ClusterProjectionManager.diag().directDisplayId,
        )
    }

    /**
     * Scale 100% means the panel's own density: the override is explicitly RESET (0) rather than
     * left at whatever a previous session set, and no settle is needed for it.
     *
     * Anti-vacuity: skipping the call at native scale leaves the stale override in place → the
     * verification finds no call and fails.
     */
    @Test
    fun `native scale resets the override and still completes the projection`() {
        setScalePct(100)
        val helper = directProjectionHelper()
        coEvery { helper.getTaskState(NAVI_PACKAGE) } returns taskOnMainScreen()

        projectDirect(helper)

        coVerify(exactly = 1) { helper.setDisplayDensity(addedDisplayId, 0) }
        assertEquals(ClusterMode.FULLSCREEN, ClusterProjectionManager.currentMode)
    }

    /**
     * UNAVAILABLE clears the persisted direct marker, so boot recovery has nothing to reset from:
     * the scaled display would stay scaled for whatever the firmware shows there next. The branch
     * resets it itself.
     *
     * Anti-vacuity: dropping the reset from the UNAVAILABLE branch leaves one density call → the
     * ordered verification fails.
     */
    @Test
    fun `UNAVAILABLE resets the density it applied`() {
        setScalePct(80)
        val helper = directProjectionHelper()
        coEvery {
            helper.launchFreeform(any(), any(), any(), any(), any(), any(), any())
        } returns FreeformLaunchResult.UNAVAILABLE
        coEvery { helper.getTaskState(NAVI_PACKAGE) } returns taskOnMainScreen()

        ClusterProjectionManager.setMode(context, ClusterMode.FULLSCREEN, helper, bootstrap())
        pumpThroughSettle()

        coVerifyOrder {
            helper.setDisplayDensity(addedDisplayId, match { it > 0 })
            helper.launchFreeform(any(), any(), any(), any(), any(), any(), any())
            helper.setDisplayDensity(addedDisplayId, 0)
        }
        assertTrue(
            "the reset must be journaled for the field log: $journalDump",
            journalHas("direct: density reset after UNAVAILABLE"),
        )
    }

    /**
     * FAILED means the daemon could not confirm the placement — it restores fullscreen but does
     * NOT move the task off the target display. With the task gone from the cluster the reset is
     * safe and runs, exactly as it did before.
     *
     * Anti-vacuity: gating the reset on something other than the task's display leaves the
     * override in place here → the ordered verification finds no reset and fails.
     */
    @Test
    fun `FAILED resets the density once the task has left the cluster display`() {
        setScalePct(80)
        val helper = directProjectionHelper()
        coEvery {
            helper.launchFreeform(any(), any(), any(), any(), any(), any(), any())
        } returns FreeformLaunchResult.FAILED
        coEvery { helper.getTaskState(NAVI_PACKAGE) } returns taskOnMainScreen()

        ClusterProjectionManager.setMode(context, ClusterMode.FULLSCREEN, helper, bootstrap())
        pumpThroughSettle()

        coVerifyOrder {
            helper.setDisplayDensity(addedDisplayId, match { it > 0 })
            helper.launchFreeform(any(), any(), any(), any(), any(), any(), any())
            helper.setDisplayDensity(addedDisplayId, 0)
        }
        assertTrue(
            "the reset must be journaled for the field log: $journalDump",
            journalHas("direct: density reset after FAILED"),
        )
    }

    /**
     * The dangerous half of FAILED: the placement was rejected but the navigator is still sitting
     * on the cluster display (fullscreen there). Dropping the override now is the live
     * Configuration change of #121, so it is skipped and the marker is kept for the recovery pass.
     *
     * Anti-vacuity: resetting unconditionally (the previous shape) makes the exactly-0 count fail.
     */
    @Test
    fun `FAILED with the task still on the cluster keeps the override and the marker`() {
        setScalePct(80)
        var launched = false
        val helper = directProjectionHelper()
        coEvery {
            helper.launchFreeform(any(), any(), any(), any(), any(), any(), any())
        } answers { launched = true; FreeformLaunchResult.FAILED }
        coEvery { helper.getTaskState(NAVI_PACKAGE) } answers {
            if (launched) strandedOnCluster() else taskOnMainScreen()
        }

        ClusterProjectionManager.setMode(context, ClusterMode.FULLSCREEN, helper, bootstrap())
        pumpThroughSettle()

        coVerify(exactly = 0) { helper.setDisplayDensity(addedDisplayId, 0) }
        assertTrue(
            "the skipped reset must be journaled: $journalDump",
            journalHas("direct: density reset skipped after FAILED, " +
                "task still on display $addedDisplayId"),
        )
        assertEquals(
            "the recovery marker must survive so a later pass resets the override",
            addedDisplayId,
            context.getSharedPreferences(ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(ClusterProjectionManager.KEY_DIRECT_DISPLAY_ID, -1),
        )
    }

    /**
     * An in-place resize addresses a LIVE window — exactly where a density change kills 2GIS. It
     * moves bounds only; the new scale lands on the next send to the cluster.
     *
     * Anti-vacuity: restoring a density call in the direct branch of swapToNewSize gives a second
     * call → the exactly-1 count fails.
     */
    @Test
    fun `in-place resize never touches the density`() {
        setScalePct(100)
        val helper = directProjectionHelper()
        coEvery { helper.getTaskState(NAVI_PACKAGE) } returns taskOnMainScreen()
        coEvery { helper.getTaskId(NAVI_PACKAGE) } returns 42

        projectDirect(helper)
        setScalePct(80)
        ClusterProjectionManager.reproject(context, helper, bootstrap())
        shadowOf(Looper.getMainLooper()).idleFor(500, MILLISECONDS)

        coVerify(exactly = 1) { helper.setDisplayDensity(any(), any()) }
        coVerify(exactly = 1) { helper.setTaskBounds(42, any(), any(), any(), any()) }
    }

    /**
     * A stale task of the package already on the cluster display is re-adopted by the launch, so
     * the window is live throughout — the density must be left exactly as it is (#121).
     *
     * Anti-vacuity: dropping the getTaskState guard sends a density under that live window → the
     * exactly-0 count fails.
     */
    @Test
    fun `task already on the cluster display keeps the density untouched`() {
        setScalePct(80)
        val helper = directProjectionHelper()
        coEvery { helper.getTaskState(NAVI_PACKAGE) } returns taskOnCluster()

        projectDirect(helper)

        coVerify(exactly = 0) { helper.setDisplayDensity(any(), any()) }
        assertTrue(
            "the skipped density change must be journaled: $journalDump",
            journalHas("direct: task already on display $addedDisplayId, density left as is"),
        )
    }

    /**
     * #121 is "dies a few seconds after launch at ANY non-native dpi", so the safe ORDER is not a
     * proof of safety — the density is a probe. A death after a non-native density drops the
     * override, relaunches at the native one and latches the package.
     *
     * Anti-vacuity: dropping the reset from the death watch leaves the relaunch running at the
     * scaled density → the ordered verification finds no reset and fails.
     */
    @Test
    fun `death after a non-native density resets it and latches the package`() {
        setScalePct(80)
        val helper = directProjectionHelper()
        coEvery { helper.getTaskState(NAVI_PACKAGE) } returns deadTask()

        projectDirect(helper)
        shadowOf(Looper.getMainLooper()).idleFor(WATCH_INTERVAL_MS + 100, MILLISECONDS)

        coVerifyOrder {
            helper.setDisplayDensity(addedDisplayId, match { it > 0 })
            helper.launchFreeform(any(), any(), any(), any(), any(), any(), any())
            helper.setDisplayDensity(addedDisplayId, 0)
            helper.launchFreeform(any(), any(), any(), any(), any(), any(), any())
        }
        assertTrue(
            "the package must be latched density-unsafe: ${densityUnsafeFlag()}",
            densityUnsafeFlag(),
        )
        assertTrue(
            "the latch must be journaled: $journalDump",
            journalHas("$NAVI_PACKAGE marked density-unsafe"),
        )
    }

    /**
     * Once latched, that package is sent at the panel's own density: no override, and no reset
     * either — nothing is touched on a display that is already native.
     *
     * Anti-vacuity: ignoring the latch sends the scaled density again → the exactly-0 count fails.
     */
    @Test
    fun `a latched package is launched without any density call`() {
        setScalePct(80)
        latchDensityUnsafe()
        val helper = directProjectionHelper()
        coEvery { helper.getTaskState(NAVI_PACKAGE) } returns taskOnMainScreen()

        projectDirect(helper)

        coVerify(exactly = 0) { helper.setDisplayDensity(any(), any()) }
        assertTrue(
            "the skip must be journaled: $journalDump",
            journalHas("direct: density skipped for $NAVI_PACKAGE"),
        )
    }

    /**
     * Pull-back order: the task leaves the cluster display FIRST, the override is dropped after.
     * A reset sent while the navigator still renders there is the same live Configuration change
     * the pre-launch order exists to avoid (#121).
     *
     * The order is asserted over a recorded call log rather than with coVerifyOrder: the latter
     * matches a SUBSEQUENCE, so a reset sent both before and after the reclaim would still pass.
     *
     * Anti-vacuity: restoring the reset above the reclaim (where it used to be) puts "density=0"
     * ahead of the windowing-mode call → the index assertions fail.
     */
    @Test
    fun `pull-back resets the density only after the task left the cluster`() {
        setScalePct(80)
        var onCluster = false
        val calls = mutableListOf<String>()
        val helper = directProjectionHelper()
        coEvery { helper.getTaskState(NAVI_PACKAGE) } answers {
            if (onCluster) taskOnCluster() else taskOnMainScreen()
        }
        coEvery { helper.getTaskId(NAVI_PACKAGE) } returns 42
        coEvery { helper.setDisplayDensity(any(), any()) } answers {
            calls += "density=${secondArg<Int>()}"; DensityResult(true)
        }
        coEvery { helper.setTaskWindowingMode(any(), any(), any()) } answers { calls += "mode"; false }
        coEvery { helper.moveTaskToDisplay(any(), any()) } answers { calls += "move"; true }

        projectDirect(helper)
        onCluster = true
        ClusterProjectionManager.setMode(context, ClusterMode.OFF, helper, bootstrap())
        awaitMode(ClusterMode.OFF)

        assertEquals(
            "the override must be dropped exactly once on the way out: $calls",
            1, calls.count { it == "density=0" },
        )
        assertTrue(
            "the task must leave the cluster before the density is reset: $calls",
            calls.indexOf("density=0") > calls.indexOf("mode") &&
                calls.indexOf("density=0") > calls.indexOf("move"),
        )
        assertTrue(
            "the reclaim itself must have run: $calls",
            calls.contains("mode") && calls.contains("move"),
        )
        assertTrue(
            "the pull-back reset must be journaled: $journalDump",
            journalHas("pullback: density reset ok="),
        )
    }

    /**
     * A reclaim that fails leaves the task ON the cluster, so there is no safe moment to drop the
     * override: it stays, and with it the marker, for the next service start to retry.
     *
     * Anti-vacuity: resetting unconditionally (the previous shape) makes the exactly-0 count fail,
     * and clearing the marker regardless of resetOk fails the marker assertion.
     */
    @Test
    fun `a failed reclaim keeps both the override and the marker`() {
        setScalePct(80)
        var onCluster = false
        val calls = mutableListOf<String>()
        val helper = directProjectionHelper()
        coEvery { helper.getTaskState(NAVI_PACKAGE) } answers {
            if (onCluster) taskOnCluster() else taskOnMainScreen()
        }
        coEvery { helper.getTaskId(NAVI_PACKAGE) } returns 42
        coEvery { helper.setDisplayDensity(any(), any()) } answers {
            calls += "density=${secondArg<Int>()}"; DensityResult(true)
        }
        coEvery { helper.setTaskWindowingMode(any(), any(), any()) } returns false
        coEvery { helper.moveTaskToDisplay(any(), any()) } returns false

        projectDirect(helper)
        onCluster = true
        ClusterProjectionManager.setMode(context, ClusterMode.OFF, helper, bootstrap())
        awaitMode(ClusterMode.OFF)

        assertEquals(
            "the override must survive a failed reclaim: $calls",
            0, calls.count { it == "density=0" },
        )
        assertEquals(
            "the marker must stay so the next start retries",
            addedDisplayId,
            context.getSharedPreferences(ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(ClusterProjectionManager.KEY_DIRECT_DISPLAY_ID, -1),
        )
        assertTrue(
            "the skip must be journaled: $journalDump",
            journalHas("pullback: density reset skipped, task still on display $addedDisplayId"),
        )
    }

    /**
     * Boot recovery after a crash mid-projection: the stranded task is still on the cluster and
     * the projection about to run RE-ADOPTS it, so nothing relaunches and the window stays live
     * throughout. Dropping the override there would hit that live window (#121). The marker stays
     * set, so density absorption remains suppressed either way.
     *
     * Anti-vacuity: removing the stranded-task guard resets the override here → the exactly-0
     * count fails.
     */
    @Test
    fun `stale density is kept while the stranded task is still on the cluster`() {
        setScalePct(80)
        context.getSharedPreferences(ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(ClusterProjectionManager.KEY_DIRECT_DISPLAY_ID, addedDisplayId).commit()
        val helper = directProjectionHelper()
        coEvery { helper.getTaskState(NAVI_PACKAGE) } returns taskOnCluster()

        projectDirect(helper)

        coVerify(exactly = 0) { helper.setDisplayDensity(any(), any()) }
        assertTrue(
            "the kept override must be journaled: $journalDump",
            journalHas("direct: stale density kept, task still on display $addedDisplayId"),
        )
    }

    // --- helpers ---

    private fun directProjectionHelper(): HelperClient = mockk<HelperClient>(relaxed = true).also {
        coEvery {
            it.launchFreeform(any(), any(), any(), any(), any(), any(), any())
        } returns FreeformLaunchResult.OK
        coEvery { it.setDisplayDensity(any(), any()) } returns DensityResult(true)
        coEvery { it.releaseVirtualDisplay(any()) } returns true
    }

    private fun bootstrap(): HelperBootstrap = mockk<HelperBootstrap>(relaxed = true).also {
        coEvery { it.ensureRunning() } returns true
    }

    /** Live task on the cluster display: the re-adoption case, where the density must stay put. */
    private fun taskOnCluster() =
        SplitTaskState(42, WINDOWING_MODE_FREEFORM, 0, 0, 1280, 480, displayId = addedDisplayId)

    /** The usual pre-send state: the navigator is on the main screen, nothing on the cluster. */
    private fun taskOnMainScreen() =
        SplitTaskState(42, WINDOWING_MODE_FULLSCREEN, 0, 0, 1920, 1200, displayId = 0)

    /** Placement rejected, task left behind on the cluster display: fullscreen, wrong screen. */
    private fun strandedOnCluster() =
        SplitTaskState(42, WINDOWING_MODE_FULLSCREEN, 0, 0, 1280, 480, displayId = addedDisplayId)

    /** The daemon answered and no task is running for the package — the #121 death shape. */
    private fun deadTask() = SplitTaskState(-1, 0, 0, 0, 0, 0)

    private fun densityUnsafeFlag(): Boolean =
        context.getSharedPreferences(ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(ClusterProjectionManager.KEY_DENSITY_UNSAFE_PREFIX + NAVI_PACKAGE, false)

    /** Latches the verdict as a previous session would have, stamped with the current build. */
    private fun latchDensityUnsafe() {
        context.getSharedPreferences(ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(ClusterProjectionManager.KEY_DENSITY_UNSAFE_VERSION, BuildConfig.VERSION_CODE)
            .putBoolean(ClusterProjectionManager.KEY_DENSITY_UNSAFE_PREFIX + NAVI_PACKAGE, true)
            .commit()
    }

    private fun setScalePct(pct: Int) {
        context.getSharedPreferences(ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(ClusterProjectionManager.KEY_SCALE_PCT, pct).commit()
    }

    /** Drives a successful direct projection and returns once the manager reports FULLSCREEN. */
    private fun projectDirect(helper: HelperClient) {
        ClusterProjectionManager.setMode(context, ClusterMode.FULLSCREEN, helper, bootstrap())
        awaitMode(ClusterMode.FULLSCREEN)
    }

    /**
     * Drains the main looper until the manager reaches [mode]. Thread.sleep lets the real IO
     * thread (write-ahead marker commit) post its continuation back; idleFor advances the virtual
     * clock so the pre-launch settle delay fires. Both are needed: sleep does not move the clock,
     * and the clock does not move the IO thread.
     */
    private fun awaitMode(mode: ClusterMode) {
        val shadow = shadowOf(Looper.getMainLooper())
        for (attempt in 0 until 40) {
            shadow.idle()
            if (ClusterProjectionManager.currentMode == mode) return
            shadow.idleFor(SETTLE_MS, MILLISECONDS)
            Thread.sleep(25)
        }
        throw AssertionError(
            "projection did not reach $mode (currentMode=${ClusterProjectionManager.currentMode})"
        )
    }

    /** Same pumping for paths that never reach FULLSCREEN (the VD fallback owns the tail). */
    private fun pumpThroughSettle() {
        val shadow = shadowOf(Looper.getMainLooper())
        shadow.idle()
        Thread.sleep(300)
        shadow.idle()
        shadow.idleFor(SETTLE_MS, MILLISECONDS)
    }

    private fun journalHas(fragment: String): Boolean =
        ClusterProjectionManager.journalLines(context).any { fragment in it }

    private val journalDump: String
        get() = ClusterProjectionManager.journalLines(context).joinToString("\n")

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
        resetSharedJournal()
    }

    /** Same static-cache problem as in [ClusterProjectionDirectDeathWatchTest]. */
    private fun resetSharedJournal() {
        field("journal").set(ClusterProjectionManager, null)
        field("frame").set(ClusterProjectionManager, null)
        ClusterJournal::class.java.getDeclaredField("instance")
            .apply { isAccessible = true }
            .set(null, null)
    }

    private fun field(name: String) =
        ClusterProjectionManager::class.java.getDeclaredField(name).apply { isAccessible = true }

    private companion object {
        /** Mirrors ClusterProjectionManager.DIRECT_DENSITY_SETTLE_MS (private). */
        const val SETTLE_MS = 150L

        /** Mirrors ClusterProjectionManager.DIRECT_DEATH_CHECK_INTERVAL_MS (private). */
        const val WATCH_INTERVAL_MS = 2000L
    }
}
