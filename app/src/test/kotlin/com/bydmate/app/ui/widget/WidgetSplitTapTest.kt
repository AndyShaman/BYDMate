package com.bydmate.app.ui.widget

import com.bydmate.app.R
import com.bydmate.app.split.SplitPair
import com.bydmate.app.split.SplitSessionManager
import com.bydmate.app.split.SplitSessionState
import com.bydmate.app.split.SplitSide
import com.bydmate.app.split.SplitStartResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Widget left-tap in SPLIT mode is an inverse toggle (W6-F5):
 * no session -> start the last pair, session -> exit (including a zombie Active,
 * whose panes are already dead — the tap is the manual recovery path).
 */
class WidgetSplitTapTest {

    private val manager = mockk<SplitSessionManager>(relaxed = true)
    private val pair = SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT)

    private var noPairCalls = 0
    private val errors = mutableListOf<Int>()

    private fun stateIs(s: SplitSessionState) {
        every { manager.state } returns MutableStateFlow(s)
    }

    private fun tap(adbBlocked: Boolean = false) = runTest {
        WidgetController.runSplitTap(
            manager = manager,
            onNoPair = { noPairCalls++ },
            onError = { errors += it },
            adbBlocked = { adbBlocked },
        )
    }

    // ── Active session -> exit ──────────────────────────────────────────────────

    @Test fun `tap with an active session exits instead of starting`() {
        stateIs(SplitSessionState.Active(pair, narrowTaskId = 11, wideTaskId = 10))
        tap()
        coVerify(exactly = 1) { manager.exit() }
        coVerify(exactly = 0) { manager.startLastPair() }
        assertEquals(0, noPairCalls)
        assertTrue(errors.isEmpty())
    }

    @Test fun `tap on a zombie session exits without probing pane liveness`() {
        // Zombie = state is Active but both pane tasks are gone (task ids never
        // resolved). exit() is hardened to tear such a session down (R1), so the
        // tap must route there unconditionally, with no liveness check of its own.
        stateIs(SplitSessionState.Active(pair, narrowTaskId = -1, wideTaskId = -1))
        tap()
        coVerify(exactly = 1) { manager.exit() }
        coVerify(exactly = 0) { manager.startLastPair() }
        assertTrue(errors.isEmpty())
    }

    // ── Idle -> start last pair ─────────────────────────────────────────────────

    @Test fun `tap without a session starts the last pair`() {
        stateIs(SplitSessionState.Idle)
        coEvery { manager.startLastPair() } returns SplitStartResult.OK
        tap()
        coVerify(exactly = 1) { manager.startLastPair() }
        coVerify(exactly = 0) { manager.exit() }
        assertEquals(0, noPairCalls)
        assertTrue(errors.isEmpty())
    }

    @Test fun `no saved pair opens the first-pair picker`() {
        stateIs(SplitSessionState.Idle)
        coEvery { manager.startLastPair() } returns null
        tap()
        assertEquals(1, noPairCalls)
        assertTrue(errors.isEmpty())
    }

    // ── Error mapping (unchanged behaviour, pinned) ─────────────────────────────

    @Test fun `freeform unavailable shows the reboot hint`() {
        stateIs(SplitSessionState.Idle)
        coEvery { manager.startLastPair() } returns SplitStartResult.FREEFORM_UNAVAILABLE
        tap()
        assertEquals(listOf(R.string.split_freeform_reboot_hint), errors)
        assertEquals(0, noPairCalls)
    }

    @Test fun `launch failure shows the launch-failed message`() {
        stateIs(SplitSessionState.Idle)
        coEvery { manager.startLastPair() } returns SplitStartResult.LAUNCH_FAILED
        tap()
        assertEquals(listOf(R.string.split_launch_failed), errors)
    }

    @Test fun `launch failure with a dead ADB channel names ADB in the message`() {
        // #133: every window op goes through the helper daemon, which cannot be spawned
        // without the on-device ADB channel — the user needs to hear that, not a bare
        // "could not launch".
        stateIs(SplitSessionState.Idle)
        coEvery { manager.startLastPair() } returns SplitStartResult.LAUNCH_FAILED
        tap(adbBlocked = true)
        assertEquals(listOf(R.string.split_launch_failed_adb), errors)
    }

    @Test fun `freeform unavailable is unaffected by the ADB state`() {
        stateIs(SplitSessionState.Idle)
        coEvery { manager.startLastPair() } returns SplitStartResult.FREEFORM_UNAVAILABLE
        tap(adbBlocked = true)
        assertEquals(listOf(R.string.split_freeform_reboot_hint), errors)
    }

    @Test fun `disabled feature shows the disabled message`() {
        stateIs(SplitSessionState.Idle)
        coEvery { manager.startLastPair() } returns SplitStartResult.DISABLED
        tap()
        assertEquals(listOf(R.string.split_feature_disabled), errors)
    }
}
