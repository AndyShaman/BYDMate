package com.bydmate.app.domain.battery

import com.bydmate.app.data.autoservice.BatteryReading
import com.bydmate.app.data.vehicle.VehicleApi
import com.bydmate.app.data.local.entity.BatterySnapshotEntity
import com.bydmate.app.data.repository.BatteryHealthRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryStateRepositoryTest {

    private class FakeVehicleApi(
        private val battery: BatteryReading?,
        private val available: Boolean = true
    ) : VehicleApi {
        override suspend fun isAvailable(): Boolean = available
        override suspend fun readBatterySnapshot(): BatteryReading? = battery
        override suspend fun readSnapshot(): com.bydmate.app.data.remote.DiParsData? = null
        override suspend fun readSoc(): Float? = null
        override suspend fun readSpeed(): Float? = null
        override suspend fun readMileageKm(): Float? = null
        override suspend fun readPowerKw(): Int? = null
        override suspend fun readAcStatus(): Int? = null
        override suspend fun readAcTemp(): Int? = null
        override suspend fun readInsideTemp(): Int? = null
        override suspend fun readExteriorTemp(): Int? = null
        override suspend fun readFanLevel(): Int? = null
        override suspend fun readWindowDriver(): Int? = null
        override suspend fun readWindowPassenger(): Int? = null
        override suspend fun readWindowRearLeft(): Int? = null
        override suspend fun readWindowRearRight(): Int? = null
        override suspend fun dispatch(commandString: String): Result<Unit> = Result.success(Unit)
        override suspend fun writeAcOn(): Result<Unit> = Result.success(Unit)
        override suspend fun writeAcOff(): Result<Unit> = Result.success(Unit)
        override suspend fun writeSetDriverTemp(celsius: Int): Result<Unit> = Result.success(Unit)
        override suspend fun writeWindowDriver(percent: Int): Result<Unit> = Result.success(Unit)
        override suspend fun writeWindowPassenger(percent: Int): Result<Unit> = Result.success(Unit)
        override suspend fun writeWindowRearLeft(percent: Int): Result<Unit> = Result.success(Unit)
        override suspend fun writeWindowRearRight(percent: Int): Result<Unit> = Result.success(Unit)
        override suspend fun writeLockDoors(): Result<Unit> = Result.success(Unit)
        override suspend fun writeUnlockDoors(): Result<Unit> = Result.success(Unit)
        override suspend fun writeSunroof(mode: com.bydmate.app.data.vehicle.SunroofMode): Result<Unit> = Result.success(Unit)
        override suspend fun writeSunshade(open: Boolean): Result<Unit> = Result.success(Unit)
    }

    // BatteryHealthRepository is not open, so we stub via the DAO
    private class StubBatterySnapshotDao(
        private val lastValue: BatterySnapshotEntity?
    ) : com.bydmate.app.data.local.dao.BatterySnapshotDao {
        override fun getAll(): Flow<List<BatterySnapshotEntity>> = flowOf(emptyList())
        override fun getRecent(limit: Int): Flow<List<BatterySnapshotEntity>> = flowOf(emptyList())
        override suspend fun insert(snapshot: BatterySnapshotEntity): Long = 0
        override suspend fun getLast(): BatterySnapshotEntity? = lastValue
        override suspend fun getCount(): Int = 0
    }

    private fun fakeBatteryHealth(last: BatterySnapshotEntity?): BatteryHealthRepository =
        BatteryHealthRepository(StubBatterySnapshotDao(last))

    @Test
    fun `state populated when autoservice available and snapshot present`() = runTest {
        val repo = BatteryStateRepository(
            FakeVehicleApi(BatteryReading(100f, 91f, 602.7f, 2091f, 14.0f, 0L)),
            fakeBatteryHealth(null),
        )

        val state = repo.refresh()

        assertEquals(100.0f, state.sohPercent!!, 0.01f)
        assertEquals(91.0f, state.socNow!!, 0.01f)
        assertEquals(602.7f, state.lifetimeKwh!!, 0.01f)
        assertEquals(2091.0f, state.lifetimeKm!!, 0.01f)
        assertEquals(14.0f, state.voltage12v!!, 0.01f)
        assertTrue(state.autoserviceAvailable)
    }

    @Test
    fun `autoserviceAvailable is false when client unreachable`() = runTest {
        val repo = BatteryStateRepository(
            FakeVehicleApi(battery = null, available = false),
            fakeBatteryHealth(null),
        )

        val state = repo.refresh()

        assertFalse(state.autoserviceAvailable)
        assertNull(state.sohPercent)
    }

    @Test
    fun `autoserviceAvailable is false when snapshot is null`() = runTest {
        val repo = BatteryStateRepository(
            FakeVehicleApi(battery = null, available = true),
            fakeBatteryHealth(null),
        )

        val state = repo.refresh()

        assertFalse(state.autoserviceAvailable)
        assertNull(state.sohPercent)
    }

    @Test
    fun `falls back to last BatterySnapshot SoH when autoservice sohPercent is null`() = runTest {
        val snap = BatterySnapshotEntity(
            timestamp = 0L, socStart = 30, socEnd = 80,
            kwhCharged = 36.0, calculatedCapacityKwh = 72.0, sohPercent = 98.7
        )
        val repo = BatteryStateRepository(
            FakeVehicleApi(BatteryReading(null, 91f, 602.7f, 2091f, 14f, 0L)),  // sohPercent sentinel
            fakeBatteryHealth(snap),
        )

        val state = repo.refresh()

        assertEquals(98.7f, state.sohPercent!!, 0.01f)  // from snapshot
    }
}
