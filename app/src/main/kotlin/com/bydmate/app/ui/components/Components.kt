package com.bydmate.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bydmate.app.data.local.entity.ChargeEntity
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

// -- Helper functions --

fun socColor(soc: Int): Color = when {
    soc > 50 -> SocGreen
    soc >= 20 -> SocYellow
    else -> SocRed
}

fun consumptionColor(kwhPer100km: Double): Color = when {
    kwhPer100km < 15.0 -> ConsumptionGood
    kwhPer100km <= 22.0 -> ConsumptionMid
    else -> ConsumptionBad
}

fun formatTime(ts: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(ts))
}

fun formatDateTime(ts: Long): String {
    val sdf = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
    return sdf.format(Date(ts))
}

fun formatDuration(startTs: Long, endTs: Long): String {
    val durationMs = endTs - startTs
    val hours = TimeUnit.MILLISECONDS.toHours(durationMs)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMs) % 60
    return if (hours > 0) "${hours}ч ${minutes}м" else "${minutes}м"
}

// ============================================================================
// SocGauge - Premium circular arc gauge with gradient and glow
// ============================================================================

@Composable
fun SocGauge(
    soc: Int,
    modifier: Modifier = Modifier
) {
    val clampedSoc = soc.coerceIn(0, 100)
    val color = socColor(clampedSoc)
    val startAngle = 150f
    val totalSweep = 240f
    val socSweep = totalSweep * (clampedSoc / 100f)
    val strokeWidth = 14.dp

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(120.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = Stroke(
                width = strokeWidth.toPx(),
                cap = StrokeCap.Round
            )
            val padding = strokeWidth.toPx() / 2f
            val arcSize = Size(
                width = size.width - strokeWidth.toPx(),
                height = size.height - strokeWidth.toPx()
            )
            val topLeft = Offset(padding, padding)

            // Subtle glow behind arc
            if (clampedSoc > 0) {
                val glowStroke = Stroke(width = strokeWidth.toPx() * 2.5f, cap = StrokeCap.Round)
                drawArc(
                    color = color.copy(alpha = 0.1f),
                    startAngle = startAngle,
                    sweepAngle = socSweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = glowStroke
                )
            }

            // Background track arc
            drawArc(
                color = CardBorder.copy(alpha = 0.4f),
                startAngle = startAngle,
                sweepAngle = totalSweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke
            )

            // Foreground SOC arc with gradient
            if (clampedSoc > 0) {
                drawArc(
                    brush = Brush.sweepGradient(
                        colors = listOf(color.copy(alpha = 0.6f), color),
                        center = Offset(size.width / 2f, size.height / 2f)
                    ),
                    startAngle = startAngle,
                    sweepAngle = socSweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = stroke
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "$clampedSoc",
                color = TextPrimary,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Text(
                text = "SOC %",
                color = TextMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ============================================================================
// TripCard
// ============================================================================

@Composable
fun TripCard(
    trip: TripEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    currencySymbol: String = "Br"
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        border = BorderStroke(1.dp, CardBorder.copy(alpha = 0.3f)),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Top row: time range and duration
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val timeRange = buildString {
                    append(formatTime(trip.startTs))
                    append(" – ")
                    append(trip.endTs?.let { formatTime(it) } ?: "…")
                }
                Text(
                    text = timeRange,
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium
                )

                if (trip.endTs != null) {
                    Text(
                        text = formatDuration(trip.startTs, trip.endTs),
                        color = TextSecondary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Middle row: distance and consumption
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                val distanceText = trip.distanceKm?.let { "%.1f км".format(it) } ?: "— км"
                Text(text = distanceText, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)

                val kwhText = trip.kwhConsumed?.let { "%.1f кВт·ч".format(it) } ?: "— кВт·ч"
                Text(text = kwhText, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)

                val consumptionText = trip.kwhPer100km?.let { "%.1f".format(it) } ?: "—"
                val consumptionClr = trip.kwhPer100km?.let { consumptionColor(it) } ?: TextSecondary
                Text(
                    text = "$consumptionText кВт·ч/100км",
                    color = consumptionClr,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // Bottom row: SOC change, battery temp, speed, cost
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // SOC start -> end
                val socStartText = trip.socStart?.let { "$it%" } ?: "—"
                val socEndText = trip.socEnd?.let { "$it%" } ?: "—"
                val socStartClr = trip.socStart?.let { socColor(it) } ?: TextSecondary
                val socEndClr = trip.socEnd?.let { socColor(it) } ?: TextSecondary

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = socStartText, color = socStartClr, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(text = " → ", color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(text = socEndText, color = socEndClr, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }

                // Battery temperature
                val batTemp = trip.batTempAvg ?: trip.tempAvgC
                if (batTemp != null) {
                    Text(
                        text = "bat %.0f°C".format(batTemp),
                        color = TextSecondary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Average speed
                val speedText = trip.avgSpeedKmh?.let { "%.0f км/ч".format(it) } ?: ""
                if (speedText.isNotEmpty()) {
                    Text(text = speedText, color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }

                // Trip cost
                if (trip.cost != null) {
                    Text(
                        text = "$currencySymbol%.2f".format(trip.cost),
                        color = AccentGreen,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

// ============================================================================
// ChargeCard
// ============================================================================

@Composable
fun ChargeCard(
    charge: ChargeEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    currencySymbol: String = "Br"
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        border = BorderStroke(1.dp, CardBorder.copy(alpha = 0.3f)),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Top row: time range, duration, and charge type badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val timeRange = buildString {
                    append(formatTime(charge.startTs))
                    append(" – ")
                    append(charge.endTs?.let { formatTime(it) } ?: "…")
                }
                Text(text = timeRange, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Medium)

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (charge.endTs != null) {
                        Text(
                            text = formatDuration(charge.startTs, charge.endTs),
                            color = TextSecondary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    if (charge.type != null) {
                        val badgeColor = if (charge.type == "DC") AccentOrange else AccentBlue
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .background(color = badgeColor, shape = RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(text = charge.type, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Middle row: SOC change and kWh charged
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val socStartText = charge.socStart?.let { "$it%" } ?: "—"
                val socEndText = charge.socEnd?.let { "$it%" } ?: "—"
                val socStartClr = charge.socStart?.let { socColor(it) } ?: TextSecondary
                val socEndClr = charge.socEnd?.let { socColor(it) } ?: TextSecondary

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = socStartText, color = socStartClr, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(text = " → ", color = TextSecondary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(text = socEndText, color = socEndClr, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }

                val kwhText = charge.kwhCharged?.let { "%.1f кВт·ч".format(it) } ?: "— кВт·ч"
                Text(text = kwhText, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }

            // Bottom row: max/avg power, battery temp, cost
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (charge.maxPowerKw != null) {
                    Text(text = "max %.1f кВт".format(charge.maxPowerKw), color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }

                if (charge.avgPowerKw != null) {
                    Text(text = "avg %.1f кВт".format(charge.avgPowerKw), color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }

                if (charge.batTempAvg != null) {
                    Text(text = "bat %.0f°C".format(charge.batTempAvg), color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }

                val costText = charge.cost?.let { "$currencySymbol%.2f".format(it) } ?: ""
                if (costText.isNotEmpty()) {
                    Text(text = costText, color = AccentGreen, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

// ============================================================================
// SummaryRow
// ============================================================================

@Composable
fun SummaryRow(
    totalKm: Double,
    totalKwh: Double,
    avgKwhPer100km: Double,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SummaryStatBox(
            value = "%.1f".format(totalKm),
            unit = "км",
            label = "Пробег",
            valueColor = TextPrimary,
            modifier = Modifier.weight(1f)
        )
        SummaryStatBox(
            value = "%.1f".format(totalKwh),
            unit = "кВт·ч",
            label = "Энергия",
            valueColor = TextPrimary,
            modifier = Modifier.weight(1f)
        )
        SummaryStatBox(
            value = "%.1f".format(avgKwhPer100km),
            unit = "кВт·ч/100км",
            label = "Расход",
            valueColor = consumptionColor(avgKwhPer100km),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SummaryStatBox(
    value: String,
    unit: String,
    label: String,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .background(color = CardSurface, shape = RoundedCornerShape(16.dp))
            .height(76.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(text = value, color = valueColor, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                Text(
                    text = " $unit",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
            Text(text = label, color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}
