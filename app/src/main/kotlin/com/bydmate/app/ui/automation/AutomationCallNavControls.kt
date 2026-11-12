package com.bydmate.app.ui.automation

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Place
import com.bydmate.app.ui.components.AppAlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.bydmate.app.R
import com.bydmate.app.data.automation.ScheduleSpec
import com.bydmate.app.data.automation.minuteToHHmm
import com.bydmate.app.data.automation.serviceStartTrigger
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.PlaceEntity
import com.bydmate.app.data.local.entity.TriggerDef
import com.bydmate.app.ui.theme.*

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
