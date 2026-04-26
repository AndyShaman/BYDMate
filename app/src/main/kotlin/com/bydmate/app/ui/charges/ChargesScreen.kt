package com.bydmate.app.ui.charges

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bydmate.app.data.local.dao.ChargeSummary
import com.bydmate.app.data.local.entity.ChargeEntity
import com.bydmate.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChargesScreen(
    onNavigateSettings: () -> Unit = {},
    viewModel: ChargesViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(NavyDark, NavyDeep)))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        if (!state.autoserviceEnabled && !state.hasLegacyCharges) {
            OnboardingEmptyState(onNavigateSettings)
            return@Column
        }

        if (state.autoserviceEnabled && state.autoserviceAllSentinel) {
            SentinelEmptyState()
            return@Column
        }

        if (!state.autoserviceEnabled && state.hasLegacyCharges) {
            NotTrackingBanner(onClick = onNavigateSettings)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChargesChip("День", state.period == ChargesPeriod.TODAY) { viewModel.setPeriod(ChargesPeriod.TODAY) }
            ChargesChip("Нед", state.period == ChargesPeriod.WEEK) { viewModel.setPeriod(ChargesPeriod.WEEK) }
            ChargesChip("Мес", state.period == ChargesPeriod.MONTH) { viewModel.setPeriod(ChargesPeriod.MONTH) }
            ChargesChip("Год", state.period == ChargesPeriod.YEAR) { viewModel.setPeriod(ChargesPeriod.YEAR) }
            ChargesChip("Всё", state.period == ChargesPeriod.ALL) { viewModel.setPeriod(ChargesPeriod.ALL) }
            Spacer(modifier = Modifier.width(12.dp))
            ChargesChip("Все", state.typeFilter == ChargeTypeFilter.ALL) { viewModel.setTypeFilter(ChargeTypeFilter.ALL) }
            ChargesChip("AC", state.typeFilter == ChargeTypeFilter.AC) { viewModel.setTypeFilter(ChargeTypeFilter.AC) }
            ChargesChip("DC", state.typeFilter == ChargeTypeFilter.DC) { viewModel.setTypeFilter(ChargeTypeFilter.DC) }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxSize()) {
            if (state.months.isEmpty()) {
                Column(
                    modifier = Modifier.weight(0.65f).fillMaxHeight(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Нет зарядок за выбранный период",
                        color = TextSecondary,
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(0.65f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    for (month in state.months) {
                        item(key = "month_${month.yearMonth}") {
                            ChargesMonthHeader(
                                month = month,
                                expanded = month.yearMonth in state.expandedMonths,
                                currencySymbol = state.currencySymbol,
                                onClick = { viewModel.toggleMonth(month.yearMonth) }
                            )
                        }
                        if (month.yearMonth in state.expandedMonths) {
                            for (day in month.days) {
                                item(key = "day_${month.yearMonth}_${day.date}") {
                                    ChargesDayHeader(
                                        day = day,
                                        expanded = day.date in state.expandedDays,
                                        currencySymbol = state.currencySymbol,
                                        onClick = { viewModel.toggleDay(day.date) }
                                    )
                                }
                                if (day.date in state.expandedDays) {
                                    item(key = "cheader_${month.yearMonth}_${day.date}") {
                                        ChargesColumnHeaders(currencySymbol = state.currencySymbol)
                                    }
                                    for (charge in day.charges) {
                                        item(key = "charge_${charge.id}") {
                                            ChargeRow(
                                                charge = charge,
                                                currencySymbol = state.currencySymbol
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(1.dp)
                    .background(CardBorder.copy(alpha = 0.5f))
            )

            ChargesStatsPanel(
                periodSummary = state.periodSummary,
                currencySymbol = state.currencySymbol,
                lifetimeAcKwh = state.lifetimeAcKwh,
                lifetimeDcKwh = state.lifetimeDcKwh,
                lifetimeTotalKwh = state.lifetimeTotalKwh,
                equivCycles = state.equivCycles,
                nominalCapacityKwh = state.nominalCapacityKwh,
                sohSeries = state.sohSeries,
                capacitySeries = state.capacitySeries,
                showLifetime = state.autoserviceEnabled && state.autoserviceConnected,
                modifier = Modifier.weight(0.35f).fillMaxHeight()
            )
        }
    }
}

// ─── Fallback states ──────────────────────────────────────────────────────────

@Composable
private fun OnboardingEmptyState(onNavigateSettings: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .background(CardSurface, RoundedCornerShape(12.dp))
                .border(1.dp, CardBorder, RoundedCornerShape(12.dp))
                .padding(horizontal = 32.dp, vertical = 60.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Статистика зарядок недоступна. Чтобы видеть кВт·ч и стоимость каждой зарядки — включи «Системные данные» в Настройках.",
                color = TextSecondary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )
            Spacer(modifier = Modifier.height(14.dp))
            Button(
                onClick = onNavigateSettings,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentGreen,
                    contentColor = NavyDark
                )
            ) {
                Text("Перейти в Настройки", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SentinelEmptyState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .background(CardSurface, RoundedCornerShape(12.dp))
                .border(1.dp, CardBorder, RoundedCornerShape(12.dp))
                .padding(horizontal = 32.dp, vertical = 60.dp)
        ) {
            Text(
                text = "На вашей модели машины статистика зарядок недоступна — диагностические данные не читаются. SoH тоже не показывается.",
                color = TextSecondary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )
        }
    }
}

@Composable
private fun NotTrackingBanner(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = SocYellow.copy(alpha = 0.08f),
                shape = RoundedCornerShape(8.dp)
            )
            .border(1.dp, SocYellow.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Новые зарядки сейчас не отслеживаются.",
            color = SocYellow,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "Включить →",
            color = AccentGreen,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.clickable { onClick() }
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
}

// ─── List components ──────────────────────────────────────────────────────────

@Composable
private fun ChargesMonthHeader(
    month: ChargesMonthGroup,
    expanded: Boolean,
    currencySymbol: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .background(CardSurface.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (expanded) "▼" else "▶", color = AccentGreen, fontSize = 11.sp)
            Spacer(modifier = Modifier.width(6.dp))
            Text(month.label, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${month.sessionCount}",
                color = TextSecondary, fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.End,
                modifier = Modifier.width(36.dp)
            )
            Text(
                "%.1f кВт·ч".format(month.totalKwh),
                color = TextSecondary, fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.End,
                modifier = Modifier.width(88.dp)
            )
            Text(
                "%.2f %s".format(month.totalCost, currencySymbol),
                color = TextSecondary, fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.End,
                modifier = Modifier.width(72.dp)
            )
        }
    }
}

@Composable
private fun ChargesDayHeader(
    day: ChargesDayGroup,
    expanded: Boolean,
    currencySymbol: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .background(CardSurface.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
            .padding(start = 20.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (expanded) "▼" else "▶", color = AccentBlue, fontSize = 11.sp)
            Spacer(modifier = Modifier.width(6.dp))
            Text(day.label, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${day.sessionCount}",
                color = TextSecondary, fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.End,
                modifier = Modifier.width(36.dp)
            )
            Text(
                "%.1f кВт·ч".format(day.totalKwh),
                color = TextSecondary, fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.End,
                modifier = Modifier.width(88.dp)
            )
            Text(
                "%.2f %s".format(day.totalCost, currencySymbol),
                color = TextSecondary, fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.End,
                modifier = Modifier.width(72.dp)
            )
        }
    }
}

@Composable
private fun ChargesColumnHeaders(currencySymbol: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 36.dp, end = 12.dp, top = 4.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("время", color = TextMuted, fontSize = 11.sp, modifier = Modifier.width(100.dp))
        Text("SOC", color = TextMuted, fontSize = 11.sp, modifier = Modifier.width(80.dp))
        Text("кВт·ч", color = TextMuted, fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.width(56.dp))
        Text(currencySymbol.lowercase(), color = TextMuted, fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.width(56.dp))
    }
    HorizontalDivider(
        color = CardBorder.copy(alpha = 0.5f),
        thickness = 0.5.dp,
        modifier = Modifier.padding(start = 36.dp, end = 12.dp)
    )
}

@Composable
private fun ChargeRow(charge: ChargeEntity, currencySymbol: String) {
    val timeFmt = SimpleDateFormat("HH:mm", Locale.US)
    val startTime = timeFmt.format(Date(charge.startTs))

    val socText = when {
        charge.socStart != null && charge.socEnd != null -> "${charge.socStart}% → ${charge.socEnd}%"
        charge.socStart != null -> "${charge.socStart}% → —"
        else -> "—"
    }

    val kwh = charge.kwhCharged?.let { "%.1f".format(it) } ?: "—"
    val cost = charge.cost?.let { "%.2f".format(it) } ?: "—"

    // gunState: 1=NONE, 2=AC, 3=DC, 4=GB_DC
    val typeLabel = when (charge.gunState) {
        2 -> "AC"
        3, 4 -> "DC"
        else -> charge.type ?: "—"
    }
    val typeColor = when (typeLabel) {
        "DC" -> AccentOrange
        else -> AccentBlue
    }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 36.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Type badge + time
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.width(100.dp)
            ) {
                Box(
                    modifier = Modifier
                        .background(CardSurfaceElevated, RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text(
                        typeLabel,
                        color = typeColor,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    startTime,
                    color = TextSecondary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Text(
                socText,
                color = TextMuted,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.width(80.dp)
            )
            Text(
                kwh,
                color = AccentGreen,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.End,
                modifier = Modifier.width(56.dp)
            )
            Text(
                cost,
                color = TextSecondary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.End,
                modifier = Modifier.width(56.dp)
            )
        }
        HorizontalDivider(
            color = CardBorder.copy(alpha = 0.3f),
            thickness = 0.5.dp,
            modifier = Modifier.padding(start = 36.dp, end = 12.dp)
        )
    }
}

// ─── Stats panel ──────────────────────────────────────────────────────────────

@Composable
private fun ChargesStatsPanel(
    periodSummary: ChargeSummary,
    currencySymbol: String,
    lifetimeAcKwh: Double,
    lifetimeDcKwh: Double,
    lifetimeTotalKwh: Double,
    equivCycles: Double,
    nominalCapacityKwh: Double,
    sohSeries: List<Float>,
    capacitySeries: List<Float>,
    showLifetime: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 8.dp)
    ) {
        // Period summary label
        Text(
            "За выбранный период",
            color = TextMuted,
            fontSize = 11.sp,
            letterSpacing = 0.3.sp,
            modifier = Modifier.padding(bottom = 6.dp)
        )

        // Period summary card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(CardSurface, RoundedCornerShape(10.dp))
                .border(1.dp, CardBorder, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            StatRow("Сессий", "${periodSummary.sessionCount}", TextPrimary)
            Spacer(modifier = Modifier.height(4.dp))
            StatRow("кВт·ч", "%.1f".format(periodSummary.totalKwh), AccentGreen)
            Spacer(modifier = Modifier.height(4.dp))
            StatRow("Стоимость", "%.2f %s".format(periodSummary.totalCost, currencySymbol), TextPrimary)
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (showLifetime) {
            // Lifetime label
            Text(
                "Лайфтайм",
                color = TextMuted,
                fontSize = 11.sp,
                letterSpacing = 0.3.sp,
                modifier = Modifier.padding(bottom = 6.dp)
            )

            // Lifetime card
            val totalForRatio = lifetimeAcKwh + lifetimeDcKwh
            val acPct = if (totalForRatio > 0) (lifetimeAcKwh / totalForRatio * 100).toInt() else 0
            val dcPct = if (totalForRatio > 0) 100 - acPct else 0

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CardSurface, RoundedCornerShape(10.dp))
                    .border(1.dp, CardBorder, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("AC / DC", color = TextMuted, fontSize = 11.sp)
                    Row {
                        Text("$acPct%", color = AccentGreen, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                        Text(" · ", color = TextMuted, fontSize = 13.sp)
                        Text("$dcPct%", color = AccentOrange, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Эквив. циклов", color = TextMuted, fontSize = 11.sp)
                    Text("%.1f".format(equivCycles), color = TextPrimary, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "%.0f кВт·ч ÷ %.1f номинал".format(lifetimeTotalKwh, nominalCapacityKwh),
                    color = TextMuted,
                    fontSize = 10.sp,
                    lineHeight = 13.sp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Mini SoH chart
            if (sohSeries.size >= 2) {
                MiniLineChart(
                    series = sohSeries,
                    title = "SoH (%)",
                    lineColor = AccentGreen
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Mini Capacity chart
            if (capacitySeries.size >= 2) {
                MiniLineChart(
                    series = capacitySeries,
                    title = "Ёмкость (кВт·ч)",
                    lineColor = AccentBlue
                )
            }
        } else {
            Text(
                "Lifetime метрики и SoH тренды доступны после включения «Системные данные»",
                color = TextMuted,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        }
    }
}

@Composable
private fun StatRow(label: String, value: String, valueColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextMuted, fontSize = 11.sp)
        Text(value, color = valueColor, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun MiniLineChart(
    series: List<Float>,
    title: String,
    lineColor: Color
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardSurface, RoundedCornerShape(10.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        Text(title, color = TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
        ) {
            val w = size.width
            val h = size.height
            val minVal = series.min()
            val maxVal = series.max()
            val range = (maxVal - minVal).coerceAtLeast(0.01f)
            val pad = 4.dp.toPx()

            // Grid line
            drawLine(
                color = ChartGrid,
                start = Offset(pad, pad),
                end = Offset(w - pad, pad),
                strokeWidth = 0.5.dp.toPx()
            )

            val path = Path()
            series.forEachIndexed { index, value ->
                val x = pad + (index.toFloat() / (series.size - 1)) * (w - pad * 2)
                val y = pad + (1f - (value - minVal) / range) * (h - pad * 2)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, lineColor, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

// ─── Chip ─────────────────────────────────────────────────────────────────────

@Composable
private fun ChargesChip(label: String, selected: Boolean, onClick: () -> Unit) {
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
