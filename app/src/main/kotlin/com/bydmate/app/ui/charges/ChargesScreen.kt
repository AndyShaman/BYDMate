package com.bydmate.app.ui.charges

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
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
            Box(
                modifier = Modifier
                    .background(AccentGreen, RoundedCornerShape(8.dp))
                    .clickable { viewModel.onCreateNewCharge() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("+ зарядка", color = NavyDark, fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold)
            }
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
                                                currencySymbol = state.currencySymbol,
                                                onLongClick = { viewModel.onLongPressCharge(charge) }
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
                autoserviceEnabled = state.autoserviceEnabled,
                bmsLifetimeKm = state.bmsLifetimeKm,
                bmsLifetimeKwh = state.bmsLifetimeKwh,
                nominalCapacityKwh = state.nominalCapacityKwh,
                sohSeries = state.sohSeries,
                cellDeltaSeries = state.cellDeltaSeries,
                batTempSeries = state.batTempSeries,
                modifier = Modifier.weight(0.35f).fillMaxHeight()
            )
        }

        state.selectedChargeForAction?.let { charge ->
            ChargeActionSheet(
                charge = charge,
                onDismiss = { viewModel.onDismissActionSheet() },
                onEdit = { viewModel.onEditCharge() },
                onDeletePrompt = { viewModel.onConfirmDeletePrompt() },
            )
        }
        state.editingCharge?.let { charge ->
            ChargeEditDialog(
                charge = charge,
                homeTariff = state.homeTariff,
                dcTariff = state.dcTariff,
                batteryCapacityKwh = state.nominalCapacityKwh,
                currencySymbol = state.currencySymbol,
                onDismiss = { viewModel.onDismissEdit() },
                onSave = { viewModel.onSaveEdit(it) },
            )
        }
        state.deleteConfirmCharge?.let { charge ->
            AlertDialog(
                onDismissRequest = { viewModel.onDismissDeleteConfirm() },
                title = { Text("Удалить зарядку?") },
                text = {
                    Text("${charge.kwhCharged?.let { "%.1f".format(it) } ?: "—"} кВт·ч • восстановить будет нельзя.")
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.onConfirmDelete() }) {
                        Text("Удалить", color = SocRed)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.onDismissDeleteConfirm() }) {
                        Text("Отмена")
                    }
                },
                containerColor = CardSurface,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChargeActionSheet(
    charge: ChargeEntity,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDeletePrompt: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CardSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "${charge.type ?: "—"} • ${charge.kwhCharged?.let { "%.1f".format(it) } ?: "—"} кВт·ч",
                color = TextSecondary, fontSize = 12.sp
            )
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Изменить", color = TextPrimary, fontSize = 16.sp)
            }
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onDeletePrompt).padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Удалить", color = SocRed, fontSize = 16.sp)
            }
            Spacer(modifier = Modifier.height(8.dp))
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChargeRow(
    charge: ChargeEntity,
    currencySymbol: String,
    onLongClick: () -> Unit,
) {
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

    Column(
        modifier = Modifier.combinedClickable(
            onClick = { /* no-op для обычного клика */ },
            onLongClick = onLongClick,
        )
    ) {
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
    autoserviceEnabled: Boolean,
    bmsLifetimeKm: Double?,
    bmsLifetimeKwh: Double?,
    nominalCapacityKwh: Double,
    sohSeries: List<Float>,
    cellDeltaSeries: List<Float>,
    batTempSeries: List<Float>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SectionLabel("За выбранный период")
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

        SectionLabel("Lifetime (системные данные)")
        if (autoserviceEnabled && bmsLifetimeKwh != null) {
            val equiv = if (nominalCapacityKwh > 0) bmsLifetimeKwh / nominalCapacityKwh else 0.0
            val avgPer100 = if (bmsLifetimeKm != null && bmsLifetimeKm > 0)
                bmsLifetimeKwh / bmsLifetimeKm * 100.0 else null
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CardSurface, RoundedCornerShape(10.dp))
                    .border(1.dp, CardBorder, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                StatRow("Прокачано всего", "%.0f кВт·ч".format(bmsLifetimeKwh), AccentGreen)
                Spacer(modifier = Modifier.height(4.dp))
                StatRow("Эквив. циклов", "%.1f".format(equiv), TextPrimary)
                Spacer(modifier = Modifier.height(4.dp))
                StatRow(
                    "Пробег BMS",
                    bmsLifetimeKm?.let { "%.1f км".format(it) } ?: "—",
                    TextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                StatRow(
                    "/100км lifetime",
                    avgPer100?.let { "%.1f кВт·ч".format(it) } ?: "—",
                    AccentBlue
                )
            }

            if (sohSeries.size >= 2) {
                MiniLineChart(series = sohSeries, title = "SoH (%)", lineColor = AccentGreen)
            }
        } else {
            PlaceholderBlock(
                if (!autoserviceEnabled)
                    "Включи «Системные данные» в Настройках, чтобы видеть пробег BMS и прокачано всего от машины."
                else
                    "Жду первого ответа от машины…"
            )
        }

        SectionLabel("Динамика")
        if (cellDeltaSeries.size >= 3) {
            MiniLineChart(series = cellDeltaSeries, title = "Δ ячеек (В)", lineColor = AccentBlue)
        } else {
            ChartPlaceholder("Δ ячеек — нужно ≥3 замера")
        }
        if (batTempSeries.size >= 3) {
            MiniLineChart(series = batTempSeries, title = "Темп. батареи (°C)", lineColor = AccentOrange)
        } else {
            ChartPlaceholder("Температура — нужно ≥3 замера")
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = TextMuted,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp
    )
}

@Composable
private fun PlaceholderBlock(text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardSurface, RoundedCornerShape(10.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 10.dp)
    ) {
        Text(text, color = TextMuted, fontSize = 11.sp, lineHeight = 15.sp)
    }
}

@Composable
private fun ChartPlaceholder(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .background(CardSurface, RoundedCornerShape(10.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(10.dp))
            .padding(10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = TextMuted, fontSize = 11.sp)
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
