package com.bydmate.app.split

import com.bydmate.app.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure mapping picker mode → title resource (R6): the picker header must name the pane
 * the choice applies to.
 *
 * ChangePane is described by the actual screen side, so the mapping depends on narrowSide;
 * the first-pair steps happen before the side is known and are described by role (2/3, 1/3).
 * No Android runtime is involved — the function only returns resource ids.
 */
class SplitPickerTitleTest {

    // ── ChangePane: side follows narrowSide, not the pane role ────────────────

    @Test fun `narrow on the right — WIDE pane is the left side`() {
        assertEquals(
            R.string.split_picker_title_left,
            pickerTitleRes(PickerMode.ChangePane(Pane.WIDE), SplitSide.RIGHT),
        )
    }

    @Test fun `narrow on the right — NARROW pane is the right side`() {
        assertEquals(
            R.string.split_picker_title_right,
            pickerTitleRes(PickerMode.ChangePane(Pane.NARROW), SplitSide.RIGHT),
        )
    }

    @Test fun `narrow on the left — NARROW pane is the left side`() {
        assertEquals(
            R.string.split_picker_title_left,
            pickerTitleRes(PickerMode.ChangePane(Pane.NARROW), SplitSide.LEFT),
        )
    }

    @Test fun `narrow on the left — WIDE pane is the right side`() {
        assertEquals(
            R.string.split_picker_title_right,
            pickerTitleRes(PickerMode.ChangePane(Pane.WIDE), SplitSide.LEFT),
        )
    }

    @Test fun `pane-closed picker uses the same side-based title`() {
        assertEquals(
            R.string.split_picker_title_right,
            pickerTitleRes(PickerMode.ChangePane(Pane.NARROW, closedPkg = "pkg.closed"), SplitSide.RIGHT),
        )
    }

    // ── First-pair sequence: described by role, side is not known yet ─────────

    @Test fun `first-pair step 1 is the wide pane title on both sides`() {
        assertEquals(R.string.split_picker_title_wide, pickerTitleRes(PickerMode.FirstPairStep1, SplitSide.RIGHT))
        assertEquals(R.string.split_picker_title_wide, pickerTitleRes(PickerMode.FirstPairStep1, SplitSide.LEFT))
    }

    @Test fun `first-pair step 2 is the narrow pane title on both sides`() {
        val mode = PickerMode.FirstPairStep2(widePkg = "pkg.wide")
        assertEquals(R.string.split_picker_title_narrow, pickerTitleRes(mode, SplitSide.RIGHT))
        assertEquals(R.string.split_picker_title_narrow, pickerTitleRes(mode, SplitSide.LEFT))
    }

    @Test fun `no picker open — no title resource`() {
        assertEquals(0, pickerTitleRes(null, SplitSide.RIGHT))
    }

    // ── paneToSide is the inverse of the existing sideToPane mapping ──────────

    @Test fun `paneToSide inverts sideToPane for both narrow sides`() {
        for (narrowSide in SplitSide.entries) {
            for (side in SplitSide.entries) {
                val pane = SplitMenuLogic.sideToPane(side, narrowSide)
                assertEquals(
                    "paneToSide must return the side sideToPane was called with",
                    side,
                    SplitMenuLogic.paneToSide(pane, narrowSide),
                )
            }
        }
    }
}
