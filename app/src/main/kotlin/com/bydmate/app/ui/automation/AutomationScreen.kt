package com.bydmate.app.ui.automation

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import kotlinx.coroutines.delay
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Place
import com.bydmate.app.ui.components.AppAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.bydmate.app.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.ui.theme.*

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AutomationScreen(
    /** A rule file opened from a file manager: imported through the usual preview, then taken. */
    ruleFile: Uri? = null,
    onRuleFileTaken: () -> Unit = {},
    viewModel: AutomationViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(ruleFile) {
        if (ruleFile != null) {
            viewModel.openRuleFile(ruleFile)
            onRuleFileTaken()
        }
    }
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
