package com.bydmate.app.ui.charges

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bydmate.app.data.local.entity.ChargeEntity
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.CardBorder
import com.bydmate.app.ui.theme.CardSurface
import com.bydmate.app.ui.theme.NavyDark
import com.bydmate.app.ui.theme.TextMuted
import com.bydmate.app.ui.theme.TextPrimary
import com.bydmate.app.ui.theme.TextSecondary

/**
 * Edit dialog for a charge row (long-press → bottom sheet → "Изменить").
 *
 * Inputs: type (AC/DC), socStart, socEnd, tariff per kWh.
 * kWh is derived: when both SOC values are present and socEnd > socStart, it is
 * computed as (socEnd - socStart) / 100 × batteryCapacityKwh and rendered read-only.
 * If SOC is incomplete (e.g. DC station with only a kWh readout), kWh becomes
 * an editable manual input as a fallback.
 * Cost = finalKwh × tariff, displayed as a computed preview.
 */
@Composable
fun ChargeEditDialog(
    charge: ChargeEntity,
    homeTariff: Double,
    dcTariff: Double,
    batteryCapacityKwh: Double,
    currencySymbol: String,
    onDismiss: () -> Unit,
    onSave: (ChargeEntity) -> Unit,
) {
    var type by remember(charge.id) { mutableStateOf(charge.type ?: "AC") }
    var socStartText by remember(charge.id) { mutableStateOf(charge.socStart?.toString() ?: "") }
    var socEndText by remember(charge.id) { mutableStateOf(charge.socEnd?.toString() ?: "") }
    var kwhText by remember(charge.id) {
        mutableStateOf(charge.kwhCharged?.let { "%.2f".format(it) } ?: "")
    }
    // tariff_per_kwh = cost / kwh (existing record), or settings default by type
    var tariffText by remember(charge.id) {
        val existing = if (charge.cost != null && (charge.kwhCharged ?: 0.0) > 0.01)
            charge.cost / charge.kwhCharged!! else null
        val initial = existing ?: if (type == "DC") dcTariff else homeTariff
        mutableStateOf("%.3f".format(initial))
    }

    // Auto-fill tariff on AC↔DC switch — only when user hasn't manually edited the field
    var tariffEditedByUser by remember(charge.id) { mutableStateOf(false) }
    LaunchedEffect(type) {
        if (!tariffEditedByUser) {
            val auto = if (type == "DC") dcTariff else homeTariff
            tariffText = "%.3f".format(auto)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onDismiss() },
            contentAlignment = Alignment.CenterStart
        ) {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = CardSurface),
                border = BorderStroke(1.dp, CardBorder),
                modifier = Modifier
                    .padding(start = 22.dp, end = 16.dp)
                    .fillMaxWidth(0.5f)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { /* absorb clicks so scrim dismissal doesn't fire — form has inputs */ }
            ) {
                Column(
                    modifier = Modifier
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Изменить зарядку",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )

                    // Type radio
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Тип:",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.width(80.dp)
                        )
                        TypeRadio("AC", type == "AC") { type = "AC" }
                        Spacer(modifier = Modifier.width(8.dp))
                        TypeRadio("DC", type == "DC") { type = "DC" }
                    }

                    // Derived values — SOC drives kWh, kWh × tariff drives cost
                    val socStart = socStartText.toIntOrNull()
                    val socEnd = socEndText.toIntOrNull()
                    val computedKwh = if (socStart != null && socEnd != null && socEnd > socStart)
                        (socEnd - socStart) / 100.0 * batteryCapacityKwh else null
                    val kwhEditable = computedKwh == null
                    val kwhDisplay = computedKwh?.let { "%.2f".format(it) } ?: kwhText
                    val tariff = tariffText.replace(',', '.').toDoubleOrNull()
                    val finalKwh = computedKwh ?: kwhText.replace(',', '.').toDoubleOrNull()
                    val computedCost = if (finalKwh != null && tariff != null) finalKwh * tariff else null

                    // SOC start / end
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "SOC %:",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.width(80.dp)
                        )
                        OutlinedTextField(
                            value = socStartText,
                            onValueChange = { socStartText = it.filter { ch -> ch.isDigit() }.take(3) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(80.dp),
                            colors = darkFieldColors(),
                            singleLine = true,
                        )
                        Text(
                            " → ",
                            color = TextMuted,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                        OutlinedTextField(
                            value = socEndText,
                            onValueChange = { socEndText = it.filter { ch -> ch.isDigit() }.take(3) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(80.dp),
                            colors = darkFieldColors(),
                            singleLine = true,
                        )
                    }

                    // kWh — computed from SOC × battery capacity when both SOC are filled,
                    // otherwise editable (DC fallback when station shows kWh but SOC is unknown).
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "кВт·ч:",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.width(80.dp)
                        )
                        OutlinedTextField(
                            value = kwhDisplay,
                            onValueChange = {
                                if (kwhEditable) {
                                    kwhText = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' }
                                }
                            },
                            enabled = kwhEditable,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.width(120.dp),
                            colors = darkFieldColors(),
                            singleLine = true,
                        )
                        if (!kwhEditable) {
                            Text(
                                "из SOC × ${"%.1f".format(batteryCapacityKwh)} кВт·ч",
                                color = TextMuted,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }

                    // Tariff per kWh
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "$currencySymbol/кВт·ч:",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.width(80.dp)
                        )
                        OutlinedTextField(
                            value = tariffText,
                            onValueChange = {
                                tariffText = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' }
                                tariffEditedByUser = true
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.width(120.dp),
                            colors = darkFieldColors(),
                            singleLine = true,
                        )
                    }

                    // Cost — computed read-only preview
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Стоимость:",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.width(80.dp)
                        )
                        Text(
                            computedCost?.let { "%.2f $currencySymbol".format(it) } ?: "—",
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // Action buttons
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text("Отмена", color = TextSecondary, fontSize = 14.sp)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                onSave(
                                    charge.copy(
                                        type = type,
                                        socStart = socStart,
                                        socEnd = socEnd,
                                        kwhCharged = finalKwh,
                                        kwhChargedSoc = computedKwh,
                                        cost = computedCost,
                                    )
                                )
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                        ) {
                            Text(
                                "Сохранить",
                                color = NavyDark,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TypeRadio(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(end = 4.dp)
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(
                selectedColor = AccentGreen,
                unselectedColor = TextMuted
            ),
        )
        Text(label, color = TextPrimary, fontSize = 13.sp)
    }
}

@Composable
private fun darkFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    disabledTextColor = TextMuted,
    focusedBorderColor = AccentGreen,
    unfocusedBorderColor = CardBorder,
    disabledBorderColor = CardBorder,
    cursorColor = AccentGreen,
)
