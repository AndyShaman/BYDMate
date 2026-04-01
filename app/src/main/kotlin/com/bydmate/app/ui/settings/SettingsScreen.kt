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
                        // Chain log — full autostart diagnostic
                        val chainLog = state.chainLog
                        if (chainLog != null) {
                            Text("Лог автозапуска:", color = TextSecondary, fontSize = 11.sp)
                            Text(
                                chainLog,
                                color = TextMuted,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 14.sp
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
