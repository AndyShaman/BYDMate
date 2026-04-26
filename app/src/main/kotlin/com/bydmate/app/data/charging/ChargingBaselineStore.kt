package com.bydmate.app.data.charging

import com.bydmate.app.data.repository.ChargeRepository
import com.bydmate.app.data.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for the autoservice charging baseline (lifetime_kwh
 * at the end of the most recent autoservice-tracked session).
 *
 * Lookup order:
 *   1. DB: MAX(lifetime_kwh_at_finish) WHERE detection_source LIKE 'autoservice%'
 *   2. k/v fallback: KEY_AUTOSERVICE_BASELINE_KWH (used during cold start, before
 *      the very first autoservice session is recorded).
 *   3. null → caller treats as "first run", records current lifetime_kwh as the
 *      seed baseline without creating a phantom session.
 */
@Singleton
class ChargingBaselineStore @Inject constructor(
    private val chargeRepo: ChargeRepository,
    private val settings: SettingsRepository
) {
    suspend fun getBaseline(): Double? {
        val fromDb = chargeRepo.getMaxLifetimeKwhAtFinish()
        if (fromDb != null) return fromDb
        return settings.getAutoserviceBaseline()?.first
    }

    suspend fun setBaseline(kwh: Double, ts: Long) {
        settings.setAutoserviceBaseline(kwh, ts)
    }
}
