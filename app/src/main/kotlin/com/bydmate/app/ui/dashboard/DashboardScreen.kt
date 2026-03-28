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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bydmate.app.ui.components.ChargeCard
import com.bydmate.app.ui.components.SocGauge
import com.bydmate.app.ui.components.SummaryRow
import com.bydmate.app.ui.components.TripCard

private val BackgroundColor = Color(0xFF0D0D0D)
private val SecondaryTextColor = Color(0xFF9E9E9E)
private val ServiceActiveColor = Color(0xFF4CAF50)
private val ServiceInactiveColor = Color(0xFF616161)

// Dashboard screen - main overview with SOC, recent trips, charging status
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundColor)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // -- Top bar: title + service status indicator --
        Spacer(modifier = Modifier.height(16.dp))
        TopBar(isServiceRunning = state.isServiceRunning)

        // -- SOC Gauge (large, centered) --
        Spacer(modifier = Modifier.height(24.dp))
        SocGauge(
            soc = state.soc ?: 0,
            modifier = Modifier.size(180.dp)
        )

        // -- Odometer below gauge --
        Spacer(modifier = Modifier.height(8.dp))
        OdometerText(odometer = state.odometer)

        // -- Today's summary --
        Spacer(modifier = Modifier.height(24.dp))
        SectionHeader(text = "Сегодня")
        Spacer(modifier = Modifier.height(8.dp))
        SummaryRow(
            totalKm = state.totalKmToday,
            totalKwh = state.totalKwhToday,
            avgKwhPer100km = state.avgConsumption
        )

        // -- Last trip section --
        Spacer(modifier = Modifier.height(24.dp))
        SectionHeader(text = "Последняя поездка")
        Spacer(modifier = Modifier.height(8.dp))
        if (state.lastTrip != null) {
            TripCard(
                trip = state.lastTrip!!,
                onClick = { /* TODO: navigate to trip detail */ }
            )
        } else {
            PlaceholderText(text = "Поездок пока нет")
        }

        // -- Last charge section --
        Spacer(modifier = Modifier.height(24.dp))
        SectionHeader(text = "Последняя зарядка")
        Spacer(modifier = Modifier.height(8.dp))
        if (state.lastCharge != null) {
            ChargeCard(
                charge = state.lastCharge!!,
                onClick = { /* TODO: navigate to charge detail */ },
                currencySymbol = state.currencySymbol
            )
        } else {
            PlaceholderText(text = "Зарядок пока нет")
        }

        // Bottom padding for navigation bar clearance
        Spacer(modifier = Modifier.height(32.dp))
    }
}

/** Top bar with app title and service running indicator. */
@Composable
private fun TopBar(isServiceRunning: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "BYDMate",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        // Green dot = service active, grey = inactive
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(
                        if (isServiceRunning) ServiceActiveColor else ServiceInactiveColor
                    )
            )
            Text(
                text = if (isServiceRunning) "Online" else "Offline",
                color = SecondaryTextColor,
                fontSize = 13.sp
            )
        }
    }
}

/** Odometer display below the SOC gauge. */
@Composable
private fun OdometerText(odometer: Double?) {
    Text(
        text = if (odometer != null) "%.1f km".format(odometer) else "— km",
        color = SecondaryTextColor,
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium
    )
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

/** Placeholder text shown when no data is available. */
@Composable
private fun PlaceholderText(text: String) {
    Text(
        text = text,
        color = SecondaryTextColor,
        fontSize = 14.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        fontWeight = FontWeight.Medium
    )
}
