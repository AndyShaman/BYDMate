package com.bydmate.app.domain.calculator

import com.bydmate.app.data.local.dao.OdometerSampleDao
import com.bydmate.app.data.local.entity.OdometerSampleEntity

class FakeOdometerSampleDao : OdometerSampleDao {
    private val rows = mutableListOf<OdometerSampleEntity>()
    private var nextId = 1L

    override suspend fun insert(sample: OdometerSampleEntity): Long {
        val withId = sample.copy(id = nextId++)
        rows += withId
        return withId.id
    }

    override suspend fun last(): OdometerSampleEntity? = rows.maxByOrNull { it.id }

    override suspend fun windowFrom(minMileage: Double): List<OdometerSampleEntity> =
        rows.filter { it.mileageKm >= minMileage }.sortedBy { it.mileageKm }

    override suspend fun trimBelow(cutoff: Double) {
        rows.removeAll { it.mileageKm < cutoff }
    }

    override suspend fun deleteOldest(howMany: Int) {
        rows.sortBy { it.id }
        repeat(howMany.coerceAtMost(rows.size)) { rows.removeAt(0) }
    }

    override suspend fun count(): Int = rows.size
    override suspend fun clear() { rows.clear() }

    fun snapshot(): List<OdometerSampleEntity> = rows.sortedBy { it.id }.toList()
}
