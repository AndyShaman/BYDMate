package com.bydmate.app.ui.automation

import android.app.TimePickerDialog
import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.AlertDialog
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bydmate.app.data.automation.ScheduleSpec
import com.bydmate.app.data.automation.minuteToHHmm
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.PlaceEntity
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.RuleLogEntity
import com.bydmate.app.data.local.entity.TriggerDef
import com.bydmate.app.ui.components.AppLaunchPickerDialog
import com.bydmate.app.ui.components.bydSwitchColors
import com.bydmate.app.ui.theme.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AutomationScreen(
    viewModel: AutomationViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val filtered = remember(state.rules, state.filter) {
        when (state.filter) {
            RuleFilter.ALL -> state.rules
            RuleFilter.ENABLED -> state.rules.filter { it.enabled }
            RuleFilter.DISABLED -> state.rules.filter { !it.enabled }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(NavyDark, NavyDeep)))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.automation_tab_title), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Spacer(Modifier.width(16.dp))
            AutoChip(stringResource(R.string.automation_filter_all), state.filter == RuleFilter.ALL) { viewModel.setFilter(RuleFilter.ALL) }
            Spacer(Modifier.width(4.dp))
            AutoChip(stringResource(R.string.automation_filter_active), state.filter == RuleFilter.ENABLED) { viewModel.setFilter(RuleFilter.ENABLED) }
            Spacer(Modifier.width(4.dp))
            AutoChip(stringResource(R.string.automation_filter_disabled), state.filter == RuleFilter.DISABLED) { viewModel.setFilter(RuleFilter.DISABLED) }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { viewModel.showJournal() },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.border(1.5.dp, CardBorder, RoundedCornerShape(8.dp))
            ) { Text(stringResource(R.string.automation_journal_button), fontSize = 13.sp) }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { viewModel.openNewRule() },
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = NavyDark),
                shape = RoundedCornerShape(8.dp)
            ) { Text(stringResource(R.string.automation_create_button), fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
        }

        Spacer(Modifier.height(12.dp))

        // Rule list
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(filtered, key = { it.id }) { rule ->
                RuleCard(
                    rule = rule,
                    onToggle = { viewModel.toggleEnabled(rule) },
                    onClick = { viewModel.openEditRule(rule) },
                    onEdit = { viewModel.openEditRule(rule) },
                    onDuplicate = { viewModel.duplicateRule(rule) },
                    onDelete = { viewModel.requestDelete(rule.id) }
                )
            }
        }
    }

    // Editor dialog
    if (state.showEditor) {
        EditorDialog(
            editing = state.editing,
            places = state.places,
            editorError = state.editorError,
            onUpdate = { viewModel.updateEditing(it) },
            onSave = { viewModel.saveRule() },
            onTestAction = { viewModel.executeNow(it) },
            onDismiss = { viewModel.closeEditor() }
        )
    }

    // Journal dialog
    if (state.showJournal) {
        JournalDialog(
            logs = state.logs,
            onDismiss = { viewModel.hideJournal() }
        )
    }

    // Delete confirmation
    state.showDeleteConfirm?.let {
        AlertDialog(
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

// --- Rule Card ---

@Composable
private fun RuleCard(
    rule: RuleEntity,
    onToggle: () -> Unit,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit
) {
    val triggers = remember(rule.triggers) { TriggerDef.listFromJson(rule.triggers) }
    val actions = remember(rule.actions) { ActionDef.listFromJson(rule.actions) }
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (rule.enabled) CardSurface else CardSurface.copy(alpha = 0.55f)
        ),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.5.dp,
            if (rule.enabled) AccentGreen.copy(alpha = 0.25f) else CardBorder
        )
    ) {
        Column(modifier = Modifier.padding(12.dp, 10.dp)) {
            // Header: dot + name + toggle + menu
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (rule.enabled) AccentGreen else Color.Transparent)
                        .border(1.5.dp, if (rule.enabled) AccentGreen else TextSecondary, CircleShape)
                )
                Spacer(Modifier.width(8.dp))
                Text(rule.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary,
                    modifier = Modifier.weight(1f))
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = { onToggle() },
                    colors = bydSwitchColors(),
                    modifier = Modifier.height(24.dp)
                )
                Box {
                    IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.MoreVert, "menu", tint = TextMuted, modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.automation_menu_edit)) },
                            onClick = { menuExpanded = false; onEdit() },
                            leadingIcon = { Icon(Icons.Outlined.Edit, null, modifier = Modifier.size(18.dp)) }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.automation_menu_duplicate)) },
                            onClick = { menuExpanded = false; onDuplicate() },
                            leadingIcon = { Icon(Icons.Outlined.ContentCopy, null, modifier = Modifier.size(18.dp)) }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.automation_menu_delete), color = Color(0xFFEF4444)) },
                            onClick = { menuExpanded = false; onDelete() },
                            leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = Color(0xFFEF4444), modifier = Modifier.size(18.dp)) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            // Trigger → Action summary
            val logicAndLabel = stringResource(R.string.automation_rule_logic_and)
            val logicOrLabel = stringResource(R.string.automation_rule_logic_or)
            Text(
                buildAnnotatedString {
                    triggers.forEachIndexed { i, t ->
                        if (i > 0) {
                            withStyle(SpanStyle(color = TextMuted, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)) {
                                append(if (rule.triggerLogic == "AND") logicAndLabel else logicOrLabel)
                            }
                        }
                        if (t.kind == "time_range") {
                            // value is JSON (parsed by the engine); show the readable displayName instead.
                            withStyle(SpanStyle(color = AccentBlue)) { append(t.displayName) }
                        } else {
                            withStyle(SpanStyle(color = AccentBlue)) { append(t.displayName.substringBefore(" ")) }
                            append(" ")
                            withStyle(SpanStyle(color = AccentOrange)) { append(t.operator) }
                            append(" ")
                            withStyle(SpanStyle(color = AccentGreen, fontFamily = FontFamily.Monospace, fontSize = 12.sp)) {
                                append(t.value)
                            }
                        }
                    }
                    withStyle(SpanStyle(color = TextMuted)) { append(" → ") }
                    withStyle(SpanStyle(color = AccentTeal)) {
                        append(actions.joinToString(", ") { it.displayName })
                    }
                },
                fontSize = 13.sp, lineHeight = 20.sp
            )

            // Stats
            Spacer(Modifier.height(4.dp))
            val triggeredLabel = stringResource(R.string.automation_rule_triggered_count, rule.triggerCount)
            val localContext = LocalContext.current
            val lastLabel = rule.lastTriggeredAt?.let { ts ->
                " · " + stringResource(R.string.automation_rule_last_trigger, formatRelativeTime(ts, localContext))
            } ?: ""
            val cooldownLabel = " · " + stringResource(R.string.automation_rule_cooldown, rule.cooldownSeconds)
            Text(triggeredLabel + lastLabel + cooldownLabel, fontSize = 11.sp, color = TextMuted)
        }
    }
}

// --- Editor Dialog ---

@Composable
private fun EditorDialog(
    editing: EditingRule,
    places: List<PlaceEntity>,
    editorError: String?,
    onUpdate: (EditingRule.() -> EditingRule) -> Unit,
    onSave: () -> Unit,
    onTestAction: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false
        )
    ) {
        val context = LocalContext.current
        Column(
            modifier = Modifier
                .fillMaxWidth(0.65f)
                .fillMaxHeight(0.85f)
                .background(NavyDeep, RoundedCornerShape(16.dp))
                .border(1.5.dp, CardBorder, RoundedCornerShape(16.dp))
        ) {
            // Title bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp, 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (editing.isNew) stringResource(R.string.automation_editor_new_rule_title) else editing.name,
                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Outlined.Close, "close", tint = TextMuted)
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
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp, 12.dp)
                ) {
                    OutlinedTextField(
                        value = editing.name,
                        onValueChange = { v -> onUpdate { copy(name = v) } },
                        placeholder = { Text(stringResource(R.string.automation_rule_name_placeholder), color = TextMuted) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentGreen,
                            unfocusedBorderColor = CardBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            cursorColor = AccentGreen
                        ),
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    )
                    Spacer(Modifier.height(14.dp))
                    SectionHeader(stringResource(R.string.automation_section_when))

                    // AND/OR toggle
                    Row {
                        LogicChip(stringResource(R.string.automation_logic_and), editing.triggerLogic == "AND") {
                            onUpdate { copy(triggerLogic = "AND") }
                        }
                        LogicChip(stringResource(R.string.automation_logic_or), editing.triggerLogic == "OR") {
                            onUpdate { copy(triggerLogic = "OR") }
                        }
                    }
                    Spacer(Modifier.height(8.dp))

                    editing.triggers.forEachIndexed { idx, trigger ->
                        TriggerRow(
                            index = idx,
                            trigger = trigger,
                            places = places,
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
                            }
                        )
                        Spacer(Modifier.height(4.dp))
                    }

                    if (editing.triggers.size < 5) {
                        AddTriggerButton(
                            places = places,
                            onAddParam = {
                                val p = TRIGGER_PARAMS.first()
                                onUpdate {
                                    copy(triggers = triggers + TriggerDef(p.param, p.chineseName, ">", "0", p.localizedName(context)))
                                }
                            },
                            onAddPlace = { place ->
                                onUpdate { copy(triggers = triggers + newPlaceTrigger(place, context)) }
                            },
                            onAddTimeOfDay = {
                                onUpdate { copy(triggers = triggers + newTimeOfDayTrigger(context)) }
                            },
                            onAddSchedule = {
                                onUpdate { copy(triggers = triggers + newScheduleTrigger()) }
                            },
                            onAddServiceStart = {
                                onUpdate { copy(triggers = triggers + newServiceStartTrigger(context)) }
                            },
                            onAddNetworkAvailable = {
                                onUpdate { copy(triggers = triggers + newNetworkAvailableTrigger(context)) }
                            }
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
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp, 12.dp)
                ) {
                    SectionHeader(stringResource(R.string.automation_section_then))

                    editing.actions.forEachIndexed { idx, action ->
                        ActionRow(
                            index = idx,
                            action = action,
                            places = places,
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
                            }
                        )
                        Spacer(Modifier.height(4.dp))
                    }

                    if (editing.actions.size < 10) {
                        AddActionButton(
                            onAddParam = {
                                val a = ACTION_COMMANDS.first()
                                onUpdate { copy(actions = actions + ActionDef(a.command, a.localizedName(context))) }
                            },
                            onAddNotification = { silent ->
                                onUpdate { copy(actions = actions + newNotificationAction(silent, context)) }
                            },
                            onAddAppLaunch = {
                                onUpdate { copy(actions = actions + newAppLaunchAction(context)) }
                            },
                            onAddCall = {
                                onUpdate { copy(actions = actions + newCallAction(context)) }
                            },
                            onAddNavigate = {
                                onUpdate { copy(actions = actions + newNavigateAction(context)) }
                            },
                            onAddUrl = {
                                onUpdate { copy(actions = actions + newUrlAction(context)) }
                            },
                            onAddYandexMusic = {
                                onUpdate { copy(actions = actions + newYandexMusicAction(context)) }
                            },
                            onAddDelay = {
                                onUpdate { copy(actions = actions + newDelayAction(context)) }
                            },
                            onAddMediaVolume = {
                                onUpdate { copy(actions = actions + newMediaVolumeAction()) }
                            }
                        )
                    }

                    Spacer(Modifier.height(20.dp))
                    SectionHeader(stringResource(R.string.automation_section_settings))

                    // Cooldown
                    SettingRow(stringResource(R.string.automation_setting_cooldown)) {
                        OutlinedTextField(
                            value = editing.cooldownSeconds.toString(),
                            onValueChange = { v ->
                                v.toIntOrNull()?.let { sec -> onUpdate { copy(cooldownSeconds = sec) } }
                            },
                            modifier = Modifier.width(70.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentGreen, unfocusedBorderColor = CardBorder,
                                focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = AccentGreen
                            ),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.automation_setting_unit_sec), fontSize = 12.sp, color = TextMuted)
                    }
                    Spacer(Modifier.height(4.dp))

                    // Require park
                    SettingRow(stringResource(R.string.automation_setting_park_only)) {
                        Switch(
                            checked = editing.requirePark,
                            onCheckedChange = { v -> onUpdate { copy(requirePark = v) } },
                            colors = bydSwitchColors(),
                        )
                    }

                    // Confirm before execute
                    SettingRow(stringResource(R.string.automation_setting_confirm_before)) {
                        Switch(
                            checked = editing.confirmBeforeExecute,
                            onCheckedChange = { v -> onUpdate { copy(confirmBeforeExecute = v) } },
                            colors = bydSwitchColors(),
                        )
                    }

                    // Fire once per trip
                    SettingRow(stringResource(R.string.automation_setting_once_per_trip)) {
                        Switch(
                            checked = editing.fireOncePerTrip,
                            onCheckedChange = { v -> onUpdate { copy(fireOncePerTrip = v) } },
                            colors = bydSwitchColors(),
                        )
                    }
                }
            }

            // Footer
            HorizontalDivider(color = CardBorder)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp, 10.dp)
            ) {
                editorError?.let { err ->
                    Text(
                        text = err,
                        color = Color(0xFFEF4444),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.border(1.5.dp, CardBorder, RoundedCornerShape(8.dp))
                    ) { Text(stringResource(R.string.automation_cancel_button)) }
                    Spacer(Modifier.width(10.dp))
                    Button(
                        onClick = onSave,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = NavyDark),
                        shape = RoundedCornerShape(8.dp),
                        enabled = editing.name.isNotBlank() && editing.triggers.isNotEmpty() && editing.actions.isNotEmpty()
                    ) { Text(stringResource(R.string.automation_save_button), fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}

// --- Trigger Row ---

/** Up/down reorder arrows shared by trigger and action rows. A null callback = boundary, shown dimmed and disabled. */
@Composable
private fun ReorderArrows(onMoveUp: (() -> Unit)?, onMoveDown: (() -> Unit)?) {
    IconButton(onClick = { onMoveUp?.invoke() }, enabled = onMoveUp != null, modifier = Modifier.size(24.dp)) {
        Icon(Icons.Outlined.KeyboardArrowUp, "выше",
            tint = TextSecondary.copy(alpha = if (onMoveUp != null) 1f else 0.25f), modifier = Modifier.size(16.dp))
    }
    IconButton(onClick = { onMoveDown?.invoke() }, enabled = onMoveDown != null, modifier = Modifier.size(24.dp)) {
        Icon(Icons.Outlined.KeyboardArrowDown, "ниже",
            tint = TextSecondary.copy(alpha = if (onMoveDown != null) 1f else 0.25f), modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun TriggerRow(
    index: Int,
    trigger: TriggerDef,
    places: List<PlaceEntity>,
    onUpdate: (TriggerDef) -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardSurface, RoundedCornerShape(8.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(8.dp))
            .padding(8.dp, 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("${index + 1}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextMuted,
            modifier = Modifier.width(16.dp))

        when (trigger.kind) {
            "place_enter", "place_exit" -> PlaceTriggerControls(trigger, places, onUpdate)
            "time_of_day" -> TimeOfDayTriggerControls(trigger, onUpdate)
            "time_range" -> ScheduleTriggerControls(trigger, onUpdate)
            "service_start" -> ServiceStartTriggerControls()
            "network_available" -> NetworkAvailableTriggerControls()
            else -> ParamTriggerControls(trigger, onUpdate)
        }

        Spacer(Modifier.weight(1f))

        ReorderArrows(onMoveUp = onMoveUp, onMoveDown = onMoveDown)
        IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
            Icon(Icons.Outlined.Close, "delete", tint = Color(0xFFEF4444).copy(alpha = 0.5f), modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun ParamTriggerControls(
    trigger: TriggerDef,
    onUpdate: (TriggerDef) -> Unit
) {
    val context = LocalContext.current
    // Param dropdown
    CatalogDropdown(
        selected = TRIGGER_PARAMS.find { it.param == trigger.param }?.localizedName(context) ?: trigger.param,
        items = TRIGGER_PARAMS.map { it.localizedName(context) },
        categories = TRIGGER_PARAMS.map { it.localizedCategory(context) },
        modifier = Modifier.width(150.dp),
        onSelect = { idx ->
            val p = TRIGGER_PARAMS[idx]
            onUpdate(trigger.copy(param = p.param, chineseName = p.chineseName,
                displayName = "${p.localizedName(context)} ${trigger.operator} ${trigger.value}"))
        }
    )
    Spacer(Modifier.width(4.dp))

    // Operator dropdown
    var opExpanded by remember { mutableStateOf(false) }
    Box {
        Text(
            trigger.operator,
            fontSize = 13.sp, color = AccentOrange, fontWeight = FontWeight.Bold,
            modifier = Modifier
                .background(CardSurface, RoundedCornerShape(6.dp))
                .border(1.dp, CardBorder, RoundedCornerShape(6.dp))
                .clickable { opExpanded = true }
                .padding(8.dp, 6.dp)
                .width(30.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        DropdownMenu(expanded = opExpanded, onDismissRequest = { opExpanded = false }) {
            OPERATORS.forEach { op ->
                DropdownMenuItem(
                    text = { Text(op, fontWeight = FontWeight.Bold) },
                    onClick = {
                        opExpanded = false
                        onUpdate(trigger.copy(operator = op))
                    }
                )
            }
        }
    }
    Spacer(Modifier.width(4.dp))

    // Value: enum dropdown or text input with unit
    val paramOption = TRIGGER_PARAMS.find { it.param == trigger.param }
    if (paramOption?.enumValues != null) {
        // Enum dropdown
        var enumExpanded by remember { mutableStateOf(false) }
        val enumLabel = paramOption.enumValues.find { it.first == trigger.value }?.second ?: trigger.value
        Box {
            Text(
                enumLabel,
                fontSize = 13.sp, color = AccentGreen,
                modifier = Modifier
                    .background(CardSurface, RoundedCornerShape(6.dp))
                    .border(1.dp, CardBorder, RoundedCornerShape(6.dp))
                    .clickable { enumExpanded = true }
                    .padding(8.dp, 6.dp)
            )
            DropdownMenu(expanded = enumExpanded, onDismissRequest = { enumExpanded = false }) {
                paramOption.enumValues.forEach { (value, label) ->
                    DropdownMenuItem(
                        text = { Text(label, fontSize = 13.sp) },
                        onClick = {
                            enumExpanded = false
                            onUpdate(trigger.copy(value = value, operator = "=="))
                        }
                    )
                }
            }
        }
    } else {
        // Numeric input
        OutlinedTextField(
            value = trigger.value,
            onValueChange = { v -> onUpdate(trigger.copy(value = v)) },
            modifier = Modifier.width(70.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AccentGreen, unfocusedBorderColor = CardBorder,
                focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = AccentGreen
            ),
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        )
        if (paramOption?.unit?.isNotEmpty() == true) {
            Spacer(Modifier.width(4.dp))
            Text(paramOption.unit, fontSize = 12.sp, color = TextMuted)
        }
    }
}

@Composable
private fun PlaceTriggerControls(
    trigger: TriggerDef,
    places: List<PlaceEntity>,
    onUpdate: (TriggerDef) -> Unit
) {
    val placeExists = places.any { it.id == trigger.placeId }
    val isStale = trigger.placeId != null && !placeExists

    Icon(
        Icons.Outlined.Place,
        contentDescription = null,
        tint = TextMuted,
        modifier = Modifier.size(16.dp)
    )
    Spacer(Modifier.width(4.dp))

    if (isStale || (trigger.placeId == null && places.isEmpty())) {
        // Show warning when the referenced place was deleted
        Text(
            stringResource(R.string.automation_trigger_place_deleted),
            fontSize = 13.sp,
            color = Color(0xFFEF4444),
            modifier = Modifier
                .background(Color(0xFFEF4444).copy(alpha = 0.08f), RoundedCornerShape(6.dp))
                .border(1.dp, Color(0xFFEF4444).copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                .padding(8.dp, 6.dp)
        )
    } else {
        // Place-name dropdown
        var placeExpanded by remember { mutableStateOf(false) }
        val deletedPlaceholder = stringResource(R.string.automation_trigger_place_deleted_placeholder)
        Box(modifier = Modifier.width(150.dp)) {
            Text(
                trigger.placeName ?: deletedPlaceholder,
                fontSize = 13.sp, color = TextPrimary,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CardSurface, RoundedCornerShape(6.dp))
                    .border(1.dp, CardBorder, RoundedCornerShape(6.dp))
                    .clickable { placeExpanded = true }
                    .padding(8.dp, 7.dp),
                maxLines = 1
            )
            val enterPrefix = stringResource(R.string.automation_trigger_place_enter_prefix)
            val exitPrefix = stringResource(R.string.automation_trigger_place_exit_prefix)
            DropdownMenu(expanded = placeExpanded, onDismissRequest = { placeExpanded = false }) {
                places.forEach { place ->
                    DropdownMenuItem(
                        text = { Text(place.name, fontSize = 13.sp) },
                        onClick = {
                            placeExpanded = false
                            val kindLabel = if (trigger.kind == "place_enter") enterPrefix else exitPrefix
                            onUpdate(trigger.copy(
                                placeId = place.id,
                                placeName = place.name,
                                displayName = "$kindLabel «${place.name}»"
                            ))
                        }
                    )
                }
            }
        }
    }
    Spacer(Modifier.width(4.dp))

    // Kind toggle pill: Въезд / Выезд
    val isEnter = trigger.kind == "place_enter"
    val enterLabel = stringResource(R.string.automation_trigger_place_enter_label)
    val exitLabel = stringResource(R.string.automation_trigger_place_exit_label)
    val enterPrefix2 = stringResource(R.string.automation_trigger_place_enter_prefix)
    val exitPrefix2 = stringResource(R.string.automation_trigger_place_exit_prefix)
    val label = if (isEnter) enterLabel else exitLabel
    Box(
        modifier = Modifier
            .background(CardSurface, RoundedCornerShape(6.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(6.dp))
            .clickable {
                val newKind = if (isEnter) "place_exit" else "place_enter"
                val kindLabel = if (newKind == "place_enter") enterPrefix2 else exitPrefix2
                onUpdate(trigger.copy(
                    kind = newKind,
                    displayName = "$kindLabel «${trigger.placeName ?: "?"}»"
                ))
            }
            .padding(8.dp, 6.dp)
    ) {
        Text(label, color = AccentGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TimeOfDayTriggerControls(
    trigger: TriggerDef,
    onUpdate: (TriggerDef) -> Unit
) {
    val dayLabel = stringResource(R.string.automation_trigger_day)
    val nightLabel = stringResource(R.string.automation_trigger_night)
    val dawnLabel = stringResource(R.string.automation_trigger_dawn)
    val duskLabel = stringResource(R.string.automation_trigger_dusk)
    val phases = listOf("DAY" to dayLabel, "NIGHT" to nightLabel, "DAWN" to dawnLabel, "DUSK" to duskLabel)
    val current = phases.find { it.first == trigger.value.uppercase() } ?: phases[1]
    var expanded by remember { mutableStateOf(false) }

    Text(stringResource(R.string.automation_trigger_time_of_day_label), fontSize = 12.sp, color = TextMuted)
    Spacer(Modifier.width(4.dp))
    Box {
        Text(
            current.second,
            fontSize = 13.sp,
            color = AccentGreen,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .background(CardSurface, RoundedCornerShape(6.dp))
                .border(1.dp, CardBorder, RoundedCornerShape(6.dp))
                .clickable { expanded = true }
                .padding(8.dp, 6.dp)
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            phases.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label, fontSize = 13.sp) },
                    onClick = {
                        expanded = false
                        onUpdate(trigger.copy(value = value, displayName = label))
                    }
                )
            }
        }
    }
}

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
        // Mode toggle: exact vs range
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = isExact,
                onClick = { if (!isExact) push(spec.copy(toMinute = spec.fromMinute)) },
                label = { Text(stringResource(R.string.automation_schedule_mode_exact), fontSize = 12.sp) },
            )
            Spacer(Modifier.width(6.dp))
            FilterChip(
                selected = !isExact,
                onClick = {
                    // Open a 2h window so "from" and "to" differ when switching to range.
                    if (isExact) push(spec.copy(toMinute = (spec.fromMinute + 120) % 1440))
                },
                label = { Text(stringResource(R.string.automation_schedule_mode_range), fontSize = 12.sp) },
            )
        }
        Spacer(Modifier.height(6.dp))
        // Time picker(s)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isExact) {
                Text(stringResource(R.string.automation_schedule_time_label) + " ", fontSize = 12.sp, color = TextMuted)
                TimeChip(minuteToHHmm(spec.fromMinute)) {
                    pickTime(spec.fromMinute) { push(spec.copy(fromMinute = it, toMinute = it)) }
                }
            } else {
                Text(stringResource(R.string.automation_schedule_from) + " ", fontSize = 12.sp, color = TextMuted)
                TimeChip(minuteToHHmm(spec.fromMinute)) {
                    pickTime(spec.fromMinute) { push(spec.copy(fromMinute = it)) }
                }
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.automation_schedule_to) + " ", fontSize = 12.sp, color = TextMuted)
                TimeChip(minuteToHHmm(spec.toMinute)) {
                    pickTime(spec.toMinute) { push(spec.copy(toMinute = it)) }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        // Days of week (none selected = every day)
        Text(stringResource(R.string.automation_schedule_days_label), fontSize = 11.sp, color = TextMuted)
        Spacer(Modifier.height(2.dp))
        Row {
            dayLabels.forEach { (d, label) ->
                DayChip(
                    label = label,
                    selected = d in spec.days,
                    onClick = {
                        val newDays = if (d in spec.days) spec.days - d else spec.days + d
                        push(spec.copy(days = newDays))
                    },
                )
                Spacer(Modifier.width(3.dp))
            }
        }
    }
}

@Composable
private fun TimeChip(text: String, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 13.sp,
        color = AccentGreen,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .background(CardSurface, RoundedCornerShape(6.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(8.dp, 6.dp)
    )
}

@Composable
private fun DayChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        color = if (selected) AccentGreen else TextMuted,
        modifier = Modifier
            .background(if (selected) AccentGreen.copy(alpha = 0.15f) else CardSurface, RoundedCornerShape(6.dp))
            .border(1.dp, if (selected) AccentGreen else CardBorder, RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 5.dp)
    )
}

/** Readable schedule label; first token is the time so the rule summary stays compact. */
private fun scheduleDisplayName(context: android.content.Context, spec: ScheduleSpec): String {
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
private fun ServiceStartTriggerControls() {
    Icon(
        Icons.Outlined.PlayArrow,
        contentDescription = null,
        tint = AccentGreen,
        modifier = Modifier.size(16.dp)
    )
    Spacer(Modifier.width(6.dp))
    Text(
        stringResource(R.string.automation_trigger_service_start),
        fontSize = 13.sp,
        color = AccentGreen,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun NetworkAvailableTriggerControls() {
    Icon(
        Icons.Outlined.Wifi,
        contentDescription = null,
        tint = AccentGreen,
        modifier = Modifier.size(16.dp)
    )
    Spacer(Modifier.width(6.dp))
    Text(
        stringResource(R.string.automation_trigger_internet),
        fontSize = 13.sp,
        color = AccentGreen,
        fontWeight = FontWeight.Bold
    )
}

// --- Action Row ---

@Composable
private fun ActionRow(
    index: Int,
    action: ActionDef,
    places: List<PlaceEntity>,
    onUpdate: (ActionDef) -> Unit,
    onTest: (String) -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardSurface, RoundedCornerShape(8.dp))
            .border(1.dp, AccentTeal.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
            .padding(8.dp, 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("${index + 1}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AccentTeal,
            modifier = Modifier.width(16.dp))

        when (action.kind) {
            "notification_silent", "notification_sound" ->
                NotificationActionControls(action = action, onUpdate = onUpdate, modifier = Modifier.weight(1f))
            "app_launch" ->
                AppLaunchActionControls(action = action, onUpdate = onUpdate, modifier = Modifier.weight(1f))
            "call" ->
                CallActionControls(action = action, onUpdate = onUpdate, modifier = Modifier.weight(1f))
            "navigate" ->
                NavigateActionControls(action = action, places = places, onUpdate = onUpdate, modifier = Modifier.weight(1f))
            "url" ->
                UrlActionControls(action = action, onUpdate = onUpdate, modifier = Modifier.weight(1f))
            "yandex_music" ->
                YandexMusicActionControls(action = action, onUpdate = onUpdate, modifier = Modifier.weight(1f))
            "delay" ->
                DelayActionControls(action = action, onUpdate = onUpdate, modifier = Modifier.weight(1f))
            "media_volume" ->
                MediaVolumeActionControls(action = action, onUpdate = onUpdate, modifier = Modifier.weight(1f))
            else -> // "param" (default)
                ParamActionControls(action = action, onUpdate = onUpdate, modifier = Modifier.weight(1f))
        }

        // "Выполнить сейчас" — fire this vehicle command immediately for live testing.
        // Only for param (vehicle) actions; result is shown via Toast + logcat status line.
        if (action.kind == "param" && action.command.isNotBlank()) {
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = { onTest(action.command) }, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Outlined.PlayArrow, "выполнить сейчас", tint = AccentGreen, modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.width(4.dp))
        ReorderArrows(onMoveUp = onMoveUp, onMoveDown = onMoveDown)
        IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
            Icon(Icons.Outlined.Close, "delete", tint = Color(0xFFEF4444).copy(alpha = 0.5f), modifier = Modifier.size(14.dp))
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
        onSelect = { idx ->
            val a = ACTION_COMMANDS[idx]
            onUpdate(ActionDef(a.command, a.localizedName(context)))
        }
    )
}

// Delay option keys — labels are resolved at runtime via stringResource
private val DELAY_OPTION_MS = listOf(500L, 1000L, 2000L, 3000L, 5000L, 10000L)

@Composable
private fun DelayActionControls(
    action: ActionDef,
    onUpdate: (ActionDef) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentMs = action.payload?.toLongOrNull() ?: 1000L
    val label0_5s = stringResource(R.string.automation_delay_0_5s)
    val label1s = stringResource(R.string.automation_delay_1s)
    val label2s = stringResource(R.string.automation_delay_2s)
    val label3s = stringResource(R.string.automation_delay_3s)
    val label5s = stringResource(R.string.automation_delay_5s)
    val label10s = stringResource(R.string.automation_delay_10s)
    val delayLabels = listOf(
        500L to label0_5s,
        1000L to label1s,
        2000L to label2s,
        3000L to label3s,
        5000L to label5s,
        10000L to label10s
    )
    // Pre-build display names for onClick lambdas (stringResource cannot be called in non-Composable onClick)
    val delayDisplayNames = delayLabels.associate { (ms, lbl) ->
        ms to stringResource(R.string.automation_delay_display_name, lbl)
    }
    val currentLabel = delayLabels.find { it.first == currentMs }?.second ?: "${currentMs}ms"
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Outlined.Pause,
            contentDescription = null,
            tint = AccentTeal,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.automation_action_delay_label), fontSize = 13.sp, color = TextMuted)
        Spacer(Modifier.width(6.dp))
        Box {
            Text(
                currentLabel,
                fontSize = 13.sp,
                color = AccentTeal,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .background(CardSurface, RoundedCornerShape(6.dp))
                    .border(1.dp, CardBorder, RoundedCornerShape(6.dp))
                    .clickable { expanded = true }
                    .padding(8.dp, 6.dp)
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                delayLabels.forEach { (ms, label) ->
                    DropdownMenuItem(
                        text = { Text(label, fontSize = 13.sp) },
                        onClick = {
                            expanded = false
                            onUpdate(action.copy(
                                payload = ms.toString(),
                                displayName = delayDisplayNames[ms] ?: label
                            ))
                        }
                    )
                }
            }
        }
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
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 13.sp, color = TextMuted)
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
            fontSize = 13.sp,
            color = AccentTeal,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(40.dp)
        )
    }
}

// --- Catalog Dropdown (with category headers) ---

@Composable
private fun CatalogDropdown(
    selected: String,
    items: List<String>,
    categories: List<String>,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Text(
            selected,
            fontSize = 13.sp, color = TextPrimary,
            modifier = Modifier
                .fillMaxWidth()
                .background(CardSurface, RoundedCornerShape(6.dp))
                .border(1.dp, CardBorder, RoundedCornerShape(6.dp))
                .clickable { expanded = true }
                .padding(8.dp, 7.dp),
            maxLines = 1
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.fillMaxHeight(0.5f)
        ) {
            var lastCat = ""
            items.forEachIndexed { idx, item ->
                val cat = categories[idx]
                if (cat != lastCat) {
                    lastCat = cat
                    DropdownMenuItem(
                        text = { Text(cat, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextMuted) },
                        onClick = {},
                        enabled = false
                    )
                }
                DropdownMenuItem(
                    text = { Text(item, fontSize = 13.sp) },
                    onClick = { expanded = false; onSelect(idx) }
                )
            }
        }
    }
}

// --- Journal Dialog ---

@Composable
private fun JournalDialog(logs: List<RuleLogEntity>, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val context = LocalContext.current
        Column(
            modifier = Modifier
                .fillMaxWidth(0.4f)
                .fillMaxHeight(0.75f)
                .background(NavyDeep, RoundedCornerShape(16.dp))
                .border(1.5.dp, CardBorder, RoundedCornerShape(16.dp))
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp, 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.automation_journal_title), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Outlined.Close, "close", tint = TextMuted)
                }
            }
            HorizontalDivider(color = CardBorder)

            if (logs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.automation_journal_empty), color = TextMuted, fontSize = 14.sp)
                }
            } else {
                LazyColumn(modifier = Modifier.padding(8.dp)) {
                    items(logs, key = { it.id }) { log ->
                        LogItem(log)
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun LogItem(log: RuleLogEntity) {
    val context = LocalContext.current
    val borderColor = if (log.success) AccentGreen else Color(0xFFEF4444)
    val bgColor = if (log.success) AccentGreen.copy(alpha = 0.05f) else Color(0xFFEF4444).copy(alpha = 0.05f)

    val actionsText = remember(log.actionsResult) {
        try {
            val arr = JSONArray(log.actionsResult)
            (0 until arr.length()).joinToString(", ") {
                val obj = arr.getJSONObject(it)
                obj.optString("displayName", obj.optString("command", ""))
            }
        } catch (_: Exception) { log.actionsResult }
    }

    val reasonText = remember(log.actionsResult) {
        try {
            val arr = JSONArray(log.actionsResult)
            (0 until arr.length()).mapNotNull {
                val obj = arr.getJSONObject(it)
                obj.optString("reason", "").ifEmpty { null }
            }.firstOrNull()
        } catch (_: Exception) { null }
    }

    val snapshotText = remember(log.triggersSnapshot) {
        try {
            val obj = JSONObject(log.triggersSnapshot)
            obj.keys().asSequence().joinToString(" · ") { key ->
                val paramName = TRIGGER_PARAMS.find { it.param == key }?.localizedName(context) ?: key
                "$paramName: ${obj.get(key)}"
            }
        } catch (_: Exception) { "" }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
    ) {
        // Left color stripe
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(borderColor)
        )
        // Content
        Column(modifier = Modifier.weight(1f).padding(8.dp, 6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(log.ruleName, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                Text(
                    formatRelativeTime(log.triggeredAt, LocalContext.current),
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = TextMuted
                )
            }
            Text("→ $actionsText", fontSize = 12.sp, color = AccentTeal)
            if (reasonText != null) {
                Text(reasonText, fontSize = 11.sp, color = AccentOrange)
            }
            if (snapshotText.isNotEmpty()) {
                Text(snapshotText, fontSize = 11.sp, color = TextSecondary)
            }
        }
    }
}

// --- Shared Composables ---

@Composable
private fun AutoChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium) },
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
private fun LogicChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(
                if (selected) AccentGreen else CardSurface,
                RoundedCornerShape(6.dp)
            )
            .border(if (selected) 0.dp else 1.dp, CardBorder, RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(10.dp, 5.dp)
    ) {
        Text(
            label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            color = if (selected) NavyDark else TextSecondary
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextMuted,
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
        Text(label, fontSize = 13.sp, color = TextSecondary)
        Row(verticalAlignment = Alignment.CenterVertically) { content() }
    }
}

@Composable
private fun AddActionButton(
    onAddParam: () -> Unit,
    onAddNotification: (silent: Boolean) -> Unit,
    onAddAppLaunch: () -> Unit,
    onAddCall: () -> Unit,
    onAddNavigate: () -> Unit,
    onAddUrl: () -> Unit,
    onAddYandexMusic: () -> Unit,
    onAddDelay: () -> Unit,
    onAddMediaVolume: () -> Unit
) {
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }
    var showOverlayPrompt by remember { mutableStateOf(false) }

    Box {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.5.dp, CardBorder, RoundedCornerShape(8.dp))
                .clickable { menuExpanded = true }
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(stringResource(R.string.automation_add_action_button), fontSize = 12.sp, color = TextMuted)
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.automation_action_dplus_command), fontSize = 13.sp) },
                onClick = { menuExpanded = false; onAddParam() }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.automation_action_notification_silent), fontSize = 13.sp) },
                onClick = { menuExpanded = false; onAddNotification(true) }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.automation_action_notification_sound), fontSize = 13.sp) },
                onClick = {
                    menuExpanded = false
                    onAddNotification(false)
                    if (!android.provider.Settings.canDrawOverlays(context)) {
                        showOverlayPrompt = true
                    }
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.automation_action_app_launch), fontSize = 13.sp) },
                onClick = { menuExpanded = false; onAddAppLaunch() }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.automation_action_yandex_music), fontSize = 13.sp) },
                onClick = { menuExpanded = false; onAddYandexMusic() }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.automation_action_call), fontSize = 13.sp) },
                onClick = { menuExpanded = false; onAddCall() }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.automation_action_navigate), fontSize = 13.sp) },
                onClick = { menuExpanded = false; onAddNavigate() }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.automation_action_url), fontSize = 13.sp) },
                onClick = { menuExpanded = false; onAddUrl() }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.automation_action_delay), fontSize = 13.sp) },
                onClick = { menuExpanded = false; onAddDelay() }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.automation_action_media_volume), fontSize = 13.sp) },
                onClick = { menuExpanded = false; onAddMediaVolume() }
            )
        }
    }

    if (showOverlayPrompt) {
        AlertDialog(
            onDismissRequest = { showOverlayPrompt = false },
            containerColor = CardSurface,
            title = { Text(stringResource(R.string.automation_overlay_permission_title), color = TextPrimary, fontSize = 16.sp) },
            text = {
                Text(
                    stringResource(R.string.automation_overlay_permission_text),
                    fontSize = 13.sp,
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
                    Text(stringResource(R.string.automation_overlay_open_settings), color = AccentGreen)
                }
            },
            dismissButton = {
                TextButton(onClick = { showOverlayPrompt = false }) {
                    Text(stringResource(R.string.automation_overlay_later), color = TextSecondary)
                }
            }
        )
    }
}

@Composable
private fun NotificationActionControls(
    action: ActionDef,
    onUpdate: (ActionDef) -> Unit,
    modifier: Modifier = Modifier
) {
    var editing by remember { mutableStateOf(false) }
    val silent = action.kind == "notification_silent"
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
            .padding(8.dp, 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (silent) Icons.Outlined.NotificationsOff else Icons.Outlined.Notifications,
            contentDescription = null,
            tint = AccentTeal,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = preview,
            fontSize = 13.sp,
            color = if (title.isBlank() && text.isBlank()) TextMuted else TextPrimary,
            maxLines = 1
        )
    }

    if (editing) {
        NotificationEditDialog(
            initialTitle = title,
            initialText = text,
            silent = silent,
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
    silent: Boolean,
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

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardSurface,
        title = {
            Text(
                text = if (silent) stringResource(R.string.automation_action_notification_silent) else stringResource(R.string.automation_action_notification_sound),
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
            .padding(8.dp, 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.Apps,
            contentDescription = null,
            tint = AccentTeal,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = preview,
            fontSize = 13.sp,
            color = if (label.isBlank() && pkg.isBlank()) TextMuted else TextPrimary,
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
            .padding(8.dp, 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.Call,
            contentDescription = null,
            tint = AccentTeal,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = preview,
            fontSize = 13.sp,
            color = if (phone.isBlank()) TextMuted else TextPrimary,
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
private fun CallEditDialog(
    initialPhone: String,
    initialName: String,
    initialAutoDial: Boolean,
    onDismiss: () -> Unit,
    onSave: (phone: String, name: String, autoDial: Boolean) -> Unit
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

    AlertDialog(
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
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
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
                        Text(stringResource(R.string.automation_call_auto_dial_label), color = TextPrimary, fontSize = 13.sp)
                        Text(
                            stringResource(R.string.automation_call_auto_dial_hint),
                            color = TextMuted,
                            fontSize = 11.sp
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
            .padding(8.dp, 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.Navigation,
            contentDescription = null,
            tint = AccentTeal,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = preview,
            fontSize = 13.sp,
            color = if (name.isBlank()) TextMuted else TextPrimary,
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

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardSurface,
        title = { Text(stringResource(R.string.automation_navigate_dialog_title), color = TextPrimary, fontSize = 16.sp) },
        text = {
            if (places.isEmpty()) {
                Text(
                    text = stringResource(R.string.automation_navigate_no_places_hint),
                    fontSize = 13.sp,
                    color = TextMuted
                )
            } else {
                Column {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = selectedPlace?.name ?: stringResource(R.string.automation_navigate_pick_place_placeholder),
                            fontSize = 13.sp,
                            color = if (selectedPlace == null) TextMuted else TextPrimary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(CardSurface, RoundedCornerShape(8.dp))
                                .border(1.dp, CardBorder, RoundedCornerShape(8.dp))
                                .clickable { placeExpanded = true }
                                .padding(12.dp, 10.dp),
                            maxLines = 1
                        )
                        DropdownMenu(expanded = placeExpanded, onDismissRequest = { placeExpanded = false }) {
                            places.forEach { place ->
                                DropdownMenuItem(
                                    text = { Text(place.name, fontSize = 13.sp) },
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
            .padding(8.dp, 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.Link,
            contentDescription = null,
            tint = AccentTeal,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = preview,
            fontSize = 13.sp,
            color = if (url.isBlank()) TextMuted else TextPrimary,
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

    AlertDialog(
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
                        fontSize = 13.sp,
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
            .padding(8.dp, 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = preview,
            fontSize = 13.sp,
            color = if (mode.isBlank()) TextMuted else TextPrimary,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        if (minimize) {
            Spacer(Modifier.width(6.dp))
            Text("↓", fontSize = 13.sp, color = TextMuted)
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

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardSurface,
        title = { Text(stringResource(R.string.automation_music_dialog_title), color = TextPrimary, fontSize = 16.sp) },
        text = {
            Column {
                Text(stringResource(R.string.automation_music_what_to_play), fontSize = 12.sp, color = TextMuted)
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
                        modes.forEach { (value, label) ->
                            DropdownMenuItem(
                                text = { Text(label, fontSize = 13.sp) },
                                onClick = {
                                    selectedMode = value
                                    dropdownExpanded = false
                                }
                            )
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
                        fontSize = 13.sp,
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

@Composable
private fun AddTriggerButton(
    places: List<PlaceEntity>,
    onAddParam: () -> Unit,
    onAddPlace: (PlaceEntity) -> Unit,
    onAddTimeOfDay: () -> Unit,
    onAddSchedule: () -> Unit,
    onAddServiceStart: () -> Unit,
    onAddNetworkAvailable: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Box {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.5.dp, CardBorder, RoundedCornerShape(8.dp))
                .clickable { menuExpanded = true }
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(stringResource(R.string.automation_add_condition_button), fontSize = 12.sp, color = TextMuted)
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.automation_trigger_type_param), fontSize = 13.sp) },
                onClick = { menuExpanded = false; onAddParam() }
            )
            val firstPlace = places.firstOrNull()
            DropdownMenuItem(
                text = {
                    if (firstPlace != null) {
                        Text(stringResource(R.string.automation_trigger_type_place), fontSize = 13.sp)
                    } else {
                        Column {
                            Text(stringResource(R.string.automation_trigger_type_place), fontSize = 13.sp, color = TextSecondary)
                            Text(stringResource(R.string.automation_trigger_type_place_empty_hint), fontSize = 11.sp, color = TextMuted)
                        }
                    }
                },
                onClick = {
                    if (firstPlace != null) {
                        menuExpanded = false
                        onAddPlace(firstPlace)
                    }
                },
                enabled = firstPlace != null
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.automation_trigger_type_time_of_day), fontSize = 13.sp) },
                onClick = {
                    menuExpanded = false
                    onAddTimeOfDay()
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.automation_trigger_type_schedule), fontSize = 13.sp) },
                onClick = {
                    menuExpanded = false
                    onAddSchedule()
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.automation_trigger_type_service_start), fontSize = 13.sp) },
                onClick = {
                    menuExpanded = false
                    onAddServiceStart()
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.automation_trigger_type_internet), fontSize = 13.sp) },
                onClick = {
                    menuExpanded = false
                    onAddNetworkAvailable()
                }
            )
        }
    }
}

private fun newPlaceTrigger(place: PlaceEntity, context: android.content.Context): TriggerDef {
    return TriggerDef(
        param = "Place",
        chineseName = "位置",
        operator = "==",
        value = "enter",
        displayName = localized("进入「${place.name}」", "Enter «${place.name}»", "Въезд в «${place.name}»", context),
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
        displayName = localized("BYDMate 启动", "BYDMate startup", "Запуск BYDMate", context),
        kind = "service_start"
    )
}

private fun newNetworkAvailableTrigger(context: android.content.Context): TriggerDef {
    return TriggerDef(
        param = "NetworkAvailable",
        chineseName = "网络可用",
        operator = "==",
        value = "true",
        displayName = localized("网络可用", "Internet available", "Доступен интернет", context),
        kind = "network_available"
    )
}

private fun newDelayAction(context: android.content.Context): ActionDef = ActionDef(
    command = "delay_1000",
    displayName = localized("延迟 1 秒", "Delay 1 sec", "Пауза 1 сек", context),
    kind = "delay",
    payload = "1000"
)

private fun newMediaVolumeAction(): ActionDef = ActionDef(
    command = "media_volume",
    displayName = "Громкость медиа: 2",
    kind = "media_volume",
    payload = "2"
)

private fun newTimeOfDayTrigger(context: android.content.Context): TriggerDef {
    return TriggerDef(
        param = "TimeOfDay",
        chineseName = "时间段",
        operator = "==",
        value = "NIGHT",
        displayName = localized("夜晚", "Night", "Ночь", context),
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

// --- Helpers ---

private fun formatRelativeTime(ts: Long, context: android.content.Context): String {
    val now = System.currentTimeMillis()
    val diff = now - ts
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    val dateSdf = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())

    return when {
        diff < 24 * 60 * 60 * 1000L -> sdf.format(Date(ts))
        diff < 48 * 60 * 60 * 1000L -> context.getString(R.string.automation_time_yesterday, sdf.format(Date(ts)))
        else -> dateSdf.format(Date(ts))
    }
}
