package com.bydmate.app.cluster

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.vehicle.FreeformLaunchResult
import com.bydmate.app.data.vehicle.HelperBootstrap
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.SplitTaskState
import com.bydmate.app.helper.HelperBinderProtocol
import com.bydmate.app.helper.WINDOWING_MODE_FREEFORM
import com.bydmate.app.helper.WINDOWING_MODE_FULLSCREEN
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.util.concurrent.TimeUnit.MILLISECONDS
import kotlinx.coroutines.Job
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
 * #134 (Sea Lion 07): the projected navigator dies a few SECONDS after the daemon reports a
 * successful cross-display move, so the daemon-side relaunch-once check (2GIS fix, 527682c2)
 * sees a live task and the client has already reported OK. Covers the post-move death watch
 * armed by `tryDirectProjection`:
 *   - task alive ON THE CLUSTER → the watch is silent (no relaunch, no journal line);
 *   - daemon unreachable (null state) → same silence: a dead channel is not a dead app;
 *   - task gone → exactly one born-on-display relaunch + journal line;
 *   - task alive on the MAIN display → the same recovery (the system restarts a killed app
 *     there, and a display-blind liveness check would report it as a healthy projection);
 *   - setMode(OFF) before the first tick → the watch is cancelled, no relaunch;
 *   - loss again after the relaunch → journaled, no second relaunch.
 *
 * Test mechanics (same shape as [ClusterProjectionSendFailureTest]):
 *   idle → CPM coroutine runs until withContext(IO) for the write-ahead marker;
 *   Thread.sleep → the real IO thread commits and posts the continuation back to Main;
 *   idle → launchFreeform returns OK, the watch is armed, currentMode becomes FULLSCREEN.
 *   idleFor(N ms) then advances the sandbox looper's VIRTUAL clock so the watch's delay()
 *   fires without consuming wall time (Thread.sleep does not advance it).
 *
 * [resetManagerState] is reflective on purpose: ClusterProjectionManager is a process-wide
 * object, and a projection left live by another test class in the same sandbox would make
 * setMode(FULLSCREEN) a no-op ("already in this mode").
 */
@RunWith(RobolectricTestRunner::class)
class ClusterProjectionDirectDeathWatchTest {

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
     * Healthy fleet (Leopard 3): the task survives the move AND stays on the cluster display, so
     * the watch reads it and stops there — no second launchFreeform, nothing in the field journal.
     *
     * The stubbed state carries the cluster display id ([addedDisplayId], the display
     * resolveClusterDisplay picks by name): "alive" is alive on the cluster, so a state with any
     * other display id must NOT satisfy this test.
     *
     * Anti-vacuity: dropping the `state.taskId > 0` early return makes the watch relaunch on a
     * live task → 2 calls and a journal line, failing both assertions.
     */
    @Test
    fun `live task on the cluster display keeps the death watch silent`() {
        val helper = directProjectionHelper()
        coEvery { helper.getTaskState(NAVI_PACKAGE) } returns liveTask()

        projectDirect(helper)
        assertEquals(
            "the stubbed live state must carry the display the projection actually resolved",
            addedDisplayId, clusterDisplayId(),
        )
        shadowOf(Looper.getMainLooper()).idleFor(3 * WATCH_INTERVAL_MS + 100, MILLISECONDS)

        coVerify(exactly = 1) { helper.launchFreeform(any(), any(), any(), any(), any(), any(), any()) }
        assertFalse(
            "a live task on the cluster must not produce a journal line: $journalDump",
            journalHas("direct task"),
        )
    }

    /**
     * Codex audit: the daemon returns the package's FIRST task regardless of display, so the
     * navigator restarted by the system on the main screen reads as taskId > 0. Without the
     * display check that masks the death — recovery never runs and the cluster stays empty.
     *
     * A live task means the relaunch performs a move back to the cluster; that is the very
     * operation project() runs on every star press, so it is safe on a healthy machine (unlike
     * a force-stop, which would kill a navigator the user may simply have reopened on the main
     * screen). Journaled with its own wording so the field log distinguishes the two causes.
     *
     * Anti-vacuity: accepting any taskId > 0 as alive gives 1 call and no journal line → fails.
     */
    @Test
    fun `task alive on the main display counts as a lost projection`() {
        val helper = directProjectionHelper()
        coEvery { helper.getTaskState(NAVI_PACKAGE) } returns fledTask()

        projectDirect(helper)
        shadowOf(Looper.getMainLooper()).idleFor(WATCH_INTERVAL_MS + 100, MILLISECONDS)

        coVerify(exactly = 2) {
            helper.launchFreeform(
                NAVI_PACKAGE, clusterDisplayId(), any(), any(), any(), any(),
                HelperBinderProtocol.PANE_TYPE_RECENTS,
            )
        }
        assertTrue(
            "the flight to the main display must be journaled with its own cause: $journalDump",
            journalHas("direct task fled to display 0"),
        )
    }

    /**
     * A daemon that cannot answer (null state) reports every task as absent. That is a dead
     * channel, not a dead app — relaunching through it would fail anyway and would put a
     * misleading death into the journal the field report is read from.
     *
     * Anti-vacuity: dropping the `state == null` guard makes the watch treat it as a death →
     * 2 calls and a journal line, failing both assertions.
     */
    @Test
    fun `unreachable daemon is not read as a death`() {
        val helper = directProjectionHelper()
        coEvery { helper.getTaskState(NAVI_PACKAGE) } returns null

        projectDirect(helper)
        shadowOf(Looper.getMainLooper()).idleFor(3 * WATCH_INTERVAL_MS + 100, MILLISECONDS)

        coVerify(exactly = 1) { helper.launchFreeform(any(), any(), any(), any(), any(), any(), any()) }
        assertFalse(
            "an unreadable task state must not be journaled as a lost projection: $journalDump",
            journalHas("direct task"),
        )
    }

    /**
     * #134 itself: the task is gone at the first tick → exactly ONE relaunch, still typed
     * RECENTS and still aimed at the cluster display, plus the journal line the field log is
     * read for.
     *
     * With no live task the daemon's resolveOrLaunchTask starts the app with
     * `--windowingMode 5 --display N`, i.e. born on the cluster display — the 2GIS mechanism.
     *
     * Anti-vacuity: removing the arm call in tryDirectProjection leaves 1 call → fails.
     */
    @Test
    fun `dead task triggers exactly one born-on-display relaunch`() {
        val helper = directProjectionHelper()
        coEvery { helper.getTaskState(NAVI_PACKAGE) } returns deadTask()

        projectDirect(helper)
        shadowOf(Looper.getMainLooper()).idleFor(WATCH_INTERVAL_MS + 100, MILLISECONDS)

        coVerify(exactly = 2) {
            helper.launchFreeform(
                NAVI_PACKAGE, clusterDisplayId(), any(), any(), any(), any(),
                HelperBinderProtocol.PANE_TYPE_RECENTS,
            )
        }
        assertTrue(
            "the relaunch must be journaled for the field log: $journalDump",
            journalHas("direct task died post-move"),
        )
    }

    /**
     * An OFF between the launch and the first tick cancels the watch: the projection was torn
     * down, so relaunching the navigator onto a cluster we just gave up would be wrong.
     *
     * Anti-vacuity: removing the cancel from applyModeLocked lets the tick fire and relaunch
     * (2 calls) → fails.
     */
    @Test
    fun `setMode OFF cancels the watch before it can fire`() {
        val helper = directProjectionHelper()
        coEvery { helper.getTaskState(NAVI_PACKAGE) } returns deadTask()

        projectDirect(helper)
        ClusterProjectionManager.setMode(context, ClusterMode.OFF, helper, bootstrap())
        awaitMode(ClusterMode.OFF)
        shadowOf(Looper.getMainLooper()).idleFor(3 * WATCH_INTERVAL_MS + 100, MILLISECONDS)

        coVerify(exactly = 1) { helper.launchFreeform(any(), any(), any(), any(), any(), any(), any()) }
        assertFalse(
            "a cancelled watch must not journal a death: $journalDump",
            journalHas("direct task died post-move"),
        )
    }

    /**
     * The relaunched task is lost again: journaled once and the watch ends. No retry loop — a
     * second relaunch would keep restarting an app this firmware refuses to host.
     *
     * Anti-vacuity: dropping the `relaunched` guard gives 3 calls → fails.
     */
    @Test
    fun `second loss is journaled without a second relaunch`() {
        val helper = directProjectionHelper()
        coEvery { helper.getTaskState(NAVI_PACKAGE) } returns deadTask()

        projectDirect(helper)
        shadowOf(Looper.getMainLooper()).idleFor(3 * WATCH_INTERVAL_MS + 100, MILLISECONDS)

        coVerify(exactly = 2) { helper.launchFreeform(any(), any(), any(), any(), any(), any(), any()) }
        assertTrue(
            "the second loss must be journaled: $journalDump",
            journalHas("born-on-display relaunch did not hold (died post-move)"),
        )
    }

    // --- helpers ---

    private fun directProjectionHelper(): HelperClient = mockk<HelperClient>(relaxed = true).also {
        coEvery {
            it.launchFreeform(any(), any(), any(), any(), any(), any(), any())
        } returns FreeformLaunchResult.OK
        coEvery { it.releaseVirtualDisplay(any()) } returns true
    }

    private fun bootstrap(): HelperBootstrap = mockk<HelperBootstrap>(relaxed = true).also {
        coEvery { it.ensureRunning() } returns true
    }

    /** Task still on the cluster display, freeform — what a healthy Leopard 3 reports. */
    private fun liveTask() =
        SplitTaskState(42, WINDOWING_MODE_FREEFORM, 0, 0, 1280, 480, displayId = addedDisplayId)

    /** The daemon answered, and no task is running for the package (taskId -1) — a real death. */
    private fun deadTask() = SplitTaskState(-1, 0, 0, 0, 0, 0)

    /** Alive, but fullscreen on the main display: the system restarted it there after the death. */
    private fun fledTask() = SplitTaskState(42, WINDOWING_MODE_FULLSCREEN, 0, 0, 1920, 1200, displayId = 0)

    /** Drives a successful direct projection and returns once the manager reports FULLSCREEN. */
    private fun projectDirect(helper: HelperClient) {
        ClusterProjectionManager.setMode(context, ClusterMode.FULLSCREEN, helper, bootstrap())
        awaitMode(ClusterMode.FULLSCREEN)
    }

    /**
     * Drains the main looper until the manager reaches [mode]. Thread.sleep between drains lets
     * the real IO thread (write-ahead marker commit) post its continuation back; it deliberately
     * does NOT advance the virtual clock, so no watch tick can fire while we wait.
     */
    private fun awaitMode(mode: ClusterMode) {
        val shadow = shadowOf(Looper.getMainLooper())
        for (attempt in 0 until 40) {
            shadow.idle()
            if (ClusterProjectionManager.currentMode == mode) return
            Thread.sleep(25)
        }
        fail("projection did not reach $mode (currentMode=${ClusterProjectionManager.currentMode})")
    }

    private fun clusterDisplayId(): Int = ClusterProjectionManager.diag().directDisplayId

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

    /**
     * [ClusterJournal.shared] caches ONE instance over the SharedPreferences object captured at
     * its first use, and [ClusterProjectionManager] caches that instance again in its own field.
     * Robolectric hands every test method a fresh Application (and fresh prefs), but those two
     * caches are static state that survives across methods — so a test would read the journal of
     * the methods that ran before it, and clearing prefs in tearDown does not reach the captured
     * object. Dropping both caches gives each test an empty journal over its own prefs.
     */
    private fun resetSharedJournal() {
        field("journal").set(ClusterProjectionManager, null)
        // Same static-cache problem for the UI7 cluster frame, installed next to the journal: it
        // keeps the prefs of whichever test asked for it first, and its re-assert job outlives it.
        field("frame").set(ClusterProjectionManager, null)
        ClusterJournal::class.java.getDeclaredField("instance")
            .apply { isAccessible = true }
            .set(null, null)
    }

    private fun field(name: String) =
        ClusterProjectionManager::class.java.getDeclaredField(name).apply { isAccessible = true }

    private companion object {
        /** Mirrors ClusterProjectionManager.DIRECT_DEATH_CHECK_INTERVAL_MS (private). */
        const val WATCH_INTERVAL_MS = 2000L
    }
}
