package com.bydmate.app.ui.settings

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.bydmate.app.cluster.ClusterEntryPoint
import com.bydmate.app.data.autoservice.AdbVerdict
import com.bydmate.app.data.autoservice.CloudOverWifiState
import com.bydmate.app.data.backup.AutoBackupPeriod
import com.bydmate.app.data.backup.BackupPart
import com.bydmate.app.cluster.ClusterProjectionManager
import dagger.hilt.android.EntryPointAccessors
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.bydmate.app.ui.components.AppAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.rememberCoroutineScope
import com.bydmate.app.data.telegram.ReportField
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import com.bydmate.app.R
import com.bydmate.app.ui.components.AdbVerdictDialog
import com.bydmate.app.ui.components.adbVerdictColor
import com.bydmate.app.ui.components.adbVerdictText
import com.bydmate.app.ui.theme.*
import com.bydmate.app.hud.HudCheck
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun ServiceSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    val context = LocalContext.current
    val clusterPrefs = remember {
        context.getSharedPreferences(ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE)
    }
    val clusterEntryPoint = remember {
        EntryPointAccessors.fromApplication(context.applicationContext, ClusterEntryPoint::class.java)
    }

    // Own picker over Download/ instead of SAF: on some firmwares ACTION_OPEN_DOCUMENT hands
    // back a URI without a read grant (#223). Non-null restoreCandidates = picker open.
    var showSaveDialog by remember { mutableStateOf(false) }

    state.restoreCandidates?.let { files ->
        AppAlertDialog(
            onDismissRequest = { viewModel.closeRestorePicker() },
            title = {
                Text(
                    stringResource(R.string.settings_config_restore_pick_title),
                    color = TextPrimary,
                )
            },
            text = {
                if (files.isEmpty()) {
                    Text(
                        stringResource(R.string.settings_config_restore_pick_empty),
                        color = TextSecondary,
                    )
                } else {
                    val dateFormat = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(files) { file ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.pickRestoreFile(file) }
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text(file.name, color = TextPrimary, fontSize = 13.sp, maxLines = 1)
                                Text(
                                    dateFormat.format(Date(file.lastModified())),
                                    color = TextSecondary,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { viewModel.closeRestorePicker() }) {
                    Text(
                        stringResource(R.string.settings_config_restore_confirm_cancel),
                        color = TextSecondary,
                    )
                }
            },
            containerColor = CardSurfaceElevated,
        )
    }

    // «Что восстановить»: only the parts the archive carries can be checked (#238).
    state.restoreChoice?.let { choice ->
        BackupPartsDialog(
            title = stringResource(R.string.settings_config_restore_dialog_title),
            initial = choice.parts,
            available = choice.parts,
            confirmLabel = stringResource(R.string.settings_config_restore_confirm_ok),
            confirmColor = SocRed,
            warning = stringResource(R.string.settings_config_restore_warning),
            busy = state.restoreInProgress,
            onConfirm = { viewModel.restoreConfig(choice.file, it) },
            onDismiss = { viewModel.dismissRestoreChoice() },
        )
    }

    if (showSaveDialog) {
        BackupPartsDialog(
            title = stringResource(R.string.settings_config_save_dialog_title),
            initial = state.manualBackupParts,
            available = BackupPart.ALL,
            confirmLabel = stringResource(R.string.settings_config_save_button),
            confirmColor = PrimaryColor,
            warning = null,
            busy = state.restoreInProgress,
            onConfirm = {
                showSaveDialog = false
                viewModel.saveConfiguration(it)
            },
            onDismiss = { showSaveDialog = false },
        )
    }

    // After a manual save: the file, the Telegram outcome and the system share sheet (#237, #238).
    state.savedBackup?.let { saved ->
        SavedBackupDialog(
            saved = saved,
            withKeys = BackupPart.KEYS in state.manualBackupParts,
            onShare = { viewModel.shareExportedBackup() },
            onDismiss = { viewModel.dismissExportedBackup() },
        )
    }

    // Car system: settings that change the head unit itself, not the app.
    SectionHeader(text = stringResource(R.string.settings_car_system_header))

    // Volume-knob press → play/pause. The interception lives in the a11y key filter, so turning
    // the switch on self-enables it via the daemon, exactly like the projection card.
    var knobPlayPause by remember {
        mutableStateOf(clusterPrefs.getBoolean(ClusterProjectionManager.KEY_KNOB_PLAY_PAUSE, false))
    }
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp)) {
            SettingToggleRow(
                title = stringResource(R.string.settings_knob_play_pause_title),
                traceId = "knob_play_pause",
                description = stringResource(R.string.settings_knob_play_pause_desc),
                checked = knobPlayPause,
                onCheckedChange = {
                    knobPlayPause = it
                    clusterPrefs.edit().putBoolean(ClusterProjectionManager.KEY_KNOB_PLAY_PAUSE, it).apply()
                    if (it) {
                        ClusterProjectionManager.enableStarControl(
                            clusterEntryPoint.helperClient(), clusterEntryPoint.helperBootstrap())
                    }
                },
            )
        }
    }

    // ADB restore (firmwares that close port 5555 on every reboot). The toggle is shown on
    // every car: where the port survives a reboot the status line simply says so.
    val adbRestore = remember { clusterEntryPoint.adbRestoreManager() }
    var adbRestoreEnabled by remember { mutableStateOf(adbRestore.isEnabled()) }
    // Android 10: the toggle stays visible but locked off, the status line says why.
    val adbRestoreSupported = remember { adbRestore.isSupported() }
    var adbRestoreHelpOpen by remember { mutableStateOf(false) }
    val adbRestoreState by adbRestore.state.collectAsStateWithLifecycle()
    val adbVerdict by viewModel.adbVerdict.collectAsStateWithLifecycle()
    val adbChecking by viewModel.adbChecking.collectAsStateWithLifecycle()
    // The verdict the dialog was opened for; cleared when the problem goes away.
    var openAdbVerdict by remember { mutableStateOf<AdbVerdict?>(null) }
    LaunchedEffect(adbVerdict) {
        if (adbVerdict == null || adbVerdict == AdbVerdict.OK) openAdbVerdict = null
    }
    // Opening Settings with the feature on refreshes the status line (and picks the port back
    // up if it is down) instead of showing whatever the last trigger left behind. Keyed on the
    // toggle, so switching it on runs an attempt right away. The attempt runs in the manager's
    // own scope, not this composition: leaving the screen used to kill the helper bootstrap that
    // follows a successful restore.
    LaunchedEffect(adbRestoreEnabled) {
        if (adbRestoreEnabled) adbRestore.requestAttempt("settings")
    }
    // BYD cloud over Wi-Fi (#310), same card. Every step goes through ADB to ourselves.
    val cloudOverWifi = remember { clusterEntryPoint.cloudOverWifiManager() }
    var cloudOverWifiEnabled by remember { mutableStateOf(cloudOverWifi.isEnabled()) }
    var cloudOverWifiHelpOpen by remember { mutableStateOf(false) }
    val cloudOverWifiState by cloudOverWifi.state.collectAsStateWithLifecycle()
    val cloudAdbReady = adbVerdict == AdbVerdict.OK || adbVerdict == AdbVerdict.HELPER_DOWN
    LaunchedEffect(cloudOverWifiEnabled) {
        if (cloudOverWifiEnabled) cloudOverWifi.requestAttempt("settings")
    }
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp)) {
            SettingToggleRow(
                title = stringResource(R.string.settings_adb_restore_title),
                traceId = "adb_restore",
                description = stringResource(R.string.settings_adb_restore_desc),
                checked = adbRestoreEnabled && adbRestoreSupported,
                onCheckedChange = { enabled ->
                    adbRestoreEnabled = enabled
                    adbRestore.setEnabled(enabled)
                },
                enabled = adbRestoreSupported,
                // The help explains a working restore; on Android 10 the status line says why there is none.
                onHelp = if (adbRestoreSupported) ({ adbRestoreHelpOpen = true }) else null,
            )
            adbRestoreStatusText(adbRestoreState)?.let { SettingHint(text = it) }
            val verdict = adbVerdict
            if (adbChecking) {
                Text(
                    stringResource(R.string.adb_verdict_checking),
                    color = TextSecondary, fontSize = 12.5.sp,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            } else if (verdict != null && verdict != AdbVerdict.OK) {
                val link = stringResource(
                    if (verdict == AdbVerdict.NOT_ENABLED) R.string.adb_verdict_link_how
                    else R.string.adb_verdict_link_more
                )
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = adbVerdictColor(verdict))) { append(adbVerdictText(verdict)) }
                        append(" ")
                        withStyle(SpanStyle(color = AccentBlue, fontWeight = FontWeight.SemiBold)) { append(link) }
                    },
                    fontSize = 12.5.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { openAdbVerdict = verdict }
                        .padding(bottom = 8.dp),
                )
            }
            SettingDivider()
            SettingToggleRow(
                title = stringResource(R.string.settings_cloud_wifi_title),
                traceId = "cloud_over_wifi",
                description = stringResource(R.string.settings_cloud_wifi_desc),
                checked = cloudOverWifiEnabled,
                onCheckedChange = { enabled ->
                    cloudOverWifiEnabled = enabled
                    cloudOverWifi.setEnabled(enabled)
                },
                // Locked off without ADB; once on, switching off stays possible so the return
                // of the car's settings can wait for ADB to come back.
                enabled = cloudOverWifiEnabled || cloudAdbReady,
                onHelp = { cloudOverWifiHelpOpen = true },
            )
            // A known verdict only: while the check runs there is nothing true to say yet.
            val cloudLockedNoAdb = adbVerdict != null && !cloudAdbReady
            val cloudStatus = if (!cloudOverWifiEnabled && cloudLockedNoAdb &&
                cloudOverWifiState == CloudOverWifiState.Disabled
            ) {
                stringResource(R.string.settings_cloud_wifi_status_no_adb)
            } else {
                cloudOverWifiStatusText(cloudOverWifiState)
            }
            cloudStatus?.let { SettingHint(text = it) }
        }
    }
    val dialogVerdict = adbVerdict
    if (openAdbVerdict != null && dialogVerdict != null && dialogVerdict != AdbVerdict.OK) {
        AdbVerdictDialog(
            verdict = dialogVerdict,
            restoreEnabled = adbRestoreEnabled,
            restoreSupported = adbRestoreSupported,
            onCheck = { viewModel.recheckAdb() },
            onEnableRestore = {
                // Keep the toggle above in step with the dialog's switch-on.
                adbRestoreEnabled = true
                viewModel.enableAdbRestore()
            },
            onOpenDiagnostics = null,
            onDismiss = { openAdbVerdict = null },
        )
    }
    if (adbRestoreHelpOpen) {
        AppAlertDialog(
            onDismissRequest = { adbRestoreHelpOpen = false },
            containerColor = CardSurface,
            title = {
                Text(stringResource(R.string.settings_adb_restore_help_title), color = TextPrimary)
            },
            text = {
                Text(
                    stringResource(R.string.settings_adb_restore_help_body),
                    color = TextSecondary, fontSize = 14.sp, lineHeight = 19.sp,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { adbRestoreHelpOpen = false }) {
                    Text(stringResource(R.string.nav_autostart_dialog_button), color = AccentGreen)
                }
            },
        )
    }
    if (cloudOverWifiHelpOpen) {
        AppAlertDialog(
            onDismissRequest = { cloudOverWifiHelpOpen = false },
            containerColor = CardSurface,
            title = {
                Text(stringResource(R.string.settings_cloud_wifi_help_title), color = TextPrimary)
            },
            text = {
                Text(
                    stringResource(R.string.settings_cloud_wifi_help_body),
                    color = TextSecondary, fontSize = 14.sp, lineHeight = 19.sp,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { cloudOverWifiHelpOpen = false }) {
                    Text(stringResource(R.string.nav_autostart_dialog_button), color = AccentGreen)
                }
            },
        )
    }

    // Hidden BYD language dialog (UI7 only). We never write the locale ourselves — the button just
    // opens the factory dialog, and the card stays hidden on firmwares that do not ship it.
    val localeIntent = remember { Intent("android.settings.LOCALE_SETTINGS1") }
    val localeDialogAvailable = remember {
        context.packageManager.resolveActivity(localeIntent, 0) != null
    }
    if (localeDialogAvailable) {
        val localeUnavailableToast = stringResource(R.string.settings_car_language_unavailable)
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                SettingActionRow(
                    title = stringResource(R.string.settings_car_language_title),
                    description = stringResource(R.string.settings_car_language_desc),
                    buttonLabel = stringResource(R.string.settings_car_language_button),
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(localeIntent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }.onFailure {
                            Toast.makeText(context, localeUnavailableToast, Toast.LENGTH_LONG).show()
                        }
                    },
                )
            }
        }
    }

    // Time zone lock (#379): off by default; the switch follows what the helper confirmed.
    var timezoneLock by remember { mutableStateOf(viewModel.isTimezoneLockOn()) }
    var timezoneLockFailed by remember { mutableStateOf(false) }
    var timezoneLockBusy by remember { mutableStateOf(false) }
    val timezoneScope = rememberCoroutineScope()
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp)) {
            SettingToggleRow(
                title = stringResource(R.string.settings_timezone_lock_title),
                traceId = "timezone_lock",
                description = stringResource(R.string.settings_timezone_lock_desc),
                checked = timezoneLock,
                onCheckedChange = { on ->
                    timezoneLockBusy = true
                    timezoneScope.launch {
                        val ok = viewModel.setTimezoneLock(on)
                        if (ok) timezoneLock = on
                        timezoneLockFailed = !ok
                        timezoneLockBusy = false
                    }
                },
                enabled = !timezoneLockBusy,
            )
            if (timezoneLockFailed) SettingHint(text = stringResource(R.string.settings_timezone_lock_failed))
        }
    }

    // Autostart status card
    SectionHeader(text = stringResource(R.string.settings_autostart_header))
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            SettingStatusRow(
                title = if (state.lastBootInfo != null) {
                    stringResource(R.string.settings_autostart_recorded, state.lastBootInfo!!)
                } else {
                    stringResource(R.string.settings_autostart_not_recorded)
                },
                description = stringResource(R.string.settings_autostart_desc),
                ok = state.lastBootInfo != null,
                buttonLabel = stringResource(R.string.settings_autostart_open_button),
                onClick = { com.bydmate.app.util.AutostartScreen.open(context) },
            )
        }
    }

    // Data management card
    SectionHeader(text = stringResource(R.string.settings_app_data_header))
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Quiet foreground notification: the service picks the channel up on its next
            // notification refresh (a few seconds), no restart needed.
            var quietNotification by remember {
                mutableStateOf(clusterPrefs.getBoolean(com.bydmate.app.service.TrackingService.KEY_QUIET_NOTIFICATION, false))
            }
            SettingToggleRow(
                title = stringResource(R.string.settings_quiet_notification_title),
                traceId = "quiet_notification",
                description = stringResource(R.string.settings_quiet_notification_desc),
                checked = quietNotification,
                onCheckedChange = {
                    quietNotification = it
                    clusterPrefs.edit().putBoolean(com.bydmate.app.service.TrackingService.KEY_QUIET_NOTIFICATION, it).apply()
                },
            )
            SettingDivider()
            SettingSubhead(stringResource(R.string.settings_config_header))
            SettingActionRow(
                title = stringResource(R.string.settings_config_save_title),
                description = stringResource(R.string.settings_config_save_desc),
                buttonLabel = stringResource(R.string.settings_config_save_button),
                onClick = { showSaveDialog = true },
                style = SettingButtonStyle.Primary,
                enabled = !state.restoreInProgress,
            )
            SettingDivider()
            SettingActionRow(
                title = stringResource(R.string.settings_config_restore_button),
                description = stringResource(R.string.settings_config_restore_desc),
                buttonLabel = stringResource(R.string.settings_config_restore_pick_button),
                onClick = { viewModel.openRestorePicker() },
                enabled = !state.restoreInProgress,
            )
            if (state.configStatus != null) {
                SettingHint(
                    text = state.configStatus!!,
                )
            }
            AutoBackupRows(state, viewModel)
            SettingDivider()
            DiagnosticsRows(state, viewModel)
        }
    }

    // Diagnostic resets card
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            SettingActionRow(
                title = stringResource(R.string.settings_reset_seat_channel),
                description = stringResource(R.string.settings_reset_seat_channel_hint),
                buttonLabel = stringResource(R.string.settings_reset_seat_channel),
                onClick = { viewModel.resetSeatChannel() },
                style = SettingButtonStyle.Warning,
            )
            SettingDivider()
            SettingActionRow(
                title = stringResource(R.string.settings_reset_trip_source),
                description = stringResource(R.string.settings_reset_trip_source_hint),
                buttonLabel = stringResource(R.string.settings_reset_trip_source),
                onClick = { viewModel.resetTripSourceDetection() },
                style = SettingButtonStyle.Warning,
            )
        }
    }
}

/** Log recording, the fid recorder (test builds) and the fid catalog dump, each with a [?] (#238). */
@Composable
private fun DiagnosticsRows(state: SettingsUiState, viewModel: SettingsViewModel) {
    // Title and body of the open [?] dialog; null = closed.
    var help by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    help?.let { (title, body) ->
        AppAlertDialog(
            onDismissRequest = { help = null },
            containerColor = CardSurface,
            title = { Text(stringResource(title), color = TextPrimary) },
            text = {
                Text(
                    stringResource(body),
                    color = TextSecondary, fontSize = 14.sp, lineHeight = 19.sp,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { help = null }) {
                    Text(stringResource(R.string.nav_autostart_dialog_button), color = AccentGreen)
                }
            },
        )
    }
    SettingSubhead(stringResource(R.string.settings_diagnostics_header))
    SettingActionRow(
        title = stringResource(R.string.settings_log_recording_start_button),
        description = stringResource(R.string.settings_log_recording_desc),
        buttonLabel = if (state.isRecordingLogs) {
            stringResource(R.string.settings_log_recording_stop_button)
        } else {
            stringResource(R.string.settings_log_recording_start_button)
        },
        onClick = {
            if (state.isRecordingLogs) viewModel.stopLogRecording()
            else viewModel.startLogRecording()
        },
        // The saved log to the user's own bot, on a tap only: it holds trips and coordinates (#324).
        // Without a bot, the system share sheet (#380).
        secondButtonLabel = when {
            state.canSendLogToTelegram -> stringResource(R.string.settings_log_tg_send)
            state.canShareLog -> stringResource(R.string.settings_backup_share)
            else -> null
        },
        onSecondClick = { viewModel.sendLog() },
        secondButtonEnabled = !state.logSending,
        onHelp = { help = R.string.settings_log_recording_start_button to R.string.settings_log_recording_help },
    )
    if (state.logSaveStatus != null) {
        SettingHint(
            text = state.logSaveStatus!!,
        )
    }
    // Diagnostic fid recorder (-test/debug builds only): the daemon owns the run, this row
    // only reflects and toggles it, so a reopened screen still shows a recording in force.
    if (state.fidRecorderVisible) {
        SettingDivider()
        SettingToggleRow(
            title = stringResource(R.string.settings_fid_recorder_title),
            traceId = "fid_recorder",
            description = stringResource(R.string.settings_fid_recorder_desc),
            checked = state.fidRecorderRunning,
            onCheckedChange = { viewModel.setFidRecorder(it) },
            onHelp = { help = R.string.settings_fid_recorder_title to R.string.settings_fid_recorder_help },
        )
        if (state.fidRecorderError != null) {
            SettingHint(
                text = stringResource(R.string.settings_fid_recorder_error, state.fidRecorderError!!),
            )
        }
        if (state.fidRecorderFile != null) {
            SettingHint(
                text = stringResource(R.string.settings_fid_recorder_file, state.fidRecorderFile!!),
            )
        }
    }
    SettingDivider()
    SettingActionRow(
        title = stringResource(R.string.settings_fid_dump_button),
        description = stringResource(R.string.settings_fid_dump_desc),
        buttonLabel = stringResource(R.string.settings_config_save_button),
        onClick = { viewModel.dumpFids() },
        onHelp = { help = R.string.settings_fid_dump_button to R.string.settings_fid_dump_help },
    )
    if (state.fidDumpStatus != null) {
        SettingHint(
            text = state.fidDumpStatus!!,
        )
    }
}

/** «Проверка HUD»: about 90 s of test hints on the glass; the recorded log and a video of the
 *  glass tell which channel draws on this car. The row only starts the check and shows where it is. */
@Composable
internal fun HudCheckRow(onWayChosen: (Int) -> Unit) {
    val context = LocalContext.current
    val hudCheck = remember {
        EntryPointAccessors.fromApplication(context.applicationContext, ClusterEntryPoint::class.java).hudCheck()
    }
    val checkState by hudCheck.state.collectAsStateWithLifecycle()
    val askAnswer by hudCheck.askAnswer.collectAsStateWithLifecycle()
    val notice by hudCheck.notice.collectAsStateWithLifecycle()
    // After an answer: the way the check picked, 0 when the glass showed nothing.
    var answered by remember { mutableStateOf<Int?>(null) }
    if (askAnswer) {
        HudCheckQuestionDialog(
            onAnswer = { seen ->
                val way = hudCheck.answer(seen)
                way?.let(onWayChosen)
                answered = way ?: 0
            },
            onDismiss = { hudCheck.dismissAnswer() },
        )
    }
    answered?.let { way -> HudCheckAnswerDialog(way) { answered = null } }
    // The hint line under the row is easy to miss: a refusal or a route that ended the check is
    // also said once in a dialog.
    notice?.let { HudCheckNoticeDialog(it) { hudCheck.dismissNotice() } }
    val running = checkState is HudCheck.State.Preparing || checkState is HudCheck.State.Step ||
        checkState == HudCheck.State.Restoring
    SettingActionRow(
        title = stringResource(R.string.settings_hud_check_title),
        description = stringResource(R.string.settings_hud_check_desc),
        buttonLabel = stringResource(R.string.settings_hud_check_button),
        onClick = { hudCheck.start() },
        enabled = !running,
    )
    val hint = when (val s = checkState) {
        HudCheck.State.Idle -> null
        HudCheck.State.Preparing -> stringResource(R.string.settings_hud_check_preparing)
        is HudCheck.State.Step -> stringResource(R.string.settings_hud_check_step, s.number, HudCheck.STEPS)
        HudCheck.State.Restoring -> stringResource(R.string.settings_hud_check_restoring)
        HudCheck.State.Done -> stringResource(R.string.settings_hud_check_done)
        HudCheck.State.RouteStarted -> stringResource(R.string.settings_hud_check_route_started)
        is HudCheck.State.Refused -> stringResource(
            when (s.reason) {
                HudCheck.Refusal.GUIDANCE -> R.string.settings_hud_check_refused_guidance
                HudCheck.Refusal.MOVING -> R.string.settings_hud_check_refused_moving
                HudCheck.Refusal.NO_LINK -> R.string.settings_hud_check_refused_no_link
            }
        )
    }
    hint?.let { SettingHint(text = it) }
}

/** A hint line with a small orange tag after it (way 3 «пробный»). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun HudTrialHint(text: String, tag: String) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = text, color = TextMuted, fontSize = 12.sp, lineHeight = 16.sp)
        Text(
            text = tag,
            color = AccentOrange,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .border(1.dp, AccentOrange, RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

/** The end of a finished HUD check: which number the glass showed; the smallest picks the way. */
@Composable
private fun HudCheckQuestionDialog(onAnswer: (Int?) -> Unit, onDismiss: () -> Unit) {
    AppAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.settings_hud_answer_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.settings_hud_answer_text), color = TextSecondary, fontSize = 14.sp, lineHeight = 19.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HudCheck.MARKERS.forEach { marker ->
                        Button(
                            onClick = { onAnswer(marker) },
                            modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = NavyDark),
                        ) { Text(marker.toString(), fontSize = 20.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
                    }
                }
                OutlinedButton(
                    onClick = { onAnswer(null) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, CardBorder),
                ) { Text(stringResource(R.string.settings_hud_answer_none), color = TextPrimary, fontSize = 15.sp) }
            }
        },
        confirmButton = {},
    )
}

/** What the answer did: the way picked ([way] 1..3), or 0 = the glass showed nothing, way kept. */
@Composable
private fun HudCheckAnswerDialog(way: Int, onDone: () -> Unit) {
    AppAlertDialog(
        onDismissRequest = onDone,
        title = {
            Text(
                if (way > 0) stringResource(R.string.settings_hud_answer_chosen_title, way)
                else stringResource(R.string.settings_hud_answer_nothing_title)
            )
        },
        text = {
            Text(
                stringResource(
                    if (way > 0) R.string.settings_hud_answer_chosen_text else R.string.settings_hud_answer_nothing_text
                ),
                fontSize = 14.sp, lineHeight = 19.sp,
            )
        },
        confirmButton = {
            Button(
                onClick = onDone,
                modifier = Modifier.heightIn(min = 48.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = NavyDark),
            ) { Text(stringResource(R.string.settings_hud_answer_done), fontSize = 14.sp, fontWeight = FontWeight.Medium) }
        },
    )
}

/** Why the check did not run through, and what to do: [notice] is a refusal or a route that ended it. */
@Composable
private fun HudCheckNoticeDialog(notice: HudCheck.State, onDone: () -> Unit) {
    val text = when (notice) {
        HudCheck.State.RouteStarted -> R.string.settings_hud_check_notice_route_started
        is HudCheck.State.Refused -> when (notice.reason) {
            HudCheck.Refusal.GUIDANCE -> R.string.settings_hud_check_notice_guidance
            HudCheck.Refusal.MOVING -> R.string.settings_hud_check_notice_moving
            HudCheck.Refusal.NO_LINK -> R.string.settings_hud_check_refused_no_link
        }
        else -> return
    }
    AppAlertDialog(
        onDismissRequest = onDone,
        title = {
            Text(
                stringResource(
                    if (notice == HudCheck.State.RouteStarted) R.string.settings_hud_check_notice_interrupted_title
                    else R.string.settings_hud_check_notice_refused_title
                )
            )
        },
        text = { Text(stringResource(text), fontSize = 14.sp, lineHeight = 19.sp) },
        confirmButton = {
            Button(
                onClick = onDone,
                modifier = Modifier.heightIn(min = 48.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = NavyDark),
            ) { Text(stringResource(R.string.settings_hud_check_notice_ok), fontSize = 14.sp, fontWeight = FontWeight.Medium) }
        },
    )
}

/**
 * Automatic save (#237, #238): period and part chips with the last run, then the Telegram bot and
 * the power-off report it sends (3.19).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun AutoBackupRows(state: SettingsUiState, viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val tokenFocus = remember { FocusRequester() }
    val botBringer = remember { BringIntoViewRequester() }
    val periods = AutoBackupPeriod.entries
    val locale = LocalConfiguration.current.locales[0]
    val dateFormat = remember(locale) { SimpleDateFormat("d MMM HH:mm", locale) }
    SettingDivider()
    // Two halves instead of SettingChipRow: at the largest text size the chips wrap onto a second
    // line instead of scrolling the last one out of sight.
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                stringResource(R.string.settings_auto_backup_title),
                color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            )
            Text(
                stringResource(R.string.settings_auto_backup_desc),
                color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                text = when {
                    state.autoBackupLastTs > 0L -> stringResource(
                        R.string.settings_auto_backup_last,
                        dateFormat.format(Date(state.autoBackupLastTs)),
                        autoBackupResultText(context, state.autoBackupLastResult),
                    )
                    // A first run that failed to export has no timestamp but does have a reason.
                    state.autoBackupLastResult.isNotEmpty() -> autoBackupResultText(context, state.autoBackupLastResult)
                    else -> stringResource(R.string.settings_auto_backup_never)
                },
                color = TextPrimary, fontSize = 13.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            val periodLabels = listOf(
                stringResource(R.string.settings_auto_backup_off),
                stringResource(R.string.settings_auto_backup_daily),
                stringResource(R.string.settings_auto_backup_weekly),
                stringResource(R.string.settings_auto_backup_monthly),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
                periods.forEachIndexed { index, period ->
                    UnitChip(
                        label = periodLabels[index],
                        selected = period == state.autoBackupPeriod,
                        onClick = { viewModel.setAutoBackupPeriod(period) },
                    )
                }
            }
            Text(
                stringResource(R.string.settings_auto_backup_parts_label),
                color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp, textAlign = TextAlign.End,
                modifier = Modifier.padding(top = 8.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
                BackupPart.entries.forEach { part ->
                    val label = stringResource(
                        when (part) {
                            BackupPart.TABLES -> R.string.settings_backup_chip_tables
                            BackupPart.SETTINGS -> R.string.settings_backup_chip_settings
                            BackupPart.KEYS -> R.string.settings_backup_chip_keys
                        }
                    )
                    val selected = part in state.autoBackupParts
                    UnitChip(
                        label = if (selected) "✓ $label" else label,
                        selected = selected,
                        onClick = { viewModel.toggleAutoBackupPart(part) },
                    )
                }
            }
        }
    }
    SettingDivider()
    TelegramBotRows(state, viewModel, tokenFocus, botBringer)
    SettingDivider()
    PowerOffReportRows(state, viewModel, tokenFocus, botBringer)
    SettingDivider()
    // Needs the bot above too; the link to connect it already sits under the power-off report.
    val connected = state.tgBackupBinding != null
    SettingToggleRow(
        title = stringResource(R.string.settings_tg_log_auto_title),
        traceId = "tg_log_auto_send",
        description = stringResource(R.string.settings_tg_log_auto_hint),
        checked = connected && state.tgLogAutoSendEnabled,
        onCheckedChange = { viewModel.setTgLogAutoSendEnabled(it) },
        enabled = connected,
    )
}

/**
 * «Отчёт при выключении машины»: needs the bot above, so without it the switch is grey and a link
 * takes the driver to the token field. The items show only while the report is on.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PowerOffReportRows(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    tokenFocus: FocusRequester,
    botBringer: BringIntoViewRequester,
) {
    val connected = state.tgBackupBinding != null
    val scope = rememberCoroutineScope()
    SettingToggleRow(
        title = stringResource(R.string.settings_tg_report_off_title),
        traceId = "tg_report_off",
        description = stringResource(R.string.settings_tg_report_off_hint),
        checked = connected && state.tgReportOffEnabled,
        onCheckedChange = { viewModel.setTgReportOffEnabled(it) },
        enabled = connected,
    )
    if (!connected) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable {
                    scope.launch {
                        botBringer.bringIntoView()
                        // The token field exists only on the first step; on the code step the block is enough.
                        if (state.tgBackupCode == null) tokenFocus.requestFocus()
                    }
                }
                .padding(bottom = 4.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                stringResource(R.string.settings_tg_report_off_no_bot),
                color = TextSecondary, fontSize = 12.5.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(end = 4.dp),
            )
            Text(
                stringResource(R.string.settings_tg_report_off_connect),
                color = AccentBlue, fontSize = 12.5.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold,
            )
        }
    } else if (state.tgReportOffEnabled) {
        Text(
            stringResource(R.string.settings_tg_report_items_label),
            color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(bottom = 6.dp),
        ) {
            ReportField.entries.forEach { field ->
                val label = stringResource(field.labelRes)
                val selected = field in state.tgReportOffFields
                UnitChip(
                    label = if (selected) "✓ $label" else label,
                    selected = selected,
                    onClick = { viewModel.toggleTgReportOffField(field) },
                )
            }
        }
    }
}

/** Telegram bot as a three-step stepper: token, code sent to the bot, connected (#238). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TelegramBotRows(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    tokenFocus: FocusRequester,
    botBringer: BringIntoViewRequester,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val copiedToast = stringResource(R.string.settings_tg_backup_code_copied)
    val binding = state.tgBackupBinding
    val code = state.tgBackupCode
    val step = when {
        binding != null -> 3
        code != null -> 2
        else -> 1
    }
    Column(
        modifier = Modifier.fillMaxWidth().bringIntoViewRequester(botBringer).padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            stringResource(R.string.settings_tg_backup_title),
            color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
        )
        TelegramStepper(step)
        when {
            binding != null -> {
                Text(
                    "✓ " + stringResource(R.string.settings_tg_backup_connected_chip),
                    color = AccentGreen, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .background(AccentGreen.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
                Text(
                    stringResource(R.string.settings_tg_backup_chat_line, binding.botName, binding.chatName),
                    color = TextPrimary, fontSize = 14.sp,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.settings_tg_backup_connected_note),
                        color = TextMuted, fontSize = 12.sp, lineHeight = 16.sp,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { viewModel.disconnectTelegramBackup() }) {
                        Text(stringResource(R.string.settings_tg_backup_disconnect), color = TextSecondary, fontSize = 13.sp)
                    }
                }
            }
            code != null -> {
                Text(
                    code,
                    color = TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace, letterSpacing = 6.sp,
                    modifier = Modifier
                        .background(NavyDark, RoundedCornerShape(10.dp))
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                )
                Text(
                    stringResource(R.string.settings_tg_backup_code_instruction, state.tgBackupBotName),
                    color = TextSecondary, fontSize = 13.sp, lineHeight = 18.sp,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(code))
                            Toast.makeText(context, copiedToast, Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, CardBorder),
                    ) { Text(stringResource(R.string.settings_tg_backup_copy_code), color = TextPrimary, fontSize = 13.sp) }
                    TelegramCheckButton(state, viewModel)
                    TextButton(onClick = { viewModel.cancelTelegramCode() }) {
                        Text(stringResource(R.string.settings_tg_backup_back), color = TextSecondary, fontSize = 13.sp)
                    }
                }
            }
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.weight(1f)) {
                        SettingsTextField(
                            label = stringResource(R.string.settings_tg_backup_token_label),
                            value = state.tgBackupToken,
                            onValueChange = { viewModel.updateTgBackupToken(it) },
                            keyboardType = KeyboardType.Password,
                            secret = true,
                            modifier = Modifier.focusRequester(tokenFocus),
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    TelegramCheckButton(state, viewModel)
                }
                Text(
                    stringResource(R.string.settings_tg_backup_hint),
                    color = TextMuted, fontSize = 12.sp, lineHeight = 16.sp,
                )
            }
        }
        // Progress and errors right under the controls, in colour: easy to miss as a grey hint.
        state.tgBackupStatus?.takeIf { step != 3 }?.let { status ->
            Text(
                status,
                color = if (state.tgBackupChecking) TextSecondary else AccentOrange,
                fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun TelegramCheckButton(state: SettingsUiState, viewModel: SettingsViewModel) {
    Button(
        onClick = { viewModel.checkTelegramBackup() },
        enabled = !state.tgBackupChecking && state.tgBackupToken.isNotBlank(),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = NavyDark),
    ) { Text(stringResource(R.string.settings_tg_backup_check), fontSize = 13.sp, fontWeight = FontWeight.Medium) }
}

/** «1 Токен — 2 Код — 3 Готово»; steps before [current] show a check mark. */
@Composable
private fun TelegramStepper(current: Int) {
    val labels = listOf(
        stringResource(R.string.settings_tg_backup_step_token),
        stringResource(R.string.settings_tg_backup_step_code),
        stringResource(R.string.settings_tg_backup_step_done),
    )
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        labels.forEachIndexed { index, label ->
            val step = index + 1
            val active = step == current
            val done = step < current || (active && step == labels.size)
            if (index > 0) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                        .height(1.dp)
                        .background(if (step <= current) AccentGreen else CardBorder),
                )
            }
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(if (active || done) AccentGreen else CardBorder, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (done) "✓" else step.toString(),
                    color = if (active || done) NavyDark else TextSecondary,
                    fontSize = 12.sp, fontWeight = FontWeight.Bold,
                )
            }
            Text(
                label,
                color = if (active) TextPrimary else TextSecondary,
                fontSize = 13.sp, fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

/**
 * «Что сохранить» / «Что восстановить» (#238): a checkbox per part, [available] parts only; at least
 * one must stay checked. Scrolls, so the buttons stay reachable at the largest text size.
 * [busy] = a restore runs, the confirm button stays disabled.
 */
@Composable
private fun BackupPartsDialog(
    title: String,
    initial: Set<BackupPart>,
    available: Set<BackupPart>,
    confirmLabel: String,
    confirmColor: Color,
    warning: String?,
    busy: Boolean,
    onConfirm: (Set<BackupPart>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(initial intersect available) }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardSurfaceElevated,
        title = { Text(title, color = TextPrimary) },
        text = {
            Box(modifier = Modifier.heightIn(max = 400.dp)) {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    BackupPart.entries.forEach { part ->
                        val enabled = part in available
                        val (titleRes, descRes) = when (part) {
                            BackupPart.TABLES -> R.string.settings_backup_part_tables to R.string.settings_backup_part_tables_desc
                            BackupPart.SETTINGS -> R.string.settings_backup_part_settings to R.string.settings_backup_part_settings_desc
                            BackupPart.KEYS -> R.string.settings_backup_part_keys to R.string.settings_backup_part_keys_desc
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = enabled) {
                                    selected = if (part in selected) selected - part else selected + part
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = part in selected,
                                onCheckedChange = null,
                                enabled = enabled,
                                colors = CheckboxDefaults.colors(
                                    checkedColor = AccentGreen,
                                    uncheckedColor = CardBorder,
                                    checkmarkColor = NavyDark,
                                ),
                            )
                            Column(modifier = Modifier.padding(start = 10.dp)) {
                                Text(
                                    stringResource(titleRes),
                                    color = if (enabled) TextPrimary else TextMuted,
                                    fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    stringResource(if (enabled) descRes else R.string.settings_backup_part_absent),
                                    color = if (enabled) TextSecondary else TextMuted,
                                    fontSize = 12.sp, lineHeight = 16.sp,
                                )
                            }
                        }
                    }
                    if (warning != null) {
                        Text(
                            warning,
                            color = AccentOrange, fontSize = 13.sp, lineHeight = 18.sp,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            val canConfirm = selected.isNotEmpty() && !busy
            TextButton(onClick = { onConfirm(selected) }, enabled = canConfirm) {
                Text(confirmLabel, color = if (canConfirm) confirmColor else TextMuted)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_config_restore_confirm_cancel), color = TextSecondary)
            }
        },
    )
}

/** «Сохранено»: file name and size, the Telegram outcome, «Поделиться» and «Закрыть». */
@Composable
private fun SavedBackupDialog(saved: SavedBackup, withKeys: Boolean, onShare: () -> Unit, onDismiss: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val sizeMb = remember(saved.file, locale) {
        String.format(locale, "%.1f", saved.file.length() / (1024.0 * 1024.0))
    }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardSurfaceElevated,
        title = { Text(stringResource(R.string.settings_config_saved_title), color = TextPrimary) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.settings_config_saved_file, saved.file.name, sizeMb), color = TextPrimary)
                if (saved.telegramStatus != null) {
                    Text(saved.telegramStatus, color = TextPrimary, fontSize = 14.sp)
                }
                if (withKeys) {
                    Text(stringResource(R.string.settings_backup_share_hint), color = TextSecondary, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_backup_close), color = PrimaryColor)
            }
        },
        dismissButton = {
            TextButton(onClick = onShare) {
                Text(stringResource(R.string.settings_backup_share), color = TextSecondary)
            }
        },
    )
}
