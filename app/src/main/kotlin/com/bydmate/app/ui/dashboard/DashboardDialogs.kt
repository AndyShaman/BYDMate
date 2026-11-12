package com.bydmate.app.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bydmate.app.R
import com.bydmate.app.data.trips.TripAutoResetMode
import com.bydmate.app.data.trips.TripCounterUi
import com.bydmate.app.ui.components.CardDetailDialog
import com.bydmate.app.ui.theme.*

/** Popup of the «Топливо» card: the car's own readings only, nothing computed by the app. */
@Composable
internal fun FuelDetailDialog(state: DashboardUiState, onEditPrice: () -> Unit, onDismiss: () -> Unit) {
    val km = stringResource(R.string.dashboard_unit_km)
    CardDetailDialog(title = null, borderColor = AccentOrange, onDismiss = onDismiss) {
        TripDetailRow(stringResource(R.string.dashboard_fuel_popup_tank),
            state.fuelPercent?.let { stringResource(R.string.dashboard_fuel_percent_value, it) } ?: "—")
        TripDetailRow(stringResource(R.string.dashboard_fuel_popup_fuel_range), state.fuelRangeKm?.let { "$it $km" } ?: "—")
        TripDetailRow(stringResource(R.string.dashboard_fuel_popup_ev_range), state.evRangeKm?.let { "$it $km" } ?: "—")
        val rpm = state.engineRpm
        when {
            rpm == null -> TripDetailRow(stringResource(R.string.dashboard_fuel_popup_engine_rpm), "—")
            rpm > 0 -> TripDetailRow(stringResource(R.string.dashboard_fuel_popup_engine_rpm),
                stringResource(R.string.dashboard_fuel_rpm_value, rpm), valueColor = AccentOrange)
            else -> TripDetailRow(stringResource(R.string.dashboard_fuel_popup_engine_rpm),
                stringResource(R.string.dashboard_fuel_engine_off), valueColor = TextMuted)
        }
        TripDetailRow(stringResource(R.string.dashboard_fuel_popup_mileage_hev), state.mileageHevKm?.let { "$it $km" } ?: "—")
        TripDetailRow(stringResource(R.string.dashboard_fuel_popup_mileage_ev), state.mileageEvKm?.let { "$it $km" } ?: "—")
        TripDetailRow(stringResource(R.string.dashboard_fuel_popup_fuel_total),
            state.fuelTotalL?.let { stringResource(R.string.dashboard_fuel_liters_value, it) } ?: "—")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.dashboard_fuel_popup_price), color = TextSecondary, fontSize = 13.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(state.fuelPricePerLiter?.let { "%.2f %s".format(it, state.currencySymbol) } ?: "—",
                    color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.dashboard_fuel_price_edit), color = AccentGreen, fontSize = 13.sp,
                    modifier = Modifier.clickable { onEditPrice() })
            }
        }
        Text(stringResource(R.string.dashboard_fuel_footer), color = TextMuted, fontSize = 11.sp)
    }
}

// ============================================================================
// TRIP counter detail popup helpers
// ============================================================================

@Composable
private fun TripDetailRow(
    label: String,
    value: String,
    valueColor: Color = TextPrimary,
    labelColor: Color = TextSecondary,
    indent: Boolean = false,
    small: Boolean = false,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = labelColor, fontSize = if (small) 12.sp else 13.sp,
            modifier = if (indent) Modifier.padding(start = 14.dp) else Modifier)
        Text(value, color = valueColor, fontSize = if (small) 12.sp else 13.sp,
            fontWeight = if (small) FontWeight.Normal else FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun TripCounterDialog(
    label: String,
    ui: TripCounterUi,
    currencySymbol: String,
    mode: TripAutoResetMode,
    onModeChange: (TripAutoResetMode) -> Unit,
    onDismiss: () -> Unit,
) {
    CardDetailDialog(title = null, borderColor = AccentGreen, onDismiss = onDismiss) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom) {
            Text(label, color = AccentGreen, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(
                if (ui.resetTs == 0L) stringResource(R.string.dashboard_trip_since_all_time)
                else stringResource(R.string.dashboard_trip_since,
                    java.text.SimpleDateFormat("d MMMM", java.util.Locale.getDefault())
                        .format(java.util.Date(ui.resetTs)),
                    ((System.currentTimeMillis() - ui.resetTs) / 86_400_000L)),
                color = TextMuted, fontSize = 11.sp)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text("%.1f".format(ui.km), color = TextPrimary, fontSize = 30.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.dashboard_unit_km), color = TextSecondary, fontSize = 14.sp)
        }
        val totalMin = ui.drivingMs / 60_000L
        val timeStr = if (totalMin >= 60)
            stringResource(R.string.dashboard_trip_time_hours_min, totalMin / 60, totalMin % 60)
        else stringResource(R.string.dashboard_trip_time_min, totalMin)
        TripDetailRow(stringResource(R.string.dashboard_trip_count_label), ui.tripCount.toString())
        TripDetailRow(stringResource(R.string.dashboard_trip_driving_time_label), timeStr)
        HorizontalDivider(color = CardBorder)
        TripDetailRow(stringResource(R.string.dashboard_trip_kwh_total_label),
            stringResource(R.string.dashboard_trip_kwh_value, "%.1f".format(ui.kwh)), valueColor = AccentGreen)
        TripDetailRow(stringResource(R.string.dashboard_trip_kwh_driving_label),
            stringResource(R.string.dashboard_trip_kwh_value, "%.1f".format(ui.drivingKwh)),
            labelColor = TextMuted, indent = true, small = true)
        TripDetailRow(stringResource(R.string.dashboard_trip_kwh_idle_label),
            stringResource(R.string.dashboard_trip_kwh_value, "%.1f".format(ui.idleKwh)),
            labelColor = TextMuted, indent = true, small = true)
        if (ui.km > 0.0) TripDetailRow(stringResource(R.string.dashboard_trip_avg_label),
            stringResource(R.string.dashboard_trip_avg_value, "%.1f".format(ui.kwh / ui.km * 100.0)))
        HorizontalDivider(color = CardBorder)
        TripDetailRow(stringResource(R.string.dashboard_trip_cost_label),
            "%.2f %s".format(ui.cost, currencySymbol))
        HorizontalDivider(color = CardBorder)
        Text(stringResource(R.string.dashboard_trip_auto_reset_label), color = TextSecondary, fontSize = 12.sp)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TripAutoResetMode.entries.forEach { m ->
                val chipLabel = when (m) {
                    TripAutoResetMode.OFF -> R.string.dashboard_trip_auto_reset_off
                    TripAutoResetMode.ANY -> R.string.dashboard_trip_auto_reset_any
                    TripAutoResetMode.AC -> R.string.dashboard_trip_auto_reset_ac
                    TripAutoResetMode.DC -> R.string.dashboard_trip_auto_reset_dc
                    TripAutoResetMode.FULL -> R.string.dashboard_trip_auto_reset_full
                }
                TripAutoResetChip(stringResource(chipLabel), selected = m == mode) { onModeChange(m) }
            }
        }
        Text(stringResource(R.string.dashboard_trip_reset_hint), color = TextMuted, fontSize = 11.sp,
            modifier = Modifier.padding(top = 6.dp))
    }
}

// DashboardPeriodChip on an elevated container: the TRIP popup itself is CardSurface.
@Composable
private fun TripAutoResetChip(label: String, selected: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 12.sp) },
        shape = RoundedCornerShape(8.dp),
        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
            selectedContainerColor = AccentGreen,
            selectedLabelColor = Color.White,
            containerColor = CardSurfaceElevated,
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
