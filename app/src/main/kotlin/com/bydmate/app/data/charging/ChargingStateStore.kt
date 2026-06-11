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
        // Single transactional write: a process kill mid-save must never leave
        // a mixed anchor (new mileage + stale SOC → false odometerMoved at the
        // next catch-up) — audit 2026-06-11.
        val values = buildMap {
            socPercent?.let { put(SettingsRepository.KEY_CHARGING_BASELINE_SOC, it.toString()) }
            put(SettingsRepository.KEY_LAST_MILEAGE_KM, mileageKm?.toString() ?: "")
            put(SettingsRepository.KEY_LAST_CAPACITY_KWH, capacityKwh?.toString() ?: "")
            put(SettingsRepository.KEY_LAST_STATE_TS, ts.toString())
        }
        settings.setStrings(values)
    }

    /** True when a charge session was observed in progress (gun connected) and
     *  has not yet been reconstructed or dismissed. */
    suspend fun loadChargePending(): Boolean = settings.getChargePending()

    suspend fun setChargePending(pending: Boolean) = settings.setChargePending(pending)
}
