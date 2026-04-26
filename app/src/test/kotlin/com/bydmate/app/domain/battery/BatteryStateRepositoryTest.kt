package com.bydmate.app.domain.battery

import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.autoservice.BatteryReading
import com.bydmate.app.data.autoservice.ChargingReading
import com.bydmate.app.data.local.entity.BatterySnapshotEntity
import com.bydmate.app.data.repository.BatteryHealthRepository
import com.bydmate.app.data.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryStateRepositoryTest {

    private class FakeAutoservice(
        private val battery: BatteryReading?,
        private val available: Boolean = true
    ) : AutoserviceClient {
        override suspend fun isAvailable(): Boolean = available
        override suspend fun getInt(dev: Int, fid: Int): Int? = null
        override suspend fun getFloat(dev: Int, fid: Int): Float? = null
        override suspend fun readBatterySnapshot(): BatteryReading? = battery
        override suspend fun readChargingSnapshot(): ChargingReading? = null
    }

    // Deviation 2: BatteryHealthRepository is not open, so we stub via the DAO
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

    // Deviation 1: SettingsRepository.isAutoserviceEnabled() is not open — back real
    // instance with a map-based SettingsDao so it reads the right value
    private fun fakeSettings(autoserviceEnabled: Boolean): SettingsRepository {
        val map = mutableMapOf<String, String>()
        map[SettingsRepository.KEY_AUTOSERVICE_ENABLED] = autoserviceEnabled.toString()
        val dao = object : com.bydmate.app.data.local.dao.SettingsDao {
            override suspend fun get(key: String): String? = map[key]
            override fun observe(key: String): Flow<String?> = flowOf(map[key])
            override suspend fun set(entity: com.bydmate.app.data.local.entity.SettingEntity) {
                map[entity.key] = entity.value ?: ""
            }
            override fun getAll(): Flow<List<com.bydmate.app.data.local.entity.SettingEntity>> = flowOf(emptyList())
        }
        return SettingsRepository(dao)
    }

    @Test
    fun `state has all autoservice fields null when toggle OFF`() = runTest {
        val repo = BatteryStateRepository(
            FakeAutoservice(BatteryReading(100f, 91f, 600f, 2091f, 14f, 0L)),
            fakeBatteryHealth(null),
            fakeSettings(autoserviceEnabled = false)
        )

        val state = repo.refresh()

        assertNull(state.sohPercent)
        assertNull(state.lifetimeKwh)
        assertNull(state.lifetimeKm)
        assertNull(state.voltage12v)
        assertFalse(state.autoserviceAvailable)
    }

    @Test
    fun `state populated when toggle ON and autoservice available`() = runTest {
        val repo = BatteryStateRepository(
            FakeAutoservice(BatteryReading(100f, 91f, 602.7f, 2091f, 14.0f, 0L)),
            fakeBatteryHealth(null),
            fakeSettings(autoserviceEnabled = true)
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
    fun `autoserviceAvailable is false when toggle ON but client unreachable`() = runTest {
        val repo = BatteryStateRepository(
            FakeAutoservice(battery = null, available = false),
            fakeBatteryHealth(null),
            fakeSettings(autoserviceEnabled = true)
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
            FakeAutoservice(BatteryReading(null, 91f, 602.7f, 2091f, 14f, 0L)),  // sohPercent sentinel
            fakeBatteryHealth(snap),
            fakeSettings(autoserviceEnabled = true)
        )

        val state = repo.refresh()

        assertEquals(98.7f, state.sohPercent!!, 0.01f)  // from snapshot
    }
}
