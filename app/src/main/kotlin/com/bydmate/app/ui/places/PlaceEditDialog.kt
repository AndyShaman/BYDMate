package com.bydmate.app.ui.places

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.bydmate.app.data.local.entity.PlaceEntity
import com.bydmate.app.service.TrackingService
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.CardBorder
import com.bydmate.app.ui.theme.CardSurface
import com.bydmate.app.ui.theme.TextMuted
import com.bydmate.app.ui.theme.TextPrimary
import com.bydmate.app.ui.theme.TextSecondary
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

@Composable
fun PlaceEditDialog(
    initial: PlaceEntity?,
    onDismiss: () -> Unit,
    onSave: (id: Long?, name: String, lat: Double, lon: Double, radiusM: Int) -> Unit
) {
    var nameText by remember { mutableStateOf(initial?.name ?: "") }
    var latText by remember { mutableStateOf(if (initial != null) initial.lat.toString() else "") }
    var lonText by remember { mutableStateOf(if (initial != null) initial.lon.toString() else "") }
    var radiusText by remember { mutableStateOf(initial?.radiusM?.toString() ?: "50") }

    // Fallback centre: last GPS fix or Moscow Red Square
    val fallback = remember {
        TrackingService.lastLocation.value?.let { it.latitude to it.longitude }
            ?: (55.7539 to 37.6208)
    }

    // Seed text fields from GPS fallback when adding a new place
    LaunchedEffect(Unit) {
        if (initial == null && latText.isEmpty() && lonText.isEmpty()) {
            latText = "%.6f".format(fallback.first)
            lonText = "%.6f".format(fallback.second)
        }
    }

    // Validation
    val nameValid = nameText.trim().isNotBlank() && nameText.trim().length <= 40
    val latValue = latText.toDoubleOrNull()
    val latValid = latValue != null && latValue in -90.0..90.0
    val lonValue = lonText.toDoubleOrNull()
    val lonValid = lonValue != null && lonValue in -180.0..180.0
    val radiusValid = radiusText.toIntOrNull() != null
    val canSave = nameValid && latValid && lonValid && radiusValid

    // Effective map coords (fallback to GPS/Moscow when text fields are unparseable)
    val effLat = latText.toDoubleOrNull() ?: fallback.first
    val effLon = lonText.toDoubleOrNull() ?: fallback.second
    val effR = radiusText.toIntOrNull()?.coerceIn(20, 500) ?: 50

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = TextPrimary,
        unfocusedTextColor = TextPrimary,
        focusedBorderColor = AccentGreen,
        unfocusedBorderColor = CardBorder,
        focusedLabelColor = AccentGreen,
        unfocusedLabelColor = TextSecondary,
        cursorColor = AccentGreen
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardSurface,
        title = {
            Text(
                text = if (initial == null) "Новое место" else "Редактировать место",
                color = TextPrimary,
                fontSize = 16.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Name field
                OutlinedTextField(
                    value = nameText,
                    onValueChange = { if (it.length <= 40) nameText = it },
                    label = { Text("Название") },
                    singleLine = true,
                    isError = nameText.isNotEmpty() && !nameValid,
                    shape = RoundedCornerShape(8.dp),
                    colors = fieldColors
                )

                // Map picker — tap to set coordinates
                PlacePickerMap(
                    lat = effLat,
                    lon = effLon,
                    radiusM = effR,
                    onPick = { lat, lon ->
                        latText = "%.6f".format(lat)
                        lonText = "%.6f".format(lon)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(8.dp))
                )

                // Lat / Lon fields side by side
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = latText,
                        onValueChange = { latText = it },
                        label = { Text("Широта") },
                        singleLine = true,
                        isError = latText.isNotEmpty() && !latValid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        shape = RoundedCornerShape(8.dp),
                        colors = fieldColors,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = lonText,
                        onValueChange = { lonText = it },
                        label = { Text("Долгота") },
                        singleLine = true,
                        isError = lonText.isNotEmpty() && !lonValid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        shape = RoundedCornerShape(8.dp),
                        colors = fieldColors,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(0.dp))

                // Radius field
                OutlinedTextField(
                    value = radiusText,
                    onValueChange = { radiusText = it },
                    label = { Text("Радиус, м") },
                    singleLine = true,
                    isError = radiusText.isNotEmpty() && !radiusValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(8.dp),
                    colors = fieldColors
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (canSave) {
                        val radius = radiusText.toInt().coerceIn(20, 500)
                        onSave(initial?.id, nameText.trim(), latValue!!, lonValue!!, radius)
                    }
                },
                enabled = canSave
            ) {
                Text("Сохранить", color = if (canSave) AccentGreen else TextMuted)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена", color = TextSecondary)
            }
        }
    )
}

@Composable
private fun PlacePickerMap(
    lat: Double,
    lon: Double,
    radiusM: Int,
    onPick: (Double, Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(
                org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER
            )
            controller.setZoom(16.0)
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

            val center = GeoPoint(lat, lon)

            // Tap handler — updates parent state via onPick callback
            val receiver = object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                    onPick(p.latitude, p.longitude)
                    return true
                }
                override fun longPressHelper(p: GeoPoint): Boolean = false
            }
            map.overlays.add(MapEventsOverlay(receiver))

            // Radius ring (polygon approximation of a circle)
            if (radiusM > 0) {
                val circle = Polygon().apply {
                    points = Polygon.pointsAsCircle(center, radiusM.toDouble())
                    fillPaint.color = android.graphics.Color.argb(60, 64, 220, 120)
                    outlinePaint.color = android.graphics.Color.argb(180, 64, 220, 120)
                    outlinePaint.strokeWidth = 3f
                    outlinePaint.isAntiAlias = true
                }
                map.overlays.add(circle)
            }

            // Marker at current point
            val marker = Marker(map).apply {
                position = center
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = null
                setInfoWindow(null)
            }
            map.overlays.add(marker)

            // Re-centre whenever lat/lon change
            map.controller.setCenter(center)
            map.invalidate()
        }
    )
}
