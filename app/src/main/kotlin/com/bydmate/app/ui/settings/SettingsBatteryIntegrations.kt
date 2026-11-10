package com.bydmate.app.ui.settings

import android.net.Uri
import com.bydmate.app.data.charging.ChargeConnector
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.res.stringResource
import com.bydmate.app.R
import com.bydmate.app.agent.LlmAgentBackend
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One-line description of the period in force today, shown under the «Тарифы и периоды»
 * button so the section stays compact but still tells the driver what is being charged.
 */
@Composable
private fun currentTariffPeriodSummary(state: SettingsUiState, hybrid: Boolean): String {
    val current = state.tariffPeriods.firstOrNull { it.startTs <= System.currentTimeMillis() }
        ?: state.tariffPeriods.lastOrNull()
        ?: return stringResource(R.string.settings_tariff_periods_empty)
    val date = remember(current.startTs) {
        if (current.startTs <= 0L) null
        else SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(current.startTs))
    }
    val rates = stringResource(
        R.string.settings_tariff_periods_summary,
        "%.2f".format(current.homeRate), "%.2f".format(current.dcRate), state.currencySymbol,
        "%.0f".format(current.acLossPct), "%.0f".format(current.dcLossPct),
    ) + fuelPriceSuffix(current.fuelPricePerLiter, hybrid, stringResource(R.string.dashboard_unit_liters))
    return if (date == null) rates
    else stringResource(R.string.settings_tariff_periods_summary_since, date, rates)
}

@Composable
internal fun BatterySection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onOpenTariffPeriods: () -> Unit,
) {
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
            SettingChipRow(
                title = stringResource(R.string.settings_charge_connector_label),
                description = stringResource(R.string.settings_charge_connector_desc),
                options = ChargeConnector.entries.map { it.label },
                selectedIndex = state.chargeConnector.ordinal,
                onSelect = { idx -> viewModel.saveChargeConnector(ChargeConnector.entries[idx]) },
            )
            SettingDivider()
            val vehicleKind by viewModel.vehicleKind.collectAsStateWithLifecycle()
            SettingActionRow(
                title = stringResource(R.string.settings_tariff_periods_title),
                description = currentTariffPeriodSummary(
                    state, hybrid = vehicleKind == com.bydmate.app.data.nativestack.VehicleKind.HYBRID,
                ),
                buttonLabel = stringResource(R.string.settings_tariff_periods_button),
                onClick = { viewModel.showTariffPeriods(); onOpenTariffPeriods() },
                style = SettingButtonStyle.Primary
            )
            SettingHint(stringResource(R.string.settings_tariff_periods_hint))
            state.tariffRecalcStatus?.let {
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

    SectionHeader(text = stringResource(R.string.settings_range_calc_section_header))
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
                title = stringResource(R.string.settings_range_calc_method_label),
                description = stringResource(R.string.settings_range_calc_method_desc),
                options = listOf(
                    stringResource(R.string.settings_range_calc_auto),
                    stringResource(R.string.settings_range_calc_manual),
                ),
                selectedIndex = if (state.rangeCalcMethod == SettingsRepository.RANGE_CALC_MANUAL) 1 else 0,
                onSelect = { idx ->
                    viewModel.saveRangeCalcMethod(
                        if (idx == 1) SettingsRepository.RANGE_CALC_MANUAL else SettingsRepository.RANGE_CALC_AUTO
                    )
                },
            )
            if (state.rangeCalcMethod == SettingsRepository.RANGE_CALC_MANUAL) {
                SettingDivider()
                SettingActionRow(
                    title = stringResource(R.string.settings_range_calc_edit_table_button),
                    description = stringResource(R.string.settings_range_calc_edit_table_desc),
                    buttonLabel = stringResource(R.string.settings_range_calc_edit_table_button),
                    onClick = { viewModel.showManualRangeTableDialog() },
                )
            }
        }
    }
}

@Composable
internal fun IntegrationsSection(state: SettingsUiState, viewModel: SettingsViewModel) {
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
                traceId = "abrp_telemetry",
                description = stringResource(R.string.settings_abrp_telemetry_description),
                checked = state.abrpTelemetryEnabled,
                onCheckedChange = { viewModel.toggleAbrpTelemetry(it) },
            )
            SettingsTextField(
                label = stringResource(R.string.settings_abrp_token_label),
                value = state.abrpUserToken,
                onValueChange = { viewModel.updateAbrpUserToken(it) },
                keyboardType = KeyboardType.Password,
                secret = true
            )
            SettingToggleRow(
                title = stringResource(R.string.settings_abrp_location_label),
                traceId = "abrp_location",
                description = stringResource(R.string.settings_abrp_location_description),
                checked = state.abrpSendLocation,
                onCheckedChange = { viewModel.toggleAbrpSendLocation(it) },
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

    SectionHeader(text = stringResource(R.string.settings_webhook_section_header))
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
                title = stringResource(R.string.settings_webhook_label),
                traceId = "webhook",
                description = stringResource(R.string.settings_webhook_description),
                checked = state.webhookEnabled,
                onCheckedChange = { viewModel.toggleWebhook(it) },
            )
            SettingsTextField(
                label = stringResource(R.string.settings_webhook_url_label),
                value = state.webhookUrl,
                onValueChange = { viewModel.updateWebhookUrl(it) },
                keyboardType = KeyboardType.Uri,
            )
            SettingsTextField(
                label = stringResource(R.string.settings_webhook_secret_label),
                value = state.webhookSecret,
                onValueChange = { viewModel.updateWebhookSecret(it) },
                keyboardType = KeyboardType.Password,
                secret = true
            )
            SettingToggleRow(
                title = stringResource(R.string.settings_webhook_location_label),
                traceId = "webhook_location",
                description = stringResource(R.string.settings_webhook_location_description),
                checked = state.webhookSendLocation,
                onCheckedChange = { viewModel.toggleWebhookSendLocation(it) },
            )
            SettingActionRow(
                title = stringResource(R.string.settings_webhook_save_button),
                buttonLabel = stringResource(R.string.settings_webhook_save_button),
                onClick = { viewModel.saveWebhookSettings() },
                style = SettingButtonStyle.Primary,
            )
            state.webhookSaveStatus?.let {
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
            keyboardType = KeyboardType.Password,
            secret = true
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
            keyboardType = KeyboardType.Password,
            secret = true
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
            keyboardType = KeyboardType.Password,
            secret = true
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
        SettingsTextField(
            label = stringResource(R.string.settings_custom_extra_json_label),
            value = state.customExtraJson,
            onValueChange = { viewModel.saveCustomExtraJson(it) },
            keyboardType = KeyboardType.Text,
            singleLine = false
        )
        SettingHint(stringResource(R.string.settings_custom_extra_json_hint))
        if (state.customExtraJson.isNotBlank() &&
            LlmAgentBackend.parseExtraJson(state.customExtraJson) == null
        ) {
            Text(
                stringResource(R.string.settings_custom_extra_json_invalid),
                color = SocRed, fontSize = 12.sp, lineHeight = 16.sp
            )
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
                secret = true
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
