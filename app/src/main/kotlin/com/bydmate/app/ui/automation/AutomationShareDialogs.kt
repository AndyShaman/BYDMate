package com.bydmate.app.ui.automation

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bydmate.app.R
import com.bydmate.app.data.local.entity.PlaceEntity
import com.bydmate.app.ui.components.AppAlertDialog
import com.bydmate.app.ui.settings.PlacesInlineContent
import com.bydmate.app.ui.theme.AccentBlue
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.AccentOrange
import com.bydmate.app.ui.theme.AccentTeal
import com.bydmate.app.ui.theme.CardBorder
import com.bydmate.app.ui.theme.CardSurfaceElevated
import com.bydmate.app.ui.theme.NavyDark
import com.bydmate.app.ui.theme.ScaledDialogContent
import com.bydmate.app.ui.theme.SocRed
import com.bydmate.app.ui.theme.TextMuted
import com.bydmate.app.ui.theme.TextPrimary
import com.bydmate.app.ui.theme.TextSecondary
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** «Места» from the Automation header: the list that used to live in Settings. */
@Composable
internal fun PlacesDialog(onDismiss: () -> Unit) {
    AppAlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardSurfaceElevated,
        title = { Text(stringResource(R.string.settings_section_places_title), color = TextPrimary) },
        text = {
            // PlacesInlineContent is a plain Column, so the dialog scrolls it as a whole.
            Column(modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                PlacesInlineContent()
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_backup_close), color = TextSecondary)
            }
        },
    )
}

/**
 * After «Поделиться», before anything is written: what the file keeps and what to check.
 * «Продолжить» writes the file and opens the system share sheet.
 */
@Composable
internal fun ShareNoteDialog(onContinue: () -> Unit, onDismiss: () -> Unit) {
    AppAlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardSurfaceElevated,
        title = { Text(stringResource(R.string.automation_share_button), color = TextPrimary) },
        text = {
            Text(stringResource(R.string.automation_share_note), color = TextSecondary, fontSize = 13.sp, lineHeight = 18.sp)
        },
        confirmButton = {
            TextButton(onClick = onContinue) {
                Text(stringResource(R.string.automation_share_continue), color = AccentGreen)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.automation_cancel_button), color = TextSecondary)
            }
        },
    )
}

/** «Сохранить» on a rule deleted while it was open in the editor: nothing is saved. */
@Composable
internal fun RuleDeletedDialog(onClose: () -> Unit) {
    AppAlertDialog(
        onDismissRequest = onClose,
        containerColor = CardSurfaceElevated,
        text = { Text(stringResource(R.string.automation_rule_deleted), color = TextPrimary, fontSize = 14.sp) },
        confirmButton = {
            TextButton(onClick = onClose) {
                Text(stringResource(R.string.settings_backup_close), color = AccentGreen)
            }
        },
    )
}

/** Import step 1: `bydmate_rule_*.json` in Download, newest first, with the date. */
@Composable
internal fun ImportPickDialog(
    files: List<File>,
    error: String?,
    onPick: (File) -> Unit,
    onDismiss: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val dateFormat = remember(locale) { SimpleDateFormat("d MMM HH:mm", locale) }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardSurfaceElevated,
        title = { Text(stringResource(R.string.automation_import_pick_title), color = TextPrimary) },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (files.isEmpty()) {
                    Text(stringResource(R.string.automation_import_pick_empty), color = TextSecondary, fontSize = 14.sp)
                }
                files.forEach { file ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(file) }
                            .padding(horizontal = 4.dp, vertical = 8.dp)
                    ) {
                        Text(file.name, color = TextPrimary, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                        Text(dateFormat.format(Date(file.lastModified())), color = TextSecondary, fontSize = 12.sp)
                    }
                }
                if (error != null) {
                    Text(error, color = SocRed, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.automation_cancel_button), color = TextSecondary)
            }
        },
    )
}

/**
 * Import step 2: the rule as it will be added. Every row is built from what will run
 * ([RuleImportSummary], via [RuleImportDraft.preview]), not from the labels in the file, and
 * the settings come in one line. Places not found by name (or found twice) and numbers of
 * calls and tel/sms links (never in the file) are asked for under «Нужно уточнить», links
 * emptied on export are listed there too (fixed later in the editor); while any stays open the
 * rule can only be added switched off. While the insert runs every control waits.
 */
@Composable
internal fun ImportPreviewDialog(
    draft: RuleImportDraft,
    places: List<PlaceEntity>,
    onPickPlace: (index: Int, place: PlaceEntity) -> Unit,
    onPickContact: (index: Int, phone: String, name: String, autoDial: Boolean) -> Unit,
    onEnableNowChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val rule = draft.rule
    val preview = draft.preview
    val unresolvedPlaces = rule.unresolvedPlaceIndexes()
    val unresolvedCalls = rule.unresolvedCallIndexes()
    val unresolvedUrls = rule.unresolvedUrlIndexes()
    val canEnable = !rule.hasUnresolved()
    val idle = !draft.saving
    var contactFor by remember { mutableStateOf<Int?>(null) }

    AppAlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardSurfaceElevated,
        title = { Text(stringResource(R.string.automation_import_preview_title), color = TextPrimary) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    rule.name,
                    color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, CardBorder, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                )
                Text(stringResource(R.string.automation_import_conditions), color = TextPrimary, fontSize = 13.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                Text(preview.logic, color = TextSecondary, fontSize = 12.sp)
                preview.triggers.forEach { line ->
                    Text(
                        line,
                        color = AccentBlue,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .border(1.dp, CardBorder, RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
                Text(stringResource(R.string.automation_import_actions), color = TextPrimary, fontSize = 13.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                preview.actions.forEach { line ->
                    Text(line, color = AccentTeal, fontSize = 13.sp)
                }
                Text(preview.flags, color = TextSecondary, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp))

                if (!canEnable) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .drawBehind {
                                drawLine(AccentOrange, Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 3.dp.toPx())
                            }
                            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(stringResource(R.string.automation_import_need_attention), color = AccentOrange,
                            fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        unresolvedPlaces.forEach { index ->
                            UnresolvedRow(
                                text = stringResource(R.string.automation_import_place_missing, rule.triggers[index].placeName.orEmpty()),
                            ) {
                                PlacePickButton(places = places, enabled = idle, onPick = { onPickPlace(index, it) })
                            }
                        }
                        unresolvedCalls.forEach { index ->
                            UnresolvedRow(text = stringResource(R.string.automation_import_contact_missing)) {
                                OutlinedButton(onClick = { contactFor = index }, enabled = idle, shape = RoundedCornerShape(8.dp)) {
                                    Text(stringResource(R.string.automation_import_pick_contact) + " ▾", color = TextPrimary, fontSize = 13.sp)
                                }
                            }
                        }
                        if (unresolvedUrls.isNotEmpty()) {
                            Text(stringResource(R.string.automation_import_url_required), color = TextPrimary, fontSize = 13.sp)
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = canEnable && idle) { onEnableNowChange(!draft.enableNow) }
                        .padding(top = 4.dp)
                ) {
                    Checkbox(
                        checked = draft.enableNow,
                        onCheckedChange = { onEnableNowChange(it) },
                        enabled = canEnable && idle,
                        colors = CheckboxDefaults.colors(
                            checkedColor = AccentGreen,
                            uncheckedColor = TextMuted,
                            checkmarkColor = NavyDark
                        )
                    )
                    Spacer(Modifier.width(4.dp))
                    Column(modifier = Modifier.padding(top = 12.dp)) {
                        Text(stringResource(R.string.automation_import_enable_now), fontSize = 13.sp,
                            color = if (canEnable) TextPrimary else TextMuted)
                        Text(stringResource(R.string.automation_import_enable_now_hint), fontSize = 11.sp, color = TextMuted)
                    }
                }

                if (draft.error != null) {
                    Text(draft.error, color = SocRed, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = idle,
                colors = ButtonDefaults.textButtonColors(containerColor = AccentGreen, contentColor = NavyDark),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(stringResource(R.string.automation_import_add), fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = idle) {
                Text(stringResource(R.string.automation_cancel_button), color = TextSecondary)
            }
        },
    )

    contactFor?.let { index ->
        val action = rule.actions.getOrNull(index)
        CallEditDialog(
            initialPhone = "",
            initialName = "",
            initialAutoDial = action?.callAutoDial() ?: false,
            onDismiss = { contactFor = null },
            onSave = { phone, name, autoDial ->
                onPickContact(index, phone, name, autoDial)
                contactFor = null
            },
            showAutoDial = action?.kind == "call",
        )
    }
}

@Composable
private fun UnresolvedRow(text: String, button: @Composable () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(end = 8.dp))
        button()
    }
}

@Composable
private fun PlacePickButton(places: List<PlaceEntity>, enabled: Boolean, onPick: (PlaceEntity) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, shape = RoundedCornerShape(8.dp)) {
            Text(stringResource(R.string.automation_import_pick_place) + " ▾", color = TextPrimary, fontSize = 13.sp)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ScaledDialogContent {
                if (places.isEmpty()) {
                    DropdownMenuItem(
                        text = {
                            Text(stringResource(R.string.automation_trigger_type_place_empty_hint), fontSize = 13.sp, color = TextMuted)
                        },
                        onClick = { expanded = false },
                    )
                }
                places.forEach { place ->
                    DropdownMenuItem(
                        // Coordinates tell apart two places with the same name.
                        text = {
                            Column {
                                Text(place.name, fontSize = 13.sp)
                                Text(
                                    String.format(Locale.US, "%.5f, %.5f", place.lat, place.lon),
                                    fontSize = 11.sp, color = TextMuted, fontFamily = FontFamily.Monospace,
                                )
                            }
                        },
                        onClick = {
                            expanded = false
                            onPick(place)
                        },
                    )
                }
            }
        }
    }
}
