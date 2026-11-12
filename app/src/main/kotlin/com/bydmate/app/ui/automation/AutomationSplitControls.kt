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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import com.bydmate.app.ui.components.AppAlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.bydmate.app.R
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.ui.components.AppLaunchPickerDialog
import com.bydmate.app.ui.theme.*

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
