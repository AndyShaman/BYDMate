package com.bydmate.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import com.bydmate.app.data.local.entity.TariffPeriodEntity
import com.bydmate.app.data.local.entity.TariffPeriodEntity.Companion.TRIP_RULE_CHARGES
import com.bydmate.app.data.local.entity.TariffPeriodEntity.Companion.TRIP_RULE_DC
import com.bydmate.app.data.local.entity.TariffPeriodEntity.Companion.TRIP_RULE_HOME
import com.bydmate.app.domain.cost.MeasuredLosses
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.AccentOrange
import com.bydmate.app.ui.theme.CardBorder
import com.bydmate.app.ui.theme.CardSurface
import com.bydmate.app.ui.theme.CardSurfaceElevated
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
 * Floating price-history window: the list of periods on the left, the form of the selected
 * one on the right. Saving a period re-prices everything from its date onwards, which is how
 * a rate entered in the middle of the month reaches the charges already recorded that month.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TariffPeriodsDialog(
    periods: List<TariffPeriodEntity>,
    currencySymbol: String,
    measuredLosses: MeasuredLosses?,
    recalcStatus: String?,
    errorText: String?,
    onSave: (TariffPeriodEntity) -> Unit,
    onDelete: (TariffPeriodEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    // Newest first, mirroring the list order the caller passes in.
    val sorted = remember(periods) { periods.sortedByDescending { it.startTs } }
    var draft by remember { mutableStateOf(sorted.firstOrNull()) }
    var showDatePicker by remember { mutableStateOf(false) }
    // Bumped on every switch of the edited period, so the text fields re-seed from it instead
    // of showing the rates of the one edited before.
    var formKey by remember { mutableStateOf(0) }
    LaunchedEffect(periods) {
        draft = sorted.firstOrNull()
        formKey++
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = CardSurface),
                border = BorderStroke(1.dp, CardBorder),
                modifier = Modifier
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .fillMaxWidth(0.82f)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { /* absorb clicks: the form has inputs */ }
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        stringResource(R.string.settings_tariff_periods_title),
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Row(modifier = Modifier.padding(top = 12.dp)) {
                        PeriodList(
                            periods = sorted,
                            selectedId = draft?.id,
                            currencySymbol = currencySymbol,
                            measuredLosses = measuredLosses,
                            onSelect = { draft = it; formKey++ },
                            onAdd = { draft = newPeriodDraft(sorted); formKey++ },
                            modifier = Modifier.width(260.dp),
                        )
                        Spacer(modifier = Modifier.width(20.dp))
                        draft?.let { current ->
                            PeriodForm(
                                period = current,
                                formKey = formKey,
                                currencySymbol = currencySymbol,
                                canDelete = sorted.size > 1 && current.id != 0L,
                                onChange = { draft = it },
                                onPickDate = { showDatePicker = true },
                                onDelete = {
                                    onDelete(current)
                                    draft = null
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    recalcStatus?.let {
                        Text(it, color = AccentGreen, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                    errorText?.let {
                        Text(it, color = AccentOrange, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.settings_cancel_button), color = TextSecondary, fontSize = 14.sp)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { draft?.let(onSave) },
                            enabled = draft != null,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                        ) {
                            Text(
                                stringResource(R.string.charges_edit_save_button),
                                color = NavyDark,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }

    val editing = draft
    if (showDatePicker && editing != null) {
        val dpState = rememberDatePickerState(initialSelectedDateMillis = localDateAsUtcMidnight(editing.startTs))
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dpState.selectedDateMillis?.let { picked ->
                        draft = editing.copy(startTs = utcMidnightAsLocalMidnight(picked))
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
            },
        ) {
            DatePicker(state = dpState)
        }
    }
}

@Composable
private fun PeriodList(
    periods: List<TariffPeriodEntity>,
    selectedId: Long?,
    currencySymbol: String,
    measuredLosses: MeasuredLosses?,
    onSelect: (TariffPeriodEntity) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxHeight()) {
        Column(
            modifier = Modifier
                .heightIn(max = 260.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (period in periods) {
                val selected = period.id == selectedId
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (selected) CardSurfaceElevated else CardSurface,
                            RoundedCornerShape(8.dp)
                        )
                        .clickable { onSelect(period) }
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Text(
                        periodDateLabel(period.startTs),
                        color = if (selected) AccentGreen else TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(
                            R.string.settings_tariff_periods_summary,
                            "%.2f".format(period.homeRate), "%.2f".format(period.dcRate), currencySymbol,
                            "%.0f".format(period.acLossPct), "%.0f".format(period.dcLossPct),
                        ),
                        color = TextMuted,
                        fontSize = 11.sp,
                    )
                }
            }
        }
        TextButton(onClick = onAdd, modifier = Modifier.padding(top = 4.dp)) {
            Text(stringResource(R.string.settings_tariff_period_add), color = AccentGreen, fontSize = 13.sp)
        }
        measuredLosses?.takeIf { it.hasAny }?.let { losses ->
            Text(
                stringResource(
                    R.string.settings_tariff_losses_measured,
                    losses.acPct?.let { "%.0f".format(it) } ?: "—", losses.acSamples,
                    losses.dcPct?.let { "%.0f".format(it) } ?: "—", losses.dcSamples,
                ),
                color = TextSecondary,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun PeriodForm(
    period: TariffPeriodEntity,
    formKey: Int,
    currencySymbol: String,
    canDelete: Boolean,
    onChange: (TariffPeriodEntity) -> Unit,
    onPickDate: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.settings_tariff_period_start_label),
                color = TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.width(120.dp),
            )
            Text(
                periodDateLabel(period.startTs),
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable { onPickDate() }
                    .padding(vertical = 8.dp, horizontal = 4.dp),
            )
        }
        NumberField(
            formKey = formKey,
            label = stringResource(R.string.settings_tariff_home_label, currencySymbol),
            value = period.homeRate,
            onValue = { onChange(period.copy(homeRate = it)) },
        )
        NumberField(
            formKey = formKey,
            label = stringResource(R.string.settings_tariff_dc_label, currencySymbol),
            value = period.dcRate,
            onValue = { onChange(period.copy(dcRate = it)) },
        )
        NumberField(
            formKey = formKey,
            label = stringResource(R.string.settings_tariff_loss_ac_label),
            value = period.acLossPct,
            onValue = { onChange(period.copy(acLossPct = it)) },
        )
        NumberField(
            formKey = formKey,
            label = stringResource(R.string.settings_tariff_loss_dc_label),
            value = period.dcLossPct,
            onValue = { onChange(period.copy(dcLossPct = it)) },
        )
        Text(stringResource(R.string.settings_tariff_loss_hint), color = TextMuted, fontSize = 11.sp)

        Text(stringResource(R.string.settings_tariff_trip_label), color = TextSecondary, fontSize = 13.sp)
        val isCustom = period.tripRule !in listOf(TRIP_RULE_HOME, TRIP_RULE_DC, TRIP_RULE_CHARGES)
        val options = listOf(
            TRIP_RULE_HOME to stringResource(R.string.settings_tariff_rule_home),
            TRIP_RULE_DC to stringResource(R.string.settings_tariff_rule_dc),
            TRIP_RULE_CHARGES to stringResource(R.string.settings_tariff_rule_charges),
        )
        val customLabel = stringResource(R.string.settings_tariff_trip_custom_chip)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((rule, label) in options) {
                RuleChip(label, period.tripRule == rule) { onChange(period.copy(tripRule = rule)) }
            }
            RuleChip(customLabel, isCustom) {
                onChange(period.copy(tripRule = "%.3f".format(Locale.US, period.homeRate)))
            }
        }
        if (isCustom) {
            NumberField(
                formKey = formKey,
                label = stringResource(R.string.settings_tariff_custom_label, currencySymbol),
                value = period.tripRule.replace(',', '.').toDoubleOrNull() ?: period.homeRate,
                onValue = { onChange(period.copy(tripRule = "%.3f".format(Locale.US, it))) },
            )
        }
        Text(stringResource(R.string.settings_tariff_trip_desc), color = TextMuted, fontSize = 11.sp)

        if (canDelete) {
            TextButton(onClick = onDelete) {
                Text(stringResource(R.string.settings_tariff_period_delete), color = AccentOrange, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun RuleChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) NavyDark else TextSecondary,
        fontSize = 12.sp,
        modifier = Modifier
            .background(if (selected) AccentGreen else CardSurfaceElevated, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/**
 * Decimal field that keeps the raw text while typing, so "0," does not snap back. [formKey]
 * changes when another period is selected: that is the only moment the text is re-seeded.
 */
@Composable
private fun NumberField(formKey: Int, label: String, value: Double, onValue: (Double) -> Unit) {
    var text by remember(formKey, label) { mutableStateOf("%.3f".format(Locale.US, value)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.width(120.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { raw ->
                text = raw.filter { it.isDigit() || it == '.' || it == ',' }
                text.replace(',', '.').toDoubleOrNull()?.let(onValue)
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.width(120.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                focusedBorderColor = AccentGreen,
                unfocusedBorderColor = CardBorder,
                cursorColor = AccentGreen,
            ),
            singleLine = true,
        )
    }
}

/** A brand-new period defaults to today; the id stays 0 until it is saved. */
private fun newPeriodDraft(existing: List<TariffPeriodEntity>): TariffPeriodEntity {
    val latest = existing.firstOrNull()
    return TariffPeriodEntity(
        startTs = todayLocalMidnight(),
        homeRate = latest?.homeRate ?: 0.20,
        dcRate = latest?.dcRate ?: 0.73,
        acLossPct = latest?.acLossPct ?: TariffPeriodEntity.DEFAULT_AC_LOSS_PCT,
        dcLossPct = latest?.dcLossPct ?: TariffPeriodEntity.DEFAULT_DC_LOSS_PCT,
        tripRule = latest?.tripRule ?: TRIP_RULE_HOME,
    )
}

private fun todayLocalMidnight(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

@Composable
private fun periodDateLabel(startTs: Long): String =
    if (startTs <= 0L) stringResource(R.string.settings_tariff_period_from_start)
    else remember(startTs) {
        SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(startTs))
    }

/** Material3 pickers work in UTC; seed them with the UTC midnight of the LOCAL day. */
private fun localDateAsUtcMidnight(ts: Long): Long {
    val local = Calendar.getInstance().apply { timeInMillis = if (ts > 0L) ts else System.currentTimeMillis() }
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

/** Mirror of [localDateAsUtcMidnight]: a period starts at local midnight of the picked day. */
private fun utcMidnightAsLocalMidnight(pickedUtcMidnightMs: Long): Long {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = pickedUtcMidnightMs }
    return Calendar.getInstance().apply {
        clear()
        set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}
