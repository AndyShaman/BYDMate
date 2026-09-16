package com.bydmate.app.ui.trips

import android.graphics.Paint as AndroidPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bydmate.app.R
import com.bydmate.app.domain.trips.TripTemperatureStats
import com.bydmate.app.ui.components.consumptionColor
import com.bydmate.app.ui.tech.SectionHeader
import com.bydmate.app.ui.tech.TechCard
import com.bydmate.app.ui.tech.TechRow
import com.bydmate.app.ui.theme.AccentBlue
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.ChartGrid
import com.bydmate.app.ui.theme.NavyDark
import com.bydmate.app.ui.theme.NavyDeep
import com.bydmate.app.ui.theme.TextMuted
import com.bydmate.app.ui.theme.TextPrimary
import com.bydmate.app.ui.theme.TextSecondary

private const val DASH = "—"

/** Card header, paddings and the narrow-range note under the chart. */
private val CARD_CHROME_HEIGHT = 56.dp

/**
 * «Расход и температура» — the full-screen picture behind the «Температура» chip on «Поездки»,
 * built like the «Техника» panel: same back circle, same cards, closed the same way. The chart
 * is on the left at full height, the numbers in a card on the right; every number comes out of
 * [TripTemperatureStats], this file only formats them.
 */
@Composable
fun TripTemperatureScreen(
    onBack: () -> Unit,
    viewModel: TripTemperatureViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val stats = state.stats

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(NavyDark, NavyDeep)))
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Header(onBack = onBack)

        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            BoxWithConstraints(modifier = Modifier.weight(0.68f).fillMaxHeight()) {
                // The card's own header and padding eat a fixed slice; the canvas takes the
                // rest, because the card content column cannot hand it a weight.
                ChartCard(stats, chartHeight = (maxHeight - CARD_CHROME_HEIGHT).coerceAtLeast(120.dp))
            }
            Box(modifier = Modifier.weight(0.32f)) {
                StatsCard(stats)
            }
        }
    }
}

/** Same shape as the «Техника» header: back circle, title, and the sample count on the right. */
@Composable
private fun Header(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .border(1.5.dp, TextMuted, CircleShape)
                .clickable { onBack() },
            contentAlignment = Alignment.Center
        ) {
            Text("‹", color = TextSecondary, fontSize = 16.sp)
        }
        Text(
            stringResource(R.string.trips_temp_title),
            color = AccentGreen,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 14.dp)
        )
        Box(modifier = Modifier.weight(1f))
        Text(
            stringResource(R.string.trips_temp_period_all),
            color = TextMuted,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun ChartCard(stats: TripTemperatureStats, chartHeight: Dp) {
    TechCard(stringResource(R.string.trips_temp_card_chart), modifier = Modifier.fillMaxHeight()) {
        if (stats.state == TripTemperatureStats.State.NOT_ENOUGH) {
            Text(
                stringResource(
                    R.string.trips_temp_not_enough,
                    TripTemperatureStats.MIN_TRIPS,
                    stats.tripsWithTemp,
                ),
                color = TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 12.dp),
            )
            return@TechCard
        }
        Box(modifier = Modifier.fillMaxWidth().height(chartHeight).padding(top = 8.dp)) {
            ScatterChart(stats)
        }
        if (stats.state == TripTemperatureStats.State.NARROW_RANGE) {
            stats.observedRange?.let { (lo, hi) ->
                Text(
                    stringResource(R.string.trips_temp_narrow_note, signed(lo), signed(hi)),
                    color = TextMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/**
 * Scatter of trip consumption against outside temperature, on the same dark grid as the trips
 * bar chart: monospace axis labels, one dot per trip, a median line only when the data is wide
 * enough to carry one.
 */
@Composable
private fun ScatterChart(stats: TripTemperatureStats) {
    val density = LocalDensity.current.density
    val labelColor = TextMuted.toArgb()
    val pointColors = stats.points.map { consumptionColor(it.kwhPer100km) }
    val trendColor = AccentBlue

    Canvas(modifier = Modifier.fillMaxSize()) {
        val axisPaint = AndroidPaint().apply {
            color = labelColor
            textSize = 11f * density
            isAntiAlias = true
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val leftPad = 40f * density
        val bottomPad = 22f * density
        val topPad = 6f * density
        val plotWidth = size.width - leftPad
        val plotHeight = size.height - bottomPad - topPad
        if (plotWidth <= 0f || plotHeight <= 0f) return@Canvas

        val xRange = stats.tempAxis
        val yRange = stats.consumptionAxis
        fun xOf(tempC: Double): Float =
            leftPad + (((tempC - xRange.min) / (xRange.max - xRange.min)) * plotWidth).toFloat()
        fun yOf(kwh: Double): Float =
            topPad + plotHeight - (((kwh - yRange.min) / (yRange.max - yRange.min)) * plotHeight).toFloat()

        // Horizontal grid every 5 kWh/100 km, labelled on the left.
        var kwh = yRange.min
        while (kwh <= yRange.max + 0.001) {
            val y = yOf(kwh)
            drawLine(ChartGrid, Offset(leftPad, y), Offset(size.width, y), strokeWidth = 1f)
            axisPaint.textAlign = AndroidPaint.Align.RIGHT
            drawContext.canvas.nativeCanvas.drawText(
                "%.0f".format(kwh), leftPad - 6f * density, y + 4f * density, axisPaint
            )
            kwh += 5.0
        }

        // Temperature ticks every 10 °C along the bottom.
        var temp = xRange.min
        while (temp <= xRange.max + 0.001) {
            val x = xOf(temp)
            axisPaint.textAlign = AndroidPaint.Align.CENTER
            drawContext.canvas.nativeCanvas.drawText(
                "%.0f".format(temp), x, size.height - 6f * density, axisPaint
            )
            temp += 10.0
        }

        // The trips themselves; a long drive gets the bigger dot.
        stats.points.forEachIndexed { i, p ->
            drawCircle(
                color = pointColors[i].copy(alpha = 0.85f),
                radius = if (p.big) 5f * density else 3.2f * density,
                center = Offset(xOf(p.tempC), yOf(p.kwhPer100km)),
            )
        }

        // Median per 5 °C bin.
        stats.trend.zipWithNext { a, b ->
            drawLine(
                color = trendColor,
                start = Offset(xOf(a.tempC), yOf(a.kwhPer100km)),
                end = Offset(xOf(b.tempC), yOf(b.kwhPer100km)),
                strokeWidth = 2f * density,
            )
        }
    }
}

@Composable
private fun StatsCard(stats: TripTemperatureStats) {
    TechCard(stringResource(R.string.trips_temp_card_stats)) {
        TechRow(stringResource(R.string.trips_temp_count), "${stats.tripsWithTemp}")
        MedianRow(R.string.trips_temp_median_cold, stats.medianCold)
        MedianRow(R.string.trips_temp_median_mild, stats.medianMild)
        MedianRow(R.string.trips_temp_median_warm, stats.medianWarm)
        TechRow(
            stringResource(R.string.trips_temp_winter_diff),
            stats.winterHigherPercent?.let { stringResource(R.string.trips_temp_percent, signed(it)) } ?: DASH,
            valueColor = if (stats.winterHigherPercent != null) AccentGreen else TextPrimary,
        )
        Box(modifier = Modifier.padding(top = 10.dp)) {
            SectionHeader(stringResource(R.string.trips_temp_verdict_header))
        }
        Text(verdictText(stats), color = TextSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun MedianRow(labelRes: Int, median: Double?) {
    TechRow(
        stringResource(labelRes),
        median?.let { stringResource(R.string.trips_temp_kwh_per_100, it) } ?: DASH,
        valueColor = median?.let { consumptionColor(it) } ?: TextPrimary,
    )
}

@Composable
private fun verdictText(stats: TripTemperatureStats): String = when (stats.verdict) {
    TripTemperatureStats.Verdict.WINTER_HIGHER ->
        stringResource(R.string.trips_temp_verdict_winter, stats.winterHigherPercent ?: 0)
    TripTemperatureStats.Verdict.WINTER_NOT_HIGHER ->
        stringResource(R.string.trips_temp_verdict_not_higher, signed(stats.winterHigherPercent ?: 0))
    TripTemperatureStats.Verdict.NARROW_RANGE -> {
        val range = stats.observedRange
        if (range == null) stringResource(R.string.trips_temp_verdict_none)
        else stringResource(R.string.trips_temp_verdict_narrow, signed(range.first), signed(range.second))
    }
    TripTemperatureStats.Verdict.NONE -> stringResource(R.string.trips_temp_verdict_none)
}

/** «+10» / «−5» — the sign is part of the sentence, so it is written out. */
private fun signed(temp: Int): String = if (temp > 0) "+$temp" else "$temp"
