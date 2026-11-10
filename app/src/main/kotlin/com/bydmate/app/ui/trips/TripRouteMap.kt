package com.bydmate.app.ui.trips

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.bydmate.app.R
import com.bydmate.app.ui.theme.*
import com.bydmate.app.BYDMateApp
import com.bydmate.app.util.Gcj02Converter
import androidx.compose.foundation.layout.size
import android.util.Log
import androidx.compose.ui.graphics.Path
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.abs

/** Border of the route fit, px, as before. */
private const val FIT_BORDER_PX = 48

@Composable
internal fun TripRouteMap(
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
