package com.bydmate.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bydmate.app.R
import com.bydmate.app.data.repository.SettingsRepository.ManualRangePoint
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.CardSurface
import com.bydmate.app.ui.theme.NavyDark
import com.bydmate.app.ui.theme.TextMuted
import com.bydmate.app.ui.theme.TextPrimary

/**
 * Editable temperature -> consumption table for RangeCalcMethod.MANUAL, ported from the
 * nordpool1hprices companion app's BatteryConsumptionSettingsDialog. Fixed rows at the same
 * five reference temperatures; consumption is required, the 100%-SOC range column is optional
 * (ManualRangeCalculator derives effective capacity from it when present).
 */
@Composable
fun ManualRangeTableDialog(
    currentTable: List<ManualRangePoint>,
    onDismiss: () -> Unit,
    onSave: (List<ManualRangePoint>) -> Unit,
    onReset: () -> Unit,
) {
    val fixedTemperatures = listOf(20, 10, 0, -10, -20)
    val byTemp = remember(currentTable) { currentTable.associateBy { it.temperatureC } }
    val rows = remember(currentTable) {
        mutableStateListOf<EditableRow>().apply {
            fixedTemperatures.forEach { temp ->
                val p = byTemp[temp]
                add(
                    EditableRow(
                        temperature = temp,
                        consumption = p?.consumptionKwhPer100Km?.toString() ?: "",
                        range = p?.rangeKmAt100Soc?.toString() ?: "",
                    )
                )
            }
        }
    }

    val parsedPoints = rows.mapNotNull { row ->
        val consumption = row.consumption.toDoubleOrNull()
        if (consumption != null) {
            ManualRangePoint(
                temperatureC = row.temperature,
                consumptionKwhPer100Km = consumption,
                rangeKmAt100Soc = row.range.toDoubleOrNull(),
            )
        } else {
            null
        }
    }
    val canSave = parsedPoints.size == rows.size

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = CardSurface),
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    stringResource(R.string.range_table_dialog_title),
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(R.string.range_table_dialog_note1),
                    color = TextMuted,
                    fontSize = 12.sp,
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.range_table_dialog_col_temp),
                        color = TextMuted, fontSize = 11.sp,
                        modifier = Modifier.width(40.dp),
                    )
                    Text(
                        stringResource(R.string.range_table_dialog_col_consumption),
                        color = TextMuted, fontSize = 11.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        stringResource(R.string.range_table_dialog_col_range),
                        color = TextMuted, fontSize = 11.sp,
                        modifier = Modifier.weight(0.8f),
                    )
                }

                rows.forEachIndexed { index, row ->
                    RangeTableRow(
                        row = row,
                        onConsumptionChange = { rows[index] = row.copy(consumption = it) },
                        onRangeChange = { rows[index] = row.copy(range = it) },
                    )
                }

                OutlinedButton(
                    onClick = onReset,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentGreen),
                ) { Text(stringResource(R.string.range_table_dialog_reset)) }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentGreen),
                    ) { Text(stringResource(R.string.range_table_dialog_cancel)) }

                    Button(
                        onClick = { onSave(parsedPoints) },
                        modifier = Modifier.weight(1f),
                        enabled = canSave,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = NavyDark),
                    ) { Text(stringResource(R.string.range_table_dialog_save)) }
                }
            }
        }
    }
}

private data class EditableRow(val temperature: Int, val consumption: String, val range: String)

@Composable
private fun RangeTableRow(
    row: EditableRow,
    onConsumptionChange: (String) -> Unit,
    onRangeChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${row.temperature}°", color = TextPrimary, fontSize = 13.sp, modifier = Modifier.width(40.dp))
        OutlinedTextField(
            value = row.consumption,
            onValueChange = { if (it.isEmpty() || it.toDoubleOrNull() != null) onConsumptionChange(it) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                focusedBorderColor = AccentGreen,
            ),
        )
        OutlinedTextField(
            value = row.range,
            onValueChange = { if (it.isEmpty() || it.toDoubleOrNull() != null) onRangeChange(it) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(0.8f),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                focusedBorderColor = AccentGreen,
            ),
        )
    }
}
