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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.local.entity.TripPointEntity
import com.bydmate.app.ui.components.consumptionColor
import com.bydmate.app.ui.components.formatDuration
import com.bydmate.app.ui.components.formatTime
import com.bydmate.app.R
import com.bydmate.app.ui.theme.*
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.RectangleShape
import java.text.SimpleDateFormat
import java.util.Date

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
