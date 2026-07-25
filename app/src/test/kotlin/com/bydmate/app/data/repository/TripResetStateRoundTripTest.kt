package com.bydmate.app.data.repository

import com.bydmate.app.data.local.entity.SettingEntity
import com.bydmate.app.data.local.dao.SettingsDao
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.trips.TripResetState
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Verifies that all 5 keys of TripResetState survive a get/set round-trip through
// SettingsRepository (including the new excludeStraddling / trip${n}_corr_excl key).
class TripResetStateRoundTripTest {

    private class FakeSettingsDao : SettingsDao {
        val map = mutableMapOf<String, String>()
        override suspend fun get(key: String): String? = map[key]
        override fun observe(key: String): Flow<String?> = flowOf(map[key])
        override suspend fun set(entity: SettingEntity) { map[entity.key] = entity.value ?: "" }
        override suspend fun setAll(settings: List<SettingEntity>) { settings.forEach { set(it) } }
        override fun getAll(): Flow<List<SettingEntity>> = flowOf(emptyList())
    }

    private fun repo() = SettingsRepository(FakeSettingsDao(), mockk<LocalePreferences>(relaxed = true))

    @Test fun `round-trip with excludeStraddling true persists all 5 keys`() = runTest {
        val r = repo()
        val state = TripResetState(
            resetTs = 1_000_000L,
            corrKm = 12.5,
            corrKwh = 2.3,
            corrMs = 45_000L,
            excludeStraddling = true,
        )
        r.setTripResetState(1, state)
        val loaded = r.getTripResetState(1)

        assertEquals(state.resetTs, loaded.resetTs)
        assertEquals(state.corrKm, loaded.corrKm, 0.0001)
        assertEquals(state.corrKwh, loaded.corrKwh, 0.0001)
        assertEquals(state.corrMs, loaded.corrMs)
        assertTrue(loaded.excludeStraddling)
    }

    @Test fun `round-trip with excludeStraddling false persists false`() = runTest {
        val r = repo()
        val state = TripResetState(resetTs = 2_000L, corrKm = 5.0, corrKwh = 1.0, corrMs = 1_000L)
        r.setTripResetState(2, state)
        val loaded = r.getTripResetState(2)
        assertFalse(loaded.excludeStraddling)
    }

    @Test fun `absent keys default to excludeStraddling false`() = runTest {
        val r = repo()
        // No setTripResetState call -- all keys are absent from the DAO.
        val loaded = r.getTripResetState(1)
        assertFalse(loaded.excludeStraddling)
        assertEquals(0L, loaded.resetTs)
    }
}
