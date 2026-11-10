package com.bydmate.app.ui.settings

import com.bydmate.app.data.autoservice.AdbRestoreState
import com.bydmate.app.data.autoservice.CloudOverWifiState
import com.bydmate.app.ui.widget.WidgetController
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.IconButton
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.runtime.LaunchedEffect
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import com.bydmate.app.R
import com.bydmate.app.ui.theme.*
import androidx.compose.material.icons.outlined.Mic
import java.util.Date

internal enum class SettingsSection(@StringRes val labelRes: Int, val icon: ImageVector) {
    VOICE(R.string.settings_section_voice_agent, Icons.Outlined.Mic),
    WIDGET(R.string.settings_section_widget_title, Icons.Outlined.PhoneAndroid),
    DISPLAY(R.string.settings_section_display_title, Icons.Outlined.DirectionsCar),
    SPLIT(R.string.settings_section_split_title, Icons.Outlined.Apps),
    BATTERY(R.string.settings_section_auto_battery_title, Icons.Outlined.BatteryChargingFull),
    INTEGRATIONS(R.string.settings_section_integrations_title, Icons.Outlined.Link),
    SERVICE(R.string.settings_section_service_title, Icons.Outlined.Build),
    APP(R.string.settings_section_application_title, Icons.Outlined.Settings),
    SMART_HOME(R.string.settings_smart_home_section_title, Icons.Outlined.Home),
}

internal val PrimaryColor = AccentGreen

/** Own phrases for built-in commands are hidden while the built-in phrase list settles. */
internal const val SHOW_VOICE_USER_PHRASES = false

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateToAgentChat: () -> Unit = {},
    onNavigateToVoiceJournal: () -> Unit = {},
    onNavigateToVoiceUserPhrases: () -> Unit = {},
    onNavigateToTariffPeriods: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // The fid recorder runs in the daemon, so its switch is re-read every time the screen opens.
    LaunchedEffect(Unit) { viewModel.refreshFidRecorder() }

    // Manual range calculation table dialog
    if (state.showManualRangeTableDialog) {
        ManualRangeTableDialog(
            currentTable = state.manualRangeTable,
            onDismiss = { viewModel.hideManualRangeTableDialog() },
            onSave = { viewModel.saveManualRangeTable(it) },
            onReset = { viewModel.resetManualRangeTable() },
        )
    }

    // Update dialog
    if (state.showUpdateDialog) {
        UpdateDialog(
            currentVersion = state.appVersion,
            state = state.updateDialogState,
            onCheck = {
                when (state.updateDialogState) {
                    is UpdateState.Available -> viewModel.downloadUpdate()
                    else -> viewModel.checkForUpdate()
                }
            },
            onDismiss = { viewModel.hideUpdateDialog() }
        )
    }

    val previewContext = LocalContext.current
    val previewLifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(previewLifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                WidgetController.setPreviewMode(previewContext, false)
            }
        }
        previewLifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            previewLifecycleOwner.lifecycle.removeObserver(observer)
            WidgetController.setPreviewMode(previewContext, false)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(NavyDark, NavyDeep)))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            color = TextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )

        Spacer(modifier = Modifier.height(12.dp))

        var selected by rememberSaveable { mutableStateOf(SettingsSection.VOICE) }
        val hiddenSelected = selected == SettingsSection.SMART_HOME
        val safeSelected = if (hiddenSelected && !state.devModeUnlocked) {
            SettingsSection.VOICE
        } else {
            selected
        }

        Row(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsRail(
                selected = safeSelected,
                smartHomeUnlocked = state.devModeUnlocked,
                appVersion = state.appVersion,
                onSelect = { selected = it },
                onVersionTap = { viewModel.onVersionTap() },
                modifier = Modifier.width(260.dp).fillMaxSize(),
            )

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardSurface),
                modifier = Modifier.weight(1f).fillMaxSize(),
            ) {
                Column(
                    modifier = Modifier
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (safeSelected) {
                        SettingsSection.BATTERY -> BatterySection(state, viewModel, onNavigateToTariffPeriods)
                        SettingsSection.INTEGRATIONS -> IntegrationsSection(state, viewModel)
                        SettingsSection.VOICE -> VoiceSettingsContent(
                            state, viewModel, onNavigateToVoiceJournal, onNavigateToAgentChat, onNavigateToVoiceUserPhrases,
                        )
                        SettingsSection.WIDGET -> WidgetSection()
                        SettingsSection.DISPLAY -> DisplaySection()
                        SettingsSection.SPLIT -> SplitSection(viewModel)
                        SettingsSection.SERVICE -> ServiceSection(state, viewModel)
                        SettingsSection.APP -> AppSection(state, viewModel)
                        SettingsSection.SMART_HOME -> SmartHomeSection(state, viewModel)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsRail(
    selected: SettingsSection,
    smartHomeUnlocked: Boolean,
    appVersion: String,
    onSelect: (SettingsSection) -> Unit,
    onVersionTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(vertical = 12.dp, horizontal = 8.dp)) {
            // Only the section list scrolls, for enlarged fonts or a short screen; it takes the
            // remaining height, so the divider and the version stay pinned to the bottom.
            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.settings_rail_sections_label),
                    color = TextMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )

                SettingsSection.entries.forEach { section ->
                    val isHidden = section == SettingsSection.SMART_HOME
                    if (isHidden && !smartHomeUnlocked) return@forEach
                    RailItem(
                        section = section,
                        isActive = section == selected,
                        isHidden = isHidden,
                        onClick = { onSelect(section) },
                    )
                }
            }

            HorizontalDivider(color = CardBorder)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clickable { onVersionTap() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "v$appVersion",
                    color = TextMuted,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun RailItem(
    section: SettingsSection,
    isActive: Boolean,
    isHidden: Boolean,
    onClick: () -> Unit,
) {
    val activeColor = if (isHidden) AccentOrange else AccentGreen
    val bg = if (isActive) activeColor.copy(alpha = 0.12f) else Color.Transparent
    val fg = when {
        isActive -> activeColor
        isHidden -> AccentOrange
        else -> TextSecondary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 2.dp)
            .background(bg, shape = RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = section.icon,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = stringResource(section.labelRes),
            color = fg,
            fontSize = 14.sp,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
internal fun SectionHeader(text: String, onHelp: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        // Optional "?" badge (SettingHelpBadge idiom) for sections that need a how-to.
        if (onHelp != null) SettingHelpBadge(onHelp)
    }
}

@Composable
internal fun SettingsTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType,
    secret: Boolean = false,
    singleLine: Boolean = true,
    modifier: Modifier = Modifier,
) {
    // Secret fields (API keys, tokens) are masked so screenshots and over-the-shoulder
    // looks do not leak them; the eye icon reveals the value while editing.
    var revealed by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        visualTransformation = if (secret && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        trailingIcon = if (secret) {
            {
                IconButton(onClick = { revealed = !revealed }) {
                    Icon(
                        imageVector = if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = stringResource(
                            if (revealed) R.string.settings_secret_hide else R.string.settings_secret_show
                        ),
                        tint = TextSecondary,
                    )
                }
            }
        } else null,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedBorderColor = AccentGreen,
            unfocusedBorderColor = CardBorder,
            focusedLabelColor = PrimaryColor,
            unfocusedLabelColor = TextSecondary,
            cursorColor = PrimaryColor
        )
    )
}

/** Status line under the ADB restore toggle; null while the feature is off. */
@Composable
internal fun adbRestoreStatusText(state: AdbRestoreState): String? = when (state) {
    AdbRestoreState.Disabled -> null
    AdbRestoreState.Unsupported -> stringResource(R.string.settings_adb_restore_status_unsupported)
    AdbRestoreState.NotNeeded -> stringResource(R.string.settings_adb_restore_status_not_needed)
    AdbRestoreState.NeedsActivation -> stringResource(R.string.settings_adb_restore_status_needs_activation)
    AdbRestoreState.WaitingWifi -> stringResource(R.string.settings_adb_restore_status_waiting_wifi)
    AdbRestoreState.NeedsDialog -> stringResource(R.string.settings_adb_restore_status_needs_dialog)
    AdbRestoreState.Connecting -> stringResource(R.string.settings_adb_restore_status_connecting)
    is AdbRestoreState.Restored -> stringResource(
        R.string.settings_adb_restore_status_restored,
        android.text.format.DateFormat.getTimeFormat(LocalContext.current)
            .format(java.util.Date(state.atMs)),
    )
    is AdbRestoreState.Failed -> stringResource(R.string.settings_adb_restore_status_failed, state.reason)
}

@Composable
internal fun cloudOverWifiStatusText(state: CloudOverWifiState): String? = when (state) {
    CloudOverWifiState.Disabled -> null
    CloudOverWifiState.NoAdb -> stringResource(R.string.settings_cloud_wifi_status_no_adb)
    CloudOverWifiState.OwnCellular -> stringResource(R.string.settings_cloud_wifi_status_own_cellular)
    CloudOverWifiState.WaitingInternet -> stringResource(R.string.settings_cloud_wifi_status_waiting)
    CloudOverWifiState.ProfileRejected -> stringResource(R.string.settings_cloud_wifi_status_rejected)
    CloudOverWifiState.CloudSilent -> stringResource(R.string.settings_cloud_wifi_status_silent)
    CloudOverWifiState.ServerRefused -> stringResource(R.string.settings_cloud_wifi_status_refused)
    CloudOverWifiState.Connected -> stringResource(R.string.settings_cloud_wifi_status_connected)
    CloudOverWifiState.ReturnPending -> stringResource(R.string.settings_cloud_wifi_status_return_pending)
}
