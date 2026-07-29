package com.bydmate.app.split

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.robolectric.Shadows
import com.bydmate.app.data.vehicle.HelperClient
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Controller-level tests for Bug B: «приложение слева»/«справа» picker must appear when
 * the menu's change-app items are tapped.
 *
 * Tests assert at two seams:
 *  1. [SplitOverlayController.buildExcludedPackages] — verifies the filter that syncLogicToView
 *     applies when pickerMode = ChangePane; ensures both session packages are excluded so the
 *     picker does not show the already-placed apps.
 *  2. [SplitOverlayController.logic].pickerMode — verifies the logic state that the (fixed)
 *     handleMenuAction propagates to pillView via syncLogicToView().
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SplitOverlayControllerMenuTest {

    private val sessionManager = mockk<SplitSessionManager>(relaxed = true)
    private val helperClient = mockk<HelperClient>(relaxed = true)
    private val splitPrefs = mockk<SplitPreferences>(relaxed = true)

    private fun makeController(): SplitOverlayController {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        every { sessionManager.events } returns MutableSharedFlow()
        return SplitOverlayController(ctx, Provider { sessionManager }, helperClient, splitPrefs)
    }

    // ── buildExcludedPackages: ChangePane ─────────────────────────────────────

    @Test fun `buildExcludedPackages ChangePane returns both session packages`() {
        val pair = SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT)
        every { sessionManager.state } returns MutableStateFlow(
            SplitSessionState.Active(pair, narrowTaskId = 11, wideTaskId = 10)
        )
        val controller = makeController()

        val excluded = controller.buildExcludedPackages(PickerMode.ChangePane(Pane.NARROW))

        assertEquals(setOf("pkg.narrow", "pkg.wide"), excluded)
    }

    @Test fun `buildExcludedPackages ChangePane in Idle returns empty set`() {
        every { sessionManager.state } returns MutableStateFlow(SplitSessionState.Idle)
        val controller = makeController()

        val excluded = controller.buildExcludedPackages(PickerMode.ChangePane(Pane.WIDE))

        assertTrue("No session → exclusion set must be empty", excluded.isEmpty())
    }

    @Test fun `buildExcludedPackages FirstPairStep2 excludes only the chosen wide package`() {
        every { sessionManager.state } returns MutableStateFlow(SplitSessionState.Idle)
        val controller = makeController()

        val excluded = controller.buildExcludedPackages(PickerMode.FirstPairStep2("pkg.wide"))

        assertEquals(setOf("pkg.wide"), excluded)
    }

    @Test fun `buildExcludedPackages null and non-picker modes return empty set`() {
        every { sessionManager.state } returns MutableStateFlow(SplitSessionState.Idle)
        val controller = makeController()

        assertEquals(emptySet<String>(), controller.buildExcludedPackages(null))
        assertEquals(emptySet<String>(), controller.buildExcludedPackages(PickerMode.FirstPairStep1))
    }

    // ── buildExcludedPackages: pane-closed ChangePane ─────────────────────────
    //
    // When the picker is opened automatically because a pane's app closed, the closed app
    // must appear in the list — only the other (still-running) pane's app is excluded.

    @Test fun `buildExcludedPackages pane-closed NARROW excludes only the wide package`() {
        val pair = SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT)
        every { sessionManager.state } returns MutableStateFlow(
            SplitSessionState.Active(pair, narrowTaskId = 11, wideTaskId = 10)
        )
        val controller = makeController()

        val excluded = controller.buildExcludedPackages(
            PickerMode.ChangePane(Pane.NARROW, closedPkg = "pkg.narrow")
        )

        // The closed app (pkg.narrow) must NOT be excluded so the user can reselect it.
        assertEquals(setOf("pkg.wide"), excluded)
    }

    @Test fun `buildExcludedPackages pane-closed WIDE excludes only the narrow package`() {
        val pair = SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT)
        every { sessionManager.state } returns MutableStateFlow(
            SplitSessionState.Active(pair, narrowTaskId = 11, wideTaskId = 10)
        )
        val controller = makeController()

        val excluded = controller.buildExcludedPackages(
            PickerMode.ChangePane(Pane.WIDE, closedPkg = "pkg.wide")
        )

        // The closed app (pkg.wide) must NOT be excluded so the user can reselect it.
        assertEquals(setOf("pkg.narrow"), excluded)
    }

    @Test fun `buildExcludedPackages pane-closed in Idle returns empty set`() {
        every { sessionManager.state } returns MutableStateFlow(SplitSessionState.Idle)
        val controller = makeController()

        val excluded = controller.buildExcludedPackages(
            PickerMode.ChangePane(Pane.NARROW, closedPkg = "pkg.closed")
        )

        assertTrue("Idle session → no other pane to exclude", excluded.isEmpty())
    }

    // ── handleAppPicked: pane-closed guard ────────────────────────────────────
    //
    // BLOCKER fix: tapping the just-closed app in the pane-closed picker must flow through
    // (pickerMode cleared → ChangeApp dispatched). Tapping the other pane's live app must
    // still be blocked.

    private fun makeControllerWithScope(): Pair<SplitOverlayController, CoroutineScope> {
        val controller = makeController()
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        controller.start(scope)
        return controller to scope
    }

    @Test fun `handleAppPicked allows the just-closed app to be tapped in pane-closed picker`() {
        val pair = SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT)
        every { sessionManager.state } returns MutableStateFlow(
            SplitSessionState.Active(pair, narrowTaskId = 11, wideTaskId = 10)
        )
        val (controller, _) = makeControllerWithScope()
        // Pane-closed picker for NARROW (closed pkg = pkg.narrow, still in pair).
        controller.logic.openPickerForPane(Pane.NARROW, closedPkg = "pkg.narrow")

        // Tap the closed app — must flow through (pickerMode cleared by onAppPicked).
        controller.handleAppPicked("pkg.narrow")

        assertNull("Closed app tap must reach onAppPicked and clear pickerMode",
            controller.logic.pickerMode)
    }

    @Test fun `handleAppPicked blocks the other pane live app in pane-closed picker`() {
        val pair = SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT)
        every { sessionManager.state } returns MutableStateFlow(
            SplitSessionState.Active(pair, narrowTaskId = 11, wideTaskId = 10)
        )
        val (controller, _) = makeControllerWithScope()
        controller.logic.openPickerForPane(Pane.NARROW, closedPkg = "pkg.narrow")

        // Tap the other pane's app (pkg.wide) — must be silently blocked.
        controller.handleAppPicked("pkg.wide")

        // pickerMode stays set (guard returned early, onAppPicked not called).
        assertEquals(PickerMode.ChangePane(Pane.NARROW, "pkg.narrow"), controller.logic.pickerMode)
    }

    @Test fun `handleAppPicked blocks both session apps in voluntary ChangePane picker`() {
        val pair = SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT)
        every { sessionManager.state } returns MutableStateFlow(
            SplitSessionState.Active(pair, narrowTaskId = 11, wideTaskId = 10)
        )
        val (controller, _) = makeControllerWithScope()
        // Voluntary picker (no closedPkg).
        controller.logic.openPickerForPane(Pane.NARROW, closedPkg = null)

        // Both session apps blocked.
        controller.handleAppPicked("pkg.narrow")
        assertEquals(PickerMode.ChangePane(Pane.NARROW, null), controller.logic.pickerMode)
        controller.handleAppPicked("pkg.wide")
        assertEquals(PickerMode.ChangePane(Pane.NARROW, null), controller.logic.pickerMode)
    }

    // ── W6-F2: departed pane app is not offered by the picker ─────────────────
    //
    // A pane app sent to the cluster owns a single task that lives there. Offering it in the
    // picker paints a black pane and leaves the cluster copy untouched (on-car 389).

    @Test fun `buildExcludedPackages hides the departed app in the pane-closed picker`() {
        val pair = SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT)
        every { sessionManager.state } returns MutableStateFlow(
            SplitSessionState.Active(pair, narrowTaskId = 11, wideTaskId = 10)
        )
        // The narrow pane departed to the cluster, and its picker opened because of that.
        every { sessionManager.departedPanePkgs() } returns setOf("pkg.narrow")
        val controller = makeController()

        val excluded = controller.buildExcludedPackages(
            PickerMode.ChangePane(Pane.NARROW, closedPkg = "pkg.narrow")
        )

        assertEquals(setOf("pkg.narrow", "pkg.wide"), excluded)
    }

    @Test fun `buildExcludedPackages keeps a non-departed closed app selectable`() {
        val pair = SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT)
        every { sessionManager.state } returns MutableStateFlow(
            SplitSessionState.Active(pair, narrowTaskId = 11, wideTaskId = 10)
        )
        // Nothing departed: the closed app must stay selectable so it can be reopened.
        every { sessionManager.departedPanePkgs() } returns emptySet()
        val controller = makeController()

        val excluded = controller.buildExcludedPackages(
            PickerMode.ChangePane(Pane.NARROW, closedPkg = "pkg.narrow")
        )

        assertEquals(setOf("pkg.wide"), excluded)
    }

    @Test fun `buildExcludedPackages offers the app again once it returns from the cluster`() {
        val pair = SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT)
        every { sessionManager.state } returns MutableStateFlow(
            SplitSessionState.Active(pair, narrowTaskId = 11, wideTaskId = 10)
        )
        // Watchdog saw the task back on display 0 and cleared the departed flag.
        every { sessionManager.departedPanePkgs() } returnsMany listOf(setOf("pkg.narrow"), emptySet())
        val controller = makeController()
        val mode = PickerMode.ChangePane(Pane.NARROW, closedPkg = "pkg.narrow")

        assertTrue("Still departed", "pkg.narrow" in controller.buildExcludedPackages(mode))
        assertTrue("Back home → selectable again", "pkg.narrow" !in controller.buildExcludedPackages(mode))
    }

    @Test fun `handleAppPicked swallows a tap on the departed app`() {
        val pair = SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT)
        every { sessionManager.state } returns MutableStateFlow(
            SplitSessionState.Active(pair, narrowTaskId = 11, wideTaskId = 10)
        )
        every { sessionManager.departedPanePkgs() } returns setOf("pkg.narrow")
        val (controller, _) = makeControllerWithScope()
        controller.logic.openPickerForPane(Pane.NARROW, closedPkg = "pkg.narrow")

        // Same tap that the pane-closed picker normally lets through — blocked while departed.
        controller.handleAppPicked("pkg.narrow")

        assertEquals(PickerMode.ChangePane(Pane.NARROW, "pkg.narrow"), controller.logic.pickerMode)
    }

    // ── handlePickerBack: failure recovery keeps picker open ──────────────────
    //
    // Logic-seam test: after handlePickerBack on a pane-closed picker, pickerMode is
    // synchronously re-opened with the closedPkg context so the picker stays visible
    // while the async restore coroutine is in flight. On failure the picker remains open.

    @Test fun `handlePickerBack re-opens picker synchronously with closedPkg context`() {
        val pair = SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT)
        every { sessionManager.state } returns MutableStateFlow(
            SplitSessionState.Active(pair, narrowTaskId = 11, wideTaskId = 10)
        )
        coEvery { sessionManager.changeApp(any(), any()) } returns SplitStartResult.LAUNCH_FAILED
        val (controller, _) = makeControllerWithScope()
        controller.logic.openPickerForPane(Pane.NARROW, closedPkg = "pkg.narrow")

        controller.handlePickerBack()

        // Synchronously after handlePickerBack: pickerMode is re-set with the closedPkg
        // so the picker overlay stays visible during the changeApp coroutine (runs before
        // withContext(Dispatchers.Main) in the recovery branch).
        val mode = controller.logic.pickerMode as? PickerMode.ChangePane
        assertEquals(Pane.NARROW, mode?.pane)
        assertEquals("pkg.narrow", mode?.closedPkg)
    }

    // ── logic.pickerMode after menu action (what syncLogicToView propagates) ──

    @Test fun `logic pickerMode ChangePane WIDE after PICK_LEFT narrowRight — exclusion filter correct`() {
        val pair = SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT)
        every { sessionManager.state } returns MutableStateFlow(
            SplitSessionState.Active(pair, narrowTaskId = 11, wideTaskId = 10)
        )
        val controller = makeController()

        // Fixed handleMenuAction calls logic.onMenuAction and then syncLogicToView when pickerMode != null.
        controller.logic.onMenuAction(MenuAction.PICK_LEFT, SplitSide.RIGHT)

        assertEquals(PickerMode.ChangePane(Pane.WIDE), controller.logic.pickerMode)
        // Exclusion filter that syncLogicToView applies:
        val excluded = controller.buildExcludedPackages(controller.logic.pickerMode)
        assertTrue(excluded.contains("pkg.narrow"))
        assertTrue(excluded.contains("pkg.wide"))
    }

    @Test fun `logic pickerMode ChangePane NARROW after PICK_RIGHT narrowRight — exclusion filter correct`() {
        val pair = SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT)
        every { sessionManager.state } returns MutableStateFlow(
            SplitSessionState.Active(pair, narrowTaskId = 11, wideTaskId = 10)
        )
        val controller = makeController()

        controller.logic.onMenuAction(MenuAction.PICK_RIGHT, SplitSide.RIGHT)

        assertEquals(PickerMode.ChangePane(Pane.NARROW), controller.logic.pickerMode)
        val excluded = controller.buildExcludedPackages(controller.logic.pickerMode)
        assertTrue(excluded.contains("pkg.narrow"))
        assertTrue(excluded.contains("pkg.wide"))
    }

    // ── H2 fix: Idle-picker teardown via ACTION_CLOSE_SYSTEM_DIALOGS ─────────
    //
    // showFirstPairPicker() attaches pill + full-screen picker while state is Idle.
    // StateFlow will NOT re-emit Idle (state already Idle), so the normal observeSessionState
    // Idle branch never fires. Without the broadcast receiver, a Home press leaves both
    // overlay windows floating over the launcher with the focusable picker eating touches.
    //
    // Fix: registerCloseSystemDialogsReceiver() is called by showFirstPairPicker().
    // The receiver calls tearDownPillIfIdle(), which clears the pill + picker.

    @Suppress("DEPRECATION")
    @Test fun `ACTION_CLOSE_SYSTEM_DIALOGS after showFirstPairPicker dismisses the Idle picker`() {
        every { splitPrefs.isFeatureEnabled() } returns true
        every { sessionManager.state } returns MutableStateFlow(SplitSessionState.Idle)

        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val controller = makeController()
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        controller.start(scope)
        // Drain the initial StateFlow emission queued by start() before registering the receiver.
        // Without this idle(), the second idle() (after sendBroadcast) would process the initial
        // Idle emission first, clearing pickerMode via the state observer — not via the receiver —
        // making the test vacuous (passes even if registerCloseSystemDialogsReceiver is removed).
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        controller.showFirstPairPicker()

        assertEquals("Precondition: picker must be open after showFirstPairPicker",
            PickerMode.FirstPairStep1, controller.logic.pickerMode)

        // Simulate Home press: system sends ACTION_CLOSE_SYSTEM_DIALOGS.
        // Robolectric 4.13 uses PAUSED looper mode by default; broadcast delivery is queued
        // on the main looper and requires an explicit idle() to be processed.
        ctx.sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertNull("ACTION_CLOSE_SYSTEM_DIALOGS must dismiss the Idle first-pair picker",
            controller.logic.pickerMode)
    }

    // ── D-4: relocale restarts menu timer ─────────────────────────────────────
    //
    // tearDownPill() cancels menuTimerJob and calls logic.dismissPicker() (clears pickerMode).
    // It does NOT clear logic.isMenuVisible, so after ensurePillAttached + syncLogicToView the
    // menu renders as visible. BUT the auto-hide timer is gone — the menu freezes open.
    //
    // Fix: relocale() snapshots wasMenuVisible before teardown, then restarts menuTimerJob
    // after reattach when wasMenuVisible == true.
    //
    // Anti-vacuity: removing `if (wasMenuVisible && ...) { menuTimerJob = ... }` leaves
    // menuTimerJob == null after relocale, so `assertNotNull(controller.menuTimerJob)` fails.

    @Suppress("DEPRECATION")
    @Test fun `relocale restarts menu timer when menu was open (D-4)`() {
        every { splitPrefs.isFeatureEnabled() } returns true
        every { sessionManager.state } returns MutableStateFlow(SplitSessionState.Idle)

        val controller = makeController()
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        controller.start(scope)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // Attach pill via the public Idle-picker entry point.
        controller.showFirstPairPicker()

        // Open the pill menu (simulating a user tap on the pill circle).
        controller.logic.onPillTap()
        assertTrue("Precondition: menu must be visible before relocale", controller.logic.isMenuVisible)
        // menuTimerJob was started by showFirstPairPicker + subsequent logic call path?
        // No — handlePillTap starts it, but showFirstPairPicker doesn't call onPillTap.
        // Force-start the timer to simulate the production state (user tapped, timer is running).
        // (In production the timer starts in handlePillTap; we bypass the pill-view callback here.)

        // Call relocale() — the fix must restart the timer.
        controller.relocale()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // Menu logic state must be preserved after relocale.
        assertTrue("logic.isMenuVisible must still be true after relocale (D-4)", controller.logic.isMenuVisible)
        // The timer must have been (re)started so the menu will eventually auto-hide.
        assertNotNull(
            "menuTimerJob must be non-null after relocale when menu was open (D-4 fix)",
            controller.menuTimerJob,
        )
        assertTrue(
            "menuTimerJob must be active after relocale (D-4 fix)",
            controller.menuTimerJob!!.isActive,
        )
    }

    // N4 negative test: broadcast arriving while state is Active must NOT tear the pill down.
    // tearDownPillIfIdle() is conditional on state == Idle; this pins that invariant so
    // a future refactor cannot accidentally remove the Idle guard.
    //
    // The receiver must actually be registered for the test to exercise the guard rather than
    // being trivially green because nobody receives the broadcast. showFirstPairPicker()
    // always registers the receiver; calling it while state is Active simulates the race
    // window where state transitioned to Active while the receiver was still armed.

    @Suppress("DEPRECATION")
    @Test fun `ACTION_CLOSE_SYSTEM_DIALOGS while session is Active does not dismiss the pill`() {
        every { splitPrefs.isFeatureEnabled() } returns true
        val pair = SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT)
        every { sessionManager.state } returns MutableStateFlow(
            SplitSessionState.Active(pair, narrowTaskId = 11, wideTaskId = 10)
        )

        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val controller = makeController()
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        controller.start(scope)
        // Drain the initial Active emission queued by start() before registering the receiver.
        // Without this idle(), the second idle() (after sendBroadcast) would process the Active
        // emission first, unregistering the receiver via the state observer — so the broadcast
        // reaches nobody and the test passes vacuously even without the Idle guard in
        // tearDownPillIfIdle. With this idle(), the receiver stays live and the guard is exercised.
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // Register the receiver via showFirstPairPicker (which always registers it).
        // State is Active, so tearDownPillIfIdle must be a no-op when the broadcast fires.
        controller.showFirstPairPicker()
        val modeBeforeBroadcast = controller.logic.pickerMode

        ctx.sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // Idle check in tearDownPillIfIdle must block teardown: pickerMode unchanged.
        assertEquals("Active session: ACTION_CLOSE_SYSTEM_DIALOGS must not dismiss the picker",
            modeBeforeBroadcast, controller.logic.pickerMode)
    }
}
