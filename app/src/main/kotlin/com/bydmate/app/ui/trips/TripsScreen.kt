package com.bydmate.app.ui.trips

import android.graphics.Paint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.local.entity.TripPointEntity
import com.bydmate.app.ui.components.SummaryRow
import com.bydmate.app.ui.components.TripCard
import com.bydmate.app.ui.theme.*
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val BarColor = AccentBlue
private val BarMaxColor = SocRed

// ============================================================================
// TripsScreen - Main composable for the Trips tab
// ============================================================================

/**
 * Trips screen showing a period toggle, summary stats, and a list of trips
 * grouped by day. Expanding a trip shows a mini map with route polyline
 * and a speed timeline bar chart.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripsScreen(
    viewModel: TripsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(NavyDark, NavyDeep)))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // -- Period toggle chips --
        PeriodToggle(
            currentPeriod = if (state.periodLabel == "7 дней") Period.WEEK else Period.MONTH,
            onPeriodChange = viewModel::setPeriod
        )

        Spacer(modifier = Modifier.height(12.dp))

        // -- Summary row with totals for the selected period --
        SummaryRow(
            totalKm = state.totalKm,
            totalKwh = state.totalKwh,
            avgKwhPer100km = state.avgConsumption,
            totalCost = state.totalCost,
            currencySymbol = state.currencySymbol
        )

        Spacer(modifier = Modifier.height(12.dp))

        // -- Trip list grouped by day --
        val grouped = remember(state.trips) { groupTripsByDay(state.trips) }

        if (state.trips.isEmpty()) {
            // Empty state
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Нет поездок за ${state.periodLabel}",
                    color = TextSecondary,
                    fontSize = 16.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                grouped.forEach { (dayLabel, trips) ->
                    // Day header
                    item(key = "header_$dayLabel") {
                        Text(
                            text = dayLabel,
                            color = TextSecondary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }

                    // Trip cards for this day
                    items(
                        items = trips,
                        key = { it.id }
                    ) { trip ->
                        val isExpanded = state.expandedTripId == trip.id

                        TripCard(
                            trip = trip,
                            onClick = { viewModel.toggleTripExpansion(trip.id) },
                            currencySymbol = state.currencySymbol
                        )

                        // Expandable detail section: mini map + speed timeline
                        AnimatedVisibility(
                            visible = isExpanded,
                            enter = expandVertically(),
                            exit = shrinkVertically()
                        ) {
                            TripDetailSection(
                                points = state.expandedTripPoints
                            )
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// PeriodToggle - Week / Month filter chips
// ============================================================================

/**
 * Row of two FilterChips for selecting the time period.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodToggle(
    currentPeriod: Period,
    onPeriodChange: (Period) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = currentPeriod == Period.WEEK,
            onClick = { onPeriodChange(Period.WEEK) },
            label = {
                Text(
                    text = "Неделя",
                    color = TextPrimary,
                    fontSize = 14.sp
                )
            },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = AccentGreenDark,
                containerColor = CardSurfaceElevated
            )
        )

        FilterChip(
            selected = currentPeriod == Period.MONTH,
            onClick = { onPeriodChange(Period.MONTH) },
            label = {
                Text(
                    text = "Месяц",
                    color = TextPrimary,
                    fontSize = 14.sp
                )
            },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = AccentGreenDark,
                containerColor = CardSurfaceElevated
            )
        )
    }
}

// ============================================================================
// TripDetailSection - Expanded trip detail (map + speed chart)
// ============================================================================

/**
 * Shows an osmdroid mini map with the route polyline and a speed timeline
 * bar chart below it when a trip is expanded.
 */
@Composable
private fun TripDetailSection(
    points: List<TripPointEntity>
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (points.isEmpty()) {
            Text(
                text = "Загрузка маршрута…",
                color = TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 4.dp)
            )
        } else {
            // Mini osmdroid map with route polyline
            TripMiniMap(
                points = points,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
            )

            // Speed timeline bar chart
            SpeedTimeline(
                points = points,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
            )
        }
    }
}

// ============================================================================
// TripMiniMap - osmdroid MapView with route polyline
// ============================================================================

/**
 * Renders trip points as a polyline on an osmdroid MapView.
 * The map auto-zooms to fit the bounding box of all points.
 */
@Composable
private fun TripMiniMap(
    points: List<TripPointEntity>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val geoPoints = remember(points) {
        points.map { GeoPoint(it.lat, it.lon) }
    }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            // Disable unnecessary controls for a compact mini map
            zoomController.setVisibility(
                org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER
            )
        }
    }

    // Clean up mapView when composable leaves composition
    DisposableEffect(Unit) {
        onDispose { mapView.onDetach() }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { map ->
            // Clear previous overlays
            map.overlays.clear()

            if (geoPoints.size >= 2) {
                // Draw route polyline
                val polyline = Polyline().apply {
                    setPoints(geoPoints)
                    outlinePaint.color = AccentGreen.toArgb()
                    outlinePaint.strokeWidth = 6f
                    outlinePaint.isAntiAlias = true
                }
                map.overlays.add(polyline)

                // Zoom to fit all points with padding
                val boundingBox = BoundingBox.fromGeoPoints(geoPoints)
                map.post {
                    map.zoomToBoundingBox(boundingBox, true, 48)
                }
            } else if (geoPoints.isNotEmpty()) {
                // Single point: center on it
                map.controller.setZoom(15.0)
                map.controller.setCenter(geoPoints.first())
            }

            map.invalidate()
        }
    )
}

// ============================================================================
// SpeedTimeline - Simple bar chart of speed at each trip point
// ============================================================================

/**
 * Draws a simple vertical bar chart where each bar represents a trip point's
 * speed. The tallest bar corresponds to the maximum speed, and bars are
 * color-coded (blue by default, red for maximum).
 */
@Composable
private fun SpeedTimeline(
    points: List<TripPointEntity>,
    modifier: Modifier = Modifier
) {
    val speeds = remember(points) {
        points.mapNotNull { it.speedKmh }
    }

    if (speeds.isEmpty()) return

    val maxSpeed = remember(speeds) { speeds.max() }

    Canvas(modifier = modifier) {
        val barCount = speeds.size
        if (barCount == 0 || maxSpeed <= 0.0) return@Canvas

        val barWidth = size.width / barCount
        val chartHeight = size.height - 20f // Leave space for axis label

        speeds.forEachIndexed { index, speed ->
            val barHeight = (speed / maxSpeed * chartHeight).toFloat()
            val isMax = speed == maxSpeed
            val color = if (isMax) BarMaxColor else BarColor

            // Draw bar from bottom up
            drawRect(
                color = color,
                topLeft = Offset(
                    x = index * barWidth + barWidth * 0.1f,
                    y = chartHeight - barHeight
                ),
                size = Size(
                    width = barWidth * 0.8f,
                    height = barHeight
                )
            )
        }

        // Draw max speed label at the top right
        drawContext.canvas.nativeCanvas.drawText(
            "макс ${maxSpeed.toInt()} км/ч",
            size.width - 8f,
            16f,
            Paint().apply {
                color = TextSecondary.toArgb()
                textSize = 28f
                textAlign = Paint.Align.RIGHT
                isAntiAlias = true
            }
        )
    }
}

// ============================================================================
// Helpers
// ============================================================================

/**
 * Groups a list of trips by day, returning an ordered list of (dayLabel, trips) pairs.
 * Day label format: "Mon, 28 Mar" (abbreviated weekday, day, abbreviated month).
 */
private fun groupTripsByDay(
    trips: List<TripEntity>
): List<Pair<String, List<TripEntity>>> {
    val sdf = SimpleDateFormat("EEE, dd MMM", Locale.getDefault())
    val dayKeyFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())

    // Group by day key, preserving insertion order (trips are already sorted desc)
    val groups = linkedMapOf<String, MutableList<TripEntity>>()
    for (trip in trips) {
        val date = Date(trip.startTs)
        val key = dayKeyFormat.format(date)
        val label = sdf.format(date)
        val list = groups.getOrPut(key) { mutableListOf() }
        list.add(trip)
        // Store label as the key in the result map; we need a separate mapping
        // because multiple trips share the same label.
        // We'll use a parallel map for labels below.
    }

    // Build result with proper labels
    val labelMap = linkedMapOf<String, String>()
    for (trip in trips) {
        val date = Date(trip.startTs)
        val key = dayKeyFormat.format(date)
        if (key !in labelMap) {
            labelMap[key] = sdf.format(date)
        }
    }

    return groups.map { (key, tripList) ->
        (labelMap[key] ?: key) to tripList.toList()
    }
}
