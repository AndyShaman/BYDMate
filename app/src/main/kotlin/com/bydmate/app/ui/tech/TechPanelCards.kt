package com.bydmate.app.ui.tech

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bydmate.app.R
import com.bydmate.app.data.nativestack.MotorSplit
import com.bydmate.app.data.nativestack.motorSplitPercent
import com.bydmate.app.domain.battery.BatteryCalibration
import com.bydmate.app.ui.components.HelpIcon
import com.bydmate.app.ui.theme.AccentBlue
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.AccentOrange
import com.bydmate.app.ui.theme.CardBorder
import com.bydmate.app.ui.theme.SocRed
import com.bydmate.app.ui.theme.SocYellow
import com.bydmate.app.ui.theme.TextMuted
import com.bydmate.app.ui.theme.TextPrimary
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

// ============================================================================
// Cards
// ============================================================================

@Composable
internal fun BatteryNowCard(state: TechPanelUiState, onHint: (String) -> Unit, modifier: Modifier = Modifier) {
    val insulationMohm = state.insulationKohm?.let { it / 1000.0 }
    TechCard(stringResource(R.string.tech_card_battery_now), modifier = modifier) {
        TechRow("SoC", state.soc?.let { "$it%" } ?: DASH)
        TechRow(
            stringResource(R.string.tech_label_remain_kwh),
            state.remainKwh?.let { stringResource(R.string.tech_value_kwh, it) } ?: DASH,
            hintKey = "remainKwh", onHint = onHint,
        )
        TechRow(
            "SoH",
            state.soh?.let { "%.0f%%".format(it) } ?: DASH,
            valueColor = if (state.soh != null) AccentGreen else TextPrimary,
            hintKey = "soh", onHint = onHint,
        )
        TechRow(
            stringResource(R.string.battery_health_bat_temp_label),
            state.batTemp?.let { stringResource(R.string.tech_value_temp, it) } ?: DASH,
            valueColor = batTempColor(state.batTemp),
        )
        TechRow(
            stringResource(R.string.tech_label_hv_voltage),
            state.hvVoltage?.let { stringResource(R.string.tech_value_volt, it) } ?: DASH,
            hintKey = "hvVoltage", onHint = onHint,
        )
        TechRow(
            stringResource(R.string.tech_label_power),
            state.powerKw?.let { stringResource(R.string.tech_value_kw, it.roundToInt()) } ?: DASH,
            valueColor = if ((state.powerKw ?: 0.0) < 0.0) AccentBlue else TextPrimary,
            hintKey = "power", onHint = onHint,
        )
        // #153: negative = the pack is taking energy in (green), positive = it is giving it away.
        TechRow(
            stringResource(R.string.tech_label_battery_power),
            state.batteryPowerW?.let { w ->
                val watts = stringResource(R.string.tech_value_watt, w.roundToInt())
                state.percentPerHour
                    ?.let { "$watts · " + stringResource(R.string.tech_value_percent_per_hour, it) }
                    ?: watts
            } ?: DASH,
            valueColor = when {
                state.batteryPowerW == null -> TextPrimary
                state.batteryPowerW < 0.0 -> AccentGreen
                else -> SocYellow
            },
            hintKey = "batteryPower", onHint = onHint,
        )
        TechRow(
            stringResource(R.string.battery_health_12v_label),
            state.voltage12v?.let { stringResource(R.string.battery_health_12v_value, it) } ?: DASH,
            valueColor = voltage12vColor(state.voltage12v),
            hintKey = "voltage12v", onHint = onHint,
        )
        TechRow(
            stringResource(R.string.dashboard_battery_insulation_label),
            insulationMohm?.let { stringResource(R.string.tech_value_mohm, it) } ?: DASH,
            valueColor = insulationColor(insulationMohm),
            hintKey = "insulation", onHint = onHint,
        )
        Hint(state.openHint, "remainKwh", R.string.tech_hint_remain_kwh)
        Hint(state.openHint, "soh", R.string.tech_hint_soh)
        Hint(state.openHint, "hvVoltage", R.string.tech_hint_hv_voltage)
        Hint(state.openHint, "power", R.string.tech_hint_motor_power)
        Hint(state.openHint, "batteryPower", R.string.tech_hint_power)
        Hint(state.openHint, "voltage12v", R.string.tech_hint_12v)
        Hint(state.openHint, "insulation", R.string.tech_hint_insulation)
    }
}

/**
 * «Полных циклов» — how many full pack charges the car has taken, off the BMS lifetime counter
 * right above it and off the app's own charging sum on a car that reports none, with the sum it
 * was divided by right under the value so the number is not a black box.
 */
@Composable
private fun FullCyclesRow(state: TechPanelUiState) {
    val cycles = TechPanelVisuals.fullCycles(
        state.lifetimeKwh?.toDouble(), state.lifetimeChargedKwh, state.nominalCapacityKwh, state.soh)
    TechRow(stringResource(R.string.tech_label_full_cycles), cycles?.value ?: DASH)
    Text(
        cycles
            ?.let { stringResource(R.string.tech_caption_full_cycles, it.totalKwh, it.capacity) }
            ?: stringResource(R.string.tech_caption_full_cycles_empty),
        color = TextMuted,
        fontSize = 10.sp,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.End,
    )
}

@Composable
internal fun LimitsAndCellsCard(state: TechPanelUiState, onHint: (String) -> Unit, modifier: Modifier = Modifier) {
    TechCard(
        stringResource(R.string.tech_card_limits),
        hintKey = "bmsLimits",
        onHint = onHint,
        modifier = modifier,
    ) {
        TechRow(
            stringResource(R.string.tech_label_max_charge),
            state.bmsMaxChargeKw?.let { stringResource(R.string.tech_value_kw, it.roundToInt()) } ?: DASH,
        )
        TechRow(
            stringResource(R.string.tech_label_max_discharge_allow),
            state.bmsMaxDischargeAllowKw?.let { stringResource(R.string.tech_value_kw, it.roundToInt()) } ?: DASH,
        )
        TechRow(
            stringResource(R.string.tech_label_max_discharge),
            state.bmsMaxDischargeKw?.let { stringResource(R.string.tech_value_ah, it) } ?: DASH,
        )
        Hint(state.openHint, "bmsLimits", R.string.tech_hint_bms_limits)

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
            SectionHeader(stringResource(R.string.tech_card_cells))
            HelpIcon { onHint("cells") }
        }
        TechRow(
            "min",
            state.cellMin?.let { stringResource(R.string.battery_health_cell_voltage_value, it) } ?: DASH,
            valueColor = cellMinVoltageColor(state.cellMin),
        )
        TechRow(
            "max",
            state.cellMax?.let { stringResource(R.string.battery_health_cell_voltage_value, it) } ?: DASH,
        )
        TechRow(
            stringResource(R.string.battery_health_cell_delta_label),
            state.cellDelta
                ?.let { stringResource(R.string.tech_value_mv, Math.round(it * 1000.0).toInt()) } ?: DASH,
            valueColor = cellDeltaColor(state.cellDelta, state.soc),
        )
        if (state.cellMin != null && state.cellMax != null) {
            CellRangeStrip(
                state.cellMin,
                state.cellMax,
                cellDeltaColor(state.cellDelta, state.soc),
            )
        }
        Hint(state.openHint, "cells", R.string.tech_hint_cells)
    }
}

/**
 * Rows of the motors card, in the order they are drawn. Kept as data so the order is pinned by
 * a unit test — the module has no Compose UI test harness. The front/rear split sits last:
 * VadimV reported it reads as a repeat of the per-motor numbers when it is at the top.
 */
internal enum class MotorRow { HEADER, MOTOR_TEMP, INVERTER_TEMP, RPM, PEDALS, POWER_SPLIT }

internal val MOTOR_CARD_ROWS: List<MotorRow> = listOf(
    MotorRow.HEADER,
    MotorRow.MOTOR_TEMP,
    MotorRow.INVERTER_TEMP,
    MotorRow.RPM,
    MotorRow.PEDALS,
    MotorRow.POWER_SPLIT,
)

@Composable
internal fun MotorsCard(state: TechPanelUiState, onHint: (String) -> Unit, modifier: Modifier = Modifier) {
    TechCard(
        stringResource(R.string.tech_card_motors),
        hintKey = "motors",
        onHint = onHint,
        modifier = modifier,
    ) {
        MOTOR_CARD_ROWS.forEach {
            MotorCardRow(it, state)
            // DM-i: the engine is not a motor pair, so its rpm is one value under the motors' rpm.
            if (it == MotorRow.RPM && state.isHybrid) EngineRpmRow(state.engineRpm, R.string.tech_label_engine_rpm)
        }
        Hint(state.openHint, "motors", R.string.tech_hint_motors)
    }
}

@Composable
private fun MotorCardRow(row: MotorRow, state: TechPanelUiState) {
    val anyTemp = state.motorTempFront ?: state.motorTempRear
        ?: state.inverterTempFront ?: state.inverterTempRear
    when (row) {
        MotorRow.HEADER -> PairRow(
            "",
            stringResource(R.string.tech_label_front),
            stringResource(R.string.tech_label_rear),
            valueColor = TextMuted,
        )
        MotorRow.MOTOR_TEMP -> {
            PairRow(
                stringResource(R.string.tech_label_motor_temp),
                state.motorTempFront?.let { stringResource(R.string.tech_value_deg, it) } ?: DASH,
                state.motorTempRear?.let { stringResource(R.string.tech_value_deg, it) } ?: DASH,
            )
            if (anyTemp != null) TempBarPair(state.motorTempFront, state.motorTempRear)
        }
        MotorRow.INVERTER_TEMP -> {
            PairRow(
                stringResource(R.string.tech_label_inverter_temp),
                inverterTempForDisplay(state.inverterTempFront)?.let { stringResource(R.string.tech_value_deg, it) } ?: DASH,
                inverterTempForDisplay(state.inverterTempRear)?.let { stringResource(R.string.tech_value_deg, it) } ?: DASH,
            )
            if (anyTemp != null) {
                TempBarPair(inverterTempForDisplay(state.inverterTempFront), inverterTempForDisplay(state.inverterTempRear))
                TempScaleCaption()
            }
        }
        MotorRow.RPM -> PairRow(
            stringResource(R.string.tech_label_rpm),
            rpmForDisplay(state.motorRpmFront)?.toString() ?: DASH,
            rpmForDisplay(state.motorRpmRear)?.toString() ?: DASH,
        )
        MotorRow.PEDALS -> PairRow(
            stringResource(R.string.tech_label_pedals),
            state.pedalAccel?.let { stringResource(R.string.tech_value_percent, it) } ?: DASH,
            state.pedalBrake?.let { stringResource(R.string.tech_value_percent, it) } ?: DASH,
        )
        MotorRow.POWER_SPLIT ->
            when (val split = motorSplitPercent(state.motorCurrentFront, state.motorCurrentRear)) {
                null -> Unit  // single-motor car or no reading: no row at all
                MotorSplit.Idle -> PairRow(stringResource(R.string.tech_label_power), DASH, DASH)
                is MotorSplit.Share -> PairRow(
                    stringResource(R.string.tech_label_power),
                    stringResource(R.string.tech_value_percent, split.frontPercent),
                    stringResource(R.string.tech_value_percent, split.rearPercent),
                )
            }
    }
}

/** Engine rpm the way the Dashboard popup shows it: orange while it turns, «ДВС выключен» at 0. */
@Composable
private fun EngineRpmRow(rpm: Int?, labelRes: Int) {
    when {
        rpm == null -> TechRow(stringResource(labelRes), DASH)
        rpm > 0 -> TechRow(stringResource(labelRes), stringResource(R.string.dashboard_fuel_rpm_value, rpm),
            valueColor = AccentOrange)
        else -> TechRow(stringResource(labelRes), stringResource(R.string.dashboard_fuel_engine_off),
            valueColor = TextMuted)
    }
}

/** DM-i only: the rows of the Dashboard «Топливо» popup without the price, all from the car. */
@Composable
internal fun FuelCard(state: TechPanelUiState, modifier: Modifier = Modifier) {
    val km = stringResource(R.string.dashboard_unit_km)
    TechCard(stringResource(R.string.tech_card_fuel), modifier = modifier) {
        TechRow(stringResource(R.string.dashboard_fuel_popup_tank),
            state.fuelPercent?.let { stringResource(R.string.dashboard_fuel_percent_value, it) } ?: DASH)
        TechRow(stringResource(R.string.dashboard_fuel_popup_fuel_range), state.fuelRangeKm?.let { "$it $km" } ?: DASH)
        TechRow(stringResource(R.string.dashboard_fuel_popup_ev_range), state.evRangeKm?.let { "$it $km" } ?: DASH)
        EngineRpmRow(state.engineRpm, R.string.dashboard_fuel_popup_engine_rpm)
        TechRow(stringResource(R.string.dashboard_fuel_popup_mileage_hev), state.mileageHevKm?.let { "$it $km" } ?: DASH)
        TechRow(stringResource(R.string.dashboard_fuel_popup_mileage_ev), state.mileageEvKm?.let { "$it $km" } ?: DASH)
        TechRow(stringResource(R.string.dashboard_fuel_popup_fuel_total),
            state.fuelTotalL?.let { stringResource(R.string.dashboard_fuel_liters_value, it) } ?: DASH)
    }
}

/** «Состояние машины»: climate on top, then the tyres; each part only when the car reports it. */
@Composable
internal fun CarStateCard(state: TechPanelUiState, onHint: (String) -> Unit, modifier: Modifier = Modifier) {
    TechCard(stringResource(R.string.tech_card_car_state), modifier = modifier) {
        if (state.showClimate) ClimateRows(state, onHint)
        if (state.showTyres) TyresSection(state, onHint)
    }
}

@Composable
private fun ClimateRows(state: TechPanelUiState, onHint: (String) -> Unit) {
    TechRow(
        stringResource(R.string.tech_label_compressor),
        state.compressorW?.let { stringResource(R.string.tech_value_watt, it) } ?: DASH,
        hintKey = "compressor", onHint = onHint,
    )
    TechRow(
        stringResource(R.string.tech_label_climate),
        when (state.acStatus) {
            null -> DASH
            0 -> stringResource(R.string.tech_off)
            else -> stringResource(R.string.tech_on)
        },
    )
    PairRow(
        stringResource(R.string.tech_label_inside_outside),
        state.insideTemp?.let { stringResource(R.string.tech_value_deg, it) } ?: DASH,
        state.exteriorTemp?.let { stringResource(R.string.tech_value_deg, it) } ?: DASH,
    )
    // PM2.5 only where the car has the sensor: no valid level, no row.
    state.pm25InShown?.let { Pm25Row(R.string.tech_label_pm25_in, it, state.pm25LevelIn) }
    state.pm25OutShown?.let { Pm25Row(R.string.tech_label_pm25_out, it, state.pm25LevelOut) }
    Hint(state.openHint, "compressor", R.string.tech_hint_compressor)
}

/** Colour from the sensor's own grade (1 excellent .. 6 serious), not a threshold of ours. */
@Composable
private fun Pm25Row(labelRes: Int, value: Int, level: Int?) {
    TechRow(
        stringResource(labelRes),
        stringResource(R.string.tech_value_pm25, value),
        valueColor = when (level) {
            1, 2 -> AccentGreen
            3, 4 -> SocYellow
            else -> SocRed
        },
    )
}

@Composable
private fun TyresSection(state: TechPanelUiState, onHint: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
        SectionHeader(stringResource(R.string.tech_card_tyres))
        HelpIcon { onHint("tyres") }
    }
    TyreTopView(state)
    Hint(state.openHint, "tyres", R.string.tech_hint_tyres)
}

@Composable
internal fun HistoryCard(state: TechPanelUiState, onHint: (String) -> Unit, modifier: Modifier = Modifier) {
    TechCard(stringResource(R.string.tech_card_history), modifier = modifier) {
        CalibrationRows(state, onHint)
        TechRow(
            stringResource(R.string.battery_health_bms_mileage_label),
            state.lifetimeKm?.let { stringResource(R.string.battery_health_bms_mileage_value, it) } ?: DASH,
        )
        TechRow(
            stringResource(R.string.battery_health_pumped_label),
            state.lifetimeKwh?.let { stringResource(R.string.battery_health_pumped_value, it) } ?: DASH,
        )
        FullCyclesRow(state)
        TechRow(
            stringResource(R.string.battery_health_avg_since_charge_label),
            state.avgSocSinceCharge?.let { "$it%" } ?: DASH,
            valueColor = avgSocColor(state.avgSocSinceCharge),
        )
        TechRow(
            stringResource(R.string.battery_health_avg_all_time_label),
            state.avgSocAllTime?.let { "$it%" } ?: DASH,
            valueColor = avgSocColor(state.avgSocAllTime),
            hintKey = "avgSoc", onHint = onHint,
        )
        Hint(state.openHint, "avgSoc", R.string.battery_health_avg_soc_hint)
    }
}

/**
 * Last full charge, time since it and last calibration (#359), off the app's own charge log
 * and to the day, then a thin line above the lifetime rows.
 */
@Composable
private fun CalibrationRows(state: TechPanelUiState, onHint: (String) -> Unit) {
    val now = System.currentTimeMillis()
    val locale = LocalConfiguration.current.locales[0]
    val full = state.lastFullChargeTs
    val cal = state.lastCalibrationTs
    TechRow(
        stringResource(R.string.tech_label_full_charge),
        full?.let { markDate(it, now, locale) } ?: DASH,
    )
    TechRow(
        stringResource(R.string.tech_label_since_full_charge),
        full?.let { elapsedDaysHours(it, now) } ?: DASH,
        valueColor = calibrationLevelColor(BatteryCalibration.sinceFullChargeLevel(full, now)),
        hintKey = "fullCharge", onHint = onHint,
    )
    Hint(state.openHint, "fullCharge", R.string.tech_hint_full_charge)
    TechRow(
        stringResource(R.string.tech_label_calibration),
        cal?.let { markDate(it, now, locale) } ?: stringResource(R.string.tech_value_no_data),
        valueColor = calibrationLevelColor(BatteryCalibration.calibrationLevel(cal, now)),
        hintKey = "calibration", onHint = onHint,
    )
    Hint(state.openHint, "calibration", R.string.tech_hint_calibration)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .height(1.dp)
            .background(CardBorder)
    )
}

/** «22 окт · 4 дн назад»: short date in the app locale and calendar days back, 0 reads «сегодня». */
@Composable
private fun markDate(ts: Long, now: Long, locale: Locale): String {
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMM")
    // Short Russian months carry an abbreviation dot («окт.»); the agreed mock drops it.
    val date = SimpleDateFormat(pattern, locale).format(Date(ts)).removeSuffix(".")
    val zone = ZoneId.systemDefault()
    val days = ChronoUnit.DAYS.between(
        Instant.ofEpochMilli(ts).atZone(zone).toLocalDate(),
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate(),
    ).coerceAtLeast(0)
    return if (days == 0L) stringResource(R.string.tech_value_date_today, date)
    else stringResource(R.string.tech_value_date_days_ago, date, days.toInt())
}

/** «4 дн 6 ч», under a day just «6 ч». */
@Composable
private fun elapsedDaysHours(ts: Long, now: Long): String {
    val hoursTotal = (now - ts).coerceAtLeast(0) / 3_600_000L
    val days = (hoursTotal / 24).toInt()
    val hours = (hoursTotal % 24).toInt()
    return if (days > 0) stringResource(R.string.tech_value_days_hours, days, hours)
    else stringResource(R.string.tech_value_hours, hours)
}
