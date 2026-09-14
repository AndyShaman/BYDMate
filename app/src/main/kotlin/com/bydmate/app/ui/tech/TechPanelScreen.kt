package com.bydmate.app.ui.tech

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bydmate.app.R
import com.bydmate.app.data.nativestack.MotorSplit
import com.bydmate.app.data.nativestack.motorSplitPercent
import com.bydmate.app.ui.components.HelpIcon
import com.bydmate.app.ui.components.HintBlock
import com.bydmate.app.ui.theme.AccentBlue
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.CardBorder
import com.bydmate.app.ui.theme.CardSurface
import com.bydmate.app.ui.theme.CardSurfaceElevated
import com.bydmate.app.ui.theme.NavyDark
import com.bydmate.app.ui.theme.NavyDeep
import com.bydmate.app.ui.theme.SocRed
import com.bydmate.app.ui.theme.SocYellow
import com.bydmate.app.ui.theme.TextMuted
import com.bydmate.app.ui.theme.TextPrimary
import com.bydmate.app.ui.theme.TextSecondary
import kotlin.math.roundToInt

private const val DASH = "—"

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

        if (state.showOrderHint) {
            Text(
                stringResource(R.string.tech_order_hint),
                color = TextMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        TechCardGrid(
            state = state,
            onHint = viewModel::toggleHint,
            onMove = viewModel::moveCard,
        )
    }
}

/** Columns of the card grid: the screen is 15.6" landscape, three cards fit across it. */
private const val GRID_COLUMNS = 3

/** How much a card grows while it is held, so the driver sees which one they picked up. */
private const val LIFT_SCALE = 1.03f

/**
 * The cards in the driver's own order, three per row. IntrinsicSize.Max makes every card in a
 * row as tall as the tallest one, so their bottoms line up instead of leaving holes under the
 * short ones. An open hint grows its whole row, as expected.
 *
 * Long-pressing a card lifts it; dragging its centre over another card's slot drops it into
 * that slot and the grid re-flows under the finger. The slot rectangles are kept by position,
 * not by card, so they survive a re-order mid-drag; the gesture itself also lives on the slot,
 * and tracks the card it picked up by identity.
 */
@Composable
private fun TechCardGrid(
    state: TechPanelUiState,
    onHint: (String) -> Unit,
    onMove: (TechCard, TechCard) -> Unit,
) {
    val cards = state.visibleCards
    val slots = remember { mutableStateMapOf<Int, Rect>() }
    val dragging = remember { mutableStateOf<TechCard?>(null) }
    val offset = remember { mutableStateOf(Offset.Zero) }
    val latestCards = rememberUpdatedState(cards)
    val latestMove = rememberUpdatedState(onMove)

    val onDrag: (Offset) -> Unit = { amount ->
        val card = dragging.value
        val from = card?.let { latestCards.value.indexOf(it) } ?: -1
        val fromRect = slots[from]
        if (card != null && fromRect != null) {
            offset.value += amount
            val centre = fromRect.center + offset.value
            val to = slots.entries.firstOrNull { (i, rect) ->
                i != from && i < latestCards.value.size && rect.contains(centre)
            }?.key
            val toRect = to?.let { slots[it] }
            if (to != null && toRect != null) {
                // Keep the lifted card under the finger across the re-flow: its slot has moved.
                offset.value += fromRect.topLeft - toRect.topLeft
                latestMove.value(card, latestCards.value[to])
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        cards.chunked(GRID_COLUMNS).forEachIndexed { rowIndex, rowCards ->
            val firstIndex = rowIndex * GRID_COLUMNS
            val holdsDragged = dragging.value?.let { it in rowCards } == true
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Max)
                    .zIndex(if (holdsDragged) 1f else 0f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                rowCards.forEachIndexed { column, card ->
                    val index = firstIndex + column
                    CardSlot(
                        lifted = dragging.value == card,
                        offset = offset.value,
                        onBounds = { slots[index] = it },
                        onLift = {
                            dragging.value = latestCards.value.getOrNull(index)
                            offset.value = Offset.Zero
                        },
                        onDrag = onDrag,
                        onDrop = {
                            dragging.value = null
                            offset.value = Offset.Zero
                        },
                    ) { cardModifier ->
                        TechCardContent(card, state, onHint, cardModifier)
                    }
                }
            }
        }
    }
}

/** One grid cell: an equal share of the row's width, stretched to the row's intrinsic height. */
@Composable
private fun RowScope.CardSlot(
    lifted: Boolean,
    offset: Offset,
    onBounds: (Rect) -> Unit,
    onLift: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDrop: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .onGloballyPositioned { onBounds(it.boundsInRoot()) }
            .zIndex(if (lifted) 1f else 0f)
            .graphicsLayer {
                if (lifted) {
                    translationX = offset.x
                    translationY = offset.y
                    scaleX = LIFT_SCALE
                    scaleY = LIFT_SCALE
                    shadowElevation = 16.dp.toPx()
                    shape = RoundedCornerShape(14.dp)
                }
            }
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { onLift() },
                    onDrag = { change, amount -> change.consume(); onDrag(amount) },
                    onDragEnd = { onDrop() },
                    onDragCancel = { onDrop() },
                )
            }
    ) {
        content(Modifier.fillMaxSize())
    }
}

@Composable
private fun TechCardContent(
    card: TechCard,
    state: TechPanelUiState,
    onHint: (String) -> Unit,
    modifier: Modifier,
) {
    when (card) {
        TechCard.BATTERY_NOW -> BatteryNowCard(state, onHint, modifier)
        TechCard.LIMITS -> LimitsAndCellsCard(state, onHint, modifier)
        TechCard.HISTORY -> HistoryCard(state, onHint, modifier)
        TechCard.MOTORS -> MotorsCard(state, onHint, modifier)
        TechCard.CLIMATE -> ClimateCard(state, onHint, modifier)
        TechCard.TYRES -> TyresCard(state, onHint, modifier)
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
private fun BatteryNowCard(state: TechPanelUiState, onHint: (String) -> Unit, modifier: Modifier = Modifier) {
    val insulationMohm = state.insulationKohm?.let { it / 1000.0 }
    TechCard(stringResource(R.string.tech_card_battery_now), modifier = modifier) {
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
        Hint(state.openHint, "power", R.string.tech_hint_motor_power)
        Hint(state.openHint, "batteryPower", R.string.tech_hint_power)
        Hint(state.openHint, "voltage12v", R.string.tech_hint_12v)
        Hint(state.openHint, "insulation", R.string.tech_hint_insulation)
    }
}

@Composable
private fun LimitsAndCellsCard(state: TechPanelUiState, onHint: (String) -> Unit, modifier: Modifier = Modifier) {
    TechCard(
        stringResource(R.string.tech_card_limits),
        hintKey = "bmsLimits",
        onHint = onHint,
        modifier = modifier,
    ) {
        TechRow(
            stringResource(R.string.tech_label_max_charge),
            state.bmsMaxChargeKw?.let { stringResource(R.string.tech_value_kw, it.roundToInt()) } ?: DASH,
        )
        TechRow(
            stringResource(R.string.tech_label_max_discharge),
            state.bmsMaxDischargeKw?.let { stringResource(R.string.tech_value_kw, it) } ?: DASH,
        )
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
        if (state.cellMin != null && state.cellMax != null) {
            CellRangeStrip(
                state.cellMin,
                state.cellMax,
                cellDeltaColor(state.cellDelta, state.soc),
            )
        }
        Hint(state.openHint, "cells", R.string.tech_hint_cells)
    }
}

/**
 * Rows of the motors card, in the order they are drawn. Kept as data so the order is pinned by
 * a unit test — the module has no Compose UI test harness. The front/rear split sits last:
 * VadimV reported it reads as a repeat of the per-motor numbers when it is at the top.
 */
internal enum class MotorRow { HEADER, MOTOR_TEMP, INVERTER_TEMP, RPM, PEDALS, POWER_SPLIT }

internal val MOTOR_CARD_ROWS: List<MotorRow> = listOf(
    MotorRow.HEADER,
    MotorRow.MOTOR_TEMP,
    MotorRow.INVERTER_TEMP,
    MotorRow.RPM,
    MotorRow.PEDALS,
    MotorRow.POWER_SPLIT,
)

@Composable
private fun MotorsCard(state: TechPanelUiState, onHint: (String) -> Unit, modifier: Modifier = Modifier) {
    TechCard(
        stringResource(R.string.tech_card_motors),
        hintKey = "motors",
        onHint = onHint,
        modifier = modifier,
    ) {
        MOTOR_CARD_ROWS.forEach { MotorCardRow(it, state) }
        Hint(state.openHint, "motors", R.string.tech_hint_motors)
    }
}

@Composable
private fun MotorCardRow(row: MotorRow, state: TechPanelUiState) {
    val anyTemp = state.motorTempFront ?: state.motorTempRear
        ?: state.inverterTempFront ?: state.inverterTempRear
    when (row) {
        MotorRow.HEADER -> PairRow(
            "",
            stringResource(R.string.tech_label_front),
            stringResource(R.string.tech_label_rear),
            valueColor = TextMuted,
        )
        MotorRow.MOTOR_TEMP -> {
            PairRow(
                stringResource(R.string.tech_label_motor_temp),
                state.motorTempFront?.let { stringResource(R.string.tech_value_deg, it) } ?: DASH,
                state.motorTempRear?.let { stringResource(R.string.tech_value_deg, it) } ?: DASH,
            )
            if (anyTemp != null) TempBarPair(state.motorTempFront, state.motorTempRear)
        }
        MotorRow.INVERTER_TEMP -> {
            PairRow(
                stringResource(R.string.tech_label_inverter_temp),
                state.inverterTempFront?.let { stringResource(R.string.tech_value_deg, it) } ?: DASH,
                state.inverterTempRear?.let { stringResource(R.string.tech_value_deg, it) } ?: DASH,
            )
            if (anyTemp != null) {
                TempBarPair(state.inverterTempFront, state.inverterTempRear)
                TempScaleCaption()
            }
        }
        MotorRow.RPM -> PairRow(
            stringResource(R.string.tech_label_rpm),
            rpmForDisplay(state.motorRpmFront)?.toString() ?: DASH,
            rpmForDisplay(state.motorRpmRear)?.toString() ?: DASH,
        )
        MotorRow.PEDALS -> PairRow(
            stringResource(R.string.tech_label_pedals),
            state.pedalAccel?.let { stringResource(R.string.tech_value_percent, it) } ?: DASH,
            state.pedalBrake?.let { stringResource(R.string.tech_value_percent, it) } ?: DASH,
        )
        MotorRow.POWER_SPLIT ->
            when (val split = motorSplitPercent(state.motorCurrentFront, state.motorCurrentRear)) {
                null -> Unit  // single-motor car or no reading: no row at all
                MotorSplit.Idle -> PairRow(stringResource(R.string.tech_label_power), DASH, DASH)
                is MotorSplit.Share -> PairRow(
                    stringResource(R.string.tech_label_power),
                    stringResource(R.string.tech_value_percent, split.frontPercent),
                    stringResource(R.string.tech_value_percent, split.rearPercent),
                )
            }
    }
}

@Composable
private fun ClimateCard(state: TechPanelUiState, onHint: (String) -> Unit, modifier: Modifier = Modifier) {
    TechCard(stringResource(R.string.tech_card_climate), modifier = modifier) {
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
private fun TyresCard(state: TechPanelUiState, onHint: (String) -> Unit, modifier: Modifier = Modifier) {
    TechCard(
        stringResource(R.string.tech_card_tyres),
        hintKey = "tyres",
        onHint = onHint,
        modifier = modifier,
    ) {
        TyreTopView(state)
        Hint(state.openHint, "tyres", R.string.tech_hint_tyres)
    }
}

@Composable
private fun HistoryCard(state: TechPanelUiState, onHint: (String) -> Unit, modifier: Modifier = Modifier) {
    TechCard(stringResource(R.string.tech_card_history), modifier = modifier) {
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
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder),
        modifier = modifier.fillMaxWidth()
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

/**
 * Range of the cell voltages drawn on a fixed LFP scale, so a wide spread is visible as a wide
 * band without reading the numbers. The band carries the same colour as the delta value.
 */
@Composable
private fun CellRangeStrip(min: Double, max: Double, color: Color) {
    val (lo, hi) = TechPanelVisuals.cellScale(min, max)
    val span = hi - lo
    val start = ((min - lo) / span).coerceIn(0.0, 1.0).toFloat()
    val end = ((max - lo) / span).coerceIn(0.0, 1.0).toFloat()
    Column(modifier = Modifier.padding(top = 6.dp)) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .background(CardSurfaceElevated, RoundedCornerShape(4.dp))
        ) {
            val track = maxWidth
            Box(
                modifier = Modifier
                    .offset(x = track * start)
                    .width((track * (end - start)).coerceAtLeast(2.dp))
                    .height(8.dp)
                    .background(color, RoundedCornerShape(4.dp))
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(stringResource(R.string.tech_value_cell_scale, lo), color = TextMuted, fontSize = 10.sp)
            Text(stringResource(R.string.tech_value_cell_scale, hi), color = TextMuted, fontSize = 10.sp)
        }
    }
}

/** Front and rear temperature bars side by side under their row. */
@Composable
private fun TempBarPair(front: Int?, rear: Int?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(modifier = Modifier.weight(1f)) { TempBar(front) }
        Box(modifier = Modifier.weight(1f)) { TempBar(rear) }
    }
}

/**
 * Temperature against the 0-150°C scale, with the warm and hot zones shaded on the track so a
 * bar reaching into them reads as a warning without a legend. Empty track when unknown.
 */
@Composable
private fun TempBar(temp: Int?) {
    val fraction = ((temp ?: 0) / TechPanelVisuals.TEMP_FULL_SCALE_C).coerceIn(0.0, 1.0).toFloat()
    val color = when (temp?.let { TechPanelVisuals.tempZone(it) }) {
        TechPanelVisuals.TempZone.HOT -> SocRed
        TechPanelVisuals.TempZone.WARM -> SocYellow
        else -> AccentGreen
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(CardSurfaceElevated, RoundedCornerShape(3.dp))
    ) {
        Row(modifier = Modifier.matchParentSize()) {
            Box(modifier = Modifier.weight(0.6f))
            Box(modifier = Modifier.weight(0.2f).fillMaxHeight().background(SocYellow.copy(alpha = 0.18f)))
            Box(modifier = Modifier.weight(0.2f).fillMaxHeight().background(SocRed.copy(alpha = 0.22f)))
        }
        if (temp != null && fraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(6.dp)
                    .background(color, RoundedCornerShape(3.dp))
            )
        }
    }
}

/** Marks where the warm and hot zones start on the temperature bars above. */
@Composable
private fun TempScaleCaption() {
    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
        Box(modifier = Modifier.weight(0.6f)) { TempScaleTick("0°") }
        Box(modifier = Modifier.weight(0.2f)) { TempScaleTick("90°") }
        Box(modifier = Modifier.weight(0.2f)) { TempScaleTick("120°") }
        TempScaleTick("150°")
    }
}

@Composable
private fun TempScaleTick(text: String) {
    Text(text, color = TextMuted, fontSize = 10.sp)
}

/** Top view of the car: pressure and temperature sit where the wheel is. */
@Composable
private fun TyreTopView(state: TechPanelUiState) {
    val deviates = TechPanelVisuals.tyreDeviates(
        listOf(state.tirePressFL, state.tirePressFR, state.tirePressRL, state.tirePressRR)
    )
    Box(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.size(width = 200.dp, height = 120.dp)) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(width = 80.dp, height = 112.dp)
                    .background(
                        CardSurfaceElevated.copy(alpha = 0.6f),
                        RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 20.dp, bottomEnd = 20.dp)
                    )
                    .border(
                        1.dp,
                        CardBorder,
                        RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 20.dp, bottomEnd = 20.dp)
                    )
            )
            TyreTile(
                state.tirePressFL, state.tyreTempFL, deviates[0],
                Modifier.align(Alignment.TopStart).padding(top = 6.dp),
            )
            TyreTile(
                state.tirePressFR, state.tyreTempFR, deviates[1],
                Modifier.align(Alignment.TopEnd).padding(top = 6.dp),
            )
            TyreTile(
                state.tirePressRL, state.tyreTempRL, deviates[2],
                Modifier.align(Alignment.BottomStart).padding(bottom = 2.dp),
            )
            TyreTile(
                state.tirePressRR, state.tyreTempRR, deviates[3],
                Modifier.align(Alignment.BottomEnd).padding(bottom = 2.dp),
            )
        }
    }
}

@Composable
private fun TyreTile(pressureKpa: Int?, tempC: Int?, deviates: Boolean, modifier: Modifier = Modifier) {
    val bar = pressureKpa?.let { it / 100.0 }
    Row(
        modifier = modifier
            .size(width = 70.dp, height = 44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(CardSurfaceElevated)
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(if (deviates) SocYellow else AccentGreen)
        )
        Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
            Text(
                bar?.let { stringResource(R.string.tech_value_tyre_pressure, it) } ?: DASH,
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                tempC?.let { stringResource(R.string.tech_value_deg, it) } ?: DASH,
                color = TextSecondary,
                fontSize = 11.sp,
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
