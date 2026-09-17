package com.bydmate.app.ui.settings

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bydmate.app.R
import com.bydmate.app.data.local.entity.TariffPeriodEntity
import com.bydmate.app.data.local.entity.TariffPeriodEntity.Companion.TRIP_RULE_CHARGES
import com.bydmate.app.data.local.entity.TariffPeriodEntity.Companion.TRIP_RULE_DC
import com.bydmate.app.data.local.entity.TariffPeriodEntity.Companion.TRIP_RULE_HOME
import com.bydmate.app.domain.cost.MeasuredLosses
import com.bydmate.app.ui.tech.TechCard
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.AccentOrange
import com.bydmate.app.ui.theme.CardBorder
import com.bydmate.app.ui.theme.CardSurface
import com.bydmate.app.ui.theme.CardSurfaceElevated
import com.bydmate.app.ui.theme.NavyDark
import com.bydmate.app.ui.theme.NavyDeep
import com.bydmate.app.ui.theme.TextMuted
import com.bydmate.app.ui.theme.TextPrimary
import com.bydmate.app.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Width of the period column; the form takes the rest of the screen. */
private val LIST_WIDTH = 260.dp

/** Every field of the form: the label column, then the input next to it. */
private val FIELD_LABEL_WIDTH = 150.dp
private val FIELD_INPUT_WIDTH = 120.dp

/** Rates carry three decimals, the loss percentages are whole numbers. */
private const val RATE_DECIMALS = 3
private const val LOSS_DECIMALS = 0

/**
 * Price-history screen, built like «Техника»: the list of periods on the left, the form of the
 * selected one on the right. Saving a period re-prices everything from its date onwards, which
 * is how a rate entered in the middle of the month reaches the charges already recorded that
 * month.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TariffPeriodsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Newest first, mirroring the list order the view model keeps.
    val sorted = remember(state.tariffPeriods) { state.tariffPeriods.sortedByDescending { it.startTs } }
    var draft by remember { mutableStateOf(sorted.firstOrNull()) }
    var showDatePicker by remember { mutableStateOf(false) }
    // Bumped on every switch of the edited period, so the text fields re-seed from it instead
    // of showing the rates of the one edited before.
    var formKey by remember { mutableStateOf(0) }
    LaunchedEffect(state.tariffPeriods) {
        draft = sorted.firstOrNull()
        formKey++
    }
    // Leaving the screen drops the recalc status and the error, as closing the window did.
    DisposableEffect(Unit) {
        onDispose { viewModel.hideTariffPeriods() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(NavyDark, NavyDeep)))
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        // Header in the «Техника» shape; the hint sits where that screen keeps its status.
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .border(1.5.dp, TextMuted, CircleShape)
                    .clickable { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Text("‹", color = TextSecondary, fontSize = 16.sp)
            }
            Text(
                stringResource(R.string.settings_tariff_periods_title),
                color = AccentGreen,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 14.dp)
            )
            Box(modifier = Modifier.weight(1f))
            Text(
                stringResource(R.string.settings_tariff_periods_hint),
                color = TextMuted,
                fontSize = 11.sp
            )
        }
        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            PeriodList(
                periods = sorted,
                selectedId = draft?.id,
                currencySymbol = state.currencySymbol,
                measuredLosses = state.measuredLosses,
                onSelect = { draft = it; formKey++ },
                onAdd = { draft = newPeriodDraft(sorted); formKey++ },
                modifier = Modifier.width(LIST_WIDTH),
            )
            Spacer(modifier = Modifier.width(20.dp))
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                draft?.let { current ->
                    PeriodForm(
                        period = current,
                        formKey = formKey,
                        currencySymbol = state.currencySymbol,
                        onChange = { draft = it },
                        onPickDate = { showDatePicker = true },
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The earliest period covers everything before itself, so it stays.
                    if (sorted.size > 1 && draft?.id?.let { it != 0L } == true) {
                        TextButton(onClick = {
                            draft?.let { viewModel.deleteTariffPeriod(it) }
                            draft = null
                        }) {
                            Text(
                                stringResource(R.string.settings_tariff_period_delete),
                                color = AccentOrange,
                                fontSize = 13.sp,
                            )
                        }
                    }
                    state.tariffRecalcStatus?.let {
                        Text(it, color = AccentGreen, fontSize = 12.sp, modifier = Modifier.padding(start = 12.dp))
                    }
                    state.tariffPeriodError?.let {
                        Text(it, color = AccentOrange, fontSize = 12.sp, modifier = Modifier.padding(start = 12.dp))
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.settings_cancel_button), color = TextSecondary, fontSize = 14.sp)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { draft?.let { viewModel.saveTariffPeriod(it) } },
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
        // The scrolling block takes the whole column, so the measured-losses line under it sits
        // at the bottom of the screen however many periods there are.
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
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
            TextButton(onClick = onAdd, modifier = Modifier.padding(top = 4.dp)) {
                Text(stringResource(R.string.settings_tariff_period_add), color = AccentGreen, fontSize = 13.sp)
            }
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
                modifier = Modifier.padding(top = 8.dp, start = 4.dp),
            )
        }
    }
}

/**
 * Two cards in the «Техника» style: the dates and rates of the period, then the rule the trips
 * of that period are costed by. The rates sit in two columns, so the form needs no scrolling.
 */
@Composable
private fun PeriodForm(
    period: TariffPeriodEntity,
    formKey: Int,
    currencySymbol: String,
    onChange: (TariffPeriodEntity) -> Unit,
    onPickDate: () -> Unit,
) {
    TechCard(stringResource(R.string.settings_tariff_card_period)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.settings_tariff_period_start_label),
                color = TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.width(FIELD_LABEL_WIDTH),
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
        Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            NumberField(
                formKey = formKey,
                label = stringResource(R.string.settings_tariff_home_label, currencySymbol),
                value = period.homeRate,
                decimals = RATE_DECIMALS,
                onValue = { onChange(period.copy(homeRate = it)) },
                modifier = Modifier.weight(1f),
            )
            NumberField(
                formKey = formKey,
                label = stringResource(R.string.settings_tariff_dc_label, currencySymbol),
                value = period.dcRate,
                decimals = RATE_DECIMALS,
                onValue = { onChange(period.copy(dcRate = it)) },
                modifier = Modifier.weight(1f),
            )
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
            NumberField(
                formKey = formKey,
                label = stringResource(R.string.settings_tariff_loss_ac_label),
                value = period.acLossPct,
                decimals = LOSS_DECIMALS,
                onValue = { onChange(period.copy(acLossPct = it)) },
                modifier = Modifier.weight(1f),
            )
            NumberField(
                formKey = formKey,
                label = stringResource(R.string.settings_tariff_loss_dc_label),
                value = period.dcLossPct,
                decimals = LOSS_DECIMALS,
                onValue = { onChange(period.copy(dcLossPct = it)) },
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            stringResource(R.string.settings_tariff_loss_hint),
            color = TextMuted,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 10.dp),
        )
    }

    Spacer(modifier = Modifier.height(12.dp))

    TechCard(stringResource(R.string.settings_tariff_trip_label)) {
        val isCustom = period.tripRule !in listOf(TRIP_RULE_HOME, TRIP_RULE_DC, TRIP_RULE_CHARGES)
        val options = listOf(
            TRIP_RULE_HOME to stringResource(R.string.settings_tariff_rule_home),
            TRIP_RULE_DC to stringResource(R.string.settings_tariff_rule_dc),
            TRIP_RULE_CHARGES to stringResource(R.string.settings_tariff_rule_charges),
        )
        val customLabel = stringResource(R.string.settings_tariff_trip_custom_chip)
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 6.dp),
        ) {
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
                decimals = RATE_DECIMALS,
                onValue = { onChange(period.copy(tripRule = "%.3f".format(Locale.US, it))) },
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Text(
            stringResource(R.string.settings_tariff_trip_desc),
            color = TextMuted,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 10.dp),
        )
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
 * changes when another period is selected: that is the only moment the text is re-seeded, with
 * [decimals] places.
 */
@Composable
private fun NumberField(
    formKey: Int,
    label: String,
    value: Double,
    decimals: Int,
    onValue: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember(formKey, label) { mutableStateOf("%.${decimals}f".format(Locale.US, value)) }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.width(FIELD_LABEL_WIDTH))
        OutlinedTextField(
            value = text,
            onValueChange = { raw ->
                text = raw.filter { it.isDigit() || it == '.' || it == ',' }
                text.replace(',', '.').toDoubleOrNull()?.let(onValue)
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.width(FIELD_INPUT_WIDTH),
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
