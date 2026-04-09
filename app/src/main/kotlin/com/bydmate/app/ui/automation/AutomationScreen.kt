package com.bydmate.app.ui.automation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.RuleLogEntity
import com.bydmate.app.data.local.entity.TriggerDef
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
            Text("Автоматизация", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Spacer(Modifier.width(16.dp))
            AutoChip("Все", state.filter == RuleFilter.ALL) { viewModel.setFilter(RuleFilter.ALL) }
            Spacer(Modifier.width(4.dp))
            AutoChip("Активные", state.filter == RuleFilter.ENABLED) { viewModel.setFilter(RuleFilter.ENABLED) }
            Spacer(Modifier.width(4.dp))
            AutoChip("Выключенные", state.filter == RuleFilter.DISABLED) { viewModel.setFilter(RuleFilter.DISABLED) }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { viewModel.showJournal() },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.border(1.5.dp, CardBorder, RoundedCornerShape(8.dp))
            ) { Text("Журнал", fontSize = 13.sp) }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { viewModel.openNewRule() },
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = NavyDark),
                shape = RoundedCornerShape(8.dp)
            ) { Text("+ Создать", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
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
            onUpdate = { viewModel.updateEditing(it) },
            onSave = { viewModel.saveRule() },
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
            title = { Text("Удалить правило?", color = TextPrimary) },
            text = { Text("Это действие нельзя отменить.", color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmDelete() }) {
                    Text("Удалить", color = Color(0xFFEF4444))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelDelete() }) {
                    Text("Отмена", color = TextSecondary)
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
                        .background(if (rule.enabled) AccentGreen else TextMuted)
                )
                Spacer(Modifier.width(8.dp))
                Text(rule.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary,
                    modifier = Modifier.weight(1f))
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = { onToggle() },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = AccentGreen,
                        uncheckedTrackColor = CardBorder
                    ),
                    modifier = Modifier.height(24.dp)
                )
                Box {
                    IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.MoreVert, "menu", tint = TextMuted, modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("Редактировать") },
                            onClick = { menuExpanded = false; onEdit() },
                            leadingIcon = { Icon(Icons.Outlined.Edit, null, modifier = Modifier.size(18.dp)) }
                        )
                        DropdownMenuItem(
                            text = { Text("Дублировать") },
                            onClick = { menuExpanded = false; onDuplicate() },
                            leadingIcon = { Icon(Icons.Outlined.ContentCopy, null, modifier = Modifier.size(18.dp)) }
                        )
                        DropdownMenuItem(
                            text = { Text("Удалить", color = Color(0xFFEF4444)) },
                            onClick = { menuExpanded = false; onDelete() },
                            leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = Color(0xFFEF4444), modifier = Modifier.size(18.dp)) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            // Trigger → Action summary
            Text(
                buildAnnotatedString {
                    triggers.forEachIndexed { i, t ->
                        if (i > 0) {
                            withStyle(SpanStyle(color = TextMuted, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)) {
                                append(if (rule.triggerLogic == "AND") " И " else " ИЛИ ")
                            }
                        }
                        withStyle(SpanStyle(color = AccentBlue)) { append(t.displayName.substringBefore(" ")) }
                        append(" ")
                        withStyle(SpanStyle(color = AccentOrange)) { append(t.operator) }
                        append(" ")
                        withStyle(SpanStyle(color = AccentGreen, fontFamily = FontFamily.Monospace, fontSize = 12.sp)) {
                            append(t.value)
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
            val statsText = buildString {
                append("Сработало: ${rule.triggerCount}")
                rule.lastTriggeredAt?.let { ts ->
                    append(" · Последний: ${formatRelativeTime(ts)}")
                }
                append(" · Пауза: ${rule.cooldownSeconds} сек")
            }
            Text(statsText, fontSize = 11.sp, color = TextMuted)
        }
    }
}

// --- Editor Dialog ---

@Composable
private fun EditorDialog(
    editing: EditingRule,
    onUpdate: (EditingRule.() -> EditingRule) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
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
                    if (editing.isNew) "Новое правило" else editing.name,
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
                        placeholder = { Text("Название правила", color = TextMuted) },
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
                    SectionHeader("КОГДА (условия)")

                    // AND/OR toggle
                    Row {
                        LogicChip("И (AND)", editing.triggerLogic == "AND") {
                            onUpdate { copy(triggerLogic = "AND") }
                        }
                        LogicChip("ИЛИ (OR)", editing.triggerLogic == "OR") {
                            onUpdate { copy(triggerLogic = "OR") }
                        }
                    }
                    Spacer(Modifier.height(8.dp))

                    editing.triggers.forEachIndexed { idx, trigger ->
                        TriggerRow(
                            index = idx,
                            trigger = trigger,
                            onUpdate = { newTrigger ->
                                onUpdate {
                                    copy(triggers = triggers.toMutableList().apply { set(idx, newTrigger) })
                                }
                            },
                            onDelete = {
                                onUpdate {
                                    copy(triggers = triggers.toMutableList().apply { removeAt(idx) })
                                }
                            }
                        )
                        Spacer(Modifier.height(4.dp))
                    }

                    if (editing.triggers.size < 5) {
                        AddButton("+ Добавить условие") {
                            val p = TRIGGER_PARAMS.first()
                            onUpdate {
                                copy(triggers = triggers + TriggerDef(p.param, p.chineseName, ">", "0", p.displayName))
                            }
                        }
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
                    SectionHeader("ТОГДА (действия)")

                    editing.actions.forEachIndexed { idx, action ->
                        ActionRow(
                            index = idx,
                            action = action,
                            onUpdate = { newAction ->
                                onUpdate {
                                    copy(actions = actions.toMutableList().apply { set(idx, newAction) })
                                }
                            },
                            onDelete = {
                                onUpdate {
                                    copy(actions = actions.toMutableList().apply { removeAt(idx) })
                                }
                            }
                        )
                        Spacer(Modifier.height(4.dp))
                    }

                    if (editing.actions.size < 10) {
                        val a = ACTION_COMMANDS.first()
                        AddButton("+ Добавить действие") {
                            onUpdate { copy(actions = actions + ActionDef(a.command, a.displayName)) }
                        }
                    }

                    Spacer(Modifier.height(20.dp))
                    SectionHeader("НАСТРОЙКИ")

                    // Cooldown
                    SettingRow("Пауза между срабатываниями") {
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
                        Text("сек", fontSize = 12.sp, color = TextMuted)
                    }
                    Spacer(Modifier.height(4.dp))

                    // Require park
                    SettingRow("Только на парковке (P)") {
                        Switch(
                            checked = editing.requirePark,
                            onCheckedChange = { v -> onUpdate { copy(requirePark = v) } },
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = AccentGreen, uncheckedTrackColor = CardBorder
                            )
                        )
                    }

                    // Confirm before execute
                    SettingRow("Спрашивать перед выполнением") {
                        Switch(
                            checked = editing.confirmBeforeExecute,
                            onCheckedChange = { v -> onUpdate { copy(confirmBeforeExecute = v) } },
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = AccentGreen, uncheckedTrackColor = CardBorder
                            )
                        )
                    }
                }
            }

            // Footer
            HorizontalDivider(color = CardBorder)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp, 10.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.border(1.5.dp, CardBorder, RoundedCornerShape(8.dp))
                ) { Text("Отмена") }
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = onSave,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = NavyDark),
                    shape = RoundedCornerShape(8.dp),
                    enabled = editing.name.isNotBlank() && editing.triggers.isNotEmpty() && editing.actions.isNotEmpty()
                ) { Text("Сохранить", fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

// --- Trigger Row ---

@Composable
private fun TriggerRow(
    index: Int,
    trigger: TriggerDef,
    onUpdate: (TriggerDef) -> Unit,
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

        // Param dropdown
        CatalogDropdown(
            selected = TRIGGER_PARAMS.find { it.param == trigger.param }?.displayName ?: trigger.param,
            items = TRIGGER_PARAMS.map { it.displayName },
            categories = TRIGGER_PARAMS.map { it.category },
            modifier = Modifier.width(150.dp),
            onSelect = { idx ->
                val p = TRIGGER_PARAMS[idx]
                onUpdate(trigger.copy(param = p.param, chineseName = p.chineseName,
                    displayName = "${p.displayName} ${trigger.operator} ${trigger.value}"))
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
        Spacer(Modifier.weight(1f))

        IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
            Icon(Icons.Outlined.Close, "delete", tint = Color(0xFFEF4444).copy(alpha = 0.5f), modifier = Modifier.size(14.dp))
        }
    }
}

// --- Action Row ---

@Composable
private fun ActionRow(
    index: Int,
    action: ActionDef,
    onUpdate: (ActionDef) -> Unit,
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

        CatalogDropdown(
            selected = action.displayName,
            items = ACTION_COMMANDS.map { it.displayName },
            categories = ACTION_COMMANDS.map { it.category },
            modifier = Modifier.weight(1f),
            onSelect = { idx ->
                val a = ACTION_COMMANDS[idx]
                onUpdate(ActionDef(a.command, a.displayName))
            }
        )
        Spacer(Modifier.width(4.dp))

        IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
            Icon(Icons.Outlined.Close, "delete", tint = Color(0xFFEF4444).copy(alpha = 0.5f), modifier = Modifier.size(14.dp))
        }
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
                Text("Журнал срабатываний", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Outlined.Close, "close", tint = TextMuted)
                }
            }
            HorizontalDivider(color = CardBorder)

            if (logs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Журнал пуст", color = TextMuted, fontSize = 14.sp)
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
                val paramName = TRIGGER_PARAMS.find { it.param == key }?.displayName ?: key
                "$paramName: ${obj.get(key)}"
            }
        } catch (_: Exception) { "" }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor, RoundedCornerShape(8.dp))
            .border(width = 0.dp, color = Color.Transparent, shape = RoundedCornerShape(8.dp))
            .padding(start = 3.dp)
            .background(bgColor, RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp))
            .drawLeftBorder(borderColor, 3.dp)
            .padding(8.dp, 6.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(log.ruleName, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
            Text(
                formatRelativeTime(log.triggeredAt),
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

// Draw a left border stripe on the log item
private fun Modifier.drawLeftBorder(color: Color, width: androidx.compose.ui.unit.Dp) =
    this.then(
        Modifier.background(color, RoundedCornerShape(topStart = width, bottomStart = width))
            .padding(start = width)
    )

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
private fun AddButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.5.dp, CardBorder, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 12.sp, color = TextMuted)
    }
}

// --- Helpers ---

private fun formatRelativeTime(ts: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - ts
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    val dateSdf = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())

    return when {
        diff < 24 * 60 * 60 * 1000L -> sdf.format(Date(ts))
        diff < 48 * 60 * 60 * 1000L -> "вчера ${sdf.format(Date(ts))}"
        else -> dateSdf.format(Date(ts))
    }
}
