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
import androidx.compose.material.icons.outlined.Route
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
import com.bydmate.app.ui.theme.SocRed
import com.bydmate.app.ui.theme.SocYellow
import com.bydmate.app.ui.theme.TextMuted
import com.bydmate.app.ui.theme.TextPrimary
import kotlinx.coroutines.delay

/**
 * v3.1 layout — 260 × 108 dp, 3 rows, SOC row vertically centered.
 *
 * Row 1 (top, service, three equal slots):
 *   left — trip duration (⏱), center — trip distance (🗺 route), right — cabin temp (🚗). 13sp.
 * Row 2 (center, main): SOC% (18sp, status color) · range km (28sp bold white) · consumption + trend (18sp).
 * Row 3 (bottom, service): battery temperature (🔋), 12V (⚡) — 13sp.
 *
 * Icons are muted gray, values are white in service rows. Km is always white
 * regardless of SOC status (only the border + SOC % + trend text colorize).
 */
@Composable
fun FloatingWidgetView(
    soc: Int?,
    rangeKm: Double?,
    consumption: Double?,
    trend: Trend,
    sessionStartedAt: Long?,
    tripDistanceKm: Double?,
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
            .size(width = 260.dp, height = 108.dp)
            .shadow(elevation = 8.dp, shape = RoundedCornerShape(14.dp))
            .background(CardSurface, RoundedCornerShape(14.dp))
            .border(1.5.dp, borderColor, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        RowTrip(
            sessionStartedAt = sessionStartedAt,
            tripDistanceKm = tripDistanceKm,
            insideTemp = insideTemp,
        )
        WidgetDivider()
        // First 300 m of an active session: the trend stat is still based on the
        // PREVIOUS trip's buffer (current trip hasn't moved enough to enter the
        // 2-km short window yet). A confident DOWN/UP arrow here would imply
        // "your driving right now is going green/red", which misleads the user
        // when they've just started or are idle. Suppress the trend until they've
        // actually driven 300 m — by then the buffer has fresh rows for this trip.
        // The numeric value still renders (Trend.NONE colors it muted gray) so
        // the user can still eyeball range, just without a confident verdict.
        val effectiveTrend = if (
            sessionStartedAt != null &&
            (tripDistanceKm ?: 0.0) < TRIP_DISTANCE_TREND_THRESHOLD_KM
        ) Trend.NONE else trend
        RowEnergy(soc = soc, rangeKm = rangeKm, consumption = consumption, trend = effectiveTrend)
        WidgetDivider()
        RowService(batTemp = batTemp, voltage12v = voltage12v)
    }
}

private const val TRIP_DISTANCE_TREND_THRESHOLD_KM = 0.3

@Composable
private fun RowEnergy(
    soc: Int?,
    rangeKm: Double?,
    consumption: Double?,
    trend: Trend,
) {
    val trendColor = when (trend) {
        Trend.DOWN -> AccentGreen
        Trend.UP -> SocYellow
        Trend.FLAT -> TextPrimary
        Trend.NONE -> TextMuted
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
            color = socColor(soc),
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = rangeKm?.let { "~${"%.0f".format(it)}" } ?: "~—",
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.Monospace,
                color = TextPrimary,
            )
            Spacer(Modifier.width(3.dp))
            Text(
                text = "км",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = TextPrimary,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
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
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(3.dp))
            }
            Text(
                text = consumption?.let { "%.1f".format(it) } ?: "—",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = if (trend == Trend.NONE) TextMuted else trendColor,
            )
        }
    }
}

@Composable
private fun RowTrip(
    sessionStartedAt: Long?,
    tripDistanceKm: Double?,
    insideTemp: Int?,
) {
    val durationText by produceState(initialValue = formatDurationShort(sessionStartedAt), sessionStartedAt) {
        while (true) {
            value = formatDurationShort(sessionStartedAt)
            delay(15_000L)
        }
    }
    // Three equal slots so long labels ("1ч 25м", "287 км") never collide.
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            IconText(icon = Icons.Outlined.Schedule, text = durationText)
        }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            IconText(icon = Icons.Outlined.Route, text = formatTripKm(tripDistanceKm))
        }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            IconText(icon = Icons.Outlined.DirectionsCar, text = insideTemp?.let { "$it°" } ?: "—")
        }
    }
}

@Composable
private fun RowService(
    batTemp: Int?,
    voltage12v: Double?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconText(icon = Icons.Outlined.Battery6Bar, text = batTemp?.let { "$it°" } ?: "—")
        IconText(icon = Icons.Outlined.Bolt, text = voltage12v?.let { "${"%.1f".format(it)} В" } ?: "—")
    }
}

/** Icon muted gray, value white, 13sp. */
@Composable
private fun IconText(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = TextMuted,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = text,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            color = TextPrimary,
        )
    }
}

@Composable
private fun WidgetDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(TextMuted.copy(alpha = 0.22f)),
    )
}

private fun formatDurationShort(sessionStartedAt: Long?): String {
    if (sessionStartedAt == null) return "—"
    val elapsed = System.currentTimeMillis() - sessionStartedAt
    val totalMin = (elapsed / 60_000L).toInt().coerceAtLeast(0)
    val hours = totalMin / 60
    val minutes = totalMin % 60
    // Compact form in hours mode ("1ч 25м") keeps label narrow enough to fit in
    // the top row's 1/3-width slot alongside trip distance and cabin temp.
    return if (hours > 0) "${hours}ч ${minutes}м" else "$minutes мин"
}

private fun formatTripKm(km: Double?): String {
    if (km == null || km.isNaN() || km.isInfinite() || km < 0.0) return "—"
    return if (km < 10.0) "%.1f км".format(km) else "%.0f км".format(km)
}

// ---- Status model (covered by WidgetStatusTest) ----

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

private fun socColor(soc: Int?): Color = when {
    soc == null -> TextMuted
    soc < 15 -> SocRed
    soc < 30 -> SocYellow
    else -> AccentGreen
}
