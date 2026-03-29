package com.bydmate.app.ui.map

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bydmate.app.ui.components.consumptionColor
import com.bydmate.app.ui.components.formatTime
import com.bydmate.app.ui.theme.*
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

@Composable
fun MapScreen(
    viewModel: MapViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Configuration.getInstance().apply {
        userAgentValue = "BYDMate/1.0"
        osmdroidBasePath = context.getExternalCacheDir()
        tileFileSystemCacheMaxBytes = 100L * 1024 * 1024 // 100 MB max
        tileFileSystemCacheTrimBytes = 80L * 1024 * 1024 // trim to 80 MB
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NavyDark)
    ) {
        if (state.isLoading && state.tripRoutes.isEmpty() && state.charges.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentGreen)
            }
        } else if (state.tripRoutes.isEmpty() && state.charges.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Нет данных за выбранный период", color = TextSecondary, fontSize = 16.sp)
            }
        } else {
            Row(modifier = Modifier.fillMaxSize()) {
                // Map area
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    OsmdroidMapView(state = state)

                    // Period selector + panel toggle at top
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        PeriodChip("День", state.period == MapPeriod.DAY) { viewModel.setPeriod(MapPeriod.DAY) }
                        PeriodChip("Неделя", state.period == MapPeriod.WEEK) { viewModel.setPeriod(MapPeriod.WEEK) }
                        PeriodChip("Месяц", state.period == MapPeriod.MONTH) { viewModel.setPeriod(MapPeriod.MONTH) }
                    }

                    // Zoom-to-fit button
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(12.dp)
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(CardSurface.copy(alpha = 0.9f))
                            .clickable { /* zoom handled in map update */ },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("⊙", color = TextPrimary, fontSize = 18.sp)
                    }

                    // Panel toggle if collapsed
                    if (!state.panelExpanded) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(12.dp)
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(CardSurface.copy(alpha = 0.9f))
                                .clickable { viewModel.togglePanel() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("◀", color = TextPrimary, fontSize = 16.sp)
                        }
                    }
                }

                // Side panel
                AnimatedVisibility(
                    visible = state.panelExpanded,
                    enter = slideInHorizontally(initialOffsetX = { it }),
                    exit = slideOutHorizontally(targetOffsetX = { it })
                ) {
                    SidePanel(state = state, onClose = { viewModel.togglePanel() })
                }
            }
        }
    }
}

@Composable
private fun SidePanel(state: MapUiState, onClose: () -> Unit) {
    Card(
        shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        modifier = Modifier
            .width(220.dp)
            .fillMaxHeight()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val periodLabel = when (state.period) {
                    MapPeriod.DAY -> "Сегодня"
                    MapPeriod.WEEK -> "Неделя"
                    MapPeriod.MONTH -> "Месяц"
                }
                Text(periodLabel, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("▶", color = TextSecondary, fontSize = 14.sp,
                    modifier = Modifier.clickable { onClose() })
            }

            // Stats
            StatLine("Дистанция", "%.1f км".format(state.totalKm))
            StatLine("Энергия", "%.1f кВт·ч".format(state.totalKwh))
            if (state.avgConsumption > 0) {
                StatLine("Расход", "%.1f/100".format(state.avgConsumption),
                    valueColor = consumptionColor(state.avgConsumption))
            }
            StatLine("Поездок", "${state.trips.size}")
            StatLine("Зарядок", "${state.charges.size}")

            Spacer(modifier = Modifier.height(4.dp))
            Text("Поездки", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(state.trips.take(20)) { trip ->
                    val time = formatTime(trip.startTs)
                    val dist = trip.distanceKm?.let { "%.1f км".format(it) } ?: "—"
                    val consumption = trip.kwhPer100km
                    val color = consumption?.let { consumptionColor(it) } ?: TextSecondary
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(time, color = TextSecondary, fontSize = 12.sp)
                        Text(dist, color = TextPrimary, fontSize = 12.sp)
                        Text(
                            consumption?.let { "%.0f".format(it) } ?: "—",
                            color = color, fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatLine(label: String, value: String, valueColor: Color = TextPrimary) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = TextSecondary, fontSize = 13.sp)
        Text(value, color = valueColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PeriodChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 12.sp) },
        shape = RoundedCornerShape(8.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = AccentGreen,
            selectedLabelColor = Color.White,
            containerColor = CardSurface.copy(alpha = 0.9f),
            labelColor = TextSecondary
        ),
        border = FilterChipDefaults.filterChipBorder(
            borderColor = Color.Transparent,
            selectedBorderColor = Color.Transparent,
            enabled = true,
            selected = selected
        )
    )
}

@Composable
private fun OsmdroidMapView(state: MapUiState) {
    val context = LocalContext.current

    val lastPoint = remember(state.tripRoutes) {
        state.tripRoutes.lastOrNull()?.points?.lastOrNull()?.let {
            GeoPoint(it.lat, it.lon)
        }
    }

    val defaultCenter = GeoPoint(22.5431, 114.0579)
    val center = lastPoint ?: defaultCenter

    val darkTileSource = remember {
        XYTileSource(
            "CartoDB Dark", 0, 19, 256, ".png",
            arrayOf("https://basemaps.cartocdn.com/dark_all/")
        )
    }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(darkTileSource)
            setMultiTouchControls(true)
            controller.setZoom(13.0)
            controller.setCenter(center)
        }
    }

    DisposableEffect(Unit) {
        onDispose { mapView.onDetach() }
    }

    AndroidView(
        factory = { mapView },
        modifier = Modifier.fillMaxSize(),
        update = { map ->
            map.overlays.clear()

            // Draw trip routes colored by consumption
            for (route in state.tripRoutes) {
                if (route.points.size < 2) continue

                val routeColor = route.kwhPer100km?.let { consumptionColor(it) } ?: AccentGreen

                val polyline = Polyline().apply {
                    outlinePaint.color = routeColor.toArgb()
                    outlinePaint.strokeWidth = 6f
                    outlinePaint.isAntiAlias = true
                    infoWindow = null
                }

                polyline.setPoints(route.points.map { GeoPoint(it.lat, it.lon) })
                map.overlays.add(polyline)
            }

            // Charge markers colored by type
            for (charge in state.charges) {
                val lat = charge.lat ?: continue
                val lon = charge.lon ?: continue

                val marker = Marker(map).apply {
                    position = GeoPoint(lat, lon)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = buildString {
                        append(charge.type ?: "Зарядка")
                        charge.kwhCharged?.let { append(" %.1f кВт·ч".format(it)) }
                    }
                }
                map.overlays.add(marker)
            }

            // Zoom to fit all points
            val allGeoPoints = mutableListOf<GeoPoint>()
            state.tripRoutes.forEach { route ->
                route.points.forEach { allGeoPoints.add(GeoPoint(it.lat, it.lon)) }
            }
            state.charges.forEach { charge ->
                val lat = charge.lat ?: return@forEach
                val lon = charge.lon ?: return@forEach
                allGeoPoints.add(GeoPoint(lat, lon))
            }
            if (allGeoPoints.size >= 2) {
                try {
                    val bbox = BoundingBox.fromGeoPoints(allGeoPoints)
                    map.zoomToBoundingBox(bbox, true, 50)
                } catch (_: Exception) {
                    map.controller.setCenter(center)
                }
            } else if (lastPoint != null) {
                map.controller.setCenter(lastPoint)
            }

            map.invalidate()
        }
    )
}
