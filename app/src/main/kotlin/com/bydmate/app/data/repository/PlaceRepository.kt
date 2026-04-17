package com.bydmate.app.data.repository

import com.bydmate.app.data.local.dao.PlaceDao
import com.bydmate.app.data.local.entity.PlaceEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaceRepository @Inject constructor(
    private val placeDao: PlaceDao
) {
    fun getAll(): Flow<List<PlaceEntity>> = placeDao.getAll()
    suspend fun getAllSnapshot(): List<PlaceEntity> = placeDao.getAllSnapshot()
    suspend fun getById(id: Long): PlaceEntity? = placeDao.getById(id)
    suspend fun insert(place: PlaceEntity): Long = placeDao.insert(place)
    suspend fun update(place: PlaceEntity) = placeDao.update(place)
    suspend fun delete(place: PlaceEntity) = placeDao.delete(place)
    suspend fun getCount(): Int = placeDao.getCount()
}
