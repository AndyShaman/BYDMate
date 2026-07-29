package com.bydmate.app.split

import com.bydmate.app.data.vehicle.FreeformLaunchResult
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.SplitTaskState
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// ─── Pure-function tests for shouldReroute ───────────────────────────────────

/** Tests for the [shouldReroute] pure trigger function (no coroutines, no mocks). */
class MediaRerouterLogicTest {

    private val panePkgs = setOf("com.yandex.music", "ru.yandex.yandexnavi")
    private val sessionPkgs = setOf("com.yandex.music")

    @Test fun `returns false when topPkg is null (mediacenter not the foreground task)`() {
        assertFalse(
            shouldReroute(
                topPkg = null,
                splitActive = true, panePkgs = panePkgs, sessionPkgs = sessionPkgs,
                stoodDown = false,
            )
        )
    }

    @Test fun `returns false when topPkg is empty string (no top task)`() {
        assertFalse(
            shouldReroute(
                topPkg = "",
                splitActive = true, panePkgs = panePkgs, sessionPkgs = sessionPkgs,
                stoodDown = false,
            )
        )
    }

    @Test fun `returns false when topPkg is some other app (not mediacenter)`() {
        assertFalse(
            shouldReroute(
                topPkg = "com.android.launcher3",
                splitActive = true, panePkgs = panePkgs, sessionPkgs = sessionPkgs,
                stoodDown = false,
            )
        )
    }

    @Test fun `returns false when mediacenter is itself a pane package (I5 guard)`() {
        // User intentionally launched mediacenter in a split pane — do not reroute.
        assertFalse(
            shouldReroute(
                topPkg = MEDIACENTER_PKG,
                splitActive = true,
                panePkgs = setOf(MEDIACENTER_PKG, "ru.yandex.yandexnavi"),
                sessionPkgs = setOf(MEDIACENTER_PKG),
                stoodDown = false,
            )
        )
    }

    @Test fun `returns false when split session is not Active`() {
        assertFalse(
            shouldReroute(
                topPkg = MEDIACENTER_PKG,
                splitActive = false, panePkgs = panePkgs, sessionPkgs = sessionPkgs,
                stoodDown = false,
            )
        )
    }

    @Test fun `returns false when no pane package has an active session`() {
        assertFalse(
            shouldReroute(
                topPkg = MEDIACENTER_PKG,
                splitActive = true,
                panePkgs = panePkgs,
                sessionPkgs = setOf("com.spotify.music"), // neither pane
                stoodDown = false,
            )
        )
    }

    @Test fun `returns false when session package set is empty`() {
        assertFalse(
            shouldReroute(
                topPkg = MEDIACENTER_PKG,
                splitActive = true, panePkgs = panePkgs, sessionPkgs = emptySet(),
                stoodDown = false,
            )
        )
    }

    @Test fun `returns false when stand-down latch is engaged`() {
        assertFalse(
            shouldReroute(
                topPkg = MEDIACENTER_PKG,
                splitActive = true, panePkgs = panePkgs, sessionPkgs = sessionPkgs,
                stoodDown = true,
            )
        )
    }

    @Test fun `returns true when all conditions met and latch not engaged`() {
        assertTrue(
            shouldReroute(
                topPkg = MEDIACENTER_PKG,
                splitActive = true, panePkgs = panePkgs, sessionPkgs = sessionPkgs,
                stoodDown = false,
            )
        )
    }

    @Test fun `pane match checks both pane packages against session packages`() {
        // Only the wide pane (second element) has a session — still triggers.
        assertTrue(
            shouldReroute(
                topPkg = MEDIACENTER_PKG,
                splitActive = true,
                panePkgs = setOf("com.example.narrow", "com.yandex.music"),
                sessionPkgs = setOf("com.yandex.music"),
                stoodDown = false,
            )
        )
    }
}

// ─── Fake implementations for integration tests ───────────────────────────────

/** Fake [MediaSessionSource] with configurable session packages and dispatch tracking. */
private class FakeMediaSessionSource(
    private var sessionPkgs: Set<String> = emptySet(),
    /** When non-null, findController throws this instead of returning a controller. */
    private val throwOnFind: Exception? = null,
    /** When non-null, activeSessionPackages throws this (simulates revoked permission). */
    private val throwOnPackages: Exception? = null,
) : MediaSessionSource {

    data class DispatchRecord(val packageName: String)
    val dispatched = mutableListOf<DispatchRecord>()

    /** N4: call counter for activeSessionPackages() — pins the I4 gating property. */
    var activeSessionPackagesCalls = 0
        private set

    fun setSessionPkgs(pkgs: Set<String>) { sessionPkgs = pkgs }

    override fun activeSessionPackages(): Set<String> {
        activeSessionPackagesCalls++
        throwOnPackages?.let { throw it }
        return sessionPkgs
    }

    override fun findController(panePkgs: Set<String>): MediaControllerHandle? {
        throwOnFind?.let { throw it }
        val pkg = panePkgs.firstOrNull { it in sessionPkgs } ?: return null
        return object : MediaControllerHandle {
            override val packageName = pkg
            override fun dispatchPlayPause() { dispatched.add(DispatchRecord(pkg)) }
        }
    }
}

// ─── Integration tests through SplitSessionManager ───────────────────────────

// DUAL-STUB RULE for editors of this file:
// reactToMediaCenterLocked() performs TWO helper calls for every reroute candidate:
//   1. getTopTaskPackageOrSkip() — pre-check OUTSIDE the session mutex (fast path)
//   2. getTopTaskPackage()       — re-validation INSIDE the session mutex (stale-guard)
// Any test whose subject (the condition being verified) is reached ONLY AFTER the in-lock
// re-read (e.g. shouldReroute gates, permission exceptions, pane guards) MUST stub BOTH
// variants. Stubbing only getTopTaskPackageOrSkip() leaves the in-lock getTopTaskPackage()
// returning null on the relaxed mock, causing an early return before the condition under test
// is ever evaluated — the test passes vacuously and proves nothing.

@OptIn(ExperimentalCoroutinesApi::class)
class MediaKeyRerouterIntegrationTest {

    // ── Helpers from SplitSessionManagerTest ──────────────────────────────────

    private class FakeSplitPreferences : SplitPreferences {
        private var saved: SplitPair? = null
        override fun getLastPair() = saved
        override fun saveLastPair(pair: SplitPair) { saved = pair }
        override fun clearLastPair() { saved = null }
        override fun isFeatureEnabled() = true
        override fun setFeatureEnabled(enabled: Boolean) {}
    }

    private class FakeSplitBackdrop : SplitBackdrop {
        var shown = false
        override suspend fun show(): Boolean { shown = true; return true }
        override fun hide() { shown = false }
    }

    private fun HelperClient.stubLaunch(vararg pkgs: String) {
        // A healthy daemon can also kill apps. Without this a relaxed mock returns false from
        // forceStop and the restart path added in W6-F1 bails out instead of placing the pane.
        coEvery { forceStop(any()) } returns true
        pkgs.forEach { pkg ->
            coEvery { launchFreeform(pkg, any(), any(), any(), any(), any()) } returns FreeformLaunchResult.OK
        }
    }

    private fun HelperClient.stubTask(pkg: String, taskId: Int, mode: Int, b: SplitBounds) {
        coEvery { getTaskState(pkg) } returns SplitTaskState(taskId, mode, b.left, b.top, b.right, b.bottom)
    }

    /**
     * Stubs setFocusedTask to return true so reAssertSplitZOrder exits the happy path
     * without entering the retry branch (which calls delay(300ms) and would require tests
     * to advance extra virtual time).
     */
    private fun HelperClient.stubFocusedTaskOk() {
        coEvery { setFocusedTask(any()) } returns true
    }

    /** Builds a [SplitSessionManager] with a [FakeMediaSessionSource] and controlled clocks. */
    private fun buildManager(
        helper: HelperClient,
        backdrop: FakeSplitBackdrop = FakeSplitBackdrop(),
        mediaSource: FakeMediaSessionSource,
        scope: kotlinx.coroutines.CoroutineScope,
        clock: () -> Long = System::currentTimeMillis,
    ) = SplitSessionManager(
        helper = helper,
        prefs = FakeSplitPreferences(),
        backdrop = backdrop,
        scope = scope,
        tickDelayMs = 60_000L,      // watchdog inactive during these tests
        mediaSource = mediaSource,
        mediaPollDelayMs = 200L,    // fast poll for test speed
        nowMs = clock,
    )

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test fun `null mediaSource - no poll started, no crash on start and exit`() = runTest {
        val helper = io.mockk.mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)

        val mgr = SplitSessionManager(
            helper, FakeSplitPreferences(), FakeSplitBackdrop(), backgroundScope,
            tickDelayMs = 60_000L, mediaSource = null,
        )
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))
        // Advance past several poll periods — no foreground-package query should occur.
        advanceTimeBy(800); runCurrent()

        coVerify(exactly = 0) { helper.getTopTaskPackageOrSkip() }
        mgr.exit()
    }

    @Test fun `poll skips helper call when manager mutex is locked (Imp1 gate)`() = runTest {
        // Imp1: when a session op holds the mutex, the poll must not call getTopTaskPackage()
        // at all — queueing on HelperClient's own mutex behind setFocusedTask's 2 s budget
        // would starve it and cause Task G fallback symptoms.
        val helper = io.mockk.mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        helper.stubFocusedTaskOk()
        coEvery { helper.getTopTaskPackageOrSkip() } returns MEDIACENTER_PKG

        val mediaSource = FakeMediaSessionSource(sessionPkgs = setOf("pkg.narrow"))
        val mgr = buildManager(helper, mediaSource = mediaSource, scope = backgroundScope)
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        // Hold the manager mutex for 300 ms virtual time — longer than one poll period.
        launch(backgroundScope.coroutineContext) {
            mgr.mutex.withLock { delay(300) }
        }
        runCurrent() // let the lock-holder acquire the mutex

        // Fire a poll tick while the mutex is held.
        advanceTimeBy(250); runCurrent()

        // The gate must have fired: no helper call allowed while mutex was locked.
        coVerify(exactly = 0) { helper.getTopTaskPackageOrSkip() }
        assertEquals(0, mediaSource.dispatched.size)

        // Cancel the session before runTest cleanup fires more poll ticks.
        mgr.exit(); runCurrent()
    }

    @Test fun `poll detects mediacenter foreground - re-asserts z-order and dispatches play-pause`() = runTest {
        val helper = io.mockk.mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        // Return true so reAssertSplitZOrder exits the happy path without the retry delay.
        helper.stubFocusedTaskOk()

        // Mediacenter is the foreground (top) task — stub both the pre-check (getTopTaskPackageOrSkip)
        // and the in-lock re-read inside reactToMediaCenterLocked (getTopTaskPackage).
        coEvery { helper.getTopTaskPackageOrSkip() } returns MEDIACENTER_PKG
        coEvery { helper.getTopTaskPackage() } returns MEDIACENTER_PKG

        val mediaSource = FakeMediaSessionSource(sessionPkgs = setOf("pkg.narrow"))
        val backdrop = FakeSplitBackdrop()

        val mgr = SplitSessionManager(
            helper = helper,
            prefs = FakeSplitPreferences(),
            backdrop = backdrop,
            scope = backgroundScope,
            tickDelayMs = 60_000L,
            mediaSource = mediaSource,
            mediaPollDelayMs = 200L,
        )
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        // Advance past one poll period.
        advanceTimeBy(250); runCurrent()

        // backdrop.show() must have been called to re-assert Z-order.
        assertEquals(true, backdrop.shown)
        // setFocusedTask called for both panes.
        coVerify { helper.setFocusedTask(11) }
        coVerify { helper.setFocusedTask(10) }
        // Play/pause was forwarded to the pane's session.
        assertEquals(1, mediaSource.dispatched.size)
        assertEquals("pkg.narrow", mediaSource.dispatched.first().packageName)
    }

    @Test fun `poll does not reroute when mediacenter is not the foreground task`() = runTest {
        val helper = io.mockk.mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)

        // Some other app is foreground; mediacenter not running.
        coEvery { helper.getTopTaskPackageOrSkip() } returns "com.android.launcher3"

        val mediaSource = FakeMediaSessionSource(sessionPkgs = setOf("pkg.narrow"))
        val mgr = buildManager(helper, mediaSource = mediaSource, scope = backgroundScope)
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        advanceTimeBy(250); runCurrent()

        assertEquals(0, mediaSource.dispatched.size)
        coVerify(exactly = 0) { helper.setFocusedTask(any()) }
        // N4: activeSessionPackages() must NOT be called when mediacenter is not foreground —
        // pins the I4 gating property (expensive call skipped on every non-candidate tick).
        assertEquals(0, mediaSource.activeSessionPackagesCalls)
    }

    @Test fun `in-lock re-read of topPkg suppresses reroute when foreground changes between pre-check and mutex`() = runTest {
        // P1 / N3: the pre-check outside the mutex fires (returns MEDIACENTER_PKG), but by the
        // time we acquire the lock and re-read, the foreground task has changed. Without the
        // in-lock re-read (N3), both calls are the same constant stub and the test always passes;
        // with returnsMany the first call (pre-check) returns MEDIACENTER_PKG and the second
        // (in-lock validation) returns launcher — proving the in-lock guard actually gatekeeps.
        val helper = io.mockk.mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        helper.stubFocusedTaskOk()

        // Pre-check (outside mutex) via getTopTaskPackageOrSkip returns MEDIACENTER_PKG;
        // the in-lock re-read (inside reactToMediaCenterLocked) via getTopTaskPackage returns
        // something else — simulates a foreground change that occurs during the mutex wait.
        coEvery { helper.getTopTaskPackageOrSkip() } returns MEDIACENTER_PKG
        coEvery { helper.getTopTaskPackage() } returns "com.android.launcher3"

        val mediaSource = FakeMediaSessionSource(sessionPkgs = setOf("pkg.narrow"))
        val mgr = buildManager(helper, mediaSource = mediaSource, scope = backgroundScope)
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        advanceTimeBy(250); runCurrent()

        // The in-lock guard must suppress the reroute even though the pre-check passed.
        assertEquals(0, mediaSource.dispatched.size)
    }

    @Test fun `poll does not reroute when no pane package has an active session`() = runTest {
        val helper = io.mockk.mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)

        // Stub both variants: pre-check + in-lock re-read (dual-stub rule — see class comment).
        coEvery { helper.getTopTaskPackageOrSkip() } returns MEDIACENTER_PKG
        coEvery { helper.getTopTaskPackage() } returns MEDIACENTER_PKG

        // No pane package has an active session.
        val mediaSource = FakeMediaSessionSource(sessionPkgs = setOf("com.spotify.music"))
        val mgr = buildManager(helper, mediaSource = mediaSource, scope = backgroundScope)
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        advanceTimeBy(250); runCurrent()

        assertEquals(0, mediaSource.dispatched.size)
        coVerify(exactly = 0) { helper.setFocusedTask(any()) }
    }

    @Test fun `poll does not reroute when mediacenter is itself a pane (I5 guard)`() = runTest {
        val helper = io.mockk.mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch(MEDIACENTER_PKG, "pkg.narrow")
        helper.stubTask(MEDIACENTER_PKG, 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)

        // Mediacenter is the top task and also a pane — user explicitly launched it in split.
        // Stub both variants (dual-stub rule — see class comment): the I5 guard lives after the
        // in-lock re-read, so the test is vacuous without the getTopTaskPackage stub.
        coEvery { helper.getTopTaskPackageOrSkip() } returns MEDIACENTER_PKG
        coEvery { helper.getTopTaskPackage() } returns MEDIACENTER_PKG

        val mediaSource = FakeMediaSessionSource(sessionPkgs = setOf(MEDIACENTER_PKG))
        val mgr = buildManager(helper, mediaSource = mediaSource, scope = backgroundScope)
        mgr.start(SplitPair("pkg.narrow", MEDIACENTER_PKG, SplitSide.RIGHT))

        advanceTimeBy(250); runCurrent()

        assertEquals(0, mediaSource.dispatched.size)
    }

    @Test fun `stand-down latch blocks reroutes after REROUTE_MAX_COUNT in a session`() = runTest {
        val helper = io.mockk.mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        helper.stubFocusedTaskOk()

        // Mediacenter always foreground — stub both the pre-check and the in-lock re-read.
        coEvery { helper.getTopTaskPackageOrSkip() } returns MEDIACENTER_PKG
        coEvery { helper.getTopTaskPackage() } returns MEDIACENTER_PKG

        val mediaSource = FakeMediaSessionSource(sessionPkgs = setOf("pkg.narrow"))
        val mgr = SplitSessionManager(
            helper = helper,
            prefs = FakeSplitPreferences(),
            backdrop = FakeSplitBackdrop(),
            scope = backgroundScope,
            tickDelayMs = 60_000L,
            mediaSource = mediaSource,
            mediaPollDelayMs = 200L,
        )
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        // Tick 1 → reroute #1 (count 0 → 1).
        advanceTimeBy(250); runCurrent()
        // Tick 2 → reroute #2 (count 1 → 2, latch now engaged).
        advanceTimeBy(250); runCurrent()
        // Tick 3 → blocked by stand-down latch.
        advanceTimeBy(250); runCurrent()
        // Tick 4 → still blocked.
        advanceTimeBy(250); runCurrent()

        assertEquals("only 2 reroutes should fire before stand-down latch engages", 2, mediaSource.dispatched.size)
    }

    @Test fun `stand-down latch stays engaged for session lifetime (no sliding window reset)`() = runTest {
        val helper = io.mockk.mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        helper.stubFocusedTaskOk()

        // Stub both the pre-check and the in-lock re-read.
        coEvery { helper.getTopTaskPackageOrSkip() } returns MEDIACENTER_PKG
        coEvery { helper.getTopTaskPackage() } returns MEDIACENTER_PKG

        val mediaSource = FakeMediaSessionSource(sessionPkgs = setOf("pkg.narrow"))
        val mgr = buildManager(helper, mediaSource = mediaSource, scope = backgroundScope)
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        // Fire MAX_COUNT reroutes to engage latch.
        advanceTimeBy(250); runCurrent()
        advanceTimeBy(250); runCurrent()
        assertEquals(2, mediaSource.dispatched.size)

        // Advance a long time — latch stays engaged, no 3rd reroute.
        advanceTimeBy(15_000); runCurrent()

        assertEquals("stand-down latch is permanent within a session", 2, mediaSource.dispatched.size)
    }

    @Test fun `poll stops when session exits - no reroute fires after exit`() = runTest {
        val helper = io.mockk.mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)

        // No mediacenter while session is active.
        coEvery { helper.getTopTaskPackageOrSkip() } returns null

        val mediaSource = FakeMediaSessionSource(sessionPkgs = setOf("pkg.narrow"))
        val mgr = buildManager(helper, mediaSource = mediaSource, scope = backgroundScope)
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))
        advanceTimeBy(250); runCurrent()

        // Exit the session.
        mgr.exit(); runCurrent()

        // Now make mediacenter appear foreground — poll must NOT fire because session is Idle.
        coEvery { helper.getTopTaskPackageOrSkip() } returns MEDIACENTER_PKG
        advanceTimeBy(500); runCurrent()

        // No reroute should have fired (session is Idle, poll was cancelled).
        assertEquals(0, mediaSource.dispatched.size)
        assertEquals(SplitSessionState.Idle, mgr.state.value)
    }

    @Test fun `permission exception from activeSessionPackages degrades gracefully - no crash, no reroute`() = runTest {
        val helper = io.mockk.mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)

        // Stub both variants (dual-stub rule — see class comment): activeSessionPackages() is
        // called after the in-lock re-read, so the degrade path is only reached when both are stubbed.
        coEvery { helper.getTopTaskPackageOrSkip() } returns MEDIACENTER_PKG
        coEvery { helper.getTopTaskPackage() } returns MEDIACENTER_PKG

        // activeSessionPackages throws (simulates revoked notification permission).
        val mediaSource = FakeMediaSessionSource(
            throwOnPackages = SecurityException("notification listener not allowed"),
        )
        val mgr = buildManager(helper, mediaSource = mediaSource, scope = backgroundScope)
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        // Must not crash; no reroute. activeSessionPackages must have been called (proving the
        // degrade path was actually reached, not short-circuited by the in-lock re-read).
        advanceTimeBy(250); runCurrent()

        assertEquals(1, mediaSource.activeSessionPackagesCalls)
        assertEquals(0, mediaSource.dispatched.size)
        assertEquals(true, mgr.state.value is SplitSessionState.Active)
    }

    @Test fun `transient null from getTopTaskPackage - poll skips tick without crash`() = runTest {
        val helper = io.mockk.mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)

        // Daemon fails transiently.
        coEvery { helper.getTopTaskPackageOrSkip() } returns null

        val mediaSource = FakeMediaSessionSource(sessionPkgs = setOf("pkg.narrow"))
        val mgr = buildManager(helper, mediaSource = mediaSource, scope = backgroundScope)
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        // Should not crash.
        advanceTimeBy(250); runCurrent()

        assertEquals(0, mediaSource.dispatched.size)
        assertEquals(true, mgr.state.value is SplitSessionState.Active)
    }

    @Test fun `getTopTaskPackageOrSkip returns null (HelperClient mutex busy) - poll skips tick without querying media sessions`() = runTest {
        // CX1: when getTopTaskPackageOrSkip() returns null (simulating a tryLock failure because
        // setFocusedTask or another session op holds HelperClient's mutex), the poll must skip
        // the tick entirely — no activeSessionPackages() call, no dispatch.
        val helper = io.mockk.mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)

        // Pre-check returns null (tryLock failed). Also stub getTopTaskPackage() to return
        // MEDIACENTER_PKG (dual-stub rule): without this stub, reverting OrSkip to plain
        // getTopTaskPackage() with the relaxed mock returning null would leave the in-lock re-read
        // also returning null → 0 calls — the test would pass vacuously even with a broken impl.
        coEvery { helper.getTopTaskPackageOrSkip() } returns null
        coEvery { helper.getTopTaskPackage() } returns MEDIACENTER_PKG

        val mediaSource = FakeMediaSessionSource(sessionPkgs = setOf("pkg.narrow"))
        val mgr = buildManager(helper, mediaSource = mediaSource, scope = backgroundScope)
        mgr.start(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        advanceTimeBy(250); runCurrent()

        assertEquals(0, mediaSource.activeSessionPackagesCalls)
        assertEquals(0, mediaSource.dispatched.size)
    }

    @Test fun `stand-down latch resets when a new session starts after exit`() = runTest {
        val helper = io.mockk.mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        helper.stubLaunch("pkg.wide", "pkg.narrow")
        helper.stubTask("pkg.wide", 10, 5, wide)
        helper.stubTask("pkg.narrow", 11, 5, narrow)
        helper.stubFocusedTaskOk()

        // Stub both the pre-check and the in-lock re-read.
        coEvery { helper.getTopTaskPackageOrSkip() } returns MEDIACENTER_PKG
        coEvery { helper.getTopTaskPackage() } returns MEDIACENTER_PKG

        val mediaSource = FakeMediaSessionSource(sessionPkgs = setOf("pkg.narrow"))
        var fakeNow = 1_000L
        val pair = SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT)
        val mgr = SplitSessionManager(
            helper = helper,
            prefs = FakeSplitPreferences(),
            backdrop = FakeSplitBackdrop(),
            scope = backgroundScope,
            tickDelayMs = 60_000L,
            mediaSource = mediaSource,
            mediaPollDelayMs = 200L,
            nowMs = { fakeNow },
        )
        mgr.start(pair)

        // Exhaust the debounce within the first session.
        advanceTimeBy(250); runCurrent()  // reroute #1
        advanceTimeBy(250); runCurrent()  // reroute #2
        advanceTimeBy(250); runCurrent()  // blocked by stand-down latch
        assertEquals(2, mediaSource.dispatched.size)

        // End the session and start a fresh one. Advance fakeNow past the double-tap guard
        // window (DOUBLE_TAP_WINDOW_MS = 1500ms from lastStartMs=1000) so start() is not
        // swallowed by the same-pair guard.
        fakeNow = 3_000L
        mgr.exit(); runCurrent()
        mgr.start(pair)

        // After the fresh session starts, the stand-down latch is cleared → reroute fires again.
        advanceTimeBy(250); runCurrent()
        assertEquals("3rd reroute allowed after session restart", 3, mediaSource.dispatched.size)
    }
}
