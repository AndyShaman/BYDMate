package com.bydmate.app.ui.automation

import android.util.Log
import android.app.TimePickerDialog
import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.delay
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.WbTwilight
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import com.bydmate.app.data.automation.AutomationEngine
import com.bydmate.app.diagnostics.Trace
import com.bydmate.app.diagnostics.TraceArea
import com.bydmate.app.data.automation.OneShotTrigger
import com.bydmate.app.util.appLocalizedContext
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.ViewList
import com.bydmate.app.data.telegram.ReportField
import com.bydmate.app.data.telegram.TELEGRAM_REPORT_KIND
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.VerticalDivider
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
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
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Gamepad
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.ui.platform.LocalContext
import com.bydmate.app.ui.components.AppAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.bydmate.app.R
import com.bydmate.app.cluster.ClusterProjectionManager
import com.bydmate.app.cluster.DEFAULT_TRIGGER_KEYCODE
import com.bydmate.app.cluster.DEFAULT_VOICE_KEYCODE
import com.bydmate.app.cluster.VOLUME_KNOB_PRESS_KEYCODE
import com.bydmate.app.cluster.knownButtonNameRes
import com.bydmate.app.cluster.voiceCompanionsFromCsv
import com.bydmate.app.data.repository.SettingsRepository
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.time.ZoneId
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.ScheduleSpec
import com.bydmate.app.data.automation.minuteToHHmm
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.PlaceEntity
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.RuleLogEntity
import com.bydmate.app.data.local.entity.TriggerDef
import com.bydmate.app.ui.components.AppLaunchPickerDialog
import com.bydmate.app.ui.settings.LearnButtonDialog
import com.bydmate.app.ui.components.bydSwitchColors
import com.bydmate.app.ui.theme.*
import com.bydmate.app.ui.widget.WidgetButtonIcons
import com.bydmate.app.ui.widget.WidgetPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    if (state.showEditor) {
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

// --- Rule list: rows and cards ---

private val RULE_CARD_SHAPE = RoundedCornerShape(12.dp)
/** Card and row buttons: 20dp icon over a 14sp label, no frame. */
private val ACTION_LABEL_STYLE = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
/** A labeled list button: at least this wide, else its label plus 8dp each side. */
private val ROW_CELL_MIN = 56.dp
private val ROW_CELL_PADDING = 8.dp
/** A labeled button is 72dp tall to touch. */
private val ROW_CELL_HEIGHT = 72.dp
/** The thin line between the row text and its buttons, with 8dp on each side. */
private val ROW_DIVIDER_SLOT = 17.dp
/** Least width the list row text keeps beside the buttons; in sp so it grows with the text size. */
private val MIN_ROW_TEXT = 240.sp

/** One of the four buttons of a rule: Изменить / Копия / Отправить / Удалить. */
private class RuleAction(
    val label: String,
    val icon: ImageVector,
    val destructive: Boolean,
    val enabled: Boolean,
    val onClick: () -> Unit,
)

@Composable
private fun ruleActions(
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
private fun labelWidths(labels: List<String>, style: TextStyle): List<Dp> {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return labels.map { with(density) { measurer.measure(it, style, maxLines = 1, softWrap = false).size.width.toDp() } }
}

private fun rowCellWidth(labelWidth: Dp): Dp = maxOf(ROW_CELL_MIN, labelWidth + ROW_CELL_PADDING * 2)

/**
 * Today's day number, re-read when the screen comes back and at every midnight: a remember key
 * for the words that say «сегодня» or «вчера».
 */
@Composable
private fun rememberToday(): Long {
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

private fun RuleStatusKind.icon(): ImageVector = when (this) {
    RuleStatusKind.OK -> Icons.Outlined.CheckCircle
    RuleStatusKind.CANCELLED -> Icons.Outlined.Block
    RuleStatusKind.SKIPPED -> Icons.Outlined.Block
    RuleStatusKind.ERROR -> Icons.Outlined.ErrorOutline
    RuleStatusKind.NEVER -> Icons.Outlined.RemoveCircleOutline
    RuleStatusKind.PLANNED -> Icons.Outlined.CalendarMonth
}

private fun RuleStatusKind.color(): Color = when (this) {
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
private fun RuleListRow(
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
private fun RuleGridCard(
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
private val FOOT_CELL_PADDING = 4.dp

/** Title with filters and the action group: one line when both fit whole, else stacked. */
@Composable
private fun HeaderLayout(content: @Composable () -> Unit) {
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
private fun ViewModeToggle(mode: RuleViewMode, onChange: (RuleViewMode) -> Unit) {
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
private fun HeaderOutlinedButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, onClick: () -> Unit) {
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

private class ListCells(private val state: LazyListState) : RuleCells {
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

private class GridCells(private val state: LazyGridState) : RuleCells {
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
private fun rememberRuleDrag(cells: RuleCells, rules: List<RuleEntity>, onDrop: (Long, Long) -> Unit): RuleDragState {
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
private fun Modifier.ruleDrag(drag: RuleDragState): Modifier = pointerInput(drag) {
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
private fun Modifier.liftedWhen(drag: RuleDragState, id: Long): Modifier =
    if (drag.held != id) this else zIndex(1f).graphicsLayer {
        val offset = drag.liftOffset()
        translationX = offset.x
        translationY = offset.y
        scaleX = LIFT_SCALE
        scaleY = LIFT_SCALE
        shadowElevation = 16.dp.toPx()
        shape = RULE_CARD_SHAPE
    }

// --- Editor Dialog ---

/** Fields, chips and dropdowns: 40dp to see, 48dp to touch. */
private val FIELD_HEIGHT = 40.dp
private val FIELD_SHAPE = RoundedCornerShape(8.dp)
private val EDITOR_TEXT = 16.sp

/** Where a missing part sits in the editor: left column (conditions) or right (actions), and its key. */
private fun Missing.anchor(): Pair<Boolean, String> = when (this) {
    Missing.Name -> true to "name"
    Missing.NoConditions -> true to "addTrigger"
    is Missing.Param -> true to "t${condition - 1}"
    is Missing.Value -> true to "t${condition - 1}"
    is Missing.Operator -> true to "t${condition - 1}"
    is Missing.Number -> true to "t${condition - 1}"
    is Missing.Key -> true to "t${condition - 1}"
    Missing.NoActions -> false to "addAction"
    is Missing.Action -> false to "a${action - 1}"
}

/**
 * The rule editor, almost the whole screen: conditions on the left, actions and settings on
 * the right. What the draft still misses is outlined in orange and named beside «Сохранить»;
 * a tap on the grey «Сохранить» scrolls to the first such place. «Назад», the cross and
 * «Отмена» ask before dropping changes.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun EditorDialog(
    editing: EditingRule,
    places: List<PlaceEntity>,
    tgBotConnected: Boolean,
    editorError: String?,
    message: String?,
    onUpdate: (EditingRule.() -> EditingRule) -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    shareEnabled: Boolean,
    onTestRun: () -> Unit,
    testRunning: Boolean,
    onTestAction: (ActionDef) -> Unit,
    onDismiss: () -> Unit
) {
    // The draft as the editor opened: leaving with anything else asks first.
    val original = remember { editing }
    var askUnsaved by remember { mutableStateOf(false) }
    val requestClose = {
        if (hasUnsavedChanges(original, editing)) askUnsaved = true else onDismiss()
    }
    Dialog(
        onDismissRequest = requestClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false
        )
    ) {
        ScaledDialogContent {
            val context = LocalContext.current
            val missing = remember(editing, context) { missingParts(editing, context) }
            val leftScroll = rememberScrollState()
            val rightScroll = rememberScrollState()
            // Content y of every row, so the grey «Сохранить» can scroll to what is missing.
            val leftY = remember { mutableStateMapOf<String, Int>() }
            val rightY = remember { mutableStateMapOf<String, Int>() }
            val scope = rememberCoroutineScope()
            fun Modifier.anchor(left: Boolean, key: String) = onGloballyPositioned {
                (if (left) leftY else rightY)[key] = it.positionInParent().y.toInt()
            }
            val hasOneShot = editing.triggers.any { it.kind == OneShotTrigger.KIND }

            Box(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 20.dp)) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(NavyDeep, RoundedCornerShape(16.dp))
                        .border(1.5.dp, CardBorder, RoundedCornerShape(16.dp))
                ) {
                    // Title bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (editing.isNew) stringResource(R.string.automation_editor_new_rule_title) else editing.name,
                            fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = requestClose, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.automation_cancel_button), tint = TextSecondary)
                        }
                    }
                    HorizontalDivider(color = CardBorder)

                    // Two columns
                    Row(modifier = Modifier.weight(1f)) {
                        // Left: Name + Triggers
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .verticalScroll(leftScroll)
                                .padding(16.dp, 12.dp)
                        ) {
                            EditorField(
                                value = editing.name,
                                onValueChange = { v -> onUpdate { copy(name = v) } },
                                placeholder = stringResource(R.string.automation_rule_name_placeholder),
                                highlight = Missing.Name in missing,
                                enabled = !editing.saving,
                                textStyle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary),
                                modifier = Modifier.fillMaxWidth().anchor(true, "name"),
                            )
                            Spacer(Modifier.height(14.dp))
                            SectionHeader(stringResource(R.string.automation_section_when))

                            // «Все условия / Любое условие» only once there is something to combine.
                            if (editing.triggers.size >= 2) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ChoiceChip(stringResource(R.string.auto_ui_logic_all), editing.triggerLogic == "AND") {
                                        onUpdate { copy(triggerLogic = "AND") }
                                    }
                                    // A one-shot rule combines only with «Все условия».
                                    ChoiceChip(
                                        stringResource(R.string.auto_ui_logic_any), editing.triggerLogic == "OR",
                                        enabled = !hasOneShot || editing.triggerLogic == "OR",
                                    ) {
                                        onUpdate { copy(triggerLogic = "OR") }
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                            }

                            editing.triggers.forEachIndexed { idx, trigger ->
                                TriggerRow(
                                    index = idx,
                                    trigger = trigger,
                                    places = places,
                                    missing = missing,
                                    showArrows = editing.triggers.size > 1,
                                    onUpdate = { newTrigger ->
                                        onUpdate {
                                            copy(triggers = triggers.toMutableList().apply { set(idx, newTrigger) })
                                        }
                                    },
                                    onMoveUp = if (idx > 0) {
                                        { onUpdate { copy(triggers = triggers.moveItem(idx, up = true)) } }
                                    } else null,
                                    onMoveDown = if (idx < editing.triggers.lastIndex) {
                                        { onUpdate { copy(triggers = triggers.moveItem(idx, up = false)) } }
                                    } else null,
                                    onDelete = {
                                        onUpdate {
                                            copy(triggers = triggers.toMutableList().apply { removeAt(idx) })
                                        }
                                    },
                                    modifier = Modifier.anchor(true, "t$idx"),
                                )
                                Spacer(Modifier.height(8.dp))
                            }

                            eventTriggerWithOthers(editing.triggers)?.let { kind ->
                                EventTriggerWarning(kind)
                                Spacer(Modifier.height(8.dp))
                            }

                            if (editing.triggers.size < 5) {
                                AddTriggerButton(
                                    places = places,
                                    highlight = Missing.NoConditions in missing,
                                    modifier = Modifier.anchor(true, "addTrigger"),
                                    onAdd = { t ->
                                        onUpdate {
                                            copy(
                                                triggers = triggers + t,
                                                triggerLogic = if (t.kind == OneShotTrigger.KIND) "AND" else triggerLogic,
                                            )
                                        }
                                    },
                                )
                            }
                        }

                        // Divider
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(1.dp)
                                .background(CardBorder)
                        )

                        // Right: Actions + Settings
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .verticalScroll(rightScroll)
                                .padding(16.dp, 12.dp)
                        ) {
                            SectionHeader(stringResource(R.string.automation_section_then))

                            editing.actions.forEachIndexed { idx, action ->
                                ActionRow(
                                    index = idx,
                                    action = action,
                                    places = places,
                                    tgBotConnected = tgBotConnected,
                                    highlight = missing.any { it is Missing.Action && it.action == idx + 1 },
                                    showArrows = editing.actions.size > 1,
                                    onUpdate = { newAction ->
                                        onUpdate {
                                            copy(actions = actions.toMutableList().apply { set(idx, newAction) })
                                        }
                                    },
                                    onTest = onTestAction,
                                    onMoveUp = if (idx > 0) {
                                        { onUpdate { copy(actions = actions.moveItem(idx, up = true)) } }
                                    } else null,
                                    onMoveDown = if (idx < editing.actions.lastIndex) {
                                        { onUpdate { copy(actions = actions.moveItem(idx, up = false)) } }
                                    } else null,
                                    onDelete = {
                                        onUpdate {
                                            copy(actions = actions.toMutableList().apply { removeAt(idx) })
                                        }
                                    },
                                    modifier = Modifier.anchor(false, "a$idx"),
                                )
                                Spacer(Modifier.height(8.dp))
                            }

                            if (editing.actions.size < MAX_RULE_ACTIONS) {
                                AddActionButton(
                                    highlight = Missing.NoActions in missing,
                                    modifier = Modifier.anchor(false, "addAction"),
                                    onAdd = { a -> onUpdate { copy(actions = actions + a) } },
                                )
                            }

                            Spacer(Modifier.height(20.dp))
                            SectionHeader(stringResource(R.string.automation_section_settings))

                            // Cooldown: «Не чаще, чем раз в 60 с»
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(stringResource(R.string.auto_ui_cooldown), fontSize = EDITOR_TEXT, color = TextPrimary)
                                Spacer(Modifier.width(12.dp))
                                // The field owns its text: binding it to cooldownSeconds.toString() made an
                                // empty field unrepresentable, so Backspace on the last digit was reverted
                                // and the caret jumped to the start (#163). Empty commits as 0.
                                var cooldownText by remember(editing.id) {
                                    mutableStateOf(editing.cooldownSeconds.toString())
                                }
                                EditorField(
                                    value = cooldownText,
                                    onValueChange = { v ->
                                        val digits = v.filter { it.isDigit() }
                                        cooldownText = digits
                                        onUpdate { copy(cooldownSeconds = digits.toIntOrNull() ?: 0) }
                                    },
                                    // Frozen while the draft is [EditingRule.saving]: onUpdate is a
                                    // no-op then, and without this the field's own remembered text
                                    // would still change, diverging from the frozen snapshot.
                                    enabled = !editing.saving,
                                    keyboardType = KeyboardType.Number,
                                    centered = true,
                                    modifier = Modifier.width(96.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.auto_ui_unit_s), fontSize = EDITOR_TEXT, color = TextSecondary)
                            }
                            Spacer(Modifier.height(4.dp))

                            SettingRow(stringResource(R.string.automation_setting_park_only)) {
                                Switch(
                                    checked = editing.requirePark,
                                    onCheckedChange = { v -> onUpdate { copy(requirePark = v) } },
                                    colors = bydSwitchColors(),
                                )
                            }
                            SettingRow(stringResource(R.string.automation_setting_confirm_before)) {
                                Switch(
                                    checked = editing.confirmBeforeExecute,
                                    onCheckedChange = { v -> onUpdate { copy(confirmBeforeExecute = v) } },
                                    colors = bydSwitchColors(),
                                )
                            }
                            SettingRow(stringResource(R.string.automation_setting_once_per_trip)) {
                                Switch(
                                    checked = editing.fireOncePerTrip,
                                    onCheckedChange = { v -> onUpdate { copy(fireOncePerTrip = v) } },
                                    colors = bydSwitchColors(),
                                )
                            }
                            // Play chime once when the rule fires
                            SettingRow(stringResource(R.string.automation_setting_play_sound)) {
                                Switch(
                                    checked = editing.playSound,
                                    onCheckedChange = { v -> onUpdate { copy(playSound = v) } },
                                    colors = bydSwitchColors(),
                                )
                            }
                        }
                    }

                    // Footer
                    HorizontalDivider(color = CardBorder)
                    val reason = remember(missing, context) { saveReason(missing, context) }
                    val complete = missing.isEmpty()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp, 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // «Тестовый запуск»: every action now, as if the conditions had matched.
                        Column(modifier = Modifier.widthIn(max = 360.dp).padding(end = 16.dp)) {
                            OutlinedEditorButton(
                                stringResource(R.string.automation_test_run_button),
                                enabled = editing.actions.isNotEmpty() && !testRunning,
                                leadingIcon = Icons.Outlined.PlayArrow,
                                onClick = onTestRun,
                            )
                            Text(
                                stringResource(R.string.automation_test_run_hint),
                                fontSize = 14.sp, color = TextSecondary, lineHeight = 18.sp,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        // What is still missing, or why the last save was refused.
                        Row(
                            modifier = Modifier.weight(1f).padding(end = 12.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val line = editorError ?: reason
                            if (line != null) {
                                Icon(
                                    Icons.Outlined.WarningAmber, null,
                                    tint = if (editorError != null) SocRed else AccentOrange,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    line, fontSize = EDITOR_TEXT, lineHeight = 22.sp,
                                    color = if (editorError != null) SocRed else AccentOrange,
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedEditorButton(stringResource(R.string.automation_cancel_button), onClick = requestClose)
                            OutlinedEditorButton(
                                stringResource(R.string.automation_share_button),
                                enabled = complete && shareEnabled,
                                onClick = onShare,
                            )
                            // Grey while something is missing; a tap then scrolls to the first such place.
                            Button(
                                onClick = {
                                    val first = missing.firstOrNull()
                                    if (first == null) {
                                        onSave()
                                    } else {
                                        val (left, key) = first.anchor()
                                        val y = (if (left) leftY else rightY)[key] ?: 0
                                        scope.launch { (if (left) leftScroll else rightScroll).animateScrollTo(y) }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (complete) AccentGreen else CardSurfaceElevated,
                                    contentColor = if (complete) NavyDark else TextSecondary,
                                ),
                                shape = FIELD_SHAPE,
                                enabled = !editing.saving,
                            ) { Text(stringResource(R.string.automation_save_button), fontSize = EDITOR_TEXT, fontWeight = FontWeight.SemiBold) }
                        }
                    }
                }
                message?.let { MessagePill(it, Modifier.align(Alignment.BottomCenter)) }
            }
        }
    }

    if (askUnsaved) {
        UnsavedChangesDialog(
            onDiscard = { askUnsaved = false; onDismiss() },
            onCancel = { askUnsaved = false },
            onSave = { askUnsaved = false; onSave() },
        )
    }
}

/** «Сохранить изменения?»: «Не сохранять» stands alone on the left, far from «Сохранить». */
@Composable
private fun UnsavedChangesDialog(onDiscard: () -> Unit, onCancel: () -> Unit, onSave: () -> Unit) {
    Dialog(onDismissRequest = onCancel) {
        ScaledDialogContent {
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .background(CardSurface, RoundedCornerShape(24.dp))
                    .padding(24.dp)
            ) {
                Text(stringResource(R.string.auto_ui_unsaved_title), fontSize = 22.sp, color = TextPrimary)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.auto_ui_unsaved_text), fontSize = EDITOR_TEXT, color = TextSecondary)
                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDiscard) {
                        Text(stringResource(R.string.auto_ui_unsaved_discard), fontSize = EDITOR_TEXT, color = AccentOrange)
                    }
                    Spacer(Modifier.weight(1f).widthIn(min = 24.dp))
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.automation_cancel_button), fontSize = EDITOR_TEXT, color = TextSecondary)
                    }
                    TextButton(onClick = onSave) {
                        Text(stringResource(R.string.automation_save_button), fontSize = EDITOR_TEXT, color = AccentGreen)
                    }
                }
            }
        }
    }
}

/** A widget button, a steering key or a voice phrase fires the rule at once: the other conditions are not checked. */
@Composable
private fun EventTriggerWarning(kind: String) {
    val name = stringResource(
        when (kind) {
            "button_press" -> R.string.auto_ui_event_button
            AutomationEngine.TRIGGER_KIND_STEERING_KEY -> R.string.auto_ui_event_key
            else -> R.string.auto_ui_event_voice
        }
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AccentOrange.copy(alpha = 0.08f), FIELD_SHAPE)
            .border(1.dp, AccentOrange, FIELD_SHAPE)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Outlined.WarningAmber, null, tint = AccentOrange, modifier = Modifier.size(20.dp))
        Column {
            Text(stringResource(R.string.auto_ui_event_warn, name), fontSize = EDITOR_TEXT, lineHeight = 22.sp, color = TextPrimary)
            Text(stringResource(R.string.auto_ui_event_warn_hint), fontSize = 14.sp, lineHeight = 20.sp, color = TextSecondary)
        }
    }
}

@Composable
private fun OutlinedEditorButton(
    label: String,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary, disabledContentColor = TextMuted),
        shape = FIELD_SHAPE,
        border = androidx.compose.foundation.BorderStroke(1.5.dp, CardBorder),
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, null, tint = if (enabled) AccentGreen else TextMuted, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, fontSize = EDITOR_TEXT)
    }
}

/**
 * A one-line text field, 40dp to see, 48dp to touch (a tap anywhere in the 48dp band focuses
 * it); an orange frame marks a value still missing.
 */
@Composable
private fun EditorField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    highlight: Boolean = false,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    centered: Boolean = false,
    textStyle: TextStyle = TextStyle(fontSize = EDITOR_TEXT, color = TextPrimary),
) {
    val focus = remember { FocusRequester() }
    val style = textStyle.merge(
        TextStyle(textAlign = if (centered) androidx.compose.ui.text.style.TextAlign.Center else androidx.compose.ui.text.style.TextAlign.Start)
    )
    Box(
        modifier = modifier
            .heightIn(min = MIN_TOUCH)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = enabled) {
                focus.requestFocus()
            },
        contentAlignment = Alignment.Center,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(AccentGreen),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus)
                .heightIn(min = FIELD_HEIGHT)
                .background(CardSurface, FIELD_SHAPE)
                .border(if (highlight) 1.5.dp else 1.dp, if (highlight) AccentOrange else CardBorder, FIELD_SHAPE),
            decorationBox = { inner ->
                Box(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = if (centered) Alignment.Center else Alignment.CenterStart,
                ) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(placeholder, style = style.copy(color = TextSecondary, fontWeight = FontWeight.Normal))
                    }
                    inner()
                }
            },
        )
    }
}

/** A selectable chip: green filled when selected, 40dp to see, 48dp to touch. */
@Composable
private fun ChoiceChip(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .heightIn(min = FIELD_HEIGHT)
            .clip(FIELD_SHAPE)
            .background(if (selected) AccentGreen else CardSurface)
            .border(if (selected) 0.dp else 1.dp, CardBorder, FIELD_SHAPE)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, fontSize = EDITOR_TEXT, fontWeight = FontWeight.SemiBold,
            color = when {
                selected -> NavyDark
                enabled -> TextSecondary
                else -> TextMuted
            },
        )
    }
}

/** A dropdown button: the current choice and a chevron, 40dp to see, 48dp to touch. */
@Composable
private fun DropdownField(
    text: String,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    color: Color = TextPrimary,
    fillWidth: Boolean = false,
    items: List<String>,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier.minimumInteractiveComponentSize()) {
        Row(
            modifier = (if (fillWidth) Modifier.fillMaxWidth() else Modifier)
                .heightIn(min = FIELD_HEIGHT)
                .clip(FIELD_SHAPE)
                .background(CardSurface)
                .border(if (highlight) 1.5.dp else 1.dp, if (highlight) AccentOrange else CardBorder, FIELD_SHAPE)
                .clickable { expanded = true }
                .padding(start = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text, fontSize = EDITOR_TEXT, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = if (fillWidth) Modifier.weight(1f) else Modifier,
            )
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Outlined.KeyboardArrowDown, null, tint = TextSecondary, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ScaledDialogContent {
                items.forEachIndexed { i, item ->
                    DropdownMenuItem(
                        text = { Text(item, fontSize = EDITOR_TEXT) },
                        onClick = {
                            expanded = false
                            onSelect(i)
                        },
                    )
                }
            }
        }
    }
}

/** Up/down reorder arrows shared by trigger and action rows. A null callback = boundary, shown dimmed and disabled. */
@Composable
private fun ReorderArrows(onMoveUp: (() -> Unit)?, onMoveDown: (() -> Unit)?) {
    IconButton(onClick = { onMoveUp?.invoke() }, enabled = onMoveUp != null, modifier = Modifier.size(MIN_TOUCH)) {
        Icon(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.auto_a11y_move_up),
            tint = TextSecondary.copy(alpha = if (onMoveUp != null) 1f else 0.25f), modifier = Modifier.size(20.dp))
    }
    IconButton(onClick = { onMoveDown?.invoke() }, enabled = onMoveDown != null, modifier = Modifier.size(MIN_TOUCH)) {
        Icon(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.auto_a11y_move_down),
            tint = TextSecondary.copy(alpha = if (onMoveDown != null) 1f else 0.25f), modifier = Modifier.size(20.dp))
    }
}

/** The arrows (only when the list has more than one row), a thin line, then the orange bin. */
@Composable
private fun RowButtons(showArrows: Boolean, onMoveUp: (() -> Unit)?, onMoveDown: (() -> Unit)?, onDelete: () -> Unit) {
    if (showArrows) ReorderArrows(onMoveUp = onMoveUp, onMoveDown = onMoveDown)
    Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(24.dp).background(CardBorder))
    IconButton(onClick = onDelete, modifier = Modifier.size(MIN_TOUCH)) {
        Icon(Icons.Outlined.Delete, stringResource(R.string.automation_delete_button), tint = AccentOrange, modifier = Modifier.size(20.dp))
    }
}

/** Icons per row in the widget-button icon picker menu. */
private const val ICON_PICKER_COLUMNS = 6

private fun triggerIcon(kind: String): ImageVector = when (kind) {
    "place_enter", "place_exit" -> Icons.Outlined.Place
    "time_of_day" -> Icons.Outlined.WbTwilight
    "time_range" -> Icons.Outlined.Schedule
    "service_start" -> Icons.Outlined.PlayArrow
    "network_available" -> Icons.Outlined.Wifi
    "button_press" -> Icons.Outlined.TouchApp
    AutomationEngine.TRIGGER_KIND_STEERING_KEY -> Icons.Outlined.Gamepad
    "voice" -> Icons.Outlined.RecordVoiceOver
    OneShotTrigger.KIND -> Icons.Outlined.CalendarMonth
    else -> Icons.Outlined.Tune
}

@Composable
private fun triggerTitle(kind: String): String = stringResource(
    when (kind) {
        "place_enter", "place_exit" -> R.string.automation_trigger_type_place
        "time_of_day" -> R.string.automation_trigger_type_time_of_day
        "time_range" -> R.string.automation_trigger_type_schedule
        "service_start" -> R.string.automation_trigger_type_service_start
        "network_available" -> R.string.automation_trigger_type_internet
        "button_press" -> R.string.auto_ui_event_button
        AutomationEngine.TRIGGER_KIND_STEERING_KEY -> R.string.auto_ui_event_key
        "voice" -> R.string.auto_ui_event_voice
        OneShotTrigger.KIND -> R.string.auto_ui_trigger_once
        else -> R.string.automation_trigger_type_param
    }
)

/**
 * One condition in two lines: on top the parameter across the row (or the kind's icon and name)
 * with the row buttons, below the operator, value and unit. What is missing is outlined orange.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TriggerRow(
    index: Int,
    trigger: TriggerDef,
    places: List<PlaceEntity>,
    missing: List<Missing>,
    showArrows: Boolean,
    onUpdate: (TriggerDef) -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val n = index + 1
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(CardSurface, FIELD_SHAPE)
            .border(1.dp, CardBorder, FIELD_SHAPE)
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$n", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextSecondary, modifier = Modifier.width(24.dp))
            Box(Modifier.weight(1f)) {
                if (trigger.kind == "param") {
                    val option = TRIGGER_PARAMS.find { it.param == trigger.param }
                    CatalogDropdown(
                        selected = option?.localizedName(context) ?: "",
                        placeholder = stringResource(R.string.auto_ui_pick_param),
                        highlight = Missing.Param(n) in missing,
                        items = TRIGGER_PARAMS.map { it.localizedName(context) },
                        categories = TRIGGER_PARAMS.map { it.localizedCategory(context) },
                        modifier = Modifier.fillMaxWidth(),
                        onSelect = { idx -> onUpdate(withParam(trigger, TRIGGER_PARAMS[idx], context)) }
                    )
                } else {
                    Row(Modifier.heightIn(min = MIN_TOUCH), verticalAlignment = Alignment.CenterVertically) {
                        Icon(triggerIcon(trigger.kind), null, tint = AccentGreen, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(triggerTitle(trigger.kind), fontSize = EDITOR_TEXT, color = TextPrimary)
                    }
                }
            }
            RowButtons(showArrows, onMoveUp, onMoveDown, onDelete)
        }
        Column(Modifier.padding(start = 24.dp, end = 8.dp)) {
            when (trigger.kind) {
                "place_enter", "place_exit" -> PlaceTriggerControls(trigger, places, onUpdate)
                "time_of_day" -> TimeOfDayTriggerControls(trigger, onUpdate)
                "time_range" -> ScheduleTriggerControls(trigger, onUpdate)
                "service_start", "network_available" -> Unit
                "button_press" -> ButtonPressTriggerControls(trigger, onUpdate)
                AutomationEngine.TRIGGER_KIND_STEERING_KEY ->
                    SteeringKeyTriggerControls(trigger, highlight = Missing.Key(n) in missing, onUpdate = onUpdate)
                "voice" -> VoiceTriggerControls(trigger, onUpdate)
                OneShotTrigger.KIND -> OneShotTriggerControls(trigger, onUpdate)
                else -> TRIGGER_PARAMS.find { it.param == trigger.param }?.let { option ->
                    ParamTriggerControls(
                        trigger, option,
                        valueMissing = Missing.Value(n) in missing,
                        numberMissing = Missing.Number(n) in missing,
                        onUpdate = onUpdate,
                    )
                }
            }
        }
    }
}

/**
 * The second line of a parameter condition. A list (gear, doors, trunk, belts) offers only
 * «равно / не равно» as buttons and a value dropdown; a number offers the six operators as
 * words, the field and its unit, with «Введите число, например 12,5» under a missing number.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ParamTriggerControls(
    trigger: TriggerDef,
    option: TriggerParamOption,
    valueMissing: Boolean,
    numberMissing: Boolean,
    onUpdate: (TriggerDef) -> Unit
) {
    val context = LocalContext.current
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (option.enumValues != null) {
            listOf("==", "!=").forEach { op ->
                Box(Modifier.align(Alignment.CenterVertically)) {
                    ChoiceChip(operatorWord(op, context.appLocalizedContext()), trigger.operator == op) {
                        onUpdate(trigger.copy(operator = op))
                    }
                }
            }
            val values = option.enumValues
            DropdownField(
                text = if (trigger.value.isBlank()) stringResource(R.string.auto_ui_pick_value)
                    else option.localizedEnumLabel(trigger.value, context),
                color = if (trigger.value.isBlank()) TextSecondary else TextPrimary,
                highlight = valueMissing,
                items = values.map { (v, _) -> option.localizedEnumLabel(v, context) },
                // The chosen operator stays; picking a value must not reset it (VadimV).
                onSelect = { i -> onUpdate(trigger.copy(value = values[i].first)) },
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        } else {
            val ops = operatorsFor(option)
            val lc = context.appLocalizedContext()
            DropdownField(
                text = operatorWord(trigger.operator, lc),
                items = ops.map { operatorWord(it, lc) },
                onSelect = { i -> onUpdate(trigger.copy(operator = ops[i])) },
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            EditorField(
                value = trigger.value,
                onValueChange = { v -> onUpdate(trigger.copy(value = v)) },
                highlight = numberMissing,
                keyboardType = KeyboardType.Decimal,
                centered = true,
                modifier = Modifier.width(96.dp).align(Alignment.CenterVertically),
            )
            val unit = option.localizedUnit(context)
            if (unit.isNotEmpty()) {
                Text(unit, fontSize = EDITOR_TEXT, color = TextSecondary, modifier = Modifier.align(Alignment.CenterVertically))
            }
        }
    }
    if (numberMissing) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
            Icon(Icons.Outlined.WarningAmber, null, tint = AccentOrange, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.auto_ui_number_hint), fontSize = 14.sp, color = AccentOrange)
        }
    }
}

/**
 * «Один раз: дата и время»: the date opens the system calendar, the time the system clock.
 * The rule runs once at that moment and then switches itself off.
 */
@Composable
private fun OneShotTriggerControls(trigger: TriggerDef, onUpdate: (TriggerDef) -> Unit) {
    val context = LocalContext.current
    val lc = context.appLocalizedContext()
    val at = remember(trigger.value) {
        OneShotTrigger.momentMs(trigger.value)?.let {
            java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
        } ?: defaultOneShotMoment()
    }
    fun push(next: java.time.LocalDateTime) = onUpdate(trigger.copy(value = OneShotTrigger.format(next)))
    val dateText = at.format(
        java.time.format.DateTimeFormatter.ofPattern(lc.getString(R.string.auto_ui_date_chip_pattern), lc.resources.configuration.locales[0])
    ).replaceFirstChar { it.titlecase(lc.resources.configuration.locales[0]) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IconChip(Icons.Outlined.CalendarMonth, dateText) {
            android.app.DatePickerDialog(
                context,
                { _, y, m, d -> push(at.withYear(y).withMonth(m + 1).withDayOfMonth(d)) },
                at.year, at.monthValue - 1, at.dayOfMonth,
            ).show()
        }
        Text(stringResource(R.string.auto_ui_once_at), fontSize = EDITOR_TEXT, color = TextSecondary)
        IconChip(Icons.Outlined.Schedule, minuteToHHmm(at.hour * 60 + at.minute), monospace = true) {
            TimePickerDialog(context, { _, h, m -> push(at.withHour(h).withMinute(m)) }, at.hour, at.minute, true).show()
        }
    }
    Text(
        stringResource(R.string.auto_ui_once_hint), fontSize = 14.sp, color = TextSecondary,
        modifier = Modifier.padding(top = 2.dp),
    )
}

/** Tomorrow at 08:00: where a new one-shot condition starts. */
private fun defaultOneShotMoment(): java.time.LocalDateTime =
    java.time.LocalDate.now().plusDays(1).atTime(8, 0)

private fun newOneShotTrigger(context: Context): TriggerDef = TriggerDef(
    param = OneShotTrigger.PARAM,
    chineseName = "单次",
    operator = "==",
    value = OneShotTrigger.format(defaultOneShotMoment()),
    displayName = context.getString(R.string.auto_ui_trigger_once),
    kind = OneShotTrigger.KIND,
)

/** A green value chip with an icon (date, time): 40dp to see, 48dp to touch. */
@Composable
private fun IconChip(icon: ImageVector, text: String, monospace: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .heightIn(min = FIELD_HEIGHT)
            .clip(FIELD_SHAPE)
            .background(CardSurface)
            .border(1.dp, CardBorder, FIELD_SHAPE)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = AccentGreen, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text, fontSize = EDITOR_TEXT, color = AccentGreen, fontWeight = FontWeight.Bold,
            fontFamily = if (monospace) FontFamily.Monospace else null,
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PlaceTriggerControls(
    trigger: TriggerDef,
    places: List<PlaceEntity>,
    onUpdate: (TriggerDef) -> Unit
) {
    val placeExists = places.any { it.id == trigger.placeId }
    val isStale = trigger.placeId != null && !placeExists
    val enterPrefix = stringResource(R.string.automation_trigger_place_enter_prefix)
    val exitPrefix = stringResource(R.string.automation_trigger_place_exit_prefix)

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Kind: Въезд / Выезд
        listOf("place_enter" to R.string.automation_trigger_place_enter_label, "place_exit" to R.string.automation_trigger_place_exit_label)
            .forEach { (kind, label) ->
                ChoiceChip(stringResource(label), trigger.kind == kind) {
                    val kindLabel = if (kind == "place_enter") enterPrefix else exitPrefix
                    onUpdate(trigger.copy(kind = kind, displayName = "$kindLabel «${trigger.placeName ?: "?"}»"))
                }
            }
        if (isStale || (trigger.placeId == null && places.isEmpty())) {
            // The referenced place was deleted
            Box(Modifier.heightIn(min = MIN_TOUCH), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.automation_trigger_place_deleted),
                    fontSize = EDITOR_TEXT,
                    color = SocRed,
                    modifier = Modifier
                        .background(SocRed.copy(alpha = 0.08f), FIELD_SHAPE)
                        .border(1.dp, SocRed.copy(alpha = 0.3f), FIELD_SHAPE)
                        .padding(12.dp, 8.dp)
                )
            }
        } else {
            DropdownField(
                text = trigger.placeName ?: stringResource(R.string.automation_trigger_place_deleted_placeholder),
                items = places.map { it.name },
                onSelect = { i ->
                    val place = places[i]
                    val kindLabel = if (trigger.kind == "place_enter") enterPrefix else exitPrefix
                    onUpdate(trigger.copy(placeId = place.id, placeName = place.name, displayName = "$kindLabel «${place.name}»"))
                },
            )
        }
    }
}

@Composable
private fun TimeOfDayTriggerControls(
    trigger: TriggerDef,
    onUpdate: (TriggerDef) -> Unit
) {
    val phases = listOf(
        "DAY" to stringResource(R.string.automation_trigger_day),
        "NIGHT" to stringResource(R.string.automation_trigger_night),
        "DAWN" to stringResource(R.string.automation_trigger_dawn),
        "DUSK" to stringResource(R.string.automation_trigger_dusk),
    )
    val current = phases.find { it.first == trigger.value.uppercase() } ?: phases[1]
    DropdownField(
        text = current.second,
        items = phases.map { it.second },
        onSelect = { i -> onUpdate(trigger.copy(value = phases[i].first, displayName = phases[i].second)) },
    )
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ScheduleTriggerControls(
    trigger: TriggerDef,
    onUpdate: (TriggerDef) -> Unit
) {
    val context = LocalContext.current
    val spec = remember(trigger.value) {
        ScheduleSpec.fromJson(trigger.value) ?: ScheduleSpec(8 * 60, 10 * 60, emptySet())
    }
    val isExact = spec.isExact

    fun push(newSpec: ScheduleSpec) {
        onUpdate(trigger.copy(value = newSpec.toJson(), displayName = scheduleDisplayName(context, newSpec)))
    }

    fun pickTime(currentMinute: Int, onPicked: (Int) -> Unit) {
        TimePickerDialog(
            context,
            { _, h, m -> onPicked(h * 60 + m) },
            currentMinute / 60, currentMinute % 60, true
        ).show()
    }

    val dayLabels = listOf(
        1 to stringResource(R.string.automation_day_mon),
        2 to stringResource(R.string.automation_day_tue),
        3 to stringResource(R.string.automation_day_wed),
        4 to stringResource(R.string.automation_day_thu),
        5 to stringResource(R.string.automation_day_fri),
        6 to stringResource(R.string.automation_day_sat),
        7 to stringResource(R.string.automation_day_sun),
    )

    Column {
        // Mode (exact / range) and the time picker(s) on one line, wrapping when narrow.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceChip(stringResource(R.string.automation_schedule_mode_exact), isExact) {
                if (!isExact) push(spec.copy(toMinute = spec.fromMinute))
            }
            // Open a 2h window so "from" and "to" differ when switching to range.
            ChoiceChip(stringResource(R.string.automation_schedule_mode_range), !isExact) {
                if (isExact) push(spec.copy(toMinute = (spec.fromMinute + 120) % 1440))
            }
            Row(Modifier.heightIn(min = MIN_TOUCH), verticalAlignment = Alignment.CenterVertically) {
                if (isExact) {
                    Text(stringResource(R.string.automation_schedule_time_label), fontSize = EDITOR_TEXT, color = TextSecondary)
                    Spacer(Modifier.width(8.dp))
                    TimeChip(minuteToHHmm(spec.fromMinute)) {
                        pickTime(spec.fromMinute) { push(spec.copy(fromMinute = it, toMinute = it)) }
                    }
                } else {
                    Text(stringResource(R.string.automation_schedule_from), fontSize = EDITOR_TEXT, color = TextSecondary)
                    Spacer(Modifier.width(8.dp))
                    TimeChip(minuteToHHmm(spec.fromMinute)) {
                        pickTime(spec.fromMinute) { push(spec.copy(fromMinute = it)) }
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.automation_schedule_to), fontSize = EDITOR_TEXT, color = TextSecondary)
                    Spacer(Modifier.width(8.dp))
                    TimeChip(minuteToHHmm(spec.toMinute)) {
                        pickTime(spec.toMinute) { push(spec.copy(toMinute = it)) }
                    }
                }
            }
        }
        // Days of week (none selected = every day)
        Text(stringResource(R.string.automation_schedule_days_label), fontSize = 14.sp, color = TextSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            dayLabels.forEach { (d, label) ->
                DayChip(
                    label = label,
                    selected = d in spec.days,
                    onClick = {
                        val newDays = if (d in spec.days) spec.days - d else spec.days + d
                        push(spec.copy(days = newDays))
                    },
                )
            }
        }
    }
}

@Composable
private fun TimeChip(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .heightIn(min = FIELD_HEIGHT)
            .clip(FIELD_SHAPE)
            .background(CardSurface)
            .border(1.dp, CardBorder, FIELD_SHAPE)
            .clickable { onClick() }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = EDITOR_TEXT, color = AccentGreen, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

/** A weekday, 48x48dp. */
@Composable
private fun DayChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(vertical = 2.dp)
            .size(MIN_TOUCH)
            .clip(FIELD_SHAPE)
            .background(if (selected) AccentGreen.copy(alpha = 0.15f) else CardSurface)
            .border(1.dp, if (selected) AccentGreen else CardBorder, FIELD_SHAPE)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = EDITOR_TEXT,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) AccentGreen else TextSecondary,
        )
    }
}

/** Readable schedule label; first token is the time so the rule summary stays compact. */
internal fun scheduleDisplayName(context: android.content.Context, spec: ScheduleSpec): String {
    val time = if (spec.isExact) {
        minuteToHHmm(spec.fromMinute)
    } else {
        "${minuteToHHmm(spec.fromMinute)}-${minuteToHHmm(spec.toMinute)}"
    }
    val days = scheduleDaysShort(context, spec.days)
    return if (days.isEmpty()) time else "$time $days"
}

private fun scheduleDaysShort(context: android.content.Context, days: Set<Int>): String {
    if (days.isEmpty() || days.size == 7) return ""
    val labels = mapOf(
        1 to R.string.automation_day_mon,
        2 to R.string.automation_day_tue,
        3 to R.string.automation_day_wed,
        4 to R.string.automation_day_thu,
        5 to R.string.automation_day_fri,
        6 to R.string.automation_day_sat,
        7 to R.string.automation_day_sun,
    )
    return days.sorted().joinToString(",") { context.getString(labels.getValue(it)) }
}

@Composable
private fun VoiceTriggerControls(trigger: TriggerDef, onUpdate: (TriggerDef) -> Unit) {
    val voiceLabel = stringResource(R.string.automation_trigger_type_voice)
    EditorField(
        value = trigger.value,
        onValueChange = { phrase ->
            onUpdate(trigger.copy(value = phrase, displayName = phrase.ifBlank { voiceLabel }))
        },
        placeholder = stringResource(R.string.automation_trigger_voice_phrase_label),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ButtonPressTriggerControls(
    trigger: TriggerDef,
    onUpdate: (TriggerDef) -> Unit,
) {
    val context = LocalContext.current
    val current = trigger.value.toIntOrNull() ?: 1
    val labels = (1..4).map { stringResource(R.string.automation_trigger_button_label, it) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DropdownField(
            text = labels.getOrElse(current - 1) { current.toString() },
            items = labels,
            onSelect = { i ->
                onUpdate(
                    trigger.copy(
                        value = (i + 1).toString(),
                        displayName = context.getString(R.string.automation_trigger_button_label, i + 1),
                    )
                )
            },
        )
        ButtonIconPicker(buttonNumber = current)
    }
}

/**
 * Picks the icon drawn on widget button [buttonNumber] instead of its digit. The
 * icon belongs to the button, not to the rule: it is written to widget prefs right
 * away, so if two rules share a button the last choice wins. Tapping the selected
 * icon again clears it and the button goes back to showing its number.
 */
@Composable
private fun ButtonIconPicker(buttonNumber: Int) {
    val context = LocalContext.current
    val prefs = remember(context) { WidgetPreferences(context) }
    var selectedId by remember(buttonNumber) { mutableStateOf(prefs.buttonIconId(buttonNumber)) }
    var open by remember { mutableStateOf(false) }
    val selected = WidgetButtonIcons.find(selectedId)

    Box {
        Box(
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .size(FIELD_HEIGHT)
                .clip(FIELD_SHAPE)
                .background(CardSurface)
                .border(1.dp, CardBorder, FIELD_SHAPE)
                .clickable { open = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = selected?.vector ?: Icons.Outlined.AddPhotoAlternate,
                contentDescription = stringResource(R.string.widget_button_icon_hint),
                tint = if (selected != null) AccentGreen else TextSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ScaledDialogContent {
                Text(
                    stringResource(R.string.widget_button_icon_hint),
                    fontSize = 14.sp,
                    color = TextSecondary,
                    modifier = Modifier.padding(12.dp, 6.dp),
                )
                WidgetButtonIcons.CATALOG.chunked(ICON_PICKER_COLUMNS).forEach { row ->
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
                        row.forEach { entry ->
                            val isSelected = entry.id == selectedId
                            Box(
                                modifier = Modifier
                                    .padding(2.dp)
                                    .background(
                                        if (isSelected) AccentGreen.copy(alpha = 0.18f) else CardSurface,
                                        RoundedCornerShape(6.dp),
                                    )
                                    .border(
                                        1.dp,
                                        if (isSelected) AccentGreen else CardBorder,
                                        RoundedCornerShape(6.dp),
                                    )
                                    .clickable {
                                        val next = if (isSelected) null else entry.id
                                        selectedId = next
                                        prefs.setButtonIconId(buttonNumber, next)
                                        Log.i("AutomationScreen", "button $buttonNumber icon=$next")
                                    }
                                    .padding(6.dp)
                                    .size(22.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = entry.vector,
                                    contentDescription = stringResource(entry.labelRes),
                                    tint = if (isSelected) AccentGreen else TextSecondary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SteeringKeyTriggerControls(
    trigger: TriggerDef,
    highlight: Boolean,
    onUpdate: (TriggerDef) -> Unit,
) {
    val context = LocalContext.current
    var learning by remember { mutableStateOf(false) }
    val code = trigger.value.toIntOrNull() ?: 0
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .heightIn(min = FIELD_HEIGHT)
            .clip(FIELD_SHAPE)
            .background(CardSurface)
            .border(if (highlight) 1.5.dp else 1.dp, if (highlight) AccentOrange else CardBorder, FIELD_SHAPE)
            .clickable { learning = true }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (code > 0) "${steeringKeyLabel(context, code)} ($code)"
            else stringResource(R.string.automation_trigger_steering_key_assign),
            fontSize = EDITOR_TEXT, color = AccentGreen, fontWeight = FontWeight.Bold,
        )
    }

    if (learning) {
        LearnButtonDialog(
            onSave = { learned ->
                onUpdate(trigger.copy(value = learned.toString(), displayName = "Клавиша $learned"))
                learning = false
            },
            onDismiss = { learning = false },
            occupiedReason = steeringKeyOccupiedReason(context),
        )
    }
}

/** Human label for a steering-wheel keycode, e.g. "Левая звезда" or "Кнопка (код 383)". */
internal fun steeringKeyLabel(context: Context, keyCode: Int): String {
    val res = knownButtonNameRes(keyCode)
    return if (res != 0) context.getString(res)
    else context.getString(R.string.steering_button_unknown, keyCode)
}

/**
 * Keys already owned by another BYDMate feature. SteeringWheelKeyService handles projection, the
 * voice button and the volume knob BEFORE automation rules, so a rule bound to one of those keys
 * would never fire — the learn dialog says so instead of saving a dead binding.
 */
private fun steeringKeyOccupiedReason(context: Context): (Int) -> String? {
    val clusterPrefs = context.getSharedPreferences(ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE)
    val voicePrefs = context.getSharedPreferences("voice", Context.MODE_PRIVATE)
    return { keyCode ->
        when {
            clusterPrefs.getBoolean(ClusterProjectionManager.KEY_MIRROR_ENABLED, false) &&
                keyCode == clusterPrefs.getInt(ClusterProjectionManager.KEY_TRIGGER_KEYCODE, DEFAULT_TRIGGER_KEYCODE) ->
                context.getString(R.string.automation_steering_key_occupied_projection)

            // The companion codes of the voice button's press are swallowed by voice as well.
            voicePrefs.getBoolean("voice_enabled", false) && (
                keyCode == voicePrefs.getInt("voice_keycode", DEFAULT_VOICE_KEYCODE) ||
                    keyCode in voiceCompanionsFromCsv(
                        voicePrefs.getString(SettingsRepository.KEY_VOICE_COMPANIONS, null))
                ) ->
                context.getString(R.string.automation_steering_key_occupied_voice)

            clusterPrefs.getBoolean(ClusterProjectionManager.KEY_KNOB_PLAY_PAUSE, false) &&
                keyCode == VOLUME_KNOB_PRESS_KEYCODE ->
                context.getString(R.string.automation_steering_key_occupied_knob)

            else -> null
        }
    }
}

// --- Action Row ---

/**
 * One action: «Выполнить сейчас» (green triangle in a light circle) at the left before the
 * action, the action's own controls across the row, then the arrows and the orange bin at the
 * far end. Every button is 48x48dp.
 */
@Composable
private fun ActionRow(
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
                else -> // "param" (default)
                    ParamActionControls(action = action, onUpdate = onUpdate, modifier = fill)
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
    CatalogDropdown(
        selected = ACTION_COMMANDS.find { it.command == action.command }?.localizedName(context) ?: action.displayName,
        items = ACTION_COMMANDS.map { it.localizedName(context) },
        categories = ACTION_COMMANDS.map { it.localizedCategory(context) },
        modifier = modifier,
        onSelect = { idx -> onUpdate(actionDefFor(ACTION_COMMANDS[idx], context)) }
    )
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

/** «Подождать 30 с»: the wait between two actions. */
@Composable
private fun DelayActionControls(
    action: ActionDef,
    onUpdate: (ActionDef) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lc = context.appLocalizedContext()
    val currentMs = action.payload?.toLongOrNull() ?: 1000L
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.HourglassEmpty, contentDescription = null, tint = AccentTeal, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.auto_ui_wait), fontSize = EDITOR_TEXT, color = TextPrimary)
        Spacer(Modifier.width(8.dp))
        DropdownField(
            text = durationText(currentMs, lc),
            fillWidth = true,
            items = DELAY_OPTION_MS.map { durationText(it, lc) },
            onSelect = { i -> onUpdate(delayAction(DELAY_OPTION_MS[i], context)) },
            modifier = Modifier.weight(1f),
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

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Outlined.VolumeUp,
            contentDescription = null,
            tint = AccentTeal,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = EDITOR_TEXT, color = TextPrimary)
        Spacer(Modifier.width(8.dp))
        Slider(
            value = current.toFloat(),
            onValueChange = { v ->
                val lvl = v.toInt().coerceIn(0, maxVolume)
                onUpdate(action.copy(payload = lvl.toString(), displayName = "$namePrefix: $lvl"))
            },
            valueRange = 0f..maxVolume.toFloat(),
            steps = (maxVolume - 1).coerceAtLeast(0),
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "$current/$maxVolume",
            fontSize = EDITOR_TEXT,
            color = AccentTeal,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.widthIn(min = 48.dp)
        )
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
            // «Включить» tapped again keeps the app a projection step picked (#279).
            val state = ActionDispatcher.onOffState(action.payload)
            StateChip(onLabel, selected = !isToggle && state == "1") {
                if (isToggle || ActionDispatcher.clusterApp(action.payload) == null) {
                    onUpdate(rowStateAction(spec, "1", "$displayLabel: $onLabel"))
                }
            }
            StateChip(offLabel, selected = !isToggle && state == "0") {
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

// --- Cluster Projection Action Controls ---

@Composable
private fun ClusterActionControls(
    action: ActionDef,
    onUpdate: (ActionDef) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val displayLabel = stringResource(R.string.automation_action_cluster_projection)
    val onLabel = stringResource(R.string.automation_action_cluster_projection_on)
    Column(modifier = modifier) {
        OnOffToggleControls(
            action = action,
            icon = Icons.Outlined.Speed,
            displayLabel = displayLabel,
            onLabel = onLabel,
            offLabel = stringResource(R.string.automation_action_cluster_projection_off),
            spec = CLUSTER_ROW,
            context = context,
            onUpdate = onUpdate,
            modifier = Modifier.fillMaxWidth(),
        )
        // «Включить» may name its own app (#279); none = the app chosen in Settings.
        if (action.kind == CLUSTER_ROW.kind && ActionDispatcher.onOffState(action.payload) == "1") {
            Spacer(Modifier.height(8.dp))
            ClusterAppField(
                action = action,
                onPick = { pkg, label ->
                    onUpdate(action.withClusterApp(pkg, label, "$displayLabel: ${pkg?.let { label } ?: onLabel}"))
                },
            )
        }
    }
}

/** The app a projection step sends to the cluster: the Settings choice until one is picked here. */
@Composable
private fun ClusterAppField(action: ActionDef, onPick: (pkg: String?, label: String) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val pkg = ActionDispatcher.clusterApp(action.payload)
    val label = ActionDispatcher.clusterAppLabel(action.payload) ?: pkg
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = Modifier
                .weight(1f)
                .minimumInteractiveComponentSize()
                .background(CardSurface, RoundedCornerShape(6.dp))
                .border(1.dp, CardBorder, RoundedCornerShape(6.dp))
                .clickable { picking = true }
                .heightIn(min = FIELD_HEIGHT)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Apps, null, tint = AccentTeal, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = label ?: stringResource(R.string.automation_action_cluster_app_default),
                fontSize = EDITOR_TEXT,
                color = if (label == null) TextSecondary else TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (pkg != null) {
            IconButton(onClick = { onPick(null, "") }, modifier = Modifier.size(MIN_TOUCH)) {
                Icon(Icons.Outlined.Close, stringResource(R.string.automation_action_cluster_app_reset), tint = TextSecondary)
            }
        }
    }
    if (picking) {
        AppLaunchPickerDialog(
            currentPackage = pkg.orEmpty(),
            onDismiss = { picking = false },
            onSelect = { newPkg, newLabel ->
                onPick(newPkg, newLabel)
                picking = false
            },
        )
    }
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
private fun CatalogDropdown(
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

/** The section the picker opened on last time: it opens there again. */
private object ActionPickerMemory {
    var section = 0
}

/** The car's catalog sections in the picker's order, then messages, apps, screen, system and waits. */
private val CATALOG_SECTIONS = listOf(
    R.string.auto_cat_windows, R.string.auto_cat_climate, R.string.auto_cat_seats, R.string.auto_cat_sunroof,
    R.string.auto_cat_locks, R.string.auto_cat_body, R.string.auto_cat_light, R.string.auto_cat_mirrors,
    R.string.auto_cat_drive_mode, R.string.auto_cat_fridge,
)

@Composable
private fun pickerSections(): List<PickerSection> {
    val context = LocalContext.current
    val lc = context.appLocalizedContext()
    return remember(lc) {
        val catalog = CATALOG_SECTIONS.map { cat ->
            PickerSection(
                lc.getString(cat),
                ACTION_COMMANDS.filter { it.categoryRes == cat }.map { option ->
                    PickerTile(option.localizedName(lc)) { actionDefFor(option, it) }
                },
            )
        }.filter { it.tiles.isNotEmpty() }
        catalog + listOf(
            PickerSection(lc.getString(R.string.auto_ui_section_messages), listOf(
                PickerTile(lc.getString(R.string.automation_action_notification), needsOverlay = true) { newNotificationAction(it) },
                PickerTile(lc.getString(R.string.automation_action_speak)) { newSpeakAction(it) },
                PickerTile(lc.getString(R.string.automation_action_agent_query)) { newAgentQueryAction(it) },
                PickerTile(lc.getString(R.string.automation_action_tg_report_add)) { newTelegramReportAction(it) },
            )),
            PickerSection(lc.getString(R.string.auto_ui_section_apps), listOf(
                PickerTile(lc.getString(R.string.automation_action_app_launch)) { newAppLaunchAction(it) },
                PickerTile(lc.getString(R.string.automation_action_yandex_music)) { newYandexMusicAction(it) },
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
private fun AddActionButton(
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
private fun AddTriggerButton(
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
private fun MessagePill(text: String, modifier: Modifier = Modifier) {
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
private fun SectionHeader(text: String) {
    Text(
        text, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextSecondary,
        letterSpacing = 1.2.sp,
        modifier = Modifier.padding(bottom = 10.dp)
    )
}

@Composable
private fun SettingRow(label: String, content: @Composable () -> Unit) {
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
            // Scrolls, so the buttons stay reachable at the largest text size.
            Column(
                modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
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
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = textValue,
                    onValueChange = { if (it.length <= TG_REPORT_TEXT_MAX) textValue = it },
                    label = { Text(stringResource(R.string.automation_tg_report_text_label)) },
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

private fun newServiceStartTrigger(context: android.content.Context): TriggerDef {
    return TriggerDef(
        param = "ServiceStart",
        chineseName = "服务启动",
        operator = "==",
        value = "true",
        displayName = context.getString(R.string.auto_trig_service_start),
        kind = "service_start"
    )
}

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
