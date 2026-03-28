package com.bydmate.app.ui.settings

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.horizontalScroll
import androidx.hilt.navigation.compose.hiltViewModel
import com.bydmate.app.data.repository.SettingsRepository

private val BackgroundColor = Color(0xFF0D0D0D)
private val CardBackground = Color(0xFF1E1E1E)
private val PrimaryColor = Color(0xFF4CAF50)
private val SecondaryTextColor = Color(0xFF9E9E9E)
private val TextFieldBorderColor = Color(0xFF3C3C3C)
private val TextFieldFocusedBorderColor = Color(0xFF4CAF50)

// Settings screen - battery capacity, tariffs, display preferences, CSV export
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundColor)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Screen title
        Text(
            text = "Настройки",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(24.dp))

        // -- Battery & tariffs section --
        SectionHeader(text = "Батарея и тарифы")
        Spacer(modifier = Modifier.height(12.dp))

        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = CardBackground),
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
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // -- Units section --
        SectionHeader(text = "Единицы измерения")
        Spacer(modifier = Modifier.height(12.dp))

        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = CardBackground),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Расстояние",
                    color = SecondaryTextColor,
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
            colors = CardDefaults.cardColors(containerColor = CardBackground),
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
            colors = CardDefaults.cardColors(containerColor = CardBackground),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Импорт поездок из встроенной базы BYD (energydata). " +
                        "Импортируются расстояние и расход по данным бортового компьютера.",
                    color = SecondaryTextColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )

                Button(
                    onClick = { viewModel.importBydHistory() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFF9800),
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
                            Color(0xFFF44336)
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
            colors = CardDefaults.cardColors(containerColor = CardBackground),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Экспорт всех поездок и зарядок в CSV файлы в папку Downloads",
                    color = SecondaryTextColor,
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
                            Color(0xFFF44336)
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
            colors = CardDefaults.cardColors(containerColor = CardBackground),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "BYDMate v${state.appVersion}",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )

                Button(
                    onClick = { viewModel.checkForUpdate() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2196F3),
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
                            Color(0xFFF44336)
                        } else {
                            PrimaryColor
                        },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
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
        color = Color.White,
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
            focusedBorderColor = TextFieldFocusedBorderColor,
            unfocusedBorderColor = TextFieldBorderColor,
            focusedLabelColor = PrimaryColor,
            unfocusedLabelColor = SecondaryTextColor,
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
            containerColor = Color(0xFF2C2C2C),
            labelColor = SecondaryTextColor
        ),
        border = FilterChipDefaults.filterChipBorder(
            borderColor = Color.Transparent,
            selectedBorderColor = Color.Transparent,
            enabled = true,
            selected = selected
        )
    )
}
