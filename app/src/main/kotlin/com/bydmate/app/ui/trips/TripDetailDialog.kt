package com.bydmate.app.ui.trips

import android.graphics.Paint
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
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
import com.bydmate.app.ui.theme.*
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline

@Composable
fun TripDetailDialog(
    trip: TripEntity,
    points: List<TripPointEntity>,
    currencySymbol: String,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    indication = null,
                    interactionSource = androidx.compose.foundation.interaction.MutableInteractionSource()
                ) { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardSurface),
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .fillMaxHeight(0.85f)
                    .clickable { /* absorb click */ }
            ) {
                val isStop = (trip.distanceKm ?: 0.0) == 0.0

                // Landscape: map left, stats right
                Row(modifier = Modifier.fillMaxSize()) {
                    // LEFT: Map + speed histogram
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Header
                        Text(
                            if (isStop) "Стоянка" else "Поездка",
                            color = AccentGreen,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "${formatTime(trip.startTs)}${trip.endTs?.let { " – ${formatTime(it)}" } ?: ""}",
                            color = TextSecondary,
                            fontSize = 14.sp
                        )

                        // Map in its own clipped box
                        if (points.size >= 2) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                            ) {
                                TripRouteMap(
                                    points = points,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .background(NavyDark, RoundedCornerShape(8.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("GPS-данные недоступны", color = TextMuted, fontSize = 13.sp)
                            }
                        }

                        // Speed histogram
                        if (points.size >= 4) {
                            SpeedHistogram(
                                points = points,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(60.dp)
                            )
                        }
                    }

                    // RIGHT: Stats
                    Column(
                        modifier = Modifier
                            .width(220.dp)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("Статистика", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)

                        trip.distanceKm?.let { DetailRow("Дистанция", "%.1f км".format(it)) }
                        if (trip.endTs != null) DetailRow("Длительность", formatDuration(trip.startTs, trip.endTs))
                        trip.avgSpeedKmh?.let { DetailRow("Ср. скорость", "%.0f км/ч".format(it)) }
                        if (points.isNotEmpty()) {
                            val maxSpeed = points.maxOfOrNull { it.speedKmh ?: 0.0 } ?: 0.0
                            if (maxSpeed > 0) DetailRow("Макс. скорость", "%.0f км/ч".format(maxSpeed))
                        }
                        trip.kwhConsumed?.let {
                            DetailRow("Потребление", "%.1f кВт·ч".format(it))
                            trip.kwhPer100km?.let { per100 ->
                                DetailRow("Расход", "%.1f/100".format(per100), consumptionColor(per100))
                            }
                        }
                        if (trip.socStart != null && trip.socEnd != null) {
                            DetailRow("SOC", "${trip.socStart}% → ${trip.socEnd}%")
                        }
                        trip.cost?.let { DetailRow("Стоимость", "%.2f %s".format(it, currencySymbol), AccentGreen) }
                        trip.exteriorTemp?.let { DetailRow("Темп.", "${it}°C") }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, valueColor: Color = TextPrimary) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = TextSecondary, fontSize = 13.sp)
        Text(value, color = valueColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun TripRouteMap(points: List<TripPointEntity>, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    val geoPoints = remember(points) {
        points.map { GeoPoint(it.lat, it.lon) }
    }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(
                org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER
            )
        }
    }

    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { map ->
            map.overlays.clear()

            if (geoPoints.size >= 2) {
                // Dark outline underneath for contrast on any road color
                for (i in 0 until points.size - 1) {
                    val outline = Polyline().apply {
                        setPoints(listOf(geoPoints[i], geoPoints[i + 1]))
                        outlinePaint.color = android.graphics.Color.argb(180, 0, 0, 0)
                        outlinePaint.strokeWidth = 14f
                        outlinePaint.isAntiAlias = true
                        outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                        infoWindow = null
                    }
                    map.overlays.add(outline)
                }
                // Bright speed-colored track on top
                for (i in 0 until points.size - 1) {
                    val speed = points[i].speedKmh ?: 0.0
                    val color = speedColor(speed)

                    val segment = Polyline().apply {
                        setPoints(listOf(geoPoints[i], geoPoints[i + 1]))
                        outlinePaint.color = color.toArgb()
                        outlinePaint.strokeWidth = 8f
                        outlinePaint.isAntiAlias = true
                        outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                        infoWindow = null
                    }
                    map.overlays.add(segment)
                }

                // Zoom to fit route
                try {
                    val bbox = BoundingBox.fromGeoPoints(geoPoints)
                    map.post { map.zoomToBoundingBox(bbox, true, 48) }
                } catch (_: Exception) {
                    map.controller.setCenter(geoPoints.first())
                }
            }

            map.invalidate()
        }
    )
}

@Composable
private fun SpeedHistogram(points: List<TripPointEntity>, modifier: Modifier = Modifier) {
    val speeds = remember(points) {
        val step = (points.size / 40).coerceAtLeast(1)
        points.filterIndexed { i, _ -> i % step == 0 }.mapNotNull { it.speedKmh }
    }

    if (speeds.isEmpty()) return
    val maxSpeed = speeds.max()

    Canvas(modifier = modifier) {
        if (maxSpeed <= 0.0) return@Canvas

        val barCount = speeds.size
        val barWidth = size.width / barCount
        val chartHeight = size.height - 20f

        speeds.forEachIndexed { index, speed ->
            val barHeight = (speed / maxSpeed * chartHeight).toFloat()
            val color = speedColor(speed)

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

private fun speedColor(speed: Double): Color = when {
    speed < 20 -> Color(0xFFFF1744.toInt())  // bright red — distinct from map's pink roads
    speed < 60 -> Color(0xFFFFFF8D.toInt())  // pale yellow — distinct from map's orange roads
    else -> Color(0xFF00E676.toInt())         // bright green — distinct from map's muted greens
}
