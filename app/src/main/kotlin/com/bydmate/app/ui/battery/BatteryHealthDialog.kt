package com.bydmate.app.ui.battery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bydmate.app.R
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
    liveCellVoltageMin: Double?,
    liveCellVoltageMax: Double?,
    liveBatTemp: Int?,
    liveVoltage12v: Double?,
    liveSoh: Float?,
    liveLifetimeKm: Float?,
    liveLifetimeKwh: Float?,
    avgSocSinceCharge: Int?,
    avgSocAllTime: Int?,
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
                        stringResource(R.string.battery_health_dialog_title),
                        color = borderColor,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )

                    SectionHeader(stringResource(R.string.battery_health_now_header))
                    LiveBlock(
                        soc = liveSoc,
                        batTemp = liveBatTemp,
                        voltage12v = liveVoltage12v
                    )

                    SectionHeader(stringResource(R.string.battery_health_banks_header))
                    BanksBlock(
                        cellMin = liveCellVoltageMin,
                        cellMax = liveCellVoltageMax,
                        cellDelta = liveCellDelta
                    )

                    SectionHeader(stringResource(R.string.battery_health_lifetime_header))
                    LifetimeBlock(
                        soh = liveSoh,
                        lifetimeKm = liveLifetimeKm,
                        lifetimeKwh = liveLifetimeKwh
                    )

                    var avgSocHintOpen by remember { mutableStateOf(false) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionHeader(stringResource(R.string.battery_health_avg_soc_header))
                        HelpIcon { avgSocHintOpen = !avgSocHintOpen }
                    }
                    AvgSocBlock(sinceCharge = avgSocSinceCharge, allTime = avgSocAllTime)
                    if (avgSocHintOpen) {
                        HintBlock(stringResource(R.string.battery_health_avg_soc_hint))
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
        StatCell(stringResource(R.string.battery_health_bat_temp_label), batTemp?.let { "$it°C" } ?: "—", TextPrimary)
        StatCell(stringResource(R.string.battery_health_12v_label), voltage12v?.let { stringResource(R.string.battery_health_12v_value, it) } ?: "—", TextPrimary)
    }
}

@Composable
private fun BanksBlock(
    cellMin: Double?,
    cellMax: Double?,
    cellDelta: Double?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardSurfaceElevated, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        StatCell(
            "min",
            cellMin?.let { stringResource(R.string.battery_health_cell_voltage_value, it) } ?: "—",
            cellMinVoltageColor(cellMin)
        )
        StatCell(
            "max",
            cellMax?.let { stringResource(R.string.battery_health_cell_voltage_value, it) } ?: "—",
            TextPrimary
        )
        StatCell(
            "Δ",
            cellDelta?.let { stringResource(R.string.battery_health_cell_delta_value, it) } ?: "—",
            cellDeltaColor(cellDelta)
        )
    }
}

@Composable
private fun LifetimeBlock(
    soh: Float?,
    lifetimeKm: Float?,
    lifetimeKwh: Float?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardSurfaceElevated, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        StatCell("SoH", soh?.let { "%.0f%%".format(it) } ?: "—", AccentGreen)
        StatCell(stringResource(R.string.battery_health_bms_mileage_label), lifetimeKm?.let { stringResource(R.string.battery_health_bms_mileage_value, it) } ?: "—", TextPrimary)
        StatCell(stringResource(R.string.battery_health_pumped_label), lifetimeKwh?.let { stringResource(R.string.battery_health_pumped_value, it) } ?: "—", TextPrimary)
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
            stringResource(R.string.battery_health_disabled_title),
            color = TextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            stringResource(R.string.battery_health_disabled_hint),
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

/**
 * Low-voltage colour cue for the minimum cell. BYD Blade is LFP: the pack sits on
 * a flat ~3.2 V plateau and only drops past the "knee" below ~3.0 V/cell, with the
 * BMS cutoff around 2.5 V. A weak / imbalanced cell dips into the knee first, which
 * is the early warning for the sudden ~10% → 0 SOC collapse users report.
 * Visual-only — the configurable notification alert lives in Automations
 * (MinCellVoltage trigger).
 */
private fun cellMinVoltageColor(min: Double?): Color = when {
    min == null -> TextPrimary
    min >= 3.0 -> AccentGreen
    min >= 2.8 -> SocYellow
    else -> SocRed
}

@Composable
private fun AvgSocBlock(sinceCharge: Int?, allTime: Int?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardSurfaceElevated, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceAround
    ) {
        StatCell(
            stringResource(R.string.battery_health_avg_since_charge_label),
            sinceCharge?.let { "$it%" } ?: "—",
            avgSocColor(sinceCharge)
        )
        StatCell(
            stringResource(R.string.battery_health_avg_all_time_label),
            allTime?.let { "$it%" } ?: "—",
            avgSocColor(allTime)
        )
    }
}

/** «?» toggle for the inline hint. Its own clickable consumes the tap, so it does
 *  not bubble to the card-body clickable that dismisses the dialog. */
@Composable
private fun HelpIcon(onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .padding(start = 6.dp)
            .size(18.dp)
            .border(1.5.dp, TextMuted, CircleShape)
            .clickable(indication = null, interactionSource = source, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text("?", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HintBlock(text: String) {
    Text(
        text,
        color = TextSecondary,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        modifier = Modifier
            .fillMaxWidth()
            .background(CardSurfaceElevated, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    )
}

/** LFP calendar-aging cue (approved mock): <65 green, 65-85 yellow, >85 red. */
private fun avgSocColor(v: Int?): Color = when {
    v == null -> TextPrimary
    v < 65 -> AccentGreen
    v <= 85 -> SocYellow
    else -> SocRed
}
