package com.bydmate.app.ui.trips

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.ui.components.consumptionColor
import com.bydmate.app.ui.components.formatDuration
import com.bydmate.app.ui.components.formatTime
import com.bydmate.app.ui.theme.*
import android.graphics.Paint as AndroidPaint
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

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
        // Period chips
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TripsChip("День", state.period == TripPeriod.TODAY) { viewModel.setPeriod(TripPeriod.TODAY) }
            TripsChip("Нед", state.period == TripPeriod.WEEK) { viewModel.setPeriod(TripPeriod.WEEK) }
            TripsChip("Мес", state.period == TripPeriod.MONTH) { viewModel.setPeriod(TripPeriod.MONTH) }
            TripsChip("Год", state.period == TripPeriod.YEAR) { viewModel.setPeriod(TripPeriod.YEAR) }
            TripsChip("Всё", state.period == TripPeriod.ALL) { viewModel.setPeriod(TripPeriod.ALL) }
            Spacer(modifier = Modifier.width(12.dp))
            TripsChip("Все", state.filter == TripFilter.ALL) { viewModel.setFilter(TripFilter.ALL) }
            TripsChip("Поездки", state.filter == TripFilter.TRIPS_ONLY) { viewModel.setFilter(TripFilter.TRIPS_ONLY) }
            TripsChip("Стоянки", state.filter == TripFilter.STOPS_ONLY) { viewModel.setFilter(TripFilter.STOPS_ONLY) }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxSize()) {
            // Left: trip list (65%)
            if (state.months.isEmpty()) {
                Column(
                    modifier = Modifier.weight(0.65f).fillMaxHeight(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Нет данных за выбранный период", color = TextSecondary, fontSize = 16.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(0.65f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    for (month in state.months) {
                        item(key = "month_${month.yearMonth}") {
                            MonthHeader(
                                month = month,
                                expanded = month.yearMonth in state.expandedMonths,
                                currencySymbol = state.currencySymbol,
                                onClick = { viewModel.toggleMonth(month.yearMonth) }
                            )
                        }
                        if (month.yearMonth in state.expandedMonths) {
                            for (day in month.days) {
                                item(key = "day_${month.yearMonth}_${day.date}") {
                                    DayHeader(
                                        day = day,
                                        expanded = day.date in state.expandedDays,
                                        currencySymbol = state.currencySymbol,
                                        onClick = { viewModel.toggleDay(day.date) }
                                    )
                                }
                                if (day.date in state.expandedDays) {
                                    item(key = "header_${month.yearMonth}_${day.date}") {
                                        ColumnHeaders(currencySymbol = state.currencySymbol)
                                    }
                                    for (trip in day.trips) {
                                        item(key = "trip_${trip.id}") {
                                            TripRow(
                                                trip = trip,
                                                currencySymbol = state.currencySymbol,
                                                onClick = { viewModel.selectTrip(trip) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Vertical divider
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(1.dp)
                    .background(CardBorder.copy(alpha = 0.5f))
            )

            // Right: chart panel (35%)
            ChartPanel(
                bars = state.chartBars,
                metric = state.chartMetric,
                selectedIndex = state.selectedBarIndex,
                currencySymbol = state.currencySymbol,
                stopsOnly = state.filter == TripFilter.STOPS_ONLY,
                onMetricChange = { viewModel.setChartMetric(it) },
                onBarSelect = { viewModel.selectBar(it) },
                modifier = Modifier.weight(0.35f).fillMaxHeight()
            )
        }
    }

    // Trip detail dialog
    state.selectedTrip?.let { trip ->
        TripDetailDialog(
            trip = trip,
            points = state.selectedTripPoints,
            currencySymbol = state.currencySymbol,
            onDismiss = { viewModel.clearSelectedTrip() }
        )
    }
}

@Composable
private fun MonthHeader(month: MonthGroup, expanded: Boolean, currencySymbol: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .background(CardSurface.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row {
            Text(if (expanded) "▼" else "▶", color = AccentGreen, fontSize = 12.sp)
            Spacer(modifier = Modifier.width(6.dp))
            Text(month.label, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("%.0f км".format(month.totalKm), color = TextSecondary, fontSize = 12.sp,
                fontFamily = FontFamily.Monospace, textAlign = TextAlign.End,
                maxLines = 1, modifier = Modifier.width(80.dp))
            Text("%.0f кВт·ч".format(month.totalKwh), color = TextSecondary, fontSize = 12.sp,
                fontFamily = FontFamily.Monospace, textAlign = TextAlign.End,
                maxLines = 1, modifier = Modifier.width(104.dp))
            Text("%.1f/100".format(month.avgConsumption),
                color = consumptionColor(month.avgConsumption), fontSize = 12.sp,
                fontFamily = FontFamily.Monospace, textAlign = TextAlign.End,
                fontWeight = FontWeight.Medium,
                maxLines = 1, modifier = Modifier.width(72.dp))
            Text("%.2f %s".format(month.totalCost, currencySymbol), color = TextSecondary, fontSize = 12.sp,
                fontFamily = FontFamily.Monospace, textAlign = TextAlign.End,
                maxLines = 1, modifier = Modifier.width(80.dp))
        }
    }
}

@Composable
private fun DayHeader(day: DayGroup, expanded: Boolean, currencySymbol: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .background(CardSurface.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
            .padding(start = 20.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row {
            Text(if (expanded) "▼" else "▶", color = AccentBlue, fontSize = 12.sp)
            Spacer(modifier = Modifier.width(6.dp))
            Text("${day.date} (${day.dayOfWeek})", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("%.1f км".format(day.totalKm), color = TextSecondary, fontSize = 12.sp,
                fontFamily = FontFamily.Monospace, textAlign = TextAlign.End,
                maxLines = 1, modifier = Modifier.width(80.dp))
            Text("%.1f кВт·ч".format(day.totalKwh), color = TextSecondary, fontSize = 12.sp,
                fontFamily = FontFamily.Monospace, textAlign = TextAlign.End,
                maxLines = 1, modifier = Modifier.width(104.dp))
            Text("%.1f/100".format(day.avgConsumption),
                color = consumptionColor(day.avgConsumption), fontSize = 12.sp,
                fontFamily = FontFamily.Monospace, textAlign = TextAlign.End,
                fontWeight = FontWeight.Medium,
                maxLines = 1, modifier = Modifier.width(72.dp))
            Text("%.2f %s".format(day.totalCost, currencySymbol), color = TextSecondary, fontSize = 12.sp,
                fontFamily = FontFamily.Monospace, textAlign = TextAlign.End,
                maxLines = 1, modifier = Modifier.width(80.dp))
        }
    }
}

@Composable
private fun ColumnHeaders(currencySymbol: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 36.dp, end = 12.dp, top = 4.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("время", color = TextMuted, fontSize = 11.sp, modifier = Modifier.width(96.dp))
        Text("длит.", color = TextMuted, fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.width(44.dp))
        Text("км", color = TextMuted, fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.width(48.dp))
        Text("кВт·ч", color = TextMuted, fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.width(44.dp))
        Text("/100", color = TextMuted, fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.width(44.dp))
        Text(currencySymbol.lowercase(), color = TextMuted, fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.width(56.dp))
    }
    HorizontalDivider(color = CardBorder.copy(alpha = 0.5f), thickness = 0.5.dp,
        modifier = Modifier.padding(start = 36.dp, end = 12.dp))
}

@Composable
private fun TripRow(trip: TripEntity, currencySymbol: String, onClick: () -> Unit) {
    val isStop = (trip.distanceKm ?: 0.0) == 0.0
    val time = formatTime(trip.startTs)
    val endTime = trip.endTs?.let { formatTime(it) } ?: ""
    val dist = trip.distanceKm?.let { "%.1f".format(it) } ?: "—"
    val dur = if (trip.endTs != null) formatDuration(trip.startTs, trip.endTs) else "—"
    val kwh = trip.kwhConsumed?.let { "%.1f".format(it) } ?: "—"
    val per100 = trip.kwhPer100km?.let { "%.1f".format(it) } ?: "—"
    val cost = trip.cost?.let { "%.2f".format(it) } ?: "—"
    val consColor = trip.kwhPer100km?.let { consumptionColor(it) } ?: TextSecondary

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(start = 36.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Time range
            Text(
                "$time–$endTime",
                color = if (isStop) TextMuted else TextSecondary,
                fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                maxLines = 1,
                modifier = Modifier.width(96.dp)
            )
            // Duration
            Text(dur, color = if (isStop) TextMuted else TextSecondary,
                fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.End, modifier = Modifier.width(44.dp))
            // Distance
            Text(
                if (isStop) "0.0" else dist,
                color = if (isStop) TextMuted else TextPrimary,
                fontSize = 14.sp, fontFamily = FontFamily.Monospace,
                fontWeight = if (!isStop) FontWeight.Medium else FontWeight.Normal,
                textAlign = TextAlign.End,
                modifier = Modifier.width(48.dp)
            )
            // kWh
            Text(kwh, color = if (isStop) TextMuted else TextSecondary,
                fontSize = 14.sp, fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.End, modifier = Modifier.width(44.dp))
            // /100
            Text(
                if (isStop) "—" else per100,
                color = if (isStop) TextMuted else consColor,
                fontSize = 14.sp, fontFamily = FontFamily.Monospace,
                fontWeight = if (!isStop) FontWeight.Medium else FontWeight.Normal,
                textAlign = TextAlign.End,
                modifier = Modifier.width(44.dp)
            )
            // Cost
            Text(
                "$cost $currencySymbol",
                color = if (isStop) TextMuted else TextSecondary,
                fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.End,
                modifier = Modifier.width(56.dp)
            )
        }
        HorizontalDivider(color = CardBorder.copy(alpha = 0.3f), thickness = 0.5.dp,
            modifier = Modifier.padding(start = 36.dp, end = 12.dp))
    }
}

@Composable
private fun TripsChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 12.sp) },
        shape = RoundedCornerShape(8.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = AccentGreen,
            selectedLabelColor = Color.White,
            containerColor = CardSurface,
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
private fun ChartPanel(
    bars: List<ChartBar>,
    metric: ChartMetric,
    selectedIndex: Int?,
    currencySymbol: String,
    stopsOnly: Boolean,
    onMetricChange: (ChartMetric) -> Unit,
    onBarSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 8.dp)
    ) {
        // Metric chips
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(modifier = Modifier.weight(1f))
            MetricChip("/100", ChartMetric.PER_100, metric, enabled = !stopsOnly, onMetricChange)
            MetricChip("кВтч", ChartMetric.KWH, metric, enabled = true, onMetricChange)
            MetricChip(currencySymbol, ChartMetric.COST, metric, enabled = true, onMetricChange)
            Spacer(modifier = Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (bars.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("Нет данных", color = TextMuted, fontSize = 13.sp)
            }
        } else {
            BarChart(
                bars = bars,
                metric = metric,
                selectedIndex = selectedIndex,
                currencySymbol = currencySymbol,
                onBarSelect = onBarSelect,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun MetricChip(
    label: String,
    metric: ChartMetric,
    current: ChartMetric,
    enabled: Boolean,
    onClick: (ChartMetric) -> Unit
) {
    val selected = metric == current
    FilterChip(
        selected = selected,
        onClick = { if (enabled) onClick(metric) },
        enabled = enabled,
        label = { Text(label, fontSize = 11.sp) },
        shape = RoundedCornerShape(8.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = AccentGreen.copy(alpha = 0.15f),
            selectedLabelColor = AccentGreen,
            containerColor = CardSurface,
            labelColor = TextSecondary,
            disabledContainerColor = CardSurface.copy(alpha = 0.3f),
            disabledLabelColor = TextMuted.copy(alpha = 0.4f)
        ),
        border = FilterChipDefaults.filterChipBorder(
            borderColor = Color.Transparent,
            selectedBorderColor = AccentGreen.copy(alpha = 0.5f),
            enabled = enabled,
            selected = selected
        )
    )
}

@Composable
private fun BarChart(
    bars: List<ChartBar>,
    metric: ChartMetric,
    selectedIndex: Int?,
    currencySymbol: String,
    onBarSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = androidx.compose.ui.platform.LocalDensity.current.density

    val labelPaint = remember {
        AndroidPaint().apply {
            color = 0xFF64748B.toInt()
            textSize = 28f
            textAlign = AndroidPaint.Align.CENTER
            isAntiAlias = true
            typeface = android.graphics.Typeface.MONOSPACE
        }
    }
    val yLabelPaint = remember {
        AndroidPaint().apply {
            color = 0xFF64748B.toInt()
            textSize = 26f
            textAlign = AndroidPaint.Align.RIGHT
            isAntiAlias = true
            typeface = android.graphics.Typeface.MONOSPACE
        }
    }
    val tooltipBgPaint = remember {
        AndroidPaint().apply {
            color = 0xFF1D2940.toInt()
            isAntiAlias = true
        }
    }
    val tooltipTextPaint = remember {
        AndroidPaint().apply {
            color = 0xFF4ADE80.toInt()
            textSize = 30f
            textAlign = AndroidPaint.Align.CENTER
            isAntiAlias = true
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
        }
    }
    val tooltipSubPaint = remember {
        AndroidPaint().apply {
            color = 0xFF94A3B8.toInt()
            textSize = 24f
            textAlign = AndroidPaint.Align.CENTER
            isAntiAlias = true
        }
    }
    val tooltipBorderPaint = remember {
        AndroidPaint().apply {
            color = 0xFF2A3A52.toInt()
            style = AndroidPaint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }
    }

    val maxValue = bars.maxOfOrNull { it.value } ?: 1.0
    val niceMax = niceAxisMax(maxValue)
    val showEveryNth = if (bars.size > 15) (bars.size / 7).coerceAtLeast(2) else 1

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(bars.size, selectedIndex) {
                    detectTapGestures { offset ->
                        val yAxisW = 48f * density
                        val chartLeft = yAxisW
                        val chartWidth = size.width - chartLeft
                        if (offset.x < chartLeft || bars.isEmpty()) {
                            onBarSelect(null)
                            return@detectTapGestures
                        }
                        val barTotalWidth = chartWidth / bars.size
                        val index = ((offset.x - chartLeft) / barTotalWidth).toInt()
                            .coerceIn(0, bars.size - 1)
                        onBarSelect(if (index == selectedIndex) null else index)
                    }
                }
        ) {
            val d = density
            val yAxisWidth = 48f * d
            val bottomPadding = 32f * d
            val topPadding = 52f * d
            val chartLeft = yAxisWidth
            val chartWidth = size.width - chartLeft
            val chartHeight = size.height - bottomPadding - topPadding

            if (chartHeight <= 0 || chartWidth <= 0) return@Canvas

            // Grid lines
            val gridSteps = 4
            for (i in 0..gridSteps) {
                val y = topPadding + chartHeight * (1f - i.toFloat() / gridSteps)
                drawLine(
                    color = ChartGrid,
                    start = Offset(chartLeft, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f
                )
                val labelValue = niceMax * i / gridSteps
                val labelText = if (labelValue >= 10) "%.0f".format(labelValue) else "%.1f".format(labelValue)
                drawContext.canvas.nativeCanvas.drawText(
                    labelText,
                    yAxisWidth - 8f * d,
                    y + 4f * d,
                    yLabelPaint
                )
            }

            // Bars
            val barTotalWidth = chartWidth / bars.size
            val barGap = (2f * d).coerceAtMost(barTotalWidth * 0.15f)
            val barWidth = (barTotalWidth - barGap).coerceAtMost(40f * d)
            val barOffset = (barTotalWidth - barWidth) / 2f

            for ((i, bar) in bars.withIndex()) {
                val barH = if (niceMax > 0) (bar.value / niceMax * chartHeight).toFloat()
                    .coerceAtLeast(2f * d) else 2f * d
                val x = chartLeft + i * barTotalWidth + barOffset
                val y = topPadding + chartHeight - barH

                val alpha = if (selectedIndex == null) 0.75f
                else if (i == selectedIndex) 1.0f else 0.35f

                drawRoundRect(
                    color = AccentGreen.copy(alpha = alpha),
                    topLeft = Offset(x, y),
                    size = Size(barWidth, barH),
                    cornerRadius = CornerRadius(3f * d, 3f * d)
                )

                // X axis label
                if (i % showEveryNth == 0 || i == bars.size - 1) {
                    drawContext.canvas.nativeCanvas.drawText(
                        bar.label,
                        chartLeft + i * barTotalWidth + barTotalWidth / 2f,
                        size.height - 4f * d,
                        labelPaint
                    )
                }
            }

            // Tooltip
            if (selectedIndex != null && selectedIndex in bars.indices) {
                val bar = bars[selectedIndex]
                val barCenterX = chartLeft + selectedIndex * barTotalWidth + barTotalWidth / 2f
                val barH = if (niceMax > 0) (bar.value / niceMax * chartHeight).toFloat() else 0f
                val barTopY = topPadding + chartHeight - barH

                val valueText = when (metric) {
                    ChartMetric.PER_100 -> "%.1f /100".format(bar.value)
                    ChartMetric.KWH -> "%.1f кВтч".format(bar.value)
                    ChartMetric.COST -> "%.2f $currencySymbol".format(bar.value)
                }
                val countText = "${bar.tripCount} поезд."

                val tooltipW = 130f * d
                val tooltipH = 48f * d
                val tooltipX = (barCenterX - tooltipW / 2f)
                    .coerceIn(chartLeft, size.width - tooltipW)
                val tooltipY = (barTopY - tooltipH - 8f * d)
                    .coerceAtLeast(4f * d)

                drawContext.canvas.nativeCanvas.drawRoundRect(
                    tooltipX, tooltipY,
                    tooltipX + tooltipW, tooltipY + tooltipH,
                    8f * d, 8f * d,
                    tooltipBgPaint
                )
                drawContext.canvas.nativeCanvas.drawRoundRect(
                    tooltipX, tooltipY,
                    tooltipX + tooltipW, tooltipY + tooltipH,
                    8f * d, 8f * d,
                    tooltipBorderPaint
                )
                drawContext.canvas.nativeCanvas.drawText(
                    valueText,
                    tooltipX + tooltipW / 2f,
                    tooltipY + 22f * d,
                    tooltipTextPaint
                )
                drawContext.canvas.nativeCanvas.drawText(
                    countText,
                    tooltipX + tooltipW / 2f,
                    tooltipY + 40f * d,
                    tooltipSubPaint
                )
            }
        }
    }
}

private fun niceAxisMax(value: Double): Double {
    if (value <= 0) return 1.0
    val magnitude = 10.0.pow(floor(log10(value)))
    val normalized = value / magnitude
    val nice = when {
        normalized <= 1.0 -> 1.0
        normalized <= 2.0 -> 2.0
        normalized <= 5.0 -> 5.0
        else -> 10.0
    }
    return nice * magnitude
}
