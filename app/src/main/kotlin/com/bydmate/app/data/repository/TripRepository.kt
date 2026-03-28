package com.bydmate.app.data.repository

import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.dao.TripPointDao
import com.bydmate.app.data.local.dao.TripSummary
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.local.entity.TripPointEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TripRepository @Inject constructor(
    private val tripDao: TripDao,
    private val tripPointDao: TripPointDao
) {
    suspend fun insertTrip(trip: TripEntity): Long = tripDao.insert(trip)

    suspend fun updateTrip(trip: TripEntity) = tripDao.update(trip)

    suspend fun getTripById(id: Long): TripEntity? = tripDao.getById(id)

    fun getAllTrips(): Flow<List<TripEntity>> = tripDao.getAll()

    fun getTripsByDateRange(from: Long, to: Long): Flow<List<TripEntity>> =
        tripDao.getByDateRange(from, to)

    suspend fun getTodaySummary(dayStart: Long, dayEnd: Long): TripSummary =
        tripDao.getTodaySummary(dayStart, dayEnd)

    fun getLastTrip(): Flow<TripEntity?> = tripDao.getLastTrip()

    suspend fun insertTripPoints(points: List<TripPointEntity>) =
        tripPointDao.insertAll(points)

    suspend fun getTripPoints(tripId: Long): List<TripPointEntity> =
        tripPointDao.getByTripId(tripId)
}
