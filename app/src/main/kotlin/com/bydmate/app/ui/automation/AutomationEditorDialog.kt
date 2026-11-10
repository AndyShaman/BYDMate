package com.bydmate.app.ui.automation

import android.util.Log
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.WbTwilight
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import com.bydmate.app.data.automation.AutomationEngine
import com.bydmate.app.data.automation.OneShotTrigger
import com.bydmate.app.util.appLocalizedContext
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Gamepad
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import com.bydmate.app.R
import com.bydmate.app.cluster.SteeringKeyOwner
import com.bydmate.app.cluster.knownButtonNameRes
import com.bydmate.app.cluster.readSteeringKeyOwners
import com.bydmate.app.cluster.steeringKeyOwner
import java.time.ZoneId
import com.bydmate.app.data.automation.ScheduleSpec
import com.bydmate.app.data.automation.minuteToHHmm
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.PlaceEntity
import com.bydmate.app.data.local.entity.TriggerDef
import com.bydmate.app.ui.settings.LearnButtonDialog
import com.bydmate.app.ui.components.bydSwitchColors
import com.bydmate.app.ui.theme.*
import com.bydmate.app.ui.widget.WidgetButtonIcons
import com.bydmate.app.ui.widget.WidgetPreferences

// --- Editor Dialog ---

/** Fields, chips and dropdowns: 40dp to see, 48dp to touch. */
internal val FIELD_HEIGHT = 40.dp
internal val FIELD_SHAPE = RoundedCornerShape(8.dp)
internal val EDITOR_TEXT = 16.sp

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
internal fun EditorDialog(
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
internal fun EditorField(
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
internal fun DropdownField(
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
internal fun RowButtons(showArrows: Boolean, onMoveUp: (() -> Unit)?, onMoveDown: (() -> Unit)?, onDelete: () -> Unit) {
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

internal fun newOneShotTrigger(context: Context): TriggerDef = TriggerDef(
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
    val phases = TIME_OF_DAY_NAMES.map { (value, name) -> value to stringResource(name) }
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

    val dayLabels = DAY_NAMES.mapIndexed { i, name -> i + 1 to stringResource(name) }

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
    return days.sorted().joinToString(",") { context.getString(DAY_NAMES[it - 1]) }
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
 * voice button, the volume knob and the split key BEFORE automation rules, so a rule bound to one
 * of those keys would never fire — the learn dialog says so instead of saving a dead binding.
 */
private fun steeringKeyOccupiedReason(context: Context): (Int) -> String? {
    val owners = readSteeringKeyOwners(context)
    return { keyCode -> steeringKeyOwner(keyCode, owners)?.let { steeringKeyOwnerText(context, it) } }
}

/** Why a key cannot be learned: the feature that already owns it. */
internal fun steeringKeyOwnerText(context: Context, owner: SteeringKeyOwner): String = context.getString(
    when (owner) {
        SteeringKeyOwner.VOICE -> R.string.automation_steering_key_occupied_voice
        SteeringKeyOwner.KNOB -> R.string.automation_steering_key_occupied_knob
        SteeringKeyOwner.PROJECTION -> R.string.automation_steering_key_occupied_projection
        SteeringKeyOwner.SPLIT -> R.string.automation_steering_key_occupied_split
    }
)
