package com.bydmate.app.ui.automation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.delay
import com.bydmate.app.diagnostics.Trace
import com.bydmate.app.diagnostics.TraceArea
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.toOffset
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.zIndex
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Share
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.bydmate.app.R
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.time.ZoneId
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.RuleLogEntity
import com.bydmate.app.ui.components.bydSwitchColors
import com.bydmate.app.ui.theme.*

// --- Rule list: rows and cards ---

private val RULE_CARD_SHAPE = RoundedCornerShape(12.dp)
/** Card and row buttons: 20dp icon over a 14sp label, no frame. */
internal val ACTION_LABEL_STYLE = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
/** A labeled list button: at least this wide, else its label plus 8dp each side. */
private val ROW_CELL_MIN = 56.dp
private val ROW_CELL_PADDING = 8.dp
/** A labeled button is 72dp tall to touch. */
private val ROW_CELL_HEIGHT = 72.dp
/** The thin line between the row text and its buttons, with 8dp on each side. */
internal val ROW_DIVIDER_SLOT = 17.dp
/** Least width the list row text keeps beside the buttons; in sp so it grows with the text size. */
internal val MIN_ROW_TEXT = 240.sp

/** One of the four buttons of a rule: Изменить / Копия / Отправить / Удалить. */
internal class RuleAction(
    val label: String,
    val icon: ImageVector,
    val destructive: Boolean,
    val enabled: Boolean,
    val onClick: () -> Unit,
)

@Composable
internal fun ruleActions(
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onShare: () -> Unit,
    shareEnabled: Boolean,
    onDelete: () -> Unit,
): List<RuleAction> = listOf(
    RuleAction(stringResource(R.string.automation_menu_edit), Icons.Outlined.Edit, false, true, onEdit),
    RuleAction(stringResource(R.string.auto_ui_card_copy), Icons.Outlined.ContentCopy, false, true, onDuplicate),
    RuleAction(stringResource(R.string.auto_ui_card_share), Icons.Outlined.Share, false, shareEnabled, onShare),
    RuleAction(stringResource(R.string.automation_menu_delete), Icons.Outlined.Delete, true, true, onDelete),
)

private fun RuleAction.contentColor(): Color = when {
    !enabled -> TextMuted
    destructive -> AccentOrange
    else -> AccentGreen
}

/** Widths of [labels] on one line at the current text size. */
@Composable
internal fun labelWidths(labels: List<String>, style: TextStyle): List<Dp> {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return labels.map { with(density) { measurer.measure(it, style, maxLines = 1, softWrap = false).size.width.toDp() } }
}

internal fun rowCellWidth(labelWidth: Dp): Dp = maxOf(ROW_CELL_MIN, labelWidth + ROW_CELL_PADDING * 2)

/**
 * Today's day number, re-read when the screen comes back and at every midnight: a remember key
 * for the words that say «сегодня» or «вчера».
 */
@Composable
internal fun rememberToday(): Long {
    var today by remember { mutableLongStateOf(epochDay(System.currentTimeMillis(), ZoneId.systemDefault())) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) today = epochDay(System.currentTimeMillis(), ZoneId.systemDefault())
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(msUntilNextDay(System.currentTimeMillis(), ZoneId.systemDefault()))
            today = epochDay(System.currentTimeMillis(), ZoneId.systemDefault())
        }
    }
    return today
}

/** The card phrase: conditions in the text colour, actions in teal; a disabled rule is all grey. */
@Composable
private fun rulePhraseText(rule: RuleEntity): AnnotatedString {
    val context = LocalContext.current
    val today = rememberToday()
    val phrase = remember(rule.triggers, rule.triggerLogic, rule.actions, today, context) { rulePhrase(rule, context) }
    if (!rule.enabled) return AnnotatedString(phrase.text(context))
    val full = phrase.text(context)
    val at = if (phrase.actions.isEmpty()) -1 else full.lastIndexOf(phrase.actions)
    return buildAnnotatedString {
        if (at < 0) {
            append(full)
        } else {
            append(full.substring(0, at))
            withStyle(SpanStyle(color = AccentTeal)) { append(phrase.actions) }
            append(full.substring(at + phrase.actions.length))
        }
    }
}

@Composable
private fun RulePhraseLine(rule: RuleEntity, modifier: Modifier = Modifier) {
    Text(
        rulePhraseText(rule), fontSize = 16.sp, lineHeight = 22.sp,
        color = if (rule.enabled) TextPrimary else TextSecondary, modifier = modifier,
    )
}

@Composable
private fun RuleName(rule: RuleEntity, modifier: Modifier = Modifier) {
    Text(
        rule.name, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold,
        color = if (rule.enabled) TextPrimary else TextSecondary, modifier = modifier,
    )
}

internal fun RuleStatusKind.icon(): ImageVector = when (this) {
    RuleStatusKind.OK -> Icons.Outlined.CheckCircle
    RuleStatusKind.CANCELLED -> Icons.Outlined.Block
    RuleStatusKind.SKIPPED -> Icons.Outlined.Block
    RuleStatusKind.ERROR -> Icons.Outlined.ErrorOutline
    RuleStatusKind.NEVER -> Icons.Outlined.RemoveCircleOutline
    RuleStatusKind.PLANNED -> Icons.Outlined.CalendarMonth
}

internal fun RuleStatusKind.color(): Color = when (this) {
    RuleStatusKind.OK -> AccentGreen
    RuleStatusKind.CANCELLED, RuleStatusKind.SKIPPED -> AccentOrange
    RuleStatusKind.ERROR -> SocRed
    RuleStatusKind.NEVER -> TextSecondary
    RuleStatusKind.PLANNED -> AccentBlue
}

/**
 * The rule's last result with its time, as a pill: only the icon is coloured. A tap opens this
 * rule's journal; the pill is 32dp to see and 48dp to touch.
 */
@Composable
private fun RuleStatusChip(rule: RuleEntity, last: RuleLogEntity?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val today = rememberToday()
    val status = remember(rule, last, today, context) { ruleStatus(rule, last, context) }
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(shape)
            .border(1.dp, CardBorder, shape)
            .clickable(onClick = onClick)
            .heightIn(min = 32.dp)
            .padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(status.kind.icon(), null, tint = status.kind.color(), modifier = Modifier.size(18.dp))
        Text(status.text, fontSize = 14.sp, lineHeight = 18.sp, color = TextSecondary)
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = TextSecondary, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun RuleDot(enabled: Boolean) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(if (enabled) AccentGreen else Color.Transparent)
            .border(1.5.dp, if (enabled) AccentGreen else TextSecondary, CircleShape)
    )
}

/** The rule's switch centred in a 64x48dp slot; the M3 switch itself is 52x48dp to touch. */
@Composable
private fun RuleSwitch(enabled: Boolean, onToggle: () -> Unit, ruleId: Long) {
    Box(Modifier.size(SWITCH_SLOT, 48.dp), contentAlignment = Alignment.Center) {
        Switch(checked = enabled, onCheckedChange = {
            Trace.event(TraceArea.USER, "rule-toggle", "rule" to ruleId, "on" to !enabled)
            onToggle()
        }, colors = bydSwitchColors())
    }
}

@Composable
private fun RuleContainer(
    rule: RuleEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (rule.enabled) CardSurface else CardSurface.copy(alpha = 0.55f)
        ),
        shape = RULE_CARD_SHAPE,
        border = androidx.compose.foundation.BorderStroke(
            1.5.dp,
            if (rule.enabled) AccentGreen.copy(alpha = 0.25f) else CardBorder
        ),
        content = content,
    )
}

/** Dot, name and the status pill (wrapping under the name when they do not fit), the phrase below. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun RuleRowText(rule: RuleEntity, last: RuleLogEntity?, onStatus: () -> Unit, modifier: Modifier) {
    Column(modifier) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.align(Alignment.CenterVertically), verticalAlignment = Alignment.CenterVertically) {
                RuleDot(rule.enabled)
                Spacer(Modifier.width(8.dp))
                RuleName(rule)
            }
            RuleStatusChip(rule, last, onStatus, Modifier.align(Alignment.CenterVertically))
        }
        RulePhraseLine(rule, Modifier.padding(start = 16.dp))
    }
}

/**
 * List view: text on the left, then behind a thin line four buttons (20dp icon over a 14sp
 * label, no frame) and the switch, all centred on the row's height. In a narrow window ([mode])
 * the buttons drop their labels, then move under the text across the row.
 */
@Composable
internal fun RuleListRow(
    rule: RuleEntity,
    last: RuleLogEntity?,
    actions: List<RuleAction>,
    cellWidths: List<Dp>,
    mode: ListActionMode,
    onToggle: () -> Unit,
    onClick: () -> Unit,
    onStatus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val padding = Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)
    RuleContainer(rule, onClick, modifier) {
        when (mode) {
            ListActionMode.LABELED, ListActionMode.ICONS -> Row(
                modifier = padding.height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                RuleRowText(rule, last, onStatus, Modifier.weight(1f).padding(end = 8.dp))
                Box(Modifier.fillMaxHeight().width(ROW_DIVIDER_SLOT).padding(horizontal = 8.dp).background(CardBorder))
                val labeled = mode == ListActionMode.LABELED
                actions.forEachIndexed { i, action ->
                    ActionCell(action, labeled, Modifier.width(if (labeled) cellWidths[i] else MIN_TOUCH))
                }
                RuleSwitch(rule.enabled, onToggle, rule.id)
            }
            ListActionMode.BELOW_LABELED, ListActionMode.BELOW_ICONS -> Column(
                modifier = padding,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RuleRowText(rule, last, onStatus, Modifier.weight(1f).padding(end = 8.dp))
                    RuleSwitch(rule.enabled, onToggle, rule.id)
                }
                HorizontalDivider(color = CardBorder, modifier = Modifier.padding(end = 12.dp))
                Row(Modifier.fillMaxWidth()) {
                    val labeled = mode == ListActionMode.BELOW_LABELED
                    actions.forEach { ActionCell(it, labeled, Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** A rule button: the icon over its label, or the icon alone in a 48x48dp cell. */
@Composable
private fun ActionCell(action: RuleAction, showLabel: Boolean, modifier: Modifier, labeledHeight: Dp = ROW_CELL_HEIGHT) {
    val color = action.contentColor()
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = action.enabled, role = Role.Button, onClick = action.onClick)
            .heightIn(min = if (showLabel) labeledHeight else MIN_TOUCH)
            .padding(horizontal = 2.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Icon(action.icon, if (showLabel) null else action.label, tint = color, modifier = Modifier.size(20.dp))
        if (showLabel) {
            Text(action.label, color = color, style = ACTION_LABEL_STYLE, maxLines = 1, softWrap = false)
        }
    }
}

/**
 * Grid view: name and switch, the phrase (wrapping, never cut), the status pill, then four
 * buttons across the card. [bodyMinHeight] is the tallest body of the card's grid row, so the
 * cards of a row come out the same height; [onBodyHeight] reports this card's own.
 */
@Composable
internal fun RuleGridCard(
    rule: RuleEntity,
    last: RuleLogEntity?,
    actions: List<RuleAction>,
    showLabels: Boolean,
    bodyMinHeight: Dp,
    onBodyHeight: (Int) -> Unit,
    onToggle: () -> Unit,
    onClick: () -> Unit,
    onStatus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RuleContainer(rule, onClick, modifier) {
        Box(Modifier.heightIn(min = bodyMinHeight)) {
            Column(
                modifier = Modifier
                    .onSizeChanged { onBodyHeight(it.height) }
                    .padding(start = 14.dp, end = 4.dp, bottom = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RuleDot(rule.enabled)
                    Spacer(Modifier.width(8.dp))
                    RuleName(rule, Modifier.weight(1f))
                    RuleSwitch(rule.enabled, onToggle, rule.id)
                }
                RulePhraseLine(rule, Modifier.padding(end = 10.dp))
                RuleStatusChip(rule, last, onStatus)
            }
        }
        HorizontalDivider(color = CardBorder)
        Row(Modifier.fillMaxWidth()) {
            actions.forEach { ActionCell(it, showLabels, Modifier.weight(1f), labeledHeight = FOOT_CELL_HEIGHT) }
        }
    }
}

/** A card's foot button: 56dp tall with its label. */
private val FOOT_CELL_HEIGHT = 56.dp
internal val FOOT_CELL_PADDING = 4.dp

/** Title with filters and the action group: one line when both fit whole, else stacked. */
@Composable
internal fun HeaderLayout(content: @Composable () -> Unit) {
    Layout(content = content, modifier = Modifier.fillMaxWidth()) { measurables, constraints ->
        val width = constraints.maxWidth
        val gap = 12.dp.roundToPx()
        val lineGap = 4.dp.roundToPx()
        val left = measurables[0].measure(Constraints(maxWidth = width))
        val right = measurables[1].measure(Constraints(maxWidth = width))
        if (headerOnOneLine(left.width, right.width, gap, width)) {
            val height = maxOf(left.height, right.height)
            layout(width, height) {
                left.place(0, (height - left.height) / 2)
                right.place(width - right.width, (height - right.height) / 2)
            }
        } else {
            layout(width, left.height + lineGap + right.height) {
                left.place(0, 0)
                right.place(width - right.width, left.height + lineGap)
            }
        }
    }
}

/** Two-button list/grid switch at the right end of the header: 48x40dp each, 48dp to touch. */
@Composable
internal fun ViewModeToggle(mode: RuleViewMode, onChange: (RuleViewMode) -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(Modifier.height(48.dp)) {
        Row {
            ViewModeCell(
                Icons.AutoMirrored.Outlined.ViewList, stringResource(R.string.automation_view_list),
                mode == RuleViewMode.LIST, RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp),
            ) { onChange(RuleViewMode.LIST) }
            ViewModeCell(
                Icons.Outlined.GridView, stringResource(R.string.automation_view_grid),
                mode == RuleViewMode.GRID, RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp),
            ) { onChange(RuleViewMode.GRID) }
        }
        // The outline and the middle line are drawn over the 40dp visible part only.
        Box(
            Modifier
                .matchParentSize()
                .padding(vertical = 4.dp)
                .border(1.5.dp, CardBorder, shape)
        )
        Box(
            Modifier
                .align(Alignment.Center)
                .width(1.5.dp)
                .height(40.dp)
                .background(CardBorder)
        )
    }
}

@Composable
private fun ViewModeCell(icon: ImageVector, label: String, selected: Boolean, shape: Shape, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(vertical = 4.dp)
            .background(if (selected) AccentGreen.copy(alpha = 0.18f) else Color.Transparent, shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (selected) AccentGreen else TextSecondary, modifier = Modifier.size(22.dp))
    }
}

@Composable
internal fun HeaderOutlinedButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 14.dp),
        // On the 40dp button itself, not around its 48dp touch area.
        border = androidx.compose.foundation.BorderStroke(1.5.dp, CardBorder),
    ) {
        Text(label, fontSize = 14.sp)
        if (icon != null) {
            Spacer(Modifier.width(6.dp))
            Icon(icon, null, modifier = Modifier.size(18.dp))
        }
    }
}

// --- Drag to reorder (#249) ---

/** How much a held rule grows, so the driver sees which one they picked up (as on «Техника»). */
private const val LIFT_SCALE = 1.03f

/**
 * The band along the top and bottom edge of the list: a held rule dragged into it scrolls the
 * list, faster the deeper it goes, at full speed once it reaches the edge.
 */
private val AUTO_SCROLL_EDGE = 64.dp

/** Full auto-scroll speed, per frame. */
private val AUTO_SCROLL_STEP = 16.dp

/** A rule on screen: its id, its index in the list as last laid out, and its bounds, px. */
internal class RuleCell(val id: Long, val index: Int, val rect: Rect)

/** What the drag needs of the LazyColumn or the LazyVerticalGrid it runs in. */
internal interface RuleCells {
    val scroll: ScrollableState
    /** List rows only move up and down; grid cards follow the finger both ways. */
    val vertical: Boolean
    val viewportHeight: Int
    fun visible(): List<RuleCell>
    /**
     * Keeps the first row on screen where it is across the next re-order: a lazy layout holds
     * on to its first item's key, so it would scroll along with a rule moved off the top.
     */
    fun pin()
}

internal class ListCells(private val state: LazyListState) : RuleCells {
    override val scroll: ScrollableState get() = state
    override val vertical = true
    override val viewportHeight: Int get() = state.layoutInfo.viewportSize.height
    // Read afresh on every call: a small scroll moves the items of the same layout info object
    // in place, so anything cached by that object would hand back stale offsets.
    override fun visible(): List<RuleCell> {
        val info = state.layoutInfo
        val width = info.viewportSize.width.toFloat()
        return info.visibleItemsInfo.mapNotNull { item ->
            (item.key as? Long)?.let { RuleCell(it, item.index, Rect(0f, item.offset.toFloat(), width, (item.offset + item.size).toFloat())) }
        }
    }
    override fun pin() = state.requestScrollToItem(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
}

internal class GridCells(private val state: LazyGridState) : RuleCells {
    override val scroll: ScrollableState get() = state
    override val vertical = false
    override val viewportHeight: Int get() = state.layoutInfo.viewportSize.height
    override fun visible(): List<RuleCell> = state.layoutInfo.visibleItemsInfo.mapNotNull { item ->
        (item.key as? Long)?.let { RuleCell(it, item.index, Rect(item.offset.toOffset(), item.size.toSize())) }
    }
    override fun pin() = state.requestScrollToItem(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
}

/**
 * A rule held and dragged in the list or the grid. The lifted card follows the finger; once its
 * centre is over another rule it takes that rule's place in [order] and the layout re-flows
 * under it. Nothing is saved while the finger is down: the release reports one move, from where
 * the rule was lifted to where it was let go. A cancelled gesture, or a filter switched or a rule
 * added or deleted under the finger, lets go without a move: see [finish].
 */
@Stable
internal class RuleDragState(
    private val cells: RuleCells,
    private val rules: State<List<RuleEntity>>,
    private val onDrop: State<(moved: Long, target: Long) -> Unit>,
) {
    /** The held rule; null when none is. */
    var held by mutableStateOf<Long?>(null)
        private set
    /** Rule ids as on screen from the lift on. */
    private var order by mutableStateOf(emptyList<Long>())
    private var start = emptyList<Long>()
    /** The rules the drag started from: the list keeps the drag's order until new ones arrive. */
    private var liftedFrom: List<RuleEntity>? = null
    /** The rules [frame] last checked against the drag's order. */
    private var checkedRules: List<RuleEntity>? = null
    private var startRect = Rect.Zero
    private var dragged by mutableStateOf(Offset.Zero)
    /** Whether the finger has moved the card up, and down, since the lift: see [edgeScroll]. */
    private var movedUp = false
    private var movedDown = false

    /** Where the finger has the held card, in the list. */
    private val liftedRect get() = startRect.translate(dragged)

    /**
     * What the list shows: the drag's own order while a rule is held, and after the drop until
     * the view model's re-ordered list comes back, so the list does not flash the old order.
     */
    fun shown(rules: List<RuleEntity>): List<RuleEntity> {
        if (held == null && rules !== liftedFrom) return rules
        val byId = rules.associateBy { it.id }
        return order.mapNotNull { byId[it] }
    }

    /** Lifts the rule under [at]; false when there is none. */
    fun lift(at: Offset): Boolean {
        val cell = cells.visible().firstOrNull { it.rect.contains(at) } ?: return false
        start = shown(rules.value).map { it.id }
        liftedFrom = rules.value
        checkedRules = rules.value
        order = start
        startRect = cell.rect
        dragged = Offset.Zero
        movedUp = false
        movedDown = false
        held = cell.id
        return true
    }

    fun dragBy(amount: Offset) {
        dragged += if (cells.vertical) Offset(0f, amount.y) else amount
        if (amount.y < 0f) movedUp = true
        if (amount.y > 0f) movedDown = true
    }

    /**
     * One frame while a rule is held: scrolls while the card is in an edge band and moves the held
     * rule to where the card is now. Every frame, not only on a drag or a scroll, so a move the
     * layout had not caught up with yet is taken on the next one. Rules shown changing under the
     * finger let go without a move: the drag's order no longer matches what the list shows.
     */
    suspend fun frame(edge: Float, maxStep: Float) {
        if (held == null) return
        val now = rules.value
        if (now !== checkedRules) {
            checkedRules = now
            if (!sameRules(order, now)) {
                finish(commit = false)
                return
            }
        }
        val speed = edgeScroll(liftedRect, cells.viewportHeight, edge, movedUp, movedDown)
        if (speed != 0f) cells.scroll.scrollBy(speed * maxStep)
        follow()
    }

    /** How far the held card is drawn from its place in the layout: to where the finger has it. */
    fun liftOffset(): Offset {
        val slot = cells.visible().firstOrNull { it.id == held } ?: return Offset.Zero
        return liftedRect.topLeft - slot.rect.topLeft
    }

    /**
     * Ends the hold, every way it ends. A finger lifted ([commit]) takes the card's last position
     * and moves a rule that ended up elsewhere into the place of the rule that was there, once,
     * while the rules shown are still the ones held over. A cancelled gesture or changed rules
     * save nothing, and the card drops back into the list's own order.
     */
    fun finish(commit: Boolean) {
        val id = held ?: return
        val save = commit && sameRules(order, rules.value)
        // The finger can cross onto the next rule and lift before a frame has taken the move.
        if (save) follow()
        held = null
        if (!save) {
            liftedFrom = null
            return
        }
        val target = start.getOrNull(order.indexOf(id))
        if (target != null && target != id) onDrop.value(id, target)
    }

    /** Moves the held rule into the place of the rule [dragTarget] picks for the lifted card. */
    private fun follow() {
        val id = held ?: return
        val target = dragTarget(cells.visible(), order, id, liftedRect) ?: return
        cells.pin()
        order = RuleOrder.move(order, id, target)
    }
}

/**
 * The rule whose place the held one takes, with the lifted [card] where the finger has it: the
 * rule under the card's centre; the first or the last on screen when the centre is past them; the
 * nearest one when the held rule's own place has scrolled off screen, which brings it back under
 * the finger. Null keeps the held rule where it is.
 */
internal fun dragTarget(visible: List<RuleCell>, order: List<Long>, held: Long, card: Rect): Long? {
    // The layout has not caught up with the last move yet: its bounds would undo that move.
    if (visible.any { order.getOrNull(it.index) != it.id }) return null
    val centre = card.center
    val slot = visible.firstOrNull { it.id == held }
    val target = visible.firstOrNull { it.rect.contains(centre) }
        ?: pastEdge(visible, centre)
        ?: (if (slot == null) visible.minByOrNull { it.rect.distanceSquaredTo(centre) } else null)
        ?: return null
    if (target.id == held) return null
    // Off screen, the held rule has no place on screen to swap straight back into.
    if (slot == null) return target.id
    // Rows differ in height: the centre has to reach the place the held rule is about to take,
    // or a taller neighbour would still hold it after the move and swap the two straight back.
    val reached = if (target.index > slot.index) {
        centre.y >= target.rect.bottom - slot.rect.height
    } else {
        centre.y <= target.rect.top + slot.rect.height
    }
    return if (reached) target.id else null
}

/** Whether [rules] are the ones [order] holds, in any order: the same rules, not a filter's subset. */
internal fun sameRules(order: List<Long>, rules: List<RuleEntity>): Boolean =
    rules.mapTo(HashSet()) { it.id } == order.toSet()

/**
 * The first rule on screen when [centre] is before it: above it, or left of it on its row; the
 * last when after it: below it, or right of it on its row, as over the grid's empty last cells.
 */
private fun pastEdge(visible: List<RuleCell>, centre: Offset): RuleCell? {
    val first = visible.minByOrNull { it.index } ?: return null
    val last = visible.maxBy { it.index }
    return when {
        centre.y >= last.rect.bottom || centre.y >= last.rect.top && centre.x >= last.rect.right -> last
        centre.y < first.rect.top || centre.y < first.rect.bottom && centre.x < first.rect.left -> first
        else -> null
    }
}

private fun Rect.distanceSquaredTo(point: Offset): Float {
    val dx = maxOf(left - point.x, 0f, point.x - right)
    val dy = maxOf(top - point.y, 0f, point.y - bottom)
    return dx * dx + dy * dy
}

/**
 * How fast the held [card] scrolls the list this frame, from -1 (full speed up) to 1 (full speed
 * down): in the [edge] band along the top or the bottom of the viewport, the deeper the faster.
 * A band counts once the finger has moved the card towards its edge ([movedUp], [movedDown]), so
 * a rule lifted inside one does not set the list moving by itself. A card in both bands at once,
 * taller than the list, goes by its centre: up above the middle, down below it, still in between.
 */
internal fun edgeScroll(card: Rect, viewportHeight: Int, edge: Float, movedUp: Boolean, movedDown: Boolean): Float {
    val up = if (movedUp) (card.top - edge).coerceAtMost(0f) else 0f
    val down = if (movedDown) (card.bottom - (viewportHeight - edge)).coerceAtLeast(0f) else 0f
    return ((up + down) / edge).coerceIn(-1f, 1f)
}

/** The drag of one list or grid; while a rule is held, runs its [RuleDragState.frame] every frame. */
@Composable
internal fun rememberRuleDrag(cells: RuleCells, rules: List<RuleEntity>, onDrop: (Long, Long) -> Unit): RuleDragState {
    val latestRules = rememberUpdatedState(rules)
    val latestOnDrop = rememberUpdatedState(onDrop)
    val drag = remember(cells) { RuleDragState(cells, latestRules, latestOnDrop) }
    val holding = drag.held != null
    val density = LocalDensity.current
    LaunchedEffect(drag, holding) {
        if (!holding) return@LaunchedEffect
        val edge = with(density) { AUTO_SCROLL_EDGE.toPx() }
        val step = with(density) { AUTO_SCROLL_STEP.toPx() }
        while (drag.held != null) {
            withFrameNanos { }
            drag.frame(edge, step)
        }
    }
    return drag
}

/**
 * Long press lifts the rule under the finger, dragging moves it, letting go drops it. Taps and
 * scrolling reach the cards and the list as before: a scroll that starts first cancels the press.
 * Only a finger lifted saves the move: a gesture cancelled by the system, or by the list leaving
 * the screen (a switch to the grid, another tab), drops the card back.
 */
internal fun Modifier.ruleDrag(drag: RuleDragState): Modifier = pointerInput(drag) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val press = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
        if (!drag.lift(press.position)) return@awaitEachGesture
        var released = false
        try {
            // Held, the gesture is the drag's alone: every change is consumed on the Initial pass,
            // before the card, its switch and buttons see it, so letting go is never also a tap.
            do {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == press.id }
                if (change != null && change.pressed) drag.dragBy(change.positionChange())
                // A system cancel arrives as an up already consumed; a finger lifted does not.
                released = change?.changedToUp() == true
                change?.consume()
            } while (change?.pressed == true)
        } finally {
            drag.finish(commit = released)
        }
    }
}

/** The held rule: drawn above the rest, a little larger and with a shadow, under the finger. */
internal fun Modifier.liftedWhen(drag: RuleDragState, id: Long): Modifier =
    if (drag.held != id) this else zIndex(1f).graphicsLayer {
        val offset = drag.liftOffset()
        translationX = offset.x
        translationY = offset.y
        scaleX = LIFT_SCALE
        scaleY = LIFT_SCALE
        shadowElevation = 16.dp.toPx()
        shape = RULE_CARD_SHAPE
    }
