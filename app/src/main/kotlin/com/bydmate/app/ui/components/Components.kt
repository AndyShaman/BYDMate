package com.bydmate.app.ui.components

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bydmate.app.data.local.entity.ChargeEntity
import com.bydmate.app.data.local.entity.TripEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

// -- Color constants matching design spec --

private val CardBackground = Color(0xFF1E1E1E)
private val CardBackgroundAlt = Color(0xFF2C2C2C)
private val TextSecondary = Color(0xFF9E9E9E)
private val SocGreen = Color(0xFF4CAF50)
private val SocYellow = Color(0xFFFFC107)
private val SocRed = Color(0xFFF44336)
private val ConsumptionGreen = Color(0xFF4CAF50)
private val ConsumptionYellow = Color(0xFFFFC107)
private val ConsumptionRed = Color(0xFFF44336)
private val ArcTrackColor = Color(0xFF2C2C2C)

// -- Helper functions --

/** Returns color based on SOC level: green >50%, yellow 20-50%, red <20% */
private fun socColor(soc: Int): Color = when {
    soc > 50 -> SocGreen
    soc >= 20 -> SocYellow
    else -> SocRed
}

/** Returns color based on consumption: green <15, yellow 15-22, red >22 kWh/100km */
private fun consumptionColor(kwhPer100km: Double): Color = when {
    kwhPer100km < 15.0 -> ConsumptionGreen
    kwhPer100km <= 22.0 -> ConsumptionYellow
    else -> ConsumptionRed
}

/** Formats a timestamp to HH:mm */
private fun formatTime(ts: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(ts))
}

/** Formats a timestamp to dd.MM HH:mm */
private fun formatDateTime(ts: Long): String {
    val sdf = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
    return sdf.format(Date(ts))
}

/** Formats duration between two timestamps as "Xч Yм" */
private fun formatDuration(startTs: Long, endTs: Long): String {
    val durationMs = endTs - startTs
    val hours = TimeUnit.MILLISECONDS.toHours(durationMs)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMs) % 60
    return if (hours > 0) "${hours}ч ${minutes}м" else "${minutes}м"
}

// ============================================================================
// SocGauge - Circular arc gauge showing 0-100% SOC
// ============================================================================

/**
 * Circular arc gauge displaying State of Charge (0-100%).
 * Draws a background track arc and a colored foreground arc proportional to SOC.
 * Color-coded: green >50%, yellow 20-50%, red <20%.
 * SOC percentage number displayed in center.
 *
 * @param soc State of charge 0-100
 * @param modifier Modifier for sizing and layout
 */
@Composable
fun SocGauge(
    soc: Int,
    modifier: Modifier = Modifier
) {
    val clampedSoc = soc.coerceIn(0, 100)
    val color = socColor(clampedSoc)
    // Arc spans 240 degrees (from 150° to 390°), leaving a gap at the bottom
    val startAngle = 150f
    val totalSweep = 240f
    val socSweep = totalSweep * (clampedSoc / 100f)
    val strokeWidth = 12.dp

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

            // Background track arc
            drawArc(
                color = ArcTrackColor,
                startAngle = startAngle,
                sweepAngle = totalSweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke
            )

            // Foreground SOC arc
            if (clampedSoc > 0) {
                drawArc(
                    color = color,
                    startAngle = startAngle,
                    sweepAngle = socSweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = stroke
                )
            }
        }

        // Center text: SOC percentage
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "$clampedSoc",
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
            Text(
                text = "%",
                color = TextSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ============================================================================
// TripCard - Card showing trip summary
// ============================================================================

/**
 * Card displaying a trip summary with time range, distance, consumption,
 * SOC change, average speed, and temperature.
 *
 * @param trip Trip entity data
 * @param onClick Callback when card is tapped
 */
@Composable
fun TripCard(
    trip: TripEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
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
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium
                )

                // Duration badge
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
                // Distance
                val distanceText = trip.distanceKm?.let { "%.1f км".format(it) } ?: "— км"
                Text(
                    text = distanceText,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )

                // kWh consumed
                val kwhText = trip.kwhConsumed?.let { "%.1f кВт·ч".format(it) } ?: "— кВт·ч"
                Text(
                    text = kwhText,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )

                // kWh/100km with color coding
                val consumptionText = trip.kwhPer100km?.let { "%.1f".format(it) } ?: "—"
                val consumptionClr = trip.kwhPer100km?.let { consumptionColor(it) } ?: TextSecondary
                Text(
                    text = "$consumptionText кВт·ч/100км",
                    color = consumptionClr,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // Bottom row: SOC change, temperature, average speed
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // SOC start → end
                val socStartText = trip.socStart?.let { "$it%" } ?: "—"
                val socEndText = trip.socEnd?.let { "$it%" } ?: "—"
                val socStartClr = trip.socStart?.let { socColor(it) } ?: TextSecondary
                val socEndClr = trip.socEnd?.let { socColor(it) } ?: TextSecondary

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = socStartText,
                        color = socStartClr,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = " → ",
                        color = TextSecondary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = socEndText,
                        color = socEndClr,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Temperature
                val tempText = trip.tempAvgC?.let { "%.0f°C".format(it) } ?: ""
                if (tempText.isNotEmpty()) {
                    Text(
                        text = tempText,
                        color = TextSecondary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Average speed
                val speedText = trip.avgSpeedKmh?.let { "%.0f км/ч".format(it) } ?: ""
                if (speedText.isNotEmpty()) {
                    Text(
                        text = speedText,
                        color = TextSecondary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

// ============================================================================
// ChargeCard - Card showing charge session summary
// ============================================================================

/**
 * Card displaying a charge session summary with time range, SOC change,
 * energy charged, max power, duration, cost, and AC/DC badge.
 *
 * @param charge Charge entity data
 * @param onClick Callback when card is tapped
 */
@Composable
fun ChargeCard(
    charge: ChargeEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    currencySymbol: String = "Br"
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
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
                // Time range
                val timeRange = buildString {
                    append(formatTime(charge.startTs))
                    append(" – ")
                    append(charge.endTs?.let { formatTime(it) } ?: "…")
                }
                Text(
                    text = timeRange,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Duration
                    if (charge.endTs != null) {
                        Text(
                            text = formatDuration(charge.startTs, charge.endTs),
                            color = TextSecondary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    // AC/DC type badge
                    if (charge.type != null) {
                        val badgeColor = if (charge.type == "DC") {
                            Color(0xFFFF9800) // Orange for DC fast charge
                        } else {
                            Color(0xFF2196F3) // Blue for AC
                        }
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .background(
                                    color = badgeColor,
                                    shape = RoundedCornerShape(6.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = charge.type,
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
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
                // SOC start → end
                val socStartText = charge.socStart?.let { "$it%" } ?: "—"
                val socEndText = charge.socEnd?.let { "$it%" } ?: "—"
                val socStartClr = charge.socStart?.let { socColor(it) } ?: TextSecondary
                val socEndClr = charge.socEnd?.let { socColor(it) } ?: TextSecondary

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = socStartText,
                        color = socStartClr,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = " → ",
                        color = TextSecondary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = socEndText,
                        color = socEndClr,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // kWh charged
                val kwhText = charge.kwhCharged?.let { "%.1f кВт·ч".format(it) } ?: "— кВт·ч"
                Text(
                    text = kwhText,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // Bottom row: max power and cost
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Max power
                val powerText = charge.maxPowerKw?.let { "⚡ %.1f кВт".format(it) } ?: ""
                if (powerText.isNotEmpty()) {
                    Text(
                        text = powerText,
                        color = TextSecondary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Cost
                val costText = charge.cost?.let { "$currencySymbol%.2f".format(it) } ?: ""
                if (costText.isNotEmpty()) {
                    Text(
                        text = costText,
                        color = TextSecondary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

// ============================================================================
// SummaryRow - Horizontal row with 3 summary stats
// ============================================================================

/**
 * Horizontal row displaying three key summary statistics in rounded boxes:
 * total distance, total energy, and average consumption.
 *
 * @param totalKm Total distance in kilometers
 * @param totalKwh Total energy consumed in kWh
 * @param avgKwhPer100km Average consumption in kWh/100km
 */
@Composable
fun SummaryRow(
    totalKm: Double,
    totalKwh: Double,
    avgKwhPer100km: Double,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Total distance
        SummaryStatBox(
            value = "%.1f".format(totalKm),
            unit = "км",
            label = "Пробег",
            valueColor = Color.White,
            modifier = Modifier.weight(1f)
        )

        // Total energy
        SummaryStatBox(
            value = "%.1f".format(totalKwh),
            unit = "кВт·ч",
            label = "Энергия",
            valueColor = Color.White,
            modifier = Modifier.weight(1f)
        )

        // Average consumption with color coding
        SummaryStatBox(
            value = "%.1f".format(avgKwhPer100km),
            unit = "кВт·ч/100км",
            label = "Расход",
            valueColor = consumptionColor(avgKwhPer100km),
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * Single stat box used inside SummaryRow.
 * Displays a value with unit and label in a rounded container.
 */
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
            .background(
                color = CardBackground,
                shape = RoundedCornerShape(12.dp)
            )
            .height(76.dp) // Min 76dp touch target per design spec
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Value + unit on the same line
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = value,
                    color = valueColor,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = " $unit",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }

            // Label below
            Text(
                text = label,
                color = TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
