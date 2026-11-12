package com.bydmate.app.ui.automation

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.minimumInteractiveComponentSize
import com.bydmate.app.util.appLocalizedContext
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.compositionLocalOf
import com.bydmate.app.data.vehicle.RearSeat
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.ui.platform.LocalContext
import com.bydmate.app.ui.components.AppAlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import com.bydmate.app.R
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.PlaceEntity
import com.bydmate.app.data.local.entity.TriggerDef
import com.bydmate.app.ui.theme.*

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
internal val LocalRearSeats = compositionLocalOf { emptySet<RearSeat>() }

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
internal const val MESSAGE_SHOW_MS = 4_000L

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
