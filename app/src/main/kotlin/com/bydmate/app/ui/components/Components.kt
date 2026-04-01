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
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
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
    kwhPer100km < 20.0 -> ConsumptionGood
    kwhPer100km <= 30.0 -> ConsumptionMid
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
    val animatedSoc by animateFloatAsState(
        targetValue = clampedSoc / 100f,
        animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
        label = "socSweep"
    )
    val socSweep = totalSweep * animatedSoc
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

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "$clampedSoc",
                color = TextPrimary,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
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
    // Compact single-row trip card with weight-based columns
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(CardSurface, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Time range
        val timeRange = buildString {
            append(formatDateTime(trip.startTs))
            append("–")
            append(trip.endTs?.let { formatTime(it) } ?: "…")
        }
        Text(text = timeRange, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace, modifier = Modifier.weight(2.5f))

        // Duration (2nd column)
        Text(
            text = if (trip.endTs != null) formatDuration(trip.startTs, trip.endTs) else "…",
            color = TextMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f)
        )

        // Distance
        Text(
            text = trip.distanceKm?.let { "%.1f".format(it) } ?: "—",
            color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f)
        )

        // kWh
        Text(
            text = trip.kwhConsumed?.let { "%.1f".format(it) } ?: "—",
            color = TextSecondary, fontSize = 14.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f)
        )

        // Consumption — color-coded
        val consumptionText = trip.kwhPer100km?.let { "%.1f".format(it) } ?: "—"
        val consumptionClr = trip.kwhPer100km?.let { consumptionColor(it) } ?: TextSecondary
        Text(text = consumptionText, color = consumptionClr, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))

        // Cost
        Text(
            text = trip.cost?.let { "${"%.1f".format(it)}" } ?: "",
            color = AccentGreen, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f)
        )
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
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        border = BorderStroke(1.dp, CardBorder.copy(alpha = 0.3f)),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Line 1: time range, type badge, SOC range, kWh
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val timeRange = buildString {
                    append(formatTime(charge.startTs))
                    append("–")
                    append(charge.endTs?.let { formatTime(it) } ?: "…")
                }
                Text(text = timeRange, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)

                if (charge.type != null) {
                    val badgeColor = if (charge.type == "DC") AccentOrange else AccentBlue
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .background(color = badgeColor, shape = RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) {
                        Text(text = charge.type, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                val socText = buildString {
                    append(charge.socStart?.let { "$it%" } ?: "—")
                    append("→")
                    append(charge.socEnd?.let { "$it%" } ?: "—")
                }
                Text(text = socText, color = TextPrimary, fontSize = 14.sp)

                val kwhText = charge.kwhCharged?.let { "%.1f кВт·ч".format(it) } ?: "—"
                Text(text = kwhText, color = TextPrimary, fontSize = 14.sp)
            }

            // Line 2: duration, avg power, bat temp, cost
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (charge.endTs != null) {
                    Text(text = formatDuration(charge.startTs, charge.endTs), color = TextSecondary, fontSize = 14.sp)
                }

                if (charge.avgPowerKw != null) {
                    Text(text = "avg %.1f кВт".format(charge.avgPowerKw), color = TextSecondary, fontSize = 14.sp)
                }

                if (charge.batTempAvg != null) {
                    Text(text = "bat %.0f°C".format(charge.batTempAvg), color = TextSecondary, fontSize = 14.sp)
                }

                val costText = charge.cost?.let { "$currencySymbol%.2f".format(it) } ?: ""
                if (costText.isNotEmpty()) {
                    Text(text = costText, color = AccentGreen, fontSize = 14.sp)
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
    totalCost: Double = 0.0,
    currencySymbol: String = "",
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
        if (totalCost > 0) {
            SummaryStatBox(
                value = "%.0f".format(totalCost),
                unit = currencySymbol,
                label = "Стоимость",
                valueColor = AccentGreen,
                modifier = Modifier.weight(1f)
            )
        }
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
