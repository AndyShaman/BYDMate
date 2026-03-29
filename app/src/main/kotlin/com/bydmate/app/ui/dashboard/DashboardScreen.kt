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
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        // Range estimate
                        val rangeText = state.estimatedRangeKm?.let { "~${"%.0f".format(it)}" } ?: "—"
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(rangeText, color = AccentGreen, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("км", color = AccentGreen.copy(alpha = 0.7f), fontSize = 18.sp,
                                fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 3.dp))
                        }
                        Text("расчётный пробег", color = TextMuted, fontSize = 13.sp)
                    }

                    // 3 compact cards: idle drain, charge, battery
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        IdleDrainMiniCard(state)
                        LastChargeCompact(state)
                        BatteryHealthCard(state, onClick = { viewModel.toggleBatteryHealthExpanded() })
                    }
                }
            }

            // RIGHT COLUMN — summary + recent trips
            Column(
                modifier = Modifier.weight(0.6f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SectionHeader(text = "Сегодня")
                SummaryRow(
                    totalKm = state.totalKmToday,
                    totalKwh = state.totalKwhToday,
                    avgKwhPer100km = state.avgConsumption
                )

                SectionHeader(text = "Последние поездки")
                // Column headers
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Время", color = TextMuted, fontSize = 11.sp)
                    Text("км", color = TextMuted, fontSize = 11.sp)
                    Text("кВт·ч", color = TextMuted, fontSize = 11.sp)
                    Text("/100", color = TextMuted, fontSize = 11.sp)
                    Text("", color = TextMuted, fontSize = 11.sp)
                }
                if (state.recentTrips.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
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
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
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
private fun LastChargeCompact(state: DashboardUiState) {
    if (state.lastCharge == null) {
        Text("Зарядок пока нет", color = TextMuted, fontSize = 13.sp)
        return
    }
    val charge = state.lastCharge
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header: type badge + SOC range
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (charge.type != null) {
                        val badgeColor = if (charge.type == "DC") AccentOrange else AccentBlue
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .background(color = badgeColor, shape = RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 1.dp)
                        ) {
                            Text(charge.type, color = androidx.compose.ui.graphics.Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text(
                        "${charge.socStart ?: "?"}% → ${charge.socEnd ?: "?"}%",
                        color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium
                    )
                }
                charge.kwhCharged?.let {
                    Text("${"%.1f".format(it)} кВт·ч", color = TextPrimary, fontSize = 15.sp)
                }
            }
            // Date + duration
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    com.bydmate.app.ui.components.formatDateTime(charge.startTs),
                    color = TextSecondary, fontSize = 13.sp
                )
                if (charge.endTs != null) {
                    Text(
                        com.bydmate.app.ui.components.formatDuration(charge.startTs, charge.endTs),
                        color = TextSecondary, fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun BatteryHealthCard(state: DashboardUiState, onClick: () -> Unit) {
    // Overall status color for the card border
    val worstStatus = when {
        state.batteryHealthStatus == "critical" || state.voltage12vStatus == "critical" -> "critical"
        state.batteryHealthStatus == "warning" || state.voltage12vStatus == "warning" -> "warning"
        else -> "ok"
    }
    val borderColor = when (worstStatus) {
        "critical" -> SocRed
        "warning" -> SocYellow
        else -> AccentGreen
    }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        border = androidx.compose.foundation.BorderStroke(2.dp, borderColor.copy(alpha = 0.6f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clickable { onClick() }
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Main row: bat temp + 12V — always visible
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                BatteryTempDisplay(state)
                Voltage12vDisplay(state)
            }

            // Expanded details — shown on click
            if (state.batteryHealthExpanded) {
                state.cellVoltageDelta?.let { delta ->
                    DetailRow(
                        label = "Баланс ячеек",
                        value = "${"%.3f".format(delta)}V",
                        valueColor = when {
                            delta > 0.10 -> SocRed
                            delta > 0.05 -> SocYellow
                            else -> AccentGreen
                        }
                    )
                }
                if (state.cellVoltageMin != null && state.cellVoltageMax != null) {
                    DetailRow(
                        label = "Ячейки",
                        value = "${"%.3f".format(state.cellVoltageMin)}–${"%.3f".format(state.cellVoltageMax)}V",
                        valueColor = TextPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = TextSecondary, fontSize = 15.sp)
        Text(value, color = valueColor, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun BatteryTempDisplay(state: DashboardUiState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val color = when (state.batteryHealthStatus) {
            "critical" -> SocRed
            "warning" -> SocYellow
            else -> AccentGreen
        }
        Text(
            text = state.avgBatTemp?.let { "${it}°C" } ?: "—",
            color = if (state.avgBatTemp != null) color else TextMuted,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "батарея",
            color = TextMuted,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun Voltage12vDisplay(state: DashboardUiState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val color = when (state.voltage12vStatus) {
            "critical" -> SocRed
            "warning" -> SocYellow
            else -> AccentGreen
        }
        Text(
            text = state.voltage12v?.let { "${"%.1f".format(it)}V" } ?: "—",
            color = if (state.voltage12v != null) color else TextMuted,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "12V",
            color = TextMuted,
            fontSize = 12.sp
        )
    }
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
private fun IdleDrainMiniCard(state: DashboardUiState) {
    val drainColor = when {
        state.idleDrainPercent > 5.0 -> SocRed
        state.idleDrainPercent > 2.0 -> SocYellow
        else -> AccentGreen
    }
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, drainColor.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("P Стоянка", color = drainColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                "${"%.1f".format(state.idleDrainKwhToday)} кВт·ч · ${"%.0f".format(state.idleDrainHours)}ч",
                color = TextSecondary, fontSize = 13.sp
            )
        }
    }
}
