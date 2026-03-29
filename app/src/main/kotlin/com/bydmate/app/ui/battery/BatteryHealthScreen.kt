package com.bydmate.app.ui.battery

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bydmate.app.ui.theme.*

@Composable
fun BatteryHealthScreen(
    viewModel: BatteryHealthViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(NavyDark, NavyDeep)))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text("Здоровье батареи", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(12.dp))

        if (state.charges.isEmpty() && !state.isLoading) {
            Text(
                "Нет данных. Информация появится после зарядок с подключённым DiPlus.",
                color = TextSecondary, fontSize = 14.sp
            )
        } else {
            // Stats row
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = CardSurface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatColumn("Текущий дельта", state.currentDelta?.let { "%.3fV".format(it) } ?: "—")
                    StatColumn("Средний дельта", state.avgDelta?.let { "%.3fV".format(it) } ?: "—")
                    StatColumn("12V текущий", state.currentVoltage12v?.let { "%.1fV".format(it) } ?: "—")
                    StatColumn("12V минимум", state.minVoltage12v?.let { "%.1fV".format(it) } ?: "—")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Cell voltage delta chart
            Text("Баланс ячеек (дельта V)", color = TextSecondary, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(4.dp))

            val deltaValues = state.charges.reversed().mapNotNull { c ->
                if (c.cellVoltageMax != null && c.cellVoltageMin != null)
                    c.cellVoltageMax - c.cellVoltageMin else null
            }
            if (deltaValues.isNotEmpty()) {
                LineChart(
                    values = deltaValues,
                    lineColor = AccentGreen,
                    warningThreshold = 0.05,
                    criticalThreshold = 0.10,
                    formatLabel = { "%.3f".format(it) },
                    modifier = Modifier.fillMaxWidth().height(150.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 12V voltage chart
            Text("Напряжение 12V батареи", color = TextSecondary, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(4.dp))

            val voltage12vValues = state.charges.reversed().mapNotNull { it.voltage12v }
            if (voltage12vValues.isNotEmpty()) {
                LineChart(
                    values = voltage12vValues,
                    lineColor = AccentBlue,
                    warningThreshold = 12.4,
                    criticalThreshold = 11.8,
                    invertThresholds = true,
                    formatLabel = { "%.1f".format(it) },
                    modifier = Modifier.fillMaxWidth().height(150.dp)
                )
            }
        }
    }
}

@Composable
private fun StatColumn(label: String, value: String) {
    Column {
        Text(value, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Text(label, color = TextSecondary, fontSize = 12.sp)
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

        // Draw threshold lines
        fun yForValue(v: Double): Float = (h - ((v - minVal) / range * h)).toFloat()

        // Warning threshold line
        if (warningThreshold in minVal..maxVal) {
            val y = yForValue(warningThreshold)
            drawLine(SocYellow.copy(alpha = 0.3f), Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }
        // Critical threshold line
        if (criticalThreshold in minVal..maxVal || (!invertThresholds && criticalThreshold > minVal)) {
            val clamped = criticalThreshold.coerceIn(minVal, maxVal)
            val y = yForValue(clamped)
            drawLine(SocRed.copy(alpha = 0.3f), Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }

        // Draw line
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * stepX
            val y = yForValue(v)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // Draw dots
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
