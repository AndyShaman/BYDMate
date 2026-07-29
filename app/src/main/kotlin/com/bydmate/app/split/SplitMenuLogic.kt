package com.bydmate.app.split

import androidx.annotation.StringRes
import com.bydmate.app.R

/**
 * Title resource for the app picker: names the pane the choice applies to.
 *
 * [PickerMode.ChangePane] is described by the visual side (left/right column) so the header
 * matches what the user sees; the side depends on [narrowSide]. The first-pair steps run
 * before the side is known, so they are described by role instead: wide (2/3), then narrow (1/3).
 *
 * Pure: returns a resource id, no Android runtime involved.
 *
 * @return string resource id, or 0 when no picker is open.
 */
@StringRes
fun pickerTitleRes(mode: PickerMode?, narrowSide: SplitSide): Int = when (mode) {
    null -> 0
    is PickerMode.ChangePane -> when (SplitMenuLogic.paneToSide(mode.pane, narrowSide)) {
        SplitSide.LEFT -> R.string.split_picker_title_left
        SplitSide.RIGHT -> R.string.split_picker_title_right
    }
    is PickerMode.FirstPairStep1 -> R.string.split_picker_title_wide
    is PickerMode.FirstPairStep2 -> R.string.split_picker_title_narrow
}

/**
 * Actions the user can trigger from the split-screen pill menu.
 * The order matches the visual order of items in the menu (top to bottom).
 */
enum class MenuAction { MIRROR, SWAP, PICK_LEFT, PICK_RIGHT, EXIT }

/**
 * Picker modes: either replacing one pane in an active split, or picking
 * the initial pair in a two-step sequence (wide 2/3 first, then narrow 1/3).
 */
sealed class PickerMode {
    /**
     * Changing one pane of an active split session.
     *
     * [closedPkg] is non-null when the picker was opened automatically because the pane's app
     * was closed by the user (PaneClosed event). In that case, system back should restore the
     * closed app rather than simply dismissing the picker. Null = voluntary picker (opened from
     * the pill menu), where back just dismisses.
     */
    data class ChangePane(val pane: Pane, val closedPkg: String? = null) : PickerMode()

    /** First-pair sequence — step 1: picking the wide (2/3) app. */
    object FirstPairStep1 : PickerMode()

    /** First-pair sequence — step 2: picking the narrow (1/3) app; [widePkg] already chosen. */
    data class FirstPairStep2(val widePkg: String) : PickerMode()
}

/**
 * Commands returned by state-machine transitions that the caller must execute.
 * The logic itself is pure (no coroutines, no Android deps).
 */
sealed class MenuCmd {
    object None : MenuCmd()
    object Mirror : MenuCmd()
    object SwapApps : MenuCmd()
    data class ChangeApp(val pane: Pane, val pkg: String) : MenuCmd()
    data class StartSplit(val pair: SplitPair) : MenuCmd()
    object Exit : MenuCmd()
}

/**
 * Pure-JVM state machine for the split-screen pill menu and app picker.
 *
 * Callers fire event methods (onPillTap, onMenuAction, etc.) and receive [MenuCmd]
 * values indicating which session operation to run — no coroutines or Android deps here.
 *
 * @param clock          Provides current time in ms; injectable so tests control time.
 * @param menuDurationMs Auto-hide deadline after the menu appears (default 4 s).
 */
class SplitMenuLogic(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val menuDurationMs: Long = 4_000L,
) {
    // ── Observable state (written only by this class) ─────────────────────────

    var isMenuVisible: Boolean = false
        private set

    var menuHideDeadline: Long = 0L
        private set

    var pickerMode: PickerMode? = null
        private set

    // ── User-interaction events ───────────────────────────────────────────────

    /** Pill tapped: show (or refresh deadline of) the upward menu. */
    fun onPillTap() {
        isMenuVisible = true
        menuHideDeadline = clock() + menuDurationMs
    }

    /**
     * A menu item was activated.
     *
     * @param action     Which item was tapped.
     * @param narrowSide The current narrow-pane side; required for left/right → [Pane] mapping.
     * @return Command to execute; [MenuCmd.None] when the picker opens instead.
     */
    fun onMenuAction(action: MenuAction, narrowSide: SplitSide): MenuCmd {
        isMenuVisible = false
        menuHideDeadline = 0L
        return when (action) {
            MenuAction.MIRROR -> MenuCmd.Mirror
            MenuAction.SWAP -> MenuCmd.SwapApps
            MenuAction.PICK_LEFT -> {
                pickerMode = PickerMode.ChangePane(sideToPane(SplitSide.LEFT, narrowSide))
                MenuCmd.None
            }
            MenuAction.PICK_RIGHT -> {
                pickerMode = PickerMode.ChangePane(sideToPane(SplitSide.RIGHT, narrowSide))
                MenuCmd.None
            }
            MenuAction.EXIT -> MenuCmd.Exit
        }
    }

    /**
     * User selected an app from the picker.
     *
     * - [PickerMode.ChangePane]: returns [MenuCmd.ChangeApp] and closes the picker.
     * - [PickerMode.FirstPairStep1]: stores the wide pkg, advances to step 2, returns [MenuCmd.None].
     * - [PickerMode.FirstPairStep2]: builds the pair and returns [MenuCmd.StartSplit]; narrow on RIGHT by default.
     */
    fun onAppPicked(pkg: String): MenuCmd {
        return when (val mode = pickerMode) {
            is PickerMode.ChangePane -> {
                pickerMode = null
                MenuCmd.ChangeApp(mode.pane, pkg)
            }
            is PickerMode.FirstPairStep1 -> {
                // Wide app chosen → advance to step 2.
                pickerMode = PickerMode.FirstPairStep2(widePkg = pkg)
                MenuCmd.None
            }
            is PickerMode.FirstPairStep2 -> {
                // Same-app guard: ignore if user somehow picks the same app as the wide pane.
                if (pkg == mode.widePkg) return MenuCmd.None
                // Narrow app chosen → assemble pair; narrow on RIGHT by default.
                pickerMode = null
                MenuCmd.StartSplit(
                    SplitPair(narrowPkg = pkg, widePkg = mode.widePkg, narrowSide = SplitSide.RIGHT)
                )
            }
            null -> MenuCmd.None
        }
    }

    /** Force-dismiss picker (e.g. session ended while picker was open). */
    fun dismissPicker() {
        pickerMode = null
    }

    /**
     * Opens the picker for [pane] in change-app mode (e.g. after a PaneClosed event).
     * Hides the menu if it was visible.
     *
     * [closedPkg] should be the package that was in [pane] before it closed, so that system back
     * can restore it. Pass null for a voluntary (pill-menu) change-app picker.
     */
    fun openPickerForPane(pane: Pane, closedPkg: String? = null) {
        isMenuVisible = false
        menuHideDeadline = 0L
        pickerMode = PickerMode.ChangePane(pane, closedPkg)
    }

    /**
     * System back was pressed while the picker was open.
     *
     * - Pane-closed picker ([PickerMode.ChangePane.closedPkg] != null): returns
     *   [MenuCmd.ChangeApp] to restore the closed app into its pane (undo the close).
     * - Voluntary picker or any other mode: returns [MenuCmd.None] (simple dismiss).
     *
     * Always clears [pickerMode].
     */
    fun onPickerBack(): MenuCmd {
        val mode = pickerMode as? PickerMode.ChangePane
        pickerMode = null
        return if (mode?.closedPkg != null) {
            MenuCmd.ChangeApp(mode.pane, mode.closedPkg)
        } else {
            MenuCmd.None
        }
    }

    /**
     * Opens the first-pair picker at step 1 (pick the wide 2/3 app first).
     * Entry point for the Task-5 split widget / settings sheet.
     */
    fun showFirstPairPicker() {
        isMenuVisible = false
        menuHideDeadline = 0L
        pickerMode = PickerMode.FirstPairStep1
    }

    /**
     * Clock-driven auto-hide check. Call periodically while the menu is visible.
     * @return true if the menu was hidden on this tick (deadline expired).
     */
    fun onTimerTick(): Boolean {
        if (!isMenuVisible) return false
        if (clock() >= menuHideDeadline) {
            isMenuVisible = false
            menuHideDeadline = 0L
            return true
        }
        return false
    }

    // ── Pure geometry helpers ─────────────────────────────────────────────────

    companion object {
        /**
         * Maps a screen side (left/right visual column) to its [Pane] role.
         *
         *   narrow-right: LEFT column → WIDE, RIGHT column → NARROW.
         *   narrow-left:  LEFT column → NARROW, RIGHT column → WIDE.
         */
        fun sideToPane(side: SplitSide, narrowSide: SplitSide): Pane = when (narrowSide) {
            SplitSide.RIGHT -> if (side == SplitSide.RIGHT) Pane.NARROW else Pane.WIDE
            SplitSide.LEFT  -> if (side == SplitSide.LEFT)  Pane.NARROW else Pane.WIDE
        }

        /**
         * Inverse of [sideToPane]: which screen side (left/right visual column) a [Pane]
         * currently occupies. The narrow pane sits on [narrowSide], the wide one opposite.
         */
        fun paneToSide(pane: Pane, narrowSide: SplitSide): SplitSide = when (pane) {
            Pane.NARROW -> narrowSide
            Pane.WIDE -> if (narrowSide == SplitSide.RIGHT) SplitSide.LEFT else SplitSide.RIGHT
        }
    }
}
