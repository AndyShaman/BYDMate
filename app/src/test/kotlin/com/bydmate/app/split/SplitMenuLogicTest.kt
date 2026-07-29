package com.bydmate.app.split

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitMenuLogicTest {

    // ── sideToPane mapping ────────────────────────────────────────────────────

    @Test fun `sideToPane narrowRight LEFT column maps to WIDE pane`() {
        assertEquals(Pane.WIDE, SplitMenuLogic.sideToPane(SplitSide.LEFT, SplitSide.RIGHT))
    }

    @Test fun `sideToPane narrowRight RIGHT column maps to NARROW pane`() {
        assertEquals(Pane.NARROW, SplitMenuLogic.sideToPane(SplitSide.RIGHT, SplitSide.RIGHT))
    }

    @Test fun `sideToPane narrowLeft LEFT column maps to NARROW pane`() {
        assertEquals(Pane.NARROW, SplitMenuLogic.sideToPane(SplitSide.LEFT, SplitSide.LEFT))
    }

    @Test fun `sideToPane narrowLeft RIGHT column maps to WIDE pane`() {
        assertEquals(Pane.WIDE, SplitMenuLogic.sideToPane(SplitSide.RIGHT, SplitSide.LEFT))
    }

    // ── Initial state ─────────────────────────────────────────────────────────

    @Test fun `initial state menu hidden and no picker`() {
        val logic = SplitMenuLogic()
        assertFalse(logic.isMenuVisible)
        assertEquals(0L, logic.menuHideDeadline)
        assertNull(logic.pickerMode)
    }

    // ── Pill tap and auto-hide ────────────────────────────────────────────────

    @Test fun `onPillTap makes menu visible and sets deadline`() {
        var now = 1000L
        val logic = SplitMenuLogic(clock = { now }, menuDurationMs = 4_000L)
        logic.onPillTap()
        assertTrue(logic.isMenuVisible)
        assertEquals(5000L, logic.menuHideDeadline)
    }

    @Test fun `onTimerTick before deadline keeps menu visible`() {
        var now = 1000L
        val logic = SplitMenuLogic(clock = { now }, menuDurationMs = 4_000L)
        logic.onPillTap() // deadline = 5000
        now = 4999L
        val hidden = logic.onTimerTick()
        assertFalse(hidden)
        assertTrue(logic.isMenuVisible)
    }

    @Test fun `onTimerTick at deadline hides menu and returns true`() {
        var now = 1000L
        val logic = SplitMenuLogic(clock = { now }, menuDurationMs = 4_000L)
        logic.onPillTap() // deadline = 5000
        now = 5000L
        val hidden = logic.onTimerTick()
        assertTrue(hidden)
        assertFalse(logic.isMenuVisible)
        assertEquals(0L, logic.menuHideDeadline)
    }

    @Test fun `onTimerTick when menu hidden returns false`() {
        val logic = SplitMenuLogic()
        val hidden = logic.onTimerTick()
        assertFalse(hidden)
    }

    @Test fun `second pillTap refreshes deadline`() {
        var now = 1000L
        val logic = SplitMenuLogic(clock = { now }, menuDurationMs = 4_000L)
        logic.onPillTap() // deadline = 5000
        now = 4500L
        logic.onPillTap() // refresh: deadline = 8500
        now = 5500L
        val hidden = logic.onTimerTick()
        assertFalse(hidden)
        assertTrue(logic.isMenuVisible)
    }

    // ── Menu actions: session-op transitions ──────────────────────────────────

    @Test fun `onMenuAction MIRROR returns Mirror and hides menu`() {
        val logic = SplitMenuLogic()
        logic.onPillTap()
        val cmd = logic.onMenuAction(MenuAction.MIRROR, SplitSide.RIGHT)
        assertEquals(MenuCmd.Mirror, cmd)
        assertFalse(logic.isMenuVisible)
    }

    @Test fun `onMenuAction SWAP returns SwapApps and hides menu`() {
        val logic = SplitMenuLogic()
        logic.onPillTap()
        val cmd = logic.onMenuAction(MenuAction.SWAP, SplitSide.RIGHT)
        assertEquals(MenuCmd.SwapApps, cmd)
        assertFalse(logic.isMenuVisible)
    }

    @Test fun `onMenuAction EXIT returns Exit and hides menu`() {
        val logic = SplitMenuLogic()
        logic.onPillTap()
        val cmd = logic.onMenuAction(MenuAction.EXIT, SplitSide.RIGHT)
        assertEquals(MenuCmd.Exit, cmd)
        assertFalse(logic.isMenuVisible)
    }

    // ── Menu actions: picker transitions ─────────────────────────────────────

    @Test fun `PICK_LEFT with narrowRight opens picker for WIDE pane`() {
        val logic = SplitMenuLogic()
        logic.onPillTap()
        val cmd = logic.onMenuAction(MenuAction.PICK_LEFT, SplitSide.RIGHT)
        assertEquals(MenuCmd.None, cmd)
        assertFalse(logic.isMenuVisible)
        assertEquals(PickerMode.ChangePane(Pane.WIDE), logic.pickerMode)
    }

    @Test fun `PICK_RIGHT with narrowRight opens picker for NARROW pane`() {
        val logic = SplitMenuLogic()
        logic.onPillTap()
        val cmd = logic.onMenuAction(MenuAction.PICK_RIGHT, SplitSide.RIGHT)
        assertEquals(MenuCmd.None, cmd)
        assertEquals(PickerMode.ChangePane(Pane.NARROW), logic.pickerMode)
    }

    @Test fun `PICK_LEFT with narrowLeft opens picker for NARROW pane`() {
        val logic = SplitMenuLogic()
        val cmd = logic.onMenuAction(MenuAction.PICK_LEFT, SplitSide.LEFT)
        assertEquals(PickerMode.ChangePane(Pane.NARROW), logic.pickerMode)
        assertEquals(MenuCmd.None, cmd)
    }

    @Test fun `PICK_RIGHT with narrowLeft opens picker for WIDE pane`() {
        val logic = SplitMenuLogic()
        val cmd = logic.onMenuAction(MenuAction.PICK_RIGHT, SplitSide.LEFT)
        assertEquals(PickerMode.ChangePane(Pane.WIDE), logic.pickerMode)
        assertEquals(MenuCmd.None, cmd)
    }

    // ── ChangePane picker flow ────────────────────────────────────────────────

    @Test fun `onAppPicked in ChangePane mode returns ChangeApp and closes picker`() {
        val logic = SplitMenuLogic()
        logic.onMenuAction(MenuAction.PICK_RIGHT, SplitSide.RIGHT) // → ChangePane(NARROW)
        val cmd = logic.onAppPicked("com.example.app")
        assertEquals(MenuCmd.ChangeApp(Pane.NARROW, "com.example.app"), cmd)
        assertNull(logic.pickerMode)
    }

    // ── openPickerForPane ─────────────────────────────────────────────────────

    @Test fun `openPickerForPane opens change-pane picker and hides menu`() {
        val logic = SplitMenuLogic()
        logic.onPillTap()
        logic.openPickerForPane(Pane.NARROW)
        assertFalse(logic.isMenuVisible)
        assertEquals(PickerMode.ChangePane(Pane.NARROW), logic.pickerMode)
    }

    @Test fun `openPickerForPane with closedPkg stores it in ChangePane`() {
        val logic = SplitMenuLogic()
        logic.openPickerForPane(Pane.WIDE, closedPkg = "com.closed.app")
        val mode = logic.pickerMode as? PickerMode.ChangePane
        assertEquals(Pane.WIDE, mode?.pane)
        assertEquals("com.closed.app", mode?.closedPkg)
    }

    @Test fun `openPickerForPane without closedPkg produces null closedPkg`() {
        val logic = SplitMenuLogic()
        logic.openPickerForPane(Pane.NARROW)
        val mode = logic.pickerMode as? PickerMode.ChangePane
        assertNull(mode?.closedPkg)
    }

    // ── onPickerBack ──────────────────────────────────────────────────────────

    @Test fun `onPickerBack in pane-closed picker returns ChangeApp with closed package`() {
        val logic = SplitMenuLogic()
        logic.openPickerForPane(Pane.NARROW, closedPkg = "com.closed.app")
        val cmd = logic.onPickerBack()
        assertEquals(MenuCmd.ChangeApp(Pane.NARROW, "com.closed.app"), cmd)
    }

    @Test fun `onPickerBack in pane-closed picker clears pickerMode`() {
        val logic = SplitMenuLogic()
        logic.openPickerForPane(Pane.WIDE, closedPkg = "com.closed.app")
        logic.onPickerBack()
        assertNull(logic.pickerMode)
    }

    @Test fun `onPickerBack in voluntary picker returns None`() {
        val logic = SplitMenuLogic()
        logic.openPickerForPane(Pane.NARROW, closedPkg = null)
        val cmd = logic.onPickerBack()
        assertEquals(MenuCmd.None, cmd)
    }

    @Test fun `onPickerBack in voluntary picker clears pickerMode`() {
        val logic = SplitMenuLogic()
        logic.openPickerForPane(Pane.NARROW, closedPkg = null)
        logic.onPickerBack()
        assertNull(logic.pickerMode)
    }

    @Test fun `onPickerBack in FirstPairStep1 returns None and clears pickerMode`() {
        val logic = SplitMenuLogic()
        logic.showFirstPairPicker()
        val cmd = logic.onPickerBack()
        assertEquals(MenuCmd.None, cmd)
        assertNull(logic.pickerMode)
    }

    @Test fun `onPickerBack in FirstPairStep2 returns None and clears pickerMode`() {
        val logic = SplitMenuLogic()
        logic.showFirstPairPicker()
        logic.onAppPicked("com.wide.app") // advance to step2
        val cmd = logic.onPickerBack()
        assertEquals(MenuCmd.None, cmd)
        assertNull(logic.pickerMode)
    }

    @Test fun `onPickerBack with no active picker returns None`() {
        val logic = SplitMenuLogic()
        val cmd = logic.onPickerBack()
        assertEquals(MenuCmd.None, cmd)
        assertNull(logic.pickerMode)
    }

    // ── First-pair sequence ───────────────────────────────────────────────────

    @Test fun `showFirstPairPicker opens step1 and hides menu`() {
        val logic = SplitMenuLogic()
        logic.onPillTap()
        logic.showFirstPairPicker()
        assertFalse(logic.isMenuVisible)
        assertEquals(PickerMode.FirstPairStep1, logic.pickerMode)
    }

    @Test fun `first-pair step1 pick advances to step2 with widePkg stored`() {
        val logic = SplitMenuLogic()
        logic.showFirstPairPicker()
        val cmd = logic.onAppPicked("com.wide.app")
        assertEquals(MenuCmd.None, cmd)
        assertEquals(PickerMode.FirstPairStep2("com.wide.app"), logic.pickerMode)
    }

    @Test fun `first-pair step2 pick returns StartSplit with correct pair`() {
        val logic = SplitMenuLogic()
        logic.showFirstPairPicker()
        logic.onAppPicked("com.wide.app")     // step1 → step2
        val cmd = logic.onAppPicked("com.narrow.app") // step2 → StartSplit
        val expected = MenuCmd.StartSplit(
            SplitPair(narrowPkg = "com.narrow.app", widePkg = "com.wide.app", narrowSide = SplitSide.RIGHT)
        )
        assertEquals(expected, cmd)
        assertNull(logic.pickerMode)
    }

    // ── dismissPicker ─────────────────────────────────────────────────────────

    @Test fun `dismissPicker clears any active picker mode`() {
        val logic = SplitMenuLogic()
        logic.showFirstPairPicker()
        logic.onAppPicked("com.wide.app") // now in step 2
        logic.dismissPicker()
        assertNull(logic.pickerMode)
    }

    // ── No-picker guard ───────────────────────────────────────────────────────

    @Test fun `onAppPicked with no picker returns None`() {
        val logic = SplitMenuLogic()
        val cmd = logic.onAppPicked("com.example.ignored")
        assertEquals(MenuCmd.None, cmd)
    }

    // ── Idle-pill-leak regression (Fix #2) ────────────────────────────────────
    //
    // The controller attaches the pill in Idle state for the first-pair picker.
    // On back (onPickerBack returns None), pickerMode is null.
    // The controller then checks state==Idle && pickerMode==null → tearDownPill().

    @Test fun `after first-pair back pickerMode is null (idle cleanup precondition)`() {
        val logic = SplitMenuLogic()
        logic.showFirstPairPicker()
        assertEquals(PickerMode.FirstPairStep1, logic.pickerMode)
        logic.onPickerBack()
        assertNull(logic.pickerMode)
        assertFalse(logic.isMenuVisible)
    }

    @Test fun `after step2 back pickerMode is null (idle cleanup precondition)`() {
        val logic = SplitMenuLogic()
        logic.showFirstPairPicker()
        logic.onAppPicked("com.wide.app") // advance to step2
        logic.onPickerBack()
        assertNull(logic.pickerMode)
    }

    // ── Fix 3: same-app guard ─────────────────────────────────────────────────

    @Test fun `onAppPicked in FirstPairStep2 with same pkg as widePkg returns None`() {
        val logic = SplitMenuLogic()
        logic.showFirstPairPicker()
        logic.onAppPicked("com.same.app")  // step 1: wide app chosen
        // step 2: user somehow picks the same app as the wide pane.
        val cmd = logic.onAppPicked("com.same.app")
        assertEquals(MenuCmd.None, cmd)
        // Picker must stay in step 2 (no state advance).
        assertEquals(PickerMode.FirstPairStep2("com.same.app"), logic.pickerMode)
    }

    @Test fun `onAppPicked in FirstPairStep2 with different pkg returns StartSplit`() {
        val logic = SplitMenuLogic()
        logic.showFirstPairPicker()
        logic.onAppPicked("com.wide.app")
        val cmd = logic.onAppPicked("com.narrow.app")
        val expected = MenuCmd.StartSplit(
            SplitPair(narrowPkg = "com.narrow.app", widePkg = "com.wide.app", narrowSide = SplitSide.RIGHT))
        assertEquals(expected, cmd)
        assertNull(logic.pickerMode)
    }

    // ── showFirstPairPicker sets pickerMode before sync (Fix #1 logic guard) ──
    //
    // Critical fix was in controller ordering (ensurePillAttached BEFORE syncLogicToView).
    // At the logic level: pickerMode must be FirstPairStep1 immediately after the call.
    // This is the invariant the controller relies on when it calls syncLogicToView after attach.

    @Test fun `showFirstPairPicker pickerMode is FirstPairStep1 immediately after call`() {
        val logic = SplitMenuLogic()
        logic.showFirstPairPicker()
        // Invariant: any controller calling syncLogicToView after attach will find FirstPairStep1.
        assertEquals(PickerMode.FirstPairStep1, logic.pickerMode)
        assertFalse(logic.isMenuVisible)
    }

    // ── Bug B regression: PICK_LEFT/RIGHT must set ChangePane pickerMode ─────
    //
    // On-car: «поменять приложение слева»/«справа» did nothing — the picker never appeared.
    // Root cause: SplitOverlayController.handleMenuAction did not call syncLogicToView() after
    // logic.onMenuAction() set pickerMode. The fix adds that call when pickerMode != null.
    // These tests verify the logic-seam state that syncLogicToView() reads and propagates
    // to pillView.setPickerMode(), making the picker overlay visible.

    @Test fun `PICK_LEFT (narrowRight) → ChangePane WIDE — logic seam for Bug B`() {
        val logic = SplitMenuLogic()
        logic.onPillTap()
        val cmd = logic.onMenuAction(MenuAction.PICK_LEFT, SplitSide.RIGHT)
        // Menu must close and pickerMode must be set (syncLogicToView syncs this to the view).
        assertEquals(MenuCmd.None, cmd)
        assertFalse(logic.isMenuVisible)
        assertEquals(PickerMode.ChangePane(Pane.WIDE), logic.pickerMode)
    }

    @Test fun `PICK_RIGHT (narrowRight) → ChangePane NARROW — logic seam for Bug B`() {
        val logic = SplitMenuLogic()
        logic.onPillTap()
        val cmd = logic.onMenuAction(MenuAction.PICK_RIGHT, SplitSide.RIGHT)
        assertEquals(MenuCmd.None, cmd)
        assertFalse(logic.isMenuVisible)
        assertEquals(PickerMode.ChangePane(Pane.NARROW), logic.pickerMode)
    }

    @Test fun `PICK_LEFT (narrowLeft) → ChangePane NARROW — logic seam for Bug B`() {
        val logic = SplitMenuLogic()
        logic.onMenuAction(MenuAction.PICK_LEFT, SplitSide.LEFT)
        // narrow-left: LEFT column is NARROW pane
        assertEquals(PickerMode.ChangePane(Pane.NARROW), logic.pickerMode)
    }

    @Test fun `PICK_RIGHT (narrowLeft) → ChangePane WIDE — logic seam for Bug B`() {
        val logic = SplitMenuLogic()
        logic.onMenuAction(MenuAction.PICK_RIGHT, SplitSide.LEFT)
        // narrow-left: RIGHT column is WIDE pane
        assertEquals(PickerMode.ChangePane(Pane.WIDE), logic.pickerMode)
    }

    @Test fun `MIRROR action does NOT open picker — no sync needed`() {
        // Verify that non-picker actions leave pickerMode null so the controller's
        // "if (logic.pickerMode != null) syncLogicToView()" branch is NOT taken.
        val logic = SplitMenuLogic()
        logic.onPillTap()
        logic.onMenuAction(MenuAction.MIRROR, SplitSide.RIGHT)
        assertNull(logic.pickerMode)
    }

    @Test fun `SWAP action does NOT open picker — no sync needed`() {
        val logic = SplitMenuLogic()
        logic.onPillTap()
        logic.onMenuAction(MenuAction.SWAP, SplitSide.RIGHT)
        assertNull(logic.pickerMode)
    }
}
