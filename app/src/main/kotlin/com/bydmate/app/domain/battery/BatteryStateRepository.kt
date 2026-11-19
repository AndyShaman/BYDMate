package com.bydmate.app.domain.battery

import com.bydmate.app.data.vehicle.VehicleApi
import com.bydmate.app.data.repository.BatteryHealthRepository
import com.bydmate.app.data.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Aggregated battery view: SoH and lifetime counters for Dashboard, the Tech panel,
 * Charges and the agent's vehicle state. Only `refresh()` (call-on-demand).
 */
data class BatteryState(
    val socNow: Float?,
    val sohPercent: Float?,
    val lifetimeKm: Float?,
    val lifetimeKwh: Float?,
    /** True when the autoservice client returned at least one real value. */
    val autoserviceAvailable: Boolean,
    /** Last positive BMS lifetime counter seen (this read or remembered from an earlier one). */
    val lifetimeKwhLastKnown: Float? = null,
) {
    /** The counter the full-cycle count uses: the live one, else the remembered one. */
    val lifetimeKwhForCycles: Float?
        get() = lifetimeKwh?.takeIf { it >= MIN_COUNTER_KWH } ?: lifetimeKwhLastKnown?.takeIf { it >= MIN_COUNTER_KWH }

    companion object {
        /** Below 1 kWh is not a lifetime counter: a link-error word read as a float is ~9e-41. */
        const val MIN_COUNTER_KWH = 1f
    }
}

@Singleton
class BatteryStateRepository @Inject constructor(
    private val vehicleApi: VehicleApi,
    private val batteryHealth: BatteryHealthRepository,
    private val settings: SettingsRepository,
) {
    suspend fun refresh(): BatteryState {
        if (!vehicleApi.isAvailable()) {
            return BatteryState(
                null, null, null, null, autoserviceAvailable = false,
                lifetimeKwhLastKnown = settings.getBmsLifetimeKwhLast(),
            )
        }
        val r = vehicleApi.readBatterySnapshot()
            ?: return BatteryState(
                null, null, null, null, autoserviceAvailable = false,
                lifetimeKwhLastKnown = settings.getBmsLifetimeKwhLast(),
            )

        val freshKwh = r.lifetimeKwh?.takeIf { it >= BatteryState.MIN_COUNTER_KWH }
        if (freshKwh != null) settings.setBmsLifetimeKwhLast(freshKwh)

        val sohFromSnapshot = batteryHealth.getLast()?.sohPercent?.toFloat()
        return BatteryState(
            socNow = r.socPercent,
            sohPercent = r.sohPercent ?: sohFromSnapshot,
            lifetimeKm = r.lifetimeMileageKm,
            lifetimeKwh = r.lifetimeKwh,
            autoserviceAvailable = true,
            lifetimeKwhLastKnown = freshKwh ?: settings.getBmsLifetimeKwhLast(),
        )
    }
}
