package com.bydmate.app.domain.battery

import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.repository.BatteryHealthRepository
import com.bydmate.app.data.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Aggregated battery view used by Dashboard (TopBar warning, BatteryCard,
 * BatteryHealthScreen header).
 *
 * Phase 1: only `refresh()` (call-on-demand). Phase 2 will compose this
 * with TrackingService.lastData and emit as a Flow into DashboardViewModel.
 */
data class BatteryState(
    val socNow: Float?,
    val voltage12v: Float?,
    val sohPercent: Float?,
    val lifetimeKm: Float?,
    val lifetimeKwh: Float?,
    /** True when toggle is ON AND the autoservice client returned at least one real value. */
    val autoserviceAvailable: Boolean
)

@Singleton
class BatteryStateRepository @Inject constructor(
    private val autoservice: AutoserviceClient,
    private val batteryHealth: BatteryHealthRepository,
    private val settings: SettingsRepository
) {
    suspend fun refresh(): BatteryState {
        if (!settings.isAutoserviceEnabled()) {
            return BatteryState(null, null, null, null, null, autoserviceAvailable = false)
        }
        if (!autoservice.isAvailable()) {
            return BatteryState(null, null, null, null, null, autoserviceAvailable = false)
        }
        val r = autoservice.readBatterySnapshot()
            ?: return BatteryState(null, null, null, null, null, autoserviceAvailable = false)

        val sohFromSnapshot = batteryHealth.getLast()?.sohPercent?.toFloat()
        return BatteryState(
            socNow = r.socPercent,
            voltage12v = r.voltage12v,
            sohPercent = r.sohPercent ?: sohFromSnapshot,
            lifetimeKm = r.lifetimeMileageKm,
            lifetimeKwh = r.lifetimeKwh,
            autoserviceAvailable = true
        )
    }
}
