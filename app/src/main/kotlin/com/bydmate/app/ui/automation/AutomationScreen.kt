package com.bydmate.app.ui.automation

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import kotlinx.coroutines.delay
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material.icons.automirrored.outlined.Send
import com.bydmate.app.data.telegram.ReportField
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.PlayArrow
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
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.bydmate.app.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bydmate.app.data.automation.ScheduleSpec
import com.bydmate.app.data.automation.minuteToHHmm
import com.bydmate.app.data.automation.serviceStartTrigger
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.PlaceEntity
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.TriggerDef
import com.bydmate.app.ui.components.AppLaunchPickerDialog
import com.bydmate.app.ui.theme.*

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

// --- Speak Action Controls (v3.6) ---

@Composable
internal fun SpeakActionControls(
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
internal fun AgentQueryActionControls(
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
internal fun TelegramReportActionControls(
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
internal fun SplitScreenActionControls(
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
internal fun SplitScreenStateActionControls(kind: String, modifier: Modifier = Modifier) {
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
internal fun AppLaunchActionControls(
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
internal fun AppCloseActionControls(
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
internal fun MediaKeyActionControls(payload: String?, modifier: Modifier = Modifier) {
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
internal fun CallActionControls(
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
internal fun NavigateActionControls(
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
internal fun UrlActionControls(
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
internal fun YandexMusicActionControls(
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

internal fun newPlaceTrigger(place: PlaceEntity, context: android.content.Context): TriggerDef {
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

internal fun newServiceStartTrigger(context: android.content.Context): TriggerDef =
    serviceStartTrigger(context.getString(R.string.auto_trig_service_start))

internal fun newNetworkAvailableTrigger(context: android.content.Context): TriggerDef {
    return TriggerDef(
        param = "NetworkAvailable",
        chineseName = "网络可用",
        operator = "==",
        value = "true",
        displayName = context.getString(R.string.auto_trig_network_available),
        kind = "network_available"
    )
}

internal fun newVoiceTrigger(context: android.content.Context): TriggerDef = TriggerDef(
    param = "Voice",
    chineseName = "语音",
    operator = "==",
    value = "",
    displayName = context.getString(R.string.automation_trigger_type_voice),
    kind = "voice"
)

internal fun newMediaVolumeAction(context: android.content.Context): ActionDef = ActionDef(
    command = "media_volume",
    displayName = context.getString(R.string.auto_act_media_volume),
    kind = "media_volume",
    payload = "2"
)

internal fun newTimeOfDayTrigger(context: android.content.Context): TriggerDef {
    return TriggerDef(
        param = "TimeOfDay",
        chineseName = "时间段",
        operator = "==",
        value = "NIGHT",
        displayName = context.getString(R.string.auto_trig_time_of_day_night),
        kind = "time_of_day"
    )
}

internal fun newScheduleTrigger(): TriggerDef {
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
