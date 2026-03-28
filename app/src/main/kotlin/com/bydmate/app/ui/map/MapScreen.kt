package com.bydmate.app.ui.map

import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

private val BackgroundColor = Color(0xFF0D0D0D)
private val PrimaryColor = Color(0xFF4CAF50)
private val SecondaryTextColor = Color(0xFF9E9E9E)
private val ChargeMarkerColor = Color(0xFF2196F3)

// Map screen - GPS route display via osmdroid (no GMS)
@Composable
fun MapScreen(
    viewModel: MapViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Configure osmdroid user agent
    Configuration.getInstance().userAgentValue = "BYDMate/0.1"

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundColor)
    ) {
        if (state.isLoading && state.tripPoints.isEmpty() && state.charges.isEmpty()) {
            // Loading indicator
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = PrimaryColor)
            }
        } else if (state.tripPoints.isEmpty() && state.charges.isEmpty()) {
            // Empty state
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Нет данных за последнюю неделю",
                    color = SecondaryTextColor,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        } else {
            // Full-screen osmdroid MapView
            OsmdroidMapView(state = state)
        }
    }
}

/**
 * Wraps osmdroid MapView in an AndroidView composable.
 * Draws trip route polylines in green and charge location markers in blue.
 */
@Composable
private fun OsmdroidMapView(state: MapUiState) {
    val context = LocalContext.current

    // Find the last known point to center the map on
    val lastPoint = remember(state.tripPoints) {
        state.tripPoints.lastOrNull()?.lastOrNull()?.let {
            GeoPoint(it.lat, it.lon)
        }
    }

    // Default center: Shenzhen (BYD HQ area) if no trip data
    val defaultCenter = GeoPoint(22.5431, 114.0579)
    val center = lastPoint ?: defaultCenter

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)

            // Dark overlay to match app theme
            overlayManager.tilesOverlay.setColorFilter(
                android.graphics.ColorMatrixColorFilter(
                    floatArrayOf(
                        -1f, 0f, 0f, 0f, 255f,  // invert red
                        0f, -1f, 0f, 0f, 255f,   // invert green
                        0f, 0f, -1f, 0f, 255f,   // invert blue
                        0f, 0f, 0f, 1f, 0f       // keep alpha
                    )
                )
            )

            // Initial zoom and center
            controller.setZoom(13.0)
            controller.setCenter(center)
        }
    }

    // Clean up MapView lifecycle
    DisposableEffect(Unit) {
        onDispose {
            mapView.onDetach()
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = Modifier.fillMaxSize(),
        update = { map ->
            // Clear previous overlays (keep tile overlay at index 0)
            val tileOverlay = map.overlayManager.tilesOverlay
            map.overlays.clear()

            // Draw trip route polylines
            for (route in state.tripPoints) {
                if (route.size < 2) continue

                val polyline = Polyline().apply {
                    outlinePaint.color = PrimaryColor.toArgb()
                    outlinePaint.strokeWidth = 6f
                    outlinePaint.isAntiAlias = true
                    // Set info window to null to avoid crashes on tap
                    infoWindow = null
                }

                val geoPoints = route.map { point ->
                    GeoPoint(point.lat, point.lon)
                }
                polyline.setPoints(geoPoints)
                map.overlays.add(polyline)
            }

            // Add charge location markers
            for (charge in state.charges) {
                val lat = charge.lat ?: continue
                val lon = charge.lon ?: continue

                val marker = Marker(map).apply {
                    position = GeoPoint(lat, lon)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = buildString {
                        append("Зарядка")
                        charge.type?.let { append(" ($it)") }
                        charge.kwhCharged?.let { append("\n%.1f кВт·ч".format(it)) }
                    }
                    // Use default marker icon (blue tint applied below if possible)
                    // osmdroid default marker is sufficient for now
                }
                map.overlays.add(marker)
            }

            // Center on last known point
            if (lastPoint != null) {
                map.controller.setCenter(lastPoint)
            }

            map.invalidate()
        }
    )
}
