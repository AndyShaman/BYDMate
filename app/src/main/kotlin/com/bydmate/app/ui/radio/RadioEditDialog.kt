package com.bydmate.app.ui.radio

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bydmate.app.R
import com.bydmate.app.data.local.entity.RadioStationEntity
import com.bydmate.app.media.RadioPresets
import com.bydmate.app.media.RadioUrlValidator
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.CardBorder
import com.bydmate.app.ui.theme.CardSurface
import com.bydmate.app.ui.theme.TextMuted
import com.bydmate.app.ui.theme.TextPrimary
import com.bydmate.app.ui.theme.TextSecondary

private const val MAX_NAME_LEN = 40

/**
 * Add/edit dialog for a manually entered radio station: name, stream URL and an optional icon
 * (a remote URL, or a local image picked with the system file picker).
 */
@Composable
fun RadioEditDialog(
    initial: RadioStationEntity?,
    onDismiss: () -> Unit,
    onSave: (id: Long?, name: String, url: String, iconUrl: String?) -> Unit
) {
    val context = LocalContext.current

    var nameText by rememberSaveable { mutableStateOf(initial?.name ?: "") }
    var urlText by rememberSaveable { mutableStateOf(initial?.url ?: "") }
    // A built-in station carries an internal bydmate://preset/… icon reference. Showing that in
    // the field would be noise, so the field stays empty and the reference is kept unless the
    // user types a real URL over it.
    val presetIcon = initial?.iconUrl?.takeIf { RadioPresets.isIconRef(it) }
    var iconText by rememberSaveable {
        mutableStateOf(initial?.iconUrl?.takeUnless { RadioPresets.isIconRef(it) } ?: "")
    }

    // OpenDocument (not GetContent) — only it hands out a URI we may persist across restarts.
    val pickIcon = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            iconText = uri.toString()
        }
    }

    val nameValid = nameText.trim().isNotBlank() && nameText.trim().length <= MAX_NAME_LEN
    val urlValid = RadioUrlValidator.isValidStreamUrl(urlText)
    val iconValid = RadioUrlValidator.isValidIconUrl(iconText)
    val canSave = nameValid && urlValid && iconValid

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = TextPrimary,
        unfocusedTextColor = TextPrimary,
        focusedBorderColor = AccentGreen,
        unfocusedBorderColor = CardBorder,
        focusedLabelColor = AccentGreen,
        unfocusedLabelColor = TextSecondary,
        cursorColor = AccentGreen
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false),
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CardSurface),
            modifier = Modifier.fillMaxWidth(0.9f),
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = stringResource(
                        if (initial == null) R.string.radio_edit_dialog_title_new
                        else R.string.radio_edit_dialog_title_edit
                    ),
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(12.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = nameText,
                        onValueChange = { if (it.length <= MAX_NAME_LEN) nameText = it },
                        label = { Text(stringResource(R.string.radio_edit_name_label)) },
                        singleLine = true,
                        isError = nameText.isNotEmpty() && !nameValid,
                        shape = RoundedCornerShape(8.dp),
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = urlText,
                        onValueChange = { urlText = it },
                        label = { Text(stringResource(R.string.radio_edit_url_label)) },
                        placeholder = { Text("https://…", color = TextMuted) },
                        singleLine = true,
                        isError = urlText.isNotEmpty() && !urlValid,
                        supportingText = {
                            Text(stringResource(R.string.radio_edit_url_hint), color = TextMuted, fontSize = 11.sp)
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = iconText,
                            onValueChange = { iconText = it },
                            label = { Text(stringResource(R.string.radio_edit_icon_label)) },
                            placeholder = { Text("https://…", color = TextMuted) },
                            singleLine = true,
                            isError = iconText.isNotEmpty() && !iconValid,
                            shape = RoundedCornerShape(8.dp),
                            colors = fieldColors,
                            modifier = Modifier.weight(1f)
                        )
                        // Debounced: the field is typed character by character and every
                        // intermediate value would otherwise fire its own icon download.
                        RadioStationIcon(
                            iconUrl = iconText.takeIf { iconValid && it.isNotBlank() } ?: presetIcon,
                            fallbackText = nameText,
                            debounceMs = 600,
                            modifier = Modifier.size(56.dp)
                        )
                    }

                    TextButton(onClick = { pickIcon.launch(arrayOf("image/*")) }) {
                        Text(stringResource(R.string.radio_edit_icon_pick), color = AccentGreen)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.settings_cancel_button), color = TextSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            if (canSave) {
                                onSave(
                                    initial?.id,
                                    nameText.trim(),
                                    urlText.trim(),
                                    iconText.trim().takeIf { it.isNotEmpty() } ?: presetIcon
                                )
                            }
                        },
                        enabled = canSave
                    ) {
                        Text(
                            stringResource(R.string.charges_edit_save_button),
                            color = if (canSave) AccentGreen else TextMuted
                        )
                    }
                }
            }
        }
    }
}
