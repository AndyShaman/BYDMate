package com.bydmate.app.ui.automation

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.minimumInteractiveComponentSize
import kotlinx.coroutines.delay
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WifiTethering
import com.bydmate.app.util.appLocalizedContext
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import com.bydmate.app.data.vehicle.RearSeat
import androidx.compose.material.icons.automirrored.outlined.Send
import com.bydmate.app.data.telegram.ReportField
import com.bydmate.app.data.telegram.TELEGRAM_REPORT_KIND
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.ui.platform.LocalContext
import com.bydmate.app.ui.components.AppAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.bydmate.app.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.ScheduleSpec
import com.bydmate.app.data.automation.minuteToHHmm
import com.bydmate.app.data.automation.serviceStartTrigger
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.PlaceEntity
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.RuleLogEntity
import com.bydmate.app.data.local.entity.TriggerDef
import com.bydmate.app.ui.components.AppLaunchPickerDialog
import com.bydmate.app.ui.components.bydSwitchColors
import com.bydmate.app.ui.theme.*
import java.text.NumberFormat

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AutomationScreen(
    viewModel: AutomationViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showPlaces by rememberSaveable { mutableStateOf(false) }
    val filtered = remember(state.rules, state.filter) {
        when (state.filter) {
            RuleFilter.ALL -> state.rules
            RuleFilter.ENABLED -> state.rules.filter { it.enabled }
            RuleFilter.DISABLED -> state.rules.filter { !it.enabled }
        }
    }

    // A note (rule limit, test run refused) hides itself after a few seconds.
    state.message?.let { msg ->
        LaunchedEffect(msg) {
            delay(MESSAGE_SHOW_MS)
            viewModel.dismissMessage()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(NavyDark, NavyDeep)))
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Header: title and filters on the left; «Места» / «Журнал», a divider, then «Импорт» /
            // «+ Создать» and the list/grid toggle on the right. When both groups do not fit one
            // line, the action group moves whole under the filters (and wraps only if still too wide).
            HeaderLayout {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(stringResource(R.string.automation_tab_title), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary,
                        modifier = Modifier.align(Alignment.CenterVertically).padding(end = 12.dp))
                    // 40dp visible, the chip's own minimum interactive size makes the touch area 48dp.
                    val chipModifier = Modifier.heightIn(min = 40.dp)
                    AutoChip(stringResource(R.string.automation_filter_all), state.filter == RuleFilter.ALL, chipModifier) { viewModel.setFilter(RuleFilter.ALL) }
                    AutoChip(stringResource(R.string.automation_filter_active), state.filter == RuleFilter.ENABLED, chipModifier) { viewModel.setFilter(RuleFilter.ENABLED) }
                    AutoChip(stringResource(R.string.automation_filter_disabled), state.filter == RuleFilter.DISABLED, chipModifier) { viewModel.setFilter(RuleFilter.DISABLED) }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    HeaderOutlinedButton(stringResource(R.string.settings_section_places_title), Icons.Outlined.Place) { showPlaces = true }
                    HeaderOutlinedButton(stringResource(R.string.automation_journal_button), null) { viewModel.showJournal() }
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 6.dp)
                            .width(1.dp)
                            .height(36.dp)
                            .background(CardBorder)
                            .align(Alignment.CenterVertically)
                    )
                    HeaderOutlinedButton(stringResource(R.string.automation_import_button), Icons.Outlined.FileOpen) { viewModel.openImport() }
                    Button(
                        onClick = { viewModel.openNewRule() },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = NavyDark),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                    ) { Text(stringResource(R.string.automation_create_button), fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                    ViewModeToggle(state.viewMode, viewModel::setViewMode)
                }
            }

            Spacer(Modifier.height(12.dp))

            // Rule list: compact rows or a 3-column grid of cards, chosen in the header. A long press
            // lifts a rule, dragging moves it, letting go saves the new order.
            val actionsFor: @Composable (RuleEntity) -> List<RuleAction> = { rule ->
                ruleActions(
                    onEdit = { viewModel.openEditRule(rule) },
                    onDuplicate = { viewModel.duplicateRule(rule) },
                    onShare = { viewModel.shareRule(rule) },
                    shareEnabled = !state.shareInProgress,
                    onDelete = { viewModel.requestDelete(rule.id) },
                )
            }
            when (state.viewMode) {
                RuleViewMode.LIST -> BoxWithConstraints(Modifier.fillMaxSize()) {
                    // Narrow windows (DiLink 3/4, split screen): the buttons give way before the text.
                    val cellWidths = labelWidths(ruleActions({}, {}, {}, true, {}).map { it.label }, ACTION_LABEL_STYLE)
                        .map { rowCellWidth(it) }
                    val mode = listActionMode(
                        rowWidth = maxWidth,
                        labeledActionsWidth = cellWidths.fold(ROW_DIVIDER_SLOT) { sum, w -> sum + w } + ROW_ACTION_SPACING * 3,
                        minTextWidth = with(LocalDensity.current) { MIN_ROW_TEXT.toDp() },
                    )
                    val listState = rememberLazyListState()
                    val drag = rememberRuleDrag(remember(listState) { ListCells(listState) }, filtered, viewModel::moveRule)
                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxSize().ruleDrag(drag)
                    ) {
                        items(drag.shown(filtered), key = { it.id }) { rule ->
                            RuleListRow(
                                rule = rule,
                                last = state.lastLogs[rule.id],
                                actions = actionsFor(rule),
                                cellWidths = cellWidths,
                                mode = mode,
                                onToggle = { viewModel.toggleEnabled(rule) },
                                onClick = { viewModel.openEditRule(rule) },
                                onStatus = { viewModel.showRuleJournal(rule.id) },
                                modifier = Modifier.liftedWhen(drag, rule.id),
                            )
                        }
                    }
                }
                RuleViewMode.GRID -> BoxWithConstraints(Modifier.fillMaxSize()) {
                    // Columns keep every card button at least 48dp wide; labels go icon-only in every
                    // card when the widest one does not fit its cell.
                    val columns = gridColumns(maxWidth)
                    val labelWidth = labelWidths(ruleActions({}, {}, {}, true, {}).map { it.label }, ACTION_LABEL_STYLE).max()
                    val showLabels = labelWidth <= footCellWidth(maxWidth, columns) - FOOT_CELL_PADDING * 2
                    val gridState = rememberLazyGridState()
                    val drag = rememberRuleDrag(remember(gridState) { GridCells(gridState) }, filtered, viewModel::moveRule)
                    // Each card's natural body height by rule id: a card grows to the tallest of its row.
                    val bodyHeights = remember { mutableStateMapOf<Long, Int>() }
                    val density = LocalDensity.current
                    val shown = drag.shown(filtered)
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        state = gridState,
                        horizontalArrangement = Arrangement.spacedBy(GRID_SPACING),
                        verticalArrangement = Arrangement.spacedBy(GRID_SPACING),
                        modifier = Modifier.fillMaxSize().ruleDrag(drag)
                    ) {
                        itemsIndexed(shown, key = { _, rule -> rule.id }) { index, rule ->
                            val row = shown.subList(index - index % columns, minOf(shown.size, index - index % columns + columns))
                            val rowHeight = row.maxOf { bodyHeights[it.id] ?: 0 }
                            RuleGridCard(
                                rule = rule,
                                last = state.lastLogs[rule.id],
                                actions = actionsFor(rule),
                                showLabels = showLabels,
                                bodyMinHeight = with(density) { rowHeight.toDp() },
                                onBodyHeight = { bodyHeights[rule.id] = it },
                                onToggle = { viewModel.toggleEnabled(rule) },
                                onClick = { viewModel.openEditRule(rule) },
                                onStatus = { viewModel.showRuleJournal(rule.id) },
                                modifier = Modifier.liftedWhen(drag, rule.id),
                            )
                        }
                    }
                }
            }
        }
        // Over the editor the note is drawn inside the editor's own window instead.
        if (!state.showEditor) state.message?.let { MessagePill(it, Modifier.align(Alignment.BottomCenter)) }
    }

    // Editor dialog
    if (state.showEditor) CompositionLocalProvider(LocalRearSeats provides state.rearSeats) {
        EditorDialog(
            editing = state.editing,
            places = state.places,
            tgBotConnected = state.tgBotConnected,
            editorError = state.editorError,
            message = state.message,
            onUpdate = { viewModel.updateEditing(it) },
            onSave = { viewModel.saveRule() },
            onShare = { viewModel.shareEditing() },
            shareEnabled = !state.shareInProgress,
            onTestRun = { viewModel.testRun() },
            testRunning = state.testRunning,
            onTestAction = { viewModel.executeNow(it) },
            onDismiss = { viewModel.closeEditor() }
        )
    }

    if (showPlaces) {
        PlacesDialog(onDismiss = { showPlaces = false })
    }

    state.importFiles?.let { files ->
        ImportPickDialog(
            files = files,
            error = state.importError,
            onPick = { viewModel.pickImportFile(it) },
            onDismiss = { viewModel.closeImport() },
        )
    }

    state.importDraft?.let { draft ->
        // A new draft starts with fresh dialog state: no sub-dialog left open on an old index.
        key(draft.token) {
            ImportPreviewDialog(
                draft = draft,
                places = state.places,
                onPickPlace = { index, place -> viewModel.resolveImportPlace(draft.token, index, place) },
                onPickContact = { index, phone, name, autoDial ->
                    viewModel.resolveImportContact(draft.token, index, phone, name, autoDial)
                },
                onEnableNowChange = { viewModel.setImportEnableNow(it) },
                onConfirm = { viewModel.confirmImport() },
                onDismiss = { viewModel.closeImport() },
            )
        }
    }

    // After «Поделиться» (card or editor), before the file is written: drawn last so it sits
    // above the editor. «Продолжить» writes the file and opens the share sheet.
    if (state.pendingShare != null) {
        ShareNoteDialog(
            onContinue = { viewModel.confirmShare() },
            onDismiss = { viewModel.cancelShare() },
        )
    }

    // «Сохранить» on a rule deleted meanwhile: above the editor, save as new or close it.
    if (state.showEditor && state.editorRuleDeleted) {
        RuleDeletedDialog(
            onSaveAsNew = { viewModel.saveDeletedRuleAsNew() },
            onClose = { viewModel.closeEditor() },
            onDismiss = { viewModel.dismissRuleDeleted() },
        )
    }

    // Journal dialog
    if (state.showJournal) {
        val journalRule = state.journalRuleId?.let { id -> state.rules.firstOrNull { it.id == id } }
        JournalDialog(
            logs = if (state.journalRuleId != null) state.ruleLogs else state.logs,
            ruleName = journalRule?.name ?: state.journalRuleId?.let { "" },
            onDismiss = { viewModel.hideJournal() }
        )
    }

    // Delete confirmation
    state.showDeleteConfirm?.let {
        AppAlertDialog(
            onDismissRequest = { viewModel.cancelDelete() },
            title = { Text(stringResource(R.string.automation_delete_confirm_title), color = TextPrimary) },
            text = { Text(stringResource(R.string.automation_delete_confirm_text), color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmDelete() }) {
                    Text(stringResource(R.string.automation_delete_button), color = Color(0xFFEF4444))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelDelete() }) {
                    Text(stringResource(R.string.automation_cancel_button), color = TextSecondary)
                }
            },
            containerColor = CardSurface
        )
    }
}

// --- Action Row ---

/**
 * One action: «Выполнить сейчас» (green triangle in a light circle) at the left before the
 * action, the action's own controls across the row, then the arrows and the orange bin at the
 * far end. Every button is 48x48dp.
 */
@Composable
internal fun ActionRow(
    index: Int,
    action: ActionDef,
    places: List<PlaceEntity>,
    tgBotConnected: Boolean,
    highlight: Boolean,
    showArrows: Boolean,
    onUpdate: (ActionDef) -> Unit,
    onTest: (ActionDef) -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(CardSurface, FIELD_SHAPE)
            .border(
                if (highlight) 1.5.dp else 1.dp,
                if (highlight) AccentOrange else AccentTeal.copy(alpha = 0.2f),
                FIELD_SHAPE,
            )
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(Modifier.width(24.dp).height(MIN_TOUCH), contentAlignment = Alignment.CenterStart) {
            Text("${index + 1}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AccentTeal)
        }

        // "Выполнить сейчас" — fire this vehicle action immediately for live testing.
        // Param actions send their command; toggle actions go through the dispatcher so the
        // target is flipped from the live state. Result is shown via Toast + logcat line.
        val testable = (action.kind == "param" && action.command.isNotBlank()) ||
            (action.kind == "toggle" && !action.payload.isNullOrBlank())
        if (testable) {
            IconButton(onClick = { onTest(action) }, modifier = Modifier.size(MIN_TOUCH)) {
                Box(
                    Modifier.size(36.dp).background(AccentGreen.copy(alpha = 0.16f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.PlayArrow, stringResource(R.string.auto_a11y_run_now), tint = AccentGreen, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.width(4.dp))
        }

        Box(Modifier.weight(1f).heightIn(min = MIN_TOUCH), contentAlignment = Alignment.CenterStart) {
            val fill = Modifier.fillMaxWidth()
            when (action.kind) {
                "notification", "notification_silent", "notification_sound" ->
                    NotificationActionControls(action = action, onUpdate = onUpdate, modifier = fill)
                "app_launch" ->
                    AppLaunchActionControls(action = action, onUpdate = onUpdate, modifier = fill)
                "app_close" ->
                    AppCloseActionControls(action = action, onUpdate = onUpdate, modifier = fill)
                "media_key" ->
                    MediaKeyActionControls(payload = action.payload, modifier = fill)
                "call" ->
                    CallActionControls(action = action, onUpdate = onUpdate, modifier = fill)
                "navigate" ->
                    NavigateActionControls(action = action, places = places, onUpdate = onUpdate, modifier = fill)
                "url" ->
                    UrlActionControls(action = action, onUpdate = onUpdate, modifier = fill)
                "yandex_music" ->
                    YandexMusicActionControls(action = action, onUpdate = onUpdate, modifier = fill)
                "delay" ->
                    DelayActionControls(action = action, onUpdate = onUpdate, modifier = fill)
                "media_volume" ->
                    MediaVolumeActionControls(action = action, onUpdate = onUpdate, modifier = fill)
                "sentry" ->
                    SentryActionControls(action = action, onUpdate = onUpdate, modifier = fill)
                "hotspot" ->
                    HotspotActionControls(action = action, onUpdate = onUpdate, modifier = fill)
                "screen" ->
                    ScreenActionControls(payload = action.payload, modifier = fill)
                "cluster_projection" ->
                    ClusterActionControls(action = action, onUpdate = onUpdate, modifier = fill)
                // A toggle on sentry or the cluster goes back to its own three-state row, so the
                // user can return to Вкл/Выкл; every other target is edited by the target dropdown.
                "toggle" -> when (toggleRowSpecFor(action)) {
                    SENTRY_ROW ->
                        SentryActionControls(action = action, onUpdate = onUpdate, modifier = fill)
                    CLUSTER_ROW ->
                        ClusterActionControls(action = action, onUpdate = onUpdate, modifier = fill)
                    else ->
                        ToggleActionControls(action = action, onUpdate = onUpdate, modifier = fill)
                }
                "speak" ->
                    SpeakActionControls(action = action, onUpdate = onUpdate, modifier = fill)
                "agent_query" ->
                    AgentQueryActionControls(action = action, onUpdate = onUpdate, modifier = fill)
                "split_screen" ->
                    SplitScreenActionControls(action = action, onUpdate = onUpdate, modifier = fill)
                "split_screen_close", "split_screen_toggle" ->
                    SplitScreenStateActionControls(kind = action.kind, modifier = fill)
                TELEGRAM_REPORT_KIND ->
                    TelegramReportActionControls(
                        action = action, tgBotConnected = tgBotConnected, onUpdate = onUpdate, modifier = fill,
                    )
                else -> { // "param" (default); a graded command is edited as its level row
                    val level = LevelFamily.of(action.command)
                    if (level != null) LevelActionControls(action = action, level = level, onUpdate = onUpdate, modifier = fill)
                    else ParamActionControls(action = action, onUpdate = onUpdate, modifier = fill)
                }
            }
        }

        Spacer(Modifier.width(4.dp))
        Row(Modifier.height(MIN_TOUCH), verticalAlignment = Alignment.CenterVertically) {
            RowButtons(showArrows, onMoveUp, onMoveDown, onDelete)
        }
    }
}

@Composable
private fun ParamActionControls(
    action: ActionDef,
    onUpdate: (ActionDef) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lc = context.appLocalizedContext()
    val rearSeats = LocalRearSeats.current
    val entries = remember(lc, rearSeats) { catalogEntries(lc, rearSeats) }
    CatalogDropdown(
        selected = paramSelectedText(action, context),
        items = entries.map { it.label },
        categories = entries.map { lc.getString(it.categoryRes) },
        modifier = modifier,
        onSelect = { idx -> onUpdate(entries[idx].make(context)) }
    )
}

/** A graded command («Температура: 22 °C»): the catalog dropdown and its level controls in one line. */
@Composable
private fun LevelActionControls(
    action: ActionDef,
    level: LevelValue,
    onUpdate: (ActionDef) -> Unit,
    modifier: Modifier = Modifier
) {
    val lc = LocalContext.current.appLocalizedContext()
    val family = level.family
    LevelRow(
        icon = family.icon,
        value = level.value,
        range = family.range,
        step = family.step,
        valueText = family.valueText(level.value, lc),
        onValue = { v -> onUpdate(action.copy(command = family.command(v), displayName = family.displayName(v, lc))) },
        modifier = modifier,
    ) {
        ParamActionControls(action = action, onUpdate = onUpdate, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * One graded value in one line: icon, [header] (name or dropdown), minus, a slider with a tick
 * per step, plus and the value. Minus and plus are 48 dp and stop at the ends of [range]; the
 * value keeps a fixed minimum width so the slider does not jump when «выкл» becomes «3».
 */
@Composable
private fun LevelRow(
    icon: ImageVector,
    value: Int,
    range: IntRange,
    step: Int,
    valueText: String,
    onValue: (Int) -> Unit,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit,
) {
    Row(
        modifier.heightIn(min = MIN_TOUCH),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = AccentTeal, modifier = Modifier.size(20.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { header() }
        LevelStepButton(Icons.Outlined.Remove, stringResource(R.string.auto_a11y_level_down), value > range.first) {
            onValue(levelStep(value, range, step, up = false))
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { v -> levelSnap(v, range, step).let { if (it != value) onValue(it) } },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = ((range.last - range.first) / step - 1).coerceAtLeast(0),
            modifier = Modifier.weight(LEVEL_SLIDER_WEIGHT),
        )
        LevelStepButton(Icons.Outlined.Add, stringResource(R.string.auto_a11y_level_up), value < range.last) {
            onValue(levelStep(value, range, step, up = true))
        }
        Text(
            valueText,
            fontSize = EDITOR_TEXT,
            color = AccentTeal,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.widthIn(min = 64.dp),
        )
    }
}

/** The slider's share next to the name, so a long name does not squeeze it away. */
private const val LEVEL_SLIDER_WEIGHT = 1.2f

@Composable
private fun LevelStepButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(MIN_TOUCH)
            .clip(FIELD_SHAPE)
            .background(CardSurfaceElevated)
            .border(1.5.dp, CardBorder, FIELD_SHAPE)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = if (enabled) AccentGreen else TextMuted, modifier = Modifier.size(24.dp))
    }
}

// Delay options, in ms
private val DELAY_OPTION_MS = listOf(500L, 1000L, 2000L, 3000L, 5000L, 10000L, 30000L, 60000L)

private fun delayAction(ms: Long, context: Context): ActionDef {
    val lc = context.appLocalizedContext()
    return ActionDef(
        command = "delay_$ms",
        displayName = lc.getString(R.string.auto_ui_act_wait, durationText(ms, lc)),
        kind = "delay",
        payload = ms.toString(),
    )
}

/**
 * «Подождать [5] [мин]»: the wait between two actions, a number plus its unit, up to 24 h.
 * An invalid number clears the payload, so the validator blocks saving and names the action.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DelayActionControls(
    action: ActionDef,
    onUpdate: (ActionDef) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val locale = context.appLocalizedContext().resources.configuration.locales[0]
    fun numberText(ms: Long): Pair<String, DelayUnit> {
        val (value, unit) = splitDelay(ms)
        val fmt = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 3; isGroupingUsed = false }
        return fmt.format(value) to unit
    }
    val payloadMs = action.payload?.toLongOrNull()
    val initial = payloadMs?.let { numberText(it) }
    // The typed text lives here: our own update must not rewrite «1,» into «1».
    var text by remember { mutableStateOf(initial?.first.orEmpty()) }
    var unit by remember { mutableStateOf(initial?.second ?: DelayUnit.SECONDS) }
    LaunchedEffect(action.payload) {
        // A payload set from outside (a preset tile, another rule, a row moved into this unkeyed
        // slot) replaces the typed text; an empty one clears a number that is not this action's.
        when {
            payloadMs != null && payloadMs != parseDelayMs(text, unit) ->
                numberText(payloadMs).let { (t, u) -> text = t; unit = u }
            payloadMs == null && parseDelayMs(text, unit) != null -> text = ""
        }
    }
    fun push(newText: String, newUnit: DelayUnit) {
        text = newText
        unit = newUnit
        val ms = parseDelayMs(newText, newUnit)
        onUpdate(if (ms != null) delayAction(ms, context) else action.copy(payload = ""))
    }
    val units = DelayUnit.entries
    val unitLabels = listOf(R.string.auto_ui_unit_seconds, R.string.auto_ui_unit_minutes, R.string.auto_ui_unit_hours)
        .map { stringResource(it) }
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.align(Alignment.CenterVertically)) {
            Icon(Icons.Outlined.HourglassEmpty, contentDescription = null, tint = AccentTeal, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.auto_ui_wait), fontSize = EDITOR_TEXT, color = TextPrimary)
        }
        EditorField(
            value = text,
            onValueChange = { push(it, unit) },
            highlight = parseDelayMs(text, unit) == null,
            keyboardType = KeyboardType.Decimal,
            centered = true,
            modifier = Modifier.width(96.dp).align(Alignment.CenterVertically),
        )
        DropdownField(
            text = unitLabels[units.indexOf(unit)],
            items = unitLabels,
            onSelect = { i -> push(text, units[i]) },
            modifier = Modifier.align(Alignment.CenterVertically),
        )
    }
}

@Composable
private fun MediaVolumeActionControls(
    action: ActionDef,
    onUpdate: (ActionDef) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // Max volume is device-dependent; read it once from the head unit's AudioManager.
    val maxVolume = remember {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        (am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15).coerceAtLeast(1)
    }
    val current = (action.payload?.toIntOrNull() ?: 2).coerceIn(0, maxVolume)
    val label = stringResource(R.string.automation_action_media_volume_label)
    val namePrefix = stringResource(R.string.automation_action_media_volume)

    LevelRow(
        icon = Icons.Outlined.VolumeUp,
        value = current,
        range = 0..maxVolume,
        step = 1,
        valueText = "$current/$maxVolume",
        onValue = { lvl -> onUpdate(action.copy(payload = lvl.toString(), displayName = "$namePrefix: $lvl")) },
        modifier = modifier,
    ) {
        Text(label, fontSize = EDITOR_TEXT, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// --- Sentry Action Controls ---

@Composable
private fun SentryActionControls(
    action: ActionDef,
    onUpdate: (ActionDef) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    OnOffToggleControls(
        action = action,
        icon = Icons.Outlined.Shield,
        displayLabel = stringResource(R.string.automation_action_sentry),
        onLabel = stringResource(R.string.automation_action_sentry_on),
        offLabel = stringResource(R.string.automation_action_sentry_off),
        spec = SENTRY_ROW,
        context = context,
        onUpdate = onUpdate,
        modifier = modifier,
    )
}

/**
 * On / off / «переключить» for a kind that has its own control row and a state the car can be
 * asked for: the name on top, the three buttons below. The first two store the row's own kind
 * with payload "1"/"0" — also when the row currently holds a toggle, so «Переключить» is never a
 * one-way door; the third stores the very same `toggle` action the catalog entries store, so one
 * target has one storage shape.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun OnOffToggleControls(
    action: ActionDef,
    icon: ImageVector,
    displayLabel: String,
    onLabel: String,
    offLabel: String,
    spec: ToggleRowSpec,
    context: Context,
    onUpdate: (ActionDef) -> Unit,
    modifier: Modifier = Modifier,
) {
    val toggleLabel = stringResource(R.string.automation_action_toggle_short)
    val isToggle = action.kind == "toggle"
    Column(modifier = modifier) {
        Row(Modifier.heightIn(min = MIN_TOUCH), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = AccentTeal, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(displayLabel, fontSize = EDITOR_TEXT, color = TextPrimary)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StateChip(onLabel, selected = !isToggle && action.payload == "1") {
                onUpdate(rowStateAction(spec, "1", "$displayLabel: $onLabel"))
            }
            StateChip(offLabel, selected = !isToggle && action.payload == "0") {
                onUpdate(rowStateAction(spec, "0", "$displayLabel: $offLabel"))
            }
            StateChip(toggleLabel, selected = isToggle) {
                onUpdate(
                    ActionDef(
                        command = "",
                        displayName = toggleDisplayName(context, spec.toggleTarget),
                        kind = "toggle",
                        payload = spec.toggleTarget,
                    )
                )
            }
        }
    }
}

/** «Включить / Отключить / Переключить»: green outline when chosen, 40dp to see, 48dp to touch. */
@Composable
private fun StateChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .heightIn(min = FIELD_HEIGHT)
            .clip(FIELD_SHAPE)
            .background(if (selected) AccentGreen.copy(alpha = 0.12f) else CardSurface)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) AccentGreen else CardBorder, FIELD_SHAPE)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, fontSize = EDITOR_TEXT, fontWeight = FontWeight.SemiBold,
            color = if (selected) AccentGreen else TextSecondary,
        )
    }
}

// --- Hotspot Action Controls ---

@Composable
private fun HotspotActionControls(
    action: ActionDef,
    onUpdate: (ActionDef) -> Unit,
    modifier: Modifier = Modifier
) {
    val isEnabled = action.payload == "1"
    val onLabel = stringResource(R.string.automation_action_hotspot_on)
    val offLabel = stringResource(R.string.automation_action_hotspot_off)
    val displayLabel = stringResource(R.string.automation_action_hotspot)

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.WifiTethering, null, tint = AccentTeal, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(displayLabel, fontSize = EDITOR_TEXT, color = TextPrimary)
        Spacer(Modifier.weight(1f))
        Text(
            if (isEnabled) onLabel else offLabel,
            fontSize = EDITOR_TEXT,
            color = if (isEnabled) AccentGreen else TextSecondary
        )
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = isEnabled,
            onCheckedChange = { checked ->
                val payload = if (checked) "1" else "0"
                val name = if (checked) onLabel else offLabel
                onUpdate(action.copy(payload = payload, displayName = "$displayLabel: $name"))
            },
            colors = bydSwitchColors()
        )
    }
}

// --- Screen Action Controls (#275) ---

/** «Экран: выключить / включить»: nothing to configure, the row only names the step. */
@Composable
private fun ScreenActionControls(payload: String?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(CardSurface, RoundedCornerShape(6.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(6.dp))
            .heightIn(min = FIELD_HEIGHT)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Tune, null, tint = AccentTeal, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(
                if (payload == "1") R.string.automation_action_screen_on else R.string.automation_action_screen_off
            ),
            fontSize = 16.sp,
            color = TextPrimary,
            maxLines = 1,
        )
    }
}

// --- Cluster Projection Action Controls ---

@Composable
private fun ClusterActionControls(
    action: ActionDef,
    onUpdate: (ActionDef) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    OnOffToggleControls(
        action = action,
        icon = Icons.Outlined.Speed,
        displayLabel = stringResource(R.string.automation_action_cluster_projection),
        onLabel = stringResource(R.string.automation_action_cluster_projection_on),
        offLabel = stringResource(R.string.automation_action_cluster_projection_off),
        spec = CLUSTER_ROW,
        context = context,
        onUpdate = onUpdate,
        modifier = modifier,
    )
}

// --- Toggle Action Controls ---

/**
 * Row for the "toggle" action: one dropdown picking what gets flipped, «Передний багажник,
 * переключить». The label comes from the target id, so it follows an in-app language switch;
 * displayName is rewritten on pick because that is what the journal and the dump show.
 */
@Composable
private fun ToggleActionControls(
    action: ActionDef,
    onUpdate: (ActionDef) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val current = action.payload ?: ActionDispatcher.TOGGLE_TRUNK
    val targetNames = ActionDispatcher.TOGGLE_TARGETS.map { target ->
        stringResource(
            R.string.auto_ui_act_toggle,
            stringResource(ActionDispatcher.toggleTargetNameRes(target) ?: R.string.automation_action_toggle),
        )
    }
    DropdownField(
        text = targetNames.getOrNull(ActionDispatcher.TOGGLE_TARGETS.indexOf(current)) ?: current,
        fillWidth = true,
        items = targetNames,
        onSelect = { i ->
            val target = ActionDispatcher.TOGGLE_TARGETS[i]
            onUpdate(action.copy(payload = target, displayName = toggleDisplayName(context, target)))
        },
        modifier = modifier,
    )
}

// --- Catalog Dropdown (with category headers) ---

/**
 * Which category headers start open when the catalog is opened: only the one holding the
 * current pick, so the list opens as a short table of contents instead of a page the driver
 * has to scroll through. Nothing matches on a free-typed or empty selection — then every
 * category starts collapsed. Pure, so the rule is testable without a UI.
 */
internal fun expandedCategoriesFor(
    selected: String,
    items: List<String>,
    categories: List<String>,
): Set<String> {
    val index = items.indexOf(selected)
    if (index < 0 || index >= categories.size) return emptySet()
    return setOf(categories[index])
}

/**
 * The catalog of parameters or car commands, grouped by category; 48dp, 16sp items. An empty
 * [selected] shows [placeholder] instead, outlined orange when [highlight].
 */
@Composable
internal fun CatalogDropdown(
    selected: String,
    items: List<String>,
    categories: List<String>,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    highlight: Boolean = false,
    onSelect: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    // Rebuilt on every open: the driver always starts from the category of the current pick.
    val openCategories = remember(expanded, selected) {
        mutableStateMapOf<String, Boolean>().apply {
            expandedCategoriesFor(selected, items, categories).forEach { put(it, true) }
        }
    }
    Box(modifier = modifier.minimumInteractiveComponentSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = FIELD_HEIGHT)
                .clip(FIELD_SHAPE)
                .background(CardSurface)
                .border(if (highlight) 1.5.dp else 1.dp, if (highlight) AccentOrange else CardBorder, FIELD_SHAPE)
                .clickable { expanded = true }
                .padding(start = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                selected.ifEmpty { placeholder },
                fontSize = EDITOR_TEXT, color = if (selected.isEmpty()) TextSecondary else TextPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Icon(Icons.Outlined.KeyboardArrowDown, null, tint = TextSecondary, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.fillMaxHeight(0.6f)
        ) {
            ScaledDialogContent {
                var lastCat = ""
                items.forEachIndexed { idx, item ->
                    val cat = categories[idx]
                    if (cat != lastCat) {
                        lastCat = cat
                        val open = openCategories[cat] == true
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        if (open) Icons.Outlined.KeyboardArrowDown else Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                        null, tint = AccentGreen, modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(cat, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextSecondary)
                                }
                            },
                            onClick = { openCategories[cat] = !open }
                        )
                    }
                    if (openCategories[cat] == true) {
                        DropdownMenuItem(
                            text = { Text(item, fontSize = EDITOR_TEXT, modifier = Modifier.padding(start = 28.dp)) },
                            onClick = { expanded = false; onSelect(idx) }
                        )
                    }
                }
            }
        }
    }
}

// --- «Добавить действие» ---

/** One tile of the action picker: its label and the action it adds. */
private class PickerTile(val label: String, val needsOverlay: Boolean = false, val make: (Context) -> ActionDef)

private class PickerSection(val name: String, val tiles: List<PickerTile>)

/** Rear seats whose heat this car reports (#327), for the editor's catalog; none outside the editor. */
private val LocalRearSeats = compositionLocalOf { emptySet<RearSeat>() }

/** The section the picker opened on last time: it opens there again. */
private object ActionPickerMemory {
    var section = 0
}

/** The car's catalog sections ([catalogSections]), then messages, apps, screen, system and waits. */
@Composable
private fun pickerSections(): List<PickerSection> {
    val context = LocalContext.current
    val lc = context.appLocalizedContext()
    val rearSeats = LocalRearSeats.current
    return remember(lc, rearSeats) {
        val catalog = catalogSections(lc, rearSeats).map { (cat, entries) ->
            PickerSection(lc.getString(cat), entries.map { entry -> PickerTile(entry.label) { entry.make(it) } })
        }
        catalog + listOf(
            PickerSection(lc.getString(R.string.auto_ui_section_messages), listOf(
                PickerTile(lc.getString(R.string.automation_action_notification), needsOverlay = true) { newNotificationAction(it) },
                PickerTile(lc.getString(R.string.automation_action_speak)) { newSpeakAction(it) },
                PickerTile(lc.getString(R.string.automation_action_agent_query)) { newAgentQueryAction(it) },
                PickerTile(lc.getString(R.string.automation_action_tg_report_add)) { newTelegramReportAction(it) },
            )),
            PickerSection(lc.getString(R.string.auto_ui_section_apps), listOf(
                PickerTile(lc.getString(R.string.automation_action_app_launch)) { newAppLaunchAction(it) },
                PickerTile(lc.getString(R.string.automation_action_app_close)) { newAppCloseAction(it) },
                PickerTile(lc.getString(R.string.automation_action_yandex_music)) { newYandexMusicAction(it) },
                PickerTile(lc.getString(R.string.automation_action_media_play)) { newMediaKeyAction(it, "play") },
                PickerTile(lc.getString(R.string.automation_action_media_pause)) { newMediaKeyAction(it, "pause") },
                PickerTile(lc.getString(R.string.automation_action_call)) { newCallAction(it) },
                PickerTile(lc.getString(R.string.automation_action_navigate)) { newNavigateAction(it) },
                PickerTile(lc.getString(R.string.automation_action_url)) { newUrlAction(it) },
            )),
            PickerSection(lc.getString(R.string.auto_ui_section_screen), listOf(
                PickerTile(lc.getString(R.string.automation_action_cluster_projection)) { newClusterAction(it) },
                PickerTile(lc.getString(R.string.automation_action_split_screen)) { newSplitScreenAction(it) },
                PickerTile(lc.getString(R.string.automation_action_split_screen_close)) { newSplitScreenCloseAction(it) },
                PickerTile(lc.getString(R.string.automation_action_split_screen_toggle)) { newSplitScreenToggleAction(it) },
            )),
            PickerSection(lc.getString(R.string.auto_ui_section_system), listOf(
                PickerTile(lc.getString(R.string.automation_action_sentry)) { newSentryAction(it) },
                PickerTile(lc.getString(R.string.automation_action_hotspot)) { newHotspotAction(it) },
                PickerTile(lc.getString(R.string.automation_action_screen_off)) { newScreenAction(it, on = false) },
                PickerTile(lc.getString(R.string.automation_action_screen_on)) { newScreenAction(it, on = true) },
                PickerTile(lc.getString(R.string.automation_action_media_volume)) { newMediaVolumeAction(it) },
            )),
            PickerSection(lc.getString(R.string.auto_ui_wait), DELAY_OPTION_MS.map { ms ->
                PickerTile(lc.getString(R.string.auto_ui_act_wait, durationText(ms, lc))) { delayAction(ms, it) }
            }),
        )
    }
}

/** «+ Добавить действие»: opens the picker; outlined orange while the rule has no action. */
@Composable
internal fun AddActionButton(
    highlight: Boolean,
    modifier: Modifier = Modifier,
    onAdd: (ActionDef) -> Unit,
) {
    val context = LocalContext.current
    var picking by remember { mutableStateOf(false) }
    var showOverlayPrompt by remember { mutableStateOf(false) }

    AddRowButton(stringResource(R.string.automation_add_action_button).removePrefix("+ ").removePrefix("+"), highlight, modifier) {
        picking = true
    }

    if (picking) {
        ActionPickerDialog(
            onPick = { tile ->
                picking = false
                onAdd(tile.make(context))
                if (tile.needsOverlay && !android.provider.Settings.canDrawOverlays(context)) {
                    showOverlayPrompt = true
                }
            },
            onDismiss = { picking = false },
        )
    }

    if (showOverlayPrompt) {
        AppAlertDialog(
            onDismissRequest = { showOverlayPrompt = false },
            containerColor = CardSurface,
            title = { Text(stringResource(R.string.automation_overlay_permission_title), color = TextPrimary, fontSize = 18.sp) },
            text = {
                Text(
                    stringResource(R.string.automation_overlay_permission_text),
                    fontSize = EDITOR_TEXT,
                    color = TextPrimary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showOverlayPrompt = false
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:${context.packageName}")
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { context.startActivity(intent) } catch (_: Exception) {}
                }) {
                    Text(stringResource(R.string.automation_overlay_open_settings), color = AccentGreen, fontSize = EDITOR_TEXT)
                }
            },
            dismissButton = {
                TextButton(onClick = { showOverlayPrompt = false }) {
                    Text(stringResource(R.string.automation_overlay_later), color = TextSecondary, fontSize = EDITOR_TEXT)
                }
            }
        )
    }
}

/** «+ Добавить условие / действие»: a 48dp row across the column with a plus. */
@Composable
private fun AddRowButton(label: String, highlight: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MIN_TOUCH)
            .clip(FIELD_SHAPE)
            .border(if (highlight) 1.5.dp else 1.dp, if (highlight) AccentOrange else CardBorder, FIELD_SHAPE)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = if (highlight) AccentOrange else TextSecondary
        Icon(Icons.Outlined.Add, null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = EDITOR_TEXT, color = color)
    }
}

/**
 * «Добавить действие»: sections on the left, the section's actions as large tiles on the right.
 * A tap on a tile adds the action and closes the window; it opens on the section used last.
 */
@Composable
private fun ActionPickerDialog(onPick: (PickerTile) -> Unit, onDismiss: () -> Unit) {
    val sections = pickerSections()
    var section by remember { mutableStateOf(ActionPickerMemory.section.coerceIn(0, sections.lastIndex)) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ScaledDialogContent {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.75f)
                    .fillMaxHeight(0.85f)
                    .background(NavyMid, RoundedCornerShape(16.dp))
                    .border(1.5.dp, CardBorder, RoundedCornerShape(16.dp))
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.auto_ui_add_action_title), fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                        color = TextPrimary, modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(MIN_TOUCH)) {
                        Icon(Icons.Outlined.Close, stringResource(R.string.automation_cancel_button), tint = TextSecondary)
                    }
                }
                HorizontalDivider(color = CardBorder)
                Row(Modifier.weight(1f)) {
                    LazyColumn(
                        modifier = Modifier.width(240.dp).fillMaxHeight(),
                        contentPadding = PaddingValues(8.dp),
                    ) {
                        itemsIndexed(sections) { i, s ->
                            val selected = i == section
                            Text(
                                s.name, fontSize = EDITOR_TEXT,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) AccentGreen else TextPrimary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = MIN_TOUCH)
                                    .clip(FIELD_SHAPE)
                                    .background(if (selected) AccentGreen.copy(alpha = 0.12f) else Color.Transparent)
                                    .selectable(selected = selected, role = Role.Tab) {
                                        section = i
                                        ActionPickerMemory.section = i
                                    }
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                            )
                        }
                    }
                    Box(Modifier.fillMaxHeight().width(1.dp).background(CardBorder))
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(sections[section].tiles) { tile ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 56.dp)
                                    .clip(FIELD_SHAPE)
                                    .background(CardSurface)
                                    .border(1.dp, CardBorder, FIELD_SHAPE)
                                    .clickable { onPick(tile) }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Text(tile.label, fontSize = EDITOR_TEXT, color = TextPrimary)
                            }
                        }
                    }
                }
            }
        }
    }
}

// --- «Добавить условие» ---

/** «+ Добавить условие»: a list of condition kinds; outlined orange while the rule has none. */
@Composable
internal fun AddTriggerButton(
    places: List<PlaceEntity>,
    highlight: Boolean,
    modifier: Modifier = Modifier,
    onAdd: (TriggerDef) -> Unit,
) {
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }
    Box(modifier) {
        AddRowButton(stringResource(R.string.automation_add_condition_button).removePrefix("+ ").removePrefix("+"), highlight) {
            menuExpanded = true
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            ScaledDialogContent {
                fun add(t: TriggerDef) {
                    menuExpanded = false
                    onAdd(t)
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.automation_trigger_type_param), fontSize = EDITOR_TEXT) },
                    onClick = { add(newParamTrigger()) }
                )
                val firstPlace = places.firstOrNull()
                DropdownMenuItem(
                    text = {
                        if (firstPlace != null) {
                            Text(stringResource(R.string.automation_trigger_type_place), fontSize = EDITOR_TEXT)
                        } else {
                            Column {
                                Text(stringResource(R.string.automation_trigger_type_place), fontSize = EDITOR_TEXT, color = TextSecondary)
                                Text(stringResource(R.string.automation_trigger_type_place_empty_hint), fontSize = 14.sp, color = TextSecondary)
                            }
                        }
                    },
                    onClick = { if (firstPlace != null) add(newPlaceTrigger(firstPlace, context)) },
                    enabled = firstPlace != null
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.automation_trigger_type_time_of_day), fontSize = EDITOR_TEXT) },
                    onClick = { add(newTimeOfDayTrigger(context)) }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.automation_trigger_type_schedule), fontSize = EDITOR_TEXT) },
                    onClick = { add(newScheduleTrigger()) }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.auto_ui_trigger_once), fontSize = EDITOR_TEXT) },
                    onClick = { add(newOneShotTrigger(context)) }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.automation_trigger_type_service_start), fontSize = EDITOR_TEXT) },
                    onClick = { add(newServiceStartTrigger(context)) }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.automation_trigger_type_internet), fontSize = EDITOR_TEXT) },
                    onClick = { add(newNetworkAvailableTrigger(context)) }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.automation_trigger_type_button_press), fontSize = EDITOR_TEXT) },
                    onClick = { add(newButtonPressTrigger(1)) }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.automation_trigger_type_steering_key), fontSize = EDITOR_TEXT) },
                    onClick = { add(newSteeringKeyTrigger(0)) }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.automation_trigger_type_voice), fontSize = EDITOR_TEXT) },
                    onClick = { add(newVoiceTrigger(context)) }
                )
            }
        }
    }
}

/** How long a note at the bottom of the tab stays. */
private const val MESSAGE_SHOW_MS = 4_000L

/** A short note at the bottom of the screen: info icon and one or two lines of text. */
@Composable
internal fun MessagePill(text: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier = modifier
            .padding(24.dp)
            .widthIn(max = 720.dp)
            .background(CardSurfaceElevated, shape)
            .border(1.dp, CardBorder, shape)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Outlined.Info, null, tint = AccentOrange, modifier = Modifier.size(22.dp))
        Text(text, fontSize = 16.sp, lineHeight = 22.sp, color = TextPrimary)
    }
}

// --- Journal Dialog ---

/**
 * The journal: all rules (header «Журнал», each entry names its rule on top) or one rule
 * ([ruleName] set, opened from the card's status pill).
 */
@Composable
private fun JournalDialog(logs: List<RuleLogEntity>, ruleName: String?, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        ScaledDialogContent {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .fillMaxHeight(0.85f)
                    .background(NavyDeep, RoundedCornerShape(16.dp))
                    .border(1.5.dp, CardBorder, RoundedCornerShape(16.dp))
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (ruleName != null) stringResource(R.string.auto_ui_journal_rule_title, ruleName)
                        else stringResource(R.string.automation_journal_title),
                        fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.Close, stringResource(R.string.automation_cancel_button), tint = TextSecondary)
                    }
                }
                HorizontalDivider(color = CardBorder)

                if (logs.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.automation_journal_empty), color = TextSecondary, fontSize = 16.sp)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        contentPadding = PaddingValues(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(logs, key = { it.id }) { log ->
                            LogItem(log, showRuleName = ruleName == null)
                        }
                    }
                }
            }
        }
    }
}

/** One entry: verdict and time, what ran, why; the stripe and icon in the verdict's colour. */
@Composable
private fun LogItem(log: RuleLogEntity, showRuleName: Boolean) {
    val context = LocalContext.current
    val today = rememberToday()
    val line = remember(log, today, context) { journalLine(log, context) }
    val color = line.kind.color()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(8.dp))
            .background(CardSurface)
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(color))
        Row(Modifier.weight(1f).padding(12.dp, 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(line.kind.icon(), null, tint = color, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f)) {
                if (showRuleName) {
                    Text(log.ruleName, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        line.status, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold,
                        color = TextPrimary, modifier = Modifier.weight(1f),
                    )
                    Text(line.time, fontSize = 14.sp, color = TextSecondary, modifier = Modifier.padding(start = 12.dp))
                }
                if (line.what.isNotEmpty()) Text(line.what, fontSize = 16.sp, lineHeight = 22.sp, color = TextPrimary)
                line.why?.let { Text(it, fontSize = 14.sp, lineHeight = 20.sp, color = TextSecondary) }
            }
        }
    }
}

// --- Shared Composables ---

@Composable
private fun AutoChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        label = { Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = AccentGreen,
            selectedLabelColor = NavyDark,
            containerColor = CardSurface,
            labelColor = TextSecondary
        ),
        shape = RoundedCornerShape(8.dp),
        border = FilterChipDefaults.filterChipBorder(
            borderColor = Color.Transparent,
            selectedBorderColor = Color.Transparent,
            enabled = true, selected = selected
        )
    )
}

@Composable
internal fun SectionHeader(text: String) {
    Text(
        text, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextSecondary,
        letterSpacing = 1.2.sp,
        modifier = Modifier.padding(bottom = 10.dp)
    )
}

@Composable
internal fun SettingRow(label: String, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = EDITOR_TEXT, color = TextPrimary, modifier = Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) { content() }
    }
}

@Composable
private fun NotificationActionControls(
    action: ActionDef,
    onUpdate: (ActionDef) -> Unit,
    modifier: Modifier = Modifier
) {
    var editing by remember { mutableStateOf(false) }
    val title = action.notificationTitle()
    val text = action.notificationText()
    val preview = when {
        title.isNotBlank() && text.isNotBlank() -> "$title — $text"
        title.isNotBlank() -> title
        text.isNotBlank() -> text
        else -> stringResource(R.string.automation_tap_to_configure)
    }

    Row(
        modifier = modifier
            .background(CardSurface, RoundedCornerShape(6.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(6.dp))
            .clickable { editing = true }
            .heightIn(min = FIELD_HEIGHT)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.Notifications,
            contentDescription = null,
            tint = AccentTeal,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = preview,
            fontSize = 16.sp,
            color = if (title.isBlank() && text.isBlank()) TextSecondary else TextPrimary,
            maxLines = 1
        )
    }

    if (editing) {
        NotificationEditDialog(
            initialTitle = title,
            initialText = text,
            onDismiss = { editing = false },
            onSave = { newTitle, newText ->
                onUpdate(action.withNotification(newTitle, newText))
                editing = false
            }
        )
    }
}

@Composable
private fun NotificationEditDialog(
    initialTitle: String,
    initialText: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var titleText by rememberSaveable { mutableStateOf(initialTitle) }
    var bodyText by rememberSaveable { mutableStateOf(initialText) }
    val canSave = titleText.trim().isNotBlank() && titleText.trim().length <= 40

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = TextPrimary,
        unfocusedTextColor = TextPrimary,
        focusedBorderColor = AccentGreen,
        unfocusedBorderColor = CardBorder,
        focusedLabelColor = AccentGreen,
        unfocusedLabelColor = TextSecondary,
        cursorColor = AccentGreen
    )

    AppAlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardSurface,
        title = {
            Text(
                text = stringResource(R.string.automation_action_notification),
                color = TextPrimary,
                fontSize = 16.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = titleText,
                    onValueChange = { if (it.length <= 40) titleText = it },
                    label = { Text(stringResource(R.string.automation_notification_title_label)) },
                    singleLine = true,
                    isError = titleText.isNotEmpty() && !canSave,
                    shape = RoundedCornerShape(8.dp),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = bodyText,
                    onValueChange = { if (it.length <= 200) bodyText = it },
                    label = { Text(stringResource(R.string.automation_notification_body_label)) },
                    maxLines = 3,
                    shape = RoundedCornerShape(8.dp),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (canSave) onSave(titleText.trim(), bodyText.trim()) }, enabled = canSave) {
                Text(stringResource(R.string.automation_save_button), color = if (canSave) AccentGreen else TextMuted)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.automation_cancel_button), color = TextSecondary)
            }
        }
    )
}

// --- Speak Action Controls (v3.6) ---

@Composable
private fun SpeakActionControls(
    action: ActionDef,
    onUpdate: (ActionDef) -> Unit,
    modifier: Modifier = Modifier
) {
    var editing by remember { mutableStateOf(false) }
    val text = action.speakText()
    val preview = if (text.isNotBlank()) text else stringResource(R.string.automation_speak_text_label)

    Row(
        modifier = modifier
            .background(CardSurface, RoundedCornerShape(6.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(6.dp))
            .clickable { editing = true }
            .heightIn(min = FIELD_HEIGHT)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.RecordVoiceOver,
            contentDescription = null,
            tint = AccentTeal,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = preview,
            fontSize = 16.sp,
            color = if (text.isBlank()) TextSecondary else TextPrimary,
            maxLines = 1
        )
    }

    if (editing) {
        SpeakEditDialog(
            initialText = text,
            onDismiss = { editing = false },
            onSave = { newText ->
                onUpdate(action.withSpeakText(newText))
                editing = false
            }
        )
    }
}

@Composable
private fun SpeakEditDialog(
    initialText: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var textValue by rememberSaveable { mutableStateOf(initialText) }
    val canSave = textValue.trim().isNotBlank() && textValue.length <= 200

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = TextPrimary,
        unfocusedTextColor = TextPrimary,
        focusedBorderColor = AccentGreen,
        unfocusedBorderColor = CardBorder,
        focusedLabelColor = AccentGreen,
        unfocusedLabelColor = TextSecondary,
        cursorColor = AccentGreen
    )

    AppAlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardSurface,
        title = {
            Text(
                text = stringResource(R.string.automation_action_speak),
                color = TextPrimary,
                fontSize = 16.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = textValue,
                    onValueChange = { if (it.length <= 200) textValue = it },
                    label = { Text(stringResource(R.string.automation_speak_text_label)) },
                    maxLines = 4,
                    shape = RoundedCornerShape(8.dp),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (canSave) onSave(textValue.trim()) }, enabled = canSave) {
                Text(stringResource(R.string.automation_save_button), color = if (canSave) AccentGreen else TextMuted)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.automation_cancel_button), color = TextSecondary)
            }
        }
    )
}

// --- Agent Query Action Controls (v3.6) ---

@Composable
private fun AgentQueryActionControls(
    action: ActionDef,
    onUpdate: (ActionDef) -> Unit,
    modifier: Modifier = Modifier
) {
    var editing by remember { mutableStateOf(false) }
    val prompt = action.agentPrompt()
    val preview = if (prompt.isNotBlank()) prompt else stringResource(R.string.automation_agent_prompt_label)

    Row(
        modifier = modifier
            .background(CardSurface, RoundedCornerShape(6.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(6.dp))
            .clickable { editing = true }
            .heightIn(min = FIELD_HEIGHT)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.Chat,
            contentDescription = null,
            tint = AccentTeal,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = preview,
            fontSize = 16.sp,
            color = if (prompt.isBlank()) TextSecondary else TextPrimary,
            maxLines = 1
        )
    }

    if (editing) {
        AgentQueryEditDialog(
            initialPrompt = prompt,
            onDismiss = { editing = false },
            onSave = { newPrompt ->
                onUpdate(action.withAgentPrompt(newPrompt))
                editing = false
            }
        )
    }
}

@Composable
private fun AgentQueryEditDialog(
    initialPrompt: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var promptValue by rememberSaveable { mutableStateOf(initialPrompt) }
    val canSave = promptValue.trim().isNotBlank() && promptValue.length <= 500

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = TextPrimary,
        unfocusedTextColor = TextPrimary,
        focusedBorderColor = AccentGreen,
        unfocusedBorderColor = CardBorder,
        focusedLabelColor = AccentGreen,
        unfocusedLabelColor = TextSecondary,
        cursorColor = AccentGreen
    )

    AppAlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardSurface,
        title = {
            Text(
                text = stringResource(R.string.automation_action_agent_query),
                color = TextPrimary,
                fontSize = 16.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = promptValue,
                    onValueChange = { if (it.length <= 500) promptValue = it },
                    label = { Text(stringResource(R.string.automation_agent_prompt_label)) },
                    maxLines = 6,
                    shape = RoundedCornerShape(8.dp),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (canSave) onSave(promptValue.trim()) }, enabled = canSave) {
                Text(stringResource(R.string.automation_save_button), color = if (canSave) AccentGreen else TextMuted)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.automation_cancel_button), color = TextSecondary)
            }
        }
    )
}

// --- Telegram Report Action Controls (3.19) ---

/** Two lines, 48 dp: «Отчёт в Telegram» and what goes in; a tap opens [TelegramReportEditDialog]. */
@Composable
private fun TelegramReportActionControls(
    action: ActionDef,
    tgBotConnected: Boolean,
    onUpdate: (ActionDef) -> Unit,
    modifier: Modifier = Modifier
) {
    var editing by remember { mutableStateOf(false) }
    val fields = action.reportFields()
    val text = action.reportText()
    val locale = LocalConfiguration.current.locales[0]
    val items = ReportField.entries.filter { it in fields }.map { stringResource(it.labelRes).lowercase(locale) }
    val ownText = stringResource(R.string.automation_tg_report_own_text)
    val summary = when {
        text.isBlank() -> items.joinToString(", ")
        items.isEmpty() -> ownText
        else -> items.joinToString(", ") + " + " + ownText
    }.replaceFirstChar { it.titlecase(locale) }

    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .background(CardSurface, RoundedCornerShape(6.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(6.dp))
            .clickable { editing = true }
            .padding(8.dp, 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.Send,
            contentDescription = null,
            tint = AccentTeal,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = stringResource(R.string.automation_action_tg_report),
                fontSize = 16.sp, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (summary.isNotEmpty()) {
                Text(
                    text = summary,
                    fontSize = 14.sp, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    if (editing) {
        TelegramReportEditDialog(
            initialFields = fields,
            initialText = text,
            tgBotConnected = tgBotConnected,
            onDismiss = { editing = false },
            onSave = { newFields, newText ->
                onUpdate(action.withTelegramReport(newFields, newText))
                editing = false
            }
        )
    }
}

/** Check rows as in «Что сохранить», then the optional own text. Saves with an item or some text. */
@Composable
private fun TelegramReportEditDialog(
    initialFields: Set<ReportField>,
    initialText: String,
    tgBotConnected: Boolean,
    onDismiss: () -> Unit,
    onSave: (Set<ReportField>, String) -> Unit
) {
    var selected by remember { mutableStateOf(initialFields) }
    var textValue by rememberSaveable { mutableStateOf(initialText) }
    val canSave = selected.isNotEmpty() || textValue.isNotBlank()

    AppAlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardSurface,
        title = {
            Text(stringResource(R.string.automation_action_tg_report_add), color = TextPrimary, fontSize = 16.sp)
        },
        text = {
            // Only the items scroll, so the buttons stay reachable at the largest text size; the own text
            // sits outside the scroller, which otherwise jumps to the cursor on every key (#344).
            Column {
                Column(
                    modifier = Modifier.weight(1f, fill = false).heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ReportField.entries.forEach { field ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selected = if (field in selected) selected - field else selected + field }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = field in selected,
                                onCheckedChange = null,
                                colors = CheckboxDefaults.colors(
                                    checkedColor = AccentGreen,
                                    uncheckedColor = CardBorder,
                                    checkmarkColor = NavyDark,
                                ),
                            )
                            Column(modifier = Modifier.padding(start = 10.dp)) {
                                Text(
                                    stringResource(field.labelRes),
                                    color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    stringResource(field.descRes),
                                    color = TextSecondary, fontSize = 14.sp, lineHeight = 16.sp,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = textValue,
                    onValueChange = { if (it.length <= TG_REPORT_TEXT_MAX) textValue = it },
                    label = { Text(stringResource(R.string.automation_tg_report_text_label)) },
                    minLines = 2,
                    maxLines = 4,
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = AccentGreen,
                        unfocusedBorderColor = CardBorder,
                        focusedLabelColor = AccentGreen,
                        unfocusedLabelColor = TextSecondary,
                        cursorColor = AccentGreen
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    stringResource(R.string.automation_tg_report_text_hint),
                    color = TextSecondary, fontSize = 14.sp, lineHeight = 16.sp,
                    modifier = Modifier.padding(start = 16.dp),
                )
                if (!tgBotConnected) {
                    Text(
                        stringResource(R.string.automation_tg_report_no_bot),
                        color = AccentOrange, fontSize = 16.sp, lineHeight = 18.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(selected, textValue.trim()) }, enabled = canSave) {
                Text(stringResource(R.string.automation_save_button), color = if (canSave) AccentGreen else TextMuted)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.automation_cancel_button), color = TextSecondary)
            }
        }
    )
}

/** Own text in a report: a short note, not a letter. */
private const val TG_REPORT_TEXT_MAX = 500

// --- Split Screen Action Controls ---

/**
 * Compact preview row for the split_screen action.
 * Tapping opens [SplitScreenEditDialog] with two [AppLaunchPickerDialog] pickers
 * (reused from app_launch) and side-selection chips.
 */
@Composable
private fun SplitScreenActionControls(
    action: ActionDef,
    onUpdate: (ActionDef) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf(false) }
    val narrowLabel = action.splitNarrowLabel()
    val wideLabel = action.splitWideLabel()
    val tapToConfigureLabel = stringResource(R.string.split_action_tap_to_configure)
    val preview = if (narrowLabel.isNotBlank() && wideLabel.isNotBlank()) {
        "$narrowLabel / $wideLabel"
    } else tapToConfigureLabel

    Row(
        modifier = modifier
            .background(CardSurface, RoundedCornerShape(6.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(6.dp))
            .clickable { editing = true }
            .heightIn(min = FIELD_HEIGHT)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Apps,
            contentDescription = null,
            tint = AccentTeal,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = preview,
            fontSize = 16.sp,
            color = if (narrowLabel.isBlank() || wideLabel.isBlank()) TextSecondary else TextPrimary,
            maxLines = 1,
        )
    }

    if (editing) {
        SplitScreenEditDialog(
            initialNarrowPkg = action.splitNarrowPkg(),
            initialNarrowLabel = narrowLabel,
            initialWidePkg = action.splitWidePkg(),
            initialWideLabel = wideLabel,
            initialSide = action.splitSide(),
            onDismiss = { editing = false },
            onSave = { nPkg, nLabel, wPkg, wLabel, side ->
                onUpdate(action.withSplitScreen(nPkg, nLabel, wPkg, wLabel, side))
                editing = false
            },
        )
    }
}

/**
 * Row for the payload-less split kinds (close / toggle): nothing to configure,
 * so it only names the action. Label comes from the kind, not from the stored
 * displayName, so it follows an in-app language switch.
 */
@Composable
private fun SplitScreenStateActionControls(kind: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(CardSurface, RoundedCornerShape(6.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(6.dp))
            .heightIn(min = FIELD_HEIGHT)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Apps,
            contentDescription = null,
            tint = AccentTeal,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(
                if (kind == "split_screen_close") R.string.automation_action_split_screen_close
                else R.string.automation_action_split_screen_toggle
            ),
            fontSize = 16.sp,
            color = TextPrimary,
            maxLines = 1,
        )
    }
}

@Composable
private fun SplitScreenEditDialog(
    initialNarrowPkg: String,
    initialNarrowLabel: String,
    initialWidePkg: String,
    initialWideLabel: String,
    initialSide: String,
    onDismiss: () -> Unit,
    onSave: (narrowPkg: String, narrowLabel: String, widePkg: String, wideLabel: String, side: String) -> Unit,
) {
    var narrowPkg by remember { mutableStateOf(initialNarrowPkg) }
    var narrowLabel by remember { mutableStateOf(initialNarrowLabel) }
    var widePkg by remember { mutableStateOf(initialWidePkg) }
    var wideLabel by remember { mutableStateOf(initialWideLabel) }
    var side by remember { mutableStateOf(initialSide.let { if (it in listOf("left", "right")) it else "right" }) }

    // Controls which app picker is open (null = none, "narrow", "wide").
    var openPicker by remember { mutableStateOf<String?>(null) }

    val canSave = narrowPkg.isNotBlank() && widePkg.isNotBlank() && narrowPkg != widePkg

    val sideLeftLabel = stringResource(R.string.split_action_side_left)
    val sideRightLabel = stringResource(R.string.split_action_side_right)

    AppAlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardSurface,
        title = {
            Text(
                text = stringResource(R.string.automation_action_split_screen),
                color = TextPrimary,
                fontSize = 16.sp,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Narrow app picker row (1/3)
                Column {
                    Text(
                        stringResource(R.string.split_action_narrow_label),
                        fontSize = 14.sp,
                        color = TextSecondary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = narrowLabel.ifBlank { stringResource(R.string.split_action_tap_to_configure) },
                        fontSize = 16.sp,
                        color = if (narrowLabel.isBlank()) TextSecondary else TextPrimary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CardSurface, RoundedCornerShape(6.dp))
                            .border(1.dp, CardBorder, RoundedCornerShape(6.dp))
                            .clickable { openPicker = "narrow" }
                            .padding(10.dp, 8.dp),
                        maxLines = 1,
                    )
                }
                // Wide app picker row (2/3)
                Column {
                    Text(
                        stringResource(R.string.split_action_wide_label),
                        fontSize = 14.sp,
                        color = TextSecondary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = wideLabel.ifBlank { stringResource(R.string.split_action_tap_to_configure) },
                        fontSize = 16.sp,
                        color = if (wideLabel.isBlank()) TextSecondary else TextPrimary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CardSurface, RoundedCornerShape(6.dp))
                            .border(1.dp, CardBorder, RoundedCornerShape(6.dp))
                            .clickable { openPicker = "wide" }
                            .padding(10.dp, 8.dp),
                        maxLines = 1,
                    )
                }
                // Side selector chips
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = side == "right",
                        onClick = { side = "right" },
                        label = { Text(sideRightLabel, fontSize = 14.sp) },
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = side == "left",
                        onClick = { side = "left" },
                        label = { Text(sideLeftLabel, fontSize = 14.sp) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (canSave) onSave(narrowPkg, narrowLabel, widePkg, wideLabel, side) },
                enabled = canSave,
            ) {
                Text(
                    stringResource(R.string.automation_save_button),
                    color = if (canSave) AccentGreen else TextMuted,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.automation_cancel_button), color = TextSecondary)
            }
        },
    )

    // App picker for narrow (1/3) pane — opened on demand.
    if (openPicker == "narrow") {
        AppLaunchPickerDialog(
            currentPackage = narrowPkg,
            showMinimizeToggle = false,
            onDismiss = { openPicker = null },
            onSelect = { pkg, label ->
                narrowPkg = pkg
                narrowLabel = label
                openPicker = null
            },
        )
    }
    // App picker for wide (2/3) pane — opened on demand.
    if (openPicker == "wide") {
        AppLaunchPickerDialog(
            currentPackage = widePkg,
            showMinimizeToggle = false,
            onDismiss = { openPicker = null },
            onSelect = { pkg, label ->
                widePkg = pkg
                wideLabel = label
                openPicker = null
            },
        )
    }
}

// --- App Launch Action Controls ---

@Composable
private fun AppLaunchActionControls(
    action: ActionDef,
    onUpdate: (ActionDef) -> Unit,
    modifier: Modifier = Modifier
) {
    var editing by remember { mutableStateOf(false) }
    var pendingMinimize by remember(action) { mutableStateOf(action.appLaunchMinimize()) }
    val pkg = action.appLaunchPackageName()
    val label = action.appLaunchLabel()
    val preview = when {
        label.isNotBlank() -> label
        pkg.isNotBlank() -> pkg
        else -> stringResource(R.string.automation_tap_to_pick_app)
    }

    Row(
        modifier = modifier
            .background(CardSurface, RoundedCornerShape(6.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(6.dp))
            .clickable { editing = true }
            .heightIn(min = FIELD_HEIGHT)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.Apps,
            contentDescription = null,
            tint = AccentTeal,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = preview,
            fontSize = 16.sp,
            color = if (label.isBlank() && pkg.isBlank()) TextSecondary else TextPrimary,
            maxLines = 1
        )
    }

    if (editing) {
        AppLaunchPickerDialog(
            currentPackage = pkg,
            showMinimizeToggle = true,
            initialMinimize = pendingMinimize,
            onMinimizeChanged = { pendingMinimize = it },
            onDismiss = {
                pendingMinimize = action.appLaunchMinimize()
                editing = false
            },
            onSelect = { newPkg, newLabel ->
                onUpdate(action.withAppLaunch(newPkg, newLabel, pendingMinimize))
                editing = false
            },
        )
    }
}

// --- App Close Action Controls (#280) ---

@Composable
private fun AppCloseActionControls(
    action: ActionDef,
    onUpdate: (ActionDef) -> Unit,
    modifier: Modifier = Modifier
) {
    var editing by remember { mutableStateOf(false) }
    val pkg = action.appLaunchPackageName()
    val label = action.appLaunchLabel()
    val selfPackage = LocalContext.current.packageName

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CardSurface, RoundedCornerShape(6.dp))
                .border(1.dp, CardBorder, RoundedCornerShape(6.dp))
                .clickable { editing = true }
                .heightIn(min = FIELD_HEIGHT)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Apps,
                contentDescription = null,
                tint = AccentTeal,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label.ifBlank { pkg.ifBlank { stringResource(R.string.automation_tap_to_pick_app) } },
                fontSize = 16.sp,
                color = if (label.isBlank() && pkg.isBlank()) TextSecondary else TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = stringResource(R.string.automation_action_app_close_hint),
            fontSize = 14.sp,
            color = TextSecondary
        )
    }

    if (editing) {
        AppLaunchPickerDialog(
            currentPackage = pkg,
            excludedPackages = setOf(selfPackage),
            onDismiss = { editing = false },
            onSelect = { newPkg, newLabel ->
                onUpdate(action.withAppClose(newPkg, newLabel))
                editing = false
            },
        )
    }
}

// --- Media Key Action Controls (#212, #275) ---

@Composable
private fun MediaKeyActionControls(payload: String?, modifier: Modifier = Modifier) {
    val play = payload == "play"
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CardSurface, RoundedCornerShape(6.dp))
                .border(1.dp, CardBorder, RoundedCornerShape(6.dp))
                .heightIn(min = FIELD_HEIGHT)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (play) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                contentDescription = null,
                tint = AccentTeal,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(
                    if (play) R.string.automation_action_media_play else R.string.automation_action_media_pause
                ),
                fontSize = 16.sp,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = stringResource(R.string.automation_action_media_key_hint),
            fontSize = 14.sp,
            color = TextSecondary
        )
    }
}

// --- Call Action Controls ---

@Composable
private fun CallActionControls(
    action: ActionDef,
    onUpdate: (ActionDef) -> Unit,
    modifier: Modifier = Modifier
) {
    var editing by remember { mutableStateOf(false) }
    val phone = action.callPhone()
    val name = action.callName()
    val preview = when {
        name.isNotBlank() && phone.isNotBlank() -> "$name — $phone"
        phone.isNotBlank() -> phone
        else -> stringResource(R.string.automation_tap_to_set_phone)
    }

    Row(
        modifier = modifier
            .background(CardSurface, RoundedCornerShape(6.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(6.dp))
            .clickable { editing = true }
            .heightIn(min = FIELD_HEIGHT)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.Call,
            contentDescription = null,
            tint = AccentTeal,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = preview,
            fontSize = 16.sp,
            color = if (phone.isBlank()) TextSecondary else TextPrimary,
            maxLines = 1
        )
    }

    if (editing) {
        CallEditDialog(
            initialPhone = phone,
            initialName = name,
            initialAutoDial = action.callAutoDial(),
            onDismiss = { editing = false },
            onSave = { newPhone, newName, newAutoDial ->
                onUpdate(action.withCall(newPhone, newName, newAutoDial))
                editing = false
            }
        )
    }
}

@Composable
internal fun CallEditDialog(
    initialPhone: String,
    initialName: String,
    initialAutoDial: Boolean,
    onDismiss: () -> Unit,
    onSave: (phone: String, name: String, autoDial: Boolean) -> Unit,
    /** False for a number going into a tel: / sms: link, which only opens the dialer. */
    showAutoDial: Boolean = true,
) {
    var phoneText by rememberSaveable { mutableStateOf(initialPhone) }
    var nameText by rememberSaveable { mutableStateOf(initialName) }
    var autoDial by remember { mutableStateOf(initialAutoDial) }
    val trimmedPhone = phoneText.trim()
    val canSave = trimmedPhone.isNotBlank() && trimmedPhone.length in 5..20

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = TextPrimary,
        unfocusedTextColor = TextPrimary,
        focusedBorderColor = AccentGreen,
        unfocusedBorderColor = CardBorder,
        focusedLabelColor = AccentGreen,
        unfocusedLabelColor = TextSecondary,
        cursorColor = AccentGreen
    )

    AppAlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardSurface,
        title = { Text(stringResource(R.string.automation_call_dialog_title), color = TextPrimary, fontSize = 16.sp) },
        text = {
            Column {
                OutlinedTextField(
                    value = nameText,
                    onValueChange = { nameText = it },
                    label = { Text(stringResource(R.string.automation_call_name_label)) },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = phoneText,
                    onValueChange = { phoneText = it },
                    label = { Text(stringResource(R.string.automation_call_phone_label)) },
                    singleLine = true,
                    isError = phoneText.isNotBlank() && !canSave,
                    shape = RoundedCornerShape(8.dp),
                    colors = fieldColors,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth()
                )
                if (showAutoDial) Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clickable { autoDial = !autoDial }
                        .padding(vertical = 4.dp)
                ) {
                    Checkbox(
                        checked = autoDial,
                        onCheckedChange = { autoDial = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = AccentGreen,
                            uncheckedColor = TextMuted,
                            checkmarkColor = NavyDark
                        )
                    )
                    Spacer(Modifier.width(4.dp))
                    Column {
                        Text(stringResource(R.string.automation_call_auto_dial_label), color = TextPrimary, fontSize = 16.sp)
                        Text(
                            stringResource(R.string.automation_call_auto_dial_hint),
                            color = TextSecondary,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (canSave) onSave(trimmedPhone, nameText.trim(), autoDial) },
                enabled = canSave
            ) {
                Text(stringResource(R.string.automation_save_button), color = if (canSave) AccentGreen else TextMuted)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.automation_cancel_button), color = TextSecondary)
            }
        }
    )
}

// --- Navigate Action Controls ---

@Composable
private fun NavigateActionControls(
    action: ActionDef,
    places: List<PlaceEntity>,
    onUpdate: (ActionDef) -> Unit,
    modifier: Modifier = Modifier
) {
    var editing by remember { mutableStateOf(false) }
    val name = action.navigateName()
    val preview = if (name.isNotBlank()) name else stringResource(R.string.automation_tap_to_pick_place)

    Row(
        modifier = modifier
            .background(CardSurface, RoundedCornerShape(6.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(6.dp))
            .clickable { editing = true }
            .heightIn(min = FIELD_HEIGHT)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.Navigation,
            contentDescription = null,
            tint = AccentTeal,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = preview,
            fontSize = 16.sp,
            color = if (name.isBlank()) TextSecondary else TextPrimary,
            maxLines = 1
        )
    }

    if (editing) {
        NavigateEditDialog(
            action = action,
            places = places,
            onDismiss = { editing = false },
            onSave = { lat, lon, placeName ->
                onUpdate(action.withNavigate(lat, lon, placeName))
                editing = false
            }
        )
    }
}

@Composable
private fun NavigateEditDialog(
    action: ActionDef,
    places: List<PlaceEntity>,
    onDismiss: () -> Unit,
    onSave: (lat: Double, lon: Double, name: String) -> Unit
) {
    // Preselect by name match, then by lat/lon proximity, otherwise null
    val initialPlace = remember(action, places) {
        val actionName = action.navigateName()
        val actionLat = action.navigateLat()
        val actionLon = action.navigateLon()
        places.find { it.name == actionName }
            ?: if (actionLat != null && actionLon != null) {
                places.find { Math.abs(it.lat - actionLat) < 0.0001 && Math.abs(it.lon - actionLon) < 0.0001 }
            } else null
    }
    var selectedPlace by remember { mutableStateOf(initialPlace) }
    var placeExpanded by remember { mutableStateOf(false) }

    val canSave = selectedPlace != null && places.isNotEmpty()

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = TextPrimary,
        unfocusedTextColor = TextPrimary,
        focusedBorderColor = AccentGreen,
        unfocusedBorderColor = CardBorder,
        focusedLabelColor = AccentGreen,
        unfocusedLabelColor = TextSecondary,
        cursorColor = AccentGreen
    )

    AppAlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardSurface,
        title = { Text(stringResource(R.string.automation_navigate_dialog_title), color = TextPrimary, fontSize = 16.sp) },
        text = {
            if (places.isEmpty()) {
                Text(
                    text = stringResource(R.string.automation_navigate_no_places_hint),
                    fontSize = 16.sp,
                    color = TextSecondary
                )
            } else {
                Column {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = selectedPlace?.name ?: stringResource(R.string.automation_navigate_pick_place_placeholder),
                            fontSize = 16.sp,
                            color = if (selectedPlace == null) TextSecondary else TextPrimary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(CardSurface, RoundedCornerShape(8.dp))
                                .border(1.dp, CardBorder, RoundedCornerShape(8.dp))
                                .clickable { placeExpanded = true }
                                .padding(12.dp, 10.dp),
                            maxLines = 1
                        )
                        DropdownMenu(expanded = placeExpanded, onDismissRequest = { placeExpanded = false }) {
                            ScaledDialogContent {
                                places.forEach { place ->
                                    DropdownMenuItem(
                                        text = { Text(place.name, fontSize = 16.sp) },
                                        onClick = {
                                            placeExpanded = false
                                            selectedPlace = place
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val p = selectedPlace
                    if (canSave && p != null) onSave(p.lat, p.lon, p.name)
                },
                enabled = canSave
            ) {
                Text(stringResource(R.string.automation_save_button), color = if (canSave) AccentGreen else TextMuted)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.automation_cancel_button), color = TextSecondary)
            }
        }
    )
}

// --- URL Action Controls ---

@Composable
private fun UrlActionControls(
    action: ActionDef,
    onUpdate: (ActionDef) -> Unit,
    modifier: Modifier = Modifier
) {
    var editing by remember { mutableStateOf(false) }
    val url = action.urlString()
    val preview = if (url.isNotBlank()) url else stringResource(R.string.automation_tap_to_set_url)

    Row(
        modifier = modifier
            .background(CardSurface, RoundedCornerShape(6.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(6.dp))
            .clickable { editing = true }
            .heightIn(min = FIELD_HEIGHT)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.Link,
            contentDescription = null,
            tint = AccentTeal,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = preview,
            fontSize = 16.sp,
            color = if (url.isBlank()) TextSecondary else TextPrimary,
            maxLines = 1
        )
    }

    if (editing) {
        UrlEditDialog(
            initialUrl = url,
            initialMinimize = action.urlMinimize(),
            onDismiss = { editing = false },
            onSave = { newUrl, newMinimize ->
                onUpdate(action.withUrl(newUrl, newMinimize))
                editing = false
            }
        )
    }
}

@Composable
private fun UrlEditDialog(
    initialUrl: String,
    initialMinimize: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, Boolean) -> Unit
) {
    var urlText by remember { mutableStateOf(initialUrl) }
    var minimize by remember { mutableStateOf(initialMinimize) }
    val trimmed = urlText.trim()
    val urlValid = trimmed.matches(Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*:.+"))
    val canSave = trimmed.isNotBlank() && urlValid

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = TextPrimary,
        unfocusedTextColor = TextPrimary,
        focusedBorderColor = AccentGreen,
        unfocusedBorderColor = CardBorder,
        focusedLabelColor = AccentGreen,
        unfocusedLabelColor = TextSecondary,
        cursorColor = AccentGreen,
        errorBorderColor = Color(0xFFEF4444),
        errorLabelColor = Color(0xFFEF4444)
    )

    AppAlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardSurface,
        title = { Text(stringResource(R.string.automation_url_dialog_title), color = TextPrimary, fontSize = 16.sp) },
        text = {
            Column {
                OutlinedTextField(
                    value = urlText,
                    onValueChange = { urlText = it },
                    label = { Text(stringResource(R.string.automation_url_field_label)) },
                    singleLine = true,
                    isError = urlText.isNotBlank() && !urlValid,
                    shape = RoundedCornerShape(8.dp),
                    colors = fieldColors,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { minimize = !minimize }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = minimize,
                        onCheckedChange = { minimize = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = AccentGreen,
                            uncheckedColor = CardBorder,
                            checkmarkColor = TextPrimary
                        )
                    )
                    Text(
                        stringResource(R.string.automation_url_minimize_label),
                        fontSize = 16.sp,
                        color = TextPrimary
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (canSave) onSave(trimmed, minimize) }, enabled = canSave) {
                Text(stringResource(R.string.automation_save_button), color = if (canSave) AccentGreen else TextMuted)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.automation_cancel_button), color = TextSecondary)
            }
        }
    )
}

// --- Yandex Music Action Controls ---

@Composable
private fun YandexMusicActionControls(
    action: ActionDef,
    onUpdate: (ActionDef) -> Unit,
    modifier: Modifier = Modifier
) {
    var editing by remember { mutableStateOf(false) }
    val mode = action.yandexMusicMode()
    val myWaveLabel = stringResource(R.string.automation_music_my_wave)
    val tapToConfigureLabel = stringResource(R.string.automation_tap_to_configure)
    val preview = when (mode) {
        "mybeat" -> myWaveLabel
        else -> tapToConfigureLabel
    }
    val minimize = action.yandexMusicMinimize()

    Row(
        modifier = modifier
            .background(CardSurface, RoundedCornerShape(6.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(6.dp))
            .clickable { editing = true }
            .heightIn(min = FIELD_HEIGHT)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = preview,
            fontSize = 16.sp,
            color = if (mode.isBlank()) TextSecondary else TextPrimary,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        if (minimize) {
            Spacer(Modifier.width(6.dp))
            Text("↓", fontSize = 16.sp, color = TextSecondary)
        }
    }

    if (editing) {
        YandexMusicEditDialog(
            initialMode = mode.ifBlank { "mybeat" },
            initialMinimize = minimize,
            onDismiss = { editing = false },
            onSave = { newMode, newMinimize ->
                onUpdate(action.withYandexMusic(newMode, newMinimize))
                editing = false
            }
        )
    }
}

@Composable
private fun YandexMusicEditDialog(
    initialMode: String,
    initialMinimize: Boolean,
    onDismiss: () -> Unit,
    onSave: (mode: String, minimize: Boolean) -> Unit
) {
    val myWaveLabel = stringResource(R.string.automation_music_my_wave)
    val modes = listOf("mybeat" to myWaveLabel)
    var selectedMode by remember { mutableStateOf(initialMode) }
    var minimize by remember { mutableStateOf(initialMinimize) }
    var dropdownExpanded by remember { mutableStateOf(false) }

    AppAlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardSurface,
        title = { Text(stringResource(R.string.automation_music_dialog_title), color = TextPrimary, fontSize = 16.sp) },
        text = {
            Column {
                Text(stringResource(R.string.automation_music_what_to_play), fontSize = 14.sp, color = TextSecondary)
                Spacer(Modifier.height(4.dp))
                Box {
                    val label = modes.find { it.first == selectedMode }?.second ?: selectedMode
                    Text(
                        label,
                        fontSize = 14.sp,
                        color = AccentGreen,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CardSurface, RoundedCornerShape(6.dp))
                            .border(1.dp, CardBorder, RoundedCornerShape(6.dp))
                            .clickable { dropdownExpanded = true }
                            .padding(10.dp, 8.dp)
                    )
                    DropdownMenu(
                        expanded = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false }
                    ) {
                        ScaledDialogContent {
                            modes.forEach { (value, label) ->
                                DropdownMenuItem(
                                    text = { Text(label, fontSize = 16.sp) },
                                    onClick = {
                                        selectedMode = value
                                        dropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { minimize = !minimize }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = minimize,
                        onCheckedChange = { minimize = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = AccentGreen,
                            uncheckedColor = CardBorder,
                            checkmarkColor = TextPrimary
                        )
                    )
                    Text(
                        stringResource(R.string.automation_music_minimize_label),
                        fontSize = 16.sp,
                        color = TextPrimary
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(selectedMode, minimize) }) {
                Text(stringResource(R.string.automation_save_button), color = AccentGreen)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.automation_cancel_button), color = TextSecondary)
            }
        }
    )
}

private fun newPlaceTrigger(place: PlaceEntity, context: android.content.Context): TriggerDef {
    return TriggerDef(
        param = "Place",
        chineseName = "位置",
        operator = "==",
        value = "enter",
        displayName = context.getString(R.string.auto_trig_place_enter, place.name),
        kind = "place_enter",
        placeId = place.id,
        placeName = place.name
    )
}

private fun newServiceStartTrigger(context: android.content.Context): TriggerDef =
    serviceStartTrigger(context.getString(R.string.auto_trig_service_start))

private fun newNetworkAvailableTrigger(context: android.content.Context): TriggerDef {
    return TriggerDef(
        param = "NetworkAvailable",
        chineseName = "网络可用",
        operator = "==",
        value = "true",
        displayName = context.getString(R.string.auto_trig_network_available),
        kind = "network_available"
    )
}

private fun newVoiceTrigger(context: android.content.Context): TriggerDef = TriggerDef(
    param = "Voice",
    chineseName = "语音",
    operator = "==",
    value = "",
    displayName = context.getString(R.string.automation_trigger_type_voice),
    kind = "voice"
)

private fun newMediaVolumeAction(context: android.content.Context): ActionDef = ActionDef(
    command = "media_volume",
    displayName = context.getString(R.string.auto_act_media_volume),
    kind = "media_volume",
    payload = "2"
)

private fun newTimeOfDayTrigger(context: android.content.Context): TriggerDef {
    return TriggerDef(
        param = "TimeOfDay",
        chineseName = "时间段",
        operator = "==",
        value = "NIGHT",
        displayName = context.getString(R.string.auto_trig_time_of_day_night),
        kind = "time_of_day"
    )
}

private fun newScheduleTrigger(): TriggerDef {
    // Default: 08:00-10:00 window, every day. displayName carries only the time
    // (no days), so no context is needed here.
    val spec = ScheduleSpec(fromMinute = 8 * 60, toMinute = 10 * 60, days = emptySet())
    return TriggerDef(
        param = "Schedule",
        chineseName = "时间表",
        operator = "==",
        value = spec.toJson(),
        displayName = "${minuteToHHmm(spec.fromMinute)}-${minuteToHHmm(spec.toMinute)}",
        kind = "time_range"
    )
}
