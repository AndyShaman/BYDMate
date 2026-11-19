package com.bydmate.app.domain.battery

import com.bydmate.app.data.autoservice.BatteryReading
import com.bydmate.app.data.vehicle.VehicleApi
import com.bydmate.app.data.local.entity.BatterySnapshotEntity
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.local.dao.FakeSettingsDao
import com.bydmate.app.data.repository.BatteryHealthRepository
import com.bydmate.app.data.repository.SettingsRepository
import io.mockk.mockk
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
        override suspend fun dispatch(commandString: String): Result<Unit> = Result.success(Unit)
    }

    // BatteryHealthRepository is not open, so we stub via the DAO
    private class StubBatterySnapshotDao(
        private val lastValue: BatterySnapshotEntity?
    ) : com.bydmate.app.data.local.dao.BatterySnapshotDao {
        override fun getAll(): Flow<List<BatterySnapshotEntity>> = flowOf(emptyList())
        override suspend fun insert(snapshot: BatterySnapshotEntity): Long = 0
        override suspend fun getLast(): BatterySnapshotEntity? = lastValue
    }

    private val dao = FakeSettingsDao()
    private val settings = SettingsRepository(dao, mockk<LocalePreferences>(relaxed = true))

    private fun fakeBatteryHealth(last: BatterySnapshotEntity?): BatteryHealthRepository =
        BatteryHealthRepository(StubBatterySnapshotDao(last))

    @Test
    fun `state populated when autoservice available and snapshot present`() = runTest {
        val repo = BatteryStateRepository(
            FakeVehicleApi(BatteryReading(100f, 91f, 602.7f, 2091f, 14.0f, 0L)),
            fakeBatteryHealth(null),
            settings,
        )

        val state = repo.refresh()

        assertEquals(100.0f, state.sohPercent!!, 0.01f)
        assertEquals(91.0f, state.socNow!!, 0.01f)
        assertEquals(602.7f, state.lifetimeKwh!!, 0.01f)
        assertEquals(2091.0f, state.lifetimeKm!!, 0.01f)
        assertTrue(state.autoserviceAvailable)
    }

    @Test
    fun `autoserviceAvailable is false when client unreachable`() = runTest {
        val repo = BatteryStateRepository(
            FakeVehicleApi(battery = null, available = false),
            fakeBatteryHealth(null),
            settings,
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
            settings,
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
            settings,
        )

        val state = repo.refresh()

        assertEquals(98.7f, state.sohPercent!!, 0.01f)  // from snapshot
    }

    private fun repo(battery: BatteryReading?, available: Boolean = true, settings: SettingsRepository = this.settings) =
        BatteryStateRepository(FakeVehicleApi(battery, available), fakeBatteryHealth(null), settings)

    private fun reading(kwh: Float?) = BatteryReading(100f, 91f, kwh, 2091f, 14f, 0L)

    @Test
    fun `a live lifetime counter is remembered`() = runTest {
        val state = repo(reading(10963f)).refresh()

        assertEquals(10963f, state.lifetimeKwh!!, 0.01f)
        assertEquals(10963f, state.lifetimeKwhLastKnown!!, 0.01f)
        assertEquals(10963f, dao.map[SettingsRepository.KEY_BMS_LIFETIME_KWH_LAST]!!.toFloat(), 0.01f)
    }

    @Test
    fun `an absent counter gives the remembered one, the raw value stays null`() = runTest {
        repo(reading(10963f)).refresh()

        val state = repo(reading(null)).refresh()

        assertNull(state.lifetimeKwh)
        assertEquals(10963f, state.lifetimeKwhLastKnown!!, 0.01f)
        assertEquals(10963f, state.lifetimeKwhForCycles!!, 0.01f)
    }

    @Test
    fun `car asleep still has the remembered one`() = runTest {
        dao.map[SettingsRepository.KEY_BMS_LIFETIME_KWH_LAST] = "10963"

        val state = repo(battery = null, available = false).refresh()

        assertFalse(state.autoserviceAvailable)
        assertEquals(10963f, state.lifetimeKwhLastKnown!!, 0.01f)
    }

    @Test
    fun `snapshot null with the car available still has the remembered one`() = runTest {
        dao.map[SettingsRepository.KEY_BMS_LIFETIME_KWH_LAST] = "10963"

        val state = repo(battery = null, available = true).refresh()

        assertEquals(10963f, state.lifetimeKwhLastKnown!!, 0.01f)
    }

    @Test
    fun `a zero counter is never remembered and never overwrites`() = runTest {
        repo(reading(10963f)).refresh()
        val after = repo(reading(0f)).refresh()
        assertEquals(10963f, dao.map[SettingsRepository.KEY_BMS_LIFETIME_KWH_LAST]!!.toFloat(), 0.01f)
        assertEquals(10963f, after.lifetimeKwhForCycles!!, 0.01f)

        val freshDao = FakeSettingsDao()
        val fresh = repo(reading(0f), settings = SettingsRepository(freshDao, mockk<LocalePreferences>(relaxed = true))).refresh()
        assertNull(freshDao.map[SettingsRepository.KEY_BMS_LIFETIME_KWH_LAST])
        assertNull(fresh.lifetimeKwhLastKnown)
        assertNull(fresh.lifetimeKwhForCycles)
    }

    // A link-error word 0x0000FFFF read as a float is a positive denormal (~9e-41): never a counter.
    @Test
    fun `a link-error float is never remembered and never counts`() = runTest {
        repo(reading(10963f)).refresh()
        val after = repo(reading(Float.fromBits(0x0000FFFF))).refresh()
        assertEquals(10963f, dao.map[SettingsRepository.KEY_BMS_LIFETIME_KWH_LAST]!!.toFloat(), 0.01f)
        assertEquals(10963f, after.lifetimeKwhForCycles!!, 0.01f)
    }

    @Test
    fun `the remembered value survives a new repository`() = runTest {
        repo(reading(10963f)).refresh()

        val state = repo(battery = null, available = false).refresh()

        assertEquals(10963f, state.lifetimeKwhLastKnown!!, 0.01f)
    }

    @Test
    fun `a car that never reported the counter has nothing remembered`() = runTest {
        val state = repo(reading(null)).refresh()

        assertNull(state.lifetimeKwhLastKnown)
        assertNull(state.lifetimeKwhForCycles)
    }
}
