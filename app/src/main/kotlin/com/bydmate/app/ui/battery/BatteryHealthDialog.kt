package com.bydmate.app.ui.battery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bydmate.app.ui.theme.AccentBlue
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.CardSurface
import com.bydmate.app.ui.theme.CardSurfaceElevated
import com.bydmate.app.ui.theme.SocRed
import com.bydmate.app.ui.theme.SocYellow
import com.bydmate.app.ui.theme.TextMuted
import com.bydmate.app.ui.theme.TextPrimary
import com.bydmate.app.ui.theme.TextSecondary

/**
 * Battery Health expand dialog.
 *
 * Two blocks:
 *  - "Сейчас" — live readings from DiPlus, available to all users.
 *  - "Lifetime" — values from BMS via autoservice. If autoservice is disabled,
 *    a placeholder with a hint to enable «Системные данные» is shown instead.
 *
 * Cell delta is colored against an LFP-friendly scale: <30mV green, 30-100mV
 * yellow, >100mV red.
 *
 * Positioning matches AI Insights / parking dialog (Alignment.CenterStart),
 * tap on scrim or on the card body dismisses.
 */
@Composable
fun BatteryHealthDialog(
    liveSoc: Int?,
    liveCellDelta: Double?,
    liveBatTemp: Int?,
    liveVoltage12v: Double?,
    liveSoh: Float?,
    liveLifetimeKm: Float?,
    liveLifetimeKwh: Float?,
    autoserviceEnabled: Boolean,
    borderColor: Color,
    onDismiss: () -> Unit,
) {
    val scrimSource = remember { MutableInteractionSource() }
    val cardSource = remember { MutableInteractionSource() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(indication = null, interactionSource = scrimSource) { onDismiss() },
            contentAlignment = Alignment.CenterStart
        ) {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = CardSurface),
                border = androidx.compose.foundation.BorderStroke(2.dp, borderColor.copy(alpha = 0.6f)),
                modifier = Modifier
                    .padding(start = 22.dp, end = 16.dp)
                    .fillMaxWidth(0.55f)
                    .clickable(indication = null, interactionSource = cardSource) { onDismiss() }
            ) {
                Column(
                    modifier = Modifier
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Здоровье батареи",
                        color = borderColor,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )

                    SectionHeader("Сейчас (от машины, live)")
                    LiveBlock(
                        soc = liveSoc,
                        cellDelta = liveCellDelta,
                        batTemp = liveBatTemp,
                        voltage12v = liveVoltage12v
                    )

                    SectionHeader("Lifetime (системные данные)")
                    if (autoserviceEnabled) {
                        LifetimeBlock(
                            soh = liveSoh,
                            lifetimeKm = liveLifetimeKm,
                            lifetimeKwh = liveLifetimeKwh
                        )
                    } else {
                        LifetimeDisabledBlock()
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        color = TextMuted,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp
    )
}

@Composable
private fun LiveBlock(
    soc: Int?,
    cellDelta: Double?,
    batTemp: Int?,
    voltage12v: Double?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardSurfaceElevated, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        StatCell("SoC", soc?.let { "$it%" } ?: "—", TextPrimary)
        StatCell(
            "Δ ячеек",
            cellDelta?.let { "%.3f В".format(it) } ?: "—",
            cellDeltaColor(cellDelta)
        )
        StatCell("темп. батареи", batTemp?.let { "$it°C" } ?: "—", TextPrimary)
        StatCell("бортовая сеть", voltage12v?.let { "%.1f В".format(it) } ?: "—", TextPrimary)
    }
}

@Composable
private fun LifetimeBlock(
    soh: Float?,
    lifetimeKm: Float?,
    lifetimeKwh: Float?
) {
    val avgPer100 = if (lifetimeKm != null && lifetimeKwh != null && lifetimeKm > 0)
        lifetimeKwh / lifetimeKm * 100.0 else null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardSurfaceElevated, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        StatCell("SoH", soh?.let { "%.0f%%".format(it) } ?: "—", AccentGreen)
        StatCell("пробег BMS", lifetimeKm?.let { "%.1f км".format(it) } ?: "—", TextPrimary)
        StatCell("прокачано", lifetimeKwh?.let { "%.0f кВт·ч".format(it) } ?: "—", TextPrimary)
        StatCell(
            "/100км lifetime",
            avgPer100?.let { "%.1f".format(it) } ?: "—",
            AccentBlue
        )
    }
}

@Composable
private fun LifetimeDisabledBlock() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardSurfaceElevated, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            "Системные данные выключены",
            color = TextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            "Включи «Системные данные» в Настройках, чтобы видеть SoH, пробег BMS и прокачано всего от машины.",
            color = TextMuted,
            fontSize = 11.sp,
            lineHeight = 15.sp
        )
    }
}

@Composable
private fun StatCell(label: String, value: String, valueColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            color = valueColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace
        )
        Text(label, color = TextSecondary, fontSize = 10.sp)
    }
}

private fun cellDeltaColor(delta: Double?): Color = when {
    delta == null -> TextPrimary
    delta < 0.030 -> AccentGreen
    delta < 0.100 -> SocYellow
    else -> SocRed
}
