package com.bydmate.app.ui.dashboard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bydmate.app.R
import com.bydmate.app.ui.components.ChargeCard
import com.bydmate.app.ui.components.SocGauge
import com.bydmate.app.ui.components.SummaryRow
import com.bydmate.app.ui.components.TripCard
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
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        TopBar(isServiceRunning = state.isServiceRunning)
        Spacer(modifier = Modifier.height(12.dp))

        // Main content: two columns
        Row(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // LEFT COLUMN: Ghost car + SOC gauge + odometer + battery health
            Box(modifier = Modifier.weight(0.4f)) {
                Image(
                    painter = painterResource(R.drawable.leopard3),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = 0.08f }
                        .padding(top = 20.dp),
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.Center
                )
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SocGauge(soc = state.soc ?: 0, modifier = Modifier.size(150.dp))
                    OdometerText(odometer = state.odometer)
                    BatteryHealthLine(state, onClick = { viewModel.toggleBatteryHealthExpanded() })
                    Voltage12vLine(state)
                    if (state.batteryHealthExpanded) {
                        BatteryDetailCard(state)
                    }
                }
            }

            // RIGHT COLUMN: Today summary + idle drain + last trip + last charge
            Column(
                modifier = Modifier.weight(0.6f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SectionHeader(text = "Сегодня")
                SummaryRow(
                    totalKm = state.totalKmToday,
                    totalKwh = state.totalKwhToday,
                    avgKwhPer100km = state.avgConsumption
                )
                IdleDrainCard(state)

                // Last trip + last charge side by side
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        SectionHeader(text = "Поездка")
                        Spacer(modifier = Modifier.height(4.dp))
                        if (state.lastTrip != null) {
                            TripCard(
                                trip = state.lastTrip!!,
                                onClick = { },
                                currencySymbol = state.currencySymbol
                            )
                        } else {
                            PlaceholderText(text = "Поездок пока нет")
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        SectionHeader(text = "Зарядка")
                        Spacer(modifier = Modifier.height(4.dp))
                        if (state.lastCharge != null) {
                            ChargeCard(
                                charge = state.lastCharge!!,
                                onClick = { },
                                currencySymbol = state.currencySymbol
                            )
                        } else {
                            PlaceholderText(text = "Зарядок пока нет")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(isServiceRunning: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "BYDMate",
            color = TextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (isServiceRunning) AccentGreen else TextMuted)
            )
            Text(
                text = if (isServiceRunning) "Online" else "Offline",
                color = TextSecondary,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun OdometerText(odometer: Double?) {
    Text(
        text = if (odometer != null) "%.1f km".format(odometer) else "— km",
        color = TextSecondary,
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium
    )
}

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

@Composable
private fun BatteryHealthLine(state: DashboardUiState, onClick: () -> Unit) {
    val color = when (state.batteryHealthStatus) {
        "critical" -> SocRed
        "warning" -> SocYellow
        else -> AccentGreen
    }
    val icon = when (state.batteryHealthStatus) {
        "critical" -> "✗"
        "warning" -> "⚠"
        else -> "✓"
    }
    val temp = state.avgBatTemp?.let { "bat ${it}°C $icon" } ?: "bat —"
    Text(
        text = temp,
        color = color,
        fontSize = 14.sp,
        modifier = Modifier.clickable { onClick() }
    )
}

@Composable
private fun Voltage12vLine(state: DashboardUiState) {
    val color = when (state.voltage12vStatus) {
        "critical" -> SocRed
        "warning" -> SocYellow
        else -> AccentGreen
    }
    val icon = when (state.voltage12vStatus) {
        "critical" -> "✗"
        "warning" -> "⚠"
        else -> "✓"
    }
    Text(
        text = "12V: ${"%.1f".format(state.voltage12v ?: 0.0)}V $icon",
        color = color,
        fontSize = 14.sp
    )
}

@Composable
private fun BatteryDetailCard(state: DashboardUiState) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                "Температура: ${state.avgBatTemp ?: "?"}°C",
                color = TextPrimary, fontSize = 13.sp
            )
            state.cellVoltageDelta?.let {
                Text(
                    "Баланс ячеек: ${"%.3f".format(it)}V",
                    color = TextPrimary, fontSize = 13.sp
                )
            }
            state.cellVoltageMin?.let { min ->
                state.cellVoltageMax?.let { max ->
                    Text(
                        "Ячейки: ${"%.3f".format(min)}V – ${"%.3f".format(max)}V",
                        color = TextSecondary, fontSize = 13.sp
                    )
                }
            }
            state.voltage12v?.let {
                Text(
                    "12V батарея: ${"%.1f".format(it)}V",
                    color = TextPrimary, fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun IdleDrainCard(state: DashboardUiState) {
    if (state.idleDrainKwhToday < 0.01) return

    val drainColor = when {
        state.idleDrainPercent > 5.0 -> SocRed
        state.idleDrainPercent > 2.0 -> SocYellow
        else -> AccentGreen
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "P", color = drainColor, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(end = 10.dp)
            )
            Column {
                Text(
                    text = "-${"%.1f".format(state.idleDrainPercent)}% за ${"%.0f".format(state.idleDrainHours)}ч",
                    color = drainColor, fontSize = 14.sp, fontWeight = FontWeight.Medium
                )
                Text(
                    text = "${"%.2f".format(state.idleDrainKwhToday)} кВт·ч · ${"%.2f".format(state.idleDrainRate)} кВт·ч/ч",
                    color = TextSecondary, fontSize = 12.sp
                )
            }
        }
    }
}
