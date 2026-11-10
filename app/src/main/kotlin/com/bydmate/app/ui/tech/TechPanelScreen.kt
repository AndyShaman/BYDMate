package com.bydmate.app.ui.tech

import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.background
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bydmate.app.R
import com.bydmate.app.ui.components.HelpIcon
import com.bydmate.app.ui.components.HintBlock
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

internal const val DASH = "—"

/**
 * «Техника» — the bottom bar tab with the live panel; the Dashboard battery card switches to it too.
 * Replaces the old battery-health dialog: same numbers plus the technical readings the
 * autoservice batch now carries. Cards without a single live value are not drawn.
 */
@Composable
fun TechPanelScreen(
    viewModel: TechPanelViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // PM2.5 is read only while this screen is on display: leaving the tab or the app stops it.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.pollPm25() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(NavyDark, NavyDeep)))
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Header(online = state.autoserviceOnline)

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
            onScan = viewModel::scanDtc,
            onOpenDtc = viewModel::openDtcDialog,
        )
    }

    if (state.dtcDialogOpen) {
        val context = LocalContext.current
        DtcScanDialog(
            state = state,
            onScan = viewModel::scanDtc,
            onSend = { viewModel.sendDtc(dtcLabels(context)) },
            onErase = viewModel::eraseDtc,
            onDismiss = viewModel::closeDtcDialog,
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
    onScan: () -> Unit,
    onOpenDtc: () -> Unit,
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
                        TechCardContent(card, state, onHint, onScan, onOpenDtc, cardModifier)
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
    onScan: () -> Unit,
    onOpenDtc: () -> Unit,
    modifier: Modifier,
) {
    when (card) {
        TechCard.BATTERY_NOW -> BatteryNowCard(state, onHint, modifier)
        TechCard.LIMITS -> LimitsAndCellsCard(state, onHint, modifier)
        TechCard.HISTORY -> HistoryCard(state, onHint, modifier)
        TechCard.MOTORS -> MotorsCard(state, onHint, modifier)
        TechCard.CAR_STATE -> CarStateCard(state, onHint, modifier)
        TechCard.FUEL -> FuelCard(state, modifier)
        TechCard.DIAGNOSTICS -> DiagnosticsCard(state, onHint, onScan, onOpenDtc, modifier)
    }
}

@Composable
private fun Header(online: Boolean?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(R.string.tech_title),
            color = AccentGreen,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
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
// Building blocks (visual style carried over from the battery-health dialog)
// ============================================================================

// internal: the «Расход и температура» screen is drawn out of the same card.
@Composable
internal fun TechCard(
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
internal fun SectionHeader(text: String) {
    Text(
        text,
        color = TextMuted,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp
    )
}

@Composable
internal fun TechRow(
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
internal fun PairRow(
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
internal fun CellRangeStrip(min: Double, max: Double, color: Color) {
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
internal fun TempBarPair(front: Int?, rear: Int?) {
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
internal fun TempScaleCaption() {
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

@Composable
internal fun Hint(openHint: String?, key: String, textRes: Int) {
    if (openHint == key) {
        Box(modifier = Modifier.padding(top = 6.dp)) { HintBlock(stringResource(textRes)) }
    }
}
