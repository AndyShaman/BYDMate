package com.bydmate.app.ui.automation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import com.bydmate.app.ui.components.AppAlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.bydmate.app.R
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.ui.theme.*

// --- Shared Composables ---

@Composable
internal fun AutoChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
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
internal fun NotificationActionControls(
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
