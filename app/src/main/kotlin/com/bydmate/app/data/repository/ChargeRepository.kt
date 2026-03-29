package com.bydmate.app.data.repository

import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.local.dao.ChargePointDao
import com.bydmate.app.data.local.dao.ChargeSummary
import com.bydmate.app.data.local.entity.ChargeEntity
import com.bydmate.app.data.local.entity.ChargePointEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

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
}
