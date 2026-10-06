package com.bydmate.app.cluster

import android.content.Context
import android.os.Looper
import android.os.SystemClock
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
import org.junit.Assert.assertFalse
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
 *   - one death after a non-native density → relaunch at the same density, nothing latched;
 *   - two deaths in a row → reset, relaunch at native, package latched (with why and when);
 *   - a death on the last check → the relaunched app is still watched;
 *   - a death at the native density → one relaunch, no latch, as before;
 *   - a marker latched by an earlier build → wiped once (tester's car, [FIXTURE]);
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
     * One death after a scaled send is not proof that the density killed the app: on some cars the
     * cross-display move itself kills it whatever the density (#134, Sea Lion 07). A single loss
     * therefore keeps the scale: the relaunch runs at the same density and nothing is latched
     * (tester's car, 3.19.6: one loss latched the player for good, see [FIXTURE]).
     *
     * Anti-vacuity: the one-strike shape (reset + latch on the first loss) makes both the
     * exactly-0 reset count and the latch assertion fail.
     */
    @Test
    fun `one death after a scaled send neither latches the package nor resets the density`() {
        setScalePct(80)
        var launches = 0
        val helper = directProjectionHelper()
        coEvery {
            helper.launchFreeform(any(), any(), any(), any(), any(), any(), any())
        } answers { launches++; FreeformLaunchResult.OK }
        coEvery { helper.getTaskState(NAVI_PACKAGE) } answers {
            when (launches) {
                0 -> taskOnMainScreen()
                1 -> deadTask()
                else -> taskOnCluster()
            }
        }

        projectDirect(helper)
        shadowOf(Looper.getMainLooper()).idleFor(4 * WATCH_INTERVAL_MS + 100, MILLISECONDS)

        coVerify(exactly = 2) { helper.launchFreeform(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { helper.setDisplayDensity(addedDisplayId, 0) }
        assertFalse("one loss must not latch the package: $journalDump", densityUnsafeFlag())
    }

    /**
     * Two losses in a row with the scale applied: the second one is the verdict. The override is
     * dropped BEFORE the second relaunch (which must run at the native density) and the package is
     * latched, with why and when.
     *
     * Anti-vacuity: latching on the first loss puts the reset ahead of the second launch → the
     * ordered verification fails; never latching fails the flag assertion.
     */
    @Test
    fun `two deaths in a row latch the package and reset the density before the second relaunch`() {
        setScalePct(80)
        var launches = 0
        val helper = directProjectionHelper()
        coEvery {
            helper.launchFreeform(any(), any(), any(), any(), any(), any(), any())
        } answers { launches++; FreeformLaunchResult.OK }
        coEvery { helper.getTaskState(NAVI_PACKAGE) } answers {
            when (launches) {
                0 -> taskOnMainScreen()
                1, 2 -> deadTask()
                else -> taskOnCluster()
            }
        }

        projectDirect(helper)
        shadowOf(Looper.getMainLooper()).idleFor(6 * WATCH_INTERVAL_MS + 100, MILLISECONDS)

        coVerifyOrder {
            helper.setDisplayDensity(addedDisplayId, match { it > 0 })
            helper.launchFreeform(any(), any(), any(), any(), any(), any(), any())
            helper.launchFreeform(any(), any(), any(), any(), any(), any(), any())
            helper.setDisplayDensity(addedDisplayId, 0)
            helper.launchFreeform(any(), any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 3) { helper.launchFreeform(any(), any(), any(), any(), any(), any(), any()) }
        assertTrue("the package must be latched density-unsafe", densityUnsafeFlag())
        assertTrue(
            "the latch must be journaled: $journalDump",
            journalHas("$NAVI_PACKAGE marked density-unsafe"),
        )
        val reason = ClusterProjectionManager.densityUnsafeReason(context, NAVI_PACKAGE).orEmpty()
        assertTrue("the marker must say why and where: $reason", reason.startsWith("died post-move on display $addedDisplayId, "))
        assertEquals(
            "the dump list must stay packages only",
            listOf(NAVI_PACKAGE), ClusterProjectionManager.densityUnsafePackages(context),
        )
    }

    /**
     * A loss seen on the LAST check used to relaunch the app and end the watch in the same breath,
     * so nobody looked at the relaunched app. With a scaled density every relaunch gets a full set
     * of checks again.
     *
     * Anti-vacuity: running the relaunch inside the same three checks (the old shape) never sees
     * the second loss → 2 launches instead of 3.
     */
    @Test
    fun `a death on the third check keeps the relaunched app watched`() {
        setScalePct(80)
        var launches = 0
        var watchReads = 0
        val helper = directProjectionHelper()
        coEvery {
            helper.launchFreeform(any(), any(), any(), any(), any(), any(), any())
        } answers { launches++; FreeformLaunchResult.OK }
        coEvery { helper.getTaskState(NAVI_PACKAGE) } answers {
            if (launches == 0) taskOnMainScreen()
            else if (++watchReads <= 2) taskOnCluster() else deadTask()
        }

        projectDirect(helper)
        shadowOf(Looper.getMainLooper()).idleFor(10 * WATCH_INTERVAL_MS + 100, MILLISECONDS)

        coVerify(exactly = 3) { helper.launchFreeform(any(), any(), any(), any(), any(), any(), any()) }
        assertTrue("the second loss must latch: $journalDump", densityUnsafeFlag())
        assertTrue(
            "the third loss must end the watch: $journalDump",
            journalHas("born-on-display relaunch did not hold (died post-move)"),
        )
    }

    /**
     * Native density (scale 100%): the death watch is exactly what it was — one relaunch within the
     * same checks, no density call besides the pre-launch reset, no latch.
     *
     * Anti-vacuity: extending the strikes to native sends gives a third launch; latching on a native
     * death fails the flag assertion.
     */
    @Test
    fun `a death at the native density keeps the one-relaunch behaviour`() {
        setScalePct(100)
        val helper = directProjectionHelper()
        coEvery { helper.getTaskState(NAVI_PACKAGE) } returns deadTask()

        projectDirect(helper)
        shadowOf(Looper.getMainLooper()).idleFor(10 * WATCH_INTERVAL_MS + 100, MILLISECONDS)

        coVerify(exactly = 2) { helper.launchFreeform(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { helper.setDisplayDensity(addedDisplayId, 0) }
        assertFalse("a native death must not latch: $journalDump", densityUnsafeFlag())
    }

    /**
     * The tester's car ([FIXTURE]): a marker latched under 3.19.6 kept ru.auto.music at the native
     * density on every send. This build bumps the probe generation, so that old marker is wiped
     * once and the scale reaches the player again.
     *
     * Anti-vacuity: keeping the VERSION_CODE stamp (or no wipe at all) honours the old marker →
     * the fixture's "density skipped" line comes back and the density count fails.
     */
    @Test
    fun `a marker latched by an earlier build is wiped once`() {
        val fixture = requireNotNull(javaClass.classLoader?.getResource(FIXTURE)).readText()
        assertTrue(
            "the fixture must show the symptom",
            fixture.contains("direct: density skipped for $MUSIC_PACKAGE"),
        )
        setScalePct(50)
        context.getSharedPreferences(ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(ClusterProjectionManager.KEY_TARGET_PACKAGE, MUSIC_PACKAGE)
            // What 3.19.6 left behind: the marker stamped with its own versionCode.
            .putInt("direct_density_unsafe_version", BuildConfig.VERSION_CODE)
            .putBoolean(ClusterProjectionManager.KEY_DENSITY_UNSAFE_PREFIX + MUSIC_PACKAGE, true)
            .commit()
        val helper = directProjectionHelper()
        coEvery { helper.getTaskState(MUSIC_PACKAGE) } returns taskOnMainScreen()

        projectDirect(helper)

        coVerify(exactly = 1) { helper.setDisplayDensity(addedDisplayId, match { it > 0 }) }
        assertFalse(
            "the old marker must not skip the density: $journalDump",
            journalHas("direct: density skipped for $MUSIC_PACKAGE"),
        )
        assertEquals(emptyList<String>(), ClusterProjectionManager.densityUnsafePackages(context))
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

    /**
     * A VirtualDisplay id orphaned by a prior process must be forgotten once released — otherwise
     * the same stale id is "released" (a daemon no-op) on every future projection start forever.
     *
     * Anti-vacuity: not clearing KEY_LAST_VD_ID after the release call leaves the marker in prefs,
     * so a second projection releases id 23 again → the second coVerify(exactly = 1) fails (would
     * see 2 calls).
     */
    @Test
    fun `an orphaned VirtualDisplay marker is cleared after release`() {
        context.getSharedPreferences(ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(ClusterProjectionManager.KEY_LAST_VD_ID, 23).commit()
        val helper = directProjectionHelper()

        projectDirect(helper)

        coVerify(exactly = 1) { helper.releaseVirtualDisplay(23) }
        assertEquals(
            "the stale marker must be cleared once the orphan is released",
            -1,
            context.getSharedPreferences(ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(ClusterProjectionManager.KEY_LAST_VD_ID, -1),
        )

        resetManagerState()
        projectDirect(helper)

        coVerify(exactly = 1) { helper.releaseVirtualDisplay(23) }
    }

    /**
     * The tester's car ([MOVED_FIXTURE], 3.19.8): started from scratch, the player's own task left
     * the cluster for the main screen a few seconds after the placement, alive and still freeform.
     * That is a move, not a death: the task goes back to the cluster at the same density and the
     * package keeps its scale.
     *
     * Anti-vacuity: counting the moved task as a strike (the 3.19.8 shape) resets the density to
     * native on the second move and latches the player → both the reset count and the flag fail.
     */
    @Test
    fun `a task moved to the main screen in freeform is no density strike`() {
        val fixture = requireNotNull(javaClass.classLoader?.getResource(MOVED_FIXTURE)).readText()
        assertTrue(
            "the fixture must show the moved freeform task",
            fixture.contains("task=54 state=TaskModeState(windowingMode=5, displayId=0)"),
        )
        setScalePct(50)
        var launches = 0
        var watchReads = 0
        val helper = directProjectionHelper()
        coEvery {
            helper.launchFreeform(any(), any(), any(), any(), any(), any(), any())
        } answers { launches++; FreeformLaunchResult.OK }
        coEvery { helper.getTaskState(NAVI_PACKAGE) } answers {
            if (launches == 0) taskOnMainScreen()
            else when (++watchReads) {
                2, 4 -> movedToMainScreen()
                else -> taskOnCluster()
            }
        }

        projectDirect(helper)
        shadowOf(Looper.getMainLooper()).idleFor(10 * WATCH_INTERVAL_MS + 100, MILLISECONDS)

        coVerify(exactly = 3) { helper.launchFreeform(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { helper.setDisplayDensity(addedDisplayId, 0) }
        assertFalse("a moved task must not latch the package: $journalDump", densityUnsafeFlag())
        assertTrue(
            "the move must be journaled with the task and its mode: $journalDump",
            journalHas("direct task moved to display 0 (task=42 wm=$WINDOWING_MODE_FREEFORM)"),
        )
        assertEquals(ClusterMode.FULLSCREEN, ClusterProjectionManager.currentMode)
    }

    /**
     * A freeform task on the main screen under ANOTHER id is not the task that was placed: the
     * placed one died and something started a new one. That stays a strike, so two of them still
     * latch the package.
     *
     * Anti-vacuity: reading every live freeform task off the cluster as a move never latches.
     */
    @Test
    fun `a new task on the main screen after the placed one is still a strike`() {
        setScalePct(80)
        var launches = 0
        var watchReads = 0
        val helper = directProjectionHelper()
        coEvery {
            helper.launchFreeform(any(), any(), any(), any(), any(), any(), any())
        } answers { launches++; FreeformLaunchResult.OK }
        coEvery { helper.getTaskState(NAVI_PACKAGE) } answers {
            if (launches == 0) taskOnMainScreen()
            else if (++watchReads == 1) taskOnCluster()
            else SplitTaskState(77, WINDOWING_MODE_FREEFORM, 0, 0, 640, 660, displayId = 0)
        }

        projectDirect(helper)
        shadowOf(Looper.getMainLooper()).idleFor(10 * WATCH_INTERVAL_MS + 100, MILLISECONDS)

        assertTrue("two restarts under the scale must latch: $journalDump", densityUnsafeFlag())
    }

    /**
     * The task a recovery relaunch put on the cluster is the placed one from then on: when it
     * moves to the main screen before the next check, still freeform, that is a move, not a second
     * strike against the dead task's id.
     *
     * Anti-vacuity: keeping the id the watch saw before the death (42) counts task 77's move as the
     * second loss → the density is reset and the package latched.
     */
    @Test
    fun `a move of the task a recovery relaunch placed is no second strike`() {
        setScalePct(80)
        var launches = 0
        var watchReads = 0
        var relaunchAt = -1L
        val helper = directProjectionHelper()
        coEvery {
            helper.launchFreeform(any(), any(), any(), any(), any(), any(), any())
        } answers {
            launches++
            if (launches == 2) relaunchAt = SystemClock.uptimeMillis()
            FreeformLaunchResult.OK
        }
        coEvery { helper.getTaskState(NAVI_PACKAGE) } answers {
            when {
                launches == 0 -> taskOnMainScreen()
                launches == 1 -> if (++watchReads == 1) taskOnCluster() else deadTask()
                // Task 77 is on the cluster right after the relaunch and on the main screen by
                // the next check, still freeform.
                launches == 2 && SystemClock.uptimeMillis() == relaunchAt ->
                    SplitTaskState(77, WINDOWING_MODE_FREEFORM, 0, 0, 1280, 480, displayId = addedDisplayId)
                launches == 2 -> SplitTaskState(77, WINDOWING_MODE_FREEFORM, 1306, 0, 1920, 660, displayId = 0)
                else -> SplitTaskState(77, WINDOWING_MODE_FREEFORM, 0, 0, 1280, 480, displayId = addedDisplayId)
            }
        }

        projectDirect(helper)
        shadowOf(Looper.getMainLooper()).idleFor(10 * WATCH_INTERVAL_MS + 100, MILLISECONDS)

        coVerify(exactly = 3) { helper.launchFreeform(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { helper.setDisplayDensity(addedDisplayId, 0) }
        assertFalse("a move of the relaunched task must not latch: $journalDump", densityUnsafeFlag())
        assertTrue(
            "the move must be journaled with the new task: $journalDump",
            journalHas("direct task moved to display 0 (task=77 wm=$WINDOWING_MODE_FREEFORM)"),
        )
    }

    /**
     * Marks already stored by 3.19.8 on users' units: a mark whose reason is a task found alive on
     * another display ("fled to display N") may be one of the moves above, so it is dropped once;
     * a mark whose reason is a real death ("died post-move") is kept.
     *
     * Anti-vacuity: no migration keeps the player latched → "density skipped" comes back and the
     * scaled density count fails; wiping everything drops the navigator's genuine mark.
     */
    @Test
    fun `stored fled marks are dropped once and died marks are kept`() {
        val fixture = requireNotNull(javaClass.classLoader?.getResource(MOVED_FIXTURE)).readText()
        assertTrue(
            "the fixture must show the fled latch",
            fixture.contains("$MUSIC_PACKAGE marked density-unsafe (dpi=160, fled to display 0, second loss)"),
        )
        setScalePct(50)
        context.getSharedPreferences(ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(ClusterProjectionManager.KEY_TARGET_PACKAGE, MUSIC_PACKAGE)
            // What 3.19.7/3.19.8 left behind: probe generation 1, a reason per marked package.
            .putInt(ClusterProjectionManager.KEY_DENSITY_UNSAFE_GENERATION, 1)
            .putBoolean(ClusterProjectionManager.KEY_DENSITY_UNSAFE_PREFIX + MUSIC_PACKAGE, true)
            .putString(WHY_PREFIX + MUSIC_PACKAGE, "fled to display 0 on display 4, 2026-10-06 16:20:26")
            .putBoolean(ClusterProjectionManager.KEY_DENSITY_UNSAFE_PREFIX + NAVI_PACKAGE, true)
            .putString(WHY_PREFIX + NAVI_PACKAGE, "died post-move on display 4, 2026-10-05 10:00:00")
            .commit()
        val helper = directProjectionHelper()
        coEvery { helper.getTaskState(MUSIC_PACKAGE) } returns taskOnMainScreen()

        projectDirect(helper)

        coVerify(exactly = 1) { helper.setDisplayDensity(addedDisplayId, match { it > 0 }) }
        assertFalse(
            "the fled mark must not skip the density: $journalDump",
            journalHas("direct: density skipped for $MUSIC_PACKAGE"),
        )
        assertEquals(listOf(NAVI_PACKAGE), ClusterProjectionManager.densityUnsafePackages(context))
        assertEquals(null, ClusterProjectionManager.densityUnsafeReason(context, MUSIC_PACKAGE))
        assertEquals(
            ClusterProjectionManager.DENSITY_PROBE_GENERATION,
            context.getSharedPreferences(ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(ClusterProjectionManager.KEY_DENSITY_UNSAFE_GENERATION, -1),
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

    /** The placed task itself, alive and still freeform, but on the main display (tester, 3.19.8). */
    private fun movedToMainScreen() =
        SplitTaskState(42, WINDOWING_MODE_FREEFORM, 1306, 0, 1920, 660, displayId = 0)

    /** Placement rejected, task left behind on the cluster display: fullscreen, wrong screen. */
    private fun strandedOnCluster() =
        SplitTaskState(42, WINDOWING_MODE_FULLSCREEN, 0, 0, 1280, 480, displayId = addedDisplayId)

    /** The daemon answered and no task is running for the package — the #121 death shape. */
    private fun deadTask() = SplitTaskState(-1, 0, 0, 0, 0, 0)

    private fun densityUnsafeFlag(): Boolean =
        context.getSharedPreferences(ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(ClusterProjectionManager.KEY_DENSITY_UNSAFE_PREFIX + NAVI_PACKAGE, false)

    /** Latches the verdict as a previous session would have, stamped with the current probe generation. */
    private fun latchDensityUnsafe() {
        context.getSharedPreferences(ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(
                ClusterProjectionManager.KEY_DENSITY_UNSAFE_GENERATION,
                ClusterProjectionManager.DENSITY_PROBE_GENERATION,
            )
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

        /** Cluster journal of a tester's dump (3.19.6, DiLink 5.0): the player latched for good. */
        const val FIXTURE = "native-stack-fixtures/cluster-density-latched-xp-20261003.txt"
        const val MUSIC_PACKAGE = "ru.auto.music"

        /** Cluster journal of a tester's dump (3.19.8, DiLink 5.0): moved task read as a strike. */
        const val MOVED_FIXTURE = "native-stack-fixtures/cluster-moved-freeform-xp-20261006.txt"

        /** Mirrors ClusterProjectionManager.KEY_DENSITY_UNSAFE_WHY_PREFIX (private). */
        const val WHY_PREFIX = "direct_density_unsafe_why_"
    }
}
