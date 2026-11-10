package com.bydmate.app.ui.trips

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bydmate.app.data.local.entity.TripPointEntity
import com.bydmate.app.ui.components.consumptionColor
import com.bydmate.app.R
import com.bydmate.app.ui.theme.*
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import android.util.Log
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.delay
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.Date

internal enum class ScrubMetric { SPEED, CONSUMPTION }

/** Cursor, range and playback of the trip window; plain remember, the dialog opens per trip. */
@Stable
internal class ScrubState {
    var cursor by mutableIntStateOf(0)
    var metric by mutableStateOf(ScrubMetric.SPEED)
    var rangeMode by mutableStateOf(false)
    var range by mutableStateOf<IntRange?>(null)
    var playing by mutableStateOf(false)
}

/** Everything the window derives from the points once per trip. */
internal class ScrubData(val points: List<TripPointEntity>) {
    val dist = TripScrub.cumulativeDistanceM(points)
    val hasPower = TripScrub.hasPower(points)
    val fractions = FloatArray(points.size) { TripScrub.fractionOfIndex(points, it) }
    val powerKw: Array<Double?> = Array(points.size) { TripScrub.smoothedPowerKw(points, it) }
    val speedColors = IntArray(points.size) { TripScrub.speedColor(points[it].speedKmh ?: 0.0) }
    val consumptionColors = IntArray(points.size) {
        TripScrub.consumptionColor(
            TripScrub.localConsumption(points, dist, it, TripScrub.TRACK_WINDOW_M, TripScrub.TRACK_MIN_KMH)
        )
    }
    val maxSpeedIndex = TripScrub.maxSpeedIndex(points)
    val maxRegenIndex = TripScrub.maxRegenIndex(points)
    val withPower = points.count { it.batteryPowerW != null }
    val withAltitude = points.count { it.altitude != null }
}

internal const val SCRUB_TAG = "TripDetail"
private const val PLAY_FRAME_MS = 40L
private const val PLAY_FRAMES = 300
private const val MIN_RANGE_POINTS = 10
private const val RANGE_WIDEN_POINTS = 30
/** Fewer points draw no chart, and the header shows no controls for it. */
internal const val CHART_MIN_POINTS = 4

/** Logs the opened track and runs «Play»: moves the cursor to the end, then stops. */
@Composable
internal fun ScrubPlayback(data: ScrubData, state: ScrubState) {
    val points = data.points
    LaunchedEffect(data) {
        Log.d(
            SCRUB_TAG,
            "open: points=${points.size} withPower=${data.withPower} withAltitude=${data.withAltitude} hasPower=${data.hasPower}"
        )
    }

    val playing = state.playing
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        val step = maxOf(1, points.size / PLAY_FRAMES)
        while (state.cursor < points.lastIndex) {
            delay(PLAY_FRAME_MS)
            state.cursor = minOf(points.lastIndex, state.cursor + step)
        }
        state.playing = false
    }
}

/** Map with the values at the cursor over it and the time chart below (needs 2+ points). */
@Composable
internal fun ColumnScope.TripScrubPane(data: ScrubData, state: ScrubState, tileSource: String) {
    // Bottom of the values panel over the map, px: the route is fitted under it.
    var panelBottomPx by remember { mutableIntStateOf(0) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .clip(RoundedCornerShape(8.dp))
    ) {
        TripRouteMap(
            data = data,
            state = state,
            tileSource = tileSource,
            topInsetPx = panelBottomPx,
            modifier = Modifier.fillMaxSize()
        )
        ScrubValuesPanel(
            data = data,
            state = state,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .onSizeChanged { panelBottomPx = it.height }
                .padding(8.dp)
        )
    }

    if (data.points.size >= CHART_MIN_POINTS) {
        ScrubChart(
            data = data,
            state = state,
            modifier = Modifier
                .fillMaxWidth()
                .height(112.dp)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScrubValuesPanel(data: ScrubData, state: ScrubState, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    FlowRow(
        modifier = modifier
            .background(NavyDark.copy(alpha = 0.88f), shape)
            .border(1.dp, CardBorder, shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        val range = state.range
        if (state.rangeMode && range != null) {
            RangeValues(data, range)
        } else {
            CursorValues(data, state.cursor.coerceIn(0, data.points.lastIndex))
        }
    }
}

@Composable
private fun CursorValues(data: ScrubData, i: Int) {
    val p = data.points[i]
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val dash = "—"
    ScrubValue(stringResource(R.string.trip_scrub_time), timeFormat.format(Date(p.timestamp)))
    ScrubValue(
        stringResource(R.string.trip_scrub_from_start),
        "%.1f".format(data.dist[i] / 1000.0),
        stringResource(R.string.trip_scrub_unit_km)
    )
    ScrubValue(
        stringResource(R.string.trip_scrub_speed),
        p.speedKmh?.let { Math.round(it).toString() } ?: dash,
        stringResource(R.string.trip_scrub_unit_kmh).takeIf { p.speedKmh != null }
    )
    val power = data.powerKw[i].takeIf { data.hasPower }
    if (power == null) {
        ScrubValue(stringResource(R.string.trip_scrub_power), dash)
    } else {
        val kw = Math.round(power)
        ScrubValue(
            stringResource(R.string.trip_scrub_power),
            if (kw < 0) "−${-kw}" else kw.toString(),
            stringResource(R.string.trip_scrub_unit_kw),
            if (kw < 0) AccentBlue else TextPrimary
        )
    }
    val consumption = if (data.hasPower) {
        TripScrub.localConsumption(data.points, data.dist, i, TripScrub.CURSOR_WINDOW_M, TripScrub.CURSOR_MIN_KMH)
    } else null
    when {
        consumption != null -> ScrubValue(
            stringResource(R.string.trip_scrub_consumption_here),
            if (consumption < 0) "−" + "%.1f".format(-consumption) else "%.1f".format(consumption),
            stringResource(R.string.trip_scrub_unit_per100),
            if (consumption < 0) AccentBlue else TextPrimary
        )
        data.hasPower && p.speedKmh != null && p.speedKmh < TripScrub.CURSOR_MIN_KMH -> ScrubValue(
            stringResource(R.string.trip_scrub_consumption_here), dash, stringResource(R.string.trip_scrub_slow)
        )
        else -> ScrubValue(stringResource(R.string.trip_scrub_consumption_here), dash)
    }
    p.altitude?.let {
        ScrubValue(
            stringResource(R.string.trip_scrub_altitude),
            Math.round(it).toString(),
            stringResource(R.string.trip_scrub_unit_m)
        )
    }
}

@Composable
private fun RangeValues(data: ScrubData, range: IntRange) {
    val s = remember(data, range) { TripScrub.rangeStats(data.points, data.dist, range.first, range.last) }
    val dash = "—"
    ScrubValue(
        stringResource(R.string.trip_scrub_range_distance),
        "%.1f".format(s.distanceKm),
        stringResource(R.string.trip_scrub_unit_km)
    )
    ScrubValue(
        stringResource(R.string.trip_scrub_time),
        maxOf(1L, Math.round(s.durationMs / 60_000.0)).toString(),
        stringResource(R.string.trip_scrub_unit_min)
    )
    ScrubValue(
        stringResource(R.string.trip_scrub_range_avg),
        s.avgSpeedKmh?.let { Math.round(it).toString() } ?: dash,
        stringResource(R.string.trip_scrub_unit_kmh).takeIf { s.avgSpeedKmh != null }
    )
    ScrubValue(
        stringResource(R.string.trip_scrub_range_energy),
        s.energyKwh?.let { "%.1f".format(it) } ?: dash,
        stringResource(R.string.trip_scrub_unit_kwh).takeIf { s.energyKwh != null }
    )
    ScrubValue(
        stringResource(R.string.trip_scrub_consumption),
        s.kwhPer100Km?.let { "%.1f".format(it) } ?: dash,
        stringResource(R.string.trip_scrub_unit_per100).takeIf { s.kwhPer100Km != null }
    )
    ScrubValue(
        stringResource(R.string.trip_scrub_range_share),
        Math.round(s.shareOfTrip * 100).toString(),
        stringResource(R.string.trip_scrub_unit_percent)
    )
}

/** Label above, a big monospace number and a small unit after it. */
@Composable
private fun ScrubValue(label: String, value: String, unit: String? = null, valueColor: Color = TextPrimary) {
    Column {
        Text(label, color = TextMuted, fontSize = 11.sp, maxLines = 1)
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = valueColor)) { append(value) }
                unit?.let {
                    withStyle(SpanStyle(fontSize = 13.sp, color = TextSecondary, fontWeight = FontWeight.Normal)) {
                        append(" $it")
                    }
                }
            },
            color = TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1
        )
    }
}

/** «Play», «Speed | Consumption» and «Range» in the header row. */
@Composable
internal fun ScrubControls(data: ScrubData, state: ScrubState) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val playing = state.playing
        ScrubButton(
            icon = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            label = stringResource(if (playing) R.string.trip_scrub_pause else R.string.trip_scrub_play),
            on = false
        ) {
            if (playing) {
                state.playing = false
            } else {
                if (state.cursor >= data.points.lastIndex) state.cursor = 0
                if (state.rangeMode) setRangeMode(state, false)
                state.playing = true
            }
        }
        MetricSwitch(data.hasPower, state)
        ScrubButton(
            icon = Icons.Filled.Straighten,
            label = stringResource(R.string.trip_scrub_range_toggle),
            on = state.rangeMode
        ) {
            state.playing = false
            setRangeMode(state, !state.rangeMode)
        }
    }
}

internal fun setRangeMode(state: ScrubState, on: Boolean) {
    state.rangeMode = on
    if (!on) state.range = null
    Log.d(SCRUB_TAG, "range mode=$on metric=${state.metric}")
}

@Composable
private fun ScrubButton(
    icon: ImageVector,
    label: String,
    on: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(NavyMid, shape)
            .border(1.dp, if (on) AccentGreen else CardBorder, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val color = if (on) AccentGreen else TextPrimary
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
        Text(label, color = color, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** «Скорость | Расход»: recolors the track and switches the chart. */
@Composable
private fun MetricSwitch(hasPower: Boolean, state: ScrubState, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(NavyMid)
    ) {
        listOf(
            ScrubMetric.SPEED to R.string.trip_scrub_speed,
            ScrubMetric.CONSUMPTION to R.string.trip_scrub_consumption,
        ).forEach { (metric, label) ->
            val enabled = metric == ScrubMetric.SPEED || hasPower
            val selected = state.metric == metric
            Box(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable(enabled = enabled && !selected) {
                        state.metric = metric
                        Log.d(SCRUB_TAG, "metric=$metric rangeMode=${state.rangeMode}")
                    }
                    .padding(3.dp)
                    .background(if (selected) NavIndicator else Color.Transparent, RoundedCornerShape(8.dp))
                    .widthIn(min = 120.dp)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(label),
                    color = when {
                        selected -> AccentGreen
                        enabled -> TextSecondary
                        else -> TextMuted
                    },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }
    }
}

/** The color ramp of the current metric with its end labels. */
@Suppress("SpreadOperator") // the ramp brush is built once per metric
@Composable
private fun ScrubLegend(metric: ScrubMetric, modifier: Modifier = Modifier) {
    val (stops, lo, hi) = when (metric) {
        ScrubMetric.SPEED -> Triple(TripScrub.SPEED_STOPS, 0.0, TripScrub.SPEED_SCALE_MAX_KMH)
        ScrubMetric.CONSUMPTION -> Triple(
            TripScrub.CONSUMPTION_STOPS,
            TripScrub.CONSUMPTION_STOPS.first().first,
            TripScrub.CONSUMPTION_STOPS.last().first
        )
    }
    val brush = remember(metric) {
        Brush.horizontalGradient(
            *stops.map { (v, c) -> ((v - lo) / (hi - lo)).toFloat() to Color(c) }.toTypedArray()
        )
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val textStyle = TextStyle(color = TextSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Text(
            stringResource(
                if (metric == ScrubMetric.SPEED) R.string.trip_scrub_legend_speed_min else R.string.trip_scrub_legend_regen
            ),
            style = textStyle,
            maxLines = 1
        )
        Box(
            modifier = Modifier
                .size(width = 110.dp, height = 6.dp)
                .background(brush, RoundedCornerShape(3.dp))
        )
        Text(
            stringResource(
                if (metric == ScrubMetric.SPEED) R.string.trip_scrub_legend_speed_max else R.string.trip_scrub_legend_consumption_max
            ),
            style = textStyle,
            maxLines = 1
        )
    }
}

/**
 * Time chart: speed (gradient by the speed scale) or signed battery power, the elevation
 * shadow behind. The paths are cached per (points, metric, size); only the cursor redraws.
 */
@Suppress("SpreadOperator") // the gradient is built once per (points, metric, size)
@Composable
private fun ScrubChart(data: ScrubData, state: ScrubState, modifier: Modifier = Modifier) {
    val metric = state.metric
    val rangeMode = state.rangeMode
    val points = data.points
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(NavyDark)
            .pointerInput(data, rangeMode) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    state.playing = false
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    fun indexAt(x: Float) = TripScrub.indexAtFraction(points, x / width)
                    val anchor = indexAt(down.position.x)
                    if (rangeMode) state.range = anchor..anchor else state.cursor = anchor
                    down.consume()
                    var change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                    while (change != null && change.pressed) {
                        val i = indexAt(change.position.x)
                        if (rangeMode) state.range = minOf(anchor, i)..maxOf(anchor, i) else state.cursor = i
                        change.consume()
                        change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                    }
                    val r = state.range
                    if (rangeMode && r != null && r.last - r.first < MIN_RANGE_POINTS) {
                        state.range = maxOf(0, r.first - RANGE_WIDEN_POINTS)..minOf(points.lastIndex, r.first + RANGE_WIDEN_POINTS)
                    }
                }
            }
            .drawWithCache {
                val w = size.width
                val h = size.height
                val top = 16.dp.toPx()
                val bottom = 6.dp.toPx()
                fun x(i: Int) = data.fractions[i] * w

                val elevation = elevationPath(data, w, h)
                val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = TextMuted.toArgb()
                    textSize = 10.sp.toPx()
                    textAlign = android.graphics.Paint.Align.RIGHT
                }

                val line = Path()
                val area = Path()
                val area2 = Path()
                var brush: Brush? = null
                var grid = emptyList<Pair<Float, String>>()
                var dot: Offset? = null
                var zeroY = 0f
                if (metric == ScrubMetric.SPEED) {
                    val vmax = data.maxSpeedIndex?.let { points[it].speedKmh } ?: 0.0
                    val yMax = maxOf(TripScrub.SPEED_SCALE_MAX_KMH, Math.ceil(vmax / 20.0) * 20.0)
                    fun y(v: Double) = (top + (1 - v / yMax) * (h - top - bottom)).toFloat()
                    brush = Brush.verticalGradient(
                        *TripScrub.SPEED_STOPS.map { (v, c) -> (v / yMax).toFloat() to Color(c) }.toTypedArray(),
                        startY = y(0.0),
                        endY = y(yMax)
                    )
                    grid = listOf(60, 120).map { y(it.toDouble()) to it.toString() }
                    area.moveTo(x(0), y(0.0))
                    points.forEachIndexed { i, p ->
                        val py = y(p.speedKmh ?: 0.0)
                        if (i == 0) line.moveTo(x(i), py) else line.lineTo(x(i), py)
                        area.lineTo(x(i), py)
                    }
                    area.lineTo(x(points.lastIndex), y(0.0))
                    area.close()
                    data.maxSpeedIndex?.let { dot = Offset(x(it), y(points[it].speedKmh ?: 0.0)) }
                } else {
                    val known = data.powerKw.filterNotNull()
                    val lo = minOf(-10.0, known.minOrNull() ?: 0.0)
                    val hi = maxOf(0.0, known.maxOrNull() ?: 0.0)
                    fun y(p: Double) = (top + (hi - p) / (hi - lo) * (h - top - bottom)).toFloat()
                    zeroY = y(0.0)
                    // Unknown power is a gap: each known stretch is its own shape down to zero.
                    var last = -1
                    fun closeStretch() {
                        if (last < 0) return
                        area.lineTo(x(last), zeroY)
                        area.close()
                        area2.lineTo(x(last), zeroY)
                        area2.close()
                        last = -1
                    }
                    data.powerKw.forEachIndexed { i, kw ->
                        if (kw == null) {
                            closeStretch()
                            return@forEachIndexed
                        }
                        if (last < 0) {
                            area.moveTo(x(i), zeroY)
                            area2.moveTo(x(i), zeroY)
                        }
                        area.lineTo(x(i), y(maxOf(0.0, kw)))
                        area2.lineTo(x(i), y(minOf(0.0, kw)))
                        last = i
                    }
                    closeStretch()
                    data.maxRegenIndex?.let { i -> data.powerKw[i]?.let { dot = Offset(x(i), y(it)) } }
                }
                val lineWidth = 2.5.dp.toPx()
                val dotRadius = 4.dp.toPx()
                val pill = Size(28.dp.toPx(), 12.dp.toPx())
                val pillBottom = 2.dp.toPx()

                onDrawBehind {
                    elevation?.let { drawPath(it, Color(0xFF334155), alpha = 0.35f) }
                    val range = state.range
                    if (rangeMode && range != null) {
                        drawRect(
                            Color.White.copy(alpha = 0.08f),
                            topLeft = Offset(x(range.first), 0f),
                            size = Size(x(range.last) - x(range.first), h)
                        )
                    }
                    if (metric == ScrubMetric.SPEED) {
                        grid.forEach { (gy, label) ->
                            drawLine(ChartGrid, Offset(0f, gy), Offset(w, gy), 1.dp.toPx())
                            drawContext.canvas.nativeCanvas.drawText(label, w - 4.dp.toPx(), gy - 3.dp.toPx(), labelPaint)
                        }
                        brush?.let {
                            drawPath(area, it, alpha = 0.22f)
                            drawPath(line, it, style = Stroke(width = lineWidth))
                        }
                        dot?.let { drawCircle(AccentOrange, dotRadius, it) }
                    } else {
                        drawLine(TextMuted, Offset(0f, zeroY), Offset(w, zeroY), 1.dp.toPx())
                        drawPath(area, AccentOrange, alpha = 0.55f)
                        drawPath(area2, AccentBlue, alpha = 0.7f)
                        dot?.let { drawCircle(AccentBlue, dotRadius, it) }
                    }
                    if (!rangeMode) {
                        val cx = x(state.cursor.coerceIn(0, points.lastIndex))
                        drawLine(Color.White, Offset(cx, 0f), Offset(cx, h), 2.dp.toPx())
                        drawRoundRect(
                            Color.White,
                            topLeft = Offset(cx - pill.width / 2, h - pill.height - pillBottom),
                            size = pill,
                            cornerRadius = CornerRadius(pill.height / 2)
                        )
                    }
                }
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, top = 2.dp, end = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                stringResource(if (metric == ScrubMetric.SPEED) R.string.trip_scrub_chart_speed else R.string.trip_scrub_chart_power),
                color = TextMuted,
                fontSize = 11.sp,
                maxLines = 1
            )
            Text(
                if (data.hasPower) "" else stringResource(R.string.trip_scrub_no_power_hint),
                color = TextMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            ScrubLegend(metric)
        }
    }
}

/** Altitude normalized to the trip's range, filling the lower 55 % of the chart; null under 2 points. */
private fun elevationPath(data: ScrubData, w: Float, h: Float): Path? {
    if (data.withAltitude < 2) return null
    val alts = data.points.mapNotNull { it.altitude }
    val lo = alts.min()
    val span = (alts.max() - lo).takeIf { it > 0 } ?: 1.0
    val path = Path()
    path.moveTo(0f, h)
    data.points.forEachIndexed { i, p ->
        val a = p.altitude ?: return@forEachIndexed
        path.lineTo(data.fractions[i] * w, (h - (a - lo) / span * h * 0.55).toFloat())
    }
    path.lineTo(w, h)
    path.close()
    return path
}
