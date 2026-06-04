package com.bydmate.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import com.bydmate.app.cluster.ClusterEntryPoint
import com.bydmate.app.cluster.ClusterProjectionManager
import com.bydmate.app.cluster.MAX_PROJECTION_PCT
import com.bydmate.app.cluster.MIN_PROJECTION_PCT
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
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Place
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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

private enum class SettingsSection(@StringRes val labelRes: Int, val icon: ImageVector) {
    BATTERY(R.string.settings_section_auto_battery_title, Icons.Outlined.BatteryChargingFull),
    INTEGRATIONS(R.string.settings_section_integrations_title, Icons.Outlined.Link),
    WIDGET(R.string.settings_section_widget_title, Icons.Outlined.PhoneAndroid),
    DISPLAY(R.string.settings_section_display_title, Icons.Outlined.DirectionsCar),
    PLACES(R.string.settings_section_places_title, Icons.Outlined.Place),
    APP(R.string.settings_section_application_title, Icons.Outlined.Settings),
    SMART_HOME(R.string.settings_smart_home_section_title, Icons.Outlined.Home),
}

private val PrimaryColor = AccentGreen

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateToPlaces: () -> Unit = {},
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

        var selected by rememberSaveable { mutableStateOf(SettingsSection.BATTERY) }
        val safeSelected = if (selected == SettingsSection.SMART_HOME && !state.devModeUnlocked) {
            SettingsSection.BATTERY
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
                        SettingsSection.WIDGET -> WidgetSection()
                        SettingsSection.DISPLAY -> DisplaySection()
                        SettingsSection.PLACES -> PlacesSection(onNavigateToPlaces)
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
            SettingsTextField(
                label = stringResource(R.string.settings_tariff_home_label, state.currencySymbol),
                value = state.homeTariff,
                onValueChange = { viewModel.updateHomeTariff(it) },
                keyboardType = KeyboardType.Decimal
            )
            SettingsTextField(
                label = stringResource(R.string.settings_tariff_dc_label, state.currencySymbol),
                value = state.dcTariff,
                onValueChange = { viewModel.updateDcTariff(it) },
                keyboardType = KeyboardType.Decimal
            )
            Text(stringResource(R.string.settings_tariff_trip_label), color = TextSecondary, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UnitChip("AC", state.tripCostTariff == "home") { viewModel.saveTripCostTariff("home") }
                UnitChip("DC", state.tripCostTariff == "dc") { viewModel.saveTripCostTariff("dc") }
                UnitChip(stringResource(R.string.settings_tariff_trip_custom_chip), state.tripCostTariff != "home" && state.tripCostTariff != "dc") {
                    viewModel.saveTripCostTariff(state.homeTariff)
                }
            }
            if (state.tripCostTariff != "home" && state.tripCostTariff != "dc") {
                SettingsTextField(
                    label = stringResource(R.string.settings_tariff_custom_label, state.currencySymbol),
                    value = state.tripCostTariff,
                    onValueChange = { viewModel.saveTripCostTariff(it) },
                    keyboardType = KeyboardType.Decimal
                )
            }

            // Save tariffs button
            Button(
                onClick = { viewModel.saveTariffs() },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = NavyDark)
            ) {
                Text(stringResource(R.string.settings_save_tariffs_button), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            state.tariffSaveStatus?.let {
                Text(it, color = AccentGreen, fontSize = 12.sp)
            }
            Text(
                stringResource(R.string.settings_tariff_future_note),
                color = TextMuted, fontSize = 11.sp, lineHeight = 15.sp
            )

            HorizontalDivider(color = CardBorder, modifier = Modifier.padding(vertical = 4.dp))

            // Recalculate all trips button
            Button(
                onClick = { viewModel.showRecalcConfirm() },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentOrange),
                border = androidx.compose.foundation.BorderStroke(1.dp, AccentOrange.copy(alpha = 0.4f))
            ) {
                Text(stringResource(R.string.settings_recalc_all_button), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            state.recalcStatus?.let {
                Text(it, color = AccentGreen, fontSize = 12.sp)
            }
            Text(
                stringResource(R.string.settings_recalc_note),
                color = TextMuted, fontSize = 11.sp, lineHeight = 15.sp
            )
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
            SettingsTextField(
                label = stringResource(R.string.settings_consumption_bad_label),
                value = state.consumptionBad,
                onValueChange = { viewModel.saveConsumptionBad(it) },
                keyboardType = KeyboardType.Decimal
            )
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        stringResource(R.string.settings_abrp_telemetry_label),
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        stringResource(R.string.settings_abrp_telemetry_description),
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                }
                Switch(
                    checked = state.abrpTelemetryEnabled,
                    onCheckedChange = { viewModel.toggleAbrpTelemetry(it) },
                    colors = bydSwitchColors(),
                )
            }
            SettingsTextField(
                label = stringResource(R.string.settings_abrp_token_label),
                value = state.abrpUserToken,
                onValueChange = { viewModel.updateAbrpUserToken(it) },
                keyboardType = KeyboardType.Password
            )
            Button(
                onClick = { viewModel.saveAbrpSettings() },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentGreen,
                    contentColor = NavyDark
                )
            ) {
                Text(stringResource(R.string.settings_abrp_save_button), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            state.abrpSaveStatus?.let {
                Text(it, color = AccentGreen, fontSize = 12.sp)
            }
        }
    }

    SectionHeader(text = stringResource(R.string.settings_ai_section_header))
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
                label = stringResource(R.string.settings_openrouter_api_key_label),
                value = state.openRouterApiKey,
                onValueChange = { viewModel.saveOpenRouterApiKey(it) },
                keyboardType = KeyboardType.Password
            )
            Button(
                onClick = { viewModel.showModelPicker() },
                enabled = state.openRouterApiKey.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentBlue,
                    contentColor = NavyDark
                )
            ) {
                Text(
                    if (state.openRouterModelName.isNotBlank())
                        stringResource(R.string.settings_openrouter_model_selected, state.openRouterModelName)
                    else stringResource(R.string.settings_openrouter_model_pick),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
            }
            val aiLoadingLabel = stringResource(R.string.settings_ai_loading_label)
            Button(
                onClick = { viewModel.saveAiSettings() },
                enabled = state.openRouterApiKey.isNotBlank() &&
                    state.openRouterModel.isNotBlank() &&
                    state.aiSaveStatus != aiLoadingLabel,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentGreen,
                    contentColor = NavyDark
                )
            ) {
                Text(
                    if (state.aiSaveStatus == aiLoadingLabel) aiLoadingLabel
                    else stringResource(R.string.settings_ai_save_button),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            if (state.aiSaveStatus != null && state.aiSaveStatus != aiLoadingLabel) {
                Text(
                    state.aiSaveStatus!!,
                    color = if (state.aiSaveStatus!!.startsWith(stringResource(R.string.settings_error_prefix))) SocRed else AccentGreen,
                    fontSize = 12.sp
                )
            }
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.settings_widget_show_soc_label),
                    color = TextPrimary,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                Switch(
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
                    colors = bydSwitchColors(),
                )
            }
            Text(
                text = stringResource(R.string.settings_widget_hints),
                color = TextSecondary,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
            )
            Button(
                onClick = {
                    prefs.resetPosition()
                    if (enabled && AndroidSettings.canDrawOverlays(context)) {
                        WidgetController.detach()
                        WidgetController.attach(context)
                    }
                },
                enabled = enabled,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CardSurface),
            ) {
                Text(stringResource(R.string.settings_widget_reset_position_button), fontSize = 13.sp, color = TextPrimary)
            }
            Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.settings_widget_opacity_label),
                        color = TextPrimary,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${(alpha * 100).toInt()}%",
                        color = TextMuted,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Slider(
                    value = alpha,
                    onValueChange = {
                        if (enabled) WidgetController.setPreviewMode(context, true)
                        prefs.setAlpha(it)
                    },
                    valueRange = 0.3f..1.0f,
                    enabled = enabled,
                    colors = SliderDefaults.colors(
                        thumbColor = AccentGreen,
                        activeTrackColor = AccentGreen,
                        inactiveTrackColor = TextMuted.copy(alpha = 0.3f),
                        disabledThumbColor = TextMuted,
                        disabledActiveTrackColor = TextMuted.copy(alpha = 0.4f),
                    ),
                )
            }
            Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.settings_widget_scale_label),
                        color = TextPrimary,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${(scale * 100).toInt()}%",
                        color = TextMuted,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Slider(
                    value = scale,
                    onValueChange = {
                        if (enabled) WidgetController.setPreviewMode(context, true)
                        prefs.setScale(it)
                    },
                    valueRange = WidgetPreferences.SCALE_MIN..WidgetPreferences.SCALE_MAX,
                    enabled = enabled,
                    colors = SliderDefaults.colors(
                        thumbColor = AccentGreen,
                        activeTrackColor = AccentGreen,
                        inactiveTrackColor = TextMuted.copy(alpha = 0.3f),
                        disabledThumbColor = TextMuted,
                        disabledActiveTrackColor = TextMuted.copy(alpha = 0.4f),
                    ),
                )
            }
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
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_widget_left_tap_zoning_label),
                        color = TextPrimary,
                        fontSize = 13.sp,
                    )
                    Text(
                        text = stringResource(R.string.settings_widget_left_tap_zoning_description),
                        color = TextSecondary,
                        fontSize = 11.sp,
                    )
                }
                Switch(
                    checked = leftTapApp.enabled,
                    onCheckedChange = { prefs.setLeftTapZoningEnabled(it) },
                    enabled = enabled,
                    colors = bydSwitchColors(),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_widget_left_tap_label), color = TextPrimary, fontSize = 13.sp)
                    Text(
                        text = stringResource(R.string.settings_widget_left_tap_description),
                        color = TextSecondary,
                        fontSize = 11.sp,
                    )
                }
                Row(
                    modifier = Modifier
                        .clickable(enabled = leftTapApp.enabled && enabled) {
                            showLeftTapPicker = true
                        }
                        .background(
                            color = if (leftTapApp.enabled && enabled) CardSurfaceElevated else CardSurface,
                            shape = RoundedCornerShape(999.dp),
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = leftTapApp.label,
                        color = if (leftTapApp.enabled && enabled) TextPrimary else TextMuted,
                        fontSize = 12.sp,
                    )
                }
            }
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
private fun PlacesSection(onNavigateToPlaces: () -> Unit) {
    SectionHeader(text = stringResource(R.string.settings_places_section_header))
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onNavigateToPlaces() }
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = androidx.compose.material.icons.Icons.Outlined.Place,
                contentDescription = null,
                tint = AccentGreen,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_places_entry_title), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(stringResource(R.string.settings_places_entry_description), color = TextSecondary, fontSize = 12.sp)
            }
        }
    }
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

    SectionHeader(text = stringResource(R.string.settings_display_mirror_header))
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.DirectionsCar,
                contentDescription = null,
                tint = AccentGreen,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_display_mirror_title), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(
                    stringResource(R.string.settings_display_mirror_desc),
                    color = TextSecondary, fontSize = 12.sp,
                )
            }
            Switch(
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
                colors = bydSwitchColors(),
            )
        }
    }

    // Trigger-button row — only meaningful while the feature (and thus the a11y service) is on, so
    // learning is gated on `enabled`. Tapping opens the learn dialog.
    if (enabled) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .clickable { learning = true }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = null,
                    tint = AccentGreen,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_display_button_title), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(steeringButtonLabel(triggerKey), color = TextSecondary, fontSize = 12.sp, maxLines = 1)
                }
                Text(stringResource(R.string.settings_display_button_change), color = AccentGreen, fontSize = 13.sp)
            }
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

    // App to project — defaults to Yandex Navi. Reuses the Automation app picker. The new app takes
    // effect on the next star press / projection start, not live (we don't migrate a running task).
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clickable { pickingApp = true }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Apps,
                contentDescription = null,
                tint = AccentGreen,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_display_app_title), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(targetLabel, color = TextSecondary, fontSize = 12.sp, maxLines = 1)
            }
        }
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
    val applySize: () -> Unit = {
        prefs.edit()
            .putInt(ClusterProjectionManager.KEY_WIDTH_PCT, widthPct)
            .putInt(ClusterProjectionManager.KEY_HEIGHT_PCT, heightPct)
            .apply()
        ClusterProjectionManager.reproject(
            context, entryPoint.helperClient(), entryPoint.helperBootstrap())
    }
    ClusterSizeSlider(stringResource(R.string.settings_display_size_width), widthPct, enabled, { widthPct = it }, applySize)
    ClusterSizeSlider(stringResource(R.string.settings_display_size_height), heightPct, enabled, { heightPct = it }, applySize)
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
private fun ClusterSizeSlider(
    label: String,
    pct: Int,
    enabled: Boolean,
    onChange: (Int) -> Unit,
    onFinished: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = if (enabled) TextPrimary else TextMuted, fontSize = 14.sp)
            Text(
                "$pct%",
                color = if (enabled) AccentGreen else TextMuted,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Slider(
            value = pct.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            onValueChangeFinished = onFinished,
            valueRange = MIN_PROJECTION_PCT.toFloat()..MAX_PROJECTION_PCT.toFloat(),
            steps = (MAX_PROJECTION_PCT - MIN_PROJECTION_PCT) / 2 - 1,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = AccentGreen,
                activeTrackColor = AccentGreen,
            ),
        )
    }
}

@Composable
private fun AppSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    val lang by viewModel.appLanguage.collectAsState()
    LanguageBlock(currentLang = lang, onLanguageChange = viewModel::setAppLanguage)

    // SAF picker for restore — must be declared at composable top level
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.restoreConfig(uri)
    }
    var showRestoreConfirm by remember { mutableStateOf(false) }

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
            Text(stringResource(R.string.settings_app_distance_label), color = TextSecondary, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UnitChip(stringResource(R.string.settings_unit_km), state.units == "km") { viewModel.saveUnits("km") }
                UnitChip(stringResource(R.string.settings_unit_miles), state.units == "miles") { viewModel.saveUnits("miles") }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(stringResource(R.string.settings_app_currency_label), color = TextSecondary, fontSize = 14.sp)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                SettingsRepository.CURRENCIES.forEach { currency ->
                    UnitChip(
                        label = currency.code,
                        selected = state.currency == currency.code,
                        onClick = { viewModel.saveCurrency(currency.code) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(stringResource(R.string.settings_map_tile_source_label), color = TextSecondary, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UnitChip("OpenStreetMap", state.mapTileSource == "osm") { viewModel.saveMapTileSource("osm") }
                UnitChip("Amap", state.mapTileSource == "amap") { viewModel.saveMapTileSource("amap") }
            }
        }
    }

    SectionHeader(text = stringResource(R.string.settings_app_data_header))
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { viewModel.exportCsv() },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryColor, contentColor = NavyDark)
            ) {
                Text(stringResource(R.string.settings_export_csv_button), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            val errorPrefix = stringResource(R.string.settings_error_prefix)
            if (state.exportStatus != null) {
                Text(
                    state.exportStatus!!,
                    color = if (state.exportStatus!!.startsWith(errorPrefix)) SocRed else PrimaryColor,
                    fontSize = 12.sp
                )
            }

            // Log recording start/stop
            Button(
                onClick = {
                    if (state.isRecordingLogs) viewModel.stopLogRecording()
                    else viewModel.startLogRecording()
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.isRecordingLogs) SocRed else AccentPurple,
                    contentColor = NavyDark
                )
            ) {
                Text(
                    if (state.isRecordingLogs) stringResource(R.string.settings_log_recording_stop_button)
                    else stringResource(R.string.settings_log_recording_start_button),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            if (state.logSaveStatus != null) {
                Text(
                    state.logSaveStatus!!,
                    color = if (state.logSaveStatus!!.startsWith(errorPrefix)) SocRed else PrimaryColor,
                    fontSize = 12.sp
                )
            }

            // Export full config backup (DB + prefs) as a zip to Downloads
            Button(
                onClick = { viewModel.exportConfig() },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryColor, contentColor = NavyDark)
            ) {
                Text(
                    stringResource(R.string.settings_config_export_button),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            // Restore config backup: confirm dialog then SAF zip picker
            Button(
                onClick = { showRestoreConfirm = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentPurple, contentColor = NavyDark)
            ) {
                Text(
                    stringResource(R.string.settings_config_restore_button),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            if (state.configStatus != null) {
                Text(
                    state.configStatus!!,
                    color = if (state.configStatus!!.startsWith(errorPrefix)) SocRed else PrimaryColor,
                    fontSize = 12.sp,
                )
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
            Text(
                "BYDMate v${state.appVersion}",
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(stringResource(R.string.settings_copyright), color = TextSecondary, fontSize = 14.sp)
            if (state.lastBootInfo != null) {
                Text(
                    stringResource(R.string.settings_autostart_recorded, state.lastBootInfo!!),
                    color = AccentGreen,
                    fontSize = 12.sp
                )
            } else {
                Text(
                    stringResource(R.string.settings_autostart_not_recorded),
                    color = SocRed,
                    fontSize = 12.sp
                )
            }
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(stringResource(R.string.settings_update_check_toggle_label), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(
                        stringResource(R.string.settings_update_check_toggle_description),
                        color = TextSecondary, fontSize = 12.sp
                    )
                }
                Switch(
                    checked = state.autoCheckUpdates,
                    onCheckedChange = { viewModel.setAutoCheckUpdates(it) },
                    colors = bydSwitchColors(),
                )
            }

            Button(
                onClick = { viewModel.showUpdateDialog() },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue, contentColor = NavyDark)
            ) {
                Text(stringResource(R.string.settings_update_check_button), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Polling", color = TextPrimary, fontSize = 14.sp)
                Switch(
                    checked = state.aliceEnabled,
                    onCheckedChange = { viewModel.toggleAlice(it) },
                    colors = bydSwitchColors(),
                )
            }
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
            Button(
                onClick = { viewModel.saveAliceSettings() },
                enabled = state.aliceEndpoint.isNotBlank() && state.aliceApiKey.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = NavyDark)
            ) {
                Text("Сохранить", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            state.aliceSaveStatus?.let {
                Text(it, color = AccentGreen, fontSize = 12.sp)
            }
            Text(
                "Polling опрашивает Worker каждую секунду\nи выполняет команды через D+ API",
                color = TextMuted, fontSize = 11.sp, lineHeight = 15.sp
            )
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
    val applyLang: (String) -> Unit = { lang ->
        if (lang != currentLang) onLanguageChange(lang)
    }

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
            LanguageOption(
                label = stringResource(R.string.settings_lang_russian),
                selected = currentLang == "ru",
                onClick = { applyLang("ru") },
            )
            LanguageOption(
                label = "English",
                selected = currentLang == "en",
                onClick = { applyLang("en") },
            )
            LanguageOption(
                label = "简体中文",
                selected = currentLang == "zh",
                onClick = { applyLang("zh") },
            )
        }
    }
}

@Composable
private fun LanguageOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(
                selectedColor = AccentGreen,
                unselectedColor = TextMuted,
            ),
        )
        Text(
            text = label,
            color = TextPrimary,
            fontSize = 14.sp,
            modifier = Modifier.padding(start = 4.dp),
        )
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

@Composable
private fun UnitChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(text = label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        },
        shape = RoundedCornerShape(8.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = PrimaryColor,
            selectedLabelColor = Color.White,
            containerColor = CardSurfaceElevated,
            labelColor = TextSecondary
        ),
        border = FilterChipDefaults.filterChipBorder(
            borderColor = Color.Transparent,
            selectedBorderColor = Color.Transparent,
            enabled = true,
            selected = selected
        )
    )
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

