package com.bydmate.app.data.charging

import com.bydmate.app.data.local.entity.SettingEntity
import com.bydmate.app.data.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChargingStateStoreTest {

    private class FakeSettingsDao(initial: Map<String, String> = emptyMap()) :
        com.bydmate.app.data.local.dao.SettingsDao {
        private val map = mutableMapOf<String, String>().also { it.putAll(initial) }
        override suspend fun get(key: String): String? = map[key]
        override fun observe(key: String): Flow<String?> = flowOf(map[key])
        override suspend fun set(entity: SettingEntity) { map[entity.key] = entity.value ?: "" }
        override fun getAll(): Flow<List<SettingEntity>> = flowOf(emptyList())
    }

    private fun store(initial: Map<String, String> = emptyMap()): ChargingStateStore {
        val dao = FakeSettingsDao(initial)
        val settings = SettingsRepository(dao)
        return ChargingStateStore(settings)
    }

    @Test
    fun `load on empty settings returns State with all nulls and zero ts`() = runTest {
        val s = store()
        val state = s.load()
        assertNull(state.socPercent)
        assertNull(state.mileageKm)
        assertNull(state.capacityKwh)
        assertEquals(0L, state.ts)
    }

    @Test
    fun `save then load returns same values`() = runTest {
        val s = store()
        s.save(socPercent = 85, mileageKm = 10500.5f, capacityKwh = 7.8f, ts = 1700000000000L)
        val state = s.load()
        assertEquals(85, state.socPercent)
        assertEquals(10500.5f, state.mileageKm!!, 0.01f)
        assertEquals(7.8f, state.capacityKwh!!, 0.01f)
        assertEquals(1700000000000L, state.ts)
    }

    @Test
    fun `save with null socPercent does not overwrite existing soc`() = runTest {
        val initial = mapOf(SettingsRepository.KEY_CHARGING_BASELINE_SOC to "80")
        val s = store(initial)
        // Save with null soc — should not overwrite the pre-existing 80
        s.save(socPercent = null, mileageKm = 100f, capacityKwh = 5f, ts = 1000L)
        val state = s.load()
        assertEquals(80, state.socPercent)
    }

    @Test
    fun `baseline survives concurrent setLastSeenSoc writes`() = runTest {
        // Regression: in v2.4.17 ChargingStateStore read socPercent from
        // KEY_LAST_SEEN_SOC, which TrackingService overwrites every 3s with
        // the live SOC via recordLastSeenSoc → setLastSeenSoc. That clobbered
        // the cascade detector's pre-charging baseline. After v2.4.18 the
        // baseline lives on its own key.
        val dao = FakeSettingsDao()
        val settings = SettingsRepository(dao)
        val s = ChargingStateStore(settings)
        s.save(socPercent = 80, mileageKm = 100f, capacityKwh = 5f, ts = 1000L)
        // Simulate live polling overwriting last_seen_soc as SOC climbs.
        listOf(81, 82, 83, 84, 85).forEach { settings.setLastSeenSoc(it) }
        val state = s.load()
        assertEquals(80, state.socPercent)
    }

    @Test
    fun `save with null capacityKwh persists as null`() = runTest {
        val s = store()
        s.save(socPercent = 70, mileageKm = null, capacityKwh = null, ts = 2000L)
        val state = s.load()
        assertNull(state.mileageKm)
        assertNull(state.capacityKwh)
        assertEquals(2000L, state.ts)
    }
}
