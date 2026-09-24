package com.bydmate.app.ui.automation

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Width decisions of the Automation rule list and header. Pure, so they are tested without
// Compose; the screen feeds them the measured widths. All widths are in dp.

internal val MIN_TOUCH = 48.dp
internal val GRID_SPACING = 12.dp
internal const val MAX_GRID_COLUMNS = 3
/** The three 1dp lines between the four foot buttons of a card. */
private val FOOT_DIVIDERS = 3.dp
internal val ROW_ACTION_SPACING = 6.dp
/** List row: start 16 + end 4 padding, 12dp gaps text|actions|switch, 64dp switch slot. */
internal val ROW_PADDING = 20.dp
private val ROW_GAPS = 24.dp
internal val SWITCH_SLOT = 64.dp
internal val ICON_ACTIONS_WIDTH = MIN_TOUCH * 4 + ROW_ACTION_SPACING * 3

/** Width of one foot button of a card when [width] holds [columns] cards. */
internal fun footCellWidth(width: Dp, columns: Int): Dp =
    ((width - GRID_SPACING * (columns - 1)) / columns - FOOT_DIVIDERS) / 4

/** Compose splits cards and buttons into whole pixels, which can take up to ~1.3 px off a
 *  button; this keeps the 48dp cell after rounding. */
private val PX_ROUNDING_SLACK = 2.dp

/** Most columns (up to 3) in which every card button keeps a 48dp wide cell; 1 at the least. */
internal fun gridColumns(width: Dp): Int =
    (MAX_GRID_COLUMNS downTo 2).firstOrNull { footCellWidth(width, it) >= MIN_TOUCH + PX_ROUNDING_SLACK } ?: 1

enum class ListActionMode {
    /** Four labeled buttons beside the text. */
    LABELED,
    /** Four 48x48dp icon buttons beside the text. */
    ICONS,
    /** The buttons move under the text across the row, labeled. */
    BELOW_LABELED,
    /** The buttons move under the text across the row, icons only. */
    BELOW_ICONS,
}

/**
 * The buttons stay beside the text while the text keeps [minTextWidth]: labeled first, then
 * icon-only. Narrower, they move under the text, labeled while [labeledActionsWidth] fits the row.
 */
internal fun listActionMode(rowWidth: Dp, labeledActionsWidth: Dp, minTextWidth: Dp): ListActionMode {
    val textRoom = rowWidth - ROW_PADDING - ROW_GAPS - SWITCH_SLOT
    return when {
        textRoom - labeledActionsWidth >= minTextWidth -> ListActionMode.LABELED
        textRoom - ICON_ACTIONS_WIDTH >= minTextWidth -> ListActionMode.ICONS
        labeledActionsWidth <= rowWidth - ROW_PADDING -> ListActionMode.BELOW_LABELED
        else -> ListActionMode.BELOW_ICONS
    }
}

/** Header: title with filters and the action group share one line only when both fit whole. */
internal fun headerOnOneLine(leftWidth: Int, rightWidth: Int, gap: Int, width: Int): Boolean =
    leftWidth + gap + rightWidth <= width
