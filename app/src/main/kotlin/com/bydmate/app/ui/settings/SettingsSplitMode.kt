package com.bydmate.app.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bydmate.app.R
import com.bydmate.app.cluster.ClusterEntryPoint
import com.bydmate.app.cluster.ClusterProjectionManager
import com.bydmate.app.ui.theme.CardSurfaceElevated
import dagger.hilt.android.EntryPointAccessors

/**
 * «Разделение экрана» of the Android 10 head units (DiLink 3 / 4): which of the two methods works,
 * «Основной» (the split section of every firmware) or «Альтернативный» (the app sets, through the
 * firmware's own split), and «Открывать при запуске» for the chosen one. Only the chosen method's
 * settings are shown under it. The choice is the split master switch
 * ([com.bydmate.app.split.appSetsActive]): the widget tap, the split key, the voice and the start
 * all follow it.
 */
@Composable
internal fun SplitModeSection(viewModel: SettingsViewModel, setsViewModel: AppSetsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val entryPoint = remember {
        EntryPointAccessors.fromApplication(context.applicationContext, ClusterEntryPoint::class.java)
    }
    val mainOn by setsViewModel.splitOn.collectAsStateWithLifecycle()
    var autostart by remember { mutableStateOf(setsViewModel.autostartEnabled()) }

    SectionHeader(text = stringResource(R.string.settings_section_split_title))
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingChipRow(
                title = stringResource(R.string.settings_split_mode_title),
                description = stringResource(R.string.settings_split_mode_desc),
                options = listOf(
                    stringResource(R.string.settings_split_mode_main),
                    stringResource(R.string.settings_split_mode_alternative),
                ),
                selectedIndex = if (mainOn) 0 else 1,
                onSelect = { index ->
                    val main = index == 0
                    if (main != mainOn) setMainSplitMethod(context, entryPoint, main)
                },
            )
            SettingDivider()
            SettingToggleRow(
                title = stringResource(R.string.settings_split_autostart_title),
                description = stringResource(R.string.settings_split_autostart_desc),
                checked = autostart,
                onCheckedChange = { autostart = it; setsViewModel.setAutostart(it) },
                traceId = "split_autostart",
            )
        }
    }
    if (mainOn) SplitSection(viewModel, embedded = true) else AppSetsSection(setsViewModel)
}

/** The same steps as the master switch of [SplitSection], which this choice replaces on Android 10. */
private fun setMainSplitMethod(context: Context, entryPoint: ClusterEntryPoint, enabled: Boolean) {
    entryPoint.splitPreferences().setFeatureEnabled(enabled)
    if (enabled) {
        ClusterProjectionManager.armSplitRebootHintIfNeeded(context, splitEnabled = true)
    } else {
        ClusterProjectionManager.clearSplitRebootHint(context)
    }
    // force on the way off, as the switch does: the flag is written back even without a transport.
    ClusterProjectionManager.realignFreeformFlag(
        context, entryPoint.helperClient(), entryPoint.helperBootstrap(), force = !enabled,
    )
}
