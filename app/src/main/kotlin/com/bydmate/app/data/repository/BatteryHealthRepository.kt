package com.bydmate.app.data.repository

import com.bydmate.app.data.charging.AutoserviceChargingDetector
import com.bydmate.app.data.local.dao.BatterySnapshotDao
import com.bydmate.app.data.local.entity.BatterySnapshotEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BatteryHealthRepository @Inject constructor(
    private val batterySnapshotDao: BatterySnapshotDao
) {
    suspend fun insert(snapshot: BatterySnapshotEntity): Long =
        batterySnapshotDao.insert(snapshot)

    suspend fun getLast(): BatterySnapshotEntity? = batterySnapshotDao.getLast()

    /**
     * Calculate usable capacity from a charge session.
     * Formula: kwhCharged / (socEnd - socStart) * 100
     * Only valid when SOC delta >= [AutoserviceChargingDetector.MIN_SOC_DELTA_FOR_SNAPSHOT] (5%) for accuracy.
     */
    fun calculateCapacity(kwhCharged: Double, socStart: Int, socEnd: Int): Double? {
        val socDelta = socEnd - socStart
        // <5% too noisy for accurate capacity estimate
        if (socDelta < AutoserviceChargingDetector.MIN_SOC_DELTA_FOR_SNAPSHOT) return null
        return kwhCharged / socDelta * 100.0
    }
}
