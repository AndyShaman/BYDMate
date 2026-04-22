package com.bydmate.app.ui.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Battery6Bar
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.TrendingDown
import androidx.compose.material.icons.outlined.TrendingFlat
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bydmate.app.domain.calculator.Trend
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.CardSurface
import com.bydmate.app.ui.theme.SocGreen
import com.bydmate.app.ui.theme.SocRed
import com.bydmate.app.ui.theme.SocYellow
import com.bydmate.app.ui.theme.TextMuted
import com.bydmate.app.ui.theme.TextPrimary
import kotlinx.coroutines.delay

/**
 * v2 layout — 190 × 64 dp, 3 rows, 7 fields.
 *
 * Row 1: SOC% (bold, green gradient), range km, consumption + trend arrow
 * Row 2: trip duration (⏱), cabin temperature (🚗)
 * Row 3: battery temperature (🔋), 12V bus (⚡)
 *
 * @param soc 0–100, null when missing
 * @param rangeKm estimated remaining range, null when missing
 * @param consumption kWh/100km to display (EMA before 3-min prewarmup, trip-avg after)
 * @param trend NONE (hide arrow) / DOWN / FLAT / UP
 * @param tripStartedAt timestamp when active trip started, null when idle
 * @param insideTemp cabin temperature °C, null when missing
 * @param batTemp battery temperature °C, null when missing
 * @param voltage12v 12V bus voltage, null when missing
 * @param alpha overall widget alpha 0.3..1.0
 */
@Composable
fun FloatingWidgetView(
    soc: Int?,
    rangeKm: Double?,
    consumption: Double?,
    trend: Trend,
    tripStartedAt: Long?,
    insideTemp: Int?,
    batTemp: Int?,
    voltage12v: Double?,
    alpha: Float,
) {
    val status = widgetStatus(soc, voltage12v)
    val borderColor = when (status) {
        Status.OK -> AccentGreen
        Status.WARN -> SocYellow
        Status.CRIT -> SocRed
        Status.NO_DATA -> TextMuted.copy(alpha = 0.4f)
    }

    Column(
        modifier = Modifier
            .alpha(alpha.coerceIn(0.3f, 1.0f))
            .size(width = 190.dp, height = 64.dp)
            .shadow(elevation = 8.dp, shape = RoundedCornerShape(12.dp))
            .background(CardSurface, RoundedCornerShape(12.dp))
            .border(1.5.dp, borderColor, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row1Energy(
            soc = soc,
            socBorderColor = borderColor,
            rangeKm = rangeKm,
            consumption = consumption,
            trend = trend,
        )
        WidgetDivider()
        Row2Trip(
            tripStartedAt = tripStartedAt,
            insideTemp = insideTemp,
        )
        WidgetDivider()
        Row3Service(
            batTemp = batTemp,
            voltage12v = voltage12v,
        )
    }
}

@Composable
private fun Row1Energy(
    soc: Int?,
    socBorderColor: Color,
    rangeKm: Double?,
    consumption: Double?,
    trend: Trend,
) {
    val trendColor = when (trend) {
        Trend.DOWN -> AccentGreen
        Trend.UP -> SocYellow
        Trend.FLAT, Trend.NONE -> TextMuted
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = soc?.let { "$it%" } ?: "—",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = socBorderColor,
        )
        Text(
            text = rangeKm?.let { "~${"%.0f".format(it)} км" } ?: "~— км",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            color = if (rangeKm != null) TextPrimary else TextMuted,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (trend != Trend.NONE) {
                Icon(
                    imageVector = when (trend) {
                        Trend.DOWN -> Icons.Outlined.TrendingDown
                        Trend.UP -> Icons.Outlined.TrendingUp
                        else -> Icons.Outlined.TrendingFlat
                    },
                    contentDescription = null,
                    tint = trendColor,
                    modifier = Modifier.size(10.dp),
                )
                Spacer(Modifier.width(2.dp))
            }
            Text(
                text = consumption?.let { "%.1f".format(it) } ?: "—",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = if (trend == Trend.NONE) TextMuted else trendColor,
            )
        }
    }
}

@Composable
private fun Row2Trip(
    tripStartedAt: Long?,
    insideTemp: Int?,
) {
    val durationText by produceState(initialValue = formatDurationShort(tripStartedAt), tripStartedAt) {
        while (true) {
            value = formatDurationShort(tripStartedAt)
            delay(15_000L)
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconText(
            icon = Icons.Outlined.Schedule,
            text = durationText,
            textColor = if (tripStartedAt == null) TextMuted else TextPrimary,
        )
        IconText(
            icon = Icons.Outlined.DirectionsCar,
            text = insideTemp?.let { "$it°" } ?: "—",
            textColor = if (insideTemp != null) TextPrimary else TextMuted,
        )
    }
}

@Composable
private fun Row3Service(
    batTemp: Int?,
    voltage12v: Double?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconText(
            icon = Icons.Outlined.Battery6Bar,
            text = batTemp?.let { "$it°" } ?: "—",
            textColor = TextMuted,
            fontSize = 9,
        )
        IconText(
            icon = Icons.Outlined.Bolt,
            text = voltage12v?.let { "${"%.1f".format(it)} В" } ?: "—",
            textColor = TextMuted,
            fontSize = 9,
        )
    }
}

@Composable
private fun IconText(
    icon: ImageVector,
    text: String,
    textColor: Color,
    fontSize: Int = 10,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = TextMuted,
            modifier = Modifier.size(10.dp),
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text = text,
            fontSize = fontSize.sp,
            fontFamily = FontFamily.Monospace,
            color = textColor,
        )
    }
}

@Composable
private fun WidgetDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(TextMuted.copy(alpha = 0.2f)),
    )
}

private fun formatDurationShort(tripStartedAt: Long?): String {
    if (tripStartedAt == null) return "—"
    val elapsed = System.currentTimeMillis() - tripStartedAt
    val totalMin = (elapsed / 60_000L).toInt().coerceAtLeast(0)
    val hours = totalMin / 60
    val minutes = totalMin % 60
    return if (hours > 0) "${hours} ч ${minutes} мин" else "${minutes} мин"
}

// ---- Status model (unchanged, covered by WidgetStatusTest) ----

internal enum class Status { OK, WARN, CRIT, NO_DATA }

internal fun widgetStatus(soc: Int?, v12: Double?): Status {
    if (soc == null && v12 == null) return Status.NO_DATA
    val socStatus = when {
        soc == null -> Status.NO_DATA
        soc < 15 -> Status.CRIT
        soc < 30 -> Status.WARN
        else -> Status.OK
    }
    val vStatus = when {
        v12 == null -> Status.OK
        v12 < 12.0 -> Status.CRIT
        v12 < 12.5 -> Status.WARN
        else -> Status.OK
    }
    return listOf(socStatus, vStatus)
        .filter { it != Status.NO_DATA }
        .maxByOrNull { severity(it) }
        ?: Status.NO_DATA
}

private fun severity(s: Status): Int = when (s) {
    Status.NO_DATA -> -1
    Status.OK -> 0
    Status.WARN -> 1
    Status.CRIT -> 2
}
