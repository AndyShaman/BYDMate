package com.bydmate.app.data.charging

import com.bydmate.app.data.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the last-known autoservice state snapshot so that runCatchUp can
 * detect SOC / capacity changes across DiLink power-cycles.
 *
 * Replaces ChargingBaselineStore (v2.4.16 and earlier used lifetime_kwh as the
 * baseline signal; v2.4.17 cascade uses SOC + per-session chargingCapacityKwh).
 */
@Singleton
class ChargingStateStore @Inject constructor(
    private val settings: SettingsRepository
) {
    data class State(
        val socPercent: Int?,
        val mileageKm: Float?,
        val capacityKwh: Float?,
        val ts: Long
    )

    suspend fun load(): State = State(
        socPercent = settings.getChargingBaselineSoc(),
        mileageKm = settings.getLastMileageKm(),
        capacityKwh = settings.getLastCapacityKwh(),
        ts = settings.getLastStateTs()
    )

    suspend fun save(socPercent: Int?, mileageKm: Float?, capacityKwh: Float?, ts: Long) {
        socPercent?.let { settings.setChargingBaselineSoc(it) }
        settings.setLastMileageKm(mileageKm)
        settings.setLastCapacityKwh(capacityKwh)
        settings.setLastStateTs(ts)
    }
}
