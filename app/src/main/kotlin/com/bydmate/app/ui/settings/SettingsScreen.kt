package com.bydmate.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.font.FontFamily
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
import com.bydmate.app.ui.theme.*

private val PrimaryColor = AccentGreen

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

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
                            onValueChange = { viewModel.saveHomeTariff(it) },
                            keyboardType = KeyboardType.Decimal
                        )
                        SettingsTextField(
                            label = "Тариф DC (${state.currencySymbol}/кВт·ч)",
                            value = state.dcTariff,
                            onValueChange = { viewModel.saveDcTariff(it) },
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
                        Text("BYDMate v${state.appVersion}", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
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

                        Button(
                            onClick = { viewModel.showUpdateDialog() },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue, contentColor = Color.White)
                        ) {
                            Text("Проверить обновления", fontSize = 14.sp, fontWeight = FontWeight.Medium)
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
