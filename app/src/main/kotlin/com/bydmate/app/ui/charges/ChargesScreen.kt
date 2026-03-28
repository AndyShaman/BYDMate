package com.bydmate.app.ui.charges

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bydmate.app.data.local.entity.ChargePointEntity
import com.bydmate.app.ui.components.ChargeCard

// -- Color constants matching dark theme --

private val BackgroundColor = Color(0xFF0D0D0D)
private val CardBackground = Color(0xFF1E1E1E)
private val CardBackgroundAlt = Color(0xFF2C2C2C)
private val TextSecondary = Color(0xFF9E9E9E)
private val AccentGreen = Color(0xFF4CAF50)
private val AccentBlue = Color(0xFF2196F3)
private val AccentOrange = Color(0xFFFF9800)
private val ChartLineColor = Color(0xFF4CAF50)
private val ChartGridColor = Color(0xFF333333)

// Charges screen - list of charging sessions with energy/cost stats
@Composable
fun ChargesScreen(
    viewModel: ChargesViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundColor)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // -- Screen title --
        Text(
            text = "Зарядки",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        // -- Period toggle and type filter chips --
        PeriodAndFilterRow(
            currentPeriod = state.periodLabel,
            typeFilter = state.typeFilter,
            onPeriodWeek = { viewModel.setPeriod(Period.WEEK) },
            onPeriodMonth = { viewModel.setPeriod(Period.MONTH) },
            onTypeFilter = { viewModel.setTypeFilter(it) }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // -- Summary row: sessions, kWh, cost --
        ChargeSummaryRow(
            sessionCount = state.sessionCount,
            totalKwh = state.totalKwh,
            totalCost = state.totalCost,
            currencySymbol = state.currencySymbol
        )

        Spacer(modifier = Modifier.height(16.dp))

        // -- Period label --
        Text(
            text = state.periodLabel,
            color = TextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.height(8.dp))

        // -- Charges list --
        if (state.charges.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Зарядок за этот период нет",
                    color = TextSecondary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(
                    items = state.charges,
                    key = { it.id }
                ) { charge ->
                    Column {
                        ChargeCard(
                            charge = charge,
                            onClick = { viewModel.toggleExpanded(charge.id) },
                            currencySymbol = state.currencySymbol
                        )

                        // Expanded power curve chart
                        AnimatedVisibility(
                            visible = state.expandedChargeId == charge.id,
                            enter = expandVertically(),
                            exit = shrinkVertically()
                        ) {
                            PowerCurveChart(
                                points = state.expandedChargePoints,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp)
                            )
                        }
                    }
                }

                // Bottom spacing for nav bar clearance
                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }
}

/** Period toggle buttons and type filter chips row. */
@Composable
private fun PeriodAndFilterRow(
    currentPeriod: String,
    typeFilter: String?,
    onPeriodWeek: () -> Unit,
    onPeriodMonth: () -> Unit,
    onTypeFilter: (String?) -> Unit
) {
    // Determine active period from label
    val isWeek = currentPeriod.startsWith("Неделя")

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Period toggle chips
        FilterChip(
            selected = isWeek,
            onClick = onPeriodWeek,
            label = { Text("Неделя", fontSize = 13.sp) },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = AccentGreen,
                selectedLabelColor = Color.White,
                containerColor = CardBackgroundAlt,
                labelColor = TextSecondary
            ),
            shape = RoundedCornerShape(8.dp)
        )
        FilterChip(
            selected = !isWeek,
            onClick = onPeriodMonth,
            label = { Text("Месяц", fontSize = 13.sp) },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = AccentGreen,
                selectedLabelColor = Color.White,
                containerColor = CardBackgroundAlt,
                labelColor = TextSecondary
            ),
            shape = RoundedCornerShape(8.dp)
        )

        Spacer(modifier = Modifier.width(8.dp))

        // Type filter chips: All / AC / DC
        FilterChip(
            selected = typeFilter == null,
            onClick = { onTypeFilter(null) },
            label = { Text("Все", fontSize = 13.sp) },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = AccentBlue,
                selectedLabelColor = Color.White,
                containerColor = CardBackgroundAlt,
                labelColor = TextSecondary
            ),
            shape = RoundedCornerShape(8.dp)
        )
        FilterChip(
            selected = typeFilter == "AC",
            onClick = { onTypeFilter("AC") },
            label = { Text("AC", fontSize = 13.sp) },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = AccentBlue,
                selectedLabelColor = Color.White,
                containerColor = CardBackgroundAlt,
                labelColor = TextSecondary
            ),
            shape = RoundedCornerShape(8.dp)
        )
        FilterChip(
            selected = typeFilter == "DC",
            onClick = { onTypeFilter("DC") },
            label = { Text("DC", fontSize = 13.sp) },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = AccentOrange,
                selectedLabelColor = Color.White,
                containerColor = CardBackgroundAlt,
                labelColor = TextSecondary
            ),
            shape = RoundedCornerShape(8.dp)
        )
    }
}

/** Summary row with 3 stat boxes: sessions, total kWh, total cost. */
@Composable
private fun ChargeSummaryRow(
    sessionCount: Int,
    totalKwh: Double,
    totalCost: Double,
    currencySymbol: String = "Br"
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SummaryBox(
            value = "$sessionCount",
            label = "Сессий",
            modifier = Modifier.weight(1f)
        )
        SummaryBox(
            value = "%.1f".format(totalKwh),
            unit = "кВт·ч",
            label = "Заряжено",
            modifier = Modifier.weight(1f)
        )
        SummaryBox(
            value = "%.0f".format(totalCost),
            unit = currencySymbol,
            label = "Стоимость",
            modifier = Modifier.weight(1f)
        )
    }
}

/** Single summary stat box. */
@Composable
private fun SummaryBox(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    unit: String? = null
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .background(
                color = CardBackground,
                shape = RoundedCornerShape(12.dp)
            )
            .height(76.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = value,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium
                )
                if (unit != null) {
                    Text(
                        text = " $unit",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
            }
            Text(
                text = label,
                color = TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * Power curve line chart drawn on Canvas.
 * Shows power_kw over time from charge_points.
 * X-axis: time, Y-axis: power in kW.
 */
@Composable
private fun PowerCurveChart(
    points: List<ChargePointEntity>,
    modifier: Modifier = Modifier
) {
    // Need at least 2 points to draw a line
    if (points.size < 2) {
        Box(
            modifier = modifier
                .background(
                    color = CardBackground,
                    shape = RoundedCornerShape(12.dp)
                )
                .height(120.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (points.isEmpty()) "Загрузка..." else "Недостаточно данных",
                color = TextSecondary,
                fontSize = 13.sp
            )
        }
        return
    }

    val validPoints = points.filter { it.powerKw != null }
    if (validPoints.size < 2) {
        Box(
            modifier = modifier
                .background(
                    color = CardBackground,
                    shape = RoundedCornerShape(12.dp)
                )
                .height(120.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Нет данных о мощности",
                color = TextSecondary,
                fontSize = 13.sp
            )
        }
        return
    }

    val minTime = validPoints.first().timestamp
    val maxTime = validPoints.last().timestamp
    val timeRange = (maxTime - minTime).coerceAtLeast(1L).toFloat()
    val maxPower = validPoints.maxOf { it.powerKw!! }.coerceAtLeast(1.0).toFloat()

    Column(
        modifier = modifier
            .background(
                color = CardBackground,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(12.dp)
    ) {
        // Chart header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Мощность зарядки",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "макс. %.1f кВт".format(maxPower.toDouble()),
                color = TextSecondary,
                fontSize = 12.sp
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Canvas line chart
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
        ) {
            val chartWidth = size.width
            val chartHeight = size.height
            val padding = 4.dp.toPx()

            val plotWidth = chartWidth - padding * 2
            val plotHeight = chartHeight - padding * 2

            // Draw horizontal grid lines (4 lines)
            for (i in 0..4) {
                val y = padding + plotHeight * (1f - i / 4f)
                drawLine(
                    color = ChartGridColor,
                    start = Offset(padding, y),
                    end = Offset(chartWidth - padding, y),
                    strokeWidth = 1f
                )
            }

            // Build the power curve path
            val path = Path()
            validPoints.forEachIndexed { index, point ->
                val x = padding + ((point.timestamp - minTime) / timeRange) * plotWidth
                val y = padding + plotHeight * (1f - (point.powerKw!!.toFloat() / maxPower))

                if (index == 0) {
                    path.moveTo(x, y)
                } else {
                    path.lineTo(x, y)
                }
            }

            // Draw the line
            drawPath(
                path = path,
                color = ChartLineColor,
                style = Stroke(
                    width = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )

            // Draw filled area under the curve
            val fillPath = Path()
            fillPath.addPath(path)
            // Close the path along the bottom
            val lastPoint = validPoints.last()
            val lastX = padding + ((lastPoint.timestamp - minTime) / timeRange) * plotWidth
            val firstX = padding + ((validPoints.first().timestamp - minTime) / timeRange) * plotWidth
            fillPath.lineTo(lastX, padding + plotHeight)
            fillPath.lineTo(firstX, padding + plotHeight)
            fillPath.close()

            drawPath(
                path = fillPath,
                color = ChartLineColor.copy(alpha = 0.15f)
            )
        }
    }
}
