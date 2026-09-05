package com.bydmate.app.ui.tech

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bydmate.app.R
import com.bydmate.app.ui.components.HelpIcon
import com.bydmate.app.ui.components.HintBlock
import com.bydmate.app.ui.theme.AccentBlue
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.AccentOrange
import com.bydmate.app.ui.theme.CardBorder
import com.bydmate.app.ui.theme.CardSurface
import com.bydmate.app.ui.theme.CardSurfaceElevated
import com.bydmate.app.ui.theme.NavyDark
import com.bydmate.app.ui.theme.NavyDeep
import com.bydmate.app.ui.theme.SocYellow
import com.bydmate.app.ui.theme.TextMuted
import com.bydmate.app.ui.theme.TextPrimary
import com.bydmate.app.ui.theme.TextSecondary
import kotlin.math.roundToInt

private const val DASH = "—"

/** Full scale of the BMS limit bars, kW. */
private const val LIMIT_BAR_FULL_SCALE_KW = 200.0

/**
 * «Техника» — the full-screen live panel behind the battery card on the Dashboard.
 * Replaces the old battery-health dialog: same numbers plus the technical readings the
 * autoservice batch now carries. Cards without a single live value are not drawn.
 */
@Composable
fun TechPanelScreen(
    onBack: () -> Unit,
    viewModel: TechPanelViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(NavyDark, NavyDeep)))
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Header(online = state.autoserviceOnline, onBack = onBack)

        if (!state.hasAnyCard) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.tech_no_data), color = TextMuted, fontSize = 14.sp)
            }
            return@Column
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            if (state.showBatteryNow) item { BatteryNowCard(state, viewModel::toggleHint) }
            if (state.showLimitsAndCells) item { LimitsAndCellsCard(state, viewModel::toggleHint) }
            if (state.showMotors) item { MotorsCard(state, viewModel::toggleHint) }
            if (state.showClimate) item { ClimateCard(state, viewModel::toggleHint) }
            if (state.showTyres) item { TyresCard(state, viewModel::toggleHint) }
            if (state.showHistory) item { HistoryCard(state, viewModel::toggleHint) }
        }
    }
}

@Composable
private fun Header(online: Boolean?, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .border(1.5.dp, TextMuted, CircleShape)
                .clickable { onBack() },
            contentAlignment = Alignment.Center
        ) {
            Text("‹", color = TextSecondary, fontSize = 16.sp)
        }
        Text(
            stringResource(R.string.tech_title),
            color = AccentGreen,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 14.dp)
        )
        Box(modifier = Modifier.weight(1f))
        Text(
            stringResource(
                if (online == true) R.string.tech_status_online else R.string.tech_status_offline
            ),
            color = TextMuted,
            fontSize = 11.sp
        )
    }
}

// ============================================================================
// Cards
// ============================================================================

@Composable
private fun BatteryNowCard(state: TechPanelUiState, onHint: (String) -> Unit) {
    val insulationMohm = state.insulationKohm?.let { it / 1000.0 }
    TechCard(stringResource(R.string.tech_card_battery_now)) {
        TechRow("SoC", state.soc?.let { "$it%" } ?: DASH)
        TechRow(
            "SoH",
            state.soh?.let { "%.0f%%".format(it) } ?: DASH,
            valueColor = if (state.soh != null) AccentGreen else TextPrimary,
            hintKey = "soh", onHint = onHint,
        )
        TechRow(
            stringResource(R.string.battery_health_bat_temp_label),
            state.batTemp?.let { stringResource(R.string.tech_value_temp, it) } ?: DASH,
        )
        TechRow(
            stringResource(R.string.tech_label_hv_voltage),
            state.hvVoltage?.let { stringResource(R.string.tech_value_volt, it) } ?: DASH,
            hintKey = "hvVoltage", onHint = onHint,
        )
        TechRow(
            stringResource(R.string.tech_label_power),
            state.powerKw?.let { stringResource(R.string.tech_value_kw, it.roundToInt()) } ?: DASH,
            valueColor = if ((state.powerKw ?: 0.0) < 0.0) AccentBlue else TextPrimary,
            hintKey = "power", onHint = onHint,
        )
        // #153: negative = the pack is taking energy in (green), positive = it is giving it away.
        TechRow(
            stringResource(R.string.tech_label_battery_power),
            state.batteryPowerW?.let { w ->
                val watts = stringResource(R.string.tech_value_watt, w.roundToInt())
                state.percentPerHour
                    ?.let { "$watts · " + stringResource(R.string.tech_value_percent_per_hour, it) }
                    ?: watts
            } ?: DASH,
            valueColor = when {
                state.batteryPowerW == null -> TextPrimary
                state.batteryPowerW < 0.0 -> AccentGreen
                else -> SocYellow
            },
            hintKey = "batteryPower", onHint = onHint,
        )
        TechRow(
            stringResource(R.string.battery_health_12v_label),
            state.voltage12v?.let { stringResource(R.string.battery_health_12v_value, it) } ?: DASH,
            valueColor = voltage12vColor(state.voltage12v),
            hintKey = "voltage12v", onHint = onHint,
        )
        TechRow(
            stringResource(R.string.dashboard_battery_insulation_label),
            insulationMohm?.let { stringResource(R.string.tech_value_mohm, it) } ?: DASH,
            valueColor = insulationColor(insulationMohm),
            hintKey = "insulation", onHint = onHint,
        )
        Hint(state.openHint, "soh", R.string.tech_hint_soh)
        Hint(state.openHint, "hvVoltage", R.string.tech_hint_hv_voltage)
        Hint(state.openHint, "power", R.string.tech_hint_power)
        Hint(state.openHint, "batteryPower", R.string.tech_hint_power)
        Hint(state.openHint, "voltage12v", R.string.tech_hint_12v)
        Hint(state.openHint, "insulation", R.string.tech_hint_insulation)
    }
}

@Composable
private fun LimitsAndCellsCard(state: TechPanelUiState, onHint: (String) -> Unit) {
    TechCard(
        stringResource(R.string.tech_card_limits),
        hintKey = "bmsLimits",
        onHint = onHint,
    ) {
        TechRow(
            stringResource(R.string.tech_label_max_charge),
            state.bmsMaxChargeKw?.let { stringResource(R.string.tech_value_kw, it.roundToInt()) } ?: DASH,
        )
        LimitBar(state.bmsMaxChargeKw, AccentBlue)
        TechRow(
            stringResource(R.string.tech_label_max_discharge),
            state.bmsMaxDischargeKw?.let { stringResource(R.string.tech_value_kw, it) } ?: DASH,
        )
        LimitBar(state.bmsMaxDischargeKw?.toDouble(), AccentOrange)
        Hint(state.openHint, "bmsLimits", R.string.tech_hint_bms_limits)

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
            SectionHeader(stringResource(R.string.tech_card_cells))
            HelpIcon { onHint("cells") }
        }
        TechRow(
            "min",
            state.cellMin?.let { stringResource(R.string.battery_health_cell_voltage_value, it) } ?: DASH,
            valueColor = cellMinVoltageColor(state.cellMin),
        )
        TechRow(
            "max",
            state.cellMax?.let { stringResource(R.string.battery_health_cell_voltage_value, it) } ?: DASH,
        )
        TechRow(
            stringResource(R.string.battery_health_cell_delta_label),
            state.cellDelta
                ?.let { stringResource(R.string.tech_value_mv, Math.round(it * 1000.0).toInt()) } ?: DASH,
            valueColor = cellDeltaColor(state.cellDelta, state.soc),
        )
        Hint(state.openHint, "cells", R.string.tech_hint_cells)
    }
}

@Composable
private fun MotorsCard(state: TechPanelUiState, onHint: (String) -> Unit) {
    TechCard(
        stringResource(R.string.tech_card_motors),
        hintKey = "motors",
        onHint = onHint,
    ) {
        PairRow(
            "",
            stringResource(R.string.tech_label_front),
            stringResource(R.string.tech_label_rear),
            valueColor = TextMuted,
        )
        PairRow(
            stringResource(R.string.tech_label_motor_temp),
            state.motorTempFront?.let { stringResource(R.string.tech_value_deg, it) } ?: DASH,
            state.motorTempRear?.let { stringResource(R.string.tech_value_deg, it) } ?: DASH,
        )
        PairRow(
            stringResource(R.string.tech_label_inverter_temp),
            state.inverterTempFront?.let { stringResource(R.string.tech_value_deg, it) } ?: DASH,
            state.inverterTempRear?.let { stringResource(R.string.tech_value_deg, it) } ?: DASH,
        )
        PairRow(
            stringResource(R.string.tech_label_rpm),
            state.motorRpmFront?.toString() ?: DASH,
            state.motorRpmRear?.toString() ?: DASH,
        )
        PairRow(
            stringResource(R.string.tech_label_pedals),
            state.pedalAccel?.let { stringResource(R.string.tech_value_percent, it) } ?: DASH,
            state.pedalBrake?.let { stringResource(R.string.tech_value_percent, it) } ?: DASH,
        )
        Hint(state.openHint, "motors", R.string.tech_hint_motors)
    }
}

@Composable
private fun ClimateCard(state: TechPanelUiState, onHint: (String) -> Unit) {
    TechCard(stringResource(R.string.tech_card_climate)) {
        TechRow(
            stringResource(R.string.tech_label_compressor),
            state.compressorW?.let { stringResource(R.string.tech_value_watt, it) } ?: DASH,
            hintKey = "compressor", onHint = onHint,
        )
        TechRow(
            stringResource(R.string.tech_label_climate),
            when (state.acStatus) {
                null -> DASH
                0 -> stringResource(R.string.tech_off)
                else -> stringResource(R.string.tech_on)
            },
        )
        PairRow(
            stringResource(R.string.tech_label_inside_outside),
            state.insideTemp?.let { stringResource(R.string.tech_value_deg, it) } ?: DASH,
            state.exteriorTemp?.let { stringResource(R.string.tech_value_deg, it) } ?: DASH,
        )
        Hint(state.openHint, "compressor", R.string.tech_hint_compressor)
    }
}

@Composable
private fun TyresCard(state: TechPanelUiState, onHint: (String) -> Unit) {
    TechCard(
        stringResource(R.string.tech_card_tyres),
        hintKey = "tyres",
        onHint = onHint,
    ) {
        TyreRow(stringResource(R.string.tech_tyre_fl), state.tirePressFL, state.tyreTempFL)
        TyreRow(stringResource(R.string.tech_tyre_fr), state.tirePressFR, state.tyreTempFR)
        TyreRow(stringResource(R.string.tech_tyre_rl), state.tirePressRL, state.tyreTempRL)
        TyreRow(stringResource(R.string.tech_tyre_rr), state.tirePressRR, state.tyreTempRR)
        Hint(state.openHint, "tyres", R.string.tech_hint_tyres)
    }
}

@Composable
private fun HistoryCard(state: TechPanelUiState, onHint: (String) -> Unit) {
    TechCard(stringResource(R.string.tech_card_history)) {
        TechRow(
            stringResource(R.string.battery_health_bms_mileage_label),
            state.lifetimeKm?.let { stringResource(R.string.battery_health_bms_mileage_value, it) } ?: DASH,
        )
        TechRow(
            stringResource(R.string.battery_health_pumped_label),
            state.lifetimeKwh?.let { stringResource(R.string.battery_health_pumped_value, it) } ?: DASH,
        )
        TechRow(
            stringResource(R.string.battery_health_avg_since_charge_label),
            state.avgSocSinceCharge?.let { "$it%" } ?: DASH,
            valueColor = avgSocColor(state.avgSocSinceCharge),
        )
        TechRow(
            stringResource(R.string.battery_health_avg_all_time_label),
            state.avgSocAllTime?.let { "$it%" } ?: DASH,
            valueColor = avgSocColor(state.avgSocAllTime),
            hintKey = "avgSoc", onHint = onHint,
        )
        Hint(state.openHint, "avgSoc", R.string.battery_health_avg_soc_hint)
    }
}

// ============================================================================
// Building blocks (visual style carried over from the battery-health dialog)
// ============================================================================

@Composable
private fun TechCard(
    header: String,
    hintKey: String? = null,
    onHint: (String) -> Unit = {},
    content: @Composable () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionHeader(header)
                if (hintKey != null) HelpIcon { onHint(hintKey) }
            }
            content()
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
private fun TechRow(
    label: String,
    value: String,
    valueColor: Color = TextPrimary,
    hintKey: String? = null,
    onHint: (String) -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = TextSecondary, fontSize = 12.sp)
            if (hintKey != null) HelpIcon { onHint(hintKey) }
        }
        Text(
            value,
            color = valueColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace
        )
    }
}

/** Label plus the front/rear pair, monospace so the two columns line up. */
@Composable
private fun PairRow(
    label: String,
    front: String,
    rear: String,
    valueColor: Color = TextPrimary,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextSecondary, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(front, color = valueColor, fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
            Text(rear, color = valueColor, fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun TyreRow(corner: String, pressureKpa: Int?, tempC: Int?) {
    val bar = pressureKpa?.let { it / 100.0 }
    val value = when {
        bar != null && tempC != null -> stringResource(R.string.tech_value_tyre, bar, tempC)
        bar != null -> stringResource(R.string.tech_value_tyre_pressure, bar)
        tempC != null -> stringResource(R.string.tech_value_deg, tempC)
        else -> DASH
    }
    TechRow(corner, value)
}

/** Proportional bar under a BMS limit; empty track when the limit is unknown. */
@Composable
private fun LimitBar(kw: Double?, color: Color) {
    val fraction = ((kw ?: 0.0) / LIMIT_BAR_FULL_SCALE_KW).coerceIn(0.0, 1.0).toFloat()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(CardSurfaceElevated, RoundedCornerShape(3.dp))
    ) {
        if (fraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(6.dp)
                    .background(color, RoundedCornerShape(3.dp))
            )
        }
    }
}

@Composable
private fun Hint(openHint: String?, key: String, textRes: Int) {
    if (openHint == key) {
        Box(modifier = Modifier.padding(top = 6.dp)) { HintBlock(stringResource(textRes)) }
    }
}
