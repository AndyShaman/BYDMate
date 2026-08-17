package com.bydmate.app.split

import app.cash.turbine.test
import com.bydmate.app.data.vehicle.FreeformLaunchResult
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.RaiseOutcome
import com.bydmate.app.data.vehicle.SplitTaskState
import com.bydmate.app.data.vehicle.TopTaskInfo
import com.bydmate.app.helper.HelperBinderProtocol
import io.mockk.Called
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// ─── Fakes ────────────────────────────────────────────────────────────────────

private class FakeSplitPreferences(featureEnabled: Boolean = true) : SplitPreferences {
    private var saved: SplitPair? = null
    private var featureEnabled = featureEnabled
    override fun getLastPair(): SplitPair? = saved
    override fun saveLastPair(pair: SplitPair) { saved = pair }
    override fun clearLastPair() { saved = null }
    override fun isFeatureEnabled(): Boolean = featureEnabled
    override fun setFeatureEnabled(enabled: Boolean) { featureEnabled = enabled }
}

private class FakeSplitBackdrop(private val showResult: Boolean = true) : SplitBackdrop {
    var shown = false
    override suspend fun show(): Boolean { shown = true; return showResult }
    override fun hide() { shown = false }
}

/**
 * Backdrop whose [show] suspends until [release] is called externally.
 * Used to test the caller-cancellation-survival fix: cancel the caller while
 * show() is suspended, then release — the session must still reach Active.
 */
private class SuspendingFakeSplitBackdrop : SplitBackdrop {
    var shown = false
    val showGate = CompletableDeferred<Boolean>()
    fun release(result: Boolean = true) { showGate.complete(result) }
    override suspend fun show(): Boolean { shown = true; return showGate.await() }
    override fun hide() { shown = false }
}

/**
 * Journal store that counts the physical writes. The ring is fully re-serialised and committed
 * on every append, so "how many lines are in the ring" does not say how often it was written —
 * the `(xN)` dedup collapses the text of a repeating payload but still writes each time.
 */
private class CountingJournalStore : SplitJournalImpl.Store {
    var value: String = ""
    var writes = 0
    override fun read(): String = value
    override fun write(value: String) { this.value = value; writes++ }
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

/**
 * Firmware pin for the pane-type gate (#130). Every test that asserts a pane activityType says
 * which branch of [PaneTypePolicy] it means instead of inheriting the JVM default (Build.FINGERPRINT
 * is null under a plain unit test → RECENTS fallback).
 */
private const val KNOWN_GOOD_FINGERPRINT = "BYD/Leopard3/eng.build.20260106:12/user/release-keys"
private const val SONG_L_FINGERPRINT = "BYD/SongL/eng.build.20260320:12/user/release-keys"
private val standardPanes = PaneTypePolicy(KNOWN_GOOD_FINGERPRINT)
private val recentsPanes = PaneTypePolicy(SONG_L_FINGERPRINT)

/** HelperClient stub that accepts a package and returns a happy-path task state. */
private fun HelperClient.stubTask(pkg: String, taskId: Int, mode: Int, b: SplitBounds) {
    coEvery { getTaskState(pkg) } returns SplitTaskState(taskId, mode, b.left, b.top, b.right, b.bottom)
}

private fun HelperClient.stubLaunch(vararg pkgs: String, result: FreeformLaunchResult = FreeformLaunchResult.OK) {
    // A healthy daemon can also kill apps. Without this a relaxed mock returns false from
    // forceStop, and every restart path takes the "forceStop failed and the task is still alive"
    // bail-out added in W6-F1 fix round 3. Tests that exercise that bail-out stub forceStop
    // explicitly AFTER calling stubLaunch.
    coEvery { forceStop(any()) } returns true
    pkgs.forEach { pkg ->
        coEvery { launchFreeform(pkg, any(), any(), any(), any(), any(), any()) } returns result
    }
}

// ─── Bounds math ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalCoroutinesApi::class)
class SplitSessionManagerTest {

    @Test fun `boundsFor RIGHT - wide left of junction, narrow right`() {
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        assertEquals(SplitBounds(0, 84, 1280, 990), wide)
        assertEquals(SplitBounds(1280, 84, 1920, 990), narrow)
    }

    @Test fun `boundsFor LEFT - wide right of junction, narrow left`() {
        val (wide, narrow) = boundsFor(SplitSide.LEFT)
        assertEquals(SplitBounds(640, 84, 1920, 990), wide)
        assertEquals(SplitBounds(0, 84, 640, 990), narrow)
    }

    // ── start() ───────────────────────────────────────────────────────────────

    @Test fun `start OK sets Active state, saves pair, shows backdrop`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val prefs = FakeSplitPreferences()
        val backdrop = FakeSplitBackdrop()

        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", taskId = 10, mode = 5, b = wide)
        helper.stubTask("pkg.narrow", taskId = 11, mode = 5, b = narrow)

        val mgr = SplitSessionManager(helper, prefs, backdrop, backgroundScope, tickDelayMs = 60_000)
        val pair = SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT)

        val result = mgr.start(pair)

        assertEquals(SplitStartResult.OK, result)
        assertEquals(SplitSessionState.Active(pair, 11, 10), mgr.state.value)
        assertEquals(pair, prefs.getLastPair())
        assertEquals(true, backdrop.shown)
    }

    @Test fun `start FREEFORM_UNAVAILABLE hides backdrop, returns FREEFORM_UNAVAILABLE`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val backdrop = FakeSplitBackdrop()

        helper.stubLaunch("pkg.wide", result = FreeformLaunchResult.UNAVAILABLE)

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), backdrop, backgroundScope)
        val result = mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        assertEquals(SplitStartResult.FREEFORM_UNAVAILABLE, result)
        assertEquals(SplitSessionState.Idle, mgr.state.value)
        assertEquals(false, backdrop.shown)
    }

    @Test fun `start FAILED on narrow launch returns LAUNCH_FAILED`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val backdrop = FakeSplitBackdrop()

        helper.stubLaunch("pkg.wide")
        helper.stubLaunch("pkg.narrow", result = FreeformLaunchResult.FAILED)

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), backdrop, backgroundScope)
        val result = mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        assertEquals(SplitStartResult.LAUNCH_FAILED, result)
        assertEquals(SplitSessionState.Idle, mgr.state.value)
        assertEquals(false, backdrop.shown)
    }

    // ── startLocked: confirming task-state reads (#139) ───────────────────────

    /**
     * The two reads that resolve the pane task ids queue behind the placement round-trips on the
     * daemon's single channel, so a null there says "the channel did not answer", not "there is no
     * task": a split standing on screen was reported LAUNCH_FAILED that way, and the revert that
     * follows no-oped on the same busy channel. One retry, and the start must land.
     */
    @Test fun `a silent confirming read is retried once and the start still lands`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        // Two pre-launch reads see no task (clean-start path), the confirming read is swallowed by
        // the busy channel, and the retry sees the live freeform pane.
        coEvery { helper.getTaskState("pkg.wide") } returnsMany listOf(
            null, null, null,
            SplitTaskState(10, 5, wide.left, wide.top, wide.right, wide.bottom),
        )
        helper.stubTask("pkg.narrow", taskId = 11, mode = 5, b = narrow)
        val store = CountingJournalStore()

        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope,
            tickDelayMs = 60_000,
            journal = SplitJournalImpl(store) { 1_700_000_000_000L },
        )
        val pair = SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT)
        val startedAt = currentTime

        val result = mgr.start(pair)

        assertEquals(SplitStartResult.OK, result)
        assertEquals(SplitSessionState.Active(pair, 11, 10), mgr.state.value)
        assertEquals("only the single retry pause may elapse", 500L, currentTime - startedAt)
        assertTrue(
            "the retry is what the field log has to show, journal was ${store.value}",
            store.value.contains("start wide task state confirmed on retry (pkg=pkg.wide)"),
        )
    }

    @Test fun `two silent confirming reads fail the start and name the silence`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val backdrop = FakeSplitBackdrop()
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        coEvery { helper.getTaskState(any()) } returns null
        val store = CountingJournalStore()

        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), backdrop, backgroundScope,
            tickDelayMs = 60_000,
            journal = SplitJournalImpl(store) { 1_700_000_000_000L },
        )

        val result = mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        assertEquals(SplitStartResult.LAUNCH_FAILED, result)
        assertEquals(SplitSessionState.Idle, mgr.state.value)
        assertFalse(backdrop.shown)
        assertTrue(
            "journal was ${store.value}",
            store.value.contains("start wide pkg.wide -> no task state reply (retried once)"),
        )
    }

    /**
     * `taskId == -1` right after a launch means "not yet", not "never" (#139): on a cold start the
     * app process has not created its task when the first read lands, which is why the user's
     * second tap always worked. The pane must be given the appearance window.
     */
    @Test fun `a task that appears on the third read still lands the start`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        // Two pre-launch reads see no task (clean-start path), then the cold-launching app reports
        // no task twice before its task shows up.
        coEvery { helper.getTaskState("pkg.wide") } returnsMany listOf(
            null, null,
            SplitTaskState(-1, 0, 0, 0, 0, 0),
            SplitTaskState(-1, 0, 0, 0, 0, 0),
            SplitTaskState(10, 5, wide.left, wide.top, wide.right, wide.bottom),
        )
        helper.stubTask("pkg.narrow", taskId = 11, mode = 5, b = narrow)
        val store = CountingJournalStore()

        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope,
            tickDelayMs = 60_000,
            journal = SplitJournalImpl(store) { 1_700_000_000_000L },
        )
        val pair = SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT)
        val startedAt = currentTime

        val result = mgr.start(pair)

        assertEquals(SplitStartResult.OK, result)
        assertEquals(SplitSessionState.Active(pair, 11, 10), mgr.state.value)
        assertEquals("one pause per re-read, nothing more", 1000L, currentTime - startedAt)
        coVerify(exactly = 0) { helper.setTaskWindowingMode(any(), 1) }
        assertTrue(
            "journal was ${store.value}",
            store.value.contains("start wide task state confirmed on retry (pkg=pkg.wide)"),
        )
    }

    /** A pane that never produces a task inside the appearance window fails the start honestly. */
    @Test fun `a task absent through the whole appearance window fails the start`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val (_, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        coEvery { helper.getTaskState("pkg.wide") } returns SplitTaskState(-1, 0, 0, 0, 0, 0)
        helper.stubTask("pkg.narrow", taskId = 11, mode = 5, b = narrow)
        val store = CountingJournalStore()
        val backdrop = FakeSplitBackdrop()

        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), backdrop, backgroundScope,
            tickDelayMs = 60_000,
            journal = SplitJournalImpl(store) { 1_700_000_000_000L },
        )
        val startedAt = currentTime

        val result = mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        assertEquals(SplitStartResult.LAUNCH_FAILED, result)
        assertEquals(SplitSessionState.Idle, mgr.state.value)
        assertFalse(backdrop.shown)
        assertEquals("six reads, five pauses between them", 2500L, currentTime - startedAt)
        // Both panes are handed back: the wide task does not exist, the narrow one is reverted.
        coVerify { helper.setTaskWindowingMode(11, 1) }
        assertTrue(
            "the observed state is the diagnosis, journal was ${store.value}",
            store.value.contains("start wide pkg.wide -> no task after launch (task=-1 mode=0 display=0, waited 6 reads)"),
        )
    }

    @Test fun `a failed wide placement names the pane in the journal`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        helper.stubLaunch("pkg.wide", result = FreeformLaunchResult.FAILED)
        coEvery { helper.getTaskState(any()) } returns null
        val store = CountingJournalStore()

        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope,
            tickDelayMs = 60_000,
            journal = SplitJournalImpl(store) { 1_700_000_000_000L },
        )

        assertEquals(
            SplitStartResult.LAUNCH_FAILED,
            mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT)),
        )
        assertTrue(
            "journal was ${store.value}",
            store.value.contains("start wide pkg.wide -> placement FAILED"),
        )
    }

    @Test fun `a failed narrow placement names the pane in the journal`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        helper.stubLaunch("pkg.wide")
        helper.stubLaunch("pkg.narrow", result = FreeformLaunchResult.FAILED)
        coEvery { helper.getTaskState(any()) } returns null
        val store = CountingJournalStore()

        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope,
            tickDelayMs = 60_000,
            journal = SplitJournalImpl(store) { 1_700_000_000_000L },
        )

        assertEquals(
            SplitStartResult.LAUNCH_FAILED,
            mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT)),
        )
        assertTrue(
            "journal was ${store.value}",
            store.value.contains("start narrow pkg.narrow -> placement FAILED"),
        )
    }

    // ── startLastPair() ───────────────────────────────────────────────────────

    @Test fun `startLastPair returns null when nothing saved`() = runTest {
        val mgr = SplitSessionManager(
            mockk(relaxed = true), FakeSplitPreferences(),
            FakeSplitBackdrop(), backgroundScope,
        )
        assertNull(mgr.startLastPair())
    }

    @Test fun `startLastPair uses saved pair`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val prefs = FakeSplitPreferences()
        val pair = SplitPair("pkg.narrow", "pkg.wide", SplitSide.LEFT)
        prefs.saveLastPair(pair)

        val (wide, narrow) = boundsFor(SplitSide.LEFT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", taskId = 20, mode = 5, b = wide)
        helper.stubTask("pkg.narrow", taskId = 21, mode = 5, b = narrow)

        val mgr = SplitSessionManager(helper, prefs, FakeSplitBackdrop(), backgroundScope, 60_000)
        val result = mgr.startLastPair()

        assertEquals(SplitStartResult.OK, result)
        assertEquals(SplitSessionState.Active(pair, 21, 20), mgr.state.value)
    }

    // ── mirror() ──────────────────────────────────────────────────────────────

    @Test fun `mirror RIGHT to LEFT calls setTaskBounds with LEFT geometry`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val prefs = FakeSplitPreferences()
        val (wideR, narrowR) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wideR)
        helper.stubTask("pkg.narrow", 11, 5, narrowR)

        val mgr = SplitSessionManager(helper, prefs, FakeSplitBackdrop(), backgroundScope, 60_000)
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        mgr.mirror()

        // After mirror: side is LEFT — narrow task 11 gets narrow-LEFT bounds,
        // wide task 10 gets wide-LEFT bounds.
        val (wideL, narrowL) = boundsFor(SplitSide.LEFT)
        coVerify { helper.setTaskBounds(11, narrowL.left, narrowL.top, narrowL.right, narrowL.bottom) }
        coVerify { helper.setTaskBounds(10, wideL.left, wideL.top, wideL.right, wideL.bottom) }

        val newState = mgr.state.value as SplitSessionState.Active
        assertEquals(SplitSide.LEFT, newState.pair.narrowSide)
        assertEquals(SplitSide.LEFT, prefs.getLastPair()?.narrowSide)
    }

    @Test fun `mirror LEFT to RIGHT uses RIGHT geometry`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val prefs = FakeSplitPreferences()
        val (wideL, narrowL) = boundsFor(SplitSide.LEFT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wideL)
        helper.stubTask("pkg.narrow", 11, 5, narrowL)

        val mgr = SplitSessionManager(helper, prefs, FakeSplitBackdrop(), backgroundScope, 60_000)
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.LEFT))
        mgr.mirror()

        val (wideR, narrowR) = boundsFor(SplitSide.RIGHT)
        coVerify { helper.setTaskBounds(11, narrowR.left, narrowR.top, narrowR.right, narrowR.bottom) }
        coVerify { helper.setTaskBounds(10, wideR.left, wideR.top, wideR.right, wideR.bottom) }
        assertEquals(SplitSide.RIGHT, (mgr.state.value as SplitSessionState.Active).pair.narrowSide)
    }

    // ── swapApps() ────────────────────────────────────────────────────────────

    @Test fun `swapApps sends former narrow task to wide bounds and vice-versa`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val prefs = FakeSplitPreferences()
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)

        val mgr = SplitSessionManager(helper, prefs, FakeSplitBackdrop(), backgroundScope, 60_000)
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        mgr.swapApps()

        // Task 11 (formerly narrow) → wide bounds; Task 10 (formerly wide) → narrow bounds.
        coVerify { helper.setTaskBounds(11, wide.left, wide.top, wide.right, wide.bottom) }
        coVerify { helper.setTaskBounds(10, narrow.left, narrow.top, narrow.right, narrow.bottom) }

        val newState = mgr.state.value as SplitSessionState.Active
        assertEquals("pkg.wide", newState.pair.narrowPkg)
        assertEquals("pkg.narrow", newState.pair.widePkg)
        // Task ids follow the apps: formerly wide task (10) is now narrow.
        assertEquals(10, newState.narrowTaskId)
        assertEquals(11, newState.wideTaskId)
        assertEquals("pkg.wide", prefs.getLastPair()?.narrowPkg)
    }

    // ── exit() ────────────────────────────────────────────────────────────────

    @Test fun `exit sets both tasks fullscreen, hides backdrop, emits SessionEnded EXIT`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val backdrop = FakeSplitBackdrop()
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), backdrop, backgroundScope, 60_000)
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        mgr.events.test {
            mgr.exit()

            coVerify { helper.setTaskWindowingMode(11, 1) }
            coVerify { helper.setTaskWindowingMode(10, 1) }
            coVerify { helper.setFocusedTask(10) }
            assertEquals(false, backdrop.shown)
            assertEquals(SplitSessionState.Idle, mgr.state.value)
            val event = awaitItem()
            assertEquals(SplitEvent.SessionEnded(EndReason.EXIT), event)
        }
    }

    @Test fun `exit preserves last saved pair`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val prefs = FakeSplitPreferences()
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)

        val pair = SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT)
        val mgr = SplitSessionManager(helper, prefs, FakeSplitBackdrop(), backgroundScope, 60_000)
        mgr.start(pair)
        mgr.exit()

        assertEquals(pair, prefs.getLastPair())
    }

    // ── Watchdog: null tick is a no-op ────────────────────────────────────────

    @Test fun `watchdog null getTaskState skips tick without any helper call`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope, 100)
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        // Now make both return null (transient failure).
        coEvery { helper.getTaskState(any()) } returns null

        advanceTimeBy(200)
        runCurrent()

        // Session must still be active and no setTaskBounds / setTaskWindowingMode called.
        assertEquals(true, mgr.state.value is SplitSessionState.Active)
        coVerify(exactly = 0) { helper.setTaskBounds(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { helper.setTaskWindowingMode(any(), any(), any()) }
    }

    // ── Watchdog: bounds drift → snap-back ────────────────────────────────────

    @Test fun `watchdog snaps narrow bounds back when they drift`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope, 100)
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        // Simulate narrow task bounds drifting to unexpected values.
        coEvery { helper.getTaskState("pkg.narrow") } returns
            SplitTaskState(11, 5, 1300, 100, 1920, 980)  // drifted
        // Wide stays correct.
        helper.stubTask("pkg.wide", 10, 5, wide)

        advanceTimeBy(150)
        runCurrent()

        // Watchdog should snap narrow back to expected bounds.
        coVerify { helper.setTaskBounds(11, narrow.left, narrow.top, narrow.right, narrow.bottom) }
        // Session stays Active.
        assertEquals(true, mgr.state.value is SplitSessionState.Active)
    }

    @Test fun `watchdog snaps wide bounds back when they drift`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope, 100)
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        // Wide drifts; narrow stays correct.
        coEvery { helper.getTaskState("pkg.wide") } returns
            SplitTaskState(10, 5, 50, 90, 1260, 985)  // drifted
        helper.stubTask("pkg.narrow", 11, 5, narrow)

        advanceTimeBy(150)
        runCurrent()

        coVerify { helper.setTaskBounds(10, wide.left, wide.top, wide.right, wide.bottom) }
        assertEquals(true, mgr.state.value is SplitSessionState.Active)
    }

    // ── Watchdog: pane gone → PaneClosed ──────────────────────────────────────

    @Test fun `watchdog emits PaneClosed NARROW when narrow task disappears`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope, 100)
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        // Narrow task gone.
        coEvery { helper.getTaskState("pkg.narrow") } returns SplitTaskState(-1, 0, 0, 0, 0, 0)
        helper.stubTask("pkg.wide", 10, 5, wide)

        mgr.events.test {
            advanceTimeBy(150)
            runCurrent()
            val event = awaitItem()
            assertEquals(SplitEvent.PaneClosed(Pane.NARROW), event)
            // Session stays Active — UI will call changeApp or exit.
            assertEquals(true, mgr.state.value is SplitSessionState.Active)
        }
    }

    @Test fun `watchdog emits PaneClosed WIDE when wide task disappears`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope, 100)
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        // Wide task gone; narrow stays.
        coEvery { helper.getTaskState("pkg.wide") } returns SplitTaskState(-1, 0, 0, 0, 0, 0)
        helper.stubTask("pkg.narrow", 11, 5, narrow)

        mgr.events.test {
            advanceTimeBy(150)
            runCurrent()
            val event = awaitItem()
            assertEquals(SplitEvent.PaneClosed(Pane.WIDE), event)
            assertEquals(true, mgr.state.value is SplitSessionState.Active)
        }
    }

    // ── Watchdog: maximize → session ends ─────────────────────────────────────

    @Test fun `watchdog ends session when narrow task is maximised`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        val backdrop = FakeSplitBackdrop()
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), backdrop, backgroundScope, 100)
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        // Narrow goes fullscreen (user tapped maximize).
        coEvery { helper.getTaskState("pkg.narrow") } returns
            SplitTaskState(11, 1, 0, 0, 1920, 1080)
        helper.stubTask("pkg.wide", 10, 5, wide)

        mgr.events.test {
            advanceTimeBy(150)
            runCurrent()
            // Wide task gets windowing mode 1 (fullscreen).
            coVerify { helper.setTaskWindowingMode(10, 1) }
            assertEquals(false, backdrop.shown)
            assertEquals(SplitSessionState.Idle, mgr.state.value)
            val event = awaitItem()
            assertEquals(SplitEvent.SessionEnded(EndReason.MAXIMIZED), event)
        }
    }

    @Test fun `watchdog ends session when wide task is maximised`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        val backdrop = FakeSplitBackdrop()
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), backdrop, backgroundScope, 100)
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        // Wide goes fullscreen.
        coEvery { helper.getTaskState("pkg.wide") } returns
            SplitTaskState(10, 1, 0, 0, 1920, 1080)
        helper.stubTask("pkg.narrow", 11, 5, narrow)

        mgr.events.test {
            advanceTimeBy(150)
            runCurrent()
            // Narrow task gets windowing mode 1.
            coVerify { helper.setTaskWindowingMode(11, 1) }
            assertEquals(false, backdrop.shown)
            assertEquals(SplitSessionState.Idle, mgr.state.value)
            val event = awaitItem()
            assertEquals(SplitEvent.SessionEnded(EndReason.MAXIMIZED), event)
        }
    }

    // ── Hotfix 390-2: backdrop resurfaced above the panes ─────────────────────
    //
    // Opening a fullscreen Activity of our own package hides the freeform panes; on Back, ATMS
    // brings the BACKDROP task to front instead of them, leaving a black screen plus the pill.
    // onBackdropResurfaced re-raises the panes. It is a cosmetic re-raise: nothing is
    // force-stopped, relaunched, or pulled off the cluster.

    @Test fun `onBackdropResurfaced re-raises both live panes with their bounds`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        coEvery { helper.raiseFreeformTaskDetailed(any(), any(), any()) } returns RaiseOutcome.RAISED

        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope, 60_000,
            paneTypePolicy = standardPanes,
        )
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))
        clearMocks(helper, answers = false)

        mgr.onBackdropResurfaced()

        // Wide first, then narrow — narrow ends up on top exactly as at start.
        coVerifyOrder {
            helper.raiseFreeformTaskDetailed("pkg.wide", 0, HelperBinderProtocol.PANE_TYPE_STANDARD)
            helper.setTaskBounds(10, wide.left, wide.top, wide.right, wide.bottom)
            helper.raiseFreeformTaskDetailed("pkg.narrow", 0, HelperBinderProtocol.PANE_TYPE_STANDARD)
            helper.setTaskBounds(11, narrow.left, narrow.top, narrow.right, narrow.bottom)
        }
        // Cosmetic only.
        coVerify(exactly = 0) { helper.forceStop(any()) }
        coVerify(exactly = 0) { helper.launchFreeform(any(), any(), any(), any(), any(), any(), any()) }
    }

    /**
     * A departed pane lives on the instrument cluster legitimately. Raising it would yank it back
     * to display 0 — the re-raise must leave it alone.
     *
     * The departed flag is the load-bearing gate here, not the display check: the flag is cleared
     * only on the tick that sees the task home again, so between the task's return and that tick
     * the state already reads display 0 while the pane is still officially departed. This test
     * models exactly that window (cluster on the arming tick, display 0 afterwards) — with only the
     * display check the raise would fire.
     *
     * Anti-vacuity: removing the departed gate makes the raise fire for the departed package and
     * the coVerify below fails.
     */
    @Test fun `onBackdropResurfaced leaves a departed pane alone`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        coEvery { helper.raiseFreeformTaskDetailed(any(), any(), any()) } returns RaiseOutcome.RAISED

        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope, tickDelayMs = 100,
            applyCalibratedBounds = { _, _ -> true },
        )
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))
        // Narrow departs to the cluster; the watchdog arms its departed flag. Afterwards the task
        // reads display 0 again while the flag is still set (the clearing tick has not run).
        coEvery { helper.getTaskState("pkg.narrow") } returnsMany listOf(
            SplitTaskState(11, 5, 0, 0, 1280, 480, displayId = 2),
            SplitTaskState(11, 5, narrow.left, narrow.top, narrow.right, narrow.bottom),
        )
        advanceTimeBy(150); runCurrent()
        clearMocks(helper, answers = false)

        mgr.onBackdropResurfaced()

        coVerify(exactly = 1) { helper.raiseFreeformTaskDetailed("pkg.wide", 0, any()) }
        coVerify(exactly = 0) { helper.raiseFreeformTaskDetailed("pkg.narrow", 0, any()) }
        coVerify(exactly = 0) { helper.setTaskBounds(11, any(), any(), any(), any()) }
    }

    @Test fun `onBackdropResurfaced skips a pane whose task is gone without relaunching it`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        coEvery { helper.raiseFreeformTaskDetailed(any(), any(), any()) } returns RaiseOutcome.RAISED

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope, 60_000)
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))
        coEvery { helper.getTaskState("pkg.narrow") } returns SplitTaskState(-1, 0, 0, 0, 0, 0)
        clearMocks(helper, answers = false)

        mgr.onBackdropResurfaced()

        coVerify(exactly = 0) { helper.raiseFreeformTaskDetailed("pkg.narrow", 0, any()) }
        coVerify(exactly = 0) { helper.launchFreeform("pkg.narrow", any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { helper.forceStop("pkg.narrow") }
    }

    /**
     * A pane the user has dragged into the NATIVE split (windowingMode 3/4) is no longer ours —
     * the tick detection is about to end the session. This lifecycle edge must not raise it back
     * and fight that teardown.
     *
     * Anti-vacuity: removing the freeform gate makes the raise fire and the coVerify below fails.
     */
    @Test fun `onBackdropResurfaced skips a pane the user moved into the native split`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        coEvery { helper.raiseFreeformTaskDetailed(any(), any(), any()) } returns RaiseOutcome.RAISED

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope, 60_000)
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))
        clearMocks(helper, answers = false)
        // SPLIT_SCREEN_PRIMARY — the native split, not our freeform pane.
        coEvery { helper.getTaskState("pkg.narrow") } returns
            SplitTaskState(11, 3, narrow.left, narrow.top, narrow.right, narrow.bottom)

        mgr.onBackdropResurfaced()

        coVerify(exactly = 0) { helper.raiseFreeformTaskDetailed("pkg.narrow", 0, any()) }
        coVerify(exactly = 0) { helper.setTaskBounds(11, any(), any(), any(), any()) }
        // The healthy pane is still reasserted.
        coVerify(exactly = 1) { helper.raiseFreeformTaskDetailed("pkg.wide", 0, any()) }
    }

    /**
     * The raise may RECREATE the task (a STANDARD-typed task cannot be re-typed in place), which
     * changes its id. Bounds must go to the id read AFTER the raise — the pre-raise id points at a
     * removed task, so the pane would silently keep the wrong geometry.
     *
     * Anti-vacuity: reusing the pre-raise id sends setTaskBounds to 11 and both coVerify calls fail.
     */
    @Test fun `onBackdropResurfaced applies bounds to the task id read after the raise`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        coEvery { helper.raiseFreeformTaskDetailed(any(), any(), any()) } returns RaiseOutcome.RAISED

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope, 60_000)
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))
        clearMocks(helper, answers = false)
        // Pre-raise read: task 11. Post-raise read: the recreated task 37.
        coEvery { helper.getTaskState("pkg.narrow") } returnsMany listOf(
            SplitTaskState(11, 5, narrow.left, narrow.top, narrow.right, narrow.bottom),
            SplitTaskState(37, 5, narrow.left, narrow.top, narrow.right, narrow.bottom),
        )

        mgr.onBackdropResurfaced()

        coVerify(exactly = 1) { helper.setTaskBounds(37, narrow.left, narrow.top, narrow.right, narrow.bottom) }
        coVerify(exactly = 0) { helper.setTaskBounds(11, any(), any(), any(), any()) }
    }

    /**
     * Anti-vacuity for the Active gate: without it the call would query task state with no session.
     */
    @Test fun `onBackdropResurfaced is a no-op when no session is active`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope, 60_000)

        mgr.onBackdropResurfaced()

        assertEquals(SplitSessionState.Idle, mgr.state.value)
        coVerify(exactly = 0) { helper.getTaskState(any()) }
        coVerify(exactly = 0) { helper.raiseFreeformTaskDetailed(any(), any(), any()) }
    }

    @Test fun `onBackdropResurfaced keeps the session alive when the raise fails`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        coEvery { helper.raiseFreeformTaskDetailed(any(), any(), any()) } returns RaiseOutcome.RAISED

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope, 60_000)
        val pair = SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT)
        mgr.start(pair)
        coEvery { helper.raiseFreeformTaskDetailed(any(), any(), any()) } returns RaiseOutcome.REFUSED
        clearMocks(helper, answers = false)

        mgr.events.test {
            mgr.onBackdropResurfaced()
            expectNoEvents()
        }
        assertEquals(SplitSessionState.Active(pair, 11, 10), mgr.state.value)
        // A failed raise must not be escalated into a relaunch.
        coVerify(exactly = 0) { helper.forceStop(any()) }
        coVerify(exactly = 0) { helper.setTaskBounds(any(), any(), any(), any(), any()) }
    }

    // ── W6-F3: native split-screen detection ──────────────────────────────────
    //
    // Our panes are freeform; a system split-screen windowing mode (3 = PRIMARY, 4 = SECONDARY)
    // on either of them means the head unit's own split took over. We stand down instead of
    // fighting the system shell; the pane tasks are left exactly as the shell put them.

    @Test fun `watchdog ends the session when a pane sits in native split mode for two ticks`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        val backdrop = FakeSplitBackdrop()
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), backdrop, backgroundScope, 100)
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        // Native split takes over: wide lands in SPLIT_SCREEN_PRIMARY and stays there.
        coEvery { helper.getTaskState("pkg.wide") } returns SplitTaskState(10, 3, 0, 0, 960, 1080)

        mgr.events.test {
            advanceTimeBy(250)
            runCurrent()
            assertEquals(SplitEvent.SessionEnded(EndReason.NATIVE_SPLIT), awaitItem())
            assertEquals(SplitSessionState.Idle, mgr.state.value)
            assertEquals(false, backdrop.shown)
        }
        // The system shell owns the windows now — we do not flip pane modes back.
        coVerify(exactly = 0) { helper.setTaskWindowingMode(10, 1) }
        coVerify(exactly = 0) { helper.setTaskWindowingMode(11, 1) }
    }

    @Test fun `watchdog ends the session for SPLIT_SCREEN_SECONDARY as well`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        val backdrop = FakeSplitBackdrop()
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), backdrop, backgroundScope, 100)
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        coEvery { helper.getTaskState("pkg.narrow") } returns SplitTaskState(11, 4, 960, 0, 1920, 1080)

        mgr.events.test {
            advanceTimeBy(250)
            runCurrent()
            assertEquals(SplitEvent.SessionEnded(EndReason.NATIVE_SPLIT), awaitItem())
        }
        assertEquals(SplitSessionState.Idle, mgr.state.value)
    }

    /**
     * Anti-flap: a single transitional reading of mode 3 must not end a healthy session.
     * Anti-vacuity: dropping the 2-tick gate ends the session on the first tick and this test
     * fails on the state assertion.
     */
    @Test fun `watchdog survives a one-tick flap into native split mode`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        val backdrop = FakeSplitBackdrop()
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), backdrop, backgroundScope, 100)
        val pair = SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT)
        mgr.start(pair)

        // One tick in mode 3, then back to freeform.
        coEvery { helper.getTaskState("pkg.wide") } returnsMany listOf(
            SplitTaskState(10, 3, 0, 0, 960, 1080),
            SplitTaskState(10, 5, wide.left, wide.top, wide.right, wide.bottom),
        )
        advanceTimeBy(250)
        runCurrent()

        assertEquals(SplitSessionState.Active(pair, 11, 10), mgr.state.value)
        assertTrue("Backdrop must stay up through a single-tick flap", backdrop.shown)
    }

    // ── MAXIMIZED: refocus the user-maximized pane ────────────────────────────

    @Test fun `MAXIMIZED top matches maximized pane - setFocusedTask called after flip`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        val backdrop = FakeSplitBackdrop()
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        // Dual-stub: both queuing and tryLock variants may be called depending on the code path.
        coEvery { helper.getTopTaskPackage() } returns "pkg.narrow"
        coEvery { helper.getTopTaskPackageOrSkip() } returns "pkg.narrow"
        coEvery { helper.setFocusedTask(any()) } returns true

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), backdrop, backgroundScope, 100)
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        // Narrow goes fullscreen (user maximized); ATMS assigned it a new task id (12),
        // distinct from the session's narrowTaskId (11), so the fresh-id requirement is tested.
        coEvery { helper.getTaskState("pkg.narrow") } returns
            SplitTaskState(12, 1, 0, 0, 1920, 1080)
        helper.stubTask("pkg.wide", 10, 5, wide)

        mgr.events.test {
            advanceTimeBy(150)
            runCurrent()
            // Read before flip, flip, then re-front — order is load-bearing.
            coVerifyOrder {
                helper.getTopTaskPackage()          // read top BEFORE the flip
                helper.setTaskWindowingMode(10, 1)  // flip wide pane out of freeform
                helper.setFocusedTask(12)           // re-front narrow using fresh taskId=12
            }
            assertEquals(false, backdrop.shown)
            assertEquals(SplitSessionState.Idle, mgr.state.value)
            val event = awaitItem()
            assertEquals(SplitEvent.SessionEnded(EndReason.MAXIMIZED), event)
        }
    }

    @Test fun `MAXIMIZED top differs from maximized pane - setFocusedTask not called`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        val backdrop = FakeSplitBackdrop()
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        coEvery { helper.getTopTaskPackage() } returns "com.android.launcher3"
        coEvery { helper.getTopTaskPackageOrSkip() } returns "com.android.launcher3"

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), backdrop, backgroundScope, 100)
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        // Narrow goes fullscreen.
        coEvery { helper.getTaskState("pkg.narrow") } returns
            SplitTaskState(11, 1, 0, 0, 1920, 1080)
        helper.stubTask("pkg.wide", 10, 5, wide)

        mgr.events.test {
            advanceTimeBy(150)
            runCurrent()
            coVerify(exactly = 0) { helper.setFocusedTask(any()) }
            assertEquals(false, backdrop.shown)
            assertEquals(SplitSessionState.Idle, mgr.state.value)
            val event = awaitItem()
            assertEquals(SplitEvent.SessionEnded(EndReason.MAXIMIZED), event)
        }
    }

    @Test fun `MAXIMIZED top null - setFocusedTask not called and branch completes`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        val backdrop = FakeSplitBackdrop()
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        coEvery { helper.getTopTaskPackage() } returns null
        coEvery { helper.getTopTaskPackageOrSkip() } returns null

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), backdrop, backgroundScope, 100)
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        // Narrow goes fullscreen.
        coEvery { helper.getTaskState("pkg.narrow") } returns
            SplitTaskState(11, 1, 0, 0, 1920, 1080)
        helper.stubTask("pkg.wide", 10, 5, wide)

        mgr.events.test {
            advanceTimeBy(150)
            runCurrent()
            coVerify(exactly = 0) { helper.setFocusedTask(any()) }
            assertEquals(false, backdrop.shown)
            assertEquals(SplitSessionState.Idle, mgr.state.value)
            val event = awaitItem()
            assertEquals(SplitEvent.SessionEnded(EndReason.MAXIMIZED), event)
        }
    }

    @Test fun `MAXIMIZED first setFocusedTask returns false - retried exactly once`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        val backdrop = FakeSplitBackdrop()
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        coEvery { helper.getTopTaskPackage() } returns "pkg.narrow"
        coEvery { helper.getTopTaskPackageOrSkip() } returns "pkg.narrow"
        // First call returns false; second (retry) returns true.
        coEvery { helper.setFocusedTask(11) } returnsMany listOf(false, true)

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), backdrop, backgroundScope, 100)
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        // Narrow goes fullscreen.
        coEvery { helper.getTaskState("pkg.narrow") } returns
            SplitTaskState(11, 1, 0, 0, 1920, 1080)
        helper.stubTask("pkg.wide", 10, 5, wide)

        mgr.events.test {
            // Advance past tick (100ms) + FOCUS_RETRY_DELAY_MS (300ms).
            advanceTimeBy(500)
            runCurrent()
            // Exactly two calls: original attempt + one retry.
            coVerify(exactly = 2) { helper.setFocusedTask(11) }
            assertEquals(false, backdrop.shown)
            assertEquals(SplitSessionState.Idle, mgr.state.value)
            val event = awaitItem()
            assertEquals(SplitEvent.SessionEnded(EndReason.MAXIMIZED), event)
        }
    }

    @Test fun `MAXIMIZED setFocusedTask throws on both attempts - branch still completes`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        val backdrop = FakeSplitBackdrop()
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        coEvery { helper.getTopTaskPackage() } returns "pkg.narrow"
        coEvery { helper.getTopTaskPackageOrSkip() } returns "pkg.narrow"
        coEvery { helper.setFocusedTask(11) } throws RuntimeException("IPC dead")

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), backdrop, backgroundScope, 100)
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        // Narrow goes fullscreen.
        coEvery { helper.getTaskState("pkg.narrow") } returns
            SplitTaskState(11, 1, 0, 0, 1920, 1080)
        helper.stubTask("pkg.wide", 10, 5, wide)

        mgr.events.test {
            advanceTimeBy(500)
            runCurrent()
            // Despite both throws, teardown steps must complete.
            assertEquals(false, backdrop.shown)
            assertEquals(SplitSessionState.Idle, mgr.state.value)
            val event = awaitItem()
            assertEquals(SplitEvent.SessionEnded(EndReason.MAXIMIZED), event)
        }
    }

    // ── Last-pair persistence ─────────────────────────────────────────────────

    @Test fun `pair is persisted across start and exit`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val prefs = FakeSplitPreferences()
        val (wide, narrow) = boundsFor(SplitSide.LEFT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)

        val pair = SplitPair("pkg.narrow", "pkg.wide", SplitSide.LEFT)
        val mgr = SplitSessionManager(helper, prefs, FakeSplitBackdrop(), backgroundScope, 60_000)

        assertNull(prefs.getLastPair())

        mgr.start(pair)
        assertEquals(pair, prefs.getLastPair())

        mgr.exit()
        // Pair must survive exit so startLastPair can restore it.
        assertEquals(pair, prefs.getLastPair())
    }

    @Test fun `swapApps updates saved pair`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val prefs = FakeSplitPreferences()
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)

        val mgr = SplitSessionManager(helper, prefs, FakeSplitBackdrop(), backgroundScope, 60_000)
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))
        mgr.swapApps()

        val saved = prefs.getLastPair()!!
        assertEquals("pkg.wide", saved.narrowPkg)
        assertEquals("pkg.narrow", saved.widePkg)
        assertEquals(SplitSide.RIGHT, saved.narrowSide)
    }

    // ── Idle guard — no-ops when session not Active ───────────────────────────

    @Test fun `mirror when Idle is a no-op`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope)
        mgr.mirror()
        verify { helper wasNot Called }
    }

    @Test fun `exit when Idle is a no-op`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope)
        mgr.exit()
        verify { helper wasNot Called }
    }

    // ── Edge-trigger: PaneClosed emitted only once per closure ───────────────

    @Test fun `watchdog emits PaneClosed exactly once across multiple ticks with pane gone`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope, 100)

        // Subscribe before start() so no events are missed.
        val collectedEvents = mutableListOf<SplitEvent>()
        val collector = launch { mgr.events.collect { collectedEvents.add(it) } }
        runCurrent() // start the collector coroutine

        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        // Narrow task disappears; wide stays.
        coEvery { helper.getTaskState("pkg.narrow") } returns SplitTaskState(-1, 0, 0, 0, 0, 0)
        helper.stubTask("pkg.wide", 10, 5, wide)

        // Advance through 4 ticks — only the first should fire PaneClosed.
        repeat(4) {
            advanceTimeBy(110)
            runCurrent()
        }
        collector.cancel()

        val paneClosed = collectedEvents.filterIsInstance<SplitEvent.PaneClosed>()
        assertEquals("PaneClosed must fire exactly once", 1, paneClosed.size)
        assertEquals(Pane.NARROW, paneClosed.first().pane)
    }

    // ── Mutex: exit vs MAXIMIZED tick cannot double-emit SessionEnded ─────────

    @Test fun `exit after MAXIMIZED tick emits SessionEnded exactly once`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope, 100)

        val collectedEvents = mutableListOf<SplitEvent>()
        val collector = launch { mgr.events.collect { collectedEvents.add(it) } }
        runCurrent()

        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        // Watchdog detects narrow maximised.
        coEvery { helper.getTaskState("pkg.narrow") } returns
            SplitTaskState(11, 1, 0, 0, 1920, 1080)
        helper.stubTask("pkg.wide", 10, 5, wide)

        advanceTimeBy(150); runCurrent()
        // Session is already Idle — exit() must be a no-op and not emit another SessionEnded.
        mgr.exit()
        runCurrent()
        collector.cancel()

        val ended = collectedEvents.filterIsInstance<SplitEvent.SessionEnded>()
        assertEquals("SessionEnded must fire exactly once", 1, ended.size)
        assertEquals(EndReason.MAXIMIZED, ended.first().reason)
    }

    // ── start() while Active dismisses previous tasks ─────────────────────────

    @Test fun `start while already Active exits old session and launches new one`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow", "pkg.new.narrow", "pkg.new.wide")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        helper.stubTask("pkg.new.wide", 20, 5, wide)
        helper.stubTask("pkg.new.narrow", 21, 5, narrow)

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope, 60_000)

        val collectedEvents = mutableListOf<SplitEvent>()
        val collector = launch { mgr.events.collect { collectedEvents.add(it) } }
        runCurrent()

        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))
        // Start a completely different pair while first session is active.
        mgr.start(SplitPair("pkg.new.narrow", "pkg.new.wide", SplitSide.RIGHT))
        runCurrent()
        collector.cancel()

        // Old narrow (11) and wide (10) tasks must have been dismissed.
        coVerify { helper.setTaskWindowingMode(11, 1) }
        coVerify { helper.setTaskWindowingMode(10, 1) }

        // An EXIT event must have been emitted for the old session.
        val exits = collectedEvents.filterIsInstance<SplitEvent.SessionEnded>()
            .filter { it.reason == EndReason.EXIT }
        assertEquals("One EXIT event for the old session", 1, exits.size)

        // New session must be Active with the new pair.
        val newState = mgr.state.value as? SplitSessionState.Active
        assertEquals("pkg.new.narrow", newState?.pair?.narrowPkg)
        assertEquals("pkg.new.wide", newState?.pair?.widePkg)
    }

    // ── onFreeformLive callback (reboot hint clear) ───────────────────────────

    @Test fun `onFreeformLive is called on successful start`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", taskId = 10, mode = 5, b = wide)
        helper.stubTask("pkg.narrow", taskId = 11, mode = 5, b = narrow)

        var callCount = 0
        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope,
            tickDelayMs = 60_000,
            onFreeformLive = { callCount++ },
        )

        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        assertEquals("onFreeformLive must fire once on successful start", 1, callCount)
    }

    @Test fun `onFreeformLive is NOT called when freeform is unavailable`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        helper.stubLaunch("pkg.wide", result = FreeformLaunchResult.UNAVAILABLE)

        var callCount = 0
        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope,
            onFreeformLive = { callCount++ },
        )

        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        assertEquals("onFreeformLive must NOT fire on FREEFORM_UNAVAILABLE", 0, callCount)
    }

    // ── Fix 1: feature gate ────────────────────────────────────────────────────

    @Test fun `start returns DISABLED when feature is off`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val prefs = FakeSplitPreferences(featureEnabled = false)
        val mgr = SplitSessionManager(helper, prefs, FakeSplitBackdrop(), backgroundScope)

        val result = mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        assertEquals(SplitStartResult.DISABLED, result)
        assertEquals(SplitSessionState.Idle, mgr.state.value)
        // No launch attempts when the gate fires.
        coVerify(exactly = 0) { helper.launchFreeform(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `startLastPair returns DISABLED when feature is off`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val prefs = FakeSplitPreferences(featureEnabled = false)
        prefs.saveLastPair(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))
        val mgr = SplitSessionManager(helper, prefs, FakeSplitBackdrop(), backgroundScope)

        val result = mgr.startLastPair()

        assertEquals(SplitStartResult.DISABLED, result)
        coVerify(exactly = 0) { helper.launchFreeform(any(), any(), any(), any(), any(), any(), any()) }
    }

    // ── Fix 4: partial-failure cleanup ────────────────────────────────────────

    @Test fun `startLocked narrow launch FAILED reverts wide task to fullscreen`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, _) = boundsFor(SplitSide.RIGHT)
        // Wide launches OK; narrow fails.
        helper.stubLaunch("pkg.wide")
        coEvery { helper.launchFreeform("pkg.narrow", any(), any(), any(), any(), any(), any()) } returns FreeformLaunchResult.FAILED
        // Wide task is resolvable for the revert call.
        helper.stubTask("pkg.wide", taskId = 10, mode = 5, b = wide)

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope)
        val result = mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        assertEquals(SplitStartResult.LAUNCH_FAILED, result)
        // Wide task (id=10) must have been reverted to fullscreen (mode=1).
        coVerify { helper.setTaskWindowingMode(10, 1) }
        assertEquals(SplitSessionState.Idle, mgr.state.value)
    }

    // ── Bug A: gentle mode-flip before freeform re-launch (Home+relaunch regression) ──
    //
    // On-car: after pressing Home during a split session, Yandex Music task stayed in
    // mode=fullscreen/invisible. Two-step fix preserves the app process (and media session)
    // when possible: (1) try setTaskWindowingMode(taskId, 5) first; (2) fall back to
    // forceStop only when the flip did not land (so playback survives on fast paths).

    @Test fun `start tries gentle mode-flip first then falls back to forceStop when flip fails`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)

        // Both tasks exist in fullscreen (mode=1). Flip attempts don't land (re-query still mode=1).
        coEvery { helper.getTaskState("pkg.wide") } returnsMany listOf(
            SplitTaskState(taskId = 10, windowingMode = 1, 0, 0, 1920, 1080),   // initial check
            SplitTaskState(taskId = 10, windowingMode = 1, 0, 0, 1920, 1080),   // re-query after flip: still fullscreen
            SplitTaskState(taskId = 10, windowingMode = 5, wide.left, wide.top, wide.right, wide.bottom), // post-launch
        )
        coEvery { helper.getTaskState("pkg.narrow") } returnsMany listOf(
            SplitTaskState(taskId = 11, windowingMode = 1, 0, 0, 1920, 1080),
            SplitTaskState(taskId = 11, windowingMode = 1, 0, 0, 1920, 1080),
            SplitTaskState(taskId = 11, windowingMode = 5, narrow.left, narrow.top, narrow.right, narrow.bottom),
        )
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        coEvery { helper.forceStop(any()) } returns true

        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope, 60_000,
            paneTypePolicy = standardPanes,
        )
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        // Step 1: gentle mode flip attempted for both tasks — as STANDARD, so the flipped task
        // gets its own root task (own input shield) exactly like a freshly launched pane.
        coVerify { helper.setTaskWindowingMode(10, 5, HelperBinderProtocol.PANE_TYPE_STANDARD) }
        coVerify { helper.setTaskWindowingMode(11, 5, HelperBinderProtocol.PANE_TYPE_STANDARD) }
        // Step 2: flip failed → forceStop called as fallback.
        coVerify { helper.forceStop("pkg.wide") }
        coVerify { helper.forceStop("pkg.narrow") }
        // Order per pane: mode-flip → forceStop → launch.
        coVerifyOrder {
            helper.setTaskWindowingMode(10, 5, HelperBinderProtocol.PANE_TYPE_STANDARD)
            helper.forceStop("pkg.wide")
            helper.launchFreeform(
                "pkg.wide", any(), any(), any(), any(), any(), HelperBinderProtocol.PANE_TYPE_STANDARD,
            )
            helper.setTaskWindowingMode(11, 5, HelperBinderProtocol.PANE_TYPE_STANDARD)
            helper.forceStop("pkg.narrow")
            helper.launchFreeform(
                "pkg.narrow", any(), any(), any(), any(), any(), HelperBinderProtocol.PANE_TYPE_STANDARD,
            )
        }
    }

    @Test fun `start skips forceStop when gentle mode-flip succeeds (app process preserved)`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)

        // Flip lands: re-query shows mode=5 after setTaskWindowingMode(taskId, 5).
        coEvery { helper.getTaskState("pkg.wide") } returnsMany listOf(
            SplitTaskState(taskId = 10, windowingMode = 1, 0, 0, 1920, 1080),   // initial: fullscreen
            SplitTaskState(taskId = 10, windowingMode = 5, wide.left, wide.top, wide.right, wide.bottom), // re-query: freeform
            SplitTaskState(taskId = 10, windowingMode = 5, wide.left, wide.top, wide.right, wide.bottom), // post-launch
        )
        coEvery { helper.getTaskState("pkg.narrow") } returnsMany listOf(
            SplitTaskState(taskId = 11, windowingMode = 1, 0, 0, 1920, 1080),
            SplitTaskState(taskId = 11, windowingMode = 5, narrow.left, narrow.top, narrow.right, narrow.bottom),
            SplitTaskState(taskId = 11, windowingMode = 5, narrow.left, narrow.top, narrow.right, narrow.bottom),
        )
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        // Task-N daemon: the flipped task is then raised into the pane (W6-F1 restart path).
        coEvery { helper.raiseFreeformTaskDetailed(any(), any(), any()) } returns RaiseOutcome.RAISED

        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope, 60_000,
            paneTypePolicy = standardPanes,
        )
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        // Flip attempted.
        coVerify { helper.setTaskWindowingMode(10, 5, HelperBinderProtocol.PANE_TYPE_STANDARD) }
        coVerify { helper.setTaskWindowingMode(11, 5, HelperBinderProtocol.PANE_TYPE_STANDARD) }
        // Flip succeeded → forceStop must NOT be called (app process survives, media keeps playing).
        coVerify(exactly = 0) { helper.forceStop(any()) }
        // After the flip the task exists in freeform on display 0, so W6-F1 places it by raising it
        // (bounds stamped explicitly) instead of going through launchFreeform. The raise carries
        // STANDARD so the pane keeps its own root task.
        coVerify { helper.raiseFreeformTaskDetailed("pkg.wide", 0, HelperBinderProtocol.PANE_TYPE_STANDARD) }
        coVerify { helper.raiseFreeformTaskDetailed("pkg.narrow", 0, HelperBinderProtocol.PANE_TYPE_STANDARD) }
        coVerify { helper.setTaskBounds(10, wide.left, wide.top, wide.right, wide.bottom) }
        coVerify { helper.setTaskBounds(11, narrow.left, narrow.top, narrow.right, narrow.bottom) }
        // Session is Active after a successful start.
        assertEquals(true, mgr.state.value is SplitSessionState.Active)
    }

    @Test fun `start does NOT call forceStop or mode-flip when task does not exist (taskId -1)`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)

        // No existing tasks; after launch, fresh freeform tasks are created.
        // Two absent reads per pane: forceStopIfNeeded, then placePaneLocked's classification read.
        coEvery { helper.getTaskState("pkg.wide") } returnsMany listOf(
            SplitTaskState(taskId = -1, windowingMode = 0, 0, 0, 0, 0),
            SplitTaskState(taskId = -1, windowingMode = 0, 0, 0, 0, 0),
            SplitTaskState(taskId = 10, windowingMode = 5, wide.left, wide.top, wide.right, wide.bottom),
        )
        coEvery { helper.getTaskState("pkg.narrow") } returnsMany listOf(
            SplitTaskState(taskId = -1, windowingMode = 0, 0, 0, 0, 0),
            SplitTaskState(taskId = -1, windowingMode = 0, 0, 0, 0, 0),
            SplitTaskState(taskId = 11, windowingMode = 5, narrow.left, narrow.top, narrow.right, narrow.bottom),
        )
        helper.stubLaunch("pkg.wide", "pkg.narrow")

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope, 60_000)
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        // Fast path: no task → neither forceStop nor setTaskWindowingMode→5 called.
        coVerify(exactly = 0) { helper.forceStop(any()) }
        // setTaskWindowingMode→5 is not called in the fast path (only in tearDown for →1).
        coVerify(exactly = 0) { helper.setTaskWindowingMode(any(), 5, any()) }
    }

    @Test fun `start does NOT call forceStop when task is already in freeform mode`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)

        // Both packages already have freeform tasks: fast path, no intervention.
        helper.stubTask("pkg.wide", taskId = 10, mode = 5, b = wide)
        helper.stubTask("pkg.narrow", taskId = 11, mode = 5, b = narrow)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        // Task-N daemon: the existing panes are raised, which must not force-stop them either.
        coEvery { helper.raiseFreeformTaskDetailed(any(), any(), any()) } returns RaiseOutcome.RAISED

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope, 60_000)
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        coVerify(exactly = 0) { helper.forceStop(any()) }
        coVerify(exactly = 0) { helper.setTaskWindowingMode(any(), 5, any()) }
    }

    // ── Bug A (same defect class): changeApp must also call forceStopIfNeeded ────────

    // ── Bug B: backdrop await-show flow ───────────────────────────────────────

    @Test fun `startLocked proceeds and becomes Active when backdrop show returns true`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", taskId = 10, mode = 5, b = wide)
        helper.stubTask("pkg.narrow", taskId = 11, mode = 5, b = narrow)

        val backdrop = FakeSplitBackdrop(showResult = true)
        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), backdrop, backgroundScope, 60_000,
            paneTypePolicy = standardPanes,
        )

        val result = mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        assertEquals(SplitStartResult.OK, result)
        assertEquals(true, backdrop.shown)
        assertEquals(true, mgr.state.value is SplitSessionState.Active)
        // Both windows launched after backdrop confirmed resumed, as STANDARD activities: a
        // recents-typed pane nests as a leaf under a shared root and inherits that root's
        // fullscreen input shield, which swallows touches on the lower pane (on-car 391).
        coVerify {
            helper.launchFreeform(
                "pkg.wide", any(), any(), any(), any(), any(), HelperBinderProtocol.PANE_TYPE_STANDARD,
            )
        }
        coVerify {
            helper.launchFreeform(
                "pkg.narrow", any(), any(), any(), any(), any(), HelperBinderProtocol.PANE_TYPE_STANDARD,
            )
        }
    }

    // ── #130: pane type is gated by firmware ─────────────────────────────────────

    @Test fun `startLocked launches panes as RECENTS on a firmware outside the known-good list`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", taskId = 10, mode = 5, b = wide)
        helper.stubTask("pkg.narrow", taskId = 11, mode = 5, b = narrow)

        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope, 60_000,
            paneTypePolicy = recentsPanes,
        )

        assertEquals(SplitStartResult.OK, mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT)))

        // On eng.build.20260320 (Song L, Sea Lion 07) a STANDARD pane finishes on the first tap,
        // so the panes are launched the v3.9 way instead.
        coVerify {
            helper.launchFreeform(
                "pkg.wide", any(), any(), any(), any(), any(), HelperBinderProtocol.PANE_TYPE_RECENTS,
            )
        }
        coVerify {
            helper.launchFreeform(
                "pkg.narrow", any(), any(), any(), any(), any(), HelperBinderProtocol.PANE_TYPE_RECENTS,
            )
        }
        coVerify(exactly = 0) {
            helper.launchFreeform(any(), any(), any(), any(), any(), any(), HelperBinderProtocol.PANE_TYPE_STANDARD)
        }
    }

    @Test fun `start raises and mode-flips as RECENTS on a firmware outside the known-good list`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        // Wide task is stale fullscreen (mode flip path), narrow survives in freeform (raise path).
        coEvery { helper.getTaskState("pkg.wide") } returnsMany listOf(
            SplitTaskState(taskId = 10, windowingMode = 1, 0, 0, 1920, 1080),
            SplitTaskState(taskId = 10, windowingMode = 5, wide.left, wide.top, wide.right, wide.bottom),
            SplitTaskState(taskId = 10, windowingMode = 5, wide.left, wide.top, wide.right, wide.bottom),
        )
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        coEvery { helper.raiseFreeformTaskDetailed(any(), any(), any()) } returns RaiseOutcome.RAISED

        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope, 60_000,
            paneTypePolicy = recentsPanes,
        )
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        // The daemon retypes a live STANDARD task through the same remove+recreate branch, so a
        // pane left over from a v3.9.1 session migrates to RECENTS on the first start after the update.
        coVerify { helper.setTaskWindowingMode(10, 5, HelperBinderProtocol.PANE_TYPE_RECENTS) }
        coVerify { helper.raiseFreeformTaskDetailed("pkg.wide", 0, HelperBinderProtocol.PANE_TYPE_RECENTS) }
        coVerify { helper.raiseFreeformTaskDetailed("pkg.narrow", 0, HelperBinderProtocol.PANE_TYPE_RECENTS) }
        coVerify(exactly = 0) { helper.raiseFreeformTaskDetailed(any(), any(), HelperBinderProtocol.PANE_TYPE_STANDARD) }
    }

    @Test fun `startLocked still launches windows when backdrop show returns false (degraded)`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", taskId = 10, mode = 5, b = wide)
        helper.stubTask("pkg.narrow", taskId = 11, mode = 5, b = narrow)

        // Simulate backdrop resume timeout: show() returns false.
        val backdrop = FakeSplitBackdrop(showResult = false)
        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), backdrop, backgroundScope, 60_000)

        val result = mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        // On timeout: degraded but functional — session must still become Active.
        assertEquals(SplitStartResult.OK, result)
        assertEquals(true, mgr.state.value is SplitSessionState.Active)
        // Windows launched even though the backdrop signal was not received.
        coVerify { helper.launchFreeform("pkg.wide", any(), any(), any(), any(), any(), any()) }
        coVerify { helper.launchFreeform("pkg.narrow", any(), any(), any(), any(), any(), any()) }
    }

    // ── Fix D: start must survive caller-scope cancellation (widget bare-backdrop bug) ──

    @Test fun `start survives caller scope cancellation mid backdrop show`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", taskId = 10, mode = 5, b = wide)
        helper.stubTask("pkg.narrow", taskId = 11, mode = 5, b = narrow)

        val backdrop = SuspendingFakeSplitBackdrop()
        val pair = SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT)
        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), backdrop, backgroundScope, 60_000)

        // Simulate the widget's dataScope launching start.
        val callerJob = launch { mgr.start(pair) }
        runCurrent() // Advance until backdrop.show() is suspended inside showGate.await().

        // Cancel the caller — mirrors dataScope.cancel() in WidgetController when backdrop
        // brings the home screen forward and the widget host stops listening.
        callerJob.cancel()
        runCurrent()

        // Backdrop onResume arrives: release the gate.
        backdrop.release(true)
        runCurrent()

        // The session must have reached Active despite the caller being cancelled —
        // work continued in the manager's own scope.
        assertEquals(SplitSessionState.Active(pair, 11, 10), mgr.state.value)
        coVerify { helper.launchFreeform("pkg.wide", any(), any(), any(), any(), any(), any()) }
        coVerify { helper.launchFreeform("pkg.narrow", any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `backdrop hidden via try-finally when start fails after backdrop shown`() = runTest {
        // Tests the try/finally belt in startLocked: backdrop.show() returns true, then the
        // wide launch fails — finally runs, checks state is not Active, and hides backdrop.
        val helper = mockk<HelperClient>(relaxed = true)
        val backdrop = FakeSplitBackdrop(showResult = true)

        // Wide launch fails → LAUNCH_FAILED return inside the try block → finally hides backdrop.
        helper.stubLaunch("pkg.wide", result = FreeformLaunchResult.FAILED)

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), backdrop, backgroundScope)
        val result = mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        assertEquals(SplitStartResult.LAUNCH_FAILED, result)
        // The try/finally must have hidden the backdrop (state never reached Active).
        assertEquals(false, backdrop.shown)
        assertEquals(SplitSessionState.Idle, mgr.state.value)
    }

    @Test fun `backdrop hidden when manager scope job cancelled while backdrop show is suspended`() = runTest {
        // Regression for IMPORTANT 1: the try/finally must START before backdrop.show() so
        // that cancellation landing inside show()'s await (the field bug vector) is covered.
        // On pre-fix code (try after show()), backdrop stays shown — hideCalls == 0.
        val helper = mockk<HelperClient>(relaxed = true)
        coEvery { helper.getTaskState(any()) } returns SplitTaskState(-1, 0, 0, 0, 0, 0)
        val backdrop = SuspendingFakeSplitBackdrop()

        // Isolated manager scope: same test dispatcher, independent Job so we can cancel it.
        val managerJob = SupervisorJob()
        val managerScope = CoroutineScope(coroutineContext + managerJob)
        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), backdrop, managerScope, 60_000)

        launch {
            try { mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT)) }
            catch (e: CancellationException) { /* expected: managerJob cancelled, propagates through await() */ }
        }
        runCurrent() // advance until show() is suspended inside showGate.await()

        // Cancel the manager scope — the startLocked coroutine is cancelled while in show().
        managerJob.cancel()
        runCurrent()

        // With try BEFORE show(): finally fires → backdrop.hide() → shown = false.
        // Without the fix (try after show()): finally never fires → shown = true.
        assertEquals(false, backdrop.shown)
    }

    // ── Fix D round-2: time-window double-tap guard ───────────────────────────

    @Test fun `double-tap within time window suppresses second start`() = runTest {
        // Verifies Fix A: lastStartMs recorded at COMPLETION (not initiation).
        // Clock advances by 2 s DURING backdrop.show() — with initiation-timestamp recording the
        // guard would see delta > 1500 ms and let the second tap through.
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", taskId = 10, mode = 5, b = wide)
        helper.stubTask("pkg.narrow", taskId = 11, mode = 5, b = narrow)

        var fakeNow = 1000L
        val pair = SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT)
        val slowBackdrop = SuspendingFakeSplitBackdrop()
        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), slowBackdrop, backgroundScope,
            tickDelayMs = 60_000, nowMs = { fakeNow },
        )

        // Launch first start without awaiting — it suspends inside backdrop.show().
        val firstStart = backgroundScope.async { mgr.start(pair) }
        runCurrent() // Let it reach showGate.await() and suspend.

        // Advance clock by 2 s to simulate a long startLocked (> DOUBLE_TAP_WINDOW_MS).
        // With initiation-timestamp recording, the guard would consider the window expired.
        fakeNow += 2_000L

        // Release the backdrop — first start completes, records lastStartMs = nowMs() = 3000.
        slowBackdrop.release(true)
        assertEquals(SplitStartResult.OK, firstStart.await())

        // Second start immediately (fakeNow still 3000; delta = 0 < 1500 ms).
        // Must be suppressed regardless of how long startLocked took.
        val result2 = mgr.start(pair)
        assertEquals(SplitStartResult.OK, result2)

        // Guard must have suppressed the second launch — each app launched exactly once.
        coVerify(exactly = 1) { helper.launchFreeform("pkg.wide", any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { helper.launchFreeform("pkg.narrow", any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `same-pair start after time window expires triggers full relaunch`() = runTest {
        // Mirrors the dead-pane scenario: watchdog keeps state Active when tasks die, but
        // the user can still relaunch the pair once the 1.5 s window has passed.
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", taskId = 10, mode = 5, b = wide)
        helper.stubTask("pkg.narrow", taskId = 11, mode = 5, b = narrow)

        var fakeNow = 1000L
        val pair = SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT)
        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope,
            tickDelayMs = 60_000, nowMs = { fakeNow },
        )

        // First start succeeds.
        assertEquals(SplitStartResult.OK, mgr.start(pair))

        // Advance past the window (2 s > 1.5 s).
        fakeNow += 2_000L

        // Second start: guard expired → teardown existing + full relaunch.
        assertEquals(SplitStartResult.OK, mgr.start(pair))

        // Each app must have been launched twice (once per start cycle).
        coVerify(exactly = 2) { helper.launchFreeform("pkg.wide", any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 2) { helper.launchFreeform("pkg.narrow", any(), any(), any(), any(), any(), any()) }
        assertEquals(true, mgr.state.value is SplitSessionState.Active)
    }

    @Test fun `backward clock jump does not suppress start`() = runTest {
        // Verifies Fix B: guard uses `in 0 until window` not `< window`.
        // A backward NTP/GPS jump makes now - lastStartMs negative; the old `<` check would
        // treat that as within-window and permanently suppress the button until time caught up.
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", taskId = 10, mode = 5, b = wide)
        helper.stubTask("pkg.narrow", taskId = 11, mode = 5, b = narrow)

        var fakeNow = 5000L
        val pair = SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT)
        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope,
            tickDelayMs = 60_000, nowMs = { fakeNow },
        )

        // First start at t=5000; records lastStartMs = 5000.
        assertEquals(SplitStartResult.OK, mgr.start(pair))

        // Simulate NTP/GPS backward jump: clock moves back to t=1000.
        // delta = 1000 - 5000 = -4000, which is NOT in 0 until 1500 → guard must not suppress.
        fakeNow = 1000L

        assertEquals(SplitStartResult.OK, mgr.start(pair))

        // Full relaunch must have happened.
        coVerify(exactly = 2) { helper.launchFreeform("pkg.wide", any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 2) { helper.launchFreeform("pkg.narrow", any(), any(), any(), any(), any(), any()) }
    }

    // ── changeApp: same-task-id guard (pane-closed restore path) ─────────────
    //
    // When restoring the just-closed app via changeApp, launchFreeform returns the app's
    // EXISTING task id (the app was alive but dismissed from freeform). oldTaskId == newTaskId.
    // Before the fix, dismissReplacedTask would flip that task to FULLSCREEN → the watchdog
    // classifies it as MAXIMIZED on the next tick → SessionEnded (split collapses).
    // Fix: skip dismissReplacedTask when oldTaskId == newTaskId.

    @Test fun `changeApp with same pkg as closed pane does not dismiss the task`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)

        // Start an active session: narrow = pkg.narrow (taskId=11), wide = pkg.wide (taskId=10).
        helper.stubTask("pkg.wide", taskId = 10, mode = 5, b = wide)
        helper.stubTask("pkg.narrow", taskId = 11, mode = 5, b = narrow)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope, tickDelayMs = 60_000
        )
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))
        assertEquals(true, mgr.state.value is SplitSessionState.Active)

        // Simulate restore: changeApp for NARROW pane with the same package (pkg.narrow).
        // forceStopIfNeeded: getTaskState returns mode=5 (already freeform) → fast-path, no flip.
        // launchFreeform: OK. getTaskState returns same taskId=11.
        // oldTaskId (11) == newTaskId (11) → dismissReplacedTask must NOT be called.
        helper.stubLaunch("pkg.narrow")
        coEvery { helper.getTaskState("pkg.narrow") } returns
            SplitTaskState(taskId = 11, windowingMode = 5, narrow.left, narrow.top, narrow.right, narrow.bottom)

        val result = mgr.changeApp(Pane.NARROW, "pkg.narrow")

        assertEquals(SplitStartResult.OK, result)
        // Session must still be Active (not collapsed to Idle).
        assertEquals(true, mgr.state.value is SplitSessionState.Active)
        // WINDOWING_FULLSCREEN flip must not have been called for the narrow task.
        coVerify(exactly = 0) { helper.setTaskWindowingMode(11, 1) }
    }

    @Test fun `changeApp tries gentle mode-flip then falls back to forceStop when flip fails`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)

        // Start an active session first.
        helper.stubTask("pkg.wide", taskId = 10, mode = 5, b = wide)
        helper.stubTask("pkg.narrow", taskId = 11, mode = 5, b = narrow)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope, 60_000,
            paneTypePolicy = standardPanes,
        )
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))
        assertEquals(true, mgr.state.value is SplitSessionState.Active)

        // Now pick a replacement for the narrow pane. pkg.new has a stale fullscreen task.
        helper.stubLaunch("pkg.new")
        coEvery { helper.getTaskState("pkg.new") } returnsMany listOf(
            SplitTaskState(taskId = 20, windowingMode = 1, 0, 0, 1920, 1080),  // initial: fullscreen
            SplitTaskState(taskId = 20, windowingMode = 1, 0, 0, 1920, 1080),  // re-query: flip did not land
            SplitTaskState(taskId = 20, windowingMode = 5, narrow.left, narrow.top, narrow.right, narrow.bottom), // post-launch
        )
        coEvery { helper.forceStop("pkg.new") } returns true

        val result = mgr.changeApp(Pane.NARROW, "pkg.new")

        assertEquals(SplitStartResult.OK, result)
        // Step 1: gentle flip attempted.
        coVerify { helper.setTaskWindowingMode(20, 5, HelperBinderProtocol.PANE_TYPE_STANDARD) }
        // Step 2: flip failed → forceStop called.
        coVerify { helper.forceStop("pkg.new") }
        // Order: flip → forceStop → launch. The replacement pane is launched as STANDARD too.
        coVerifyOrder {
            helper.setTaskWindowingMode(20, 5, HelperBinderProtocol.PANE_TYPE_STANDARD)
            helper.forceStop("pkg.new")
            helper.launchFreeform(
                "pkg.new", any(), any(), any(), any(), any(), HelperBinderProtocol.PANE_TYPE_STANDARD,
            )
        }
        // Session updated to use pkg.new in the narrow pane.
        val active = mgr.state.value as? SplitSessionState.Active
        assertEquals("pkg.new", active?.pair?.narrowPkg)
    }

    // ── Task G: changeApp Z-order fix — old task must not surface above backdrop ──────────────

    @Test fun `changeApp re-asserts Z-order — old task fullscreen then backdrop front then panes focused`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val backdrop = mockk<SplitBackdrop>()
        coEvery { backdrop.show() } returns true
        every { backdrop.hide() } just runs
        // Stub setFocusedTask to succeed so the I1 fallback (backdrop.hide on both-fail) does not fire.
        coEvery { helper.setFocusedTask(any()) } returns true

        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow", "pkg.new")
        helper.stubTask("pkg.wide", taskId = 10, mode = 5, b = wide)
        helper.stubTask("pkg.narrow", taskId = 11, mode = 5, b = narrow)
        helper.stubTask("pkg.new", taskId = 20, mode = 5, b = narrow)

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), backdrop, backgroundScope, 60_000)
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        mgr.changeApp(Pane.NARROW, "pkg.new")

        // backdrop.show() invoked twice: once in start, once in dismissReplacedTask.
        coVerify(exactly = 2) { backdrop.show() }

        // The old task flip must happen BEFORE the backdrop re-assert, and the re-assert
        // BEFORE the focus calls — otherwise the old task surfaces above the backdrop.
        coVerifyOrder {
            helper.setTaskWindowingMode(11, 1)   // old narrow task dismissed to fullscreen
            backdrop.show()                       // backdrop re-queued to front
            helper.setFocusedTask(any())          // new pane tasks brought above backdrop
        }

        // Session stays Active with the new package in the narrow pane.
        val active = mgr.state.value as? SplitSessionState.Active
        assertEquals("pkg.new", active?.pair?.narrowPkg)
        // N3: backdrop must NOT be hidden on the happy path (unconditional hide would pass without this).
        verify(exactly = 0) { backdrop.hide() }
    }

    @Test fun `changeApp Z-order re-assert — backdrop timeout does not prevent focus calls`() = runTest {
        // Happy path with backdrop timeout: focus succeeds despite show() timing out.
        // Session stays Active; backdrop.hide() must NOT fire because no focus failure occurred.
        val helper = mockk<HelperClient>(relaxed = true)
        val backdrop = mockk<SplitBackdrop>()
        // start() backdrop returns true; changeApp dismissReplacedTask backdrop times out.
        coEvery { backdrop.show() } returnsMany listOf(true, false)
        every { backdrop.hide() } just runs
        // Focus succeeds: this test covers backdrop-timeout + focus-success only.
        coEvery { helper.setFocusedTask(any()) } returns true

        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow", "pkg.new")
        helper.stubTask("pkg.wide", taskId = 10, mode = 5, b = wide)
        helper.stubTask("pkg.narrow", taskId = 11, mode = 5, b = narrow)
        helper.stubTask("pkg.new", taskId = 20, mode = 5, b = narrow)

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), backdrop, backgroundScope, 60_000)
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        val result = mgr.changeApp(Pane.NARROW, "pkg.new")

        // Degraded but functional: backdrop timed out, session stays Active, focus still applied.
        assertEquals(SplitStartResult.OK, result)
        assertEquals(true, mgr.state.value is SplitSessionState.Active)
        coVerify(atLeast = 1) { helper.setFocusedTask(any()) }
        // Focus succeeded: no both-fail condition, backdrop must NOT be hidden.
        verify(exactly = 0) { backdrop.hide() }
    }

    @Test fun `dismissReplacedTask hides backdrop when both focus fail after retry even if backdrop timed out`() = runTest {
        // W3 scenario: ROM never fires onResume mid-session (backdrop.show() always times out, i.e.
        // backdropOk=false). Focus also fails after retry. The hide fallback must still fire —
        // hiding a never-fronted backdrop is harmless (finishInstance is a no-op on a null instance).
        val helper = mockk<HelperClient>(relaxed = true)
        val backdrop = mockk<SplitBackdrop>()
        // start() confirmed (true); changeApp dismissReplacedTask backdrop times out (false).
        coEvery { backdrop.show() } returnsMany listOf(true, false)
        every { backdrop.hide() } just runs
        // All focus calls fail (initial + retry).
        coEvery { helper.setFocusedTask(any()) } returns false

        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow", "pkg.new")
        helper.stubTask("pkg.wide", taskId = 10, mode = 5, b = wide)
        helper.stubTask("pkg.narrow", taskId = 11, mode = 5, b = narrow)
        helper.stubTask("pkg.new", taskId = 20, mode = 5, b = narrow)

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), backdrop, backgroundScope, 60_000)
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        mgr.changeApp(Pane.NARROW, "pkg.new")

        // backdrop.show() timed out but hide must still fire: fallback is not gated on backdropOk.
        verify { backdrop.hide() }
        assertEquals(true, mgr.state.value is SplitSessionState.Active)
    }

    @Test fun `dismissReplacedTask does not hide backdrop when old-daemon fallback setFocusedTask succeeds`() = runTest {
        // Old-daemon path: raiseFreeformTask returns false (new TX unrecognised) so the fallback
        // to setFocusedTask is taken; setFocusedTask succeeds for both panes. The backdrop must
        // NOT be hidden (effective1=true, effective2=true → no both-fail condition).
        val helper = mockk<HelperClient>(relaxed = true)
        val backdrop = mockk<SplitBackdrop>()
        coEvery { backdrop.show() } returns true
        every { backdrop.hide() } just runs
        // raiseFreeformTask not stubbed → relaxed returns false (old daemon / unrecognised TX).
        coEvery { helper.setFocusedTask(any()) } returns true

        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow", "pkg.new")
        helper.stubTask("pkg.wide", taskId = 10, mode = 5, b = wide)
        helper.stubTask("pkg.narrow", taskId = 11, mode = 5, b = narrow)
        helper.stubTask("pkg.new", taskId = 20, mode = 5, b = narrow)

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), backdrop, backgroundScope, 60_000)
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        val result = mgr.changeApp(Pane.NARROW, "pkg.new")

        assertEquals(SplitStartResult.OK, result)
        assertEquals(true, mgr.state.value is SplitSessionState.Active)
        // Fallback setFocusedTask succeeded → no both-fail condition → backdrop must NOT be hidden.
        verify(exactly = 0) { backdrop.hide() }
    }

    @Test fun `changeApp same-task restore — backdrop not re-asserted when old and new task ids are identical`() = runTest {
        // When the app was still alive after PaneClosed (same task resurfaces), changeApp reuses
        // the existing task: oldTaskId == newTaskId, so dismissReplacedTask is skipped entirely —
        // backdrop.show() must NOT be called a second time (already called during start()).
        val helper = mockk<HelperClient>(relaxed = true)
        val backdrop = mockk<SplitBackdrop>()
        coEvery { backdrop.show() } returns true
        every { backdrop.hide() } returns Unit

        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", taskId = 10, mode = 5, b = wide)
        helper.stubTask("pkg.narrow", taskId = 11, mode = 5, b = narrow)

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), backdrop, backgroundScope, 60_000)
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        // Restore: same package, same task id.
        coEvery { helper.getTaskState("pkg.narrow") } returns
            SplitTaskState(taskId = 11, windowingMode = 5, narrow.left, narrow.top, narrow.right, narrow.bottom)

        mgr.changeApp(Pane.NARROW, "pkg.narrow")

        // show() called exactly once (during start only, not during the restore changeApp).
        coVerify(exactly = 1) { backdrop.show() }
        // No fullscreen flip (same-task guard prevents dismissReplacedTask).
        coVerify(exactly = 0) { helper.setTaskWindowingMode(11, 1) }
        assertEquals(true, mgr.state.value is SplitSessionState.Active)
    }

    // ── CX2: double-tap guard clears on every teardown path ──────────────────

    @Test fun `start after exit within double-tap window still launches (guard cleared by teardown)`() = runTest {
        // CX2: tearDownLocked now clears lastStartedPair/lastStartMs so that an immediate
        // re-start after exit is not silently suppressed by the double-tap guard.
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)

        var fakeNow = 1_000L
        val pair = SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT)
        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope,
            tickDelayMs = 60_000L,
            nowMs = { fakeNow },
        )

        mgr.start(pair)
        // Exit while well inside the 1500 ms double-tap window.
        mgr.exit(); runCurrent()

        // Re-start 100 ms after the first launch — inside the window. Without CX2 the guard
        // would suppress this and return OK with no backdrop or launchFreeform calls.
        fakeNow = 1_100L
        val result = mgr.start(pair)

        assertEquals(SplitStartResult.OK, result)
        assertEquals(true, mgr.state.value is SplitSessionState.Active)
        // launchFreeform must have been called a second time (first start + this start).
        coVerify(exactly = 2) { helper.launchFreeform("pkg.wide", any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `start after MAXIMIZED within double-tap window still launches (guard cleared by watchdog)`() = runTest {
        // CX2: the MAXIMIZED path in handlePaneLocked now clears lastStartedPair/lastStartMs
        // so that an immediate re-start after the user maximises a pane is not suppressed.
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)

        var fakeNow = 1_000L
        val pair = SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT)
        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope,
            tickDelayMs = 100L,    // fast watchdog so the MAXIMIZED tick fires quickly
            nowMs = { fakeNow },
        )

        mgr.start(pair)

        // Narrow task goes fullscreen — watchdog fires the MAXIMIZED teardown path.
        coEvery { helper.getTaskState("pkg.narrow") } returns SplitTaskState(11, 1, 0, 0, 1920, 1080)
        helper.stubTask("pkg.wide", 10, 5, wide)
        advanceTimeBy(150); runCurrent()
        assertEquals(SplitSessionState.Idle, mgr.state.value)

        // Re-start 100 ms after the first launch — inside the 1500 ms window. Without CX2 the
        // guard would suppress this because the MAXIMIZED path did not clear the guard.
        fakeNow = 1_100L
        val result = mgr.start(pair)

        assertEquals(SplitStartResult.OK, result)
        assertEquals(true, mgr.state.value is SplitSessionState.Active)
        coVerify(exactly = 2) { helper.launchFreeform("pkg.wide", any(), any(), any(), any(), any(), any()) }
    }

    // ── Task M: pane departure to the cluster display ──────────────────────────

    /**
     * When a pane's task departs to another display (displayId != 0), the watchdog must:
     *  - never call setTaskBounds (no snap-back fight with the native mechanism)
     *  - call the applyCalibratedBounds hook exactly once across multiple ticks
     *  - emit PaneClosed exactly once
     *  - leave the session Active (no SessionEnded)
     */
    @Test fun `watchdog departed pane - no snap-back, hook called once, PaneClosed once, Active`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        // Dual-stub: both queuing and tryLock variants may be called by media poll.
        coEvery { helper.getTopTaskPackage() } returns null
        coEvery { helper.getTopTaskPackageOrSkip() } returns null

        var calibratedCallCount = 0
        var lastCalledTaskId = -1
        var lastCalledDisplayId = -1
        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope, tickDelayMs = 100,
            applyCalibratedBounds = { t, d -> calibratedCallCount++; lastCalledTaskId = t; lastCalledDisplayId = d; true },
        )
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        // Narrow pane departs to display 2 (cluster); wide stays on main display.
        coEvery { helper.getTaskState("pkg.narrow") } returns
            SplitTaskState(taskId = 11, windowingMode = 5, 0, 0, 1280, 480, displayId = 2)
        helper.stubTask("pkg.wide", 10, 5, wide)

        mgr.events.test {
            // 3 ticks
            repeat(3) { advanceTimeBy(110); runCurrent() }

            // PaneClosed emitted exactly once.
            val event = awaitItem()
            assertEquals(SplitEvent.PaneClosed(Pane.NARROW), event)

            // Session still Active.
            assertEquals(true, mgr.state.value is SplitSessionState.Active)
        }

        // Calibrated bounds hook called exactly once across the 3 ticks.
        assertEquals(1, calibratedCallCount)
        assertEquals(11, lastCalledTaskId)
        assertEquals(2, lastCalledDisplayId)

        // No snap-back: setTaskBounds must NOT have been called for the departed task.
        coVerify(exactly = 0) { helper.setTaskBounds(11, any(), any(), any(), any()) }
    }

    @Test fun `watchdog departed pane with FULLSCREEN mode is treated as departed NOT maximized`() = runTest {
        // The native "show on cluster" mechanism may flip the task to FULLSCREEN on the cluster.
        // The departed branch (displayId != 0) must fire BEFORE the MAXIMIZED branch.
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        coEvery { helper.getTopTaskPackage() } returns null
        coEvery { helper.getTopTaskPackageOrSkip() } returns null

        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope, tickDelayMs = 100,
        )
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        // Narrow departs with FULLSCREEN windowing mode (native mechanism may set this on cluster).
        coEvery { helper.getTaskState("pkg.narrow") } returns
            SplitTaskState(taskId = 11, windowingMode = 1, 0, 0, 1280, 480, displayId = 2)
        helper.stubTask("pkg.wide", 10, 5, wide)

        mgr.events.test {
            advanceTimeBy(150); runCurrent()

            // Must emit PaneClosed (departed path), NOT SessionEnded (MAXIMIZED path).
            val event = awaitItem()
            assertEquals(SplitEvent.PaneClosed(Pane.NARROW), event)
            // Session stays Active.
            assertEquals(true, mgr.state.value is SplitSessionState.Active)
        }

        // The wide task must NOT have been put to FULLSCREEN (that's the MAXIMIZED teardown path).
        coVerify(exactly = 0) { helper.setTaskWindowingMode(10, 1) }
    }

    @Test fun `watchdog displayId=0 task is not treated as departed - snap-back still happens`() = runTest {
        // Verifies that displayId == 0 falls through to the existing snap-back path.
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        coEvery { helper.getTopTaskPackage() } returns null
        coEvery { helper.getTopTaskPackageOrSkip() } returns null

        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope, tickDelayMs = 100,
        )
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        // Narrow task on display 0 (main) with drifted bounds — old daemon or main-display task.
        coEvery { helper.getTaskState("pkg.narrow") } returns
            SplitTaskState(taskId = 11, windowingMode = 5, 1300, 100, 1920, 980, displayId = 0)
        helper.stubTask("pkg.wide", 10, 5, wide)

        advanceTimeBy(150); runCurrent()

        // Snap-back must still happen on display 0.
        coVerify { helper.setTaskBounds(11, narrow.left, narrow.top, narrow.right, narrow.bottom) }
        assertEquals(true, mgr.state.value is SplitSessionState.Active)
    }

    @Test fun `changeApp with replaced task on displayId=2 skips dismissReplacedTask`() = runTest {
        // When the replaced task is on the cluster (displayId=2), dismissReplacedTask would yank
        // it back to the main display. The gate must prevent that.
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow", "pkg.new")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        coEvery { helper.setFocusedTask(any()) } returns true

        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope, tickDelayMs = 60_000,
        )
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        // Narrow departed to display 2; the new task (pkg.new) is on display 0.
        coEvery { helper.getTaskState("pkg.narrow") } returns
            SplitTaskState(taskId = 11, windowingMode = 5, 0, 0, 1280, 480, displayId = 2)
        helper.stubTask("pkg.new", taskId = 20, mode = 5, b = narrow)

        val result = mgr.changeApp(Pane.NARROW, "pkg.new")

        assertEquals(SplitStartResult.OK, result)
        // Departed task (id=11) must NOT be set to FULLSCREEN (which would yank it off the cluster).
        coVerify(exactly = 0) { helper.setTaskWindowingMode(11, 1) }
        // Session updated to use pkg.new in the narrow pane.
        val active = mgr.state.value as? SplitSessionState.Active
        assertEquals("pkg.new", active?.pair?.narrowPkg)
    }

    @Test fun `changeApp same-taskId path still skips dismissal (regression guard)`() = runTest {
        // Regression: same-taskId guard (oldTaskId == newTaskId) still skips dismissal
        // regardless of displayId. This covers the "changed my mind" happy path from the brief.
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", taskId = 10, mode = 5, b = wide)
        helper.stubTask("pkg.narrow", taskId = 11, mode = 5, b = narrow)

        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope, tickDelayMs = 60_000,
        )
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        // launchFreeform returns the same task; getTaskState returns same taskId=11.
        helper.stubLaunch("pkg.narrow")
        coEvery { helper.getTaskState("pkg.narrow") } returns
            SplitTaskState(taskId = 11, windowingMode = 5, narrow.left, narrow.top, narrow.right, narrow.bottom)

        val result = mgr.changeApp(Pane.NARROW, "pkg.narrow")

        assertEquals(SplitStartResult.OK, result)
        // Same-taskId: no FULLSCREEN flip (dismissReplacedTask not called).
        coVerify(exactly = 0) { helper.setTaskWindowingMode(11, 1) }
        assertEquals(true, mgr.state.value is SplitSessionState.Active)
    }

    // -----------------------------------------------------------------------------------------
    // M-4 regression: INVALID_DISPLAY (-1) must not trigger the departed branch (displayId > 0).
    // -----------------------------------------------------------------------------------------

    @Test fun `watchdog displayId=-1 (INVALID_DISPLAY) is not treated as departed - snap-back applies`() = runTest {
        // Display.INVALID_DISPLAY = -1 occurs during a mid-reparent transient. It satisfies
        // displayId != 0 but must NOT satisfy displayId > 0, so the departed branch must NOT fire.
        // The task falls to the else (snap-back) branch instead.
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        coEvery { helper.getTopTaskPackage() } returns null
        coEvery { helper.getTopTaskPackageOrSkip() } returns null

        var calibratedCallCount = 0
        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope, tickDelayMs = 100,
            applyCalibratedBounds = { _, _ -> calibratedCallCount++; true },
        )
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        // INVALID_DISPLAY with drifted bounds — mid-reparent transient.
        coEvery { helper.getTaskState("pkg.narrow") } returns
            SplitTaskState(taskId = 11, windowingMode = 5, 1300, 100, 1920, 980, displayId = -1)
        helper.stubTask("pkg.wide", 10, 5, wide)

        mgr.events.test {
            repeat(3) { advanceTimeBy(110); runCurrent() }
            // No PaneClosed: displayId=-1 does NOT satisfy displayId > 0.
            expectNoEvents()
        }

        // calibratedBounds hook must NOT be called for an INVALID_DISPLAY task.
        assertEquals(0, calibratedCallCount)
        // Snap-back must still fire: drifted freeform task on display -1 → else branch.
        coVerify { helper.setTaskBounds(11, narrow.left, narrow.top, narrow.right, narrow.bottom) }
        assertEquals(true, mgr.state.value is SplitSessionState.Active)
    }

    // -----------------------------------------------------------------------------------------
    // M-3 regression: after departure, a subsequent taskId=-1 must NOT emit a second PaneClosed.
    // -----------------------------------------------------------------------------------------

    @Test fun `watchdog departed pane followed by task death does not emit a second PaneClosed`() = runTest {
        // M-3 fix: the departed branch sets both *PaneDepartedEmitted and *PaneClosedEmitted.
        // Without the *PaneClosedEmitted flag, the subsequent taskId=-1 tick would emit a
        // duplicate PaneClosed, re-opening the replacement picker on an already-open pane.
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        coEvery { helper.getTopTaskPackage() } returns null
        coEvery { helper.getTopTaskPackageOrSkip() } returns null

        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope, tickDelayMs = 100,
        )
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        // Phase 1: narrow pane departs to the cluster display.
        coEvery { helper.getTaskState("pkg.narrow") } returns
            SplitTaskState(taskId = 11, windowingMode = 5, 0, 0, 1280, 480, displayId = 2)
        helper.stubTask("pkg.wide", 10, 5, wide)

        mgr.events.test {
            advanceTimeBy(110); runCurrent()

            // First PaneClosed: the departure (expected and correct).
            assertEquals(SplitEvent.PaneClosed(Pane.NARROW), awaitItem())

            // Phase 2: app dies on the cluster (taskId becomes -1). The *PaneClosedEmitted
            // flag set by the departed branch must suppress a second PaneClosed here.
            coEvery { helper.getTaskState("pkg.narrow") } returns
                SplitTaskState(taskId = -1, windowingMode = 5, 0, 0, 0, 0, displayId = 0)

            repeat(2) { advanceTimeBy(110); runCurrent() }

            // ensureAllEventsConsumed() at block exit catches any duplicate PaneClosed.
            expectNoEvents()
        }

        // Session stays Active: the wide pane is healthy, no SessionEnded.
        assertEquals(true, mgr.state.value is SplitSessionState.Active)
    }

    // -----------------------------------------------------------------------------------------
    // I-2: applyCalibratedBounds throwing must not crash the watchdog (D-1-R2 restore).
    // -----------------------------------------------------------------------------------------

    @Test fun `watchdog applyCalibratedBounds throwing must not crash the watchdog (I-2 D-1-R2)`() = runTest {
        // CPM's runCatching only guards setTaskBounds; resolveClusterDisplay, readSizePct,
        // readOffsetPct, geometryFor, and mutex.withLock can still throw. Any non-CE exception
        // propagated from the hook would travel through tickLocked into watchdogLoop's
        // scope.launch which has no try/catch — killing the watchdog silently.
        //
        // The D-1-R2 fix wraps the hook invocation in runCatching inside SSM: a throw is
        // treated identically to false (retry, arm calibrationPendingPkgs).
        //
        // Anti-vacuity: removing the runCatching wrapper lets the exception escape tickLocked
        // and terminate the watchdog coroutine. The test proves the watchdog survives by
        // verifying PaneClosed is emitted on the retry tick (impossible if watchdog is dead).
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        coEvery { helper.getTopTaskPackage() } returns null
        coEvery { helper.getTopTaskPackageOrSkip() } returns null

        var throwCount = 0
        var successCount = 0
        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope, tickDelayMs = 100,
            // Hook throws once, then returns true: simulates an unexpected exception from CPM internals.
            applyCalibratedBounds = { _, _ ->
                if (throwCount == 0) { throwCount++; throw RuntimeException("simulated CPM internal failure") }
                successCount++
                true
            },
        )
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        // Narrow pane departs to the cluster display.
        coEvery { helper.getTaskState("pkg.narrow") } returns
            SplitTaskState(taskId = 11, windowingMode = 5, 0, 0, 1280, 480, displayId = 2)
        helper.stubTask("pkg.wide", 10, 5, wide)

        val events = mutableListOf<SplitEvent>()
        val collectJob = launch { mgr.events.collect { events.add(it) } }

        // Tick 1 (150 ms): hook throws → runCatching catches → treated as false →
        // PaneClosed NOT emitted, session stays Active, watchdog still alive.
        advanceTimeBy(150); runCurrent()
        assertEquals("PaneClosed must NOT be emitted when hook throws (D-1-R2)", 0, events.size)
        assertTrue("session must stay Active after hook throw", mgr.state.value is SplitSessionState.Active)
        assertEquals("hook must have thrown exactly once", 1, throwCount)

        // Tick 2 (300 ms): hook succeeds → watchdog was NOT killed → PaneClosed emitted.
        advanceTimeBy(150); runCurrent()
        assertEquals("PaneClosed must be emitted after retry (proves watchdog survived the throw)", 1, events.size)
        assertEquals(SplitEvent.PaneClosed(Pane.NARROW), events[0])
        assertTrue("session must stay Active after departure", mgr.state.value is SplitSessionState.Active)
        assertEquals("hook must have succeeded once on retry", 1, successCount)

        collectJob.cancel()
    }

    // I-2a: applyCalibratedBounds returning false retries until success (D-1-R1).

    @Test fun `watchdog applyCalibratedBounds returning false - watchdog retries, PaneClosed deferred to next tick (D-1 D-1-R1)`() = runTest {
        // The applyCalibratedBounds hook invokes ClusterProjectionManager which calls the
        // helper daemon. An IPC failure causes helper.setTaskBounds to return false (it does
        // NOT throw); CPM propagates the Boolean to the hook return value (D-1-R1).
        //
        // D-1 behaviour: a false return causes the watchdog to RETRY on the next tick
        // (return false without setting the departed flag). PaneClosed is deferred until
        // calibration succeeds — it is NOT emitted on the first failing tick.
        //
        // Anti-vacuity: removing the `if (!calibrated) return false` guard emits PaneClosed
        // on the first (failing) tick and sets the departed flag, preventing any further retry.
        // The test must assert the FIRST tick emits NOTHING to pin the retry behaviour.
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        coEvery { helper.getTopTaskPackage() } returns null
        coEvery { helper.getTopTaskPackageOrSkip() } returns null

        var calibrateCount = 0
        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope, tickDelayMs = 100,
            // Hook that returns false once, then true: simulates a transient IPC failure.
            applyCalibratedBounds = { _, _ ->
                calibrateCount++
                calibrateCount > 1  // false on first call (failure), true thereafter
            },
        )
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        // Narrow pane departs to the cluster display.
        coEvery { helper.getTaskState("pkg.narrow") } returns
            SplitTaskState(taskId = 11, windowingMode = 5, 0, 0, 1280, 480, displayId = 2)
        helper.stubTask("pkg.wide", 10, 5, wide)

        val events = mutableListOf<SplitEvent>()
        val collectJob = launch { mgr.events.collect { events.add(it) } }

        // Tick 1 (150 ms): hook returns false → PaneClosed NOT emitted, session stays Active.
        advanceTimeBy(150); runCurrent()
        assertEquals("PaneClosed must NOT be emitted on the failing calibration tick (D-1)", 0, events.size)
        assertTrue("session must stay Active while calibration retries", mgr.state.value is SplitSessionState.Active)

        // Tick 2 (300 ms): hook returns true → PaneClosed emitted, watchdog survives.
        advanceTimeBy(150); runCurrent()
        assertEquals("PaneClosed must be emitted after calibration retry succeeds", 1, events.size)
        assertEquals(SplitEvent.PaneClosed(Pane.NARROW), events[0])
        assertTrue("session must stay Active after departure", mgr.state.value is SplitSessionState.Active)
        assertEquals("calibrate must have been called twice total", 2, calibrateCount)

        collectJob.cancel()
    }

    // D-1-R3: calibrationPendingPkgs is cleared in death branch and start() ---

    @Test fun `pane death clears calibrationPendingPkgs - swapApps unblocked after death (D-1-R3)`() = runTest {
        // A failed calibration arms calibrationPendingPkgs, which blocks swapApps/mirror.
        // If the pane task subsequently dies, SSM must clear the marker (death branch) so that
        // swapApps/mirror are not permanently disabled for the remainder of the session.
        //
        // Anti-vacuity: without the D-1-R3 death-branch fix, calibrationPendingPkgs is not
        // cleared on death and swapApps never calls setTaskBounds (blocked gate).
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        coEvery { helper.getTopTaskPackage() } returns null
        coEvery { helper.getTopTaskPackageOrSkip() } returns null

        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope, tickDelayMs = 100,
            // Hook always returns false: calibration never succeeds, pending marker stays set.
            applyCalibratedBounds = { _, _ -> false },
        )
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        // Tick 1: narrow departs to cluster, hook returns false → calibrationPendingPkgs armed.
        coEvery { helper.getTaskState("pkg.narrow") } returns
            SplitTaskState(taskId = 11, windowingMode = 5, 0, 0, 1280, 480, displayId = 2)
        helper.stubTask("pkg.wide", 10, 5, wide)
        advanceTimeBy(150); runCurrent()

        // Clear recorded calls from setup/tick-1 so we can assert cleanly on swapApps calls.
        clearMocks(helper, answers = false)

        // Tick 2: narrow task dies (taskId=-1, no grace). Death branch fires → calibrationPendingPkgs -= pkg.narrow.
        coEvery { helper.getTaskState("pkg.narrow") } returns
            SplitTaskState(taskId = -1, windowingMode = 5, 0, 0, 0, 0, displayId = 0)
        helper.stubTask("pkg.wide", 10, 5, wide)
        advanceTimeBy(150); runCurrent()

        // swapApps must now proceed past the calibrationPendingPkgs gate and call setTaskBounds.
        // (Both narrowPaneDepartedEmitted and departureGraceDeadlines are clear after death.)
        mgr.swapApps()
        coVerify(atLeast = 1) { helper.setTaskBounds(any(), any(), any(), any(), any()) }
    }

    @Test fun `start clears calibrationPendingPkgs - new session begins unblocked (D-1-R3)`() = runTest {
        // calibrationPendingPkgs must be cleared in start() alongside departureGraceDeadlines.
        // Without this, a failed calibration in session A leaves the marker set, and session B
        // (started immediately after) inherits a permanently blocked swapApps/mirror.
        //
        // Note: exit() (via tearDownLocked) also clears calibrationPendingPkgs, so this test
        // mainly serves as an invariant backstop for start() — the primary cross-session guarantee
        // is provided by teardown. The swapApps assertion verifies the gate is open in session B.
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        coEvery { helper.getTopTaskPackage() } returns null
        coEvery { helper.getTopTaskPackageOrSkip() } returns null

        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope, tickDelayMs = 100,
            // Hook always returns false: calibration never succeeds, pending marker stays set.
            applyCalibratedBounds = { _, _ -> false },
        )
        // Session A: start, depart, hook fails → calibrationPendingPkgs armed.
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))
        coEvery { helper.getTaskState("pkg.narrow") } returns
            SplitTaskState(taskId = 11, windowingMode = 5, 0, 0, 1280, 480, displayId = 2)
        helper.stubTask("pkg.wide", 10, 5, wide)
        advanceTimeBy(150); runCurrent()

        // End session A explicitly.
        mgr.exit()

        // Session B: fresh start must clear calibrationPendingPkgs.
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        coEvery { helper.getTaskState("pkg.narrow") } returns
            SplitTaskState(taskId = 11, windowingMode = 5, 0, 0, 0, 0, displayId = 0)
        coEvery { helper.getTaskState("pkg.wide") } returns
            SplitTaskState(taskId = 10, windowingMode = 5, 0, 0, 0, 0, displayId = 0)
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))
        advanceTimeBy(50); runCurrent()  // let the session settle

        // Clear recorded calls from session A / start so we can assert cleanly on swapApps calls.
        clearMocks(helper, answers = false)

        // swapApps in session B must not be blocked by the now-cleared calibrationPendingPkgs.
        mgr.swapApps()
        coVerify(atLeast = 1) { helper.setTaskBounds(any(), any(), any(), any(), any()) }
    }

    // ── E-1 / E-2 / E-3 / E-4: calibration-retry race fixes (Round 6) ─────────

    @Test fun `E-1a noise window suppresses stale fullscreen during retry then MAXIMIZED fires after window expires`() = runTest {
        // E-1a: the noise window (nowMs + POST_DEPARTURE_NOISE_MS) is armed at the departure
        // tick — BEFORE the calibration attempt — so stale FULLSCREEN@display0 cache readings
        // do not trigger MAXIMIZED while calibration retries. The window is refreshed only by
        // the departed branch (displayId>0); a FULLSCREEN tick (displayId==0) does not refresh
        // it. Once the window expires, FULLSCREEN@display0 correctly ends the session.
        //
        // Uses injectable clock (nowMs = { fakeNow }) so the noise boundary is controlled
        // exactly without relying on real elapsed time.
        //
        // Anti-vacuity (in-window): removing E-1a (noise arming before calibration) causes
        // the in-window stale-fullscreen tick to pass the gate → SessionEnded(MAXIMIZED)
        // fires → first assertTrue fails.
        // Post-window: with E-1a only (no E-1b removed by NEW-1), the gate reopens after
        // the noise window → MAXIMIZED correctly fires → final assertEquals passes.
        var fakeNow = 0L
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        coEvery { helper.getTopTaskPackage() } returns null
        coEvery { helper.getTopTaskPackageOrSkip() } returns null

        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope,
            tickDelayMs = 60_000, nowMs = { fakeNow },
            applyCalibratedBounds = { _, _ -> false },  // always fails
        )
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        // Tick 1 (fakeNow=0): narrow departs to cluster.
        // Noise deadline = 0 + POST_DEPARTURE_NOISE_MS = 3000 ms. Hook returns false.
        coEvery { helper.getTaskState("pkg.narrow") } returns
            SplitTaskState(taskId = 11, windowingMode = 5, 0, 0, 1280, 480, displayId = 2)
        helper.stubTask("pkg.wide", 10, 5, wide)
        advanceTimeBy(60_001); runCurrent()

        // Tick 2 (fakeNow=100, inside noise window): daemon cache lag returns FULLSCREEN@display0.
        // FULLSCREEN branch: graceActive=false, inNoiseWindow=true (100 < 3000) → suppressed.
        // Note: FULLSCREEN tick goes to FULLSCREEN branch (displayId=0), NOT departed branch,
        // so the noise deadline is NOT refreshed here — it remains 3000 from tick 1.
        fakeNow = 100L
        coEvery { helper.getTaskState("pkg.narrow") } returns
            SplitTaskState(taskId = 11, windowingMode = 1, 0, 0, 1920, 1080, displayId = 0)
        helper.stubTask("pkg.wide", 10, 5, wide)
        advanceTimeBy(60_001); runCurrent()
        assertTrue(
            "stale FULLSCREEN within noise window must be suppressed (E-1a in-window)",
            mgr.state.value is SplitSessionState.Active,
        )
        coVerify(exactly = 0) { helper.setTaskWindowingMode(10, 1) }

        // Tick 3 (fakeNow=4000, past noise window): deadline=3000, nowMs=4000 → inNoiseWindow=false.
        // No departed-branch tick between tick 1 and tick 3 refreshed the deadline.
        // FULLSCREEN@display0 is now genuine return → MAXIMIZED fires → session ends.
        fakeNow = 4000L
        advanceTimeBy(60_001); runCurrent()
        assertEquals(
            "FULLSCREEN after noise window expires must end the session (MAXIMIZED)",
            SplitSessionState.Idle, mgr.state.value,
        )
        coVerify(atLeast = 1) { helper.setTaskWindowingMode(10, 1) }
    }

    @Test fun `E-1c COVERED suppressed while departure in progress - resumes and fires after success`() = runTest {
        // NEW-4 / E-1c: COVERED is suppressed while anyDepartureInProgress is true.
        // anyDepartureInProgress = taskId!=-1 && displayId>0 && !departedEmitted (per pane).
        // This covers the departure tick itself (COVERED runs BEFORE handlePaneLocked —
        // anyCalibrationPending was not yet set on that tick) and all retry ticks (departed
        // flag stays false until calibration succeeds). Once calibration succeeds and
        // departedEmitted=true, anyDepartureInProgress becomes false and COVERED resumes.
        //
        // Anti-vacuity: removing !anyDepartureInProgress from panesAtLeastOneAliveFreeformOnMain
        // causes coveredTickCount to reach 2 on ticks 1-2 and SessionEnded(COVERED) fires
        // before PaneClosed — the session ends while the pane is still calibrating.
        val backdrop = FakeSplitBackdrop()
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        coEvery { helper.getTopTask() } returns foreignFullscreenTopTask()
        coEvery { helper.getTopTaskPackage() } returns null
        coEvery { helper.getTopTaskPackageOrSkip() } returns null

        // Narrow stays on cluster display for all ticks.
        coEvery { helper.getTaskState("pkg.narrow") } returns
            SplitTaskState(taskId = 11, windowingMode = 5, 0, 0, 1280, 480, displayId = 2)
        helper.stubTask("pkg.wide", 10, 5, wide)

        var calibrateCount = 0
        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), backdrop, backgroundScope, tickDelayMs = 60_000,
            applyCalibratedBounds = { _, _ -> ++calibrateCount > 2 },  // false×2, then true
        )
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        val events = mutableListOf<SplitEvent>()
        val collectJob = launch { mgr.events.collect { events.add(it) } }

        // Ticks 1-3: narrow on displayId=2 with !narrowPaneDepartedEmitted → anyDepartureInProgress=true
        // throughout. coveredTickCount stays 0 on all 3 ticks. Calibration succeeds on tick 3
        // (handlePaneLocked runs after COVERED, so even tick 3's COVERED check still sees
        // !departedEmitted=true → suppressed). PaneClosed emitted at end of tick 3.
        repeat(3) { advanceTimeBy(60_001); runCurrent() }
        assertEquals("PaneClosed must be emitted once calibration succeeds (tick 3)", 1, events.size)
        assertEquals(SplitEvent.PaneClosed(Pane.NARROW), events[0])
        assertTrue("session must remain Active after calibration", mgr.state.value is SplitSessionState.Active)

        // Tick 4: narrowPaneDepartedEmitted=true → anyDepartureInProgress=false → COVERED resumes.
        // First consecutive foreign-fullscreen tick; counter = 1.
        advanceTimeBy(60_001); runCurrent()
        assertEquals("one foreign-fullscreen tick must not yet fire COVERED", 1, events.size)

        // Tick 5: second consecutive foreign-fullscreen tick → counter = 2 → COVERED fires.
        advanceTimeBy(60_001); runCurrent()
        assertEquals("COVERED must fire after 2 consecutive foreign-fullscreen ticks post-calibration", 2, events.size)
        assertEquals(SplitEvent.SessionEnded(EndReason.COVERED), events[1])
        assertFalse("backdrop must be hidden after COVERED teardown", backdrop.shown)

        collectJob.cancel()
    }

    @Test fun `E-3 pane returns to main display mid-retry clears calibrationPendingPkgs`() = runTest {
        // If the departed pane's task returns freeform@display0 before calibration succeeds,
        // the else (return-to-main) branch fires. E-3 adds a calibrationPendingPkgs - pkg
        // clear in that branch so swapApps/mirror are not permanently blocked for the remainder
        // of the session.
        //
        // Anti-vacuity: removing the E-3 synchronized block leaves calibrationPendingPkgs set
        // after the return → swapApps gate fires → setTaskBounds never called.
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        coEvery { helper.getTopTaskPackage() } returns null
        coEvery { helper.getTopTaskPackageOrSkip() } returns null

        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope, tickDelayMs = 100,
            applyCalibratedBounds = { _, _ -> false },  // always fails
        )
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        // Tick 1: narrow departs to cluster, hook fails → calibrationPendingPkgs armed.
        coEvery { helper.getTaskState("pkg.narrow") } returns
            SplitTaskState(taskId = 11, windowingMode = 5, 0, 0, 1280, 480, displayId = 2)
        helper.stubTask("pkg.wide", 10, 5, wide)
        advanceTimeBy(150); runCurrent()

        // Tick 2: narrow returns freeform@display0 — else branch fires, clears the pending marker.
        // The else branch also calls setTaskBounds (bounds snap-back). clearMocks is placed AFTER
        // this tick so the snap-back call is cleared and only swapApps's call is verified below.
        // (clearMocks before tick 2 was vacuous: the snap-back call from tick 2 alone satisfied
        // the verify even when E-3 was absent and the gate remained blocked.)
        coEvery { helper.getTaskState("pkg.narrow") } returns
            SplitTaskState(taskId = 11, windowingMode = 5, 0, 0, 0, 0, displayId = 0)
        helper.stubTask("pkg.wide", 10, 5, wide)
        advanceTimeBy(150); runCurrent()

        clearMocks(helper, answers = false)  // clears tick-2 snap-back; stubs preserved

        // Gate is now open: swapApps must call setTaskBounds.
        mgr.swapApps()
        coVerify(atLeast = 1) { helper.setTaskBounds(any(), any(), any(), any(), any()) }
    }

    // E-4 structural guarantee (not unit-testable, declared as D-2 class):
    // beginClusterSend reads _state.value and checks pair membership INSIDE synchronized(graceLock).
    // changeApp also holds graceLock when it clears the old-pkg grace entry (and publishes the
    // new pair under mutex before that). These critical sections are serialised by graceLock:
    // any interleaving of changeApp and a delayed beginClusterSend(oldPkg) leaves the grace map
    // in a consistent state — either changeApp cleared the stale entry after beginClusterSend
    // wrote it, or beginClusterSend saw the new pair and skipped the write entirely.
    // The TOCTOU race (pair check outside lock in old code) cannot be deterministically
    // reproduced without thread-level instrumentation that would make tests non-deterministic;
    // correctness is guaranteed by construction (same lock pattern as D-2 graceLock fix).

    // ── Task N: reAssertSplitZOrder via raiseFreeformTask ─────────────────────

    @Test fun `reroute does not call raiseFreeformTask for a departed pane`() = runTest {
        // I1 regression guard: a pane whose task has moved to a non-zero display (e.g. cluster)
        // must NOT be passed to raiseFreeformTask — am start --display 0 would yank it back.
        // The non-departed pane must still be raised normally.
        val helper = mockk<HelperClient>(relaxed = true)
        val mediaSource = mockk<MediaSessionSource>(relaxed = true)
        val controller = mockk<MediaControllerHandle>(relaxed = true)

        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", taskId = 10, mode = 5, b = wide)
        helper.stubTask("pkg.narrow", taskId = 11, mode = 5, b = narrow)
        // Dual-stub: both poll variants may be called; mediacenter is not foreground initially.
        coEvery { helper.getTopTaskPackageOrSkip() } returns "pkg.wide"
        coEvery { helper.getTopTaskPackage() } returns "pkg.wide"

        every { mediaSource.activeSessionPackages() } returns setOf("pkg.narrow")
        every { mediaSource.findController(any()) } returns controller
        every { controller.packageName } returns "pkg.narrow"

        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope,
            tickDelayMs = 100,
            mediaSource = mediaSource, mediaPollDelayMs = 50L,
            paneTypePolicy = standardPanes,
        )
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))
        // W6-F1: start() itself now raises pre-existing pane tasks (restart path). Drop those
        // recorded calls so the verifications below only see the reroute's reAssert.
        clearMocks(helper, answers = false)

        // Narrow pane departs to the cluster (display 2). Let one watchdog tick observe it so
        // narrowPaneDepartedEmitted is set to true before the reroute fires.
        coEvery { helper.getTaskState("pkg.narrow") } returns
            SplitTaskState(taskId = 11, windowingMode = 5, 0, 0, 1280, 480, displayId = 2)
        advanceTimeBy(150); runCurrent()  // watchdog tick at 100ms → narrowPaneDepartedEmitted = true

        // Trigger reroute: mediacenter surfaces on the main display.
        coEvery { helper.getTopTaskPackageOrSkip() } returns MEDIACENTER_PKG
        coEvery { helper.getTopTaskPackage() } returns MEDIACENTER_PKG
        advanceTimeBy(200); runCurrent()  // first media poll tick (50ms) → reroute fires

        // Departed narrow pane: raiseFreeformTask must NOT be called (would yank it off the cluster).
        coVerify(exactly = 0) { helper.raiseFreeformTask("pkg.narrow", activityType = any()) }
        // Non-departed wide pane: must still be raised normally, as a STANDARD pane.
        coVerify(atLeast = 1) {
            helper.raiseFreeformTask("pkg.wide", 0, HelperBinderProtocol.PANE_TYPE_STANDARD)
        }
        // setFocusedTask for narrow: also skipped (raise1=true via departed flag short-circuit).
        coVerify(exactly = 0) { helper.setFocusedTask(11) }
    }

    @Test fun `reroute path calls raiseFreeformTask for both panes after backdrop show`() = runTest {
        // Verifies that when the media-key reroute fires, reAssertSplitZOrder:
        //   1. calls backdrop.show() first (panes must land above the backdrop), and
        //   2. then calls raiseFreeformTask for both the narrow and wide package.
        //
        // Order is verified by tracking global call order via an atomic counter. Each mock records
        // the counter value when it fires. The reroute's backdrop.show() must have a lower counter
        // than both raise calls. A mutation that moves show() after the raises would produce
        // showAtN > raise1AtN, failing the assertion.
        var callOrder = 0
        var showOrder = -1
        var raiseNarrowOrder = -1
        var raiseWideOrder = -1

        val helper = mockk<HelperClient>(relaxed = true)
        // FakeSplitBackdrop can't intercept calls — use a recording wrapper.
        val backdrop = object : SplitBackdrop {
            override suspend fun show(): Boolean { showOrder = callOrder++; return true }
            override fun hide() {}
        }
        val mediaSource = mockk<MediaSessionSource>(relaxed = true)
        val controller = mockk<MediaControllerHandle>(relaxed = true)

        every { mediaSource.activeSessionPackages() } returns setOf("pkg.narrow")
        every { mediaSource.findController(any()) } returns controller
        every { controller.packageName } returns "pkg.narrow"

        coEvery { helper.raiseFreeformTask("pkg.narrow", activityType = any()) } answers { raiseNarrowOrder = callOrder++; true }
        coEvery { helper.raiseFreeformTask("pkg.wide", activityType = any()) } answers { raiseWideOrder = callOrder++; true }

        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", taskId = 10, mode = 5, b = wide)
        helper.stubTask("pkg.narrow", taskId = 11, mode = 5, b = narrow)

        // Initial tick: mediacenter is not on top.
        coEvery { helper.getTopTaskPackageOrSkip() } returns "pkg.narrow"
        coEvery { helper.getTopTaskPackage() } returns "pkg.narrow"

        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), backdrop, backgroundScope,
            tickDelayMs = 60_000,
            mediaSource = mediaSource, mediaPollDelayMs = 50L,
        )
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        // Trigger reroute: mediacenter surfaces.
        coEvery { helper.getTopTaskPackageOrSkip() } returns MEDIACENTER_PKG
        coEvery { helper.getTopTaskPackage() } returns MEDIACENTER_PKG

        advanceTimeBy(200); runCurrent()

        // The reroute must have fired: both raise calls must have happened.
        assertEquals("raiseFreeformTask(pkg.narrow) must have been called", true, raiseNarrowOrder >= 0)
        assertEquals("raiseFreeformTask(pkg.wide) must have been called", true, raiseWideOrder >= 0)
        // backdrop.show() inside reAssertSplitZOrder MUST precede both raise calls.
        // showOrder captures the LAST backdrop.show() call (from start and reroute; reroute comes last).
        assertEquals("backdrop.show() must precede raiseFreeformTask (narrow)", true, showOrder < raiseNarrowOrder)
        assertEquals("backdrop.show() must precede raiseFreeformTask (wide)", true, showOrder < raiseWideOrder)
        // Short-circuit guard: setFocusedTask must NOT be called when both raises succeed.
        // Mutation || → or (non-short-circuit) would add two extra binder RTTs per reroute
        // inside the mutex — this assertion catches that mutation.
        coVerify(exactly = 0) { helper.setFocusedTask(any()) }
    }

    @Test fun `reAssertSplitZOrder falls back to setFocusedTask when raiseFreeformTask returns false`() = runTest {
        // Simulates an old daemon (pre-Task-N) where TX_RAISE_FREEFORM_TASK is unrecognised:
        // the binder returns false → raiseFreeformTask returns false → fallback to setFocusedTask
        // for both task ids (386-era behavior). The backdrop must NOT be hidden (fallback succeeds).
        val helper = mockk<HelperClient>(relaxed = true)
        val backdrop = mockk<SplitBackdrop>()

        coEvery { backdrop.show() } returns true
        every { backdrop.hide() } just runs
        // Simulate old daemon: raise returns false for both panes.
        coEvery { helper.raiseFreeformTask(any(), activityType = any()) } returns false
        // Old-daemon fallback: setFocusedTask succeeds for both task ids.
        coEvery { helper.setFocusedTask(any()) } returns true

        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow", "pkg.new")
        helper.stubTask("pkg.wide", taskId = 10, mode = 5, b = wide)
        helper.stubTask("pkg.narrow", taskId = 11, mode = 5, b = narrow)
        helper.stubTask("pkg.new", taskId = 20, mode = 5, b = narrow)

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), backdrop, backgroundScope, 60_000)
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        mgr.changeApp(Pane.NARROW, "pkg.new")

        // Both raises failed → fallback to setFocusedTask for both task ids.
        coVerify { helper.setFocusedTask(10) }
        coVerify { helper.setFocusedTask(20) }
        // Fallback succeeded → no both-fail condition → backdrop must NOT be hidden.
        verify(exactly = 0) { backdrop.hide() }
        assertEquals(true, mgr.state.value is SplitSessionState.Active)
    }

    @Test fun `reAssertSplitZOrder hides backdrop when both raise and fallback setFocusedTask fail`() = runTest {
        // Both raiseFreeformTask and setFocusedTask fallback fail for both panes:
        // effective1 = false, effective2 = false → backdrop.hide() fires.
        val helper = mockk<HelperClient>(relaxed = true)
        val backdrop = mockk<SplitBackdrop>()

        coEvery { backdrop.show() } returns true
        every { backdrop.hide() } just runs
        // All raise calls fail (old daemon / component not found).
        coEvery { helper.raiseFreeformTask(any(), activityType = any()) } returns false
        // Fallback setFocusedTask also fails for both panes.
        coEvery { helper.setFocusedTask(any()) } returns false

        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow", "pkg.new")
        helper.stubTask("pkg.wide", taskId = 10, mode = 5, b = wide)
        helper.stubTask("pkg.narrow", taskId = 11, mode = 5, b = narrow)
        helper.stubTask("pkg.new", taskId = 20, mode = 5, b = narrow)

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), backdrop, backgroundScope, 60_000)
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        mgr.changeApp(Pane.NARROW, "pkg.new")

        // Both pane raise calls failed (after fallback) → backdrop hidden to avoid black screen.
        verify { backdrop.hide() }
        // Session stays Active (only the backdrop layer is removed, not the split session).
        assertEquals(true, mgr.state.value is SplitSessionState.Active)
    }

    // ── P2: departed flag cleared on task death ───────────────────────────────

    /**
     * P2 fix (proper test through the reAssert/reroute path, as the brief demanded):
     * departure → task death → reroute fires → raiseFreeformTask IS called for that pane.
     *
     * Anti-vacuity: removing `narrowPaneDepartedEmitted = false` from the death branch leaves
     * the flag true; reAssertSplitZOrder then short-circuits via `narrowDeparted || raise1`
     * and `raiseFreeformTask("pkg.narrow", activityType = any())` is never called — the coVerify fails.
     */
    @Test fun `P2 departed flag cleared on death - reroute reAssert raises pane`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val mediaSource = mockk<MediaSessionSource>(relaxed = true)
        val controller = mockk<MediaControllerHandle>(relaxed = true)

        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", taskId = 10, mode = 5, b = wide)
        helper.stubTask("pkg.narrow", taskId = 11, mode = 5, b = narrow)
        coEvery { helper.getTopTaskPackageOrSkip() } returns "pkg.wide"
        coEvery { helper.getTopTaskPackage() } returns "pkg.wide"
        every { mediaSource.activeSessionPackages() } returns setOf("pkg.narrow")
        every { mediaSource.findController(any()) } returns controller
        every { controller.packageName } returns "pkg.narrow"

        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope,
            tickDelayMs = 100,
            mediaSource = mediaSource, mediaPollDelayMs = 50L,
        )
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))
        // W6-F1: start() itself now raises pre-existing pane tasks (restart path). Drop those
        // recorded calls so the step verifications below only see the reroute's reAssert.
        clearMocks(helper, answers = false)

        // Step 1: Narrow pane departs to cluster — watchdog sets narrowPaneDepartedEmitted=true.
        coEvery { helper.getTaskState("pkg.narrow") } returns
            SplitTaskState(taskId = 11, windowingMode = 5, 0, 0, 1280, 480, displayId = 2)
        helper.stubTask("pkg.wide", 10, 5, wide)
        advanceTimeBy(150); runCurrent()
        coVerify(exactly = 0) { helper.raiseFreeformTask("pkg.narrow", activityType = any()) }  // still departed

        // Step 2: Narrow task dies on cluster — P2 fix clears narrowPaneDepartedEmitted.
        coEvery { helper.getTaskState("pkg.narrow") } returns SplitTaskState(-1, 0, 0, 0, 0, 0)
        advanceTimeBy(150); runCurrent()

        // Step 3: Reroute fires (mediacenter surfaces) → reAssertSplitZOrder runs.
        coEvery { helper.getTopTaskPackageOrSkip() } returns MEDIACENTER_PKG
        coEvery { helper.getTopTaskPackage() } returns MEDIACENTER_PKG
        advanceTimeBy(200); runCurrent()

        // P2 cleared the flag on death → raiseFreeformTask IS called for pkg.narrow.
        // Without P2: flag stays true, raise is skipped, pane stays under backdrop.
        coVerify(atLeast = 1) { helper.raiseFreeformTask("pkg.narrow", activityType = any()) }
    }

    /**
     * Companion to the P2 fix: verifies the task-death branch does NOT snap bounds back
     * (the departed task is gone; snapping would corrupt cluster calibration). Also confirms
     * P2 flag clear lets mirror() resume after death (uses the P3 guard as observable proxy).
     *
     * NOTE: this test pins the watchdog's no-snap behaviour on departed-then-dead tasks, NOT
     * the reAssert path. The proper reAssert pin is in the test above.
     */
    @Test fun `P2+P3 departed death does not snap bounds - flag clear lets mirror resume`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope, 100)
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        // Narrow pane departs to cluster (displayId=2); watchdog tick sets narrowPaneDepartedEmitted=true.
        coEvery { helper.getTaskState("pkg.narrow") } returns
            SplitTaskState(11, 5, narrow.left, narrow.top, narrow.right, narrow.bottom, displayId = 2)
        helper.stubTask("pkg.wide", 10, 5, wide)
        advanceTimeBy(150); runCurrent()
        // Departed branch does not snap bounds back (would corrupt cluster calibration).
        coVerify(exactly = 0) { helper.setTaskBounds(11, any(), any(), any(), any()) }

        // Narrow task dies on the cluster (taskId=-1); P2 fix clears narrowPaneDepartedEmitted.
        coEvery { helper.getTaskState("pkg.narrow") } returns SplitTaskState(-1, 0, 0, 0, 0, 0)
        advanceTimeBy(150); runCurrent()

        // After death the departed flag is cleared: mirror() executes (P3 guard passes).
        // If P2 did NOT clear the flag, mirror would still be a no-op and this verify would fail.
        mgr.mirror()
        val (wideL, narrowL) = boundsFor(SplitSide.LEFT)
        coVerify { helper.setTaskBounds(11, narrowL.left, narrowL.top, narrowL.right, narrowL.bottom) }
    }

    // ── P3: mirror and swapApps are no-ops when a pane is departed ───────────

    @Test fun `P3 mirror is full no-op when narrow pane is departed`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope, 100)
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        // Narrow pane departs to cluster — sets narrowPaneDepartedEmitted=true.
        coEvery { helper.getTaskState("pkg.narrow") } returns
            SplitTaskState(11, 5, narrow.left, narrow.top, narrow.right, narrow.bottom, displayId = 2)
        helper.stubTask("pkg.wide", 10, 5, wide)
        advanceTimeBy(150)
        runCurrent()

        // Capture state before mirror attempt.
        val stateBefore = mgr.state.value as SplitSessionState.Active

        mgr.mirror()

        // mirror() must be a full no-op: no bounds changes and session state unchanged.
        // Without the guard, mirror() would stamp main-screen bounds onto the cluster task,
        // corrupting its calibration.
        coVerify(exactly = 0) { helper.setTaskBounds(11, any(), any(), any(), any()) }
        coVerify(exactly = 0) { helper.setTaskBounds(10, any(), any(), any(), any()) }
        assertEquals("session state must not change on mirror with departed pane", stateBefore, mgr.state.value)
    }

    @Test fun `P3 swapApps is full no-op when wide pane is departed`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope, 100)
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        // Wide pane departs — sets widePaneDepartedEmitted=true.
        coEvery { helper.getTaskState("pkg.wide") } returns
            SplitTaskState(10, 5, wide.left, wide.top, wide.right, wide.bottom, displayId = 2)
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        advanceTimeBy(150)
        runCurrent()

        val stateBefore = mgr.state.value as SplitSessionState.Active

        mgr.swapApps()

        // swapApps() must be a full no-op: no bounds changes and package roles unchanged.
        // Without the guard the departed flag would point at the wrong pane after the swap,
        // risking a later reAssert pulling the cluster task back to the main display.
        coVerify(exactly = 0) { helper.setTaskBounds(any(), any(), any(), any(), any()) }
        assertEquals("state must not change on swapApps with departed pane", stateBefore, mgr.state.value)
        val stateAfter = mgr.state.value as SplitSessionState.Active
        assertEquals("narrowPkg must not change", "pkg.narrow", stateAfter.pair.narrowPkg)
        assertEquals("widePkg must not change", "pkg.wide", stateAfter.pair.widePkg)
    }

    // ── Q1: departure grace (F-1) ─────────────────────────────────────────────

    @Test fun `Q1 grace active + fullscreen@display0 does not trigger MAXIMIZED`() = runTest {
        // Reproduction of F-1: BYDMate sends the narrow pane to the cluster via direct projection
        // (REMOVE+RELAUNCH). During the transient the watchdog sees the pane as fullscreen@display0
        // and would call MAXIMIZED teardown — collapsing the split. Grace must suppress this.
        // Uses injectable clock (fakeNow) so the test is deterministic and not time-dependent.
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        coEvery { helper.getTopTaskPackage() } returns null
        coEvery { helper.getTopTaskPackageOrSkip() } returns null

        var fakeNow = 0L
        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope,
            tickDelayMs = 60_000, nowMs = { fakeNow },
        )
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        // Set grace at t=0: beginClusterSend is synchronous — deadline = fakeNow(0) + 18000 = 18000.
        mgr.beginClusterSend("pkg.narrow")
        // fakeNow stays 0: grace is active (0 < 18000).

        // Watchdog tick: narrow pane appears fullscreen on display 0 (REMOVE step).
        coEvery { helper.getTaskState("pkg.narrow") } returns
            SplitTaskState(taskId = 11, windowingMode = 1, 0, 0, 1920, 1080)
        advanceTimeBy(60_001); runCurrent()

        // Session must still be Active — grace suppressed the MAXIMIZED branch.
        assertEquals(
            "grace must prevent MAXIMIZED teardown during cluster send",
            true, mgr.state.value is SplitSessionState.Active,
        )
        // Wide pane must NOT have been flipped to fullscreen (MAXIMIZED teardown action).
        coVerify(exactly = 0) { helper.setTaskWindowingMode(10, 1) }
    }

    @Test fun `Q1 grace active + task death does not emit PaneClosed`() = runTest {
        // During REMOVE+RELAUNCH the task briefly dies (taskId==-1). Grace must suppress
        // PaneClosed so the user's split UI does not show a "choose replacement" dialog.
        // Uses injectable clock (fakeNow) so the test is deterministic.
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)

        var fakeNow = 0L
        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope,
            tickDelayMs = 60_000, nowMs = { fakeNow },
        )
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        // beginClusterSend is synchronous — deadline = fakeNow(0) + 18000 = 18000. fakeNow stays 0.
        mgr.beginClusterSend("pkg.narrow")

        // Narrow task dies (REMOVE step).
        coEvery { helper.getTaskState("pkg.narrow") } returns
            SplitTaskState(taskId = -1, windowingMode = 0, 0, 0, 0, 0)

        mgr.events.test {
            advanceTimeBy(60_001); runCurrent()
            // No PaneClosed must be emitted.
            expectNoEvents()
        }
        // Session stays Active.
        assertEquals(true, mgr.state.value is SplitSessionState.Active)
    }

    @Test fun `Q1 departed resolves grace - post-departure noise suppressed within window`() = runTest {
        // F-1 happy-path: grace fires → tick sees displayId>0 (cluster) → departed branch fires
        // (PaneClosed emitted, noise window armed for POST_DEPARTURE_NOISE_MS=3000 ms) →
        // a follow-up tick within the noise window returning fullscreen@display0 is suppressed.
        // Uses injectable clock to control noise window boundary deterministically.
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        coEvery { helper.getTopTaskPackage() } returns null
        coEvery { helper.getTopTaskPackageOrSkip() } returns null

        var fakeNow = 0L
        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope,
            tickDelayMs = 60_000, nowMs = { fakeNow },
        )
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        // Grace armed at t=0 (deadline=18000); fakeNow stays 0 during tick 1.
        mgr.beginClusterSend("pkg.narrow")

        // Tick 1: narrow task arrived on cluster (displayId=4) → departed; noise deadline = 0+3000 = 3000.
        coEvery { helper.getTaskState("pkg.narrow") } returns
            SplitTaskState(taskId = 41, windowingMode = 5, 0, 0, 1280, 480, displayId = 4)

        mgr.events.test {
            advanceTimeBy(60_001); runCurrent()
            assertEquals(SplitEvent.PaneClosed(Pane.NARROW), awaitItem())
        }
        assertEquals("session stays Active after departure", true, mgr.state.value is SplitSessionState.Active)

        // Advance clock to t=1000 — still within noise window (1000 < 3000).
        fakeNow = 1_000L
        coEvery { helper.getTaskState("pkg.narrow") } returns
            SplitTaskState(taskId = 41, windowingMode = 1, 0, 0, 1920, 1080, displayId = 0)

        mgr.events.test {
            advanceTimeBy(60_001); runCurrent()
            expectNoEvents()  // noise window active → MAXIMIZED suppressed
        }
        assertEquals("session must still be Active inside noise window", true, mgr.state.value is SplitSessionState.Active)
    }

    @Test fun `Q1 post-departure noise window expired - fullscreen@display0 triggers MAXIMIZED`() = runTest {
        // F-Q1-1 fix: after the bounded post-departure noise window (3000 ms) expires, a
        // fullscreen@display0 reading means the pane genuinely returned from the cluster.
        // MAXIMIZED teardown must fire, emitting SessionEnded and flipping the other pane.
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        coEvery { helper.getTopTaskPackage() } returns null
        coEvery { helper.getTopTaskPackageOrSkip() } returns null

        var fakeNow = 0L
        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope,
            tickDelayMs = 60_000, nowMs = { fakeNow },
        )
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        mgr.beginClusterSend("pkg.narrow")

        // Tick 1: task departed to cluster at t=0 — noise deadline = 0 + 3000 = 3000.
        coEvery { helper.getTaskState("pkg.narrow") } returns
            SplitTaskState(taskId = 41, windowingMode = 5, 0, 0, 1280, 480, displayId = 4)

        mgr.events.test {
            advanceTimeBy(60_001); runCurrent()
            assertEquals(SplitEvent.PaneClosed(Pane.NARROW), awaitItem())
        }

        // Jump clock past noise deadline: t=3001 > 3000 → inNoiseWindow=false.
        fakeNow = 3_001L
        coEvery { helper.getTaskState("pkg.narrow") } returns
            SplitTaskState(taskId = 41, windowingMode = 1, 0, 0, 1920, 1080, displayId = 0)

        mgr.events.test {
            advanceTimeBy(60_001); runCurrent()
            // Noise window expired → pane returned from cluster → MAXIMIZED fires.
            assertEquals(SplitEvent.SessionEnded(EndReason.MAXIMIZED), awaitItem())
        }
        // Other pane (wide, taskId=10) must have been flipped to FULLSCREEN.
        coVerify(atLeast = 1) { helper.setTaskWindowingMode(10, 1) }
    }

    @Test fun `Q1 grace expired + fullscreen@display0 triggers MAXIMIZED normally`() = runTest {
        // Deadline expiry: the grace deadline passed without a confirmed departure. Normal
        // MAXIMIZED classification must resume (fail-open).
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        coEvery { helper.getTopTaskPackage() } returns null
        coEvery { helper.getTopTaskPackageOrSkip() } returns null

        var fakeNow = 0L
        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope,
            tickDelayMs = 60_000, nowMs = { fakeNow },
        )
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        // beginClusterSend is synchronous — sets deadline = fakeNow(0) + 18000 = 18000 immediately.
        // Jump clock past the deadline AFTER the call so the watchdog tick reads graceActive=false.
        mgr.beginClusterSend("pkg.narrow")  // deadline=18000 set synchronously
        fakeNow = 18_001L                   // now: 18001 > 18000 → graceActive=false

        // Tick: narrow pane is fullscreen@display0 — grace is expired, normal MAXIMIZED must fire.
        coEvery { helper.getTaskState("pkg.narrow") } returns
            SplitTaskState(taskId = 11, windowingMode = 1, 0, 0, 1920, 1080)

        mgr.events.test {
            advanceTimeBy(60_001); runCurrent()
            assertEquals(SplitEvent.SessionEnded(EndReason.MAXIMIZED), awaitItem())
        }
    }

    @Test fun `Q1 anti-vacuity - without grace fullscreen@display0 ends the session`() = runTest {
        // Mutation evidence: if beginClusterSend is NOT called (or grace suppression is removed),
        // the same fullscreen@display0 tick fires MAXIMIZED and collapses the split.
        // This test must PASS (it verifies the pre-grace behavior is correct) and proves that
        // the grace mechanism in the positive test above is what prevents the teardown.
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        coEvery { helper.getTopTaskPackage() } returns null
        coEvery { helper.getTopTaskPackageOrSkip() } returns null

        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope,
            tickDelayMs = 60_000,
        )
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        // No beginClusterSend call — grace is inactive.

        coEvery { helper.getTaskState("pkg.narrow") } returns
            SplitTaskState(taskId = 11, windowingMode = 1, 0, 0, 1920, 1080)

        mgr.events.test {
            advanceTimeBy(60_001); runCurrent()
            // Without grace, MAXIMIZED fires and ends the session.
            assertEquals(SplitEvent.SessionEnded(EndReason.MAXIMIZED), awaitItem())
        }
    }

    @Test fun `Q1b grace remains active at t=16000 — late relaunch within 18s window`() = runTest {
        // Regression guard for DEPARTURE_GRACE_MS = 18 000 ms (class deadline-vs-operation).
        //
        // Scenario: daemon's PRE-MOVE path (task missing → am start → 16×500ms poll → 1500ms
        // wait → moveTaskToDisplayReflect) can take ~9.5s INSIDE the 15s FORCE_TIMEOUT_MS cap.
        // With the old 8s grace, the watchdog tick at t=9000+ saw fullscreen@display0 (REMOVE
        // step) with grace expired → MAXIMIZED → session dead. At 18s the same tick at t=16000
        // is within the grace window → session stays Active.
        //
        // Anti-vacuity (mutation "DEPARTURE_GRACE_MS = 8_000L"):
        //   deadline = 0 + 8000 = 8000; fakeNow=16000 > 8000 → graceActive=false
        //   → MAXIMIZED fires → assertEquals(Active) fails (proved below).
        //
        // Backstop: at t=19000 > 18000 grace is expired → MAXIMIZED fires — the backstop is alive.
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        coEvery { helper.getTopTaskPackage() } returns null
        coEvery { helper.getTopTaskPackageOrSkip() } returns null

        var fakeNow = 0L
        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope,
            tickDelayMs = 60_000, nowMs = { fakeNow },
        )
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        // Re-arm at t=0 mirrors the launchAndForce re-arm (CPM:874 → onBeforeClusterSend →
        // beginClusterSend). Deadline = fakeNow(0) + 18000 = 18000.
        mgr.beginClusterSend("pkg.narrow")

        // Narrow pane in REMOVE step: appears fullscreen@display0.
        coEvery { helper.getTaskState("pkg.narrow") } returns
            SplitTaskState(taskId = 11, windowingMode = 1, 0, 0, 1920, 1080)

        // Phase 1: tick at t=16000 — inside new grace window (16000 < 18000).
        // With DEPARTURE_GRACE_MS=8000: 16000 > 8000 → graceActive=false → MAXIMIZED fires.
        fakeNow = 16_000L
        advanceTimeBy(60_001); runCurrent()

        assertEquals(
            "grace must suppress MAXIMIZED at t=16000 (< 18s deadline); " +
            "mutation 'DEPARTURE_GRACE_MS=8_000' gives deadline=8000, 16000>8000 → MAXIMIZED fires",
            true, mgr.state.value is SplitSessionState.Active,
        )
        coVerify(exactly = 0) { helper.setTaskWindowingMode(10, 1) }

        // Phase 2: backstop — grace expired at t=18000. Tick at t=19000 fires MAXIMIZED.
        fakeNow = 19_000L
        mgr.events.test {
            advanceTimeBy(60_001); runCurrent()
            assertEquals(
                "backstop: MAXIMIZED must fire at t=19000 after grace expiry (19000 > 18000)",
                SplitEvent.SessionEnded(EndReason.MAXIMIZED), awaitItem(),
            )
        }
    }

    // ── Q3: COVERED teardown (F-3) ────────────────────────────────────────────

    private fun foreignFullscreenTopTask(pkg: String = "com.foreign.app") = TopTaskInfo(
        pkg = pkg, taskId = 99, windowingMode = 1 /* FULLSCREEN */, activityType = 1, displayId = 0,
    )

    @Test fun `Q3 foreign fullscreen for 2 ticks emits COVERED and hides backdrop`() = runTest {
        // F-3: foreign fullscreen app covering the split → SessionEnded(COVERED). Pane tasks must
        // NOT be touched (setTaskWindowingMode not called). Backdrop is hidden.
        val backdrop = FakeSplitBackdrop()
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        coEvery { helper.getTopTask() } returns foreignFullscreenTopTask()
        coEvery { helper.getTopTaskPackage() } returns null
        coEvery { helper.getTopTaskPackageOrSkip() } returns null

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), backdrop, backgroundScope, 60_000)
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        assertTrue("backdrop shown after start", backdrop.shown)

        mgr.events.test {
            // Tick 1 — coveredTickCount=1; no event yet (flicker immunity).
            advanceTimeBy(60_001); runCurrent()
            // Tick 2 — coveredTickCount=2 ≥ 2 → COVERED fires.
            advanceTimeBy(60_001); runCurrent()
            assertEquals(SplitEvent.SessionEnded(EndReason.COVERED), awaitItem())
        }

        assertFalse("backdrop must be hidden after COVERED", backdrop.shown)
        // Pane tasks must NOT be flipped to fullscreen — music lives.
        coVerify(exactly = 0) { helper.setTaskWindowingMode(10, 1) }
        coVerify(exactly = 0) { helper.setTaskWindowingMode(11, 1) }
        assertEquals("state must be Idle after COVERED", SplitSessionState.Idle, mgr.state.value)
    }

    @Test fun `Q3 flicker immunity - foreign fullscreen for 1 tick then gone keeps session Active`() = runTest {
        // Only 1 tick of foreign fullscreen → coveredTickCount=1, no teardown.
        // On the second tick the top task reverts → coveredTickCount reset → session stays Active.
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        coEvery { helper.getTopTask() } returnsMany listOf(
            foreignFullscreenTopTask(),            // tick 1: covered
            TopTaskInfo("pkg.narrow", 11, 5, 3, 0), // tick 2: narrow pane back on top
        )
        coEvery { helper.getTopTaskPackage() } returns null
        coEvery { helper.getTopTaskPackageOrSkip() } returns null

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope, 60_000)
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        // Two ticks without COVERED firing.
        advanceTimeBy(60_001); runCurrent()
        advanceTimeBy(60_001); runCurrent()

        assertEquals("session must stay Active after 1-tick flicker", true, mgr.state.value is SplitSessionState.Active)
    }

    @Test fun `Q3 top task is session pane pkg - NOT COVERED`() = runTest {
        // Top task is one of the pane packages at fullscreen@display0. The pane-pkg exclusion
        // must prevent COVERED from firing for 3 consecutive ticks.
        // Anti-vacuity (verified by mutation): removing both
        //   topTask.pkg != current.pair.narrowPkg
        //   topTask.pkg != current.pair.widePkg
        // from SplitSessionManager makes coveredTickCount reach 2 and SessionEnded(COVERED)
        // is emitted at tick 2, failing the expectNoEvents check below.
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        // Both panes alive and freeform on display 0 — panesBothAliveFreeformOnMain=true.
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        // Top task is the narrow pane itself — COVERED must be blocked by pane-pkg exclusion.
        coEvery { helper.getTopTask() } returns TopTaskInfo("pkg.narrow", 11, 1 /* FULLSCREEN */, 3, 0)
        coEvery { helper.getTopTaskPackage() } returns null
        coEvery { helper.getTopTaskPackageOrSkip() } returns null

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope, 60_000)
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        mgr.events.test {
            // Three ticks — COVERED must never fire; pane-pkg exclusion is the only guard here.
            repeat(3) { advanceTimeBy(60_001); runCurrent() }
            // No SessionEnded(COVERED) must have been emitted.
            val received = cancelAndConsumeRemainingEvents()
            val coveredEvent = received.filterIsInstance<app.cash.turbine.Event.Item<SplitEvent>>()
                .map { it.value }
                .filterIsInstance<SplitEvent.SessionEnded>()
                .any { it.reason == EndReason.COVERED }
            assertFalse("pane-pkg exclusion must prevent COVERED teardown", coveredEvent)
        }
    }

    @Test fun `Q3 top task is our own package - NOT COVERED`() = runTest {
        // BYDMate's own activity (e.g. Settings overlay) on top must not collapse the split.
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        coEvery { helper.getTopTask() } returns TopTaskInfo("com.bydmate.app", 50, 1, 1, 0)
        coEvery { helper.getTopTaskPackage() } returns null
        coEvery { helper.getTopTaskPackageOrSkip() } returns null

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope, 60_000)
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        // Three ticks — COVERED must not fire for our own package.
        repeat(3) { advanceTimeBy(60_001); runCurrent() }

        assertTrue("session must stay Active when BYDMate is on top", mgr.state.value is SplitSessionState.Active)
        // Neither pane touched by setTaskWindowingMode (COVERED does not call it; session is Active).
        coVerify(exactly = 0) { helper.setTaskWindowingMode(any(), 1) }
    }

    @Test fun `Q3 getTopTask returns null - no classification change this tick`() = runTest {
        // Helper hiccup (getTopTask returns null) — coveredTickCount stays 0, session not ended.
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        coEvery { helper.getTopTask() } returns null
        coEvery { helper.getTopTaskPackage() } returns null
        coEvery { helper.getTopTaskPackageOrSkip() } returns null

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope, 60_000)
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        // Three ticks with null getTopTask — COVERED must never fire.
        repeat(3) { advanceTimeBy(60_001); runCurrent() }

        assertEquals("session must stay Active on helper hiccup", true, mgr.state.value is SplitSessionState.Active)
    }

    @Test fun `Q3 anti-vacuity - removing 2-tick debounce would fire COVERED on 1st tick`() = runTest {
        // Proof that the debounce (coveredTickCount >= 2) is load-bearing.
        // In this test we verify that with a foreign fullscreen on tick 1 + session-pane on tick 2,
        // COVERED does NOT fire. If the threshold were 1 instead of 2, the session would be
        // terminated after tick 1 and this test would see SessionEnded instead of Active.
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        coEvery { helper.getTopTask() } returnsMany listOf(
            foreignFullscreenTopTask(),            // tick 1: foreign → coveredTickCount=1
            TopTaskInfo("pkg.narrow", 11, 5, 3, 0), // tick 2: not foreign → reset counter
        )
        coEvery { helper.getTopTaskPackage() } returns null
        coEvery { helper.getTopTaskPackageOrSkip() } returns null

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope, 60_000)
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        advanceTimeBy(60_001); runCurrent() // tick 1 — counter increments
        advanceTimeBy(60_001); runCurrent() // tick 2 — counter resets

        assertEquals("session must stay Active — 1 tick of coverage is not enough", true, mgr.state.value is SplitSessionState.Active)
    }

    @Test fun `Q3 Q1-grace interaction - departure grace pkg appears fullscreen - NOT COVERED`() = runTest {
        // During Q1 departure grace, the departing pane (pkg.narrow) is in the REMOVE step:
        // taskId=-1 or windowingMode=FULLSCREEN. Because panesBothAliveFreeformOnMain=false
        // (task dead or not-freeform), COVERED detection is skipped entirely — the departing
        // pane's transient fullscreen@display0 cannot trigger COVERED.
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        // getTopTask would return the departing pane's pkg as fullscreen (worst case).
        coEvery { helper.getTopTask() } returns TopTaskInfo("pkg.narrow", 11, 1, 3, 0)
        coEvery { helper.getTopTaskPackage() } returns null
        coEvery { helper.getTopTaskPackageOrSkip() } returns null

        var fakeNow = 0L
        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope,
            tickDelayMs = 60_000, nowMs = { fakeNow },
        )
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))
        mgr.beginClusterSend("pkg.narrow") // arms departure grace

        // Narrow pane goes through REMOVE step: taskId=-1 (dead).
        coEvery { helper.getTaskState("pkg.narrow") } returns
            SplitTaskState(taskId = -1, windowingMode = 1, 0, 0, 1920, 1080)

        // Two ticks during grace (fakeNow=0 < 18000=grace deadline).
        advanceTimeBy(60_001); runCurrent()
        advanceTimeBy(60_001); runCurrent()

        // Session must stay Active — the pane death is suppressed by grace, COVERED skipped
        // because panesBothAliveFreeformOnMain=false (narrow taskId=-1).
        assertEquals("grace+dead pane must not trigger COVERED", true, mgr.state.value is SplitSessionState.Active)
    }

    @Test fun `Q3 grace is the sole COVERED suppressor when both panes alive-freeform`() = runTest {
        // Both panes are alive, freeform, on display 0 — panesBothAliveFreeformOnMain=true but
        // for the grace conditions. Grace is armed on narrow; foreign fullscreen is on top.
        // During the grace window, COVERED must be suppressed. Once grace expires, the same
        // foreign fullscreen for 2 consecutive ticks must trigger COVERED.
        //
        // Anti-vacuity (verified by mutation): removing both `!narrowGrace && !wideGrace`
        // conditions from panesBothAliveFreeformOnMain makes COVERED fire during the grace
        // window, so `mgr.events.test` receives SessionEnded(COVERED) before the grace expires,
        // failing the no-COVERED assertion below.
        val backdrop = FakeSplitBackdrop()
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        // Both panes alive and freeform on display 0.
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        // Foreign fullscreen on top — would trigger COVERED if grace were absent.
        coEvery { helper.getTopTask() } returns foreignFullscreenTopTask("com.foreign.navigator")
        coEvery { helper.getTopTaskPackage() } returns null
        coEvery { helper.getTopTaskPackageOrSkip() } returns null

        var fakeNow = 0L
        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), backdrop, backgroundScope,
            tickDelayMs = 60_000, nowMs = { fakeNow },
        )
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))
        // Arm departure grace for narrow — DEPARTURE_GRACE_MS = 18000 ms.
        mgr.beginClusterSend("pkg.narrow")

        mgr.events.test {
            // Tick 1 during grace (fakeNow=0 < 18000 deadline) — COVERED suppressed by grace.
            advanceTimeBy(60_001); runCurrent()
            // Tick 2 during grace — still suppressed; coveredTickCount would be 2 without grace.
            advanceTimeBy(60_001); runCurrent()
            val duringGrace = cancelAndConsumeRemainingEvents()
            assertFalse(
                "COVERED must be suppressed by departure grace when both panes alive-freeform",
                duringGrace.filterIsInstance<app.cash.turbine.Event.Item<SplitEvent>>()
                    .map { it.value }
                    .filterIsInstance<SplitEvent.SessionEnded>()
                    .any { it.reason == EndReason.COVERED },
            )
        }

        // After grace expires (fakeNow > 18000), 2 ticks of foreign fullscreen must fire COVERED.
        fakeNow = 19_000L
        mgr.events.test {
            // Tick 1 post-grace — coveredTickCount=1.
            advanceTimeBy(60_001); runCurrent()
            // Tick 2 post-grace — coveredTickCount=2 → COVERED fires.
            advanceTimeBy(60_001); runCurrent()
            assertEquals(SplitEvent.SessionEnded(EndReason.COVERED), awaitItem())
        }
    }

    // ── Codex fix-round: C-1 (grace survives swapApps) ───────────────────────

    @Test fun `C-1 departure grace survives swapApps - grace protects package in new slot`() = runTest {
        // Narrow=pkg.nav (navigator), Wide=pkg.music. Grace armed for pkg.nav in narrow slot.
        // swapApps makes pkg.nav the wide pane. During grace, navigator appears FULLSCREEN@display0
        // (the REMOVE step of its cluster REMOVE+RELAUNCH). Grace must suppress MAXIMIZED for it
        // even though it is now in the WIDE slot.
        //
        // Anti-vacuity: without the C-1 fix (role-based narrowDepartureGraceDeadlineMs), swapApps
        // moves pkg.nav to wide but the deadline stays in the NARROW field. handlePaneLocked for
        // the WIDE slot reads wideDepartureGraceDeadlineMs = Long.MIN_VALUE → graceActive=false →
        // MAXIMIZED fires → session ends → test fails at the Active-state assertion.
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.nav", "pkg.music")
        helper.stubTask("pkg.nav", 11, 5, narrow)
        helper.stubTask("pkg.music", 10, 5, wide)
        coEvery { helper.getTopTask() } returns null
        coEvery { helper.getTopTaskPackage() } returns null
        coEvery { helper.getTopTaskPackageOrSkip() } returns null

        var fakeNow = 0L
        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope,
            tickDelayMs = 60_000, nowMs = { fakeNow },
        )
        mgr.start(SplitPair("pkg.nav", "pkg.music", SplitSide.RIGHT))
        runCurrent()

        // Grace armed for pkg.nav (currently narrow) at t=0 → deadline=18000.
        mgr.beginClusterSend("pkg.nav")
        // Swap: pkg.nav → wide slot, pkg.music → narrow slot.
        mgr.swapApps()
        runCurrent()

        // fakeNow stays 0 (grace still active: 0 < 18000).
        // Watchdog sees pkg.nav as FULLSCREEN@display0 (REMOVE step, now in wide slot).
        coEvery { helper.getTaskState("pkg.nav") } returns
            SplitTaskState(taskId = 11, windowingMode = 1 /* FULLSCREEN */, 0, 0, 1920, 1080)
        coEvery { helper.getTaskState("pkg.music") } returns
            SplitTaskState(taskId = 10, windowingMode = 5 /* FREEFORM */, narrow.left, narrow.top, narrow.right, narrow.bottom)

        advanceTimeBy(60_001); runCurrent()

        assertEquals(
            "grace keyed by package must survive swapApps and protect pkg.nav in the wide slot",
            true, mgr.state.value is SplitSessionState.Active,
        )
        // Wide pane (pkg.nav, FULLSCREEN) must NOT have been flipped by MAXIMIZED teardown.
        coVerify(exactly = 0) { helper.setTaskWindowingMode(10, 1) }  // wide's OTHER (music) must not be flipped
    }

    // ── Codex fix-round: C-2 (COVERED with departed pane) ────────────────────

    @Test fun `C-2 COVERED fires when one pane is closed and foreign fullscreen covers the remaining pane`() = runTest {
        // One pane closed (taskId=-1), the other alive-freeform@display0. A foreign fullscreen
        // appearing for 2 consecutive ticks must still trigger COVERED (session teardown).
        //
        // Anti-vacuity: without C-2 fix (panesBothAliveFreeformOnMain requiring BOTH), the closed
        // pane makes the condition false → COVERED detection skipped entirely → session stays Active
        // regardless → test fails at the hasCovered assertion.
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        coEvery { helper.getTopTask() } returns null
        coEvery { helper.getTopTaskPackage() } returns null
        coEvery { helper.getTopTaskPackageOrSkip() } returns null

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope, 60_000)
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))
        runCurrent()

        // Narrow pane is now dead (closed by user or crashed).
        coEvery { helper.getTaskState("pkg.narrow") } returns SplitTaskState(taskId = -1, 0, 0, 0, 0, 0)
        // Wide pane alive and freeform on display 0.
        coEvery { helper.getTaskState("pkg.wide") } returns
            SplitTaskState(taskId = 10, windowingMode = 5, wide.left, wide.top, wide.right, wide.bottom)
        // Foreign fullscreen app on top.
        coEvery { helper.getTopTask() } returns foreignFullscreenTopTask()

        mgr.events.test {
            // Tick 1: PaneClosed(NARROW) emitted; coveredTickCount=1 (wide alive, foreign on top).
            advanceTimeBy(60_001); runCurrent()
            // Tick 2: coveredTickCount=2 → COVERED fires.
            advanceTimeBy(60_001); runCurrent()
            val received = cancelAndConsumeRemainingEvents()
            val items = received.filterIsInstance<app.cash.turbine.Event.Item<SplitEvent>>()
                .map { it.value }
            val hasCovered = items.filterIsInstance<SplitEvent.SessionEnded>()
                .any { it.reason == EndReason.COVERED }
            assertTrue("foreign fullscreen must trigger COVERED when only one pane remains alive", hasCovered)
        }
    }

    @Test fun `C-2 COVERED does not fire when top task is a session pane package even if pane is closed`() = runTest {
        // One pane closed (taskId=-1), but the top fullscreen task's package is that closed pane's
        // package. This is NOT foreign — the user relaunched their own app fullscreen.
        // COVERED must not fire.
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        coEvery { helper.getTopTask() } returns null
        coEvery { helper.getTopTaskPackage() } returns null
        coEvery { helper.getTopTaskPackageOrSkip() } returns null

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope, 60_000)
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))
        runCurrent()

        // Narrow pane closed.
        coEvery { helper.getTaskState("pkg.narrow") } returns SplitTaskState(taskId = -1, 0, 0, 0, 0, 0)
        // Wide pane alive freeform.
        coEvery { helper.getTaskState("pkg.wide") } returns
            SplitTaskState(taskId = 10, windowingMode = 5, wide.left, wide.top, wide.right, wide.bottom)
        // Top fullscreen task is the CLOSED narrow pane's package — not a foreign app.
        coEvery { helper.getTopTask() } returns TopTaskInfo("pkg.narrow", 77, 1, 1, 0)

        mgr.events.test {
            // Two ticks: top is narrowPkg → not foreign → coveredTickCount stays 0.
            advanceTimeBy(60_001); runCurrent()
            advanceTimeBy(60_001); runCurrent()
            val received = cancelAndConsumeRemainingEvents()
            val items = received.filterIsInstance<app.cash.turbine.Event.Item<SplitEvent>>()
                .map { it.value }
            val hasCovered = items.filterIsInstance<SplitEvent.SessionEnded>()
                .any { it.reason == EndReason.COVERED }
            assertFalse("pane-pkg on top must not trigger COVERED even when that pane is closed", hasCovered)
        }
    }

    // ── Codex fix-round reviewer probes ──────────────────────────────────────

    /**
     * PROBE C-2-R1: departed pane on cluster (displayId=4, taskId alive) + alive-freeform wide
     * pane on display0 + foreign fullscreen 2 consecutive ticks → COVERED fires.
     *
     * This is the exact scenario from the codex finding: narrow has departed to the cluster
     * (displayId=4) but the session is still Active and the picker is open.
     * Wide is alive-freeform on display0. A foreign fullscreen app covers the session.
     *
     * Tick structure with NEW-4 anyDepartureInProgress guard:
     *   Tick 1 (departure): anyDepartureInProgress=true (displayId=4, !departedEmitted) →
     *     COVERED suppressed, coveredTickCount=0. handlePaneLocked: null hook → calibrated=true
     *     → narrowPaneDepartedEmitted=true; PaneClosed emitted.
     *   Tick 2: departedEmitted=true → anyDepartureInProgress=false → coveredTickCount=1.
     *   Tick 3: coveredTickCount=2 → COVERED fires.
     *
     * Anti-vacuity: changing `||` to `&&` in panesAtLeastOneAliveFreeformOnMain makes the
     * wide-alive condition insufficient when narrow is on display4 → panesAtLeastOne=false
     * on ticks 2-3 → coveredTickCount never reaches 2 → hasCovered=false → test fails.
     */
    @Test fun `PROBE C-2-R1 departed pane on cluster plus foreign fullscreen 2 ticks fires COVERED`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        coEvery { helper.getTopTask() } returns null
        coEvery { helper.getTopTaskPackage() } returns null
        coEvery { helper.getTopTaskPackageOrSkip() } returns null

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope, 60_000)
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))
        runCurrent()

        // Narrow has departed to cluster display (displayId=4), still alive-freeform.
        coEvery { helper.getTaskState("pkg.narrow") } returns
            SplitTaskState(taskId = 41, windowingMode = 5 /* FREEFORM */, 0, 0, 800, 480, displayId = 4)
        // Wide alive-freeform on display 0.
        coEvery { helper.getTaskState("pkg.wide") } returns
            SplitTaskState(taskId = 10, windowingMode = 5, wide.left, wide.top, wide.right, wide.bottom)
        // Foreign fullscreen on top of display 0.
        coEvery { helper.getTopTask() } returns foreignFullscreenTopTask()

        mgr.events.test {
            // Tick 1 (departure): COVERED suppressed by anyDepartureInProgress; PaneClosed emitted.
            advanceTimeBy(60_001); runCurrent()
            // Tick 2: narrowPaneDepartedEmitted=true → COVERED detection resumes; coveredTickCount=1.
            advanceTimeBy(60_001); runCurrent()
            // Tick 3: coveredTickCount=2 → COVERED fires.
            advanceTimeBy(60_001); runCurrent()
            val received = cancelAndConsumeRemainingEvents()
            val items = received.filterIsInstance<app.cash.turbine.Event.Item<SplitEvent>>().map { it.value }
            val hasCovered = items.filterIsInstance<SplitEvent.SessionEnded>().any { it.reason == EndReason.COVERED }
            assertTrue("departed narrow + foreign fullscreen 2 ticks must trigger COVERED (original codex scenario)", hasCovered)
        }
    }

    // ── Codex fix-round: Minor 1 (swapApps blocked during active grace) ──────

    /**
     * swapApps is a no-op when departure grace is active. Swapping during the REMOVE+RELAUNCH
     * window would set bounds on a task id that is mid-move to the cluster, and the subsequent
     * calibratedBounds would apply to the wrong slot.
     *
     * Anti-vacuity: removing `if (departureGraceDeadlines.values.any { nowMs() < it }) return@withLock`
     * from swapApps lets the swap proceed; coVerify(exactly = 1) { setTaskBounds(any(), narrowBounds) }
     * then finds a call that should not have happened, failing the test.
     */
    @Test fun `swapApps is no-op while departure grace is active`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.nav", "pkg.music")
        helper.stubTask("pkg.nav", 11, 5, narrow)
        helper.stubTask("pkg.music", 10, 5, wide)
        coEvery { helper.getTopTask() } returns null
        coEvery { helper.getTopTaskPackage() } returns null
        coEvery { helper.getTopTaskPackageOrSkip() } returns null

        var fakeNow = 0L
        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope,
            tickDelayMs = 60_000, nowMs = { fakeNow },
        )
        mgr.start(SplitPair("pkg.nav", "pkg.music", SplitSide.RIGHT))
        runCurrent()

        // Arm departure grace for pkg.nav (narrow slot). deadline = 0 + DEPARTURE_GRACE_MS = 18000.
        mgr.beginClusterSend("pkg.nav")

        // Attempt swap while grace is active (fakeNow=0 < 18000).
        mgr.swapApps()
        runCurrent()

        // setTaskBounds should NOT have been called (swap was blocked by grace gate).
        coVerify(exactly = 0) { helper.setTaskBounds(any(), any(), any(), any(), any()) }
        // State must remain unchanged: pkg.nav still in narrow, pkg.music in wide.
        val state = mgr.state.value as? SplitSessionState.Active
        assertNotNull("session must still be Active after blocked swap", state)
        assertEquals("pkg.nav must still be in narrow slot", "pkg.nav", state?.pair?.narrowPkg)
    }

    // ── Codex fix-round 3: D-1 (mirror grace gate + calibration retry) ─────────

    /**
     * mirror() is a no-op while any departure grace is active (D-1).
     *
     * Anti-vacuity: removing `if (departureGraceDeadlines.values.any { nowMs() < it }) return@withLock`
     * from mirror() lets the mirror proceed; coVerify(exactly = 1) { setTaskBounds(11, wideB) }
     * then finds a call that stamped main-screen bounds onto a mid-REMOVE+RELAUNCH task, failing.
     */
    @Test fun `mirror is no-op while departure grace is active (D-1)`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.nav", "pkg.music")
        helper.stubTask("pkg.nav", 11, 5, narrow)
        helper.stubTask("pkg.music", 10, 5, wide)
        coEvery { helper.getTopTask() } returns null

        var fakeNow = 0L
        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope,
            tickDelayMs = 60_000, nowMs = { fakeNow },
        )
        mgr.start(SplitPair("pkg.nav", "pkg.music", SplitSide.RIGHT))
        runCurrent()

        // Arm departure grace for pkg.nav. deadline = 0 + 18000 = 18000. fakeNow=0 → grace active.
        mgr.beginClusterSend("pkg.nav")
        mgr.mirror()
        runCurrent()

        // setTaskBounds must not be called — mirror is blocked by the grace gate.
        coVerify(exactly = 0) { helper.setTaskBounds(any(), any(), any(), any(), any()) }
    }

    /**
     * When applyCalibratedBounds returns false on the first tick, the departed flag is NOT set
     * and the next tick retries calibration (D-1 / D-1-R1). PaneClosed is emitted only after
     * the retry succeeds. The hook returns Boolean — false = IPC failure, true = success.
     *
     * Anti-vacuity: removing `if (!calibrated) return false` keeps the old one-shot behaviour —
     * the flag is set on the first tick regardless of the result, so the second tick sees
     * alreadyDeparted=true, skips the block, and no second PaneClosed is emitted. That means
     * the `events.size == 1 after retry` assertion would still pass, BUT the assertion that
     * calibrate() is called TWICE would fail. More precisely: removing the early return causes
     * PaneClosed on the FIRST tick, so the `events after tick1 == 0` assertion fails.
     */
    @Test fun `calibration retries when applyCalibratedBounds returns false on first tick (D-1-R1)`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.nav", "pkg.music")
        helper.stubTask("pkg.nav", 11, 5, narrow)
        helper.stubTask("pkg.music", 10, 5, wide)
        coEvery { helper.getTopTask() } returns null
        coEvery { helper.getTopTaskPackage() } returns null
        coEvery { helper.getTopTaskPackageOrSkip() } returns null

        // applyCalibratedBounds: first call returns false (IPC failure), second returns true.
        // D-1-R1: the hook must return Boolean; false signals a retry, not a throw.
        var calibrateCount = 0
        val calibrate: suspend (Int, Int) -> Boolean = { _, _ ->
            calibrateCount++
            calibrateCount > 1  // false on first call, true thereafter
        }

        var fakeNow = 0L
        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope,
            tickDelayMs = 60_000, nowMs = { fakeNow },
            applyCalibratedBounds = calibrate,
        )
        mgr.start(SplitPair("pkg.nav", "pkg.music", SplitSide.RIGHT))
        runCurrent()

        val events = mutableListOf<SplitEvent>()
        val job = launch { mgr.events.collect { events.add(it) } }

        // Arm grace and present the departed task on the cluster (displayId=4) for tick 1.
        mgr.beginClusterSend("pkg.nav")
        coEvery { helper.getTaskState("pkg.nav") } returns
            SplitTaskState(11, 5, narrow.left, narrow.top, narrow.right, narrow.bottom, displayId = 4)

        // Tick 1: calibration returns false → departed flag NOT set → no PaneClosed.
        advanceTimeBy(60_001)
        runCurrent()
        assertEquals("PaneClosed must NOT be emitted when calibration returns false (D-1-R1)", 0, events.size)
        assertEquals("calibrate must have been called once on tick 1", 1, calibrateCount)

        // Tick 2: calibration returns true → departed flag set → PaneClosed emitted.
        advanceTimeBy(60_001)
        runCurrent()
        assertEquals("PaneClosed must be emitted after calibration retry succeeds (D-1-R1)", 1, events.size)
        assertTrue("emitted event must be PaneClosed for NARROW pane", events[0] is SplitEvent.PaneClosed)
        assertEquals(2, calibrateCount)

        job.cancel()
    }

    /**
     * A calibration that keeps failing is retried on every watchdog tick, but only the transition
     * into the retry state reaches the journal: each append re-reads, re-serialises and commits
     * the whole ring, and once a second of that is what the `(xN)` dedup hides but does not avoid.
     *
     * Anti-vacuity: journaling inside the failure branch instead of on the transition makes the
     * counted writes grow with the ticks (3 instead of 1).
     */
    @Test fun `a calibration stuck in retry journals once, not on every tick`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.nav", "pkg.music")
        helper.stubTask("pkg.nav", 11, 5, narrow)
        helper.stubTask("pkg.music", 10, 5, wide)
        coEvery { helper.getTopTask() } returns null
        coEvery { helper.getTopTaskPackage() } returns null
        coEvery { helper.getTopTaskPackageOrSkip() } returns null

        val store = CountingJournalStore()
        var fakeNow = 0L
        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope,
            tickDelayMs = 60_000, nowMs = { fakeNow },
            applyCalibratedBounds = { _, _ -> false },  // calibration never succeeds
            journal = SplitJournalImpl(store) { 1_700_000_000_000L },
        )
        mgr.start(SplitPair("pkg.nav", "pkg.music", SplitSide.RIGHT))
        runCurrent()

        mgr.beginClusterSend("pkg.nav")
        coEvery { helper.getTaskState("pkg.nav") } returns
            SplitTaskState(11, 5, narrow.left, narrow.top, narrow.right, narrow.bottom, displayId = 4)

        val writesBeforeTicks = store.writes
        repeat(3) {
            advanceTimeBy(60_001)
            runCurrent()
        }

        assertEquals(
            "three ticks in calibration-pending must produce exactly one journal write",
            1, store.writes - writesBeforeTicks,
        )
        assertTrue(
            "the single line must be the retry transition",
            store.value.trim().endsWith("display=4 calibration failed, retry"),
        )

        // Leaving the state is a transition too: the recovery line is written once.
        coEvery { helper.getTaskState("pkg.nav") } returns
            SplitTaskState(11, 5, narrow.left, narrow.top, narrow.right, narrow.bottom, displayId = 0)
        fakeNow = 100_000  // past the departure grace, so the else (return-to-main) branch runs
        advanceTimeBy(60_001)
        runCurrent()
        assertTrue(
            "leaving calibration-pending must be journalled",
            store.value.lines().last().endsWith("calibration pkg.nav abandoned: back on main display"),
        )
    }

    // ── Codex fix-round 3: D-2 (graceLock lost-update) ───────────────────────

    /**
     * changeApp(NARROW, newPkg) removes the NARROW pane's grace but must NOT destroy the
     * WIDE pane's simultaneously active grace deadline (D-2 functional invariant).
     *
     * Without [graceLock], a hypothetical concurrent beginClusterSend write for the WIDE pane
     * that races against changeApp's read-modify-write would be silently lost. This test
     * exercises the single-threaded functional invariant that establishes correctness even
     * without a real concurrent race: after replacing the narrow pane, the wide pane's grace
     * must still be present in the map.
     *
     * Anti-vacuity: if `synchronized(graceLock)` were removed from changeApp AND from
     * beginClusterSend, the functional test still passes in a single-threaded execution
     * (no real race). However, the race's impact can be approximated by performing both
     * operations in opposing order and verifying the map. The deterministic assertion here
     * is: both graces are set via beginClusterSend, then changeApp removes only oldPkg, and
     * the other pkg remains. Removing the synchronized blocks allows the operations to proceed
     * — so the test DOES pass without the lock in a single-threaded test. The fix prevents
     * the race in PRODUCTION; the test documents the functional contract enforced by the lock.
     */
    @Test fun `grace deadline for wide pane survives changeApp that replaces narrow pane (D-2)`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        val newPkg = "pkg.new"
        helper.stubLaunch("pkg.nav", "pkg.music", newPkg)
        helper.stubTask("pkg.nav", 11, 5, narrow)
        helper.stubTask("pkg.music", 10, 5, wide)
        helper.stubTask(newPkg, 12, 5, narrow)
        coEvery { helper.getTopTask() } returns null

        var fakeNow = 0L
        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope,
            tickDelayMs = 60_000, nowMs = { fakeNow },
        )
        mgr.start(SplitPair("pkg.nav", "pkg.music", SplitSide.RIGHT))
        runCurrent()

        // Arm grace for BOTH panes simultaneously (e.g. coordinated projection attempt).
        mgr.beginClusterSend("pkg.nav")    // narrow pane
        mgr.beginClusterSend("pkg.music")  // wide pane

        // Replace the narrow pane — should remove pkg.nav's grace, leave pkg.music's grace intact.
        mgr.changeApp(Pane.NARROW, newPkg)
        runCurrent()

        // Wide pane grace must survive the narrow changeApp.
        assertTrue(
            "pkg.music grace must survive changeApp(NARROW) (D-2 invariant)",
            mgr.departureGraceDeadlines.containsKey("pkg.music"),
        )
        assertFalse(
            "pkg.nav grace must be removed by changeApp(NARROW)",
            mgr.departureGraceDeadlines.containsKey("pkg.nav"),
        )
    }

    // ── Codex fix-round 3: D-3 (endClusterSend clears grace on UNAVAILABLE/FAILED) ─

    /**
     * endClusterSend() clears the departure grace immediately so swapApps is unblocked.
     * This models the UNAVAILABLE/FAILED path in ClusterProjectionManager (D-3): grace was
     * armed by beginClusterSend before launchFreeform, but the task was not moved, so the
     * 8-second window must not unnecessarily block the split session.
     *
     * Anti-vacuity: removing [SplitSessionManager.endClusterSend] (or not calling it from CPM)
     * leaves the grace active for 8 s. swapApps() is then a no-op (grace gate returns early)
     * and `coVerify(exactly = 1) { setTaskBounds(...) }` fails because setTaskBounds is never called.
     */
    @Test fun `endClusterSend clears grace and unblocks swapApps (D-3)`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.nav", "pkg.music")
        helper.stubTask("pkg.nav", 11, 5, narrow)
        helper.stubTask("pkg.music", 10, 5, wide)
        coEvery { helper.getTopTask() } returns null

        var fakeNow = 0L
        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope,
            tickDelayMs = 60_000, nowMs = { fakeNow },
        )
        mgr.start(SplitPair("pkg.nav", "pkg.music", SplitSide.RIGHT))
        runCurrent()

        // Arm grace (simulates onBeforeClusterSend). Grace is active.
        mgr.beginClusterSend("pkg.nav")

        // swapApps must be blocked while grace is active (pre-condition: grace gate works).
        mgr.swapApps()
        runCurrent()
        coVerify(exactly = 0) { helper.setTaskBounds(any(), any(), any(), any(), any()) }

        // Now simulate the UNAVAILABLE/FAILED path: clear grace immediately (D-3).
        mgr.endClusterSend("pkg.nav")

        // swapApps must proceed now that grace is cleared.
        mgr.swapApps()
        runCurrent()
        // After a successful swap, setTaskBounds is called for BOTH tasks (2 calls).
        coVerify(atLeast = 1) { helper.setTaskBounds(any(), any(), any(), any(), any()) }
    }

    // ── W6-F1: restart with pane apps still alive in the background ───────────
    //
    // After a COVERED teardown the pane apps keep running as background tasks. On the next
    // start the daemon's launchFreeform path resolves those live (recents-leaf) task ids and
    // hands them to moveRootTaskToDisplay/setFocusedRootTask, which ATMS rejects
    // ("moveRootTaskToTaskDisplayArea: Unknown rootTaskId") — the panes stay behind the
    // backdrop: black backdrop + pill, no panes (on-car 389).

    /**
     * Anti-vacuity: reverting placePaneLocked to the plain `helper.launchFreeform` call (the
     * move-TX path) makes `coVerify(exactly = 0) { launchFreeform(...) }` fail.
     */
    @Test fun `start raises existing background panes via raiseFreeformTask instead of launchFreeform`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val backdrop = FakeSplitBackdrop()
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        // Both pane apps survived a previous COVERED teardown: tasks alive, freeform, display 0.
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        // launchFreeform would report OK — it must not be used on this path at all.
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        coEvery { helper.raiseFreeformTaskDetailed(any(), any(), any()) } returns RaiseOutcome.RAISED

        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), backdrop, backgroundScope, 60_000,
            paneTypePolicy = standardPanes,
        )
        val pair = SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT)

        val result = mgr.start(pair)

        assertEquals(SplitStartResult.OK, result)
        assertEquals(SplitSessionState.Active(pair, 11, 10), mgr.state.value)
        assertEquals(true, backdrop.shown)
        // Task N mechanism (am start --windowingMode 5 --activityType 1 --display 0 -n <cmp>).
        coVerify(exactly = 1) {
            helper.raiseFreeformTaskDetailed("pkg.wide", 0, HelperBinderProtocol.PANE_TYPE_STANDARD)
        }
        coVerify(exactly = 1) {
            helper.raiseFreeformTaskDetailed("pkg.narrow", 0, HelperBinderProtocol.PANE_TYPE_STANDARD)
        }
        // move-TX path (daemon moveRootTaskToDisplay + setFocusedRootTask by leaf id) must not run.
        coVerify(exactly = 0) { helper.launchFreeform(any(), any(), any(), any(), any(), any(), any()) }
        // Pane geometry is stamped explicitly on the raise path.
        coVerify { helper.setTaskBounds(10, wide.left, wide.top, wide.right, wide.bottom) }
        coVerify { helper.setTaskBounds(11, narrow.left, narrow.top, narrow.right, narrow.bottom) }
    }

    /**
     * Anti-vacuity: raising PANE_RAISE_MAX_ATTEMPTS (e.g. to 5) makes
     * `coVerify(exactly = 2) { raiseFreeformTask(...) }` fail; removing the bound entirely
     * (`while (true)`) hangs the test — the retry loop must be finite.
     */
    @Test fun `start gives up raising after a bounded number of attempts, relaunches, then ends the session`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val backdrop = FakeSplitBackdrop()
        val (wide, _) = boundsFor(SplitSide.RIGHT)
        // Wide pane task exists in freeform (so forceStopIfNeeded fast-paths and the raise path is
        // entered), but the raise never lands: every post-raise read still shows fullscreen.
        val liveFreeform = SplitTaskState(10, 5, wide.left, wide.top, wide.right, wide.bottom)
        val neverRaised = SplitTaskState(10, 1, wide.left, wide.top, wide.right, wide.bottom)
        coEvery { helper.getTaskState("pkg.wide") } returnsMany listOf(liveFreeform, liveFreeform, neverRaised)
        coEvery { helper.raiseFreeformTaskDetailed(any(), any(), any()) } returns RaiseOutcome.RAISED
        coEvery { helper.forceStop(any()) } returns true
        // Fresh relaunch also fails → the session must end honestly instead of going zombie.
        helper.stubLaunch("pkg.wide", result = FreeformLaunchResult.FAILED)

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), backdrop, backgroundScope, 60_000)

        mgr.events.test {
            val result = mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))
            assertEquals(SplitStartResult.LAUNCH_FAILED, result)
            assertEquals(SplitEvent.SessionEnded(EndReason.EXIT), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(SplitSessionState.Idle, mgr.state.value)
        assertEquals(false, backdrop.shown)
        // Finite retry loop, then ONE fresh relaunch (force-stop + launch).
        coVerify(exactly = 2) { helper.raiseFreeformTaskDetailed("pkg.wide", 0, any()) }
        coVerify(exactly = 1) { helper.forceStop("pkg.wide") }
        coVerify(exactly = 1) {
            helper.launchFreeform("pkg.wide", 0, wide.left, wide.top, wide.right, wide.bottom, any())
        }
    }

    /**
     * Zombie session: state is Active but the pane apps are gone, so every task op fails.
     * exit() must still complete the full cleanup (this is the foundation for the R2 toggle).
     *
     * Anti-vacuity: dropping the runCatching wrappers in tearDownLocked lets the
     * IllegalStateException propagate out of exit() — the test fails with that exception and
     * the session stays Active with the backdrop up.
     */
    @Test fun `exit tears down a zombie session even when every task op throws`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val backdrop = FakeSplitBackdrop()
        val mediaSource = mockk<MediaSessionSource>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)

        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), backdrop, backgroundScope,
            tickDelayMs = 60_000, mediaSource = mediaSource, mediaPollDelayMs = 50L,
        )
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))
        runCurrent()
        // Arm a departure grace so the "grace markers cleared" assertion below is not vacuous.
        mgr.beginClusterSend("pkg.narrow")
        assertTrue(mgr.departureGraceDeadlines.isNotEmpty())

        // Pane apps died: tasks are gone and every task op throws on the dead ids.
        coEvery { helper.getTaskState(any()) } returns SplitTaskState(-1, 0, 0, 0, 0, 0)
        coEvery { helper.setTaskWindowingMode(any(), any(), any()) } throws IllegalStateException("dead task")
        coEvery { helper.setFocusedTask(any()) } throws IllegalStateException("dead task")

        mgr.events.test {
            mgr.exit()
            assertEquals(SplitEvent.SessionEnded(EndReason.EXIT), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(SplitSessionState.Idle, mgr.state.value)
        assertEquals(false, backdrop.shown)
        // Grace/pending markers are cleared. calibrationPendingPkgs is private with no test hook,
        // so it cannot be asserted directly; it is mutated in the same synchronized(graceLock)
        // statement as departureGraceDeadlines in tearDownLocked, so this assertion covers both.
        assertTrue(mgr.departureGraceDeadlines.isEmpty())

        // Watchdog AND media poll are cancelled: no further polling, no resurrection.
        clearMocks(helper, answers = false)
        advanceTimeBy(180_000)
        runCurrent()
        coVerify(exactly = 0) { helper.getTaskState(any()) }
        coVerify(exactly = 0) { helper.getTopTaskPackageOrSkip() }
        coVerify(exactly = 0) { helper.getTopTaskPackage() }
        assertEquals(SplitSessionState.Idle, mgr.state.value)
    }

    /**
     * FIX round 3: forceStop itself failed and the task is still alive. Launching now would hand
     * launchFreeform the SAME live recents leaf that the raise could not lift — the silent false OK
     * this whole fix exists to prevent. The pane must fail honestly instead.
     *
     * Anti-vacuity: ignoring the forceStop result makes launchFreeform run and the coVerify below
     * fails.
     */
    @Test fun `start fails the pane when forceStop fails and the task survives`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val backdrop = FakeSplitBackdrop()
        val (wide, _) = boundsFor(SplitSide.RIGHT)
        helper.stubTask("pkg.wide", 10, 5, wide)
        coEvery { helper.raiseFreeformTaskDetailed("pkg.wide", 0, any()) } returns RaiseOutcome.NO_REPLY
        helper.stubLaunch("pkg.wide")
        coEvery { helper.forceStop("pkg.wide") } returns false

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), backdrop, backgroundScope, 60_000)
        val result = mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        assertEquals(SplitStartResult.LAUNCH_FAILED, result)
        coVerify(exactly = 1) { helper.forceStop("pkg.wide") }
        coVerify(exactly = 0) { helper.launchFreeform("pkg.wide", any(), any(), any(), any(), any(), any()) }
    }

    /**
     * FIX round 3, other side: forceStop reported false but the re-read shows the task is gone
     * anyway (the kill landed, only the reply did not). Nothing live is left to collide with, so the
     * fresh launch proceeds — a false forceStop must not become a hard block on its own.
     */
    @Test fun `start still launches when forceStop reports false but the task is gone`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val backdrop = FakeSplitBackdrop()
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        val liveWide = SplitTaskState(10, 5, wide.left, wide.top, wide.right, wide.bottom)
        // forceStopIfNeeded and the placePane classification see the live task; the post-forceStop
        // re-read positively reports "no task" (taskId == -1, NOT null — null means the daemon was
        // unreachable); the post-launch read reports the placed pane.
        val goneWide = SplitTaskState(-1, 0, 0, 0, 0, 0)
        coEvery { helper.getTaskState("pkg.wide") } returnsMany listOf(liveWide, liveWide, goneWide, liveWide)
        // The narrow pane has no task at all — plain clean start, untouched by this path.
        coEvery { helper.getTaskState("pkg.narrow") } returnsMany listOf(
            null, null, SplitTaskState(11, 5, narrow.left, narrow.top, narrow.right, narrow.bottom),
        )
        coEvery { helper.raiseFreeformTaskDetailed(any(), any(), any()) } returns RaiseOutcome.NO_REPLY
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        coEvery { helper.forceStop(any()) } returns false

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), backdrop, backgroundScope, 60_000)
        val result = mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        assertEquals(SplitStartResult.OK, result)
        coVerify(exactly = 1) {
            helper.launchFreeform("pkg.wide", 0, wide.left, wide.top, wide.right, wide.bottom, any())
        }
    }

    /**
     * FIX round 4: forceStop failed AND the task state came back null. Per HelperClient's contract
     * null means "the daemon was unreachable / the call failed" — not "no task". That is exactly
     * the unreachable-daemon scenario the guard exists for: the next launchFreeform could reconnect
     * to a revived daemon and meet the same live recents leaf. So null is terminal.
     *
     * Anti-vacuity: restoring the old `survivor != null && survivor.taskId != -1` condition lets
     * the launch run and the coVerify below fails.
     */
    @Test fun `start fails the pane when forceStop fails and the task state is unreadable`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val backdrop = FakeSplitBackdrop()
        val (wide, _) = boundsFor(SplitSide.RIGHT)
        val liveWide = SplitTaskState(10, 5, wide.left, wide.top, wide.right, wide.bottom)
        // Classification sees the live task; the post-forceStop re-read is unreadable (null).
        coEvery { helper.getTaskState("pkg.wide") } returnsMany listOf(liveWide, liveWide, null)
        coEvery { helper.raiseFreeformTaskDetailed("pkg.wide", 0, any()) } returns RaiseOutcome.NO_REPLY
        helper.stubLaunch("pkg.wide")
        coEvery { helper.forceStop("pkg.wide") } returns false

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), backdrop, backgroundScope, 60_000)
        val result = mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        assertEquals(SplitStartResult.LAUNCH_FAILED, result)
        coVerify(exactly = 0) { helper.launchFreeform("pkg.wide", any(), any(), any(), any(), any(), any()) }
    }

    /**
     * FIX-A (1): the daemon REFUSES the raise on the first attempt (it handled the TX but `am start`
     * did not run cleanly). Handing the same recents-leaf task to launchFreeform would reproduce
     * the silent false OK, so the pane must be force-stopped and launched fresh.
     *
     * Anti-vacuity: treating REFUSED as "unsupported → plain launchFreeform" skips the force-stop
     * and the coVerify below fails.
     */
    @Test fun `start force-stops and relaunches when the daemon refuses the raise`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val backdrop = FakeSplitBackdrop()
        val (wide, _) = boundsFor(SplitSide.RIGHT)
        helper.stubTask("pkg.wide", 10, 5, wide)
        coEvery { helper.raiseFreeformTaskDetailed("pkg.wide", 0, any()) } returns RaiseOutcome.REFUSED
        coEvery { helper.forceStop(any()) } returns true
        helper.stubLaunch("pkg.wide", result = FreeformLaunchResult.FAILED)

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), backdrop, backgroundScope, 60_000)
        val result = mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        assertEquals(SplitStartResult.LAUNCH_FAILED, result)
        // One attempt only: REFUSED is terminal, no retry storm.
        coVerify(exactly = 1) { helper.raiseFreeformTaskDetailed("pkg.wide", 0, any()) }
        coVerify(exactly = 1) { helper.forceStop("pkg.wide") }
        coVerify(exactly = 1) {
            helper.launchFreeform("pkg.wide", 0, wide.left, wide.top, wide.right, wide.bottom, any())
        }
    }

    /**
     * FIX-A (2): NO_REPLY — the daemon never answered (unknown TX on a build predating Task N,
     * timeout, dead binder). The old "386 parity: plain launchFreeform, no force-stop" branch was
     * removed on purpose: that parity preserved exactly the broken path (launchFreeform on a live
     * recents leaf = silent false OK). Force-stop + fresh launch works on every daemon generation.
     *
     * Anti-vacuity: restoring the unsupported branch makes the force-stop coVerify fail.
     */
    @Test fun `start force-stops and relaunches when the raise gets no reply`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val backdrop = FakeSplitBackdrop()
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        coEvery { helper.raiseFreeformTaskDetailed(any(), any(), any()) } returns RaiseOutcome.NO_REPLY
        coEvery { helper.forceStop(any()) } returns true

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), backdrop, backgroundScope, 60_000)
        val pair = SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT)

        val result = mgr.start(pair)

        assertEquals(SplitStartResult.OK, result)
        assertEquals(SplitSessionState.Active(pair, 11, 10), mgr.state.value)
        // One attempt per pane (NO_REPLY is terminal), then force-stop + fresh launch for both.
        coVerify(exactly = 1) { helper.raiseFreeformTaskDetailed("pkg.wide", 0, any()) }
        coVerify(exactly = 1) { helper.raiseFreeformTaskDetailed("pkg.narrow", 0, any()) }
        coVerify(exactly = 1) { helper.forceStop("pkg.wide") }
        coVerify(exactly = 1) { helper.forceStop("pkg.narrow") }
        coVerify(exactly = 1) {
            helper.launchFreeform("pkg.wide", 0, wide.left, wide.top, wide.right, wide.bottom, any())
        }
        coVerify(exactly = 1) {
            helper.launchFreeform("pkg.narrow", 0, narrow.left, narrow.top, narrow.right, narrow.bottom, any())
        }
    }

    /**
     * FIX-2 / FIX-B (no hook wired): the pane app is on the cluster (displayId 2) when the session
     * starts. raiseFreeformTask must NOT be used (`am start --display 0` would yank the task off the
     * cluster), and the task must not be handed to launchFreeform as-is (its move-by-leaf-id failure
     * path resolves to `am stack remove` + relaunch, i.e. the same eviction, non-deterministically).
     * Deterministic force-stop + fresh launch instead.
     */
    @Test fun `start force-stops and relaunches a pane whose task sits on the cluster display`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val backdrop = FakeSplitBackdrop()
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        // Wide pane was sent to the cluster earlier. Narrow has no task until it is launched
        // (clean start): absent for the forceStopIfNeeded and placePane reads, live for the
        // task-id resolution that follows the launch.
        coEvery { helper.getTaskState("pkg.wide") } returns
            SplitTaskState(10, 5, 0, 0, 1280, 480, displayId = 2)
        coEvery { helper.getTaskState("pkg.narrow") } returnsMany listOf(
            SplitTaskState(-1, 0, 0, 0, 0, 0),
            SplitTaskState(-1, 0, 0, 0, 0, 0),
            SplitTaskState(11, 5, narrow.left, narrow.top, narrow.right, narrow.bottom),
        )
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        coEvery { helper.raiseFreeformTaskDetailed(any(), any(), any()) } returns RaiseOutcome.RAISED
        coEvery { helper.forceStop(any()) } returns true

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), backdrop, backgroundScope, 60_000)
        val result = mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        assertEquals(SplitStartResult.OK, result)
        coVerify(exactly = 1) { helper.forceStop("pkg.wide") }
        coVerify(exactly = 1) {
            helper.launchFreeform("pkg.wide", 0, wide.left, wide.top, wide.right, wide.bottom, any())
        }
        coVerify(exactly = 0) { helper.raiseFreeformTaskDetailed("pkg.wide", 0, any()) }
        // The clean-start pane is untouched by both the cluster and the raise branch.
        coVerify(exactly = 0) { helper.forceStop("pkg.narrow") }
        coVerify(exactly = 0) { helper.raiseFreeformTaskDetailed("pkg.narrow", 0, any()) }
    }

    /**
     * FIX round 4 (integration): the whole thing through start(), not by calling placePaneLocked
     * directly. A pane app sitting FULLSCREEN on the cluster must reach the cluster branch — the
     * endClusterProjection hook has to run BEFORE anything touches the task. Previously
     * forceStopIfNeeded flipped the task to freeform first, and the daemon's compat path dragged it
     * home to display 0, so placePane classified it as an ordinary restart and the hook never ran:
     * CPM kept reporting FULLSCREEN with an orphaned overlay/VirtualDisplay on the cluster.
     *
     * Anti-vacuity: removing the display gate from forceStopIfNeeded makes setTaskWindowingMode run
     * before the hook and coVerifyOrder below fails.
     */
    @Test fun `start runs the cluster hook before touching a fullscreen task on the cluster`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val backdrop = FakeSplitBackdrop()
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        // Wide app is projected on the cluster: FULLSCREEN on display 2.
        coEvery { helper.getTaskState("pkg.wide") } returns SplitTaskState(10, 1, 0, 0, 1280, 480, displayId = 2)
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        coEvery { helper.raiseFreeformTaskDetailed(any(), any(), any()) } returns RaiseOutcome.RAISED

        val hookCalls = mutableListOf<String>()
        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), backdrop, backgroundScope, tickDelayMs = 60_000,
            endClusterProjection = { pkg -> hookCalls += pkg; false },
            paneTypePolicy = standardPanes,
        )
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        assertEquals("Cluster hook must run for the projected pane", listOf("pkg.wide"), hookCalls)
        // Nothing touched the cluster task before the hook decided what to do with it.
        coVerify(exactly = 0) { helper.setTaskWindowingMode(10, any(), any()) }
        // Hook said "not ours" → deterministic force-stop + fresh launch, as in the unit test.
        coVerify(exactly = 1) { helper.forceStop("pkg.wide") }
        coVerify(exactly = 1) {
            helper.launchFreeform(
                "pkg.wide", 0, wide.left, wide.top, wide.right, wide.bottom,
                HelperBinderProtocol.PANE_TYPE_STANDARD,
            )
        }
    }

    /**
     * FIX round 4, Bug A regression guard: a stale FULLSCREEN task on display 0 must still get the
     * gentle mode flip before the launch — the display gate must not disarm that path.
     */
    @Test fun `start still mode-flips a stale fullscreen task on the main display`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val backdrop = FakeSplitBackdrop()
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        val stale = SplitTaskState(10, 1, 0, 0, 1920, 1080, displayId = 0)
        val flipped = SplitTaskState(10, 5, wide.left, wide.top, wide.right, wide.bottom, displayId = 0)
        // forceStopIfNeeded: read (stale) → flip → re-read (flipped); then placePane sees freeform.
        coEvery { helper.getTaskState("pkg.wide") } returnsMany listOf(stale, flipped, flipped)
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        coEvery { helper.raiseFreeformTaskDetailed(any(), any(), any()) } returns RaiseOutcome.RAISED

        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), backdrop, backgroundScope, 60_000,
            paneTypePolicy = standardPanes,
        )
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        coVerify(exactly = 1) { helper.setTaskWindowingMode(10, 5, HelperBinderProtocol.PANE_TYPE_STANDARD) }
        // The flip landed, so no force-stop of the app process.
        coVerify(exactly = 0) { helper.forceStop("pkg.wide") }
    }

    /**
     * FIX-B: the pane app is on the cluster AND it is OUR projection. The endClusterProjection hook
     * ends that projection through the ordinary OFF sequence; the task comes home to display 0, so
     * the pane goes down the normal raise path — no force-stop, the app process survives.
     *
     * Anti-vacuity: removing the hook call leaves the task classified as "on the cluster" and the
     * force-stop coVerify below fails.
     */
    @Test fun `start ends our cluster projection and raises the pane when the task comes home`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val backdrop = FakeSplitBackdrop()
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        // Read 1 (forceStopIfNeeded) + read 2 (placePane classification): on the cluster.
        // Read 3 (after the projection ended) and later: back home on display 0.
        val onCluster = SplitTaskState(10, 5, 0, 0, 1280, 480, displayId = 2)
        val atHome = SplitTaskState(10, 5, wide.left, wide.top, wide.right, wide.bottom, displayId = 0)
        coEvery { helper.getTaskState("pkg.wide") } returnsMany listOf(onCluster, onCluster, atHome)
        coEvery { helper.getTaskState("pkg.narrow") } returns
            SplitTaskState(11, 5, narrow.left, narrow.top, narrow.right, narrow.bottom)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        coEvery { helper.raiseFreeformTaskDetailed(any(), any(), any()) } returns RaiseOutcome.RAISED
        coEvery { helper.forceStop(any()) } returns true

        val ended = mutableListOf<String>()
        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), backdrop, backgroundScope, tickDelayMs = 60_000,
            endClusterProjection = { pkg -> ended += pkg; true },
        )
        val pair = SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT)

        val result = mgr.start(pair)

        assertEquals(SplitStartResult.OK, result)
        assertEquals(listOf("pkg.wide"), ended)
        // Projection ended → task at home → raise path, app process preserved.
        coVerify(exactly = 1) { helper.raiseFreeformTaskDetailed("pkg.wide", 0, any()) }
        coVerify(exactly = 0) { helper.forceStop("pkg.wide") }
        coVerify(exactly = 0) { helper.launchFreeform("pkg.wide", any(), any(), any(), any(), any(), any()) }
    }

    /**
     * FIX-B: the hook reports false (the running projection belongs to another package, or none is
     * running) → the deterministic force-stop + fresh launch path, exactly as with no hook wired.
     */
    @Test fun `start force-stops the cluster pane when the projection is not ours`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val backdrop = FakeSplitBackdrop()
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        coEvery { helper.getTaskState("pkg.wide") } returns
            SplitTaskState(10, 5, 0, 0, 1280, 480, displayId = 2)
        coEvery { helper.getTaskState("pkg.narrow") } returns
            SplitTaskState(11, 5, narrow.left, narrow.top, narrow.right, narrow.bottom)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        coEvery { helper.raiseFreeformTaskDetailed(any(), any(), any()) } returns RaiseOutcome.RAISED
        coEvery { helper.forceStop(any()) } returns true

        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), backdrop, backgroundScope, tickDelayMs = 60_000,
            endClusterProjection = { false },
        )
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        coVerify(exactly = 1) { helper.forceStop("pkg.wide") }
        coVerify(exactly = 1) {
            helper.launchFreeform("pkg.wide", 0, wide.left, wide.top, wide.right, wide.bottom, any())
        }
        coVerify(exactly = 0) { helper.raiseFreeformTaskDetailed("pkg.wide", 0, any()) }
    }

    /**
     * FIX-B: a throwing hook must not abort the start (fail-soft) — the cluster pane still gets the
     * deterministic force-stop + fresh launch.
     */
    @Test fun `start survives a throwing endClusterProjection hook and relaunches the cluster pane`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val backdrop = FakeSplitBackdrop()
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        coEvery { helper.getTaskState("pkg.wide") } returns
            SplitTaskState(10, 5, 0, 0, 1280, 480, displayId = 2)
        coEvery { helper.getTaskState("pkg.narrow") } returns
            SplitTaskState(11, 5, narrow.left, narrow.top, narrow.right, narrow.bottom)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        coEvery { helper.raiseFreeformTaskDetailed(any(), any(), any()) } returns RaiseOutcome.RAISED
        coEvery { helper.forceStop(any()) } returns true

        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), backdrop, backgroundScope, tickDelayMs = 60_000,
            endClusterProjection = { throw IllegalStateException("cluster manager blew up") },
        )
        val result = mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        assertEquals(SplitStartResult.OK, result)
        coVerify(exactly = 1) { helper.forceStop("pkg.wide") }
        coVerify(exactly = 1) {
            helper.launchFreeform("pkg.wide", 0, wide.left, wide.top, wide.right, wide.bottom, any())
        }
    }

    /**
     * FIX-C: the daemon reports Display.INVALID_DISPLAY (-1) — a mid-reparent transient. The pane
     * state is re-read a bounded number of times; once it resolves to display 0 the normal raise
     * path runs (no force-stop).
     *
     * Anti-vacuity: removing the re-read loop classifies -1 immediately and the pane is
     * force-stopped instead — the coVerify below fails.
     */
    @Test fun `start re-reads a pane reporting INVALID_DISPLAY and raises it once it settles`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val backdrop = FakeSplitBackdrop()
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        val reparenting = SplitTaskState(10, 5, wide.left, wide.top, wide.right, wide.bottom, displayId = -1)
        val settled = SplitTaskState(10, 5, wide.left, wide.top, wide.right, wide.bottom, displayId = 0)
        // forceStopIfNeeded, placePane classification (-1), one re-read (-1), second re-read (0).
        coEvery { helper.getTaskState("pkg.wide") } returnsMany
            listOf(reparenting, reparenting, reparenting, settled)
        coEvery { helper.getTaskState("pkg.narrow") } returns
            SplitTaskState(11, 5, narrow.left, narrow.top, narrow.right, narrow.bottom)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        coEvery { helper.raiseFreeformTaskDetailed(any(), any(), any()) } returns RaiseOutcome.RAISED
        coEvery { helper.forceStop(any()) } returns true

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), backdrop, backgroundScope, 60_000)
        val result = mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        assertEquals(SplitStartResult.OK, result)
        coVerify(exactly = 1) { helper.raiseFreeformTaskDetailed("pkg.wide", 0, any()) }
        coVerify(exactly = 0) { helper.forceStop("pkg.wide") }
        coVerify(exactly = 0) { helper.launchFreeform("pkg.wide", any(), any(), any(), any(), any(), any()) }
    }

    /**
     * FIX-C: the display never resolves within the bounded re-reads → conservative force-stop +
     * fresh launch. The live task is never handed to launchFreeform (that is the silent-false-OK
     * path this whole fix exists to avoid).
     */
    @Test fun `start relaunches a pane whose display never resolves`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val backdrop = FakeSplitBackdrop()
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        coEvery { helper.getTaskState("pkg.wide") } returns
            SplitTaskState(10, 5, wide.left, wide.top, wide.right, wide.bottom, displayId = -1)
        coEvery { helper.getTaskState("pkg.narrow") } returns
            SplitTaskState(11, 5, narrow.left, narrow.top, narrow.right, narrow.bottom)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        coEvery { helper.raiseFreeformTaskDetailed(any(), any(), any()) } returns RaiseOutcome.RAISED
        coEvery { helper.forceStop(any()) } returns true

        val mgr = SplitSessionManager(helper, FakeSplitPreferences(), backdrop, backgroundScope, 60_000)
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        coVerify(exactly = 1) { helper.forceStop("pkg.wide") }
        coVerify(exactly = 1) {
            helper.launchFreeform("pkg.wide", 0, wide.left, wide.top, wide.right, wide.bottom, any())
        }
        coVerify(exactly = 0) { helper.raiseFreeformTaskDetailed("pkg.wide", 0, any()) }
    }

    /**
     * FIX-D: a failed calibration arms calibrationPendingPkgs, which blocks mirror()/swapApps().
     * Both tearDownLocked and start() clear the marker; this test pins the full user-visible
     * round trip — blocked in session A, unblocked in session B — which the existing D-1-R3 test
     * does not (it asserts only the session-B side).
     *
     * Observability note: because start() clears the same marker, no test that goes through the
     * public API can attribute the clean-up to tearDownLocked alone — a mutation that removes
     * `calibrationPendingPkgs = emptySet()` from tearDownLocked cannot be killed without reaching
     * into private state. Recorded honestly in the report rather than papered over.
     */
    @Test fun `calibration-pending blocks swapApps in session A and is cleared for session B`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        coEvery { helper.getTopTaskPackage() } returns null
        coEvery { helper.getTopTaskPackageOrSkip() } returns null

        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope,
            tickDelayMs = 100,
            // Calibration always fails → the departed pane arms calibrationPendingPkgs.
            applyCalibratedBounds = { _, _ -> false },
        )
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        // Narrow pane departs to the cluster; the watchdog tick arms the pending marker.
        coEvery { helper.getTaskState("pkg.narrow") } returns
            SplitTaskState(taskId = 11, windowingMode = 5, 0, 0, 1280, 480, displayId = 2)
        advanceTimeBy(150); runCurrent()

        // Pre-condition: swapApps is blocked while calibration is pending.
        clearMocks(helper, answers = false)
        mgr.swapApps()
        runCurrent()
        coVerify(exactly = 0) { helper.setTaskBounds(any(), any(), any(), any(), any()) }

        mgr.exit()
        runCurrent()

        // New session with healthy panes: swapApps must not be blocked by the old marker.
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))
        advanceTimeBy(50); runCurrent()
        clearMocks(helper, answers = false)
        mgr.swapApps()
        runCurrent()
        coVerify(atLeast = 1) { helper.setTaskBounds(any(), any(), any(), any(), any()) }
    }
}
