package com.bydmate.app.ui.places

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.widget.Toast
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bydmate.app.R
import com.bydmate.app.data.local.entity.PlaceEntity
import com.bydmate.app.service.TrackingService
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.CardBorder
import com.bydmate.app.ui.theme.CardSurface
import com.bydmate.app.ui.theme.TextMuted
import com.bydmate.app.ui.theme.TextPrimary
import com.bydmate.app.ui.theme.TextSecondary
import com.bydmate.app.BYDMateApp
import com.bydmate.app.util.Gcj02Converter
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

/**
 * Parse a coordinate the user sees. The text fields are seeded/updated with the device-locale
 * format ("%.6f"), which yields a comma decimal separator in ru/be ("52,069746"); a bare
 * toDoubleOrNull() returns null on the comma, so both validation and the map re-centre would fail.
 * Normalise the comma to a dot first (same fix as ChargeEditDialog / parseNumericSetting).
 */
internal fun parseCoordinate(text: String): Double? = text.replace(',', '.').trim().toDoubleOrNull()

@Composable
fun PlaceEditDialog(
    initial: PlaceEntity?,
    tileSource: String = "osm",
    onDismiss: () -> Unit,
    onSave: (id: Long?, name: String, lat: Double, lon: Double, radiusM: Int) -> Unit
) {
    var nameText by rememberSaveable { mutableStateOf(initial?.name ?: "") }
    var latText by rememberSaveable { mutableStateOf(if (initial != null) initial.lat.toString() else "") }
    var lonText by rememberSaveable { mutableStateOf(if (initial != null) initial.lon.toString() else "") }
    var radiusText by rememberSaveable { mutableStateOf(initial?.radiusM?.toString() ?: "50") }

    // Observe live GPS location from TrackingService
    val lastLocation by TrackingService.lastLocation.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Resolve current GPS location: TrackingService → LocationManager → 深圳 fallback
    val currentLocation = remember { mutableStateOf<Pair<Double, Double>?>(null) }

    // Update when TrackingService provides a fix
    LaunchedEffect(lastLocation) {
        lastLocation?.let { currentLocation.value = it.latitude to it.longitude }
    }

    // If no live location, try Android LocationManager once
    LaunchedEffect(Unit) {
        if (currentLocation.value == null) {
            val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
            for (p in listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER)) {
                try {
                    @Suppress("MissingPermission")
                    val loc = lm.getLastKnownLocation(p)
                    if (loc != null) {
                        currentLocation.value = loc.latitude to loc.longitude
                        break
                    }
                } catch (_: SecurityException) { }
            }
        }
    }

    // Seed text fields once when location becomes available
    val loc = currentLocation.value
    val isAmap = tileSource == "amap"
    val fbLat = loc?.first  ?: if (isAmap) 22.5431 else 55.7539   // 深圳 / 莫斯科
    val fbLon = loc?.second ?: if (isAmap) 114.0579 else 37.6208
    LaunchedEffect(loc) {
        if (initial == null && latText.isEmpty() && lonText.isEmpty() && loc != null) {
            latText = "%.6f".format(loc.first)
            lonText = "%.6f".format(loc.second)
        }
    }

    // Validation
    val nameValid = nameText.trim().isNotBlank() && nameText.trim().length <= 40
    val latValue = parseCoordinate(latText)
    val latValid = latValue != null && latValue in -90.0..90.0
    val lonValue = parseCoordinate(lonText)
    val lonValid = lonValue != null && lonValue in -180.0..180.0
    val radiusValid = radiusText.toIntOrNull() != null
    val canSave = nameValid && latValid && lonValid && radiusValid

    // Effective map coords (fallback to GPS/Moscow when text fields are unparseable)
    val effLat = parseCoordinate(latText) ?: fallback.first
    val effLon = parseCoordinate(lonText) ?: fallback.second
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

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false),
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CardSurface),
            modifier = Modifier.fillMaxWidth(0.9f),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = if (initial == null) stringResource(R.string.place_edit_dialog_title_new) else stringResource(R.string.place_edit_dialog_title_edit),
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(12.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Name field
                    OutlinedTextField(
                        value = nameText,
                        onValueChange = { if (it.length <= 40) nameText = it },
                        label = { Text(stringResource(R.string.place_edit_name_label)) },
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
                        tileSource = tileSource,
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
                            label = { Text(stringResource(R.string.place_edit_lat_label)) },
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
                            label = { Text(stringResource(R.string.place_edit_lon_label)) },
                            singleLine = true,
                            isError = lonText.isNotEmpty() && !lonValid,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            shape = RoundedCornerShape(8.dp),
                            colors = fieldColors,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Radius field
                    OutlinedTextField(
                        value = radiusText,
                        onValueChange = { radiusText = it },
                        label = { Text(stringResource(R.string.place_edit_radius_label)) },
                        singleLine = true,
                        isError = radiusText.isNotEmpty() && !radiusValid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(8.dp),
                        colors = fieldColors
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.settings_cancel_button), color = TextSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            if (canSave) {
                                val raw = radiusText.toInt()
                                if (raw < 20) {
                                    Toast.makeText(context, context.getString(R.string.place_edit_error_min_radius), Toast.LENGTH_SHORT).show()
                                }
                                val radius = raw.coerceIn(20, 500)
                                onSave(initial?.id, nameText.trim(), latValue!!, lonValue!!, radius)
                            }
                        },
                        enabled = canSave
                    ) {
                        Text(stringResource(R.string.charges_edit_save_button), color = if (canSave) AccentGreen else TextMuted)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlacePickerMap(
    lat: Double,
    lon: Double,
    radiusM: Int,
    tileSource: String,
    onPick: (Double, Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isAmap = tileSource == "amap"

    // Convert WGS-84 to display coords for Amap alignment
    val (displayLat, displayLon) = if (isAmap) Gcj02Converter.wgs84ToGcj02(lat, lon) else lat to lon

    val mapView = remember(tileSource) {
        MapView(context).apply {
            setTileSource(
                if (isAmap) BYDMateApp.AmapTileSource else TileSourceFactory.MAPNIK
            )
            setMultiTouchControls(true)
            zoomController.setVisibility(
                org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER
            )
            controller.setZoom(16.0)
        }
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
        factory = { mapView },
        modifier = modifier,
        update = { map ->
            map.overlays.clear()

            val center = GeoPoint(displayLat, displayLon)

            // Tap handler — osmdroid returns coordinates in the tile source's
            // coordinate system, so for Amap we must reverse-convert back to WGS-84.
            val receiver = object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                    val (wgsLat, wgsLon) = if (isAmap) Gcj02Converter.gcj02ToWgs84(p.latitude, p.longitude)
                    else p.latitude to p.longitude
                    onPick(wgsLat, wgsLon)
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
    } // key(tileSource)
}
