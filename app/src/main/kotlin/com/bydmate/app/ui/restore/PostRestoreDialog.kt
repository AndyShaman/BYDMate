package com.bydmate.app.ui.restore

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bydmate.app.R
import com.bydmate.app.data.backup.PostRestoreItem
import com.bydmate.app.data.backup.PostRestoreNotice
import com.bydmate.app.ui.components.AppAlertDialog
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.CardSurface
import com.bydmate.app.ui.theme.TextPrimary
import com.bydmate.app.ui.theme.TextSecondary
import com.bydmate.app.voice.TtsVoiceCatalog

/** One dialog after a restore: only what still needs the user, one row per item. */
@Composable
fun PostRestoreDialog(viewModel: PostRestoreViewModel) {
    val report by viewModel.report.collectAsState()
    val asr by viewModel.asr.collectAsState()
    val tts by viewModel.tts.collectAsState()
    val current = report ?: return
    if (current.isEmpty) return

    val context = LocalContext.current
    // The overlay screen is opened like Settings does (no activity result on DiLink is relied on):
    // coming back to the app resumes the host, and that is when the grant is re-probed.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.recheck()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.recheck()
    }

    AppAlertDialog(
        onDismissRequest = {},
        containerColor = CardSurface,
        title = { Text(stringResource(R.string.post_restore_title), color = TextPrimary, fontSize = 16.sp) },
        text = {
            // Scrolls: with the largest text size all rows no longer fit a 1200 px tall screen.
            Box(modifier = Modifier.heightIn(max = 400.dp)) {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.post_restore_intro), color = TextSecondary, fontSize = 13.sp)
                    current.items.forEach { item ->
                        when (item) {
                            PostRestoreItem.Overlay -> ActionRow(
                                stringResource(R.string.post_restore_overlay),
                                stringResource(R.string.post_restore_allow),
                            ) {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}"),
                                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                                runCatching { context.startActivity(intent) }
                                    .onFailure { Log.w("PostRestoreDialog", "overlay screen: ${it.message}") }
                            }
                            PostRestoreItem.Mic -> ActionRow(
                                stringResource(R.string.post_restore_mic),
                                stringResource(R.string.post_restore_allow),
                            ) { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                            PostRestoreItem.Contacts -> ActionRow(
                                stringResource(R.string.post_restore_contacts),
                                stringResource(R.string.post_restore_allow),
                            ) { permissionLauncher.launch(Manifest.permission.READ_CONTACTS) }
                            PostRestoreItem.AsrModel -> DownloadRow(
                                stringResource(R.string.post_restore_asr),
                                stringResource(R.string.post_restore_download_asr),
                                asr,
                                asr.shortfall?.let {
                                    stringResource(R.string.settings_asr_gigaam_no_space, it.requiredMb, it.availableMb)
                                } ?: stringResource(R.string.settings_asr_gigaam_download_failed),
                            ) { viewModel.downloadAsr() }
                            is PostRestoreItem.TtsVoice -> DownloadRow(
                                stringResource(
                                    R.string.post_restore_tts,
                                    stringResource(TtsVoiceCatalog.byId(item.voiceId).labelRes),
                                ),
                                stringResource(R.string.post_restore_download),
                                tts,
                                stringResource(R.string.settings_tts_download_failed),
                            ) { viewModel.downloadTts(item.voiceId) }
                        }
                    }
                    if (PostRestoreNotice.REBOOT in current.notices) {
                        Text(stringResource(R.string.post_restore_reboot), color = TextPrimary, fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.dismiss() }) {
                Text(stringResource(R.string.post_restore_close), color = TextSecondary)
            }
        },
    )
}

@Composable
private fun ActionRow(label: String, button: String, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(end = 8.dp))
        TextButton(onClick = onClick) { Text(button, color = AccentGreen) }
    }
}

@Composable
private fun DownloadRow(
    label: String,
    button: String,
    download: PostRestoreDownload,
    failedText: String,
    onClick: () -> Unit,
) {
    val percent = download.percent
    if (percent == null) {
        Column {
            ActionRow(label, button, onClick)
            if (download.failed) Text(failedText, color = TextSecondary, fontSize = 12.sp)
        }
    } else {
        val progress = if (download.unpacking) {
            stringResource(R.string.settings_voice_model_unpacking, percent)
        } else {
            stringResource(R.string.settings_voice_model_downloading, percent)
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(end = 8.dp))
            Text(progress, color = TextSecondary, fontSize = 12.sp)
        }
    }
}
