package com.bydmate.app.ui.dashboard

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bydmate.app.R
import com.bydmate.app.data.remote.DynamicMetric
import com.bydmate.app.data.trips.TripCounterUi
import com.bydmate.app.ui.theme.*

// ============================================================================
// AI Insight card — text-based, different from value-pair cards
// ============================================================================

@Composable
internal fun InsightCard(
    title: String?,
    summary: String?,
    borderColor: Color,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        border = androidx.compose.foundation.BorderStroke(
            1.5.dp,
            if (title != null) borderColor.copy(alpha = 0.5f) else TextMuted.copy(alpha = 0.3f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("✦", fontSize = 16.sp, color = if (title != null) borderColor else TextMuted)
            Spacer(modifier = Modifier.width(8.dp))
            if (title != null) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        color = borderColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    if (summary != null) {
                        Text(
                            summary,
                            color = borderColor.copy(alpha = 0.8f),
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                    }
                }
            } else {
                Text(
                    stringResource(R.string.dashboard_insight_no_data),
                    color = TextMuted,
                    fontSize = 12.sp
                )
            }
        }
    }
}

// ============================================================================
// TRIP 1 / TRIP 2 counter button
// ============================================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TripCounterButton(
    label: String,
    ui: TripCounterUi?,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, AccentGreen.copy(alpha = 0.5f)),
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, color = AccentGreen, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("%.0f".format(ui?.km ?: 0.0), color = AccentGreen,
                        fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text(stringResource(R.string.dashboard_unit_km), color = TextMuted, fontSize = 10.sp)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("%.1f".format(ui?.kwh ?: 0.0), color = AccentGreen,
                        fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text(stringResource(R.string.dashboard_unit_kwh), color = TextMuted, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
internal fun BatteryCompactCard(
    sohText: String,
    sohColor: Color,
    tempText: String,
    tempColor: Color,
    voltageText: String,
    voltageColor: Color,
    cellDeltaText: String,
    cellDeltaColor: Color,
    insulationText: String,
    insulationColor: Color,
    borderColor: Color,
    onClick: () -> Unit,
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
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            BatteryCell(value = sohText, label = "SoH", color = sohColor)
            BatteryCell(value = tempText, label = stringResource(R.string.dashboard_battery_temp_label), color = tempColor)
            BatteryCell(value = voltageText, label = stringResource(R.string.dashboard_battery_voltage_label), color = voltageColor)
            BatteryCell(value = cellDeltaText, label = stringResource(R.string.battery_health_cell_delta_label), color = cellDeltaColor)
            BatteryCell(value = insulationText, label = stringResource(R.string.dashboard_battery_insulation_label), color = insulationColor)
        }
    }
}

/** DM-i only: tank level and the car's range on fuel, same shape as [BatteryCompactCard]. */
@Composable
internal fun FuelCompactCard(tankText: String, rangeText: String, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, AccentOrange.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            FuelCell(value = tankText, label = stringResource(R.string.dashboard_fuel_tank), color = AccentOrange)
            FuelCell(value = rangeText, label = stringResource(R.string.dashboard_fuel_range), color = TextPrimary)
        }
    }
}

@Composable
private fun FuelCell(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontSize = 17.sp, fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, color = TextMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The four period cards on a hybrid (#184): every figure is over all km of the period, the
 * way the instrument cluster's «Accumulated AEC» counts, except litres per 100, which divide by
 * the km of the trips that know their litres. Missing sums leave their subtitle out.
 */
@Composable
internal fun RowScope.HybridStatCards(state: DashboardUiState) {
    val tripCount = stringResource(R.string.dashboard_trip_count, state.tripCount)
    StatCard(
        stringResource(R.string.dashboard_stat_distance),
        stringResource(R.string.dashboard_stat_km_value, state.totalKm),
        state.periodHevKm?.let { stringResource(R.string.dashboard_distance_hev_subtitle, tripCount, it) } ?: tripCount,
        Color.White, Modifier.weight(1f), valueMaxLines = 1,
    )
    StatCard(
        stringResource(R.string.dashboard_stat_energy),
        stringResource(R.string.dashboard_stat_kwh_value, state.totalKwh),
        state.periodLiters?.let { stringResource(R.string.dashboard_energy_fuel_subtitle, it) },
        AccentBlue, Modifier.weight(1f), valueMaxLines = 1,
    )
    val litersPer100 = periodLitersPer100(state.periodLiters, state.periodLitersKm)
    if (litersPer100 != null) {
        StatCard(
            stringResource(R.string.dashboard_stat_consumption),
            stringResource(R.string.dashboard_consumption_fuel_value, litersPer100),
            if (state.avgConsumption > 0) stringResource(R.string.dashboard_consumption_elec_subtitle, state.avgConsumption) else null,
            AccentOrange, Modifier.weight(1f), valueMaxLines = 1,
        )
    } else {
        StatCard(
            stringResource(R.string.dashboard_stat_consumption),
            "—",
            if (state.avgConsumption > 0) "%.1f/100".format(state.avgConsumption) else null,
            TextSecondary, Modifier.weight(1f), valueMaxLines = 1,
        )
    }
    StatCard(
        stringResource(R.string.dashboard_stat_cost),
        "%.2f %s".format(state.totalCost, state.currencySymbol),
        state.periodFuelCost?.let {
            stringResource(R.string.dashboard_cost_split_subtitle, state.periodElecCost ?: 0.0, it)
        },
        AccentGreen, Modifier.weight(1f), valueMaxLines = 1,
    )
}

@Composable
private fun BatteryCell(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontSize = 17.sp, fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace)
        Text(label, color = TextMuted, fontSize = 10.sp)
    }
}

@Composable
internal fun DynamicsRow(metric: DynamicMetric) {
    val changeColor = when (metric.sentiment) {
        "good" -> AccentGreen
        "bad" -> SocRed
        else -> TextMuted
    }
    val arrow = when {
        metric.changePct == null -> ""
        metric.changePct > 0 -> "▲"
        metric.changePct < 0 -> "▼"
        else -> ""
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            metric.label,
            color = TextSecondary,
            fontSize = 11.sp,
            modifier = Modifier.width(100.dp)
        )
        Text(
            metric.current,
            color = TextPrimary,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold
        )
        if (metric.previous != null) {
            Spacer(modifier = Modifier.width(6.dp))
            Text("\u2190", color = TextMuted, fontSize = 10.sp)
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                metric.previous,
                color = TextMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        if (metric.changePct != null) {
            Text(
                "$arrow${"%.0f".format(kotlin.math.abs(metric.changePct))}%",
                color = changeColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

// Highlight numbers in insight text with amber color, bold text before first dash
@Composable
internal fun StyledInsightText(text: String, bulletColor: Color) {
    val numberPattern = Regex("""(\d+[.,]\d+|\d+)(%| кВтч?/ч| кВтч?/100км| кВтч?| км/ч| км| мВ|°C)?""")
    val annotated = buildAnnotatedString {
        var lastEnd = 0
        // Bold: text before first " —" or " —"
        val dashIdx = text.indexOf(" — ").takeIf { it > 0 }
            ?: text.indexOf(" — ").takeIf { it > 0 }
        if (dashIdx != null && dashIdx < 40) {
            withStyle(SpanStyle(color = TextPrimary, fontWeight = FontWeight.SemiBold)) {
                append(text.substring(0, dashIdx))
            }
            lastEnd = dashIdx
        }
        // Highlight numbers
        val remaining = text.substring(lastEnd)
        var rLastEnd = 0
        for (match in numberPattern.findAll(remaining)) {
            if (match.range.first > rLastEnd) {
                withStyle(SpanStyle(color = TextSecondary)) {
                    append(remaining.substring(rLastEnd, match.range.first))
                }
            }
            val num = match.value
            // Only highlight if it looks like a real metric (has digits)
            if (num.any { it.isDigit() } && num.length > 1) {
                withStyle(SpanStyle(color = SocYellow, fontFamily = FontFamily.Monospace, fontSize = 12.sp)) {
                    append(num)
                }
            } else {
                withStyle(SpanStyle(color = TextSecondary)) {
                    append(num)
                }
            }
            rLastEnd = match.range.last + 1
        }
        if (rLastEnd < remaining.length) {
            withStyle(SpanStyle(color = TextSecondary)) {
                append(remaining.substring(rLastEnd))
            }
        }
    }
    Text(annotated, fontSize = 13.sp, lineHeight = 19.sp)
}
