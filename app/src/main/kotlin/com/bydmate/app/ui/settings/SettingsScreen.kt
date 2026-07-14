package com.bydmate.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import com.bydmate.app.cluster.ClusterEntryPoint
import com.bydmate.app.cluster.ClusterProjectionManager
import com.bydmate.app.cluster.CENTER_OFFSET_PCT
import com.bydmate.app.cluster.MAX_OFFSET_PCT
import com.bydmate.app.cluster.MAX_PROJECTION_PCT
import com.bydmate.app.cluster.MIN_OFFSET_PCT
import com.bydmate.app.cluster.MIN_PROJECTION_PCT
import com.bydmate.app.cluster.MIN_SCALE_PCT
import com.bydmate.app.cluster.MAX_SCALE_PCT
import com.bydmate.app.cluster.DEFAULT_SCALE_PCT
import com.bydmate.app.cluster.NAVI_PACKAGE
import dagger.hilt.android.EntryPointAccessors
import kotlin.math.roundToInt
import com.bydmate.app.ui.widget.WidgetController
import com.bydmate.app.ui.widget.WidgetPreferences
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
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.font.FontFamily
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.horizontalScroll
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import com.bydmate.app.cluster.DEFAULT_TRIGGER_KEYCODE
import com.bydmate.app.cluster.SteeringWheelKeyService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import com.bydmate.app.R
import com.bydmate.app.data.remote.OpenRouterModel
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.ui.components.AppLaunchPickerDialog
import com.bydmate.app.ui.components.bydSwitchColors
import com.bydmate.app.ui.theme.*
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.compose.material.icons.outlined.Mic
import com.bydmate.app.cluster.DEFAULT_VOICE_KEYCODE
import com.bydmate.app.voice.AgentPersona
import com.bydmate.app.voice.TtsGender
import com.bydmate.app.voice.TtsVoiceCatalog
import com.bydmate.app.voice.online.TtsRouter
import java.util.Locale

private enum class SettingsSection(@StringRes val labelRes: Int, val icon: ImageVector) {
    VOICE(R.string.settings_section_voice_agent, Icons.Outlined.Mic),
    WIDGET(R.string.settings_section_widget_title, Icons.Outlined.PhoneAndroid),
    DISPLAY(R.string.settings_section_display_title, Icons.Outlined.DirectionsCar),
    BATTERY(R.string.settings_section_auto_battery_title, Icons.Outlined.BatteryChargingFull),
    PLACES(R.string.settings_section_places_title, Icons.Outlined.Place),
    INTEGRATIONS(R.string.settings_section_integrations_title, Icons.Outlined.Link),
    SERVICE(R.string.settings_section_service_title, Icons.Outlined.Build),
    APP(R.string.settings_section_application_title, Icons.Outlined.Settings),
    SMART_HOME(R.string.settings_smart_home_section_title, Icons.Outlined.Home),
}

private val PrimaryColor = AccentGreen

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateToAgentChat: () -> Unit = {},
    onNavigateToVoiceJournal: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Recalculate confirmation dialog
    if (state.showRecalcConfirm) {
        val tariffLabel = when (state.tripCostTariff) {
            "home" -> state.homeTariff
            "dc" -> state.dcTariff
            else -> state.tripCostTariff
        }
        AlertDialog(
            onDismissRequest = { viewModel.hideRecalcConfirm() },
            title = { Text(stringResource(R.string.settings_recalc_dialog_title), color = TextPrimary) },
            text = {
                Text(
                    stringResource(R.string.settings_recalc_dialog_text, tariffLabel, state.currencySymbol),
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmRecalc() }) {
                    Text(stringResource(R.string.settings_recalc_confirm_button), color = AccentOrange)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.hideRecalcConfirm() }) {
                    Text(stringResource(R.string.settings_cancel_button), color = TextSecondary)
                }
            },
            containerColor = CardSurface
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
                        SettingsSection.BATTERY -> BatterySection(state, viewModel)
                        SettingsSection.INTEGRATIONS -> IntegrationsSection(state, viewModel)
                        SettingsSection.VOICE -> VoiceSettingsContent(state, viewModel, onNavigateToVoiceJournal, onNavigateToAgentChat)
                        SettingsSection.WIDGET -> WidgetSection()
                        SettingsSection.DISPLAY -> DisplaySection()
                        SettingsSection.PLACES -> PlacesSection()
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

            Spacer(modifier = Modifier.weight(1f))
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
private fun BatterySection(state: SettingsUiState, viewModel: SettingsViewModel) {
    SectionHeader(text = stringResource(R.string.settings_battery_section_header))
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SettingsTextField(
                label = stringResource(R.string.settings_battery_capacity_label),
                value = state.batteryCapacity,
                onValueChange = { viewModel.saveBatteryCapacity(it) },
                keyboardType = KeyboardType.Decimal
            )
            SettingHint(stringResource(R.string.settings_battery_capacity_desc))
            SettingDivider()
            SettingsTextField(
                label = stringResource(R.string.settings_tariff_home_label, state.currencySymbol),
                value = state.homeTariff,
                onValueChange = { viewModel.updateHomeTariff(it) },
                keyboardType = KeyboardType.Decimal
            )
            SettingHint(stringResource(R.string.settings_tariff_home_desc))
            SettingDivider()
            SettingsTextField(
                label = stringResource(R.string.settings_tariff_dc_label, state.currencySymbol),
                value = state.dcTariff,
                onValueChange = { viewModel.updateDcTariff(it) },
                keyboardType = KeyboardType.Decimal
            )
            SettingHint(stringResource(R.string.settings_tariff_dc_desc))
            SettingDivider()
            val customChipLabel = stringResource(R.string.settings_tariff_trip_custom_chip)
            val tariffOptions = listOf("AC", "DC", customChipLabel)
            val tariffSelectedIndex = when {
                state.tripCostTariff == "home" -> 0
                state.tripCostTariff == "dc" -> 1
                else -> 2
            }
            SettingChipRow(
                title = stringResource(R.string.settings_tariff_trip_label),
                description = stringResource(R.string.settings_tariff_trip_desc),
                options = tariffOptions,
                selectedIndex = tariffSelectedIndex,
                onSelect = { index ->
                    when (index) {
                        0 -> viewModel.saveTripCostTariff("home")
                        1 -> viewModel.saveTripCostTariff("dc")
                        else -> viewModel.saveTripCostTariff(state.homeTariff)
                    }
                }
            )
            if (state.tripCostTariff != "home" && state.tripCostTariff != "dc") {
                SettingsTextField(
                    label = stringResource(R.string.settings_tariff_custom_label, state.currencySymbol),
                    value = state.tripCostTariff,
                    onValueChange = { viewModel.saveTripCostTariff(it) },
                    keyboardType = KeyboardType.Decimal
                )
            }
            SettingDivider()
            SettingActionRow(
                title = stringResource(R.string.settings_save_tariffs_button),
                description = stringResource(R.string.settings_tariff_future_note),
                buttonLabel = stringResource(R.string.settings_save_tariffs_button),
                onClick = { viewModel.saveTariffs() },
                style = SettingButtonStyle.Primary
            )
            state.tariffSaveStatus?.let {
                Text(it, color = AccentGreen, fontSize = 12.sp)
            }
            SettingDivider()
            SettingActionRow(
                title = stringResource(R.string.settings_recalc_all_button),
                description = stringResource(R.string.settings_recalc_note),
                buttonLabel = stringResource(R.string.settings_recalc_all_button),
                onClick = { viewModel.showRecalcConfirm() },
                style = SettingButtonStyle.Warning
            )
            state.recalcStatus?.let {
                Text(it, color = AccentGreen, fontSize = 12.sp)
            }
        }
    }

    SectionHeader(text = stringResource(R.string.settings_consumption_section_header))
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SettingsTextField(
                label = stringResource(R.string.settings_consumption_good_label),
                value = state.consumptionGood,
                onValueChange = { viewModel.saveConsumptionGood(it) },
                keyboardType = KeyboardType.Decimal
            )
            SettingHint(stringResource(R.string.settings_consumption_good_desc))
            SettingDivider()
            SettingsTextField(
                label = stringResource(R.string.settings_consumption_bad_label),
                value = state.consumptionBad,
                onValueChange = { viewModel.saveConsumptionBad(it) },
                keyboardType = KeyboardType.Decimal
            )
            SettingHint(stringResource(R.string.settings_consumption_bad_desc))
        }
    }
}

@Composable
private fun IntegrationsSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    SectionHeader(text = stringResource(R.string.settings_abrp_section_header))
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SettingToggleRow(
                title = stringResource(R.string.settings_abrp_telemetry_label),
                description = stringResource(R.string.settings_abrp_telemetry_description),
                checked = state.abrpTelemetryEnabled,
                onCheckedChange = { viewModel.toggleAbrpTelemetry(it) },
            )
            SettingsTextField(
                label = stringResource(R.string.settings_abrp_token_label),
                value = state.abrpUserToken,
                onValueChange = { viewModel.updateAbrpUserToken(it) },
                keyboardType = KeyboardType.Password
            )
            SettingActionRow(
                title = stringResource(R.string.settings_abrp_save_button),
                buttonLabel = stringResource(R.string.settings_abrp_save_button),
                onClick = { viewModel.saveAbrpSettings() },
                style = SettingButtonStyle.Primary,
            )
            state.abrpSaveStatus?.let {
                Text(it, color = AccentGreen, fontSize = 12.sp)
            }
        }
    }

    SectionHeader(text = stringResource(R.string.settings_ai_connections_header))

    ConnectionCard(
        title = "OpenRouter",
        configured = state.openRouterConfigured,
        testResult = state.connTestResults["openrouter"],
        testRunning = state.connTestRunning == "openrouter",
        onTest = { viewModel.testConnection("openrouter") },
    ) {
        SettingsTextField(
            label = stringResource(R.string.settings_conn_api_key_label),
            value = state.openRouterApiKey,
            onValueChange = { viewModel.saveOpenRouterApiKey(it) },
            keyboardType = KeyboardType.Password
        )
        SettingActionRow(
            title = stringResource(R.string.settings_openrouter_model_pick),
            buttonLabel = if (state.openRouterModelName.isNotBlank())
                stringResource(R.string.settings_openrouter_model_selected, state.openRouterModelName)
            else stringResource(R.string.settings_openrouter_model_pick),
            onClick = { viewModel.showModelPicker() },
            enabled = state.openRouterApiKey.isNotBlank(),
        )
    }

    ConnectionCard(
        title = "z.ai",
        configured = state.zaiConfigured,
        testResult = state.connTestResults["zai"],
        testRunning = state.connTestRunning == "zai",
        onTest = { viewModel.testConnection("zai") },
    ) {
        SettingsTextField(
            label = stringResource(R.string.settings_conn_api_key_label),
            value = state.zaiApiKey,
            onValueChange = { viewModel.saveZaiApiKey(it) },
            keyboardType = KeyboardType.Password
        )
        SettingHint(stringResource(R.string.settings_zai_hint))
    }

    ConnectionCard(
        title = stringResource(R.string.settings_conn_custom_title),
        configured = state.customConfigured,
        testResult = state.connTestResults["custom"],
        testRunning = state.connTestRunning == "custom",
        onTest = { viewModel.testConnection("custom") },
    ) {
        var presetHint by remember { mutableStateOf<Int?>(null) }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            CUSTOM_PRESETS.forEach { preset ->
                UnitChip(
                    label = preset.name,
                    selected = state.customName == preset.name,
                    onClick = {
                        viewModel.applyCustomPreset(preset.name, preset.baseUrl, preset.model)
                        presetHint = preset.hintRes
                    }
                )
            }
        }
        SettingHint(stringResource(presetHint ?: R.string.settings_custom_hint))
        SettingsTextField(
            label = stringResource(R.string.settings_conn_name_label),
            value = state.customName,
            onValueChange = { viewModel.saveCustomName(it) },
            keyboardType = KeyboardType.Text
        )
        SettingsTextField(
            label = stringResource(R.string.settings_conn_base_url_label),
            value = state.customBaseUrl,
            onValueChange = { viewModel.saveCustomBaseUrl(it) },
            keyboardType = KeyboardType.Uri
        )
        SettingsTextField(
            label = stringResource(R.string.settings_conn_api_key_label),
            value = state.customApiKey,
            onValueChange = { viewModel.saveCustomApiKey(it) },
            keyboardType = KeyboardType.Password
        )
        SettingsTextField(
            label = stringResource(R.string.settings_conn_model_label),
            value = state.customModel,
            onValueChange = { viewModel.saveCustomModel(it) },
            keyboardType = KeyboardType.Text
        )
        SettingActionRow(
            title = stringResource(R.string.settings_custom_list_models),
            buttonLabel = stringResource(R.string.settings_custom_list_models),
            onClick = { viewModel.loadCustomModels() },
            enabled = state.customBaseUrl.isNotBlank() && state.customApiKey.isNotBlank(),
        )
        state.customModelsError?.let {
            Text(it, color = SocRed, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }

    // Custom model picker dialog
    if (state.showCustomModelPicker) {
        CustomModelPickerDialog(
            models = state.customModelList,
            loading = state.customModelsLoading,
            selectedId = state.customModel,
            onSelect = { id ->
                viewModel.saveCustomModel(id)
                viewModel.hideCustomModelPickerDialog()
            },
            onDismiss = { viewModel.hideCustomModelPickerDialog() }
        )
    }

    ModelSelectionCard(state, viewModel)
    SearchStatusCard(state)

    SectionHeader(text = stringResource(R.string.settings_exa_section_header))
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SettingsTextField(
                label = stringResource(R.string.agent_exa_api_key_label),
                value = state.exaApiKey,
                onValueChange = { viewModel.saveExaApiKey(it) },
                keyboardType = KeyboardType.Password,
            )
            SettingHint(stringResource(R.string.settings_exa_search_hint))
        }
    }

    // Model picker dialog
    if (state.showModelPicker) {
        ModelPickerDialog(
            models = state.availableModels,
            loading = state.modelsLoading,
            selectedId = state.openRouterModel,
            onSelect = { viewModel.selectModel(it) },
            onDismiss = { viewModel.hideModelPicker() }
        )
    }
}

@Composable
private fun WidgetSection() {
    val context = LocalContext.current
    val prefs = remember { WidgetPreferences(context) }
    val enabled by prefs.enabledFlow().collectAsStateWithLifecycle(initialValue = prefs.isEnabled())
    val alpha by prefs.alphaFlow().collectAsStateWithLifecycle(initialValue = prefs.getAlpha())
    val scale by prefs.scaleFlow().collectAsStateWithLifecycle(initialValue = prefs.getScale())
    val leftTapApp by prefs.leftTapAppFlow().collectAsStateWithLifecycle(
        initialValue = WidgetPreferences.LeftTapAppState(
            enabled = prefs.isLeftTapZoningEnabled(),
            packageName = prefs.getLeftTapAppPackage(),
            label = prefs.getLeftTapAppLabel(),
        ),
    )
    val buttonsEnabled by prefs.buttonsEnabledFlow().collectAsStateWithLifecycle(
        initialValue = prefs.isButtonsEnabled(),
    )
    val hideOnYoutube by prefs.hideOnYoutubeFlow()
        .collectAsStateWithLifecycle(initialValue = prefs.isHideOnYoutube())
    var showLeftTapPicker by remember { mutableStateOf(false) }

    SectionHeader(text = stringResource(R.string.settings_widget_section_header))
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SettingToggleRow(
                title = stringResource(R.string.settings_widget_show_soc_label),
                description = stringResource(R.string.settings_widget_show_desc),
                checked = enabled,
                onCheckedChange = { requested ->
                    if (requested) {
                        if (AndroidSettings.canDrawOverlays(context)) {
                            prefs.setEnabled(true)
                            WidgetController.attach(context)
                        } else {
                            val intent = Intent(
                                AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                            context.startActivity(intent)
                        }
                    } else {
                        prefs.setEnabled(false)
                        WidgetController.detach()
                    }
                },
            )
            SettingHint(text = stringResource(R.string.settings_widget_hints))
            SettingToggleRow(
                title = stringResource(R.string.settings_widget_hide_youtube_label),
                description = stringResource(R.string.settings_widget_hide_youtube_description),
                checked = hideOnYoutube,
                onCheckedChange = { prefs.setHideOnYoutube(it) },
            )
            SettingSliderRow(
                title = stringResource(R.string.settings_widget_opacity_label),
                value = alpha,
                onValueChange = {
                    if (enabled) WidgetController.setPreviewMode(context, true)
                    prefs.setAlpha(it)
                },
                valueRange = 0.3f..1.0f,
                valueLabel = "${(alpha * 100).toInt()}%",
                enabled = enabled,
            )
            SettingSliderRow(
                title = stringResource(R.string.settings_widget_scale_label),
                value = scale,
                onValueChange = {
                    if (enabled) WidgetController.setPreviewMode(context, true)
                    prefs.setScale(it)
                },
                valueRange = WidgetPreferences.SCALE_MIN..WidgetPreferences.SCALE_MAX,
                valueLabel = "${(scale * 100).toInt()}%",
                enabled = enabled,
            )
            SettingActionRow(
                title = stringResource(R.string.settings_widget_reset_position_button),
                description = stringResource(R.string.settings_widget_reset_position_desc),
                buttonLabel = stringResource(R.string.settings_widget_reset_position_button),
                onClick = {
                    prefs.resetPosition()
                    if (enabled && AndroidSettings.canDrawOverlays(context)) {
                        WidgetController.detach()
                        WidgetController.attach(context)
                    }
                },
                style = SettingButtonStyle.Secondary,
                enabled = enabled,
            )
        }
    }

    SectionHeader(text = stringResource(R.string.settings_widget_tap_section_header))
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SettingToggleRow(
                title = stringResource(R.string.settings_widget_left_tap_zoning_label),
                description = stringResource(R.string.settings_widget_left_tap_zoning_description),
                checked = leftTapApp.enabled,
                onCheckedChange = { prefs.setLeftTapZoningEnabled(it) },
                enabled = enabled,
            )
            SettingToggleRow(
                title = stringResource(R.string.settings_widget_buttons_label),
                description = stringResource(R.string.settings_widget_buttons_description),
                checked = buttonsEnabled,
                onCheckedChange = { prefs.setButtonsEnabled(it) },
                enabled = enabled,
            )
            SettingValueRow(
                title = stringResource(R.string.settings_widget_left_tap_label),
                description = stringResource(R.string.settings_widget_left_tap_description),
                value = leftTapApp.label,
                onClick = { showLeftTapPicker = true },
                enabled = leftTapApp.enabled && enabled,
            )
        }
    }

    if (showLeftTapPicker) {
        AppLaunchPickerDialog(
            currentPackage = leftTapApp.packageName,
            onDismiss = { showLeftTapPicker = false },
            onSelect = { pkg, label ->
                prefs.setLeftTapApp(pkg, label)
                showLeftTapPicker = false
            },
            showMinimizeToggle = false,
        )
    }
}

@Composable
private fun PlacesSection() {
    SectionHeader(text = stringResource(R.string.settings_section_places_title))
    PlacesInlineContent()
}

@Composable
private fun DisplaySection() {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE)
    }
    val entryPoint = remember {
        EntryPointAccessors.fromApplication(context.applicationContext, ClusterEntryPoint::class.java)
    }
    var enabled by remember {
        mutableStateOf(prefs.getBoolean(ClusterProjectionManager.KEY_MIRROR_ENABLED, false))
    }
    var widthPct by remember {
        mutableStateOf(prefs.getInt(ClusterProjectionManager.KEY_WIDTH_PCT, MAX_PROJECTION_PCT))
    }
    var heightPct by remember {
        mutableStateOf(prefs.getInt(ClusterProjectionManager.KEY_HEIGHT_PCT, MAX_PROJECTION_PCT))
    }
    var offsetXPct by remember {
        mutableStateOf(prefs.getInt(ClusterProjectionManager.KEY_OFFSET_X_PCT, CENTER_OFFSET_PCT))
    }
    var offsetYPct by remember {
        mutableStateOf(prefs.getInt(ClusterProjectionManager.KEY_OFFSET_Y_PCT, CENTER_OFFSET_PCT))
    }
    var scalePct by remember {
        mutableStateOf(prefs.getInt(ClusterProjectionManager.KEY_SCALE_PCT, DEFAULT_SCALE_PCT))
    }
    var targetPkg by remember {
        mutableStateOf(prefs.getString(ClusterProjectionManager.KEY_TARGET_PACKAGE, NAVI_PACKAGE) ?: NAVI_PACKAGE)
    }
    var targetLabel by remember {
        mutableStateOf(
            prefs.getString(ClusterProjectionManager.KEY_TARGET_LABEL, null)
                ?: resolveAppLabel(context, targetPkg)
        )
    }
    var pickingApp by remember { mutableStateOf(false) }
    var learning by remember { mutableStateOf(false) }
    var triggerKey by remember {
        mutableStateOf(prefs.getInt(ClusterProjectionManager.KEY_TRIGGER_KEYCODE, DEFAULT_TRIGGER_KEYCODE))
    }
    var autoContainer by remember {
        mutableStateOf(prefs.getBoolean(ClusterProjectionManager.KEY_AUTO_CONTAINER, true))
    }

    SectionHeader(text = stringResource(R.string.settings_display_mirror_header))
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingToggleRow(
                title = stringResource(R.string.settings_display_mirror_title),
                description = stringResource(R.string.settings_display_mirror_desc),
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    prefs.edit().putBoolean(ClusterProjectionManager.KEY_MIRROR_ENABLED, it).apply()
                    // Turning the switch on self-enables our a11y key filter via the daemon, so star
                    // control works on a clean install with no ADB (DiLink has no a11y settings UI).
                    if (it) {
                        ClusterProjectionManager.enableStarControl(
                            entryPoint.helperClient(), entryPoint.helperBootstrap())
                    }
                },
            )
            SettingDivider()
            // Auto power-on toggle: when enabled, ClusterProjectionManager wakes the cluster compositor
            // before sending the projection window.
            SettingToggleRow(
                title = stringResource(R.string.settings_cluster_auto_container_title),
                description = stringResource(R.string.settings_cluster_auto_container_desc),
                checked = autoContainer,
                onCheckedChange = {
                    autoContainer = it
                    prefs.edit().putBoolean(ClusterProjectionManager.KEY_AUTO_CONTAINER, it).apply()
                },
            )
            // Trigger-button row — only meaningful while the feature (and thus the a11y service) is on.
            if (enabled) {
                SettingDivider()
                SettingValueRow(
                    title = stringResource(R.string.settings_display_button_title),
                    value = steeringButtonLabel(triggerKey),
                    onClick = { learning = true },
                )
            }
            SettingDivider()
            // App to project — defaults to Yandex Navi. Takes effect on the next star press, not live.
            SettingValueRow(
                title = stringResource(R.string.settings_display_app_title),
                value = targetLabel,
                onClick = { pickingApp = true },
            )
        }
    }

    if (learning) {
        LearnButtonDialog(
            onSave = { code ->
                triggerKey = code
                ClusterProjectionManager.setTriggerKeyCode(context, code)
                learning = false
            },
            onDismiss = { learning = false },
        )
    }

    if (pickingApp) {
        AppLaunchPickerDialog(
            currentPackage = targetPkg,
            onDismiss = { pickingApp = false },
            onSelect = { newPkg, newLabel ->
                targetPkg = newPkg
                targetLabel = newLabel
                prefs.edit()
                    .putString(ClusterProjectionManager.KEY_TARGET_PACKAGE, newPkg)
                    .putString(ClusterProjectionManager.KEY_TARGET_LABEL, newLabel)
                    .apply()
                pickingApp = false
            },
        )
    }

    SectionHeader(text = stringResource(R.string.settings_display_size_header))
    // Persist the new size and re-apply it live. reproject() is a no-op unless we are actively
    // projecting, so a tweak while OFF just lands in prefs and shows on the next star press.
    val applyGeometry: () -> Unit = {
        prefs.edit()
            .putInt(ClusterProjectionManager.KEY_WIDTH_PCT, widthPct)
            .putInt(ClusterProjectionManager.KEY_HEIGHT_PCT, heightPct)
            .putInt(ClusterProjectionManager.KEY_OFFSET_X_PCT, offsetXPct)
            .putInt(ClusterProjectionManager.KEY_OFFSET_Y_PCT, offsetYPct)
            .putInt(ClusterProjectionManager.KEY_SCALE_PCT, scalePct)
            .apply()
        ClusterProjectionManager.reproject(
            context, entryPoint.helperClient(), entryPoint.helperBootstrap())
    }
    // Defaults (100/100 size, centered, scale 100) reproduce the plain fullscreen projection, so
    // cars without a native mini zone (e.g. Leopard 3) need no tuning. The offset sliders only
    // matter once the window is smaller than the panel; on Sea Lion 07 they let the user move the
    // window into the native mini-cluster zone (#48).
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingSliderRow(
                title = stringResource(R.string.settings_display_size_width),
                description = stringResource(R.string.settings_display_size_width_desc),
                value = widthPct.toFloat(),
                onValueChange = { widthPct = it.roundToInt() },
                valueRange = MIN_PROJECTION_PCT.toFloat()..MAX_PROJECTION_PCT.toFloat(),
                valueLabel = "${widthPct}%",
                steps = (MAX_PROJECTION_PCT - MIN_PROJECTION_PCT) / 2 - 1,
                enabled = enabled,
                onValueChangeFinished = applyGeometry,
            )
            SettingDivider()
            SettingSliderRow(
                title = stringResource(R.string.settings_display_size_height),
                description = stringResource(R.string.settings_display_size_height_desc),
                value = heightPct.toFloat(),
                onValueChange = { heightPct = it.roundToInt() },
                valueRange = MIN_PROJECTION_PCT.toFloat()..MAX_PROJECTION_PCT.toFloat(),
                valueLabel = "${heightPct}%",
                steps = (MAX_PROJECTION_PCT - MIN_PROJECTION_PCT) / 2 - 1,
                enabled = enabled,
                onValueChangeFinished = applyGeometry,
            )
            SettingDivider()
            SettingSliderRow(
                title = stringResource(R.string.settings_display_offset_x),
                description = stringResource(R.string.settings_display_offset_x_desc),
                value = offsetXPct.toFloat(),
                onValueChange = { offsetXPct = it.roundToInt() },
                valueRange = MIN_OFFSET_PCT.toFloat()..MAX_OFFSET_PCT.toFloat(),
                valueLabel = "${offsetXPct}%",
                steps = (MAX_OFFSET_PCT - MIN_OFFSET_PCT) / 2 - 1,
                enabled = enabled,
                onValueChangeFinished = applyGeometry,
            )
            SettingDivider()
            SettingSliderRow(
                title = stringResource(R.string.settings_display_offset_y),
                description = stringResource(R.string.settings_display_offset_y_desc),
                value = offsetYPct.toFloat(),
                onValueChange = { offsetYPct = it.roundToInt() },
                valueRange = MIN_OFFSET_PCT.toFloat()..MAX_OFFSET_PCT.toFloat(),
                valueLabel = "${offsetYPct}%",
                steps = (MAX_OFFSET_PCT - MIN_OFFSET_PCT) / 2 - 1,
                enabled = enabled,
                onValueChangeFinished = applyGeometry,
            )
            SettingDivider()
            SettingSliderRow(
                title = stringResource(R.string.settings_display_scale),
                description = stringResource(R.string.settings_display_scale_desc),
                value = scalePct.toFloat(),
                onValueChange = { scalePct = it.roundToInt() },
                valueRange = MIN_SCALE_PCT.toFloat()..MAX_SCALE_PCT.toFloat(),
                valueLabel = "${scalePct}%",
                steps = (MAX_SCALE_PCT - MIN_SCALE_PCT) / 2 - 1,
                enabled = enabled,
                onValueChangeFinished = applyGeometry,
            )
        }
    }
}

/** App label for the cluster picker row; falls back to the package name when not installed/resolvable. */
private fun resolveAppLabel(context: Context, pkg: String): String =
    try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

/** Human label for a steering-wheel keycode: known name, else "Кнопка (код N)". */
@Composable
private fun steeringButtonLabel(keyCode: Int): String {
    val res = com.bydmate.app.cluster.knownButtonNameRes(keyCode)
    return if (res != 0) stringResource(res)
    else stringResource(R.string.steering_button_unknown, keyCode)
}

private sealed interface LearnUiState {
    data object Waiting : LearnUiState
    data class Rejected(val keyCode: Int) : LearnUiState
    data class Captured(val keyCode: Int) : LearnUiState
    data object TimedOut : LearnUiState
}

/**
 * Learn-the-button dialog. Puts SteeringWheelKeyService into learn mode while open and collects the
 * captured key from its StateFlow (same process). States: Waiting → (Rejected loops) → Captured
 * (confirm) / TimedOut. learnMode is always cleared on dispose.
 */
@Composable
private fun LearnButtonDialog(
    onSave: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var state by remember { mutableStateOf<LearnUiState>(LearnUiState.Waiting) }

    // Clear learn mode AND the captured value on close, so a later reopen can't latch a stale capture.
    DisposableEffect(Unit) {
        onDispose {
            SteeringWheelKeyService.learnMode = false
            SteeringWheelKeyService.capturedKey.value = null
        }
    }

    // Arm capture, then react ONLY to fresh emissions. The StateFlow replays its current value to a
    // new collector, so we reset it to null first and filterNotNull() drops both that reset and any
    // stale value left from a previous session — reopening therefore always starts at Waiting.
    LaunchedEffect(Unit) {
        SteeringWheelKeyService.capturedKey.value = null
        SteeringWheelKeyService.learnMode = true
        SteeringWheelKeyService.capturedKey
            .filterNotNull()
            .collect { r ->
                state = if (r.assignable) {
                    SteeringWheelKeyService.learnMode = false
                    LearnUiState.Captured(r.keyCode)
                } else {
                    LearnUiState.Rejected(r.keyCode)
                }
            }
    }

    // Timeout while still waiting/rejected (no assignable capture yet).
    LaunchedEffect(state) {
        if (state is LearnUiState.Waiting || state is LearnUiState.Rejected) {
            delay(10_000)
            if (state is LearnUiState.Waiting || state is LearnUiState.Rejected) {
                SteeringWheelKeyService.learnMode = false
                state = LearnUiState.TimedOut
            }
        }
    }

    // "Again": re-arm. The collector above keeps running (keyed on Unit), so just reset + Waiting.
    val restart = {
        SteeringWheelKeyService.capturedKey.value = null
        SteeringWheelKeyService.learnMode = true
        state = LearnUiState.Waiting
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.learn_button_dialog_title)) },
        text = {
            when (val s = state) {
                is LearnUiState.Waiting -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = AccentGreen)
                    Text(stringResource(R.string.learn_button_waiting))
                }
                is LearnUiState.Rejected -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.learn_button_rejected))
                    Text(steeringButtonLabel(s.keyCode), color = TextSecondary, fontSize = 12.sp)
                }
                is LearnUiState.Captured -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.learn_button_captured))
                    Text(
                        "${steeringButtonLabel(s.keyCode)} (${s.keyCode})",
                        color = TextPrimary, fontWeight = FontWeight.Medium,
                    )
                }
                is LearnUiState.TimedOut -> Text(stringResource(R.string.learn_button_timeout))
            }
        },
        confirmButton = {
            when (val s = state) {
                is LearnUiState.Captured -> TextButton(onClick = { onSave(s.keyCode) }) {
                    Text(stringResource(R.string.learn_button_save))
                }
                is LearnUiState.TimedOut -> TextButton(onClick = restart) {
                    Text(stringResource(R.string.learn_button_again))
                }
                else -> {}
            }
        },
        dismissButton = {
            when (state) {
                is LearnUiState.Captured -> TextButton(onClick = restart) {
                    Text(stringResource(R.string.learn_button_again))
                }
                else -> TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.learn_button_cancel))
                }
            }
        },
    )
}


@Composable
private fun ServiceSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    val context = LocalContext.current

    // SAF picker for restore — must be declared at composable top level
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.restoreConfig(uri)
    }
    var showRestoreConfirm by remember { mutableStateOf(false) }
    var showExportConfirm by remember { mutableStateOf(false) }

    // Confirm dialog for destructive restore operation
    if (showRestoreConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirm = false },
            title = {
                Text(
                    stringResource(R.string.settings_config_restore_confirm_title),
                    color = TextPrimary,
                )
            },
            text = {
                Text(
                    stringResource(R.string.settings_config_restore_confirm_body),
                    color = TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRestoreConfirm = false
                    restoreLauncher.launch(arrayOf("application/zip"))
                }) {
                    Text(
                        stringResource(R.string.settings_config_restore_confirm_ok),
                        color = SocRed,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirm = false }) {
                    Text(
                        stringResource(R.string.settings_config_restore_confirm_cancel),
                        color = TextSecondary,
                    )
                }
            },
            containerColor = CardSurfaceElevated,
        )
    }

    // Confirm dialog before exporting plaintext backup
    if (showExportConfirm) {
        AlertDialog(
            onDismissRequest = { showExportConfirm = false },
            title = {
                Text(
                    stringResource(R.string.settings_config_export_confirm_title),
                    color = TextPrimary,
                )
            },
            text = {
                Text(
                    stringResource(R.string.settings_config_export_confirm_body),
                    color = TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showExportConfirm = false
                    viewModel.exportConfig()
                }) {
                    Text(
                        stringResource(R.string.settings_config_export_confirm_ok),
                        color = PrimaryColor,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportConfirm = false }) {
                    Text(
                        stringResource(R.string.settings_config_restore_confirm_cancel),
                        color = TextSecondary,
                    )
                }
            },
            containerColor = CardSurfaceElevated,
        )
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
            SettingActionRow(
                title = stringResource(R.string.settings_export_csv_button),
                description = stringResource(R.string.settings_export_csv_desc),
                buttonLabel = stringResource(R.string.settings_export_csv_button),
                onClick = { viewModel.exportCsv() },
            )
            if (state.exportStatus != null) {
                SettingHint(
                    text = state.exportStatus!!,
                )
            }
            SettingDivider()
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
            )
            if (state.logSaveStatus != null) {
                SettingHint(
                    text = state.logSaveStatus!!,
                )
            }
            SettingDivider()
            SettingActionRow(
                title = stringResource(R.string.settings_config_export_button),
                description = stringResource(R.string.settings_config_export_desc),
                buttonLabel = stringResource(R.string.settings_config_export_button),
                onClick = { showExportConfirm = true },
            )
            SettingDivider()
            SettingActionRow(
                title = stringResource(R.string.settings_config_restore_button),
                description = stringResource(R.string.settings_config_restore_desc),
                buttonLabel = stringResource(R.string.settings_config_restore_button),
                onClick = { showRestoreConfirm = true },
            )
            if (state.configStatus != null) {
                SettingHint(
                    text = state.configStatus!!,
                )
            }
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

@Composable
private fun AppSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    val lang by viewModel.appLanguage.collectAsState()
    LanguageBlock(currentLang = lang, onLanguageChange = viewModel::setAppLanguage)

    var showDonate by remember { mutableStateOf(false) }
    if (showDonate) {
        DonateDialog(entry = DonateEntry.SETTINGS, onDismiss = { showDonate = false })
    }

    SectionHeader(text = stringResource(R.string.settings_app_units_header))
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SettingChipRow(
                title = stringResource(R.string.settings_app_distance_label),
                options = listOf(stringResource(R.string.settings_unit_km), stringResource(R.string.settings_unit_miles)),
                selectedIndex = if (state.units == "km") 0 else 1,
                onSelect = { idx -> viewModel.saveUnits(if (idx == 0) "km" else "miles") },
            )
            SettingChipRow(
                title = stringResource(R.string.settings_app_currency_label),
                options = SettingsRepository.CURRENCIES.map { it.code },
                selectedIndex = SettingsRepository.CURRENCIES.indexOfFirst { it.code == state.currency }.coerceAtLeast(0),
                onSelect = { idx -> viewModel.saveCurrency(SettingsRepository.CURRENCIES[idx].code) },
            )
            SettingChipRow(
                title = stringResource(R.string.settings_map_tile_source_label),
                options = listOf("OpenStreetMap", "Amap"),
                selectedIndex = if (state.mapTileSource == "osm") 0 else 1,
                onSelect = { idx -> viewModel.saveMapTileSource(if (idx == 0) "osm" else "amap") },
            )
        }
    }

    SectionHeader(text = stringResource(R.string.settings_update_dialog_title))
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SettingToggleRow(
                title = stringResource(R.string.settings_update_check_toggle_label),
                description = stringResource(R.string.settings_update_check_toggle_description),
                checked = state.autoCheckUpdates,
                onCheckedChange = { viewModel.setAutoCheckUpdates(it) },
            )
            SettingActionRow(
                title = stringResource(R.string.settings_update_check_button),
                description = "BYDMate v${state.appVersion}",
                buttonLabel = stringResource(R.string.settings_update_check_button),
                onClick = { viewModel.showUpdateDialog() },
            )
        }
    }

    SectionHeader(text = stringResource(R.string.settings_donate_header))
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(stringResource(R.string.settings_donate_card_body), color = TextSecondary, fontSize = 14.sp)
            Button(
                onClick = { showDonate = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = NavyDark)
            ) {
                Text(stringResource(R.string.settings_donate_button), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }

    SectionHeader(text = stringResource(R.string.settings_about_header))
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth()
    ) {
        val context = LocalContext.current
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(stringResource(R.string.settings_copyright), color = TextSecondary, fontSize = 14.sp)
            Text(
                text = "github.com/AndyShaman/BYDMate",
                color = AccentBlue,
                fontSize = 14.sp,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable {
                    context.startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/AndyShaman/BYDMate")))
                }
            )
            Text(
                text = stringResource(R.string.settings_weather_attribution),
                color = TextSecondary,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun VoiceSettingsContent(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onNavigateToVoiceJournal: () -> Unit,
    onNavigateToAgentChat: () -> Unit,
) {
    val context = LocalContext.current

    // Tracks which action requested the RECORD_AUDIO permission so the onResult
    // callback dispatches the right operation (ENABLE toggle).
    var pendingVoiceAction by remember { mutableStateOf("") }  // "ENABLE" | ""

    // RECORD_AUDIO permission launcher — requested on enable
    val audioPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            when (pendingVoiceAction) {
                "ENABLE" -> viewModel.setVoiceEnabled(true)
            }
        }
        pendingVoiceAction = ""
    }

    fun hasAudioPerm() = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    fun hasContactsPerm() = ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_CONTACTS
    ) == PackageManager.PERMISSION_GRANTED

    // READ_CONTACTS permission for call_contact — standard runtime dialog only, no ADB.
    var contactsPermGranted by remember { mutableStateOf(hasContactsPerm()) }
    val contactsPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> contactsPermGranted = granted }

    // --- Section 1: Агент (enable toggle, name, persona, gender, debug tools) ---
    SectionHeader(text = stringResource(R.string.settings_agent_section_header))

    // Agent enable toggle
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp)) {
            SettingToggleRow(
                title = stringResource(R.string.agent_enable_title),
                description = stringResource(R.string.agent_enable_desc),
                checked = state.agentEnabled,
                onCheckedChange = { viewModel.setAgentEnabled(it) },
            )
        }
    }

    // Agent identity: display/wake name + persona (spoken-reply style) + gender
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsTextField(
                label = stringResource(R.string.settings_agent_name_label),
                value = state.agentName,
                onValueChange = { viewModel.setAgentName(it) },
                keyboardType = KeyboardType.Text,
            )
            SettingHint(stringResource(R.string.settings_agent_name_hint))
            SettingDivider()
            val personaIds = listOf(AgentPersona.SNARKY.id, AgentPersona.NAVIGATOR.id, AgentPersona.ENGINEER.id)
            SettingChipRow(
                title = stringResource(R.string.settings_agent_persona_label),
                options = listOf(
                    stringResource(R.string.settings_persona_snarky),
                    stringResource(R.string.settings_persona_navigator),
                    stringResource(R.string.settings_persona_engineer),
                ),
                selectedIndex = personaIds.indexOf(state.agentPersona).coerceAtLeast(0),
                onSelect = { viewModel.setAgentPersona(personaIds[it]) },
            )
            SettingDivider()
            val genderIds = listOf("m", "f")
            SettingChipRow(
                title = stringResource(R.string.settings_agent_gender_label),
                description = stringResource(R.string.settings_agent_gender_hint),
                options = listOf(
                    stringResource(R.string.settings_agent_gender_male),
                    stringResource(R.string.settings_agent_gender_female),
                ),
                selectedIndex = genderIds.indexOf(state.agentGender).coerceAtLeast(0),
                onSelect = { viewModel.setAgentGender(genderIds[it]) },
            )
        }
    }

    // Agent debug tools
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp)) {
            val agentConfigured = state.agentConnConfigured
            SettingActionRow(
                title = stringResource(R.string.settings_agent_debug_row_title),
                description = stringResource(R.string.settings_agent_debug_row_desc),
                buttonLabel = if (state.modelTestRunning)
                    stringResource(R.string.agent_test_model_running)
                else
                    stringResource(R.string.agent_test_model_button),
                onClick = { viewModel.testAgentModel() },
                enabled = agentConfigured && !state.modelTestRunning,
                secondButtonLabel = stringResource(R.string.agent_chat_open),
                onSecondClick = onNavigateToAgentChat,
                secondButtonEnabled = state.agentEnabled,
            )
            state.modelTestResult?.let {
                Text(it, color = TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            SettingDivider()
            SettingHint(stringResource(R.string.agent_model_shared_caption))
        }
    }

    // --- Section 2: Ответы агента (TTS toggle + per-voice list, filtered by agent gender) ---
    SectionHeader(text = stringResource(R.string.settings_tts_header))
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp)) {
            SettingToggleRow(
                title = stringResource(R.string.settings_tts_toggle),
                description = stringResource(R.string.settings_tts_subtitle),
                checked = state.ttsEnabled,
                onCheckedChange = { viewModel.setTtsEnabled(it) },
            )
        }
    }

    // Voice list: local voices matching the current agent gender, then the online voices
    // (Gemini via OpenRouter, MiniMax). Selecting any row sets tts_source.
    val currentGender = if (state.agentGender == "f") TtsGender.FEMALE else TtsGender.MALE
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TtsVoiceCatalog.byGender(currentGender).forEach { voice ->
                TtsVoiceRow(voice = voice, state = state, viewModel = viewModel)
            }
            OnlineTtsVoiceRow(
                id = "gemini",
                label = "Gemini",
                description = stringResource(R.string.settings_tts_voice_online_gemini_desc),
                badge = stringResource(R.string.settings_tts_voice_online_gemini_badge),
                enabled = true,
                state = state, viewModel = viewModel,
            )
            OnlineTtsVoiceRow(
                id = "minimax",
                label = "MiniMax",
                description = stringResource(R.string.settings_tts_voice_online_minimax_desc),
                badge = stringResource(R.string.settings_tts_voice_online_minimax_badge),
                enabled = state.minimaxKeySet,
                state = state, viewModel = viewModel,
            )
        }
    }
    SettingHint(stringResource(R.string.settings_tts_online_fallback_hint))

    // --- Ключи для онлайн-голосов: OpenRouter hint (key lives in AI connections) + MiniMax
    // provider/price/key ---
    SectionHeader(text = stringResource(R.string.settings_tts_keys_header))
    SettingHint(stringResource(R.string.settings_tts_keys_openrouter_hint))
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("MiniMax", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            val providerIds = listOf("official", "fal", "replicate")
            SettingChipRow(
                title = stringResource(R.string.settings_minimax_provider_label),
                options = listOf(
                    stringResource(R.string.settings_minimax_provider_official),
                    stringResource(R.string.settings_minimax_provider_fal),
                    stringResource(R.string.settings_minimax_provider_replicate),
                ),
                selectedIndex = providerIds.indexOf(state.minimaxProvider).coerceAtLeast(0),
                onSelect = { viewModel.setMinimaxProvider(providerIds[it]) },
            )
            SettingHint(stringResource(R.string.settings_minimax_price_hint))
            MiniMaxKeyField(keySet = state.minimaxKeySet, onSave = { viewModel.setMinimaxKey(it) })
        }
    }

    // --- Section 3: Настройки локального голоса (speed/liveliness sliders + preview) ---
    SectionHeader(text = stringResource(R.string.settings_tts_tuning_header))
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp)) {
            var ratePct by remember(state.ttsRate) { mutableStateOf((state.ttsRate * 100).roundToInt()) }
            SettingSliderRow(
                title = stringResource(R.string.settings_tts_rate_label),
                description = stringResource(R.string.settings_tts_rate_hint),
                value = ratePct.toFloat(),
                onValueChange = { ratePct = it.roundToInt() },
                valueRange = 70f..140f,
                valueLabel = String.format(Locale.US, "%.2f", ratePct / 100f),
                steps = 13,
                onValueChangeFinished = { viewModel.setTtsRate(ratePct / 100f) },
            )
            SettingDivider()
            var liveliness by remember(state.ttsLiveliness) { mutableStateOf(state.ttsLiveliness) }
            SettingSliderRow(
                title = stringResource(R.string.settings_tts_liveliness_label),
                description = stringResource(R.string.settings_tts_liveliness_hint),
                value = liveliness.toFloat(),
                onValueChange = { liveliness = it.roundToInt() },
                valueRange = 0f..100f,
                valueLabel = "$liveliness%",
                steps = 49,
                onValueChangeFinished = { viewModel.setTtsLiveliness(liveliness) },
            )
            SettingDivider()
            SettingActionRow(
                title = stringResource(R.string.settings_tts_preview_button),
                buttonLabel = stringResource(R.string.settings_tts_preview_button),
                onClick = { viewModel.previewVoice() },
                style = SettingButtonStyle.Secondary,
            )
        }
    }
    SettingHint(stringResource(R.string.settings_voice_volume_hint))

    // --- Section 4: Распознавание речи (GigaAM) ---
    SectionHeader(text = stringResource(R.string.settings_asr_gigaam_title))
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp)) {
            val gigaAmDownloading = state.gigaAmDownloadProgress >= 0
            if (gigaAmDownloading) {
                Text(
                    stringResource(R.string.settings_voice_model_downloading, state.gigaAmDownloadProgress),
                    color = TextSecondary, fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = AccentGreen,
                )
            } else {
                SettingStatusRow(
                    title = "GigaAM",
                    ok = state.gigaAmModelReady,
                )
                SettingDivider()
                if (state.gigaAmModelReady) {
                    SettingActionRow(
                        title = stringResource(R.string.settings_asr_gigaam_ready),
                        buttonLabel = stringResource(R.string.settings_asr_gigaam_delete),
                        onClick = { viewModel.deleteGigaAmModel() },
                        style = SettingButtonStyle.Warning,
                    )
                } else {
                    if (state.gigaAmDownloadFailed) {
                        Text(
                            stringResource(R.string.settings_asr_gigaam_download_failed),
                            color = SocRed, fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                    SettingActionRow(
                        title = "GigaAM",
                        buttonLabel = stringResource(
                            R.string.settings_asr_gigaam_download,
                            com.bydmate.app.voice.GigaAmModelManager.MODEL_SIZE_LABEL,
                        ),
                        onClick = { viewModel.downloadGigaAmModel() },
                        style = SettingButtonStyle.Primary,
                    )
                }
            }
        }
    }

    // --- Section 5: Кнопка и микрофон ---
    SectionHeader(text = stringResource(R.string.settings_voice_button_mic_header))

    // Voice commands toggle
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp)) {
            SettingToggleRow(
                title = stringResource(R.string.settings_voice_enable_label),
                description = stringResource(R.string.settings_voice_enable_description),
                checked = state.voiceEnabled,
                onCheckedChange = { on ->
                    if (on && !hasAudioPerm()) {
                        pendingVoiceAction = "ENABLE"
                        audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        viewModel.setVoiceEnabled(on)
                    }
                },
            )
        }
    }

    // Steering-button assignment (reuses LearnButtonDialog from DisplaySection)
    var learningVoiceKey by remember { mutableStateOf(false) }
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp)) {
            val keyLabel = steeringButtonLabel(
                if (state.voiceKeycode == 0) DEFAULT_VOICE_KEYCODE else state.voiceKeycode
            )
            SettingValueRow(
                title = stringResource(R.string.settings_voice_button_label),
                value = stringResource(R.string.settings_voice_button_current, keyLabel),
                onClick = { learningVoiceKey = true },
            )
        }
    }

    if (learningVoiceKey) {
        LearnButtonDialog(
            onSave = { code ->
                viewModel.saveVoiceKeycode(code)
                learningVoiceKey = false
            },
            onDismiss = { learningVoiceKey = false },
        )
    }

    // Contacts permission (call_contact tool)
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp)) {
            SettingStatusRow(
                title = stringResource(R.string.settings_voice_contacts_label),
                description = stringResource(R.string.settings_voice_contacts_description),
                ok = contactsPermGranted,
                buttonLabel = if (!contactsPermGranted) stringResource(R.string.settings_voice_contacts_grant) else null,
                onClick = if (!contactsPermGranted) {
                    { contactsPermLauncher.launch(Manifest.permission.READ_CONTACTS) }
                } else null,
            )
        }
    }

    SettingHint(stringResource(R.string.settings_voice_a11y_hint))

    // Voice journal (last MAX voice sessions: what was heard and how it went)
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp)) {
            SettingActionRow(
                title = stringResource(R.string.settings_voice_journal_entry_title),
                description = stringResource(R.string.settings_voice_journal_entry_description),
                buttonLabel = stringResource(R.string.settings_voice_journal_entry_title),
                onClick = onNavigateToVoiceJournal,
                style = SettingButtonStyle.Secondary,
            )
        }
    }

    // --- Section 6: Штатный помощник BYD (moved verbatim from AppSection) ---
    SectionHeader(text = stringResource(R.string.settings_voice_native_assistant_header))
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp)) {
            SettingToggleRow(
                title = stringResource(R.string.settings_voice_disable_native_label),
                description = stringResource(R.string.settings_voice_disable_native_description),
                checked = state.disableNativeAssistant,
                onCheckedChange = { viewModel.setDisableNativeAssistant(it) },
            )
            SettingHint(stringResource(R.string.settings_voice_native_assistant_reboot_note))
        }
    }
}

/** One row in the "Ответы агента" voice list: radio button, name + description + "Локально"
 *  badge, and per-voice download/ready/delete controls. A voice with no model on disk cannot
 *  be selected. */
@Composable
private fun TtsVoiceRow(
    voice: com.bydmate.app.voice.TtsVoice,
    state: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    val ready = voice.id in state.ttsReadyVoices
    val downloadProgress = state.ttsDownloadProgress[voice.id]
    val failed = voice.id in state.ttsDownloadFailed
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = state.ttsSource == TtsRouter.OFFLINE && state.ttsVoice == voice.id,
            enabled = ready,
            onClick = {
                viewModel.setTtsVoice(voice.id)
                viewModel.setTtsSource(TtsRouter.OFFLINE)
            },
            colors = RadioButtonDefaults.colors(
                selectedColor = AccentGreen,
                unselectedColor = TextMuted,
            ),
        )
        Column(modifier = Modifier.weight(1f).padding(start = 4.dp, end = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(voice.labelRes),
                    color = if (ready) TextPrimary else TextMuted,
                    fontSize = 14.sp, fontWeight = FontWeight.Medium,
                )
                Text(
                    stringResource(R.string.settings_tts_voice_local_badge),
                    color = TextSecondary, fontSize = 10.sp,
                    modifier = Modifier
                        .background(CardSurface, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Text(
                stringResource(
                    when (voice.engine) {
                        com.bydmate.app.voice.TtsVoiceEngine.PIPER -> R.string.settings_tts_voice_desc_piper
                        com.bydmate.app.voice.TtsVoiceEngine.VITS_MULTI -> R.string.settings_tts_voice_desc_vits
                        com.bydmate.app.voice.TtsVoiceEngine.SUPERTONIC -> R.string.settings_tts_voice_desc_supertonic
                    }
                ),
                color = TextSecondary, fontSize = 12.sp,
            )
            if (!ready) {
                Text(
                    stringResource(R.string.settings_tts_voice_locked_hint),
                    color = TextMuted, fontSize = 11.sp,
                )
            }
        }
        when {
            downloadProgress != null -> Text(
                stringResource(R.string.settings_voice_model_downloading, downloadProgress),
                color = TextSecondary, fontSize = 12.sp,
            )
            ready -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings_tts_voice_ready), color = AccentGreen, fontSize = 12.sp)
                Text(" · ", color = TextMuted, fontSize = 12.sp)
                Text(
                    stringResource(R.string.settings_tts_model_delete),
                    color = SocRed, fontSize = 12.sp,
                    modifier = Modifier.clickable { viewModel.deleteTtsVoice(voice.id) },
                )
            }
            else -> Column(horizontalAlignment = Alignment.End) {
                Text(
                    stringResource(R.string.settings_tts_voice_download_label, voice.sizeMb),
                    color = AccentGreen, fontSize = 12.sp,
                    modifier = Modifier.clickable { viewModel.downloadTtsVoice(voice.id) },
                )
                if (failed) {
                    Text(
                        stringResource(R.string.settings_tts_download_failed),
                        color = SocRed, fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

/** One row in the voice list for an online TTS backend (Gemini/MiniMax): radio button,
 *  name + description + an optional badge. Unlike [TtsVoiceRow] there is no download/ready
 *  state -- [enabled] gates selection instead (MiniMax until its key is saved). */
@Composable
private fun OnlineTtsVoiceRow(
    id: String,
    label: String,
    description: String,
    badge: String?,
    enabled: Boolean,
    state: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = state.ttsSource == id,
            enabled = enabled,
            onClick = { viewModel.setTtsSource(id) },
            colors = RadioButtonDefaults.colors(
                selectedColor = AccentGreen,
                unselectedColor = TextMuted,
            ),
        )
        Column(modifier = Modifier.weight(1f).padding(start = 4.dp, end = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    label,
                    color = if (enabled) TextPrimary else TextMuted,
                    fontSize = 14.sp, fontWeight = FontWeight.Medium,
                )
                if (badge != null) {
                    Text(
                        badge,
                        color = TextSecondary, fontSize = 10.sp,
                        modifier = Modifier
                            .background(CardSurface, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Text(description, color = TextSecondary, fontSize = 12.sp)
        }
    }
}


@Composable
private fun SmartHomeSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    SectionHeader(text = "Умный дом")
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SettingToggleRow(
                title = "Polling",
                checked = state.aliceEnabled,
                onCheckedChange = { viewModel.toggleAlice(it) },
            )
            SettingsTextField(
                label = "Endpoint URL",
                value = state.aliceEndpoint,
                onValueChange = { viewModel.updateAliceEndpoint(it) },
                keyboardType = KeyboardType.Uri
            )
            SettingsTextField(
                label = "API Key",
                value = state.aliceApiKey,
                onValueChange = { viewModel.updateAliceApiKey(it) },
                keyboardType = KeyboardType.Password
            )
            SettingActionRow(
                title = "Сохранить",
                buttonLabel = "Сохранить",
                onClick = { viewModel.saveAliceSettings() },
                style = SettingButtonStyle.Primary,
                enabled = state.aliceEndpoint.isNotBlank() && state.aliceApiKey.isNotBlank(),
            )
            state.aliceSaveStatus?.let {
                Text(it, color = AccentGreen, fontSize = 12.sp)
            }
            SettingHint("Polling опрашивает Worker каждую секунду\nи выполняет команды через D+ API")
        }
    }
}

@Composable
private fun LanguageBlock(
    currentLang: String,
    onLanguageChange: (String) -> Unit
) {
    // No Activity.recreate(): MainActivity listens to LocalePreferences,
    // mutates Resources.configuration in place, and re-provides
    // LocalConfiguration so every stringResource recomposes on next frame.
    val langCodes = listOf("ru", "en", "zh", "pt")
    val langLabels = listOf(
        stringResource(R.string.settings_lang_russian), "English", "简体中文", "Português",
    )
    SectionHeader(text = stringResource(R.string.settings_language_title))
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SettingChipRow(
                title = stringResource(R.string.settings_language_title),
                options = langLabels,
                selectedIndex = langCodes.indexOf(currentLang).coerceAtLeast(0),
                onSelect = { idx -> if (langCodes[idx] != currentLang) onLanguageChange(langCodes[idx]) },
            )
        }
    }
}

/** Preset for the custom connection card: fills URL and model, key stays the user's. */
private data class CustomPreset(val name: String, val baseUrl: String, val model: String, val hintRes: Int)

private val CUSTOM_PRESETS = listOf(
    CustomPreset("Cloudflare", "https://api.cloudflare.com/client/v4/accounts/ACCOUNT_ID/ai/v1",
        "@cf/zai-org/glm-4.7-flash", R.string.settings_preset_hint_cloudflare),
    CustomPreset("Vercel", "https://ai-gateway.vercel.sh/v1",
        "openai/gpt-oss-120b", R.string.settings_preset_hint_vercel),
    CustomPreset("Kimi", "https://api.moonshot.ai/v1", "", R.string.settings_preset_hint_kimi),
    CustomPreset("NanoGPT", "https://nano-gpt.com/api/subscription/v1", "", R.string.settings_preset_hint_nanogpt),
    CustomPreset("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat", R.string.settings_preset_hint_deepseek),
    CustomPreset("Mistral", "https://api.mistral.ai/v1", "", R.string.settings_preset_hint_mistral),
)

@Composable
private fun ConnectionCard(
    title: String,
    configured: Boolean,
    testResult: String?,
    testRunning: Boolean,
    onTest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .background(if (configured) AccentGreen else TextMuted, CircleShape)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    stringResource(
                        if (configured) R.string.settings_conn_configured
                        else R.string.settings_conn_not_configured
                    ),
                    color = if (configured) AccentGreen else TextMuted, fontSize = 12.sp
                )
            }
            content()
            OutlinedButton(
                onClick = onTest,
                enabled = configured && !testRunning,
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    stringResource(
                        if (testRunning) R.string.settings_conn_checking else R.string.settings_conn_check
                    ),
                    fontSize = 13.sp
                )
            }
            testResult?.let {
                Text(it, color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
    }
}

@Composable
private fun ModelSelectionCard(state: SettingsUiState, viewModel: SettingsViewModel) {
    // Options: configured connections as id -> "label · model" pairs.
    // stringResource is resolved OUTSIDE buildList (its lambda is not composable).
    val customTitle = stringResource(R.string.settings_conn_custom_title)
    val options = buildList {
        if (state.openRouterConfigured) add("openrouter" to "OpenRouter · ${state.openRouterModelName.ifBlank { state.openRouterModel }}")
        if (state.zaiConfigured) add("zai" to "z.ai · glm-4.7-flash")
        if (state.customConfigured) add("custom" to "${state.customName.ifBlank { customTitle }} · ${state.customModel}")
    }
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.settings_models_header),
                color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold
            )
            ConnDropdown(
                label = stringResource(R.string.settings_model_primary),
                options = options,
                selectedId = state.primaryConn.ifBlank { "openrouter" },
                allowNone = false,
                onSelect = { viewModel.selectPrimaryConn(it) },
            )
            ConnDropdown(
                label = stringResource(R.string.settings_model_fallback),
                options = options,
                selectedId = state.fallbackConn,
                allowNone = true,
                onSelect = { viewModel.selectFallbackConn(it) },
            )
            SettingHint(stringResource(R.string.settings_models_hint))
        }
    }
}

@Composable
private fun ConnDropdown(
    label: String,
    options: List<Pair<String, String>>,
    selectedId: String,
    allowNone: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val noneLabel = stringResource(R.string.settings_model_none)
    val selectedLabel = options.firstOrNull { it.first == selectedId }?.second
        ?: if (allowNone) noneLabel else options.firstOrNull()?.second ?: noneLabel
    Column {
        Text(label, color = TextMuted, fontSize = 12.sp)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(selectedLabel, fontSize = 13.sp, maxLines = 1)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                if (allowNone) {
                    DropdownMenuItem(text = { Text(noneLabel) }, onClick = { onSelect(""); expanded = false })
                }
                options.forEach { (id, text) ->
                    DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(id); expanded = false })
                }
            }
        }
    }
}

@Composable
private fun SearchStatusCard(state: SettingsUiState) {
    val searchOk = state.exaApiKey.isNotBlank() ||
        (state.primaryConn.ifBlank { "openrouter" } != "custom" && state.agentConnConfigured)
    val source = when {
        state.exaApiKey.isNotBlank() -> stringResource(R.string.settings_search_source_exa)
        state.primaryConn.ifBlank { "openrouter" } != "custom" && state.agentConnConfigured ->
            stringResource(R.string.settings_search_source_native)
        else -> stringResource(R.string.settings_search_source_none)
    }
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SettingStatusRow(
                title = stringResource(R.string.settings_search_status_title),
                description = source,
                ok = searchOk,
            )
            SettingHint(stringResource(R.string.settings_search_status_hint))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        color = TextPrimary,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun SettingsTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
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

/** MiniMax API key field. The real key lives only in SettingsRepository -- [keySet] (never
 *  the key itself) comes back from the ViewModel, so a saved key shows as a placeholder
 *  instead of being echoed into the field. */
@Composable
private fun MiniMaxKeyField(keySet: Boolean, onSave: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    OutlinedTextField(
        value = input,
        onValueChange = { input = it; onSave(it) },
        label = { Text(stringResource(R.string.settings_conn_api_key_label)) },
        placeholder = if (keySet) {
            { Text(stringResource(R.string.settings_minimax_key_saved_placeholder), color = TextMuted) }
        } else null,
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedBorderColor = AccentGreen,
            unfocusedBorderColor = CardBorder,
            focusedLabelColor = PrimaryColor,
            unfocusedLabelColor = TextSecondary,
            cursorColor = PrimaryColor,
        ),
    )
}

@Composable
private fun CustomModelPickerDialog(
    models: List<String>,
    loading: Boolean,
    selectedId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CardSurface),
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .heightIn(max = 500.dp)
                .padding(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    stringResource(R.string.settings_model_picker_title),
                    color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (loading) {
                    Text(
                        stringResource(R.string.settings_model_picker_loading),
                        color = TextSecondary, fontSize = 14.sp
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        items(models) { id ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(id) }
                                    .background(
                                        if (id == selectedId) AccentGreen.copy(alpha = 0.15f)
                                        else Color.Transparent,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    id,
                                    color = if (id == selectedId) AccentGreen else TextPrimary,
                                    fontSize = 13.sp,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelPickerDialog(
    models: List<OpenRouterModel>,
    loading: Boolean,
    selectedId: String,
    onSelect: (OpenRouterModel) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CardSurface),
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .heightIn(max = 500.dp)
                .padding(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.settings_model_picker_title), color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text(stringResource(R.string.settings_model_picker_search_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = AccentGreen,
                        unfocusedBorderColor = CardBorder,
                        focusedLabelColor = AccentGreen,
                        unfocusedLabelColor = TextSecondary,
                        cursorColor = AccentGreen
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (loading) {
                    Text(stringResource(R.string.settings_model_picker_loading), color = TextSecondary, fontSize = 14.sp)
                } else {
                    val filtered = if (searchQuery.isBlank()) models
                    else models.filter { it.name.contains(searchQuery, ignoreCase = true) ||
                        it.id.contains(searchQuery, ignoreCase = true) }

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        items(filtered) { model ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(model) }
                                    .background(
                                        if (model.id == selectedId) AccentGreen.copy(alpha = 0.15f)
                                        else Color.Transparent,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    model.name,
                                    color = if (model.id == selectedId) AccentGreen else TextPrimary,
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1
                                )
                                Text(
                                    if (model.pricingPrompt == 0.0) "FREE"
                                    else "${"%.2f".format(model.pricingPrompt)}$/M",
                                    color = if (model.pricingPrompt == 0.0) AccentGreen else TextSecondary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

