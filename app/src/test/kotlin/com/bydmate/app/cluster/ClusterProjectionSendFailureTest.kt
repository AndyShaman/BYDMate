package com.bydmate.app.cluster

import android.content.Context
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.vehicle.FreeformLaunchResult
import com.bydmate.app.data.vehicle.HelperBootstrap
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.helper.HelperBinderProtocol
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
 * Round 8 / F-1 fix: verifies that grace is NOT released early when [tryDirectProjection]
 * falls through to the VD pipeline (UNAVAILABLE / FAILED), and IS released on VD terminal
 * failures where the task is confirmed absent from the cluster.
 *
 * endClusterSend / onClusterSendFailed contract:
 *   - UNAVAILABLE path: task not moved, but VD-fallback runs — grace must survive.
 *   - FAILED path: same — VD-fallback owns the task; grace must survive.
 *   - VD terminal failure (surface timeout): task never reached cluster — release grace.
 *   - VD success (null return): task on VD display; watchdog's departed-branch resolves
 *     grace normally. onClusterSendFailed is NOT called.
 *
 * Test mechanics:
 *   [drainUntilSurfaceWait]: drains the main looper and lets the real IO thread commit the
 *           SharedPreferences write-ahead marker and post the continuation back, until
 *           overlayView is set: launchFreeform has returned UNAVAILABLE or FAILED, the
 *           branch-under-test has run, and the VD path (addOverlayAndAwaitSurface, wm.addView on
 *           the shadow WM) is suspended at ready.await(). The virtual clock does not move.
 *   idleFor(SURFACE_TIMEOUT_MS + 1, MILLISECONDS): advances the sandbox looper's virtual
 *           clock past the withTimeoutOrNull deadline, firing the timeout and resuming the
 *           continuation that calls onClusterSendFailed.
 *
 * Why ShadowSettings.setCanDrawOverlays: Robolectric 4.13 shadows canDrawOverlays as a
 * static flag (default false). ensureOverlayPermission enters a delay loop on false; this
 * flag makes it pass immediately, letting the coroutine reach tryDirectProjection.
 *
 * tearDown uses runToEndOfTasks() (not Thread.sleep): withTimeoutOrNull registers its timer
 * via handler.postDelayed at virtual time = SystemClock.uptimeMillis() + SURFACE_TIMEOUT_MS.
 * Thread.sleep does NOT advance Robolectric's virtual clock, so the timer never fires and
 * the mutex stays held. runToEndOfTasks() advances the virtual clock to the last scheduled
 * task, firing the timeout and releasing the mutex before the next test starts.
 */
@RunWith(RobolectricTestRunner::class)
class ClusterProjectionSendFailureTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var addedDisplayId: Int = -1
    private var savedOnClusterSendFailed: ((String) -> Unit)? = null
    private var savedOnBeforeClusterSend: ((String) -> Unit)? = null

    @Before
    fun setUp() {
        savedOnClusterSendFailed = ClusterProjectionManager.onClusterSendFailed
        savedOnBeforeClusterSend = ClusterProjectionManager.onBeforeClusterSend

        // Reset WM global so accumulated ViewRootImpl objects from previous tests don't
        // interfere with wm.addView in tests that run later in the suite.
        ShadowWindowManagerGlobal.reset()
        ShadowWindowManagerImpl.reset()
        // Force PAUSED mode for the Choreographer so scheduleVsync() defers to the clock-listener
        // mechanism instead of calling ShadowSystemClock.advanceBy(frameDelay) directly.
        // Without this, stale traversal callbacks from prior tests cascade the virtual clock
        // past the 3000ms surface timeout during wm.addView(), firing withTimeoutOrNull before
        // the test can trigger surfaceCreated.
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
        ShadowSettings.setCanDrawOverlays(false)
        ClusterProjectionManager.onClusterSendFailed = savedOnClusterSendFailed
        ClusterProjectionManager.onBeforeClusterSend = savedOnBeforeClusterSend
        if (addedDisplayId != -1) ShadowDisplayManager.removeDisplay(addedDisplayId)
        context.getSharedPreferences(ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        // Drain any pending coroutine tasks so CPM's mutex and currentMode are settled before
        // the next test. withTimeoutOrNull registers its timer via handler.postDelayed at
        // virtual time = SystemClock.uptimeMillis() + SURFACE_TIMEOUT_MS. Thread.sleep does
        // NOT advance Robolectric's virtual clock, so it would leave the mutex held and
        // overlayView set. runToEndOfTasks() advances the clock to the last scheduled task
        // (the surface timeout), firing it and running the failure path that releases the mutex.
        // Callbacks are already restored to null above, so no test counter is incremented.
        val shadow = shadowOf(Looper.getMainLooper())
        shadow.runToEndOfTasks()
        shadow.idle()
    }

    /**
     * UNAVAILABLE → grace must NOT be released at the UNAVAILABLE branch.
     *
     * Anti-vacuity: restoring the removed `onClusterSendFailed?.invoke(pkg)` call in the
     * UNAVAILABLE branch makes firedPkg non-null at Idle 2 (before the VD timeout) →
     * assertNull fails.
     */
    @Test
    fun `CPM does NOT fire onClusterSendFailed when launchFreeform returns UNAVAILABLE (VD-fallback continues)`() {
        var firedPkg: String? = null
        ClusterProjectionManager.onClusterSendFailed = { pkg -> firedPkg = pkg }

        val helper = mockk<HelperClient>(relaxed = true)
        coEvery {
            helper.launchFreeform(any(), any(), any(), any(), any(), any(), any())
        } returns FreeformLaunchResult.UNAVAILABLE

        val bootstrap = mockk<HelperBootstrap>(relaxed = true)
        coEvery { bootstrap.ensureRunning() } returns true

        ClusterProjectionManager.setMode(context, ClusterMode.FULLSCREEN, helper, bootstrap)

        drainUntilSurfaceWait()  // UNAVAILABLE branch: no onClusterSendFailed call; VD path suspends at ready.await()

        assertNull(
            "CPM must NOT invoke onClusterSendFailed at the UNAVAILABLE branch: " +
            "restoring the removed call makes firedPkg non-null at this point, breaking this test",
            firedPkg,
        )
    }

    /**
     * FAILED → grace must NOT be released at the FAILED branch.
     *
     * Anti-vacuity: restoring the removed `onClusterSendFailed?.invoke(pkg)` call in the
     * FAILED branch makes firedPkg non-null at Idle 2 → assertNull fails.
     */
    @Test
    fun `CPM does NOT fire onClusterSendFailed when launchFreeform returns FAILED (VD-fallback continues)`() {
        var firedPkg: String? = null
        ClusterProjectionManager.onClusterSendFailed = { pkg -> firedPkg = pkg }

        val helper = mockk<HelperClient>(relaxed = true)
        coEvery {
            helper.launchFreeform(any(), any(), any(), any(), any(), any(), any())
        } returns FreeformLaunchResult.FAILED

        val bootstrap = mockk<HelperBootstrap>(relaxed = true)
        coEvery { bootstrap.ensureRunning() } returns true

        ClusterProjectionManager.setMode(context, ClusterMode.FULLSCREEN, helper, bootstrap)

        drainUntilSurfaceWait()  // FAILED branch: no onClusterSendFailed call; VD path suspends at ready.await()

        assertNull(
            "CPM must NOT invoke onClusterSendFailed at the FAILED branch: " +
            "restoring the removed call makes firedPkg non-null at this point, breaking this test",
            firedPkg,
        )
    }

    /**
     * VD surface timeout → onClusterSendFailed must fire exactly once.
     *
     * Setup: direct=true, launchFreeform → UNAVAILABLE (so tryDirectProjection returns false,
     * grace was armed by onBeforeClusterSend but NOT released at UNAVAILABLE). Falls through
     * to VD path. addOverlayAndAwaitSurface cannot complete in Robolectric (no real window
     * manager surface callback), so withTimeoutOrNull(SURFACE_TIMEOUT_MS) fires. The
     * timeout continuation calls onClusterSendFailed?.invoke(pkg).
     *
     * "Exactly once" proves two mutations at once:
     *   - Removing the call from the VD surface-timeout path gives count=0 → fails.
     *   - Restoring the removed call in the UNAVAILABLE branch gives count=2
     *     (UNAVAILABLE + VD timeout) → fails.
     *
     * runToEndOfTasks advances the sandbox looper's virtual clock without consuming real wall time.
     */
    @Test
    fun `CPM fires onClusterSendFailed exactly once on VD surface timeout after UNAVAILABLE fallback`() {
        var callCount = 0
        ClusterProjectionManager.onClusterSendFailed = { _ -> callCount++ }

        val helper = mockk<HelperClient>(relaxed = true)
        coEvery {
            helper.launchFreeform(any(), any(), any(), any(), any(), any(), any())
        } returns FreeformLaunchResult.UNAVAILABLE

        val bootstrap = mockk<HelperBootstrap>(relaxed = true)
        coEvery { bootstrap.ensureRunning() } returns true

        ClusterProjectionManager.setMode(context, ClusterMode.FULLSCREEN, helper, bootstrap)

        drainUntilSurfaceWait()  // UNAVAILABLE: no call; falls through; VD path suspends at surface ready.await()
        val shadow = shadowOf(Looper.getMainLooper())

        // Fire all remaining scheduled tasks including the 3000ms surface timeout.
        shadow.runToEndOfTasks()
        shadow.idle()

        assertEquals(
            "CPM must invoke onClusterSendFailed exactly once on VD surface timeout: " +
            "removing the call from the timeout path gives count=0; " +
            "restoring the removed UNAVAILABLE call gives count=2",
            1, callCount,
        )
        // #134: the cluster launches its projected app as a STANDARD-typed task, so a live
        // navigator task matches and is never removed and recreated on its way to the cluster.
        coVerify {
            helper.launchFreeform(
                any(), any(), any(), any(), any(), any(), HelperBinderProtocol.PANE_TYPE_STANDARD,
            )
        }
    }

    /**
     * VD path reaches launchAndForce → onBeforeClusterSend must fire a third time (re-arm).
     *
     * Scenario: direct=true + UNAVAILABLE → VD fallback. The surface wait is bypassed by
     * manually triggering surfaceCreated on the FakeSurfaceHolder that Robolectric installs for
     * the SurfaceView. createVirtualDisplay returns 1, launchAndForce returns false (terminal
     * VD failure). Expected count of onBeforeClusterSend calls = 3:
     *   1. tryDirectProjection arms grace before launchFreeform (line ~924).
     *   2. VD-path entry re-arm (line ~835, covers surface→createVd, NEW-8-1 first call).
     *   3. Re-arm immediately before launchAndForce (line ~874, NEW-8-1 second call, under test here).
     *
     * Anti-vacuity: removing the re-arm at line ~874 gives count=2 → assertEquals(3,2) fails.
     *
     * Surface trigger mechanics: after idle2 suspends the coroutine at ready.await(),
     * ClusterProjectionManager.overlayView (set to the container FrameLayout immediately before
     * ready.await()) is accessed via reflection. We obtain the child SurfaceView's
     * FakeSurfaceHolder and call surfaceCreated(mockHolder) on each registered callback.
     * ready.complete(mockSurface) fires inside the callback, resuming the coroutine.
     * A final shadow.idle() drains the chain through createClusterVd → launchAndForce re-arm →
     * launchAndForce → onClusterSendFailed → hideOverlay.
     *
     * releaseVirtualDisplay is stubbed to return true so a stale remoteDisplayId from a prior
     * test run does not divert execution into the stale-VD abort branch before createVirtualDisplay.
     *
     * FakeSurfaceHolder.getSurface() returns null — a mock holder with a non-null surface is used
     * so ready.complete(surface) receives an actual Surface instance.
     *
     * Order enforcement (NEW-9-3): launchAndForce is stubbed with coAnswers so the count can be
     * asserted AT THE MOMENT OF CALL. A mutation that moves the re-arm to after launchAndForce
     * would give count=2 at call time and fail the in-stub assertion, even though the final count
     * would still reach 3. This closes the ordering gap missed by the simple count-at-end check.
     */
    @Test
    fun `CPM fires onBeforeClusterSend again before launchAndForce in VD path after UNAVAILABLE fallback`() {
        var beforeCallCount = 0
        ClusterProjectionManager.onBeforeClusterSend = { _ -> beforeCallCount++ }

        val helper = mockk<HelperClient>(relaxed = true)
        coEvery {
            helper.launchFreeform(any(), any(), any(), any(), any(), any(), any())
        } returns FreeformLaunchResult.UNAVAILABLE
        coEvery { helper.createVirtualDisplay(any(), any(), any(), any(), any(), any()) } returns 1
        // Ensures a stale remoteDisplayId left by a prior test does not short-circuit into the
        // abort path ("stale releaseVirtualDisplay failed; aborting") before createVirtualDisplay.
        coEvery { helper.releaseVirtualDisplay(any()) } returns true
        // Assert the re-arm count AT call time so a mutation moving the invoke() to after
        // launchAndForce (count=2 here) is caught even if the final post-call count would be 3.
        coEvery { helper.launchAndForce(any(), any(), any(), any()) } coAnswers {
            assertEquals(
                "onBeforeClusterSend must fire before launchAndForce: count must be 3 at call time; " +
                "moving the re-arm at ~CPM:874 to after launchAndForce gives count=2 here and fails",
                3, beforeCallCount,
            )
            false
        }

        val bootstrap = mockk<HelperBootstrap>(relaxed = true)
        coEvery { bootstrap.ensureRunning() } returns true

        ClusterProjectionManager.setMode(context, ClusterMode.FULLSCREEN, helper, bootstrap)

        val shadow = shadowOf(Looper.getMainLooper())

        // Drain until the surface wait: both IO dispatches (recoverStaleDirectDensity and the
        // write-ahead prefs.commit) complete in wall time, then the coroutine runs
        // tryDirectProjection (count=1), UNAVAILABLE branch, VD-path entry re-arm (count=2),
        // wm.addView, and suspends at ready.await(). With ShadowChoreographer.setPaused(true) (set
        // in setUp), scheduleVsync() uses the clock-listener path and does NOT advance the virtual
        // clock here, so the 3000ms surface timeout cannot fire during this drain.
        val container = drainUntilSurfaceWait()
        val surfaceView = container.getChildAt(0) as SurfaceView

        // Fire surfaceCreated with a mock holder so ready.complete(mockSurface) runs.
        // FakeSurfaceHolder.getSurface() returns null, so we must supply a mock holder with a
        // non-null surface — otherwise ready.complete(null) leaves the deferred in a bad state.
        val mockHolder = mockk<SurfaceHolder>()
        val mockSurface = mockk<Surface>()
        every { mockHolder.surface } returns mockSurface
        shadowOf(surfaceView).getFakeSurfaceHolder().getCallbacks()
            .forEach { it.surfaceCreated(mockHolder) }

        // Resume: createClusterVd (returns 1) → launchAndForce re-arm (count=3) → launchAndForce
        // stub (asserts count=3 at call time) → onClusterSendFailed → hideOverlay.
        shadow.idle()

        assertEquals(
            "CPM must call onBeforeClusterSend again before launchAndForce (NEW-8-1 second re-arm): " +
            "expected count=3 (tryDirect + VD-entry + launchAndForce); " +
            "removing the re-arm at ~CPM:874 gives count=2 and this assertion fails",
            3, beforeCallCount,
        )
    }

    /**
     * Drains the main looper until the VD path waits for its surface. CPM sets overlayView =
     * container immediately before ready.await(); a non-null value confirms the coroutine has
     * reached the surface-wait step (and that the direct branch under test is behind it). This
     * avoids the ShadowWindowManagerImpl.views Multimap, whose display-id indexing is fragile
     * across Robolectric test runs. Thread.sleep between drains lets the real IO thread post its
     * continuation back; the virtual clock is NOT advanced, so the surface timeout cannot fire.
     */
    private fun drainUntilSurfaceWait(): FrameLayout {
        val shadow = shadowOf(Looper.getMainLooper())
        val deadline = System.currentTimeMillis() + 10_000
        while (overlayView() == null && System.currentTimeMillis() < deadline) {
            shadow.idle()
            if (overlayView() == null) Thread.sleep(10)
        }
        return checkNotNull(overlayView()) {
            "ClusterProjectionManager.overlayView is null after 10 s — coroutine did not reach " +
            "addOverlayAndAwaitSurface. Possible cause: wm.addView threw before overlayView was set."
        }
    }

    private fun overlayView(): FrameLayout? =
        field("overlayView").get(ClusterProjectionManager) as? FrameLayout

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
        field("journal").set(ClusterProjectionManager, null)
        field("frame").set(ClusterProjectionManager, null)
        ClusterJournal::class.java.getDeclaredField("instance")
            .apply { isAccessible = true }
            .set(null, null)
    }

    private fun field(name: String) =
        ClusterProjectionManager::class.java.getDeclaredField(name).apply { isAccessible = true }
}
