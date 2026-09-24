package com.bydmate.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bydmate.app.R
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

data class ConsumptionThresholds(val good: Double, val bad: Double) {
    companion object {
        val Default = ConsumptionThresholds(good = 20.0, bad = 30.0)
    }
}

// Provided by MainActivity / WidgetController from SettingsRepository so user-edited
// thresholds in Settings actually recolor every screen and the floating widget.
val LocalConsumptionThresholds = compositionLocalOf { ConsumptionThresholds.Default }

// Pure helper — testable without Compose runtime.
fun consumptionColor(kwhPer100km: Double, good: Double, bad: Double): Color = when {
    kwhPer100km < good -> ConsumptionGood
    kwhPer100km <= bad -> ConsumptionMid
    else -> ConsumptionBad
}

// Composable overload — reads thresholds from CompositionLocal so existing
// `consumptionColor(value)` callsites pick up the user-configured values.
@Composable
@ReadOnlyComposable
fun consumptionColor(kwhPer100km: Double): Color {
    val t = LocalConsumptionThresholds.current
    return consumptionColor(kwhPer100km, t.good, t.bad)
}

fun formatTime(ts: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(ts))
}

fun formatDateTime(ts: Long): String {
    val sdf = SimpleDateFormat("dd.MM  HH:mm", Locale.getDefault())
    return sdf.format(Date(ts))
}

fun formatDuration(context: android.content.Context, startTs: Long, endTs: Long): String {
    val durationMs = endTs - startTs
    val hours = TimeUnit.MILLISECONDS.toHours(durationMs)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMs) % 60
    return if (hours > 0) context.getString(R.string.common_duration_hours_minutes, hours.toInt(), minutes.toInt())
    else context.getString(R.string.common_duration_minutes, minutes.toInt())
}

// Language-neutral h:mm form (1:32, 0:05) for cells too narrow for formatDuration's words.
fun formatDurationCompact(startTs: Long, endTs: Long): String {
    val totalMin = ((endTs - startTs) / 60_000L).coerceAtLeast(0L)
    return "%d:%02d".format(totalMin / 60, totalMin % 60)
}

// Единый стиль Switch по всему приложению:
// включён — зелёный track + тёмный thumb, выключен — серый track + тёмный thumb.
@Composable
fun bydSwitchColors(): SwitchColors = SwitchDefaults.colors(
    checkedThumbColor = NavyMid,
    checkedTrackColor = AccentGreen,
    checkedBorderColor = Color.Transparent,
    uncheckedThumbColor = NavyMid,
    uncheckedTrackColor = TextMuted,
    uncheckedBorderColor = Color.Transparent,
)

// ============================================================================
// SocGauge - Premium circular arc gauge with gradient and glow
// ============================================================================

private val SocGaugeStroke = 14.dp
private val SocGaugeIconSize = 18.dp
private val SocGaugeNumberSize = 32.sp
private val SocGaugeLabelSize = 12.sp
private const val SOC_GAUGE_LABEL = "SOC %"

@Composable
fun SocGauge(
    soc: Int,
    modifier: Modifier = Modifier,
    isCharging: Boolean = false,
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
    val strokeWidth = SocGaugeStroke

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
            if (isCharging) {
                Icon(
                    imageVector = Icons.Outlined.Bolt,
                    contentDescription = stringResource(R.string.battery_health_charging_cd),
                    tint = AccentGreen,
                    modifier = Modifier.size(SocGaugeIconSize)
                )
            }
            Text(
                text = "$clampedSoc",
                color = TextPrimary,
                fontSize = SocGaugeNumberSize,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center
            )
            Text(
                text = SOC_GAUGE_LABEL,
                color = TextMuted,
                fontSize = SocGaugeLabelSize,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** One row of SocGauge's centred content, px: layout height, ink top and bottom within it, width. */
internal data class GaugeContentRow(val height: Float, val inkTop: Float, val inkBottom: Float, val width: Float)

/**
 * Smallest square SocGauge can take before any row of its centred content reaches the arc's
 * inner edge, which lies [strokePx] inside half the square. Pure geometry, px in and out.
 */
internal fun socGaugeMinSizePx(rows: List<GaugeContentRow>, strokePx: Float): Float {
    var top = -rows.sumOf { it.height.toDouble() }.toFloat() / 2f
    var radius = 0f
    for (row in rows) {
        val reach = maxOf(kotlin.math.abs(top + row.inkTop), kotlin.math.abs(top + row.inkBottom))
        radius = maxOf(radius, kotlin.math.hypot(row.width / 2f, reach))
        top += row.height
    }
    return 2f * (radius + strokePx)
}

/**
 * Smallest SocGauge size that keeps «100», «SOC %» and, while charging, the bolt clear of the
 * arc at the current text scale (app setting times system font scale). Glyphs of the 32 sp
 * number are taller than the theme line height, so the ink comes from the platform paint.
 */
@Composable
fun rememberSocGaugeMinSize(isCharging: Boolean): Dp {
    val measurer = rememberTextMeasurer()
    val baseStyle = LocalTextStyle.current
    val density = LocalDensity.current
    return remember(measurer, baseStyle, density, isCharging) {
        with(density) {
            val rows = buildList {
                if (isCharging) {
                    val icon = SocGaugeIconSize.toPx()
                    add(GaugeContentRow(icon, 0f, icon, icon))
                }
                add(gaugeTextRow(measurer, "100",
                    baseStyle.merge(TextStyle(fontSize = SocGaugeNumberSize, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)),
                    android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD),
                    SocGaugeNumberSize.toPx()))
                add(gaugeTextRow(measurer, SOC_GAUGE_LABEL,
                    baseStyle.merge(TextStyle(fontSize = SocGaugeLabelSize, fontWeight = FontWeight.Medium)),
                    android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, FontWeight.Medium.weight, false),
                    SocGaugeLabelSize.toPx()))
            }
            // A lower bound: round up, or the arc could still touch the ink by a fraction of a px.
            kotlin.math.ceil(socGaugeMinSizePx(rows, SocGaugeStroke.toPx())).toDp()
        }
    }
}

// Row height and width as Compose lays the text out; ink bounds from the paint, on that baseline.
private fun gaugeTextRow(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    typeface: android.graphics.Typeface,
    textSizePx: Float,
): GaugeContentRow {
    val layout = measurer.measure(text, style, softWrap = false, maxLines = 1)
    val ink = android.graphics.Rect()
    android.graphics.Paint().apply {
        this.typeface = typeface
        textSize = textSizePx
    }.getTextBounds(text, 0, text.length, ink)
    return GaugeContentRow(
        height = layout.size.height.toFloat(),
        inkTop = layout.firstBaseline + ink.top,
        inkBottom = layout.firstBaseline + ink.bottom,
        width = layout.size.width.toFloat(),
    )
}

// ============================================================================
// TripCard
// ============================================================================

@Composable
fun TripCard(
    trip: TripEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    currencySymbol: String = "BYN",
    verticalPadding: Dp = 8.dp,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // Compact single-row trip card with weight-based columns
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(CardSurface, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Time range
        val timeRange = buildString {
            append(formatDateTime(trip.startTs))
            append(" – ")
            append(trip.endTs?.let { formatTime(it) } ?: "…")
        }
        Text(text = timeRange, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace, modifier = Modifier.weight(2.5f))

        // Duration (2nd column): always one line. The worded form while the cell fits it,
        // h:mm once a large text size leaves the cell too narrow, ellipsized if even that is
        // too wide so a cut «24:00» never reads as a shorter time.
        val durationStyle = LocalTextStyle.current.merge(TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace))
        val measurer = rememberTextMeasurer()
        BoxWithConstraints(modifier = Modifier.weight(1f)) {
            val durationText = if (trip.endTs != null) {
                val full = formatDuration(ctx, trip.startTs, trip.endTs)
                val fullWidth = measurer.measure(full, durationStyle, softWrap = false, maxLines = 1).size.width
                if (fullWidth <= constraints.maxWidth) full else formatDurationCompact(trip.startTs, trip.endTs)
            } else "…"
            Text(
                text = durationText,
                color = TextMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.End, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Distance
        Text(
            text = trip.distanceKm?.let { "%.1f".format(it) } ?: "—",
            color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )

        // kWh
        Text(
            text = trip.kwhConsumed?.let { "%.1f".format(it) } ?: "—",
            color = AccentBlue, fontSize = 14.sp, fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )

        // Consumption — color-coded
        val consumptionText = trip.kwhPer100km?.let { "%.1f".format(it) } ?: "—"
        val consumptionClr = trip.kwhPer100km?.let { consumptionColor(it) } ?: TextSecondary
        Text(
            text = consumptionText, color = consumptionClr, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )

        // Cost
        Text(
            text = trip.cost?.let { "${"%.1f".format(it)}" } ?: "",
            color = AccentGreen, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End,
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
    currencySymbol: String = "BYN"
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
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
                    Text(text = formatDuration(ctx, charge.startTs, charge.endTs), color = TextSecondary, fontSize = 14.sp)
                }

                if (charge.avgPowerKw != null) {
                    Text(text = "avg %.1f кВт".format(charge.avgPowerKw), color = TextSecondary, fontSize = 14.sp)
                }

                if (charge.batTempAvg != null) {
                    Text(text = "bat %.0f°C".format(charge.batTempAvg), color = TextSecondary, fontSize = 14.sp)
                }

                val costText = charge.cost?.let { "%.2f %s".format(it, currencySymbol) } ?: ""
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
            unit = stringResource(R.string.summary_unit_km),
            label = stringResource(R.string.summary_label_mileage),
            valueColor = TextPrimary,
            modifier = Modifier.weight(1f)
        )
        SummaryStatBox(
            value = "%.1f".format(totalKwh),
            unit = stringResource(R.string.summary_unit_kwh),
            label = stringResource(R.string.summary_label_energy),
            valueColor = TextPrimary,
            modifier = Modifier.weight(1f)
        )
        SummaryStatBox(
            value = "%.1f".format(avgKwhPer100km),
            unit = stringResource(R.string.summary_unit_kwh_per_100km),
            label = stringResource(R.string.summary_label_consumption),
            valueColor = consumptionColor(avgKwhPer100km),
            modifier = Modifier.weight(1f)
        )
        if (totalCost > 0) {
            SummaryStatBox(
                value = "%.0f".format(totalCost),
                unit = currencySymbol,
                label = stringResource(R.string.summary_label_cost),
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
