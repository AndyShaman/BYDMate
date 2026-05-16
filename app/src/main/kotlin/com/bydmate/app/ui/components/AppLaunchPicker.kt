package com.bydmate.app.ui.components

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bydmate.app.R
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.CardBorder
import com.bydmate.app.ui.theme.CardSurface
import com.bydmate.app.ui.theme.TextMuted
import com.bydmate.app.ui.theme.TextPrimary
import com.bydmate.app.ui.theme.TextSecondary

data class InstalledApp(val packageName: String, val label: String)

/**
 * Reusable app chooser. Used by Automation (with minimize toggle) and the
 * widget settings (without). `showMinimizeToggle = false` hides the
 * "minimize after launch" checkbox and skips emitting the parameter.
 */
@Composable
fun AppLaunchPickerDialog(
    currentPackage: String,
    onDismiss: () -> Unit,
    onSelect: (pkg: String, label: String) -> Unit,
    showMinimizeToggle: Boolean = false,
    initialMinimize: Boolean = false,
    onMinimizeChanged: ((Boolean) -> Unit)? = null,
) {
    val context = LocalContext.current
    val apps = remember { queryLaunchableApps(context) }
    var search by remember { mutableStateOf("") }
    var minimize by remember { mutableStateOf(initialMinimize) }

    val filtered = remember(search, apps) {
        val q = search.trim().lowercase()
        if (q.isEmpty()) apps
        else apps.filter { it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = TextPrimary,
        unfocusedTextColor = TextPrimary,
        focusedBorderColor = AccentGreen,
        unfocusedBorderColor = CardBorder,
        focusedLabelColor = AccentGreen,
        unfocusedLabelColor = TextSecondary,
        cursorColor = AccentGreen,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardSurface,
        title = { Text(stringResource(R.string.applauncher_title), color = TextPrimary, fontSize = 16.sp) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text(stringResource(R.string.applauncher_search_label)) },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (showMinimizeToggle) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                minimize = !minimize
                                onMinimizeChanged?.invoke(minimize)
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = minimize,
                            onCheckedChange = {
                                minimize = it
                                onMinimizeChanged?.invoke(it)
                            },
                            colors = CheckboxDefaults.colors(
                                checkedColor = AccentGreen,
                                uncheckedColor = CardBorder,
                                checkmarkColor = TextPrimary,
                            ),
                        )
                        Text(stringResource(R.string.applauncher_minimize_label), fontSize = 13.sp, color = TextPrimary)
                    }
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp),
                ) {
                    items(filtered, key = { it.packageName }) { app ->
                        val selected = app.packageName == currentPackage
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(app.packageName, app.label) }
                                .background(if (selected) AccentGreen.copy(alpha = 0.1f) else Color.Transparent)
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(app.label, fontSize = 14.sp, color = TextPrimary, maxLines = 1)
                                Text(app.packageName, fontSize = 11.sp, color = TextMuted, maxLines = 1)
                            }
                        }
                    }
                    if (filtered.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.applauncher_empty),
                                color = TextMuted,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(8.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.applauncher_close_button), color = TextSecondary) }
        },
    )
}

fun queryLaunchableApps(context: Context): List<InstalledApp> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
    val resolved = pm.queryIntentActivities(intent, 0)
    val self = context.packageName
    return resolved
        .map { InstalledApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
        .filter { it.packageName != self }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}
