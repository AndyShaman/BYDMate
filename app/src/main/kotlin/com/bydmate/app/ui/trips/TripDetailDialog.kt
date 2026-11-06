@file:Suppress("TooManyFunctions") // the trip window: header, stats column, map, chart and their small parts

package com.bydmate.app.ui.trips

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.local.entity.TripPointEntity
import com.bydmate.app.ui.components.consumptionColor
import com.bydmate.app.ui.components.formatDuration
import com.bydmate.app.ui.components.formatTime
import com.bydmate.app.R
import com.bydmate.app.ui.theme.*
import com.bydmate.app.BYDMateApp
import com.bydmate.app.util.Gcj02Converter
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
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
import androidx.compose.ui.graphics.RectangleShape
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
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.abs

@Composable
fun TripDetailDialog(
    trip: TripEntity,
    points: List<TripPointEntity>,
    currencySymbol: String,
    tileSource: String = "osm",
    // DM-i (#184): fuel rows appear on a hybrid or on any trip that carries litres.
    hybrid: Boolean = false,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        ScaledDialogContent {
            // The whole app window (between the DiLink status bar and the climate bar): the map needs the height.
            Box(modifier = Modifier.fillMaxSize()) {
                Card(
                    shape = RectangleShape,
                    colors = CardDefaults.cardColors(containerColor = CardSurface),
                    modifier = Modifier.fillMaxSize()
                ) {
                    val isStop = (trip.distanceKm ?: 0.0) == 0.0
                    val scrub = remember(points) { if (points.size >= 2) ScrubData(points) else null }
                    val scrubState = remember(points) { ScrubState() }
                    scrub?.let { ScrubPlayback(it, scrubState) }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TripDetailHeader(trip, isStop, onDismiss) {
                            if (scrub != null && scrub.points.size >= CHART_MIN_POINTS) ScrubControls(scrub, scrubState)
                        }

                        // Landscape: map left, stats right
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // LEFT: map with the values at the cursor, controls, chart
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (scrub != null) {
                                    TripScrubPane(data = scrub, state = scrubState, tileSource = tileSource)
                                } else {
                                    LaunchedEffect(points.size) {
                                        Log.d(SCRUB_TAG, "open: points=${points.size} (no track)")
                                    }
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f)
                                            .background(NavyDark, RoundedCornerShape(8.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(stringResource(R.string.trip_detail_gps_unavailable), color = TextMuted, fontSize = 13.sp)
                                    }
                                }
                            }

                            // RIGHT: Stats in groups split by a thin line; a row without data and a group without rows are hidden
                            val showFuel = hybrid || trip.fuelLiters != null
                            val groups = buildList {
                                add(buildList {
                                    trip.distanceKm?.let {
                                        add(StatRow(stringResource(R.string.trip_detail_distance_label),
                                            stringResource(R.string.trip_detail_distance_value, it), main = true))
                                    }
                                    val odoStart = trip.odometerStartKm
                                    val odoEnd = trip.odometerEndKm
                                    if (isStop && odoStart != null && odoStart == odoEnd) {
                                        add(StatRow(stringResource(R.string.trip_detail_odometer_label),
                                            stringResource(R.string.trip_detail_odometer_value, odoEnd)))
                                    } else {
                                        odoStart?.let {
                                            add(StatRow(stringResource(R.string.trip_detail_odometer_start_label),
                                                stringResource(R.string.trip_detail_odometer_value, it)))
                                        }
                                        odoEnd?.let {
                                            add(StatRow(stringResource(R.string.trip_detail_odometer_end_label),
                                                stringResource(R.string.trip_detail_odometer_value, it)))
                                        }
                                    }
                                })
                                add(buildList {
                                    if (trip.endTs != null) {
                                        add(StatRow(stringResource(R.string.trip_detail_duration_label),
                                            formatDuration(ctx, trip.startTs, trip.endTs)))
                                    }
                                    val moving = remember(points, trip.startTs, trip.endTs) {
                                        TripDetailStats.movingTimeMs(points, trip.startTs, trip.endTs)
                                    }
                                    moving?.let {
                                        add(StatRow(stringResource(R.string.trip_detail_moving_label), formatDuration(ctx, 0L, it)))
                                    }
                                    trip.avgSpeedKmh?.let {
                                        add(StatRow(stringResource(R.string.trip_detail_avg_speed_label),
                                            stringResource(R.string.trip_detail_speed_value, it)))
                                    }
                                    if (points.isNotEmpty()) {
                                        val maxSpeed = points.maxOfOrNull { it.speedKmh ?: 0.0 } ?: 0.0
                                        if (maxSpeed > 0) {
                                            add(StatRow(stringResource(R.string.trip_detail_max_speed_label),
                                                stringResource(R.string.trip_detail_speed_value, maxSpeed)))
                                        }
                                    }
                                })
                                add(buildList {
                                    trip.kwhConsumed?.let {
                                        add(StatRow(stringResource(R.string.trip_detail_consumption_label),
                                            stringResource(R.string.trip_detail_consumption_value, it)))
                                        trip.kwhPer100km?.let { per100 ->
                                            add(StatRow(stringResource(R.string.trip_detail_efficiency_label),
                                                stringResource(R.string.trip_detail_efficiency_value, per100), consumptionColor(per100)))
                                        }
                                    }
                                    if (trip.socStart != null && trip.socEnd != null) {
                                        add(StatRow(stringResource(R.string.trip_detail_soc_label),
                                            "${trip.socStart}% → ${trip.socEnd}%",
                                            note = "(${TripDetailStats.socChangeLabel(trip.socStart, trip.socEnd)})"))
                                    }
                                    if (showFuel) {
                                        add(StatRow(stringResource(R.string.trip_detail_fuel_label),
                                            trip.fuelLiters?.let { stringResource(R.string.dashboard_fuel_liters_value, it) } ?: "—",
                                            if ((trip.fuelLiters ?: 0.0) > 0.0) AccentOrange else TextPrimary))
                                        val hev = trip.hevKm
                                        val dist = trip.distanceKm
                                        add(StatRow(stringResource(R.string.trip_detail_hev_ev_label),
                                            if (hev != null && dist != null)
                                                stringResource(R.string.trip_detail_hev_ev_value, hev, (dist - hev).coerceAtLeast(0.0))
                                            else "—"))
                                    }
                                })
                                add(buildList {
                                    trip.cost?.let {
                                        add(StatRow(stringResource(R.string.trip_detail_cost_label),
                                            "%.2f %s".format(it, currencySymbol), AccentGreen))
                                    }
                                    TripDetailStats.costPer100Km(trip.cost, trip.distanceKm)?.let {
                                        add(StatRow(stringResource(R.string.trip_detail_cost_per_100_label),
                                            "%.2f %s".format(it, currencySymbol)))
                                    }
                                    if (showFuel) {
                                        add(StatRow(stringResource(R.string.trip_detail_fuel_cost_label),
                                            trip.fuelCost?.let { "%.2f %s".format(it, currencySymbol) } ?: "—"))
                                    }
                                })
                                add(buildList {
                                    TripDetailStats.exteriorTempLabel(trip.exteriorTemp, trip.exteriorTempEnd)?.let {
                                        add(StatRow(stringResource(R.string.trip_detail_ext_temp_label), it))
                                    }
                                })
                            }.filter { it.isNotEmpty() }

                            TripStatsColumn(
                                groups = groups,
                                modifier = Modifier
                                    .width(340.dp)
                                    .fillMaxHeight()
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Close button on the left (the driver's side), the title with the weekday and date and the
 * time range below it, then the window [controls].
 */
@Composable
private fun TripDetailHeader(
    trip: TripEntity,
    isStop: Boolean,
    onDismiss: () -> Unit,
    controls: @Composable () -> Unit,
) {
    // Reading LocalConfiguration ties the date to the app language (see TripsScreen.MonthHeader).
    val locale = LocalConfiguration.current.locales[0]
    val datePattern = stringResource(R.string.trip_detail_date_pattern)
    val dateLabel = remember(trip.startTs, locale, datePattern) {
        SimpleDateFormat(datePattern, locale).format(Date(trip.startTs))
            .replaceFirstChar { it.uppercase(locale) }
    }
    val title = if (isStop) stringResource(R.string.trip_detail_title_stop) else stringResource(R.string.trip_detail_title_trip)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(CardSurfaceElevated, CircleShape)
                    .border(1.dp, CardBorder, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.trip_detail_close),
                    tint = TextPrimary,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = AccentGreen, fontWeight = FontWeight.Bold)) { append(title) }
                    withStyle(SpanStyle(color = TextPrimary, fontWeight = FontWeight.Medium)) { append(" · $dateLabel") }
                },
                fontSize = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${formatTime(trip.startTs)}${trip.endTs?.let { " – ${formatTime(it)}" } ?: ""}",
                color = TextSecondary,
                fontSize = 14.sp,
                style = TextStyle(fontFeatureSettings = "tnum")
            )
        }
        controls()
    }
}

/** One stats line; [main] is the big distance row, [note] a muted tail after the value. */
private data class StatRow(
    val label: String,
    val value: String,
    val valueColor: Color = TextPrimary,
    val main: Boolean = false,
    val note: String? = null,
)

@Composable
private fun TripStatsColumn(groups: List<List<StatRow>>, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(start = 8.dp, end = 4.dp)
        ) {
            groups.forEachIndexed { index, rows ->
                if (index > 0) {
                    HorizontalDivider(color = CardBorder, thickness = 1.dp, modifier = Modifier.padding(vertical = 8.dp))
                }
                rows.forEach { DetailRow(it) }
            }
        }
        // Soft fade at the bottom hints that the column scrolls on.
        if (scroll.canScrollForward) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(36.dp)
                    .background(Brush.verticalGradient(listOf(CardSurface.copy(alpha = 0f), CardSurface)))
            )
        }
    }
}

@Composable
private fun DetailRow(row: StatRow) {
    val tabular = TextStyle(fontFeatureSettings = "tnum", lineHeight = 1.22.em)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            row.label,
            color = if (row.main) TextPrimary else TextSecondary,
            fontSize = 14.sp,
            style = TextStyle(lineHeight = 1.22.em),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .alignByBaseline()
        )
        val value = if (row.main) {
            // "50.5 km": the number big and bold, the unit in the regular value size.
            val cut = row.value.indexOf(' ').takeIf { it > 0 } ?: row.value.length
            buildAnnotatedString {
                withStyle(SpanStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold)) { append(row.value.substring(0, cut)) }
                withStyle(SpanStyle(fontSize = 15.sp, color = TextSecondary)) { append(row.value.substring(cut)) }
            }
        } else {
            buildAnnotatedString {
                append(row.value)
                row.note?.let {
                    withStyle(SpanStyle(fontSize = 13.sp, color = TextMuted, fontWeight = FontWeight.Normal)) { append(" $it") }
                }
            }
        }
        Text(
            value,
            color = row.valueColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            style = tabular,
            maxLines = 1,
            modifier = Modifier.alignByBaseline()
        )
    }
}

private enum class ScrubMetric { SPEED, CONSUMPTION }

/** Cursor, range and playback of the trip window; plain remember, the dialog opens per trip. */
@Stable
private class ScrubState {
    var cursor by mutableIntStateOf(0)
    var metric by mutableStateOf(ScrubMetric.SPEED)
    var rangeMode by mutableStateOf(false)
    var range by mutableStateOf<IntRange?>(null)
    var playing by mutableStateOf(false)
}

/** Everything the window derives from the points once per trip. */
private class ScrubData(val points: List<TripPointEntity>) {
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

private const val SCRUB_TAG = "TripDetail"
private const val PLAY_FRAME_MS = 40L
private const val PLAY_FRAMES = 300
private const val MIN_RANGE_POINTS = 10
private const val RANGE_WIDEN_POINTS = 30
/** Fewer points draw no chart, and the header shows no controls for it. */
private const val CHART_MIN_POINTS = 4
/** Border of the route fit, px, as before. */
private const val FIT_BORDER_PX = 48

/** Logs the opened track and runs «Play»: moves the cursor to the end, then stops. */
@Composable
private fun ScrubPlayback(data: ScrubData, state: ScrubState) {
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
private fun ColumnScope.TripScrubPane(data: ScrubData, state: ScrubState, tileSource: String) {
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
private fun ScrubControls(data: ScrubData, state: ScrubState) {
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

private fun setRangeMode(state: ScrubState, on: Boolean) {
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
        ScrubMetric.CONSUMPTION -> Triple(TripScrub.CONSUMPTION_STOPS, -20.0, 60.0)
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

@Composable
private fun TripRouteMap(
    data: ScrubData,
    state: ScrubState,
    tileSource: String,
    topInsetPx: Int,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isAmap = tileSource == "amap"
    val points = data.points

    // Converted once per trip: cursor moves must not rebuild the coordinates or the overlay.
    val geoPoints = remember(points, tileSource) {
        points.map {
            val (lat, lon) = if (isAmap) Gcj02Converter.wgs84ToGcj02(it.lat, it.lon) else it.lat to it.lon
            GeoPoint(lat, lon)
        }
    }

    val mapView = remember(tileSource) {
        MapView(context).apply {
            setTileSource(
                if (tileSource == "amap") BYDMateApp.AmapTileSource else TileSourceFactory.MAPNIK
            )
            setMultiTouchControls(true)
            zoomController.setVisibility(
                org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER
            )
        }
    }
    val fontScale = LocalDensity.current.fontScale
    val overlay = remember(mapView) { TripRouteOverlay(context.resources.displayMetrics.density, fontScale) }
    val speedLabel = data.maxSpeedIndex?.let {
        stringResource(R.string.trip_scrub_map_max_speed, Math.round(points[it].speedKmh ?: 0.0))
    }
    val regenLabel = data.maxRegenIndex?.takeIf { data.hasPower }?.let { i ->
        points[i].batteryPowerW?.let { stringResource(R.string.trip_scrub_map_max_regen, Math.round(abs(it) / 1000.0)) }
    }

    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }

    key(tileSource) {
        AndroidView(
            factory = {
                mapView.overlays.clear()
                mapView.overlays.add(overlay)
                mapView
            },
            modifier = modifier,
            update = { map ->
                overlay.geoPoints = geoPoints
                overlay.colors = if (state.metric == ScrubMetric.CONSUMPTION) data.consumptionColors else data.speedColors
                overlay.maxSpeedIndex = data.maxSpeedIndex
                overlay.speedLabel = speedLabel
                overlay.maxRegenIndex = if (regenLabel != null) data.maxRegenIndex else null
                overlay.regenLabel = regenLabel
                overlay.cursor = state.cursor.coerceIn(0, geoPoints.lastIndex)
                overlay.range = if (state.rangeMode) state.range else null
                overlay.rangeMode = state.rangeMode
                overlay.onTouch = { state.playing = false }
                overlay.onTap = { i ->
                    if (state.rangeMode) setRangeMode(state, false)
                    state.cursor = i
                }

                // Fit the route once per points list, never on cursor moves; waits for the
                // values panel to be measured so the route lands under it.
                if (overlay.fittedFor !== geoPoints && geoPoints.size >= 2 && topInsetPx > 0) {
                    overlay.fittedFor = geoPoints
                    map.post { fitRoute(map, geoPoints, topInsetPx) }
                }
                map.invalidate()
            }
        )
    } // key(tileSource)
}

/** Zooms to the route, stretched north by the part of the map the values panel covers. */
@Suppress("TooGenericExceptionCaught") // as before: any osmdroid failure falls back to centering, now logged
private fun fitRoute(map: MapView, geoPoints: List<GeoPoint>, topInsetPx: Int) {
    try {
        val b = BoundingBox.fromGeoPoints(geoPoints)
        val pad = TripScrub.northPadFraction(map.height, FIT_BORDER_PX, topInsetPx)
        val bbox = BoundingBox(b.latNorth + (b.latNorth - b.latSouth) * pad, b.lonEast, b.latSouth, b.lonWest)
        map.zoomToBoundingBox(bbox, true, FIT_BORDER_PX)
        Log.d(SCRUB_TAG, "fit: map=${map.width}x${map.height} panelBottom=$topInsetPx northPad=${"%.2f".format(pad)}")
    } catch (e: Exception) {
        Log.w(SCRUB_TAG, "fit failed: ${e.message}")
        map.controller.setCenter(geoPoints.first())
    }
}

/**
 * The whole trip in one osmdroid overlay: outline, colored segments, start/end pins,
 * max speed / max regen markers and the cursor arrow. Much cheaper than two Polyline
 * overlays per segment; drags stay with the map (pan/zoom), only a tap is taken.
 */
private class TripRouteOverlay(private val density: Float, fontScale: Float) : Overlay() {
    var geoPoints: List<GeoPoint> = emptyList()
    var colors: IntArray = IntArray(0)
    var cursor = 0
    var rangeMode = false
    var range: IntRange? = null
    var maxSpeedIndex: Int? = null
    var speedLabel: String? = null
    var maxRegenIndex: Int? = null
    var regenLabel: String? = null
    var onTap: (Int) -> Unit = {}
    var onTouch: () -> Unit = {}
    var fittedFor: List<GeoPoint>? = null

    private var xy = FloatArray(0)
    private val screen = android.graphics.Point()
    private val trackPath = android.graphics.Path()
    private val rangePath = android.graphics.Path()
    private val rect = android.graphics.RectF()
    private var heading = 0f

    private val outlinePaint = strokePaint(14f, android.graphics.Color.argb(180, 0, 0, 0))
    private val segmentPaint = strokePaint(8f, android.graphics.Color.WHITE)
    private val rangePaint = strokePaint(24f, android.graphics.Color.argb(89, 255, 255, 255))
    private val fillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeJoin = android.graphics.Paint.Join.ROUND
    }
    private val pinTextPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = NavyDark.toArgb()
        textSize = 12f * density * fontScale
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = TextPrimary.toArgb()
        textSize = 12f * density * fontScale
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = android.graphics.Typeface.MONOSPACE
    }
    // The mock's arrow, pointing along +x, in px.
    private val arrow = android.graphics.Path().apply {
        moveTo(14f * density, 0f)
        lineTo(-10f * density, -10f * density)
        lineTo(-5f * density, 0f)
        lineTo(-10f * density, 10f * density)
        close()
    }

    private fun strokePaint(width: Float, color: Int) =
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
            strokeWidth = width
            this.color = color
        }

    override fun draw(canvas: android.graphics.Canvas, mapView: MapView, shadow: Boolean) {
        val n = geoPoints.size
        if (shadow || n < 2 || colors.size != n) return
        if (xy.size != n * 2) xy = FloatArray(n * 2)
        val projection = mapView.projection
        for (i in 0 until n) {
            projection.toPixels(geoPoints[i], screen)
            xy[2 * i] = screen.x.toFloat()
            xy[2 * i + 1] = screen.y.toFloat()
        }

        drawTrack(canvas, n)
        drawPin(canvas, 0, AccentGreen.toArgb(), "A")
        drawPin(canvas, n - 1, TextPrimary.toArgb(), "B")
        maxSpeedIndex?.let { i -> speedLabel?.let { drawMarker(canvas, i, it, AccentOrange.toArgb(), below = false) } }
        maxRegenIndex?.let { i -> regenLabel?.let { drawMarker(canvas, i, it, AccentBlue.toArgb(), below = true) } }

        if (!rangeMode) drawCursor(canvas, cursor.coerceIn(0, n - 1))
    }

    /** Range highlight (range mode), dark outline, then the colored segments. */
    private fun drawTrack(canvas: android.graphics.Canvas, n: Int) {
        val r = range
        if (rangeMode && r != null && r.last > r.first) {
            buildPath(rangePath, r.first, r.last.coerceAtMost(n - 1))
            canvas.drawPath(rangePath, rangePaint)
        }
        buildPath(trackPath, 0, n - 1)
        canvas.drawPath(trackPath, outlinePaint)
        for (i in 0 until n - 1) {
            segmentPaint.color = colors[i]
            canvas.drawLine(xy[2 * i], xy[2 * i + 1], xy[2 * i + 2], xy[2 * i + 3], segmentPaint)
        }
    }

    private fun buildPath(path: android.graphics.Path, from: Int, to: Int) {
        path.reset()
        path.moveTo(xy[2 * from], xy[2 * from + 1])
        for (i in from + 1..to) path.lineTo(xy[2 * i], xy[2 * i + 1])
    }

    private fun drawPin(canvas: android.graphics.Canvas, i: Int, color: Int, letter: String) {
        val x = xy[2 * i]
        val y = xy[2 * i + 1]
        fillPaint.color = color
        canvas.drawCircle(x, y, 11f * density, fillPaint)
        ringPaint.color = NavyDark.toArgb()
        ringPaint.strokeWidth = 3f * density
        canvas.drawCircle(x, y, 11f * density, ringPaint)
        canvas.drawText(letter, x, y + 4.5f * density, pinTextPaint)
    }

    /** Dot and a framed label: above-right for max speed, below-left for max regen. */
    private fun drawMarker(canvas: android.graphics.Canvas, i: Int, label: String, color: Int, below: Boolean) {
        val x = xy[2 * i]
        val y = xy[2 * i + 1]
        fillPaint.color = color
        canvas.drawCircle(x, y, 4f * density, fillPaint)
        val w = labelPaint.measureText(label) + 14f * density
        val left = if (below) x - 8f * density - w else x + 8f * density
        val top = if (below) y + 6f * density else y - 26f * density
        rect.set(left, top, left + w, top + 20f * density)
        fillPaint.color = NavyDark.toArgb()
        canvas.drawRoundRect(rect, 5f * density, 5f * density, fillPaint)
        ringPaint.color = color
        ringPaint.strokeWidth = 1f * density
        canvas.drawRoundRect(rect, 5f * density, 5f * density, ringPaint)
        canvas.drawText(label, rect.centerX(), rect.bottom - 6f * density, labelPaint)
    }

    private fun drawCursor(canvas: android.graphics.Canvas, i: Int) {
        val n = geoPoints.size
        val a = maxOf(0, i - 2)
        val b = minOf(n - 1, i + 2)
        val dx = xy[2 * b] - xy[2 * a]
        val dy = xy[2 * b + 1] - xy[2 * a + 1]
        // Standing still keeps the last heading instead of snapping to east.
        if (dx * dx + dy * dy > 1f) heading = Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
        val x = xy[2 * i]
        val y = xy[2 * i + 1]
        fillPaint.color = AccentGreen.copy(alpha = 0.18f).toArgb()
        canvas.drawCircle(x, y, 22f * density, fillPaint)
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(heading)
        fillPaint.color = android.graphics.Color.WHITE
        canvas.drawPath(arrow, fillPaint)
        ringPaint.color = NavyDark.toArgb()
        ringPaint.strokeWidth = 2.5f * density
        canvas.drawPath(arrow, ringPaint)
        canvas.restore()
    }

    override fun onTouchEvent(event: android.view.MotionEvent, mapView: MapView): Boolean {
        if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) onTouch()
        return false
    }

    override fun onSingleTapConfirmed(event: android.view.MotionEvent, mapView: MapView): Boolean {
        val n = geoPoints.size
        if (n == 0) return false
        val projection = mapView.projection
        var best = -1
        var bestD2 = Float.MAX_VALUE
        for (i in 0 until n) {
            projection.toPixels(geoPoints[i], screen)
            val dx = screen.x - event.x
            val dy = screen.y - event.y
            val d2 = dx * dx + dy * dy
            if (d2 < bestD2) {
                bestD2 = d2
                best = i
            }
        }
        val threshold = 50f * density
        if (best >= 0 && bestD2 <= threshold * threshold) {
            onTap(best)
            mapView.invalidate()
            return true
        }
        return false
    }
}
