package com.bydmate.app.ui.dashboard

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
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
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        TopBar(isServiceRunning = state.isServiceRunning)

        Spacer(modifier = Modifier.height(24.dp))
        SocGauge(
            soc = state.soc ?: 0,
            modifier = Modifier.size(200.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))
        OdometerText(odometer = state.odometer)

        Spacer(modifier = Modifier.height(24.dp))
        SectionHeader(text = "Сегодня")
        Spacer(modifier = Modifier.height(8.dp))
        SummaryRow(
            totalKm = state.totalKmToday,
            totalKwh = state.totalKwhToday,
            avgKwhPer100km = state.avgConsumption
        )

        if (state.idleDrainKwhToday > 0.01) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Расход на стоянке: ${"%.2f".format(state.idleDrainKwhToday)} кВт·ч",
                color = AccentOrange,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
        SectionHeader(text = "Последняя поездка")
        Spacer(modifier = Modifier.height(8.dp))
        if (state.lastTrip != null) {
            TripCard(
                trip = state.lastTrip!!,
                onClick = { },
                currencySymbol = state.currencySymbol
            )
        } else {
            PlaceholderText(text = "Поездок пока нет")
        }

        Spacer(modifier = Modifier.height(24.dp))
        SectionHeader(text = "Последняя зарядка")
        Spacer(modifier = Modifier.height(8.dp))
        if (state.lastCharge != null) {
            ChargeCard(
                charge = state.lastCharge!!,
                onClick = { },
                currencySymbol = state.currencySymbol
            )
        } else {
            PlaceholderText(text = "Зарядок пока нет")
        }

        Spacer(modifier = Modifier.height(32.dp))
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
            fontSize = 28.sp,
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
        fontSize = 20.sp,
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
            .padding(vertical = 16.dp),
        fontWeight = FontWeight.Medium
    )
}
