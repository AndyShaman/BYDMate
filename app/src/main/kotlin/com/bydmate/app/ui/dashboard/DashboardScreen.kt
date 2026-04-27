package com.bydmate.app.ui.dashboard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.TrendingDown
import androidx.compose.material.icons.outlined.TrendingFlat
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.bydmate.app.R
import com.bydmate.app.data.remote.DynamicMetric
import com.bydmate.app.domain.calculator.Trend
import com.bydmate.app.ui.components.SocGauge
import com.bydmate.app.ui.components.TripCard
import com.bydmate.app.ui.components.consumptionColor
import com.bydmate.app.ui.theme.*
import com.bydmate.app.ui.widget.TRIP_DISTANCE_TREND_THRESHOLD_KM
import com.bydmate.app.ui.widget.formatDurationShort
import com.bydmate.app.ui.widget.formatTripKm
import kotlinx.coroutines.delay

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.refresh()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(NavyDark, NavyDeep)))
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        TopBar(isServiceRunning = state.isServiceRunning, diPlusConnected = state.diPlusConnected, adbConnected = state.adbConnected)
        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // LEFT COLUMN — fill full height with SpaceBetween
            Box(modifier = Modifier.weight(0.4f)) {
                // Ghost car background
                Image(
                    painter = painterResource(R.drawable.leopard3),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = 0.06f },
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.Center
                )
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // TOP: SOC gauge + 4 widget-style stats around it (mirrors FloatingWidgetView).
                    // Two symmetric rows wrap the gauge:
                    //   row mid    — duration | odometer | inside temp
                    //   row bottom — trip km | range km + label | consumption + trend
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        SocGauge(
                            soc = state.soc ?: 0,
                            modifier = Modifier.size(150.dp),
                            isCharging = state.isCharging,
                        )

                        // Live-ticking duration text (refresh every 15s, like in widget).
                        val durationText by produceState(
                            initialValue = formatDurationShort(state.sessionStartedAt),
                            state.sessionStartedAt
                        ) {
                            while (true) {
                                value = formatDurationShort(state.sessionStartedAt)
                                delay(15_000L)
                            }
                        }

                        // Row mid: schedule | odometer | inside temp
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                CornerStat(
                                    icon = Icons.Outlined.Schedule,
                                    text = durationText,
                                )
                            }
                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                Text(
                                    text = if (state.odometer != null) "%.1f km".format(state.odometer) else "— km",
                                    color = TextSecondary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                                CornerStat(
                                    icon = Icons.Outlined.DirectionsCar,
                                    text = state.insideTemp?.let { "$it°" } ?: "—",
                                    iconLast = true,
                                )
                            }
                        }

                        // Row bottom: trip km | range km + label | consumption + trend
                        // Trend logic mirrors widget: suppress arrow within first 300m of session.
                        val effectiveTrend = if (
                            state.sessionStartedAt != null &&
                            (state.tripDistanceKm ?: 0.0) < TRIP_DISTANCE_TREND_THRESHOLD_KM
                        ) Trend.NONE else state.consumptionTrend
                        val trendColor = when (effectiveTrend) {
                            Trend.DOWN -> AccentGreen
                            Trend.UP -> SocYellow
                            Trend.FLAT -> TextPrimary
                            Trend.NONE -> TextMuted
                        }
                        val rangeText = state.estimatedRangeKm?.let { "~${"%.0f".format(it)}" } ?: "—"
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                CornerStat(
                                    icon = Icons.Outlined.Route,
                                    text = formatTripKm(state.tripDistanceKm),
                                )
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text(rangeText, color = AccentGreen, fontSize = 32.sp, fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("км", color = AccentGreen.copy(alpha = 0.7f), fontSize = 18.sp,
                                        fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 4.dp))
                                }
                                Text("расчётный пробег", color = TextMuted, fontSize = 12.sp)
                            }
                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (effectiveTrend != Trend.NONE) {
                                        Icon(
                                            imageVector = when (effectiveTrend) {
                                                Trend.DOWN -> Icons.Outlined.TrendingDown
                                                Trend.UP -> Icons.Outlined.TrendingUp
                                                else -> Icons.Outlined.TrendingFlat
                                            },
                                            contentDescription = null,
                                            tint = trendColor,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(Modifier.width(4.dp))
                                    }
                                    Text(
                                        text = state.consumption?.let { "%.1f".format(it) } ?: "—",
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = FontFamily.Monospace,
                                        color = if (effectiveTrend == Trend.NONE) TextMuted else trendColor,
                                    )
                                }
                            }
                        }
                    }

                    // 3 compact cards: insight, battery, idle drain
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // AI Insight card
                        val insightColor = when (state.effectiveInsightTone) {
                            "critical" -> SocRed
                            "warning" -> SocYellow
                            else -> AccentGreen
                        }
                        InsightCard(
                            title = state.insightTitle,
                            summary = state.insightSummary,
                            hasApiKey = state.hasApiKey,
                            loading = state.insightLoading,
                            borderColor = insightColor,
                            onClick = { viewModel.toggleInsightExpanded() }
                        )
                        // Battery card — 3 значения: SoH | темп. батареи | бортовая сеть
                        val sohStatus = when {
                            (state.currentSoh ?: 100f) < 80f -> "critical"
                            (state.currentSoh ?: 100f) < 90f -> "warning"
                            else -> "ok"
                        }
                        val sohColor = when (sohStatus) {
                            "critical" -> SocRed; "warning" -> SocYellow; else -> AccentGreen
                        }
                        val tempColor = when (state.batteryHealthStatus) {
                            "critical" -> SocRed; "warning" -> SocYellow; else -> AccentGreen
                        }
                        val voltageColor = when (state.voltage12vStatus) {
                            "critical" -> SocRed; "warning" -> SocYellow; else -> AccentGreen
                        }
                        val worstColor = when {
                            sohStatus == "critical" ||
                                state.batteryHealthStatus == "critical" ||
                                state.voltage12vStatus == "critical" -> SocRed
                            sohStatus == "warning" ||
                                state.batteryHealthStatus == "warning" ||
                                state.voltage12vStatus == "warning" -> SocYellow
                            else -> AccentGreen
                        }
                        BatteryCompactCard(
                            sohText = state.currentSoh?.let { "%.0f%%".format(it) } ?: "—",
                            sohColor = sohColor,
                            tempText = state.avgBatTemp?.let { "${it}°" } ?: "—",
                            tempColor = tempColor,
                            voltageText = state.voltage12v?.let { "%.1fВ".format(it) } ?: "—",
                            voltageColor = voltageColor,
                            borderColor = worstColor,
                            onClick = { viewModel.toggleBatteryHealthExpanded() }
                        )
                        // Idle drain card — hidden in DiPlus mode (no zero-km records)
                        if (state.idleDrainAvailable) {
                            val idleTimeStr = if (state.idleDrainHours < 1.0)
                                "%.0f".format(state.idleDrainHours * 60) + " мин"
                            else "%.1f".format(state.idleDrainHours) + " ч"
                            CompactCard(
                                leftValue = "%.1f".format(state.idleDrainKwhToday),
                                leftLabel = "кВт·ч",
                                rightValue = idleTimeStr,
                                rightLabel = "стоянка",
                                borderColor = when {
                                    state.idleDrainPercent > 5.0 -> SocRed
                                    state.idleDrainPercent > 2.0 -> SocYellow
                                    else -> AccentGreen
                                },
                                onClick = { viewModel.toggleIdleDrainExpanded() }
                            )
                        }
                    }

                    // Pop-up dialogs
                    if (state.insightExpanded) {
                        val insightDialogColor = when (state.effectiveInsightTone) {
                            "critical" -> SocRed
                            "warning" -> SocYellow
                            else -> AccentGreen
                        }
                        CardDetailDialog(
                            title = null,
                            borderColor = insightDialogColor,
                            onDismiss = { viewModel.toggleInsightExpanded() }
                        ) {
                            val dynamics = state.insightDynamics
                            val insights = state.insightInsights
                            val hasContent = dynamics.isNotEmpty() || insights.isNotEmpty()
                            if (hasContent) {
                                // Dynamics table
                                if (dynamics.isNotEmpty()) {
                                    Column {
                                        dynamics.forEach { metric ->
                                            if (metric.section != null) {
                                                Text(
                                                    metric.section,
                                                    color = TextMuted,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp)
                                                )
                                            }
                                            DynamicsRow(metric = metric)
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(10.dp))
                                }
                                // Divider
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(1.dp)
                                        .background(Color(0xFF2A2A2E))
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                // Insights as bullet points
                                if (insights.isNotEmpty()) {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        insights.forEach { text ->
                                            Row(modifier = Modifier.fillMaxWidth()) {
                                                Box(
                                                    modifier = Modifier
                                                        .padding(top = 7.dp, end = 8.dp)
                                                        .size(5.dp)
                                                        .background(
                                                            insightDialogColor.copy(alpha = 0.5f),
                                                            shape = CircleShape
                                                        )
                                                )
                                                StyledInsightText(text = text, bulletColor = insightDialogColor)
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                                // Footer
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    state.insightDate?.let {
                                        Text(it, color = TextMuted, fontSize = 11.sp)
                                    }
                                    state.insightError?.let { err ->
                                        Text(err, color = SocRed, fontSize = 11.sp)
                                    }
                                    androidx.compose.material3.Button(
                                        onClick = { viewModel.refreshInsight() },
                                        enabled = !state.insightLoading,
                                        shape = RoundedCornerShape(8.dp),
                                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                            containerColor = insightDialogColor,
                                            contentColor = Color.White
                                        )
                                    ) {
                                        Text(
                                            if (state.insightLoading) "Обновление..." else "Обновить",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            } else if (!state.hasApiKey) {
                                Text(
                                    "Для AI-инсайтов настройте OpenRouter API в Настройках",
                                    color = TextSecondary,
                                    fontSize = 13.sp
                                )
                            } else {
                                Text("Мало данных для анализа", color = TextMuted, fontSize = 13.sp)
                            }
                        }
                    }
                    if (state.batteryHealthExpanded) {
                        val sohForColor = state.currentSoh ?: 100f
                        val color = when {
                            sohForColor < 80f ||
                                state.batteryHealthStatus == "critical" ||
                                state.voltage12vStatus == "critical" -> SocRed
                            sohForColor < 90f ||
                                state.batteryHealthStatus == "warning" ||
                                state.voltage12vStatus == "warning" -> SocYellow
                            else -> AccentGreen
                        }
                        com.bydmate.app.ui.battery.BatteryHealthDialog(
                            liveSoc = state.soc,
                            liveCellDelta = state.cellVoltageDelta,
                            liveBatTemp = state.avgBatTemp,
                            liveVoltage12v = state.voltage12v,
                            liveSoh = state.currentSoh,
                            liveLifetimeKm = state.currentLifetimeKm,
                            liveLifetimeKwh = state.currentLifetimeKwh,
                            autoserviceEnabled = state.autoserviceEnabled,
                            borderColor = color,
                            onDismiss = { viewModel.toggleBatteryHealthExpanded() },
                        )
                    }
                    if (state.idleDrainExpanded) {
                        val color = when {
                            state.idleDrainPercent > 5.0 -> SocRed
                            state.idleDrainPercent > 2.0 -> SocYellow
                            else -> AccentGreen
                        }
                        CardDetailDialog(
                            title = "Расход на стоянке",
                            borderColor = color,
                            onDismiss = { viewModel.toggleIdleDrainExpanded() }
                        ) {
                            if (state.idleDrainRate > 0) {
                                DetailRow("Скорость", "${"%.2f".format(state.idleDrainRate)} кВт·ч/час", color)
                            }
                            DetailRow("За 7 дней", "${"%.1f".format(state.idleDrainKwhWeek)} кВт·ч", TextPrimary)
                            if (state.idleDrainKwhWeek > 0) {
                                DetailRow("Ср. в день", "${"%.1f".format(state.idleDrainKwhWeek / 7.0)} кВт·ч", TextPrimary)
                            }
                        }
                    }
                }
            }

            // RIGHT COLUMN — period filter + 4 cards + recent trips
            Column(
                modifier = Modifier.weight(0.6f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Period chips
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DashboardPeriodChip("День", state.period == DashboardPeriod.TODAY) { viewModel.setPeriod(DashboardPeriod.TODAY) }
                    DashboardPeriodChip("Нед", state.period == DashboardPeriod.WEEK) { viewModel.setPeriod(DashboardPeriod.WEEK) }
                    DashboardPeriodChip("Мес", state.period == DashboardPeriod.MONTH) { viewModel.setPeriod(DashboardPeriod.MONTH) }
                    DashboardPeriodChip("Год", state.period == DashboardPeriod.YEAR) { viewModel.setPeriod(DashboardPeriod.YEAR) }
                    DashboardPeriodChip("Всё", state.period == DashboardPeriod.ALL) { viewModel.setPeriod(DashboardPeriod.ALL) }
                }

                // 4 stat cards
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatCard("Пробег", "%.1f км".format(state.totalKm), "${state.tripCount} поездок", Color.White, Modifier.weight(1f))
                    StatCard("Энергия", "%.1f кВт·ч".format(state.totalKwh), null, AccentBlue, Modifier.weight(1f))
                    val consColor = if (state.avgConsumption > 0) consumptionColor(state.avgConsumption) else TextSecondary
                    StatCard("Расход", if (state.avgConsumption > 0) "%.1f/100".format(state.avgConsumption) else "—", null, consColor, Modifier.weight(1f))
                    StatCard("Стоимость", "%.2f %s".format(state.totalCost, state.currencySymbol), null, AccentGreen, Modifier.weight(1f))
                }

                SectionHeader(text = "Последние поездки")
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("время", color = TextMuted, fontSize = 11.sp, modifier = Modifier.weight(2.5f))
                    Text("длит.", color = TextMuted, fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                    Text("км", color = TextMuted, fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                    Text("кВт·ч", color = TextMuted, fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                    Text("/100", color = TextMuted, fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                    Text(state.currencySymbol, color = TextMuted, fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                }
                if (state.recentTrips.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        state.recentTrips.forEach { trip ->
                            TripCard(
                                trip = trip,
                                onClick = { },
                                currencySymbol = state.currencySymbol
                            )
                        }
                    }
                } else {
                    PlaceholderText(text = "Поездок пока нет")
                }
            }
        }
    }
}

@Composable
private fun TopBar(isServiceRunning: Boolean, diPlusConnected: Boolean, adbConnected: Boolean? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "BYDMate",
            color = TextPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isServiceRunning && !diPlusConnected) {
                Text(
                    text = "DiPlus не отвечает",
                    color = SocYellow,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            if (isServiceRunning && adbConnected == false) {
                Text("ADB не отвечает", color = SocYellow, fontSize = 12.sp)
                Spacer(modifier = Modifier.width(8.dp))
            }
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(if (isServiceRunning) AccentGreen else TextMuted)
            )
            Text(
                text = if (isServiceRunning) "Online" else "Offline",
                color = TextSecondary,
                fontSize = 12.sp
            )
        }
    }
}

// ============================================================================
// AI Insight card — text-based, different from value-pair cards
// ============================================================================

@Composable
private fun InsightCard(
    title: String?,
    summary: String?,
    hasApiKey: Boolean,
    loading: Boolean,
    borderColor: Color,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        border = androidx.compose.foundation.BorderStroke(
            1.5.dp,
            if (hasApiKey && title != null) borderColor.copy(alpha = 0.5f) else TextMuted.copy(alpha = 0.3f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("✦", fontSize = 16.sp, color = if (hasApiKey && title != null) borderColor else TextMuted)
            Spacer(modifier = Modifier.width(8.dp))
            if (loading) {
                Text("Анализ...", color = TextSecondary, fontSize = 13.sp)
            } else if (title != null && hasApiKey) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        color = borderColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    if (summary != null) {
                        Text(
                            summary,
                            color = borderColor.copy(alpha = 0.8f),
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                    }
                }
            } else {
                Text(
                    "AI Инсайты — Настройте OpenRouter →",
                    color = TextMuted,
                    fontSize = 12.sp
                )
            }
        }
    }
}

// ============================================================================
// Compact card — same look for all three
// ============================================================================

@Composable
private fun CompactCard(
    leftValue: String,
    leftLabel: String,
    rightValue: String,
    rightLabel: String,
    borderColor: Color,
    hasData: Boolean = true,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, borderColor.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(leftValue, color = if (hasData) borderColor else TextMuted,
                    fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text(leftLabel, color = TextMuted, fontSize = 11.sp)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(rightValue, color = if (hasData) borderColor else TextMuted,
                    fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text(rightLabel, color = TextMuted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun BatteryCompactCard(
    sohText: String,
    sohColor: Color,
    tempText: String,
    tempColor: Color,
    voltageText: String,
    voltageColor: Color,
    borderColor: Color,
    onClick: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, borderColor.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            BatteryCell(value = sohText, label = "SoH", color = sohColor)
            BatteryCell(value = tempText, label = "темп. бат.", color = tempColor)
            BatteryCell(value = voltageText, label = "борт. сеть", color = voltageColor)
        }
    }
}

@Composable
private fun BatteryCell(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontSize = 17.sp, fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace)
        Text(label, color = TextMuted, fontSize = 10.sp)
    }
}

// ============================================================================
// Pop-up dialog for card details
// ============================================================================

@Composable
private fun CardDetailDialog(
    title: String? = null,
    borderColor: Color,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
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
            contentAlignment = Alignment.CenterStart
        ) {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = CardSurface),
                border = androidx.compose.foundation.BorderStroke(2.dp, borderColor.copy(alpha = 0.6f)),
                modifier = Modifier
                    .padding(start = 22.dp, end = 16.dp)
                    .fillMaxWidth(0.4f)
                    .clickable { onDismiss() }
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (title != null) {
                        Text(title, color = borderColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                    content()
                }
            }
        }
    }
}

@Composable
private fun DynamicsRow(metric: DynamicMetric) {
    val changeColor = when (metric.sentiment) {
        "good" -> AccentGreen
        "bad" -> SocRed
        else -> TextMuted
    }
    val arrow = when {
        metric.changePct == null -> ""
        metric.changePct > 0 -> "▲"
        metric.changePct < 0 -> "▼"
        else -> ""
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            metric.label,
            color = TextSecondary,
            fontSize = 11.sp,
            modifier = Modifier.width(100.dp)
        )
        Text(
            metric.current,
            color = TextPrimary,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold
        )
        if (metric.previous != null) {
            Spacer(modifier = Modifier.width(6.dp))
            Text("\u2190", color = TextMuted, fontSize = 10.sp)
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                metric.previous,
                color = TextMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        if (metric.changePct != null) {
            Text(
                "$arrow${"%.0f".format(kotlin.math.abs(metric.changePct))}%",
                color = changeColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

// Highlight numbers in insight text with amber color, bold text before first dash
@Composable
private fun StyledInsightText(text: String, bulletColor: Color) {
    val numberPattern = Regex("""(\d+[.,]\d+|\d+)(%| кВтч?/ч| кВтч?/100км| кВтч?| км/ч| км| мВ|°C)?""")
    val annotated = buildAnnotatedString {
        var lastEnd = 0
        // Bold: text before first " —" or " —"
        val dashIdx = text.indexOf(" — ").takeIf { it > 0 }
            ?: text.indexOf(" — ").takeIf { it > 0 }
        if (dashIdx != null && dashIdx < 40) {
            withStyle(SpanStyle(color = TextPrimary, fontWeight = FontWeight.SemiBold)) {
                append(text.substring(0, dashIdx))
            }
            lastEnd = dashIdx
        }
        // Highlight numbers
        val remaining = text.substring(lastEnd)
        var rLastEnd = 0
        for (match in numberPattern.findAll(remaining)) {
            if (match.range.first > rLastEnd) {
                withStyle(SpanStyle(color = TextSecondary)) {
                    append(remaining.substring(rLastEnd, match.range.first))
                }
            }
            val num = match.value
            // Only highlight if it looks like a real metric (has digits)
            if (num.any { it.isDigit() } && num.length > 1) {
                withStyle(SpanStyle(color = SocYellow, fontFamily = FontFamily.Monospace, fontSize = 12.sp)) {
                    append(num)
                }
            } else {
                withStyle(SpanStyle(color = TextSecondary)) {
                    append(num)
                }
            }
            rLastEnd = match.range.last + 1
        }
        if (rLastEnd < remaining.length) {
            withStyle(SpanStyle(color = TextSecondary)) {
                append(remaining.substring(rLastEnd))
            }
        }
    }
    Text(annotated, fontSize = 13.sp, lineHeight = 19.sp)
}

@Composable
private fun DetailRow(label: String, value: String, valueColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = TextSecondary, fontSize = 14.sp)
        Text(value, color = valueColor, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace)
    }
}

// ============================================================================
// Shared UI components
// ============================================================================

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        color = TextPrimary,
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun DashboardPeriodChip(label: String, selected: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 12.sp) },
        shape = RoundedCornerShape(8.dp),
        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
            selectedContainerColor = AccentGreen,
            selectedLabelColor = Color.White,
            containerColor = CardSurface,
            labelColor = TextSecondary
        ),
        border = androidx.compose.material3.FilterChipDefaults.filterChipBorder(
            borderColor = Color.Transparent,
            selectedBorderColor = Color.Transparent,
            enabled = true,
            selected = selected
        )
    )
}

@Composable
private fun StatCard(title: String, value: String, subtitle: String?, accentColor: Color, modifier: Modifier = Modifier) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        modifier = modifier.height(64.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(title, color = TextSecondary, fontSize = 11.sp)
            Text(value, color = accentColor, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace)
            Text(subtitle ?: "", color = TextMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun PlaceholderText(text: String) {
    Text(
        text = text,
        color = TextSecondary,
        fontSize = 14.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        fontWeight = FontWeight.Medium
    )
}

/**
 * Compact widget-style stat used in the top section of the dashboard left column.
 * Mirrors the IconText composable in FloatingWidgetView (icon muted gray + monospace value).
 * Set iconLast=true for the right-aligned variant where the value comes before the icon.
 */
@Composable
private fun CornerStat(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    iconLast: Boolean = false,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (!iconLast) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text = text,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            color = TextPrimary,
        )
        if (iconLast) {
            Spacer(Modifier.width(5.dp))
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
