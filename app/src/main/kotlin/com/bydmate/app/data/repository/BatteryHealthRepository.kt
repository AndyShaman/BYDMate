package com.bydmate.app.data.repository

import com.bydmate.app.data.local.dao.BatterySnapshotDao
import com.bydmate.app.data.local.entity.BatterySnapshotEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BatteryHealthRepository @Inject constructor(
    private val batterySnapshotDao: BatterySnapshotDao
) {
    fun getAll(): Flow<List<BatterySnapshotEntity>> = batterySnapshotDao.getAll()

    fun getRecent(limit: Int = 50): Flow<List<BatterySnapshotEntity>> =
        batterySnapshotDao.getRecent(limit)

    suspend fun insert(snapshot: BatterySnapshotEntity): Long =
        batterySnapshotDao.insert(snapshot)

    suspend fun getLast(): BatterySnapshotEntity? = batterySnapshotDao.getLast()

    suspend fun getCount(): Int = batterySnapshotDao.getCount()

    /**
     * Calculate usable capacity from a charge session.
     * Formula: kwhCharged / (socEnd - socStart) * 100
     * Only valid when SOC delta >= 10% for accuracy.
     */
    fun calculateCapacity(kwhCharged: Double, socStart: Int, socEnd: Int): Double? {
        val socDelta = socEnd - socStart
        if (socDelta < 5) return null // <5% too noisy for accurate capacity estimate
        return kwhCharged / socDelta * 100.0
    }

    /**
     * Calculate SOH based on calculated capacity vs nominal.
     * Leopard 3 nominal: 72.9 kWh
     */
    fun calculateSoh(calculatedCapacityKwh: Double, nominalCapacityKwh: Double = 72.9): Double {
        return (calculatedCapacityKwh / nominalCapacityKwh * 100.0).coerceIn(0.0, 110.0)
    }
}
