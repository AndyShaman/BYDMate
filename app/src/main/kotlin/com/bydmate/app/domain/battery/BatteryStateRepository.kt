package com.bydmate.app.domain.battery

import com.bydmate.app.data.vehicle.VehicleApi
import com.bydmate.app.data.repository.BatteryHealthRepository
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
    val autoserviceAvailable: Boolean
)

@Singleton
class BatteryStateRepository @Inject constructor(
    private val vehicleApi: VehicleApi,
    private val batteryHealth: BatteryHealthRepository,
) {
    suspend fun refresh(): BatteryState {
        if (!vehicleApi.isAvailable()) {
            return BatteryState(null, null, null, null, autoserviceAvailable = false)
        }
        val r = vehicleApi.readBatterySnapshot()
            ?: return BatteryState(null, null, null, null, autoserviceAvailable = false)

        val sohFromSnapshot = batteryHealth.getLast()?.sohPercent?.toFloat()
        return BatteryState(
            socNow = r.socPercent,
            sohPercent = r.sohPercent ?: sohFromSnapshot,
            lifetimeKm = r.lifetimeMileageKm,
            lifetimeKwh = r.lifetimeKwh,
            autoserviceAvailable = true
        )
    }
}
