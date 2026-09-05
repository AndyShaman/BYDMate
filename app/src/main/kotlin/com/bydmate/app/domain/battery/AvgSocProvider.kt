package com.bydmate.app.domain.battery

import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.repository.TripRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Time-weighted average SOC over two windows, computed on demand (#93). */
data class AvgSoc(val sinceCharge: Int?, val allTime: Int?)

/**
 * Cheap on-open aggregation over a few hundred Room rows — no polling. Extracted from
 * DashboardViewModel when the battery-health dialog became the «Техника» screen, so both
 * screens read the same numbers.
 */
@Singleton
class AvgSocProvider @Inject constructor(
    private val tripRepository: TripRepository,
    private val chargeDao: ChargeDao,
) {
    suspend fun compute(nowMs: Long = System.currentTimeMillis()): AvgSoc {
        val trips = tripRepository.getAllTrips().first()
        val charges = chargeDao.getAll().first()
        val points = AvgSocCalculator.buildPoints(trips, charges)
        val allTime = points.firstOrNull()
            ?.let { AvgSocCalculator.averageSince(points, it.ts, nowMs) }
        val lastChargeEnd = charges
            .filter { it.status == "COMPLETED" && it.endTs != null && it.socEnd != null }
            .maxByOrNull { it.endTs!! }
            ?.endTs
        val sinceCharge = lastChargeEnd
            ?.let { AvgSocCalculator.averageSince(points, it, nowMs) }
        return AvgSoc(sinceCharge = sinceCharge, allTime = allTime)
    }
}
