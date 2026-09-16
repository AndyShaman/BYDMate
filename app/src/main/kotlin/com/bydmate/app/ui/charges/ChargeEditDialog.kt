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
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.bydmate.app.data.local.entity.ChargeEntity
import com.bydmate.app.domain.cost.TariffSchedule
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.CardBorder
import com.bydmate.app.ui.theme.CardSurface
import com.bydmate.app.ui.theme.NavyDark
import com.bydmate.app.ui.theme.TextMuted
import com.bydmate.app.ui.theme.TextPrimary
import com.bydmate.app.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Edit dialog for a charge row (long-press → bottom sheet → "Изменить").
 *
 * Inputs: type (AC/DC), socStart, socEnd, meter reading, tariff per kWh.
 * kWh is derived: when both SOC values are present and socEnd > socStart, it is
 * computed as (socEnd - socStart) / 100 × batteryCapacityKwh and rendered read-only.
 * If SOC is incomplete (e.g. DC station with only a kWh readout), kWh becomes
 * an editable manual input as a fallback.
 * Cost = paid energy × tariff, where paid energy is the meter reading when one is typed
 * in, otherwise the pack intake grossed up by the period's losses. Touching the tariff or
 * the meter marks the row as priced by hand, and a recalculation then skips it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChargeEditDialog(
    charge: ChargeEntity,
    schedule: TariffSchedule,
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
    var meterText by remember(charge.id) {
        mutableStateOf(charge.meterKwh?.let { "%.2f".format(it) } ?: "")
    }
    var startTs by remember(charge.id) { mutableStateOf(charge.startTs) }
    var showDatePicker by remember(charge.id) { mutableStateOf(false) }

    // Rates follow the period covering the edited date, so backdating a session re-reads
    // the price that was in force then.
    val period = schedule.periodAt(startTs)
    val periodRate = period?.let { TariffSchedule.rateFor(it, type) } ?: 0.0
    val lossPct = period?.let { TariffSchedule.lossPctFor(it, type) } ?: 0.0

    // tariff_per_kwh = cost / paid energy (hand-priced record), or the period rate
    var tariffText by remember(charge.id) {
        val paidKwh = period?.let {
            TariffSchedule.energyPaid(it, charge.type, charge.kwhCharged, charge.meterKwh)
        }
        val existing = if (charge.costManual && charge.cost != null && (paidKwh ?: 0.0) > 0.01)
            charge.cost / paidKwh!! else null
        mutableStateOf("%.3f".format(existing ?: periodRate))
    }
    var costManual by remember(charge.id) { mutableStateOf(charge.costManual) }

    // Auto-fill tariff on AC↔DC switch and on a date change — only while the row still
    // follows its period.
    LaunchedEffect(type, startTs) {
        if (!costManual) tariffText = "%.3f".format(periodRate)
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
                        stringResource(R.string.charges_edit_dialog_title),
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )

                    // Date (start_ts) — editable so manual entries can be backdated
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.charges_edit_date_label),
                            color = TextSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.width(80.dp)
                        )
                        Text(
                            remember(startTs) {
                                SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(startTs))
                            },
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clickable { showDatePicker = true }
                                .padding(vertical = 8.dp, horizontal = 4.dp)
                        )
                    }

                    // Type radio
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.charges_edit_type_label),
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
                    val meterKwh = meterText.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 }
                    // Paid energy: the meter beats the estimate, the estimate grosses the pack
                    // intake up by the period's losses.
                    val paidKwh = meterKwh ?: finalKwh?.let { it / (1.0 - lossPct / 100.0) }
                    val computedCost = if (paidKwh != null && tariff != null) paidKwh * tariff else null

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
                            stringResource(R.string.charges_edit_kwh_label),
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
                                stringResource(R.string.charges_edit_kwh_from_soc, batteryCapacityKwh),
                                color = TextMuted,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }

                    // Meter reading — optional; what the wall socket actually billed.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.charges_edit_meter_label),
                            color = TextSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.width(80.dp)
                        )
                        OutlinedTextField(
                            value = meterText,
                            onValueChange = {
                                meterText = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' }
                                if (meterText.isNotBlank()) costManual = true
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.width(120.dp),
                            colors = darkFieldColors(),
                            singleLine = true,
                        )
                        Text(
                            if (meterKwh != null) stringResource(R.string.charges_edit_meter_used)
                            else stringResource(R.string.charges_edit_loss_estimate, lossPct),
                            color = TextMuted,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }

                    // Tariff per kWh
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.charges_edit_tariff_label, currencySymbol),
                            color = TextSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.width(80.dp)
                        )
                        OutlinedTextField(
                            value = tariffText,
                            onValueChange = {
                                tariffText = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' }
                                costManual = true
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
                            stringResource(R.string.charges_edit_cost_label),
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
                        if (costManual) {
                            TextButton(onClick = {
                                costManual = false
                                meterText = ""
                                tariffText = "%.3f".format(periodRate)
                            }) {
                                Text(
                                    stringResource(R.string.charges_edit_follow_period),
                                    color = AccentGreen,
                                    fontSize = 14.sp
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.settings_cancel_button), color = TextSecondary, fontSize = 14.sp)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                onSave(
                                    charge.copy(
                                        startTs = startTs,
                                        endTs = charge.endTs?.plus(startTs - charge.startTs),
                                        type = type,
                                        socStart = socStart,
                                        socEnd = socEnd,
                                        kwhCharged = finalKwh,
                                        kwhChargedSoc = computedKwh,
                                        cost = computedCost,
                                        meterKwh = meterKwh,
                                        costManual = costManual,
                                    )
                                )
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                        ) {
                            Text(
                                stringResource(R.string.charges_edit_save_button),
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

    if (showDatePicker) {
        val dpState = rememberDatePickerState(initialSelectedDateMillis = localDateAsUtcMidnight(startTs))
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dpState.selectedDateMillis?.let { picked ->
                        startTs = combineDateKeepingTime(picked, startTs)
                    }
                    showDatePicker = false
                }) {
                    Text(stringResource(R.string.charges_edit_save_button), color = AccentGreen, fontSize = 14.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.settings_cancel_button), color = TextSecondary, fontSize = 14.sp)
                }
            }
        ) {
            DatePicker(state = dpState)
        }
    }
}

/**
 * Material3 DatePicker reads/writes its selection in UTC, so seed it with the UTC
 * midnight of [ts]'s LOCAL calendar day. Without this, in UTC+ zones an early-morning
 * timestamp preselects the previous day. Mirror of [combineDateKeepingTime] on the way in.
 */
private fun localDateAsUtcMidnight(ts: Long): Long {
    val local = Calendar.getInstance().apply { timeInMillis = ts }
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

/**
 * Apply the picked day (DatePicker returns UTC midnight) onto [originalTs] while
 * keeping its local time-of-day, so editing the date never zeroes the clock.
 */
private fun combineDateKeepingTime(pickedUtcMidnightMs: Long, originalTs: Long): Long {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = pickedUtcMidnightMs }
    return Calendar.getInstance().apply {
        timeInMillis = originalTs
        set(Calendar.YEAR, utc.get(Calendar.YEAR))
        set(Calendar.MONTH, utc.get(Calendar.MONTH))
        set(Calendar.DAY_OF_MONTH, utc.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
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
