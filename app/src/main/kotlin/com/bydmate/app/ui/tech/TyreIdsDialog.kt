@file:Suppress("TooManyFunctions") // the tyre window: car, sets table, plan, confirmations, result and their small parts

package com.bydmate.app.ui.tech

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import com.bydmate.app.R
import com.bydmate.app.data.tpms.TpmsError
import com.bydmate.app.data.tpms.TpmsPhase
import com.bydmate.app.data.tpms.TpmsRepository
import com.bydmate.app.data.tpms.TyreBattery
import com.bydmate.app.data.tpms.TyrePlan
import com.bydmate.app.data.tpms.TyrePlanError
import com.bydmate.app.data.tpms.TyrePlans
import com.bydmate.app.data.tpms.TyreSetName
import com.bydmate.app.data.tpms.TyreWheelStatus
import com.bydmate.app.data.tpms.TyreWheelStatuses
import com.bydmate.app.data.tpms.tyreHex
import com.bydmate.app.helper.dtc.TpmsStatus
import com.bydmate.app.helper.dtc.TpmsVerdict
import com.bydmate.app.helper.dtc.TpmsWire
import com.bydmate.app.ui.theme.AccentBlue
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.CardBorder
import com.bydmate.app.ui.theme.CardSurface
import com.bydmate.app.ui.theme.CardSurfaceElevated
import com.bydmate.app.ui.theme.NavyDark
import com.bydmate.app.ui.theme.NavyMid
import com.bydmate.app.ui.theme.ScaledDialogContent
import com.bydmate.app.ui.theme.SocRed
import com.bydmate.app.ui.theme.SocYellow
import com.bydmate.app.ui.theme.TextMuted
import com.bydmate.app.ui.theme.TextPrimary
import com.bydmate.app.ui.theme.TextSecondary
import kotlin.math.roundToInt

/** The window's screens, as the states of mock `2026-11-20-tyre-ids-firmware-ui.html`. */
private enum class TyreStep { VIEW, PLAN, ONE, CONFIRM1, CONFIRM2, RESULT }

/**
 * What the confirmations lead to: the target per wheel (null = leave), the set it comes from or the
 * single [wheel], or [undo] = «Вернуть как было» of the last write.
 */
private data class TyreDraft(val target: List<String?>, val set: TyreSetName?, val wheel: Int?, val undo: Boolean = false)

/** What the window asks of the repository; [onStep] logs a step it does not see. */
internal class TyreIdsActions(
    val onTake: (TyreSetName) -> Unit,
    val onSave: (TyreSetName, Int, String?) -> Unit,
    /** target, set, wheel, and the «было» IDs the confirmation showed. */
    val onWrite: (List<String?>, TyreSetName?, Int?, List<Int?>) -> Unit,
    val onUndo: (List<Int?>) -> Unit,
    val onStep: (String) -> Unit,
)

/** The IDs now in the block and the one write this window is about, worked out once per frame. */
private class TyreCtx(
    val state: TechPanelUiState,
    val actions: TyreIdsActions,
    val current: List<Int?>,
    val readBack: List<Int?>,
    val before: List<Int?>,
) {
    val block: TyreWriteBlock? get() = state.tyreWriteBlock

    /** «Было» of [draft]: the last read, or for an undo what the last write read back. */
    fun from(draft: TyreDraft): List<Int?> = if (draft.undo) readBack else current

    fun plan(draft: TyreDraft): TyrePlan = TyrePlans.build(from(draft), draft.target)
}

/**
 * «Датчики колёс» (CONTEXT.md «Номера датчиков колёс»): the car on the left with pressure, temperature,
 * sensor ID and battery per wheel, the winter and summer sets on the right. Writing goes through two
 * confirmations and needs a Leopard 3 shaped read on P; pressure and temperature are always live.
 */
@Composable
internal fun TyreIdsDialog(state: TechPanelUiState, actions: TyreIdsActions, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val tpms = state.tpms
    var step by remember { mutableStateOf(TyreStep.VIEW) }
    var draft by remember { mutableStateOf<TyreDraft?>(null) }
    var agreed by remember { mutableStateOf(false) }
    // The last write when «Прописать» was tapped: a different one is the result of that tap.
    var writeFrom by remember { mutableStateOf<TpmsStatus?>(null) }
    val last = tpms.lastWrite
    val ctx = TyreCtx(
        state, actions, TpmsRepository.idsOf(tpms.lastRead),
        readBack = last?.let { st -> TpmsWire.DIDS.map { did -> TpmsWire.parseSlot(did, slotOf(st, did)?.afterUds)?.id } }
            ?: List(TpmsWire.DIDS.size) { null },
        before = TpmsRepository.idsOf(last),
    )
    val go: (TyreStep, String) -> Unit = { next, why ->
        actions.onStep("step $step -> $next $why")
        step = next
    }
    val result = last?.takeIf { step == TyreStep.RESULT && it !== writeFrom && tpms.phase == TpmsPhase.IDLE }
    // Until the repository's WRITING reaches this frame there is neither a result nor an error: still writing.
    val writing = step == TyreStep.RESULT && result == null && (tpms.phase == TpmsPhase.WRITING || tpms.error == null)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ScaledDialogContent {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = CardSurface),
                border = BorderStroke(1.dp, CardBorder),
                modifier = Modifier.widthIn(max = 1200.dp).fillMaxWidth().padding(24.dp),
            ) {
                Column(modifier = Modifier.padding(start = 22.dp, end = 12.dp, top = 8.dp, bottom = 16.dp)) {
                    TitleRow(
                        title = titleText(step, draft, result, context),
                        sub = when {
                            tpms.phase == TpmsPhase.READING -> stringResource(R.string.tyre_reading)
                            tpms.lastReadAt != null -> stringResource(R.string.tyre_read_at, dtcWhen(context, tpms.lastReadAt))
                            else -> stringResource(R.string.tyre_never_read)
                        },
                        onDismiss = onDismiss,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(22.dp),
                        modifier = Modifier.fillMaxWidth().height(BODY_HEIGHT).padding(end = 10.dp),
                    ) {
                        Column(modifier = Modifier.weight(LEFT_WEIGHT).fillMaxHeight().verticalScroll(rememberScrollState())) {
                            LeftSide(ctx, step, draft, result,
                                onSwap = { a, b ->
                                    draft?.let { d ->
                                        val t = d.target.toMutableList()
                                        t[a] = t[b].also { t[b] = t[a] }
                                        actions.onStep("drag swap ${WHEEL_KEYS[a]}<->${WHEEL_KEYS[b]} target=${t.joinToString(",") { it ?: "-" }}")
                                        draft = d.copy(target = t)
                                    }
                                },
                                onHold = { wheel ->
                                    draft = TyreDraft(List(TpmsWire.DIDS.size) { null }, null, wheel)
                                    go(TyreStep.ONE, "hold wheel=${WHEEL_KEYS[wheel]}")
                                },
                            )
                        }
                        Column(modifier = Modifier.weight(1f - LEFT_WEIGHT).fillMaxHeight()) {
                            RightSide(
                                ctx, step, draft, agreed, result, writing,
                                RightCallbacks(
                                    onDraft = { draft = it },
                                    onGo = go,
                                    onAgree = {
                                        agreed = it
                                        actions.onStep("confirm1 agreed=$it")
                                    },
                                    onConfirmed = { d ->
                                        writeFrom = last
                                        go(TyreStep.RESULT, "confirm2 write undo=${d.undo} plan=${planText(ctx.from(d), d.target)}")
                                        if (d.undo) actions.onUndo(ctx.from(d)) else actions.onWrite(d.target, d.set, d.wheel, ctx.from(d))
                                    },
                                    onUndo = {
                                        draft = TyreDraft(ctx.before.map { it?.let(::tyreHex) }, null, null, undo = true)
                                        go(TyreStep.CONFIRM1, "undo tap")
                                    },
                                    onResetAgree = { agreed = false },
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TitleRow(title: String, sub: String, onDismiss: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
        Text(title, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(sub, color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(start = 14.dp).weight(1f))
        IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.trip_detail_close), tint = TextSecondary)
        }
    }
}

private fun titleText(step: TyreStep, draft: TyreDraft?, result: TpmsStatus?, context: android.content.Context): String = when {
    step == TyreStep.VIEW || draft == null -> context.getString(R.string.tyre_title)
    step == TyreStep.RESULT && result != null -> context.getString(resultTitle(draft, result))
    draft.undo -> context.getString(R.string.tyre_undo)
    draft.set == TyreSetName.WINTER -> context.getString(R.string.tyre_title_plan_winter)
    draft.set == TyreSetName.SUMMER -> context.getString(R.string.tyre_title_plan_summer)
    else -> context.getString(R.string.tyre_title_one, wheelName(context, draft.wheel ?: 0).lowercase())
}

private fun resultTitle(draft: TyreDraft, result: TpmsStatus): Int = when {
    !writeComplete(result) -> R.string.tyre_title_not_done
    draft.undo -> R.string.tyre_title_undone
    draft.set == TyreSetName.WINTER -> R.string.tyre_title_done_winter
    draft.set == TyreSetName.SUMMER -> R.string.tyre_title_done_summer
    else -> R.string.tyre_title_done_one
}

// ---- left: the car ----

@Composable
private fun LeftSide(
    ctx: TyreCtx,
    step: TyreStep,
    draft: TyreDraft?,
    result: TpmsStatus?,
    onSwap: (Int, Int) -> Unit,
    onHold: (Int) -> Unit,
) {
    val header = when {
        step == TyreStep.VIEW || draft == null -> R.string.tyre_hd_now
        step == TyreStep.RESULT && result != null -> R.string.tyre_hd_after
        draft.undo -> R.string.tyre_hd_undo
        draft.set == TyreSetName.WINTER -> R.string.tyre_hd_plan_winter
        draft.set == TyreSetName.SUMMER -> R.string.tyre_hd_plan_summer
        else -> R.string.tyre_hd_one
    }
    TyreHeader(stringResource(header))
    when {
        step == TyreStep.VIEW || draft == null -> ViewCar(ctx, onHold)
        step == TyreStep.RESULT && result != null -> ResultCar(result)
        // Result requested but nothing came back yet (or the start was refused): what was planned.
        else -> PlanCar(ctx, draft, draggable = step == TyreStep.PLAN, single = step == TyreStep.ONE, onSwap = onSwap)
    }
    when {
        step == TyreStep.VIEW && ctx.block == null && ctx.state.tpms.lastRead != null ->
            HintBanner(stringResource(R.string.tyre_hint_hold))
        step == TyreStep.PLAN -> HintBanner(stringResource(R.string.tyre_hint_drag))
        step == TyreStep.RESULT && result != null ->
            Text(stringResource(R.string.tyre_after_hint), color = TextMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        else -> Unit
    }
}

/** «Сейчас в машине»: live pressure and temperature, the ID and the sensor state from the last read. */
@Composable
private fun ViewCar(ctx: TyreCtx, onHold: (Int) -> Unit) {
    val s = ctx.state
    val pressures = listOf(s.tirePressFL, s.tirePressFR, s.tirePressRL, s.tirePressRR)
    val temps = listOf(s.tyreTempFL, s.tyreTempFR, s.tyreTempRL, s.tyreTempRR)
    val deviates = TechPanelVisuals.tyreDeviates(pressures)
    val wheels = TyreWheelStatuses.of(TpmsRepository.slots(s.tpms.lastRead), s.tpms.lastRead?.dtcBefore)
    val canHold = ctx.block == null
    var pressed by remember { mutableStateOf<Int?>(null) }
    val latestHold by rememberUpdatedState(onHold)
    TyreCar(deviates.map { if (it) WheelTint.WARN else WheelTint.NORMAL }) { i ->
        val w = wheels[i]
        val warn = deviates[i] || w.heard == false || w.battery == TyreBattery.LOW
        WheelTile(
            bar = if (warn) SocYellow else AccentGreen,
            outline = if (pressed == i) AccentBlue else null,
            modifier = if (!canHold) Modifier else Modifier.pointerInput(i) {
                // Hold 0,6 s: a plain tap does nothing, so the write screen never opens by accident.
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = i
                    var released = false
                    withTimeoutOrNull(HOLD_MS) {
                        waitForUpOrCancellation()
                        released = true
                    }
                    pressed = null
                    if (!released) {
                        latestHold(i)
                        waitForUpOrCancellation()
                    }
                }
            },
        ) {
            Text(
                pressures[i]?.let { stringResource(R.string.tech_value_tyre_pressure, it / KPA_PER_BAR) } ?: DASH,
                color = TextPrimary, fontSize = 19.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace, maxLines = 1,
            )
            Text(
                temps[i]?.let { stringResource(R.string.tech_value_deg, it) } ?: DASH,
                color = TextSecondary, fontSize = 14.sp, lineHeight = 17.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
            )
            IdText(w.id?.let(::tyreHex) ?: DASH)
            SensorState(w)
        }
    }
}

/** The target of [draft] on the car: blue = will be written, the rest stays; [draggable] swaps tiles. */
@Composable
private fun PlanCar(ctx: TyreCtx, draft: TyreDraft, draggable: Boolean, single: Boolean, onSwap: (Int, Int) -> Unit) {
    val context = LocalContext.current
    val from = ctx.from(draft)
    val shown = draft.target.mapIndexed { i, t -> TyrePlans.normalize(t) ?: from[i]?.let(::tyreHex) }
    val changed = shown.mapIndexed { i, id -> id != null && id != from[i]?.let(::tyreHex) }
    val bounds = remember { mutableStateMapOf<Int, Rect>() }
    var dragging by remember { mutableStateOf<Int?>(null) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var over by remember { mutableStateOf<Int?>(null) }
    val latestSwap by rememberUpdatedState(onSwap)
    TyreCar(changed.map { if (it) WheelTint.CHANGE else WheelTint.NORMAL }) { i ->
        val lifted = dragging == i
        val status = when {
            single && i != draft.wheel -> R.string.tyre_status_untouched
            changed[i] || (single && i == draft.wheel) -> R.string.tyre_status_will_write
            else -> R.string.tyre_status_in_block
        }
        val blue = status == R.string.tyre_status_will_write
        WheelTile(
            bar = if (blue) AccentBlue else AccentGreen,
            outline = when {
                over == i -> AccentGreen
                blue -> AccentBlue
                else -> null
            },
            modifier = Modifier
                .onGloballyPositioned { bounds[i] = it.boundsInParent() }
                .zIndex(if (lifted) 1f else 0f)
                .graphicsLayer {
                    if (lifted) {
                        translationX = offset.x
                        translationY = offset.y
                        alpha = LIFT_ALPHA
                        shadowElevation = 12.dp.toPx()
                    }
                }
                .then(
                    if (!draggable) Modifier else Modifier.pointerInput(i) {
                        var start: Rect? = null
                        detectDragGestures(
                            onDragStart = {
                                dragging = i
                                offset = Offset.Zero
                                start = bounds[i]
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                offset += amount
                                val centre = start?.center?.plus(offset)
                                over = centre?.let { c -> bounds.entries.firstOrNull { (j, r) -> j != i && r.contains(c) }?.key }
                            },
                            onDragEnd = {
                                over?.let { latestSwap(i, it) }
                                dragging = null
                                over = null
                                offset = Offset.Zero
                            },
                            onDragCancel = {
                                dragging = null
                                over = null
                                offset = Offset.Zero
                            },
                        )
                    },
                ),
        ) {
            Text(wheelName(context, i), color = TextSecondary, fontSize = 14.sp, lineHeight = 17.sp, maxLines = 1)
            IdText(shown[i] ?: DASH)
            Text(stringResource(status), color = if (blue) AccentBlue else TextMuted, fontSize = 12.sp, lineHeight = 15.sp)
        }
    }
}

/** «Прочитано из блока после записи»: the read-back per wheel and what the write got there. */
@Composable
private fun ResultCar(result: TpmsStatus) {
    val context = LocalContext.current
    TyreCar(List(TpmsWire.DIDS.size) { WheelTint.NORMAL }) { i ->
        val did = TpmsWire.DIDS[i]
        val slot = slotOf(result, did)
        val after = TpmsWire.parseSlot(did, slot?.afterUds)
        val (text, color) = when (slot?.verdict) {
            null -> R.string.tyre_status_not_changed to TextMuted
            TpmsVerdict.WRITTEN ->
                if (after?.heard == true) R.string.tyre_status_written to AccentGreen else R.string.tyre_status_written_wait to SocYellow
            TpmsVerdict.ALREADY -> R.string.tyre_status_in_block to TextMuted
            else -> R.string.tyre_status_not_written to SocYellow
        }
        WheelTile(bar = if (color == TextMuted) AccentGreen else color, outline = null) {
            Text(wheelName(context, i), color = TextSecondary, fontSize = 14.sp, lineHeight = 17.sp, maxLines = 1)
            IdText(after?.id?.let(::tyreHex) ?: DASH)
            Text(stringResource(text), color = color, fontSize = 12.sp, lineHeight = 15.sp)
        }
    }
}

/**
 * The big car with a tile at each wheel, as the card's TyreTopView: tiles sit at the axle lines and
 * size to their text up to half the width; tall tiles (font 1,3) push the car down or the rear
 * pair lower instead of overlapping.
 */
@Composable
private fun TyreCar(tints: List<WheelTint>, tile: @Composable (Int) -> Unit) {
    Layout(
        content = {
            CarTopImage(tints, BIG_CAR_HEIGHT, leadReach = TILE_SPACE_UNITS)
            repeat(TpmsWire.DIDS.size) { tile(it) }
        },
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) { measurables, constraints ->
        val car = measurables[0].measure(Constraints())
        val centre = constraints.maxWidth / 2
        val gap = car.width / 2 + TILE_SPACE.roundToPx()
        val tileMax = (centre - gap).coerceAtLeast(0)
        val tiles = measurables.drop(1).map { it.measure(Constraints(maxWidth = tileMax)) }
        val k = car.height / CAR_VIEWPORT_H
        val frontY = FRONT_LEAD_Y * k
        val top = maxOf(0f, tiles[0].height / 2f - frontY, tiles[1].height / 2f - frontY)
        val frontBottom = top + frontY + maxOf(tiles[0].height, tiles[1].height) / 2f
        val rearHalf = maxOf(tiles[2].height, tiles[3].height) / 2f
        val rearY = maxOf(REAR_LEAD_Y * k + top, frontBottom + TILE_SPACE.toPx() + rearHalf)
        val height = maxOf(car.height + top, rearY + rearHalf)
        layout(constraints.maxWidth, height.roundToInt()) {
            fun y(axle: Float, h: Int) = (axle - h / 2f).roundToInt()
            car.place(centre - car.width / 2, top.roundToInt())
            tiles[0].place(centre - gap - tiles[0].width, y(frontY + top, tiles[0].height))
            tiles[1].place(centre + gap, y(frontY + top, tiles[1].height))
            tiles[2].place(centre - gap - tiles[2].width, y(rearY, tiles[2].height))
            tiles[3].place(centre + gap, y(rearY, tiles[3].height))
        }
    }
}

@Composable
private fun WheelTile(bar: Color, outline: Color?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = modifier
            .widthIn(min = TILE_MIN_WIDTH)
            .height(IntrinsicSize.Min)
            .clip(shape)
            .background(CardSurfaceElevated)
            .then(if (outline != null) Modifier.border(2.dp, outline, shape) else Modifier),
    ) {
        Box(modifier = Modifier.width(5.dp).fillMaxHeight().background(bar))
        Column(modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 7.dp), content = content)
    }
}

@Composable
private fun IdText(id: String) {
    Text(
        id, color = TextPrimary, fontSize = 15.sp, lineHeight = 19.sp, fontFamily = FontFamily.Monospace,
        letterSpacing = 0.5.sp, maxLines = 1, modifier = Modifier.padding(top = 3.dp),
    )
}

/** «нет сигнала» when the block does not hear the sensor, else the battery: green OK, yellow when low. */
@Composable
private fun SensorState(w: TyreWheelStatus) {
    when {
        w.id == null -> Unit
        w.heard == false -> Text(stringResource(R.string.tyre_no_signal), color = SocYellow, fontSize = 12.sp, lineHeight = 15.sp)
        w.battery == TyreBattery.UNKNOWN -> Unit
        else -> {
            val low = w.battery == TyreBattery.LOW
            val color = if (low) SocYellow else AccentGreen
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 1.dp)) {
                BatteryIcon(color)
                Text(
                    stringResource(if (low) R.string.tyre_battery_low else R.string.tyre_battery_ok),
                    color = color, fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 5.dp),
                )
            }
        }
    }
}

/** The mock's battery: an outline with a cap and a full charge bar, in [color]. */
@Composable
private fun BatteryIcon(color: Color) {
    Canvas(modifier = Modifier.size(width = 20.dp, height = 11.dp)) {
        val u = size.width / BATTERY_W
        drawRoundRect(
            color, Offset(0.75f * u, 0.75f * u), Size(16f * u, 9.5f * u), CornerRadius(2f * u), style = Stroke(1.5f * u),
        )
        drawRoundRect(color, Offset(17.5f * u, 3.5f * u), Size(2f * u, 4f * u), CornerRadius(0.5f * u))
        drawRoundRect(color, Offset(2.75f * u, 2.75f * u), Size(12f * u, 5.5f * u), CornerRadius(1f * u))
    }
}

@Composable
private fun HintBanner(text: String) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(top = 6.dp)
            .fillMaxWidth()
            .background(AccentBlue.copy(alpha = HINT_ALPHA), shape)
            .border(1.dp, AccentBlue, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Icon(Icons.Outlined.TouchApp, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(24.dp))
        Text(text, color = AccentBlue, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp))
    }
}

// ---- right: sets, plan, confirmations, result ----

private class RightCallbacks(
    val onDraft: (TyreDraft) -> Unit,
    val onGo: (TyreStep, String) -> Unit,
    val onAgree: (Boolean) -> Unit,
    val onConfirmed: (TyreDraft) -> Unit,
    val onUndo: () -> Unit,
    val onResetAgree: () -> Unit,
)

@Composable
private fun ColumnScope.RightSide(
    ctx: TyreCtx,
    step: TyreStep,
    draft: TyreDraft?,
    agreed: Boolean,
    result: TpmsStatus?,
    writing: Boolean,
    cb: RightCallbacks,
) {
    if (step == TyreStep.VIEW || draft == null) {
        SetsView(ctx, cb)
        return
    }
    val plan = ctx.plan(draft)
    val back = if (draft.undo) TyreStep.RESULT else if (draft.wheel != null) TyreStep.ONE else TyreStep.PLAN
    when (step) {
        TyreStep.PLAN -> {
            Scroll {
                TyreHeader(stringResource(R.string.tyre_hd_changes))
                DiffBox(ctx.from(draft), draft.target, sameText = R.string.tyre_diff_in_block)
                Text(
                    stringResource(R.string.tyre_plan_count, (plan as? TyrePlan.Ok)?.entries?.size ?: 0),
                    color = TextMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp),
                )
                Text(stringResource(R.string.tyre_plan_note), color = TextMuted, fontSize = 13.sp)
                PlanProblem(plan)
                BlockLine(ctx)
            }
            Buttons(
                TyreButtonSpec(stringResource(R.string.settings_cancel_button)) { cb.onGo(TyreStep.VIEW, "plan cancel") },
                TyreButtonSpec(stringResource(R.string.tyre_next), TyreButtonKind.PRIMARY, plan is TyrePlan.Ok && ctx.block == null) {
                    cb.onResetAgree()
                    cb.onGo(TyreStep.CONFIRM1, "plan next")
                },
            )
        }
        TyreStep.ONE -> OneWheel(ctx, draft, plan, cb)
        TyreStep.CONFIRM1 -> {
            Scroll {
                TyreHeader(stringResource(R.string.tyre_hd_experimental))
                Column(modifier = Modifier.fillMaxWidth().tyreBox(SocYellow)) {
                    Text(stringResource(R.string.tyre_exp_text), color = TextPrimary, fontSize = 15.sp, lineHeight = 22.sp)
                    Text(stringResource(R.string.tyre_exp_log), color = TextMuted, fontSize = 15.sp, lineHeight = 22.sp)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { cb.onAgree(!agreed) },
                ) {
                    Checkbox(
                        checked = agreed,
                        onCheckedChange = null,
                        colors = CheckboxDefaults.colors(checkedColor = AccentGreen, uncheckedColor = TextMuted, checkmarkColor = NavyDark),
                    )
                    Text(
                        stringResource(R.string.tyre_exp_agree), color = TextPrimary, fontSize = 15.sp, lineHeight = 21.sp,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
            Buttons(
                TyreButtonSpec(stringResource(R.string.tyre_back)) { cb.onGo(back, "confirm1 back") },
                TyreButtonSpec(stringResource(R.string.tyre_next), TyreButtonKind.PRIMARY, agreed) {
                    cb.onGo(TyreStep.CONFIRM2, "confirm1 next")
                },
            )
        }
        TyreStep.CONFIRM2 -> {
            Scroll {
                TyreHeader(stringResource(R.string.tyre_hd_check))
                DiffBox(ctx.from(draft), draft.target, sameText = R.string.tyre_status_unchanged)
                PlanProblem(plan)
                BlockLine(ctx)
            }
            Buttons(
                TyreButtonSpec(stringResource(R.string.tyre_back)) {
                    cb.onGo(TyreStep.CONFIRM1, "confirm2 back")
                },
                TyreButtonSpec(stringResource(R.string.tyre_write), TyreButtonKind.PRIMARY, plan is TyrePlan.Ok && ctx.block == null) {
                    cb.onConfirmed(draft)
                },
            )
        }
        TyreStep.RESULT -> ResultSide(ctx, result, writing, cb)
        TyreStep.VIEW -> Unit
    }
}

/** «Комплекты»: the table, «Взять с машины», «Сохранить», then «Прописать» or the reason it is off. */
@Composable
private fun ColumnScope.SetsView(ctx: TyreCtx, cb: RightCallbacks) {
    val sets = ctx.state.tpms.sets
    var winter by remember(sets.winter) { mutableStateOf(sets.winter.ids.map { it.orEmpty() }) }
    var summer by remember(sets.summer) { mutableStateOf(sets.summer.ids.map { it.orEmpty() }) }
    val drafts = mapOf(TyreSetName.WINTER to winter, TyreSetName.SUMMER to summer)
    val bad = drafts.mapValues { (_, cells) -> cells.map { c -> TyrePlans.normalize(c)?.let { TyrePlans.cellError(it) } != null } }
    val dirty = drafts.any { (name, cells) -> cells.indices.any { TyrePlans.normalize(cells[it]) != sets.of(name).ids[it] } }
    val anyBad = bad.values.any { it.any { b -> b } }
    val hasRead = TpmsRepository.idsOf(ctx.state.tpms.lastRead).none { it == null }
    Scroll {
        TyreHeader(stringResource(R.string.tyre_hd_sets))
        Row(modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
            Text(stringResource(R.string.tyre_col_place).uppercase(), color = TextMuted, fontSize = 12.sp, modifier = Modifier.width(PLACE_WIDTH))
            Text(stringResource(R.string.tyre_col_winter).uppercase(), color = TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.tyre_col_summer).uppercase(), color = TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(start = 10.dp))
        }
        for (i in TpmsWire.DIDS.indices) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp)) {
                Text(placeShort(i), color = TextSecondary, fontSize = 15.sp, modifier = Modifier.width(PLACE_WIDTH))
                IdField(winter[i], bad.getValue(TyreSetName.WINTER)[i], Modifier.weight(1f)) { v ->
                    winter = winter.toMutableList().also { it[i] = v }
                }
                IdField(summer[i], bad.getValue(TyreSetName.SUMMER)[i], Modifier.weight(1f).padding(start = 10.dp)) { v ->
                    summer = summer.toMutableList().also { it[i] = v }
                }
            }
        }
        if (anyBad) Text(stringResource(R.string.tyre_bad_id), color = SocRed, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 10.dp)) {
            for (name in TyreSetName.entries) {
                TyreButton(
                    TyreButtonSpec(
                        stringResource(if (name == TyreSetName.WINTER) R.string.tyre_take_winter else R.string.tyre_take_summer),
                        enabled = hasRead,
                    ) { ctx.actions.onTake(name) },
                    Modifier.weight(1f),
                )
            }
            TyreButton(
                TyreButtonSpec(stringResource(R.string.tyre_save), TyreButtonKind.PRIMARY, dirty && !anyBad) {
                    ctx.actions.onStep("save tap")
                    drafts.forEach { (name, cells) ->
                        cells.indices.filter { TyrePlans.normalize(cells[it]) != sets.of(name).ids[it] }
                            .forEach { ctx.actions.onSave(name, it, cells[it]) }
                    }
                },
                Modifier.weight(1f),
            )
        }
        ctx.state.tpms.error?.let { tpmsErrorText(it) }?.let {
            Text(stringResource(it), color = SocYellow, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
        }
    }
    val block = ctx.block
    when (block) {
        null -> Buttons(
            TyreSetName.entries.map { name ->
                val cells = drafts.getValue(name)
                val usable = cells.any { TyrePlans.normalize(it) != null } && bad.getValue(name).none { it }
                TyreButtonSpec(
                    stringResource(if (name == TyreSetName.WINTER) R.string.tyre_write_winter else R.string.tyre_write_summer),
                    enabled = usable,
                ) {
                    val target = cells.mapIndexed { i, c -> TyrePlans.normalize(c) ?: ctx.current[i]?.let(::tyreHex) }
                    cb.onDraft(TyreDraft(target, name, null))
                    cb.onGo(TyreStep.PLAN, "write $name target=${target.joinToString(",") { it ?: "-" }}")
                }
            },
        )
        TyreWriteBlock.NOT_L3 -> Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp).tyreBox(SocYellow)) {
            Text(stringResource(R.string.tyre_not_l3_title), color = SocYellow, fontSize = 15.sp, lineHeight = 21.sp)
            Text(stringResource(R.string.tyre_not_l3_text), color = TextMuted, fontSize = 14.sp, lineHeight = 20.sp)
        }
        else -> Text(
            stringResource(blockText(block)), color = TextMuted, fontSize = 14.sp, lineHeight = 20.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** «Одно колесо»: the ID in the block now and the new one; then the same two confirmations. */
@Composable
private fun ColumnScope.OneWheel(ctx: TyreCtx, draft: TyreDraft, plan: TyrePlan, cb: RightCallbacks) {
    val context = LocalContext.current
    val wheel = draft.wheel ?: 0
    val input = draft.target[wheel].orEmpty()
    val problem = if (TyrePlans.normalize(input) == null) null else planErrorText(plan)
    Scroll {
        TyreHeader(wheelName(context, wheel).uppercase())
        Row(modifier = Modifier.fillMaxWidth().tyreBox(CardBorder)) {
            Text(stringResource(R.string.tyre_now_in_block), color = TextSecondary, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(ctx.current[wheel]?.let(::tyreHex) ?: DASH, color = TextMuted, fontSize = 16.sp, fontFamily = FontFamily.Monospace)
        }
        TyreHeader(stringResource(R.string.tyre_hd_new_id), Modifier.padding(top = 14.dp))
        IdField(input, problem != null && problem != R.string.tyre_plan_nothing, Modifier.fillMaxWidth()) { v ->
            cb.onDraft(draft.copy(target = draft.target.toMutableList().also { it[wheel] = v }))
        }
        Text(
            stringResource(problem ?: R.string.tyre_id_help),
            color = if (problem != null) SocRed else TextMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp),
        )
        BlockLine(ctx)
    }
    Buttons(
        TyreButtonSpec(stringResource(R.string.settings_cancel_button)) { cb.onGo(TyreStep.VIEW, "one cancel") },
        TyreButtonSpec(stringResource(R.string.tyre_next), TyreButtonKind.PRIMARY, plan is TyrePlan.Ok && ctx.block == null) {
            cb.onResetAgree()
            cb.onGo(TyreStep.CONFIRM1, "one next id=$input")
        },
    )
}

/** The write running, then its read-back verdict and «Вернуть как было». */
@Composable
private fun ColumnScope.ResultSide(ctx: TyreCtx, result: TpmsStatus?, writing: Boolean, cb: RightCallbacks) {
    val error = ctx.state.tpms.error
    Scroll {
        when {
            writing -> {
                TyreHeader(stringResource(R.string.tyre_hd_writing))
                Text(stringResource(R.string.tyre_writing), color = TextPrimary, fontSize = 15.sp)
                LinearProgressIndicator(color = AccentGreen, trackColor = CardBorder, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp))
            }
            result == null -> {
                // The start was refused before anything went to the car.
                TyreHeader(stringResource(R.string.tyre_hd_not_done))
                Text(stringResource(error?.let { tpmsErrorText(it) } ?: R.string.tyre_err_failed), color = SocYellow, fontSize = 15.sp)
            }
            writeComplete(result) -> {
                TyreHeader(stringResource(R.string.tyre_hd_done))
                Column(modifier = Modifier.fillMaxWidth().tyreBox(CardBorder)) {
                    Text(stringResource(R.string.tyre_done_ok), color = AccentGreen, fontSize = 15.sp, lineHeight = 22.sp)
                    Text(stringResource(R.string.tyre_done_drive), color = TextPrimary, fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 10.dp))
                }
            }
            else -> {
                TyreHeader(stringResource(R.string.tyre_hd_not_done))
                Column(modifier = Modifier.fillMaxWidth().tyreBox(SocYellow)) {
                    Text(stringResource(R.string.tyre_done_partial), color = SocYellow, fontSize = 15.sp, lineHeight = 22.sp)
                    error?.let { tpmsErrorText(it) }?.let { Text(stringResource(it), color = TextMuted, fontSize = 14.sp, lineHeight = 20.sp) }
                }
            }
        }
        if (result != null) BlockLine(ctx)
    }
    val changed = result != null && ctx.before.indices.any { ctx.before[it] != null && ctx.readBack[it] != null && ctx.before[it] != ctx.readBack[it] }
    Buttons(
        TyreButtonSpec(stringResource(R.string.tyre_undo), TyreButtonKind.WARN, changed && ctx.block == null) { cb.onUndo() },
        TyreButtonSpec(stringResource(R.string.tyre_close), TyreButtonKind.PRIMARY, ctx.state.tpms.phase != TpmsPhase.WRITING) {
            cb.onGo(TyreStep.VIEW, "result close complete=${result?.let(::writeComplete)}")
        },
    )
}

/** Per wheel «было → станет»; a wheel that keeps its ID shows [sameText]. */
@Composable
private fun DiffBox(from: List<Int?>, target: List<String?>, sameText: Int) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth().tyreBox(CardBorder)) {
        for (i in TpmsWire.DIDS.indices) {
            val old = from[i]?.let(::tyreHex)
            val new = TyrePlans.normalize(target[i]) ?: old
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                Text(wheelName(context, i), color = TextSecondary, fontSize = 15.sp, modifier = Modifier.weight(DIFF_NAME_WEIGHT))
                Row(modifier = Modifier.weight(1f - DIFF_NAME_WEIGHT)) {
                    if (new == old) {
                        val same = stringResource(sameText)
                        Text(
                            if (sameText == R.string.tyre_diff_in_block) "${old ?: DASH} $same" else same,
                            color = TextMuted, fontSize = 16.sp, fontFamily = FontFamily.Monospace,
                        )
                    } else {
                        Text(old ?: DASH, color = TextMuted, fontSize = 16.sp, fontFamily = FontFamily.Monospace)
                        Text(" → ", color = TextMuted, fontSize = 16.sp, fontFamily = FontFamily.Monospace)
                        Text(new ?: DASH, color = AccentBlue, fontSize = 16.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanProblem(plan: TyrePlan) {
    planErrorText(plan)?.let {
        Text(stringResource(it), color = SocYellow, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
    }
}

/** Why the write button is off right now (left the car's P, a scan started, …); nothing when it is on. */
@Composable
private fun BlockLine(ctx: TyreCtx) {
    val block = ctx.block ?: return
    Text(
        stringResource(if (block == TyreWriteBlock.NOT_L3) R.string.tyre_not_l3_title else blockText(block)),
        color = SocYellow, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun IdField(value: String, bad: Boolean, modifier: Modifier, onChange: (String) -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    BasicTextField(
        value = value,
        onValueChange = { onChange(it.uppercase().take(ID_INPUT_MAX)) },
        singleLine = true,
        textStyle = TextStyle(color = TextPrimary, fontSize = 17.sp, fontFamily = FontFamily.Monospace, letterSpacing = 0.5.sp),
        cursorBrush = SolidColor(AccentGreen),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Done,
        ),
        modifier = modifier,
        decorationBox = { inner ->
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .background(NavyMid, shape)
                    .border(1.dp, if (bad) SocRed else CardBorder, shape)
                    .padding(horizontal = 12.dp),
            ) { inner() }
        },
    )
}

@Composable
private fun TyreHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
        modifier = modifier.padding(vertical = 6.dp),
    )
}

@Composable
private fun ColumnScope.Scroll(content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), content = content)
}

private enum class TyreButtonKind { NEUTRAL, PRIMARY, WARN }

private class TyreButtonSpec(
    val text: String,
    val kind: TyreButtonKind = TyreButtonKind.NEUTRAL,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

@Composable
private fun Buttons(vararg buttons: TyreButtonSpec) = Buttons(buttons.asList())

@Composable
private fun Buttons(buttons: List<TyreButtonSpec>) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
        buttons.forEach { TyreButton(it, Modifier.weight(1f)) }
    }
}

/** Outlined, at least 52 dp high, up to two lines so no caption is cut at font 1,3. */
@Composable
private fun TyreButton(spec: TyreButtonSpec, modifier: Modifier) {
    val color = when (spec.kind) {
        TyreButtonKind.PRIMARY -> AccentGreen
        TyreButtonKind.WARN -> SocYellow
        TyreButtonKind.NEUTRAL -> TextPrimary
    }
    OutlinedButton(
        onClick = spec.onClick,
        enabled = spec.enabled,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            1.dp,
            if (spec.kind == TyreButtonKind.NEUTRAL) CardBorder else color.copy(alpha = if (spec.enabled) 1f else DISABLED_ALPHA),
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = CardSurfaceElevated,
            contentColor = color,
            disabledContainerColor = CardSurfaceElevated,
            disabledContentColor = color.copy(alpha = DISABLED_ALPHA),
        ),
        modifier = modifier.heightIn(min = 52.dp),
    ) {
        Text(spec.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2)
    }
}

private fun Modifier.tyreBox(border: Color): Modifier {
    val shape = RoundedCornerShape(12.dp)
    return background(NavyMid, shape).border(1.dp, border, shape).padding(horizontal = 16.dp, vertical = 12.dp)
}

// ---- texts and small helpers ----

private fun wheelName(context: android.content.Context, i: Int): String = context.getString(
    when (i) {
        0 -> R.string.tyre_wheel_fl
        1 -> R.string.tyre_wheel_fr
        2 -> R.string.tyre_wheel_rl
        else -> R.string.tyre_wheel_rr
    },
)

@Composable
private fun placeShort(i: Int): String = stringResource(
    when (i) {
        0 -> R.string.tyre_place_fl
        1 -> R.string.tyre_place_fr
        2 -> R.string.tyre_place_rl
        else -> R.string.tyre_place_rr
    },
)

private fun planErrorText(plan: TyrePlan): Int? = when (plan) {
    is TyrePlan.Ok -> null
    TyrePlan.NothingToWrite -> R.string.tyre_plan_nothing
    is TyrePlan.Invalid -> when (plan.error) {
        TyrePlanError.INVALID_HEX, TyrePlanError.INVALID_LENGTH -> R.string.tyre_bad_id
        TyrePlanError.INVALID_VALUE -> R.string.tyre_bad_value
        TyrePlanError.DUPLICATE -> R.string.tyre_duplicate
        TyrePlanError.NOT_READ -> R.string.tyre_why_no_read
    }
}

private fun tpmsErrorText(error: TpmsError): Int = when (error) {
    TpmsError.NOT_PARK -> R.string.tyre_why_only_p
    TpmsError.BUSY -> R.string.tyre_why_busy
    TpmsError.STATUS_LOST -> R.string.tyre_err_lost
    TpmsError.NOT_L3 -> R.string.tyre_not_l3_title
    else -> R.string.tyre_err_failed
}

private fun blockText(block: TyreWriteBlock): Int = when (block) {
    TyreWriteBlock.READING -> R.string.tyre_reading
    TyreWriteBlock.WRITING -> R.string.tyre_writing
    TyreWriteBlock.NO_READ -> R.string.tyre_why_no_read
    TyreWriteBlock.NOT_L3 -> R.string.tyre_not_l3_text
    TyreWriteBlock.NOT_PARK -> R.string.tyre_why_only_p
    TyreWriteBlock.BUSY -> R.string.tyre_why_busy
}

private fun slotOf(status: TpmsStatus, did: Int) = status.slots.firstOrNull { it.did == did }

/** The daemon leaves unplanned wheels without a verdict, so the planned ones are those with one. */
private fun writeComplete(status: TpmsStatus): Boolean =
    TpmsRepository.writeComplete(status, status.slots.filter { it.verdict != null }.map { it.did })

private fun planText(from: List<Int?>, target: List<String?>): String = TpmsWire.DIDS.indices.joinToString(" ") { i ->
    val old = from[i]?.let(::tyreHex) ?: "-"
    val new = TyrePlans.normalize(target[i]) ?: old
    "${WHEEL_KEYS[i]}=" + if (new == old) "same" else "$old>$new"
}

private val WHEEL_KEYS = listOf("FL", "FR", "RL", "RR")
private val BODY_HEIGHT = 460.dp
private const val LEFT_WEIGHT = 0.46f
/** The car of the window: 260 dp high, so one viewport unit is one dp. */
private val BIG_CAR_HEIGHT = 260.dp
/** Space between the car's side and a tile, and the same in viewport units for the leader lines. */
private val TILE_SPACE = 12.dp
private const val TILE_SPACE_UNITS = 12f
private val TILE_MIN_WIDTH = 132.dp
private val PLACE_WIDTH = 56.dp
private const val DIFF_NAME_WEIGHT = 0.42f
private const val HOLD_MS = 600L
private const val ID_INPUT_MAX = 12
private const val KPA_PER_BAR = 100.0
private const val LIFT_ALPHA = 0.85f
private const val HINT_ALPHA = 0.12f
private const val BATTERY_W = 20f
