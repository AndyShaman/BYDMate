package com.bydmate.app.data.repository

import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.local.dao.ChargePointDao
import com.bydmate.app.data.local.dao.ChargeSummary
import com.bydmate.app.data.local.entity.ChargeEntity
import com.bydmate.app.data.local.entity.ChargePointEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

data class LifetimeChargingStats(
    val totalKwhAdded: Double,
    val acKwh: Double,
    val dcKwh: Double,
    val sessionCount: Int
)

/**
 * Pack capacity one full cycle is worth, corrected for SoH (#224). The BMS lifetime counter
 * spans the whole life of the pack, so it is divided by the average capacity from new to now:
 * nominal × (100 + SoH) / 200. The app's own charging sum is recent, so it is divided by the
 * capacity today: nominal × SoH / 100. No usable SoH (null, ≤ 0, > 100) keeps the nominal.
 */
fun fullCycleCapacityKwh(nominalCapacityKwh: Double, sohPercent: Float?, lifetimeSource: Boolean): Double {
    val soh = sohPercent?.toDouble()?.takeIf { it > 0.0 && it <= 100.0 } ?: return nominalCapacityKwh
    return if (lifetimeSource) nominalCapacityKwh * (100.0 + soh) / 200.0 else nominalCapacityKwh * soh / 100.0
}

/**
 * Full-cycle equivalent of everything ever pumped into the pack: total kWh divided by the
 * SoH-corrected capacity ([fullCycleCapacityKwh]). Shared by the «Зарядки» stats and the
 * «Техника» battery card so both screens show the same number from the same source.
 */
fun equivalentFullCycles(
    totalKwhAdded: Double,
    nominalCapacityKwh: Double,
    sohPercent: Float?,
    lifetimeSource: Boolean,
): Double {
    val capacity = fullCycleCapacityKwh(nominalCapacityKwh, sohPercent, lifetimeSource)
    return if (capacity > 0) totalKwhAdded / capacity else 0.0
}

/**
 * kWh the full-cycle count is divided from: the BMS lifetime counter when the car reports one,
 * the app's own sum of logged sessions otherwise. The BMS counts the whole life of the car, our
 * sum only what the app saw since it was installed, so the two are nowhere near each other on a
 * car that answers (field 2026-09-18: 10 963 kWh from the BMS against 1 557 kWh logged — 126
 * cycles against 18). Null when neither source has anything to divide.
 */
fun fullCycleKwh(bmsLifetimeKwh: Double?, chargedKwh: Double?): Double? =
    bmsLifetimeKwh?.takeIf { it > 0.0 } ?: chargedKwh?.takeIf { it > 0.0 }

@Singleton
class ChargeRepository @Inject constructor(
    private val chargeDao: ChargeDao,
    private val chargePointDao: ChargePointDao
) {
    suspend fun insertCharge(charge: ChargeEntity): Long = chargeDao.insert(charge)

    suspend fun updateCharge(charge: ChargeEntity) = chargeDao.update(charge)

    suspend fun getChargeById(id: Long): ChargeEntity? = chargeDao.getById(id)

    fun getAllCharges(): Flow<List<ChargeEntity>> = chargeDao.getAll()

    fun getChargesByDateRange(from: Long, to: Long): Flow<List<ChargeEntity>> =
        chargeDao.getByDateRange(from, to)

    suspend fun getPeriodSummary(from: Long, to: Long): ChargeSummary =
        chargeDao.getPeriodSummary(from, to)

    fun getLastCharge(): Flow<ChargeEntity?> = chargeDao.getLastCharge()

    suspend fun insertChargePoints(points: List<ChargePointEntity>) =
        chargePointDao.insertAll(points)

    suspend fun getChargePoints(chargeId: Long): List<ChargePointEntity> =
        chargePointDao.getByChargeId(chargeId)

    suspend fun getLastSuspendedCharge(): ChargeEntity? = chargeDao.getLastSuspendedCharge()

    suspend fun getStaleSessions(cutoffTs: Long): List<ChargeEntity> =
        chargeDao.getStaleSessions(cutoffTs)

    suspend fun getRecentChargesWithBatteryData(): List<ChargeEntity> =
        chargeDao.getRecentChargesWithBatteryData()

    suspend fun getMaxLifetimeKwhAtFinish(): Double? =
        chargeDao.getMaxLifetimeKwhAtFinish()

    suspend fun getLifetimeStats(): LifetimeChargingStats {
        val all = chargeDao.getAllAutoserviceCharges()
        val ac = all.filter { it.type == "AC" }.sumOf { it.kwhCharged ?: 0.0 }
        val dc = all.filter { it.type == "DC" }.sumOf { it.kwhCharged ?: 0.0 }
        return LifetimeChargingStats(
            totalKwhAdded = ac + dc,
            acKwh = ac,
            dcKwh = dc,
            sessionCount = all.size
        )
    }

    suspend fun hasLegacyCharges(): Boolean = chargeDao.hasLegacyCharges()

    suspend fun deleteEmpty(): Int = chargeDao.deleteEmpty()

    suspend fun deleteCharge(charge: ChargeEntity) = chargeDao.delete(charge)
}
