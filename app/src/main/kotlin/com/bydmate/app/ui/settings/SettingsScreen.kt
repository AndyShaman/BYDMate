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
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.Lifecycle
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
import com.bydmate.app.ui.components.bydSwitchColors
import com.bydmate.app.ui.theme.*

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
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = "Настройки",
            color = TextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Two-column layout
        Row(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // LEFT COLUMN: Battery & tariffs + Data
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SectionHeader(text = "Батарея и тарифы")
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = CardSurface),
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
                    colors = CardDefaults.cardColors(containerColor = CardSurface),
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

                SectionHeader(text = "Источник данных поездок")
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = CardSurface),
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
                    colors = CardDefaults.cardColors(containerColor = CardSurface),
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

                SectionHeader(text = "Данные")
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = CardSurface),
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
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryColor, contentColor = Color.White)
                        ) {
                            Text("Экспорт CSV", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                        if (state.exportStatus != null) {
                            Text(
                                state.exportStatus!!,
                                color = if (state.exportStatus!!.startsWith("Ошибка")) SocRed else PrimaryColor,
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
                                contentColor = Color.White
                            )
                        ) {
                            Text(
                                if (state.isRecordingLogs) "⏺ Остановить запись" else "Запись логов",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        if (state.logSaveStatus != null) {
                            Text(
                                state.logSaveStatus!!,
                                color = if (state.logSaveStatus!!.startsWith("Ошибка")) SocRed else PrimaryColor,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            // RIGHT COLUMN: Units & currency + Consumption thresholds + About
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SectionHeader(text = "Единицы и валюта")
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = CardSurface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Расстояние", color = TextSecondary, fontSize = 14.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            UnitChip("км", state.units == "km") { viewModel.saveUnits("km") }
                            UnitChip("мили", state.units == "miles") { viewModel.saveUnits("miles") }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Валюта", color = TextSecondary, fontSize = 14.sp)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        ) {
                            SettingsRepository.CURRENCIES.forEach { currency ->
                                UnitChip(
                                    label = "${currency.symbol} ${currency.label}",
                                    selected = state.currency == currency.code,
                                    onClick = { viewModel.saveCurrency(currency.code) }
                                )
                            }
                        }
                    }
                }

                // --- Плавающий виджет ---
                val widgetCtx = LocalContext.current
                val widgetPrefs = remember { WidgetPreferences(widgetCtx) }
                val widgetEnabled by widgetPrefs.enabledFlow().collectAsStateWithLifecycle(initialValue = widgetPrefs.isEnabled())
                val widgetAlpha by widgetPrefs.alphaFlow().collectAsStateWithLifecycle(initialValue = widgetPrefs.getAlpha())

                SectionHeader(text = "Плавающий виджет")
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 0.dp, vertical = 0.dp)) {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = CardSurface),
                        modifier = Modifier.fillMaxWidth()
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
                                    checked = widgetEnabled,
                                    onCheckedChange = { requested ->
                                        if (requested) {
                                            if (AndroidSettings.canDrawOverlays(widgetCtx)) {
                                                widgetPrefs.setEnabled(true)
                                                WidgetController.attach(widgetCtx)
                                            } else {
                                                val intent = Intent(
                                                    AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                    Uri.parse("package:${widgetCtx.packageName}"),
                                                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                                                widgetCtx.startActivity(intent)
                                            }
                                        } else {
                                            widgetPrefs.setEnabled(false)
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
                                    widgetPrefs.resetPosition()
                                    if (widgetEnabled && AndroidSettings.canDrawOverlays(widgetCtx)) {
                                        WidgetController.detach()
                                        WidgetController.attach(widgetCtx)
                                    }
                                },
                                enabled = widgetEnabled,
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
                                        text = "${(widgetAlpha * 100).toInt()}%",
                                        color = TextMuted,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                                Slider(
                                    value = widgetAlpha,
                                    onValueChange = { widgetPrefs.setAlpha(it) },
                                    valueRange = 0.3f..1.0f,
                                    enabled = widgetEnabled,
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
                }

                SectionHeader(text = "AI Инсайты")
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = CardSurface),
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

                SectionHeader(text = "Места")
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = CardSurface),
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
                            imageVector = Icons.Outlined.Place,
                            contentDescription = null,
                            tint = AccentGreen,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Точки для автоматизации", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text("Дом, работа, любимые места — триггеры «Въезд» / «Выезд»", color = TextSecondary, fontSize = 12.sp)
                        }
                    }
                }

                SectionHeader(text = "О приложении")
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = CardSurface),
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
                            modifier = Modifier.clickable { viewModel.onVersionTap() }
                        )
                        Text("\u00A9 2026 AndyShaman", color = TextSecondary, fontSize = 14.sp)
                        if (state.lastBootInfo != null) {
                            Text(
                                "Автозапуск: ${state.lastBootInfo}",
                                color = AccentGreen,
                                fontSize = 12.sp
                            )
                        } else {
                            Text(
                                "Автозапуск: не зафиксирован",
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
                                Text("Проверять обновления", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    "Через 30 секунд после запуска проверять GitHub и предлагать обновиться",
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
                            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue, contentColor = Color.White)
                        ) {
                            Text("Проверить обновления сейчас", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }

                // Hidden Smart Home section — unlocked by tapping version 7 times
                if (state.devModeUnlocked) {
                    SectionHeader(text = "Умный дом")
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = CardSurface),
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
            }
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
            val km = status.lifetimeKm?.let { "%.0f км".format(it) } ?: "—"
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

