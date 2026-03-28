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

// Settings screen - battery capacity, tariffs, display preferences, CSV export
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(NavyDark, NavyDeep)))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Screen title
        Text(
            text = "Настройки",
            color = TextPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(24.dp))

        // -- Battery & tariffs section --
        SectionHeader(text = "Батарея и тарифы")
        Spacer(modifier = Modifier.height(12.dp))

        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = CardSurface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Battery capacity
                SettingsTextField(
                    label = "Ёмкость батареи (кВт·ч)",
                    value = state.batteryCapacity,
                    onValueChange = { viewModel.saveBatteryCapacity(it) },
                    keyboardType = KeyboardType.Decimal
                )

                // Home (AC) tariff
                SettingsTextField(
                    label = "Тариф дома (${state.currencySymbol}/кВт·ч)",
                    value = state.homeTariff,
                    onValueChange = { viewModel.saveHomeTariff(it) },
                    keyboardType = KeyboardType.Decimal
                )

                // DC fast-charge tariff
                SettingsTextField(
                    label = "Тариф DC (${state.currencySymbol}/кВт·ч)",
                    value = state.dcTariff,
                    onValueChange = { viewModel.saveDcTariff(it) },
                    keyboardType = KeyboardType.Decimal
                )

                // Trip cost tariff selector
                Text(
                    text = "Тариф для стоимости поездок",
                    color = TextSecondary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    UnitChip(
                        label = "Домашний (AC)",
                        selected = state.tripCostTariff == "home",
                        onClick = { viewModel.saveTripCostTariff("home") }
                    )
                    UnitChip(
                        label = "DC",
                        selected = state.tripCostTariff == "dc",
                        onClick = { viewModel.saveTripCostTariff("dc") }
                    )
                    UnitChip(
                        label = "Свой",
                        selected = state.tripCostTariff != "home" && state.tripCostTariff != "dc",
                        onClick = { viewModel.saveTripCostTariff(state.homeTariff) }
                    )
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

        Spacer(modifier = Modifier.height(24.dp))

        // -- Units section --
        SectionHeader(text = "Единицы измерения")
        Spacer(modifier = Modifier.height(12.dp))

        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = CardSurface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Расстояние",
                    color = TextSecondary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Unit selector using FilterChips (segmented button alternative)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    UnitChip(
                        label = "км",
                        selected = state.units == "km",
                        onClick = { viewModel.saveUnits("km") }
                    )
                    UnitChip(
                        label = "мили",
                        selected = state.units == "miles",
                        onClick = { viewModel.saveUnits("miles") }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // -- Currency section --
        SectionHeader(text = "Валюта")
        Spacer(modifier = Modifier.height(12.dp))

        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = CardSurface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
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

        Spacer(modifier = Modifier.height(24.dp))

        // -- Import BYD history section --
        SectionHeader(text = "История BYD")
        Spacer(modifier = Modifier.height(12.dp))

        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = CardSurface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Импорт поездок из встроенной базы BYD (energydata). " +
                        "Импортируются расстояние и расход по данным бортового компьютера.",
                    color = TextSecondary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )

                Button(
                    onClick = { viewModel.importBydHistory() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentOrange,
                        contentColor = Color.White
                    )
                ) {
                    Text(
                        text = "Импорт истории BYD",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }

                if (state.importStatus != null) {
                    Text(
                        text = state.importStatus!!,
                        color = if (state.importStatus!!.startsWith("Ошибка")) {
                            SocRed
                        } else {
                            PrimaryColor
                        },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // -- Export section --
        SectionHeader(text = "Экспорт данных")
        Spacer(modifier = Modifier.height(12.dp))

        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = CardSurface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Экспорт всех поездок и зарядок в CSV файлы в папку Downloads",
                    color = TextSecondary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )

                Button(
                    onClick = { viewModel.exportCsv() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryColor,
                        contentColor = Color.White
                    )
                ) {
                    Text(
                        text = "Экспорт CSV",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }

                // Export status message
                if (state.exportStatus != null) {
                    Text(
                        text = state.exportStatus!!,
                        color = if (state.exportStatus!!.startsWith("Ошибка")) {
                            SocRed
                        } else {
                            PrimaryColor
                        },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // -- About & update section --
        SectionHeader(text = "О приложении")
        Spacer(modifier = Modifier.height(12.dp))

        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = CardSurface),
            modifier = Modifier.fillMaxWidth()
        ) {
            val context = LocalContext.current
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "BYDMate v${state.appVersion}",
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )

                Text(
                    text = "\u00A9 2026 AndyShaman",
                    color = TextSecondary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )

                Text(
                    text = "github.com/AndyShaman/BYDMate",
                    color = AccentBlue,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable {
                        val intent = Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/AndyShaman/BYDMate"))
                        context.startActivity(intent)
                    }
                )

                Text(
                    text = "GPLv3 License",
                    color = TextSecondary,
                    fontSize = 12.sp
                )

                Button(
                    onClick = { viewModel.checkForUpdate() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentBlue,
                        contentColor = Color.White
                    )
                ) {
                    Text(
                        text = "Проверить обновления",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }

                if (state.updateStatus != null) {
                    Text(
                        text = state.updateStatus!!,
                        color = if (state.updateStatus!!.startsWith("Ошибка")) {
                            SocRed
                        } else {
                            PrimaryColor
                        },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // -- Diagnostics section --
        Spacer(modifier = Modifier.height(24.dp))
        SectionHeader(text = "Диагностика")
        Spacer(modifier = Modifier.height(12.dp))

        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = CardSurface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Проверка доступа к БД BYD, DiPlus API, разрешений и данных в нашей базе",
                    color = TextSecondary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )

                Button(
                    onClick = { viewModel.runDiagnostics() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentPurple,
                        contentColor = Color.White
                    )
                ) {
                    Text(
                        text = "Запустить диагностику",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }

                if (state.diagnosticLog != null) {
                    Text(
                        text = state.diagnosticLog!!,
                        color = TextPrimary,
                        fontSize = 11.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        lineHeight = 15.sp
                    )
                }
            }
        }

        // Bottom padding for navigation bar clearance
        Spacer(modifier = Modifier.height(32.dp))
    }
}

/** Section header text. */
@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        color = TextPrimary,
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth()
    )
}

/** Themed OutlinedTextField for settings values. */
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

/** FilterChip styled as a segment button for unit selection. */
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
            Text(
                text = label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
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
