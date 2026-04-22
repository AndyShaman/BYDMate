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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.CardSurface
import com.bydmate.app.ui.theme.SocRed
import com.bydmate.app.ui.theme.SocYellow
import com.bydmate.app.ui.theme.TextMuted
import com.bydmate.app.ui.theme.TextPrimary

/**
 * B2 layout: two-row card, 220 × 78 dp, colored border by status.
 *
 * @param soc 0–100, or null when data is missing
 * @param rangeKm estimated remaining range, or null
 * @param batTemp battery temperature °C, or null
 * @param voltage12v 12V bus voltage, or null
 */
@Composable
fun FloatingWidgetView(
    soc: Int?,
    rangeKm: Double?,
    batTemp: Int?,
    voltage12v: Double?,
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
            .size(width = 220.dp, height = 78.dp)
            .shadow(elevation = 8.dp, shape = RoundedCornerShape(12.dp))
            .background(CardSurface, RoundedCornerShape(12.dp))
            .border(1.5.dp, borderColor, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = soc?.let { "$it%" } ?: "—",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = borderColor,
            )
            Text(
                text = rangeKm?.let { "~${"%.0f".format(it)} км" } ?: "~— км",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                color = if (rangeKm != null) TextPrimary else TextMuted,
            )
        }

        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(TextMuted.copy(alpha = 0.2f)),
        )
        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = batTemp?.let { "${it}°C" } ?: "—",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = TextMuted,
            )
            Text(
                text = voltage12v?.let { "${"%.1f".format(it)} V" } ?: "—",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = TextMuted,
                textAlign = TextAlign.End,
            )
        }
    }
}

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
    // Worst wins, but NO_DATA (from missing soc) only applies when both missing
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
