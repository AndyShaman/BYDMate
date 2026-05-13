package com.bydmate.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import com.bydmate.app.ui.widget.WidgetController
import com.bydmate.app.ui.widget.WidgetPreferences
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.Icons
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
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
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
import androidx.compose.runtime.getValue
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
import com.bydmate.app.data.remote.OpenRouterModel
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.ui.components.AppLaunchPickerDialog
import com.bydmate.app.ui.components.bydSwitchColors
import com.bydmate.app.ui.theme.*

private enum class SettingsSection(val label: String, val icon: ImageVector) {
    BATTERY("Авто и батарея", Icons.Outlined.BatteryChargingFull),
    TRIPS("Поездки", Icons.Outlined.DirectionsCar),
    INTEGRATIONS("Интеграции", Icons.Outlined.Link),
    WIDGET("Виджет", Icons.Outlined.PhoneAndroid),
    PLACES("Места", Icons.Outlined.Place),
    APP("Приложение", Icons.Outlined.Settings),
    SMART_HOME("Умный дом", Icons.Outlined.Home),
}

private val PrimaryColor = AccentGreen

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateToPlaces: () -> Unit = {}
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
            title = { Text("Пересчитать стоимость?", color = TextPrimary) },
            text = {
                Text(
                    "Все поездки будут пересчитаны по тарифу $tariffLabel ${state.currencySymbol}/кВт·ч.\nУже посчитанные значения будут заменены.",
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmRecalc() }) {
                    Text("Пересчитать", color = AccentOrange)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.hideRecalcConfirm() }) {
                    Text("Отмена", color = TextSecondary)
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(NavyDark, NavyDeep)))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = "Настройки",
            color = TextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )

        Spacer(modifier = Modifier.height(12.dp))

        var selected by rememberSaveable { mutableStateOf(SettingsSection.BATTERY) }

        Row(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsRail(
                selected = selected,
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
                    when (selected) {
                        SettingsSection.BATTERY -> BatterySection(state, viewModel)
                        SettingsSection.TRIPS -> TripsSection(state, viewModel)
                        SettingsSection.INTEGRATIONS -> IntegrationsSection(state, viewModel)
                        SettingsSection.WIDGET -> WidgetSection()
                        SettingsSection.PLACES -> Text("Места — TODO", color = TextMuted)
                        SettingsSection.APP -> Text("Приложение — TODO", color = TextMuted)
                        SettingsSection.SMART_HOME -> Text("Умный дом — TODO", color = TextMuted)
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
                "Разделы",
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
            text = section.label,
            color = fg,
            fontSize = 14.sp,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun BatterySection(state: SettingsUiState, viewModel: SettingsViewModel) {
    SectionHeader(text = "Батарея и тарифы")
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
                label = "Ёмкость батареи (кВт·ч)",
                value = state.batteryCapacity,
                onValueChange = { viewModel.saveBatteryCapacity(it) },
                keyboardType = KeyboardType.Decimal
            )
            SettingsTextField(
                label = "Тариф дома (${state.currencySymbol}/кВт·ч)",
                value = state.homeTariff,
                onValueChange = { viewModel.updateHomeTariff(it) },
                keyboardType = KeyboardType.Decimal
            )
            SettingsTextField(
                label = "Тариф DC (${state.currencySymbol}/кВт·ч)",
                value = state.dcTariff,
                onValueChange = { viewModel.updateDcTariff(it) },
                keyboardType = KeyboardType.Decimal
            )
            Text("Тариф поездок", color = TextSecondary, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UnitChip("AC", state.tripCostTariff == "home") { viewModel.saveTripCostTariff("home") }
                UnitChip("DC", state.tripCostTariff == "dc") { viewModel.saveTripCostTariff("dc") }
                UnitChip("Свой", state.tripCostTariff != "home" && state.tripCostTariff != "dc") {
                    viewModel.saveTripCostTariff(state.homeTariff)
                }
            }
            if (state.tripCostTariff != "home" && state.tripCostTariff != "dc") {
                SettingsTextField(
                    label = "Свой тариф (${state.currencySymbol}/кВт·ч)",
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
                Text("Сохранить тарифы", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            state.tariffSaveStatus?.let {
                Text(it, color = AccentGreen, fontSize = 12.sp)
            }
            Text(
                "Новый тариф применяется к будущим поездкам.\nУже посчитанные поездки не изменятся.",
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
                Text("Пересчитать все поездки", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            state.recalcStatus?.let {
                Text(it, color = AccentGreen, fontSize = 12.sp)
            }
            Text(
                "Пересчитает стоимость всех поездок\nпо текущему тарифу.",
                color = TextMuted, fontSize = 11.sp, lineHeight = 15.sp
            )
        }
    }

    SectionHeader(text = "Пороги расхода")
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
                label = "Хороший < (кВт·ч/100км)",
                value = state.consumptionGood,
                onValueChange = { viewModel.saveConsumptionGood(it) },
                keyboardType = KeyboardType.Decimal
            )
            SettingsTextField(
                label = "Плохой > (кВт·ч/100км)",
                value = state.consumptionBad,
                onValueChange = { viewModel.saveConsumptionBad(it) },
                keyboardType = KeyboardType.Decimal
            )
        }
    }
}

@Composable
private fun TripsSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    SectionHeader(text = "Источник данных поездок")
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Leopard 3 — BYD energydata.\nSong и другие без встроенной базы — DiPlus TripInfo.",
                color = TextSecondary,
                fontSize = 11.sp,
            )
            DataSourceOption(
                label = "BYD energydata",
                selected = state.dataSource == "ENERGYDATA",
                onClick = { viewModel.setDataSource("ENERGYDATA") },
            )
            DataSourceOption(
                label = "DiPlus TripInfo",
                selected = state.dataSource == "DIPLUS",
                onClick = { viewModel.setDataSource("DIPLUS") },
            )
            Text(
                text = "Если после 2–3 поездок список пустой — переключи режим.",
                color = TextSecondary,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (state.dataSourceStatus != null) {
                Text(
                    state.dataSourceStatus!!,
                    color = PrimaryColor,
                    fontSize = 11.sp,
                )
            }
        }
    }

    SectionHeader(text = "Системные данные (экспериментально)")
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Расширенные данные с машины: SoH батареи, истинный пробег от BMS, статистика зарядок. Только чтение.",
                color = TextSecondary,
                fontSize = 12.sp,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Включить", color = TextPrimary, fontSize = 14.sp)
                Switch(
                    checked = state.autoserviceEnabled,
                    onCheckedChange = { enabled ->
                        viewModel.enableAutoservice(enabled)
                    },
                    colors = bydSwitchColors(),
                )
            }
            AutoserviceStatusBlock(status = state.autoserviceStatus)
        }
    }
}

@Composable
private fun IntegrationsSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    SectionHeader(text = "ABRP — телеметрия")
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
                        "Живые данные → A Better Route Planner",
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "Живые данные (SOC, мощность, температуры, одометр, давление шин) отправляются в Iternio Telemetry API для актуального плана в ABRP. GPS не передаётся — ABRP читает координаты сам.",
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
                label = "Токен живых данных из ABRP",
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
                Text("Сохранить ABRP", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            state.abrpSaveStatus?.let {
                Text(it, color = AccentGreen, fontSize = 12.sp)
            }
        }
    }

    SectionHeader(text = "AI Инсайты")
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
                label = "OpenRouter API Key",
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
                    contentColor = Color.White
                )
            ) {
                Text(
                    if (state.openRouterModelName.isNotBlank())
                        "Модель: ${state.openRouterModelName}"
                    else "Выбрать модель",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
            }
            Button(
                onClick = { viewModel.saveAiSettings() },
                enabled = state.openRouterApiKey.isNotBlank() &&
                    state.openRouterModel.isNotBlank() &&
                    state.aiSaveStatus != "Загрузка инсайта...",
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentGreen,
                    contentColor = Color.White
                )
            ) {
                Text(
                    if (state.aiSaveStatus == "Загрузка инсайта...") "Загрузка..."
                    else "Сохранить и получить инсайт",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            if (state.aiSaveStatus != null && state.aiSaveStatus != "Загрузка инсайта...") {
                Text(
                    state.aiSaveStatus!!,
                    color = if (state.aiSaveStatus!!.startsWith("Ошибка")) SocRed else AccentGreen,
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

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                WidgetController.setPreviewMode(context, false)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            WidgetController.setPreviewMode(context, false)
        }
    }

    SectionHeader(text = "Плавающий виджет")
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
                    text = "Показывать виджет SOC",
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
                text = "• Долгий тап на виджете — скрыть до следующего открытия BYDMate.\n" +
                        "• Перетащить в корзину внизу — выключить совсем.\n" +
                        "• Обычный тап — открыть BYDMate.",
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
                Text("Сбросить позицию", fontSize = 13.sp, color = TextPrimary)
            }
            Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Прозрачность",
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
                        text = "Размер",
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

    SectionHeader(text = "Тап по виджету")
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
                        text = "Зонирование тапов",
                        color = TextPrimary,
                        fontSize = 13.sp,
                    )
                    Text(
                        text = "Левая 1/3 — открывает выбранное приложение; остальное — BYDMate.",
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
                    Text("Приложение для левого тапа", color = TextPrimary, fontSize = 13.sp)
                    Text(
                        text = "По умолчанию Яндекс.Навигатор. Если приложение удалено, тап не сработает.",
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
private fun DataSourceOption(label: String, selected: Boolean, onClick: () -> Unit) {
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
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun AutoserviceStatusBlock(status: AutoserviceStatus) {
    when (status) {
        AutoserviceStatus.NotEnabled -> Unit
        AutoserviceStatus.Disconnected -> StatusRow(
            marker = "✗",
            markerColor = TextMuted,
            title = "не подключено",
            detail = "перезапусти приложение, если ADB включён в Настройках разработчика",
            detailColor = TextSecondary,
        )
        AutoserviceStatus.AllSentinel -> StatusRow(
            marker = "⚠",
            markerColor = SocYellow,
            title = "подключено, но данные не читаются",
            detail = "возможно функция работает только на Leopard 3",
            detailColor = SocYellow,
        )
        is AutoserviceStatus.Connected -> {
            val soh = status.sohPercent?.let { "%.0f%%".format(it) } ?: "—"
            val km = status.lifetimeKm?.let { "%.1f км".format(it) } ?: "—"
            val kwh = status.lifetimeKwh?.let { "%.0f кВт·ч".format(it) } ?: "—"
            StatusRow(
                marker = "✓",
                markerColor = AccentGreen,
                title = "подключено",
                detail = "SoH $soh • lifetime $km / $kwh",
                detailColor = AccentGreen,
            )
        }
    }
}

@Composable
private fun StatusRow(
    marker: String,
    markerColor: Color,
    title: String,
    detail: String,
    detailColor: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = marker,
            color = markerColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 1.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = title, color = TextPrimary, fontSize = 13.sp)
            Text(
                text = detail,
                color = detailColor.copy(alpha = 0.85f),
                fontSize = 11.sp,
            )
        }
    }
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
                Text("Выбор модели", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Поиск") },
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
                    Text("Загрузка моделей...", color = TextSecondary, fontSize = 14.sp)
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

