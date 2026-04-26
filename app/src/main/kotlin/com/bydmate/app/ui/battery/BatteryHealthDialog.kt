package com.bydmate.app.ui.battery

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bydmate.app.ui.theme.AccentBlue
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.CardSurface
import com.bydmate.app.ui.theme.CardSurfaceElevated
import com.bydmate.app.ui.theme.SocRed
import com.bydmate.app.ui.theme.SocYellow
import com.bydmate.app.ui.theme.TextMuted
import com.bydmate.app.ui.theme.TextPrimary
import com.bydmate.app.ui.theme.TextSecondary

@Composable
fun BatteryHealthDialog(
    liveSoh: Float?,
    liveLifetimeKm: Float?,
    liveLifetimeKwh: Float?,
    borderColor: Color,
    onDismiss: () -> Unit,
    viewModel: BatteryHealthViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scrimSource = remember { MutableInteractionSource() }
    val cardSource = remember { MutableInteractionSource() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(indication = null, interactionSource = scrimSource) { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = CardSurface),
                border = androidx.compose.foundation.BorderStroke(2.dp, borderColor.copy(alpha = 0.6f)),
                modifier = Modifier
                    .padding(horizontal = 32.dp)
                    .fillMaxWidth(0.55f)
                    .clickable(indication = null, interactionSource = cardSource) { /* absorb */ }
            ) {
                Column(
                    modifier = Modifier
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Здоровье батареи", color = borderColor, fontSize = 16.sp,
                        fontWeight = FontWeight.Bold)

                    BmsLiveBlock(
                        soh = liveSoh,
                        lifetimeKm = liveLifetimeKm,
                        lifetimeKwh = liveLifetimeKwh,
                    )

                    Row(
                        modifier = Modifier.padding(top = 4.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        StatColumn("Текущ. дельта",
                            state.currentDelta?.let { "%.3fV".format(it) } ?: "—")
                        StatColumn("Средн. дельта",
                            state.avgDelta?.let { "%.3fV".format(it) } ?: "—")
                        StatColumn("12V мин.",
                            state.minVoltage12v?.let { "%.1fV".format(it) } ?: "—")
                    }

                    val chargesOldestFirst = state.charges.reversed()
                    val deltaValues = chargesOldestFirst.mapNotNull { c ->
                        if (c.cellVoltageMax != null && c.cellVoltageMin != null)
                            c.cellVoltageMax - c.cellVoltageMin else null
                    }
                    if (deltaValues.size >= 2) {
                        Text("Баланс ячеек (Δ V)", color = TextSecondary, fontSize = 12.sp)
                        LineChart(
                            values = deltaValues, lineColor = AccentGreen,
                            warningThreshold = 0.05, criticalThreshold = 0.10,
                            formatLabel = { "%.3f".format(it) },
                            modifier = Modifier.fillMaxWidth().height(110.dp)
                        )
                    }

                    val voltage12vValues = chargesOldestFirst.mapNotNull { it.voltage12v }
                    if (voltage12vValues.size >= 2) {
                        Text("Бортовая сеть 12V", color = TextSecondary, fontSize = 12.sp)
                        LineChart(
                            values = voltage12vValues, lineColor = AccentBlue,
                            warningThreshold = 12.4, criticalThreshold = 11.8,
                            invertThresholds = true,
                            formatLabel = { "%.1f".format(it) },
                            modifier = Modifier.fillMaxWidth().height(110.dp)
                        )
                    }

                    val sohValues = state.snapshots.reversed().mapNotNull { it.sohPercent }
                    if (sohValues.size >= 2) {
                        Text("История SOH", color = TextSecondary, fontSize = 12.sp)
                        LineChart(
                            values = sohValues, lineColor = AccentGreen,
                            warningThreshold = 90.0, criticalThreshold = 80.0,
                            invertThresholds = true,
                            formatLabel = { "%.1f".format(it) },
                            modifier = Modifier.fillMaxWidth().height(110.dp)
                        )
                    }

                    if (state.charges.isEmpty() && state.snapshots.isEmpty() && !state.isLoading) {
                        Text(
                            "История появится после первой полной зарядки. SoH и пробег от BMS видны выше — обновляются на каждом запуске.",
                            color = TextMuted, fontSize = 12.sp, lineHeight = 16.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BmsLiveBlock(soh: Float?, lifetimeKm: Float?, lifetimeKwh: Float?) {
    val avgPer100 = if (lifetimeKm != null && lifetimeKwh != null && lifetimeKm > 0)
        lifetimeKwh / lifetimeKm * 100.0 else null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardSurfaceElevated, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("Сейчас от BMS", color = TextMuted, fontSize = 10.sp, letterSpacing = 0.3.sp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            BmsValue("SoH", soh?.let { "%.0f%%".format(it) } ?: "—", AccentGreen)
            BmsValue("Пробег", lifetimeKm?.let { "%.0f км".format(it) } ?: "—", TextPrimary)
            BmsValue("Прокачано", lifetimeKwh?.let { "%.0f кВт·ч".format(it) } ?: "—", TextPrimary)
            BmsValue("Расход /100км",
                avgPer100?.let { "%.1f".format(it) } ?: "—",
                AccentBlue)
        }
    }
}

@Composable
private fun BmsValue(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace)
        Text(label, color = TextMuted, fontSize = 10.sp)
    }
}

@Composable
private fun StatColumn(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace)
        Text(label, color = TextSecondary, fontSize = 11.sp)
    }
}

@Composable
private fun LineChart(
    values: List<Double>,
    lineColor: Color,
    warningThreshold: Double,
    criticalThreshold: Double,
    invertThresholds: Boolean = false,
    formatLabel: (Double) -> String,
    modifier: Modifier = Modifier
) {
    if (values.size < 2) return
    val minVal = values.min()
    val maxVal = values.max()
    val range = (maxVal - minVal).coerceAtLeast(0.001)
    Canvas(modifier = modifier.background(CardSurface, RoundedCornerShape(8.dp)).padding(8.dp)) {
        val w = size.width
        val h = size.height
        val stepX = w / (values.size - 1).coerceAtLeast(1)
        fun yForValue(v: Double): Float = (h - ((v - minVal) / range * h)).toFloat()
        if (warningThreshold in minVal..maxVal) {
            val y = yForValue(warningThreshold)
            drawLine(SocYellow.copy(alpha = 0.3f), Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }
        if (criticalThreshold in minVal..maxVal) {
            val y = yForValue(criticalThreshold)
            drawLine(SocRed.copy(alpha = 0.3f), Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * stepX
            val y = yForValue(v)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, lineColor,
            style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        values.forEachIndexed { i, v ->
            val x = i * stepX
            val y = yForValue(v)
            val dotColor = if (invertThresholds) {
                when {
                    v < criticalThreshold -> SocRed
                    v < warningThreshold -> SocYellow
                    else -> lineColor
                }
            } else {
                when {
                    v > criticalThreshold -> SocRed
                    v > warningThreshold -> SocYellow
                    else -> lineColor
                }
            }
            drawCircle(dotColor, radius = 4f, center = Offset(x, y))
        }
    }
}
