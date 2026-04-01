package com.bydmate.app.ui.trips

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.ui.components.consumptionColor
import com.bydmate.app.ui.components.formatDuration
import com.bydmate.app.ui.components.formatTime
import com.bydmate.app.ui.theme.*

@Composable
fun TripsScreen(
    viewModel: TripsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(NavyDark, NavyDeep)))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Period chips
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TripsChip("День", state.period == TripPeriod.TODAY) { viewModel.setPeriod(TripPeriod.TODAY) }
            TripsChip("Нед", state.period == TripPeriod.WEEK) { viewModel.setPeriod(TripPeriod.WEEK) }
            TripsChip("Мес", state.period == TripPeriod.MONTH) { viewModel.setPeriod(TripPeriod.MONTH) }
            TripsChip("Год", state.period == TripPeriod.YEAR) { viewModel.setPeriod(TripPeriod.YEAR) }
            TripsChip("Всё", state.period == TripPeriod.ALL) { viewModel.setPeriod(TripPeriod.ALL) }
            Spacer(modifier = Modifier.width(12.dp))
            TripsChip("Все", state.filter == TripFilter.ALL) { viewModel.setFilter(TripFilter.ALL) }
            TripsChip("Поездки", state.filter == TripFilter.TRIPS_ONLY) { viewModel.setFilter(TripFilter.TRIPS_ONLY) }
            TripsChip("Стоянки", state.filter == TripFilter.STOPS_ONLY) { viewModel.setFilter(TripFilter.STOPS_ONLY) }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (state.months.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Нет данных за выбранный период", color = TextSecondary, fontSize = 16.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                for (month in state.months) {
                    // Month header
                    item(key = "month_${month.yearMonth}") {
                        MonthHeader(
                            month = month,
                            expanded = month.yearMonth in state.expandedMonths,
                            currencySymbol = state.currencySymbol,
                            onClick = { viewModel.toggleMonth(month.yearMonth) }
                        )
                    }

                    if (month.yearMonth in state.expandedMonths) {
                        for (day in month.days) {
                            // Day header
                            item(key = "day_${month.yearMonth}_${day.date}") {
                                DayHeader(
                                    day = day,
                                    expanded = day.date in state.expandedDays,
                                    currencySymbol = state.currencySymbol,
                                    onClick = { viewModel.toggleDay(day.date) }
                                )
                            }

                            if (day.date in state.expandedDays) {
                                // Column headers
                                item(key = "header_${month.yearMonth}_${day.date}") {
                                    ColumnHeaders(currencySymbol = state.currencySymbol)
                                }
                                for (trip in day.trips) {
                                    item(key = "trip_${trip.id}") {
                                        TripRow(
                                            trip = trip,
                                            currencySymbol = state.currencySymbol,
                                            onClick = { viewModel.selectTrip(trip) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Trip detail dialog
    state.selectedTrip?.let { trip ->
        TripDetailDialog(
            trip = trip,
            points = state.selectedTripPoints,
            currencySymbol = state.currencySymbol,
            onDismiss = { viewModel.clearSelectedTrip() }
        )
    }
}

@Composable
private fun MonthHeader(month: MonthGroup, expanded: Boolean, currencySymbol: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .background(CardSurface.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row {
            Text(if (expanded) "▼" else "▶", color = AccentGreen, fontSize = 12.sp)
            Spacer(modifier = Modifier.width(6.dp))
            Text(month.label, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            "%.0f км | %.0f кВт·ч | %.1f/100 | %.2f %s".format(
                month.totalKm, month.totalKwh, month.avgConsumption, month.totalCost, currencySymbol
            ),
            color = TextSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun DayHeader(day: DayGroup, expanded: Boolean, currencySymbol: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .background(CardSurface.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
            .padding(start = 20.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row {
            Text(if (expanded) "▼" else "▶", color = AccentBlue, fontSize = 12.sp)
            Spacer(modifier = Modifier.width(6.dp))
            Text("${day.date} (${day.dayOfWeek})", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        Text(
            "%.1f км | %.1f кВт·ч | %.1f/100 | %.2f %s".format(
                day.totalKm, day.totalKwh, day.avgConsumption, day.totalCost, currencySymbol
            ),
            color = TextSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun ColumnHeaders(currencySymbol: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 36.dp, end = 12.dp, top = 4.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("время", color = TextMuted, fontSize = 11.sp, modifier = Modifier.width(96.dp))
        Text("длит.", color = TextMuted, fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.width(44.dp))
        Text("км", color = TextMuted, fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.width(48.dp))
        Text("кВт·ч", color = TextMuted, fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.width(44.dp))
        Text("/100", color = TextMuted, fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.width(44.dp))
        Text(currencySymbol.lowercase(), color = TextMuted, fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.width(56.dp))
    }
    HorizontalDivider(color = CardBorder.copy(alpha = 0.5f), thickness = 0.5.dp,
        modifier = Modifier.padding(start = 36.dp, end = 12.dp))
}

@Composable
private fun TripRow(trip: TripEntity, currencySymbol: String, onClick: () -> Unit) {
    val isStop = (trip.distanceKm ?: 0.0) == 0.0
    val time = formatTime(trip.startTs)
    val endTime = trip.endTs?.let { formatTime(it) } ?: ""
    val dist = trip.distanceKm?.let { "%.1f".format(it) } ?: "—"
    val dur = if (trip.endTs != null) formatDuration(trip.startTs, trip.endTs) else "—"
    val kwh = trip.kwhConsumed?.let { "%.1f".format(it) } ?: "—"
    val per100 = trip.kwhPer100km?.let { "%.1f".format(it) } ?: "—"
    val cost = trip.cost?.let { "%.2f".format(it) } ?: "—"
    val consColor = trip.kwhPer100km?.let { consumptionColor(it) } ?: TextSecondary

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(start = 36.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Time range
            Text(
                "$time–$endTime",
                color = if (isStop) TextMuted else TextSecondary,
                fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                maxLines = 1,
                modifier = Modifier.width(96.dp)
            )
            // Duration
            Text(dur, color = if (isStop) TextMuted else TextSecondary,
                fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.End, modifier = Modifier.width(44.dp))
            // Distance
            Text(
                if (isStop) "0.0" else dist,
                color = if (isStop) TextMuted else TextPrimary,
                fontSize = 14.sp, fontFamily = FontFamily.Monospace,
                fontWeight = if (!isStop) FontWeight.Medium else FontWeight.Normal,
                textAlign = TextAlign.End,
                modifier = Modifier.width(48.dp)
            )
            // kWh
            Text(kwh, color = if (isStop) TextMuted else TextSecondary,
                fontSize = 14.sp, fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.End, modifier = Modifier.width(44.dp))
            // /100
            Text(
                if (isStop) "—" else per100,
                color = if (isStop) TextMuted else consColor,
                fontSize = 14.sp, fontFamily = FontFamily.Monospace,
                fontWeight = if (!isStop) FontWeight.Medium else FontWeight.Normal,
                textAlign = TextAlign.End,
                modifier = Modifier.width(44.dp)
            )
            // Cost
            Text(
                "$cost $currencySymbol",
                color = if (isStop) TextMuted else TextSecondary,
                fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.End,
                modifier = Modifier.width(56.dp)
            )
        }
        HorizontalDivider(color = CardBorder.copy(alpha = 0.3f), thickness = 0.5.dp,
            modifier = Modifier.padding(start = 36.dp, end = 12.dp))
    }
}

@Composable
private fun TripsChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 12.sp) },
        shape = RoundedCornerShape(8.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = AccentGreen,
            selectedLabelColor = Color.White,
            containerColor = CardSurface,
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
