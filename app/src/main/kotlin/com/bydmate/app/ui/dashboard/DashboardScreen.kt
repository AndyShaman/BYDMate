package com.bydmate.app.ui.dashboard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.bydmate.app.R
import com.bydmate.app.ui.components.SocGauge
import com.bydmate.app.ui.components.TripCard
import com.bydmate.app.ui.components.consumptionColor
import com.bydmate.app.ui.theme.*

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(NavyDark, NavyDeep)))
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        TopBar(isServiceRunning = state.isServiceRunning, diPlusConnected = state.diPlusConnected)
        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // LEFT COLUMN — fill full height with SpaceBetween
            Box(modifier = Modifier.weight(0.4f)) {
                // Ghost car background
                Image(
                    painter = painterResource(R.drawable.leopard3),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = 0.06f },
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.Center
                )
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // TOP: SOC gauge + odometer + range
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        SocGauge(soc = state.soc ?: 0, modifier = Modifier.size(150.dp))
                        Text(
                            text = if (state.odometer != null) "%.1f km".format(state.odometer) else "— km",
                            color = TextSecondary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        // Range estimate
                        val rangeText = state.estimatedRangeKm?.let { "~${"%.0f".format(it)}" } ?: "—"
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(rangeText, color = AccentGreen, fontSize = 32.sp, fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("км", color = AccentGreen.copy(alpha = 0.7f), fontSize = 18.sp,
                                fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 4.dp))
                        }
                        Text("расчётный пробег", color = TextMuted, fontSize = 12.sp)
                    }

                    // 3 compact cards: SoH, battery, idle drain
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // SoH card
                        val sohPct = state.sohPercent
                        val sohColor = when {
                            sohPct == null -> TextMuted
                            sohPct >= 95 -> AccentGreen
                            sohPct >= 85 -> SocYellow
                            else -> SocRed
                        }
                        CompactCard(
                            leftValue = state.sohPercent?.let { "%.1f%%".format(it) } ?: "—",
                            leftLabel = "SoH",
                            rightValue = state.estimatedCapacityKwh?.let { "%.1f".format(it) } ?: "—",
                            rightLabel = "кВт·ч",
                            borderColor = sohColor,
                            hasData = state.sohPercent != null,
                            onClick = { viewModel.toggleSohExpanded() }
                        )
                        // Battery card
                        CompactCard(
                            leftValue = state.avgBatTemp?.let { "${it}°C" } ?: "—",
                            leftLabel = "батарея",
                            rightValue = state.voltage12v?.let { "${"%.1f".format(it)}V" } ?: "—",
                            rightLabel = "12V",
                            borderColor = run {
                                val worst = when {
                                    state.batteryHealthStatus == "critical" || state.voltage12vStatus == "critical" -> "critical"
                                    state.batteryHealthStatus == "warning" || state.voltage12vStatus == "warning" -> "warning"
                                    else -> "ok"
                                }
                                when (worst) { "critical" -> SocRed; "warning" -> SocYellow; else -> AccentGreen }
                            },
                            onClick = { viewModel.toggleBatteryHealthExpanded() }
                        )
                        // Idle drain card
                        CompactCard(
                            leftValue = "%.1f".format(state.idleDrainKwhToday),
                            leftLabel = "кВт·ч",
                            rightValue = "%.0f".format(state.idleDrainHours) + "ч",
                            rightLabel = "стоянка",
                            borderColor = when {
                                state.idleDrainPercent > 5.0 -> SocRed
                                state.idleDrainPercent > 2.0 -> SocYellow
                                else -> AccentGreen
                            },
                            onClick = { viewModel.toggleIdleDrainExpanded() }
                        )
                    }

                    // Pop-up dialogs
                    if (state.sohExpanded) {
                        val sohPctD = state.sohPercent
                        val sohColor = when {
                            sohPctD == null -> TextMuted
                            sohPctD >= 95 -> AccentGreen
                            sohPctD >= 85 -> SocYellow
                            else -> SocRed
                        }
                        CardDetailDialog(
                            title = "Здоровье ВВБ (SoH)",
                            borderColor = sohColor,
                            onDismiss = { viewModel.toggleSohExpanded() }
                        ) {
                            if (state.sohPercent != null) {
                                DetailRow("SoH", "${"%.1f".format(state.sohPercent)}%", sohColor)
                                DetailRow("Ёмкость", "${"%.1f".format(state.estimatedCapacityKwh)} кВт·ч", TextPrimary)
                                DetailRow("Номинал", "72.9 кВт·ч", TextMuted)
                                DetailRow("По данным", "${state.sohTripCount} поездок", TextSecondary)
                            } else {
                                DetailRow("Статус", "Мало данных", TextMuted)
                                DetailRow("Нужно", "Поездки с дельтой SOC ≥ 10%", TextSecondary)
                            }
                        }
                    }
                    if (state.idleDrainExpanded) {
                        val color = when {
                            state.idleDrainPercent > 5.0 -> SocRed
                            state.idleDrainPercent > 2.0 -> SocYellow
                            else -> AccentGreen
                        }
                        CardDetailDialog(
                            title = "Расход на стоянке",
                            borderColor = color,
                            onDismiss = { viewModel.toggleIdleDrainExpanded() }
                        ) {
                            if (state.idleDrainRate > 0) {
                                DetailRow("Скорость", "${"%.2f".format(state.idleDrainRate)} кВт·ч/час", color)
                            }
                            DetailRow("За 7 дней", "${"%.1f".format(state.idleDrainKwhWeek)} кВт·ч", TextPrimary)
                            if (state.idleDrainKwhWeek > 0) {
                                DetailRow("Ср. в день", "${"%.1f".format(state.idleDrainKwhWeek / 7.0)} кВт·ч", TextPrimary)
                            }
                        }
                    }
                    if (state.batteryHealthExpanded) {
                        val color = when {
                            state.batteryHealthStatus == "critical" -> SocRed
                            state.batteryHealthStatus == "warning" -> SocYellow
                            else -> AccentGreen
                        }
                        CardDetailDialog(
                            title = "Здоровье батареи",
                            borderColor = color,
                            onDismiss = { viewModel.toggleBatteryHealthExpanded() }
                        ) {
                            state.avgBatTemp?.let {
                                DetailRow("Температура", "${it}°C", color)
                            }
                            state.voltage12v?.let {
                                val v12Color = when (state.voltage12vStatus) {
                                    "critical" -> SocRed; "warning" -> SocYellow; else -> AccentGreen
                                }
                                DetailRow("12V батарея", "${"%.1f".format(it)}V", v12Color)
                            }
                            state.cellVoltageDelta?.let { delta ->
                                DetailRow("Баланс ячеек", "${"%.3f".format(delta)}V", when {
                                    delta > 0.10 -> SocRed; delta > 0.05 -> SocYellow; else -> AccentGreen
                                })
                            }
                            if (state.cellVoltageMin != null && state.cellVoltageMax != null) {
                                DetailRow("Ячейки", "${"%.3f".format(state.cellVoltageMin)}–${"%.3f".format(state.cellVoltageMax)}V", TextPrimary)
                            }
                        }
                    }
                }
            }

            // RIGHT COLUMN — period filter + 4 cards + recent trips
            Column(
                modifier = Modifier.weight(0.6f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Period chips
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DashboardPeriodChip("День", state.period == DashboardPeriod.TODAY) { viewModel.setPeriod(DashboardPeriod.TODAY) }
                    DashboardPeriodChip("Нед", state.period == DashboardPeriod.WEEK) { viewModel.setPeriod(DashboardPeriod.WEEK) }
                    DashboardPeriodChip("Мес", state.period == DashboardPeriod.MONTH) { viewModel.setPeriod(DashboardPeriod.MONTH) }
                    DashboardPeriodChip("Год", state.period == DashboardPeriod.YEAR) { viewModel.setPeriod(DashboardPeriod.YEAR) }
                    DashboardPeriodChip("Всё", state.period == DashboardPeriod.ALL) { viewModel.setPeriod(DashboardPeriod.ALL) }
                }

                // 4 stat cards
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatCard("Пробег", "%.1f км".format(state.totalKm), "${state.tripCount} поездок", AccentGreen, Modifier.weight(1f))
                    StatCard("Энергия", "%.1f кВт·ч".format(state.totalKwh), null, AccentBlue, Modifier.weight(1f))
                    val consColor = if (state.avgConsumption > 0) consumptionColor(state.avgConsumption) else TextSecondary
                    StatCard("Расход", if (state.avgConsumption > 0) "%.1f/100".format(state.avgConsumption) else "—", null, consColor, Modifier.weight(1f))
                    StatCard("Стоимость", "%.2f %s".format(state.totalCost, state.currencySymbol), null, AccentGreen, Modifier.weight(1f))
                }

                SectionHeader(text = "Последние поездки")
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("время", color = TextMuted, fontSize = 11.sp, modifier = Modifier.weight(2.5f))
                    Text("длит.", color = TextMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Text("км", color = TextMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Text("кВт·ч", color = TextMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Text("/100", color = TextMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Text(state.currencySymbol, color = TextMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                }
                if (state.recentTrips.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        state.recentTrips.forEach { trip ->
                            TripCard(
                                trip = trip,
                                onClick = { },
                                currencySymbol = state.currencySymbol
                            )
                        }
                    }
                } else {
                    PlaceholderText(text = "Поездок пока нет")
                }
            }
        }
    }
}

@Composable
private fun TopBar(isServiceRunning: Boolean, diPlusConnected: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "BYDMate",
            color = TextPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isServiceRunning && !diPlusConnected) {
                Text(
                    text = "DiPlus не отвечает",
                    color = SocYellow,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(if (isServiceRunning) AccentGreen else TextMuted)
            )
            Text(
                text = if (isServiceRunning) "Online" else "Offline",
                color = TextSecondary,
                fontSize = 12.sp
            )
        }
    }
}

// ============================================================================
// Compact card — same look for all three
// ============================================================================

@Composable
private fun CompactCard(
    leftValue: String,
    leftLabel: String,
    rightValue: String,
    rightLabel: String,
    borderColor: Color,
    hasData: Boolean = true,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, borderColor.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(leftValue, color = if (hasData) borderColor else TextMuted,
                    fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text(leftLabel, color = TextMuted, fontSize = 11.sp)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(rightValue, color = if (hasData) borderColor else TextMuted,
                    fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text(rightLabel, color = TextMuted, fontSize = 11.sp)
            }
        }
    }
}

// ============================================================================
// Pop-up dialog for card details
// ============================================================================

@Composable
private fun CardDetailDialog(
    title: String,
    borderColor: Color,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    indication = null,
                    interactionSource = androidx.compose.foundation.interaction.MutableInteractionSource()
                ) { onDismiss() },
            contentAlignment = Alignment.CenterStart
        ) {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = CardSurface),
                border = androidx.compose.foundation.BorderStroke(2.dp, borderColor.copy(alpha = 0.6f)),
                modifier = Modifier
                    .padding(start = 22.dp, end = 16.dp)
                    .fillMaxWidth(0.4f)
                    .clickable { onDismiss() }
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(title, color = borderColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    content()
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, valueColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = TextSecondary, fontSize = 14.sp)
        Text(value, color = valueColor, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace)
    }
}

// ============================================================================
// Shared UI components
// ============================================================================

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

@Composable
private fun DashboardPeriodChip(label: String, selected: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 12.sp) },
        shape = RoundedCornerShape(8.dp),
        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
            selectedContainerColor = AccentGreen,
            selectedLabelColor = Color.White,
            containerColor = CardSurface,
            labelColor = TextSecondary
        ),
        border = androidx.compose.material3.FilterChipDefaults.filterChipBorder(
            borderColor = Color.Transparent,
            selectedBorderColor = Color.Transparent,
            enabled = true,
            selected = selected
        )
    )
}

@Composable
private fun StatCard(title: String, value: String, subtitle: String?, accentColor: Color, modifier: Modifier = Modifier) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        modifier = modifier.height(64.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(title, color = TextSecondary, fontSize = 11.sp)
            Text(value, color = accentColor, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace)
            Text(subtitle ?: "", color = TextMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun PlaceholderText(text: String) {
    Text(
        text = text,
        color = TextSecondary,
        fontSize = 14.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        fontWeight = FontWeight.Medium
    )
}
